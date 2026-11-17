package me.medisync.audio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import me.medisync.core.ClientCore;
import me.medisync.core.Json;
import me.medisync.errors.LiveStreamException;
import me.medisync.models.StreamConfig;
import me.medisync.models.StreamServerMessages;
import org.jspecify.annotations.Nullable;

/**
 * An open live stream (specs/asyncapi.yaml): one WebSocket per conversation. Send audio with
 * {@link #send}, read messages by iterating the stream, and end with {@link #finish()} to keep the
 * last transcripts, or {@link #close()} to drop them.
 *
 * <p>After a drop, or a fatal error that a new stream can continue, the stream connects again with
 * <code>timeline_offset_seconds</code> set to the last <code>segment.end</code> and sends the audio
 * after that point again. The iteration then gives a {@link StreamReconnecting} event.
 *
 * <pre>{@code
 * try (LiveStream stream = client.audio().stream().open()) {
 *   new Thread(() -> { for (StreamEvent e : stream) System.out.println(e); }).start();
 *   stream.send(pcm);
 *   stream.finish();
 * }
 * }</pre>
 */
public final class LiveStream implements Iterable<StreamEvent>, AutoCloseable {
  /** Fatal codes after which a new stream can continue the conversation. */
  public static final Set<String> CONTINUE_CODES =
      Collections.unmodifiableSet(new HashSet<>(Arrays.asList("overloaded", "unavailable", "service_unavailable", "worker_lost", "max_duration_reached", "session_conflict")));

  /** Close codes after which a new stream can continue: abnormal drop, going away, service failure, restart. */
  private static final Set<Integer> CONTINUE_CLOSE = new HashSet<>(Arrays.asList(1001, 1006, 1011, 1012));

  /** After end_utterance, the stream ends when nothing has arrived for this long. */
  private static final long SILENCE_MS = 3_000;

  private static final Object END = new Object();

  private static final class Frame {
    final double start;
    final double end;
    final byte[] data;

    Frame(double start, double end, byte[] data) {
      this.start = start;
      this.end = end;
      this.data = data;
    }
  }

  private static final class Finishing {
    final CompletableFuture<Void> future = new CompletableFuture<>();
    @Nullable Double ack;
    int timer;
  }

  private final ClientCore core;
  private final StreamOptions options;
  private final ObjectNode config;
  private final @Nullable Integer bytesPerSecond;
  /** Bytes of one sample of every channel: the step a resent frame may start at. */
  private final int sampleBytes;
  private final boolean reconnect;
  private final LinkedBlockingQueue<Object> queue = new LinkedBlockingQueue<>();

  // State, guarded by this.
  private @Nullable WebSocket ws;
  private CompletableFuture<?> sendChain = CompletableFuture.completedFuture(null);
  private int generation;
  private boolean live;
  private final Deque<Frame> frames = new ArrayDeque<>();
  private double sent;
  private double lastEnd;
  private boolean done;
  private @Nullable LiveStreamException failure;
  private boolean closing;
  private @Nullable Finishing finishing;
  private int attempts;
  private @Nullable String continueReason;
  private @Nullable CompletableFuture<Void> opened;
  private @Nullable List<String> terms;
  private volatile @Nullable String sessionId;

  private LiveStream(ClientCore core, StreamOptions options) {
    this.core = core;
    this.options = options;
    StreamConfig given = options.getConfig();
    ObjectNode c = given == null ? Json.MAPPER.createObjectNode() : (ObjectNode) Json.MAPPER.valueToTree(given);
    ObjectNode audio = Json.MAPPER.createObjectNode().put("encoding", "pcm_s16le").put("sample_rate", 16000).put("channels", 1);
    if (c.get("audio") instanceof ObjectNode) audio.setAll((ObjectNode) c.get("audio"));
    c.set("audio", audio);
    c.remove("type");
    this.config = c;
    boolean pcm = "pcm_s16le".equals(audio.path("encoding").asText());
    int channels = Math.max(1, audio.path("channels").asInt(1));
    this.bytesPerSecond = pcm ? audio.path("sample_rate").asInt(16000) * 2 * channels : null;
    this.sampleBytes = 2 * channels;
    this.reconnect = options.isReconnect() && bytesPerSecond != null && options.getToken() == null;
    this.sent = c.path("timeline_offset_seconds").asDouble(0);
    this.lastEnd = sent;
  }

  /** Opens a stream and completes when the server sent <code>session_ready</code>. */
  public static CompletableFuture<LiveStream> open(ClientCore core, StreamOptions options) {
    LiveStream stream = new LiveStream(core, options);
    CompletableFuture<Void> opened = new CompletableFuture<>();
    synchronized (stream) {
      stream.opened = opened;
    }
    stream.connect();
    return opened.thenApply(v -> stream);
  }

  /** The session of the current socket, from <code>session_ready</code>. */
  public @Nullable String getSessionId() {
    return sessionId;
  }

  /** The largest <code>segment.end</code> so far: everything before it is transcribed. */
  public synchronized double getTranscribedUntil() {
    return lastEnd;
  }

  /**
   * Sends one audio frame (at most 1 MiB), in the format of the config. Frames are kept for a
   * reconnect until a transcript covers them. Send at real-time speed.
   */
  public synchronized void send(byte[] audio) {
    if (done || closing) throw new LiveStreamException("closed", "The stream is closed.");
    byte[] data = audio.clone();
    Integer bps = bytesPerSecond;
    if (bps != null) {
      Frame frame = new Frame(sent, sent + (double) data.length / bps, data);
      sent = frame.end;
      frames.addLast(frame);
      double keep = sent - options.getBufferSeconds();
      while (!frames.isEmpty() && frames.peekFirst().end < keep) frames.removeFirst();
    }
    WebSocket socket = ws;
    if (live && socket != null) sendBinary(socket, data);
  }

  /** Replaces the wordbook of this stream. An empty list removes it. */
  public synchronized void setTerms(List<String> words) {
    terms = new ArrayList<>(words);
    ObjectNode message = Json.MAPPER.createObjectNode().put("type", "terms");
    message.set("terms", Json.MAPPER.valueToTree(terms));
    sendJson(message);
  }

  /** Flushes the audio the server holds. The server answers with <code>ack</code>. */
  public synchronized void endUtterance() {
    sendJson(Json.MAPPER.createObjectNode().put("type", "end_utterance"));
  }

  /**
   * Ends the stream and keeps its tail: sends <code>end_utterance</code>, waits for the transcripts
   * up to the ack's <code>processed_until</code> (or 3 s without a message), then closes.
   */
  public synchronized CompletableFuture<Void> finishAsync() {
    if (done) {
      CompletableFuture<Void> f = new CompletableFuture<>();
      if (failure != null) f.completeExceptionally(failure);
      else f.complete(null);
      return f;
    }
    if (finishing != null) return finishing.future;
    Finishing f = new Finishing();
    finishing = f;
    if (live) endUtterance();
    return f.future;
  }

  /** {@link #finishAsync()}, waiting. */
  public void finish() {
    ClientCore.join(finishAsync());
  }

  /** Ends the stream at once. Audio not yet transcribed is lost. */
  @Override
  public synchronized void close() {
    if (done) return;
    closing = true;
    sendJson(Json.MAPPER.createObjectNode().put("type", "close"));
    closeSocket();
    end(null);
  }

  /** Every event until the stream ends. Blocks for the next one. Throws the failure of the stream at its end. */
  @Override
  public Iterator<StreamEvent> iterator() {
    return new Iterator<StreamEvent>() {
      private @Nullable Object next;
      private boolean ended;

      @Override
      public boolean hasNext() {
        if (next != null) return true;
        if (ended) return false;
        Object item;
        try {
          item = queue.take();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new LiveStreamException("interrupted", "Interrupted while reading the stream.");
        }
        if (item == END) {
          ended = true;
          queue.offer(END);
          LiveStreamException f;
          synchronized (LiveStream.this) {
            f = failure;
          }
          if (f != null) throw f;
          return false;
        }
        next = item;
        return true;
      }

      @Override
      public StreamEvent next() {
        if (!hasNext()) throw new NoSuchElementException();
        StreamEvent e = (StreamEvent) next;
        next = null;
        return e;
      }
    };
  }

  // --- internals, called with the lock held unless noted ---

  private void push(StreamEvent event) {
    queue.offer(event);
  }

  private static StreamEvent typed(JsonNode message) {
    String type = message.path("type").asText();
    Class<? extends StreamEvent> cls = StreamServerMessages.forType(type);
    if (cls == null) return new UnknownStreamEvent(type, message);
    try {
      return Json.MAPPER.treeToValue(message, cls);
    } catch (Exception e) {
      return new UnknownStreamEvent(type, message);
    }
  }

  private void sendJson(ObjectNode message) {
    WebSocket socket = ws;
    if (live && socket != null) sendText(socket, message.toString());
  }

  private void sendText(WebSocket socket, String text) {
    sendChain = sendChain.thenCompose(x -> socket.sendText(text, true)).handle((x, e) -> socket);
  }

  private void sendBinary(WebSocket socket, byte[] data) {
    sendChain = sendChain.thenCompose(x -> socket.sendBinary(ByteBuffer.wrap(data), true)).handle((x, e) -> socket);
  }

  private void closeSocket() {
    WebSocket socket = ws;
    if (socket == null) return;
    sendChain = sendChain.thenCompose(x -> socket.sendClose(1000, "")).handle((x, e) -> socket);
  }

  private void end(@Nullable LiveStreamException error) {
    if (done) return;
    done = true;
    failure = error;
    live = false;
    Finishing f = finishing;
    if (f != null) {
      finishing = null;
      if (error != null) f.future.completeExceptionally(error);
      else f.future.complete(null);
    }
    CompletableFuture<Void> o = opened;
    if (o != null) {
      opened = null;
      if (error != null) o.completeExceptionally(error);
      else o.complete(null);
    }
    queue.offer(END);
  }

  private static final class Target {
    final URI uri;
    final @Nullable Map<String, String> headers;

    Target(URI uri, @Nullable Map<String, String> headers) {
      this.uri = uri;
      this.headers = headers;
    }
  }

  /** Called without the lock. */
  private CompletableFuture<Target> target() {
    String base = core.baseUrl().replaceFirst("^http", "ws");
    String token = options.getToken();
    Supplier<CompletableFuture<String>> supplier = options.getTokenSupplier();
    if (token != null) return CompletableFuture.completedFuture(new Target(URI.create(base + "/v1/audio/stream?token=" + ClientCore.encode(token)), null));
    if (supplier != null) return supplier.get().thenApply(t -> new Target(URI.create(base + "/v1/audio/stream?token=" + ClientCore.encode(t)), null));
    return core.authorization("platform")
        .thenApply(
            auth -> {
              Map<String, String> headers = core.baseHeaders();
              headers.put("authorization", auth);
              return new Target(URI.create(base + "/v1/audio/stream"), headers);
            });
  }

  /** Called without the lock. */
  private void connect() {
    target()
        .whenComplete(
            (t, err) -> {
              if (err != null) {
                RuntimeException e = ClientCore.unwrap(err);
                synchronized (this) {
                  end(e instanceof LiveStreamException ? (LiveStreamException) e : new LiveStreamException("handshake_failed", "Could not open the stream: " + e.getMessage()));
                }
                return;
              }
              int gen;
              double offset;
              synchronized (this) {
                if (done) return;
                gen = ++generation;
                live = false;
                continueReason = null;
                ws = null;
                offset = lastEnd;
              }
              WebSocket.Builder b = core.http().newWebSocketBuilder();
              if (t.headers != null) {
                for (Map.Entry<String, String> h : t.headers.entrySet()) {
                  try {
                    b.header(h.getKey(), h.getValue());
                  } catch (IllegalArgumentException restricted) {
                    // A header the WebSocket client sets itself.
                  }
                }
              }
              Listener listener = new Listener(gen, offset);
              b.buildAsync(t.uri, listener).whenComplete((socket, e) -> {
                if (e != null) listener.refused(ClientCore.unwrap(e));
              });
            });
  }

  private final class Listener implements WebSocket.Listener {
    private final int gen;
    private final double offset;
    private final StringBuilder text = new StringBuilder();
    private boolean isOpen;

    Listener(int gen, double offset) {
      this.gen = gen;
      this.offset = offset;
    }

    private boolean current() {
      return gen == generation && !done;
    }

    @Override
    public void onOpen(WebSocket socket) {
      synchronized (LiveStream.this) {
        isOpen = true;
        if (!current()) {
          socket.abort();
          return;
        }
        ws = socket;
        sendChain = CompletableFuture.completedFuture(socket);
        ObjectNode message = config.deepCopy();
        message.put("type", "config");
        message.put("timeline_offset_seconds", offset);
        sendText(socket, message.toString());
      }
      socket.request(1);
    }

    @Override
    public @Nullable CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
      text.append(data);
      if (last) {
        String message = text.toString();
        text.setLength(0);
        JsonNode json = null;
        try {
          json = Json.MAPPER.readTree(message);
        } catch (Exception notJson) {
          json = null;
        }
        if (json != null && json.isObject()) {
          synchronized (LiveStream.this) {
            if (current()) onMessage(socket, json, offset);
          }
        }
      }
      socket.request(1);
      return null;
    }

    @Override
    public @Nullable CompletionStage<?> onBinary(WebSocket socket, ByteBuffer data, boolean last) {
      socket.request(1);
      return null;
    }

    @Override
    public @Nullable CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
      synchronized (LiveStream.this) {
        if (current()) onSocketClose(statusCode, reason);
      }
      return null;
    }

    @Override
    public void onError(WebSocket socket, Throwable error) {
      synchronized (LiveStream.this) {
        if (current()) onSocketClose(1006, "");
      }
    }

    /** The handshake failed: an HTTP answer instead of the upgrade, or no connection. */
    void refused(Throwable error) {
      synchronized (LiveStream.this) {
        if (!current() || isOpen) return;
        if (sessionId == null && error instanceof WebSocketHandshakeException) {
          HttpResponse<?> response = ((WebSocketHandshakeException) error).getResponse();
          Object body = response.body();
          String text = body instanceof byte[] ? new String((byte[]) body, StandardCharsets.UTF_8) : body instanceof String ? (String) body : "";
          JsonNode e = null;
          try {
            e = text.isEmpty() ? null : Json.MAPPER.readTree(text).get("error");
          } catch (Exception notJson) {
            e = null;
          }
          if (e != null && e.path("code").isTextual()) {
            end(new LiveStreamException(e.get("code").asText(), e.path("message").asText("The stream did not open."), e.path("param").isTextual() ? e.get("param").asText() : null, e.path("request_id").isTextual() ? e.get("request_id").asText() : null, null));
            return;
          }
          end(new LiveStreamException("handshake_failed", "The stream did not open (HTTP " + response.statusCode() + "). Check the API key or stream token, the speech scope and the stream limit of the project."));
          return;
        }
        onSocketClose(1006, "");
      }
    }
  }

  private void onMessage(WebSocket socket, JsonNode message, double offset) {
    String type = message.path("type").asText();
    switch (type) {
      case "session_ready":
        {
          sessionId = message.path("session_id").asText(null);
          attempts = 0;
          List<String> t = terms;
          if (t != null) {
            ObjectNode m = Json.MAPPER.createObjectNode().put("type", "terms");
            m.set("terms", Json.MAPPER.valueToTree(t));
            sendText(socket, m.toString());
          }
          // The audio that has no transcript yet, from the offset on, then live audio.
          Integer bps = bytesPerSecond;
          if (bps != null) {
            for (Frame f : frames) {
              if (f.end <= offset) continue;
              int skip = (int) Math.max(0, Math.round((offset - f.start) * bps / sampleBytes)) * sampleBytes;
              sendBinary(socket, skip > 0 ? Arrays.copyOfRange(f.data, Math.min(skip, f.data.length), f.data.length) : f.data);
            }
          }
          live = true;
          Finishing f = finishing;
          if (f != null) {
            f.ack = null;
            endUtterance();
          }
          CompletableFuture<Void> o = opened;
          if (o != null) {
            opened = null;
            o.complete(null);
          }
          break;
        }
      case "transcript":
        {
          double end = message.path("segment").path("end").asDouble(0);
          if (end > lastEnd) lastEnd = end;
          while (!frames.isEmpty() && frames.peekFirst().end <= lastEnd) frames.removeFirst();
          Finishing f = finishing;
          if (f != null) {
            if (f.ack != null && end >= f.ack) {
              push(typed(message));
              completeFinish();
              return;
            }
            armSilence();
          }
          break;
        }
      case "ack":
        {
          Finishing f = finishing;
          if (f != null) {
            double until = message.path("processed_until").asDouble(0);
            f.ack = until;
            if (lastEnd >= until) {
              push(typed(message));
              completeFinish();
              return;
            }
            armSilence();
          }
          break;
        }
      case "error":
        if (message.path("fatal").asBoolean(false)) {
          String code = message.path("code").asText("error");
          if (reconnect && CONTINUE_CODES.contains(code)) {
            continueReason = code;
          } else {
            push(typed(message));
            end(new LiveStreamException(code, message.path("message").asText(code), message.path("param").isTextual() ? message.get("param").asText() : null, message.path("request_id").isTextual() ? message.get("request_id").asText() : null, null));
            closeSocket();
            return;
          }
        }
        break;
      default:
        break;
    }
    push(typed(message));
  }

  private void armSilence() {
    Finishing f = finishing;
    if (f == null) return;
    int timer = ++f.timer;
    CompletableFuture.delayedExecutor(SILENCE_MS, TimeUnit.MILLISECONDS)
        .execute(
            () -> {
              synchronized (this) {
                if (finishing == f && f.timer == timer) completeFinish();
              }
            });
  }

  private void completeFinish() {
    sendJson(Json.MAPPER.createObjectNode().put("type", "close"));
    closing = true;
    closeSocket();
    end(null);
  }

  private void onSocketClose(int code, String reason) {
    if (done) return;
    live = false;
    String why = continueReason != null ? continueReason : reason.isEmpty() ? String.valueOf(code) : reason;
    // Before the first session_ready a failure ends open(): a key, scope or stream limit problem
    // shows at once instead of after every reconnect attempt.
    boolean canContinue = reconnect && !closing && sessionId != null && (continueReason != null || CONTINUE_CLOSE.contains(code) || (code == 1008 && CONTINUE_CODES.contains(reason)));
    if (!canContinue) {
      if (code == 1000 || closing) end(null);
      else if (sessionId == null && reason.isEmpty())
        end(new LiveStreamException("handshake_failed", "The stream did not open (close " + code + "). Check the API key or stream token, the speech scope and the stream limit of the project.", null, null, code));
      else end(new LiveStreamException(reason.isEmpty() ? "closed" : reason, "The stream closed with " + code + (reason.isEmpty() ? "" : " " + reason) + ".", null, null, code));
      return;
    }
    attempts++;
    if (attempts > options.getMaxReconnects()) {
      end(new LiveStreamException("reconnect_failed", "The stream could not connect again after " + (attempts - 1) + " attempts (" + why + ").", null, null, code));
      return;
    }
    push(new StreamReconnecting(attempts, lastEnd, why));
    ws = null;
    long delay = Math.round(Math.min(1000 * Math.pow(2, attempts - 1), 30_000) * (0.5 + ThreadLocalRandom.current().nextDouble() / 2));
    CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS).execute(this::connect);
  }
}
