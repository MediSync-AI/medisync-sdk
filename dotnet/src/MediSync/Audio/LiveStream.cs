using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net.WebSockets;
using System.Runtime.CompilerServices;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;
using MediSync.Core;
using MediSync.Models;

namespace MediSync;

/// <summary>
/// A message of the live stream: a server message (<c>StreamTranscript</c>, <c>StreamAck</c>, ...) or
/// <see cref="StreamReconnecting"/>, which the SDK sends. Switch on <see cref="Type"/>.
/// </summary>
public interface IStreamEvent
{
    /// <summary>The message type, for example <c>transcript</c>.</summary>
    string Type { get; }
}

/// <summary>Sent by the SDK, not the server: the stream lost its socket and connects again.</summary>
public sealed class StreamReconnecting : IStreamEvent
{
    internal StreamReconnecting(int attempt, double offsetSeconds, string reason)
    {
        Attempt = attempt;
        OffsetSeconds = offsetSeconds;
        Reason = reason;
    }

    /// <inheritdoc />
    public string Type => "reconnecting";

    /// <summary>1 for the first attempt after a drop.</summary>
    public int Attempt { get; }

    /// <summary>The <c>timeline_offset_seconds</c> of the new stream: the last <c>segment.end</c>.</summary>
    public double OffsetSeconds { get; }

    /// <summary>The error code or close code that ended the old stream.</summary>
    public string Reason { get; }
}

/// <summary>A server message of a type this SDK version does not know.</summary>
public sealed class UnknownStreamEvent : IStreamEvent
{
    internal UnknownStreamEvent(string type, JsonElement json)
    {
        Type = type;
        Json = json;
    }

    /// <inheritdoc />
    public string Type { get; }

    /// <summary>The whole message.</summary>
    public JsonElement Json { get; }
}

/// <summary>Options of <c>Audio.Stream.OpenAsync</c>.</summary>
public sealed class StreamOptions
{
    /// <summary>The config. Its <c>type</c> is set by the SDK. Default: 16 kHz mono pcm_s16le, language detected.</summary>
    public StreamConfig? Config { get; set; }

    /// <summary>A single-use stream token (<c>mst_…</c>) instead of the API key. A stream with one token cannot reconnect.</summary>
    public string? Token { get; set; }

    /// <summary>Gets a new stream token for each connection, so the stream can connect again after a drop.</summary>
    public Func<CancellationToken, Task<string>>? TokenProvider { get; set; }

    /// <summary>Connect again after a drop or a fatal error a new stream can continue. Default true for pcm_s16le.</summary>
    public bool Reconnect { get; set; } = true;

    /// <summary>Attempts in a row without a session_ready before the stream gives up. Default 5.</summary>
    public int MaxReconnects { get; set; } = 5;

    /// <summary>Seconds of sent audio kept for a reconnect. Older audio is dropped. Default 300.</summary>
    public double BufferSeconds { get; set; } = 300;
}

/// <summary>
/// An open live stream (specs/asyncapi.yaml): one WebSocket per conversation. Send audio with
/// <see cref="SendAsync"/>, read messages with <c>await foreach</c>, and end with
/// <see cref="FinishAsync"/> to keep the last transcripts, or <see cref="CloseAsync"/> to drop them.
/// After a drop, or a fatal error that a new stream can continue, the stream connects again with
/// <c>timeline_offset_seconds</c> set to the last <c>segment.end</c> and sends the audio after that
/// point again; the iteration then gives a <see cref="StreamReconnecting"/> event.
/// </summary>
public sealed class LiveStream : IAsyncEnumerable<IStreamEvent>, IAsyncDisposable
{
    /// <summary>Fatal codes after which a new stream can continue the conversation.</summary>
    public static readonly IReadOnlyCollection<string> ContinueCodes = new HashSet<string> { "overloaded", "unavailable", "service_unavailable", "worker_lost", "max_duration_reached", "session_conflict" };

    /// <summary>Close codes after which a new stream can continue: abnormal drop, going away, service failure, restart.</summary>
    private static readonly HashSet<int> ContinueClose = new() { 1001, 1006, 1011, 1012 };

    /// <summary>After end_utterance, the stream ends when nothing has arrived for this long.</summary>
    private static readonly TimeSpan Silence = TimeSpan.FromSeconds(3);

    private sealed class Frame
    {
        public double Start;
        public double End;
        public byte[] Data = Array.Empty<byte>();
    }

    private sealed class Finishing
    {
        public readonly TaskCompletionSource<bool> Done = new(TaskCreationOptions.RunContinuationsAsynchronously);
        public double? Ack;
        public int Timer;
    }

    private readonly ClientCore _core;
    private readonly StreamOptions _options;
    private readonly JsonObject _config;
    private readonly int? _bytesPerSecond;
    /// <summary>Bytes of one sample of every channel: the step a resent frame may start at.</summary>
    private readonly int _sampleBytes;
    private readonly bool _reconnect;
    private readonly SemaphoreSlim _gate = new(1, 1);
    private readonly ConcurrentQueue<IStreamEvent> _events = new();
    private readonly SemaphoreSlim _signal = new(0);

    // State, guarded by _gate.
    private ClientWebSocket? _ws;
    private int _generation;
    private bool _live;
    private readonly LinkedList<Frame> _frames = new();
    private double _sent;
    private double _lastEnd;
    private bool _done;
    private LiveStreamException? _failure;
    private bool _closing;
    private Finishing? _finishing;
    private int _attempts;
    private string? _continueReason;
    private TaskCompletionSource<bool>? _opened;
    private List<string>? _terms;

    private LiveStream(ClientCore core, StreamOptions options)
    {
        _core = core;
        _options = options;
        var config = options.Config is null ? new JsonObject() : (JsonNode.Parse(options.Config.ToJson()) as JsonObject ?? new JsonObject());
        var audio = new JsonObject { ["encoding"] = "pcm_s16le", ["sample_rate"] = 16000, ["channels"] = 1 };
        if (config["audio"] is JsonObject given)
        {
            foreach (var kv in given.ToList()) audio[kv.Key] = kv.Value?.DeepClone();
        }
        config["audio"] = audio;
        config.Remove("type");
        // Parsed again, so every number reads as a double.
        config = (JsonObject)JsonNode.Parse(config.ToJsonString())!;
        audio = (JsonObject)config["audio"]!;
        _config = config;
        var pcm = audio["encoding"]?.GetValue<string>() == "pcm_s16le";
        var channels = Math.Max(1, (int)(audio["channels"]?.GetValue<double>() ?? 1));
        _bytesPerSecond = pcm ? (int)(audio["sample_rate"]?.GetValue<double>() ?? 16000) * 2 * channels : null;
        _sampleBytes = 2 * channels;
        _reconnect = options.Reconnect && _bytesPerSecond is not null && options.Token is null;
        _sent = config["timeline_offset_seconds"]?.GetValue<double>() ?? 0;
        _lastEnd = _sent;
    }

    /// <summary>Opens a stream and completes when the server sent <c>session_ready</c>.</summary>
    internal static async Task<LiveStream> OpenAsync(ClientCore core, StreamOptions options, CancellationToken ct)
    {
        var stream = new LiveStream(core, options);
        var opened = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        stream._opened = opened;
        _ = stream.ConnectAsync();
        using (ct.Register(() => opened.TrySetCanceled()))
        {
            await opened.Task.ConfigureAwait(false);
        }
        return stream;
    }

    /// <summary>The session of the current socket, from <c>session_ready</c>.</summary>
    public string? SessionId { get; private set; }

    /// <summary>The largest <c>segment.end</c> so far: everything before it is transcribed.</summary>
    public double TranscribedUntil => Volatile.Read(ref _lastEnd);

    /// <summary>
    /// Sends one audio frame (at most 1 MiB), in the format of the config. Frames are kept for a
    /// reconnect until a transcript covers them. Send at real-time speed.
    /// </summary>
    public async Task SendAsync(byte[] audio, CancellationToken cancellationToken = default)
    {
        await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            if (_done || _closing) throw new LiveStreamException("closed", "The stream is closed.");
            var data = (byte[])audio.Clone();
            if (_bytesPerSecond is { } bps)
            {
                var frame = new Frame { Start = _sent, End = _sent + (double)data.Length / bps, Data = data };
                _sent = frame.End;
                _frames.AddLast(frame);
                var keep = _sent - _options.BufferSeconds;
                while (_frames.First is { } first && first.Value.End < keep) _frames.RemoveFirst();
            }
            if (_live && _ws is { } ws) await SendBinary(ws, data).ConfigureAwait(false);
        }
        finally
        {
            _gate.Release();
        }
    }

    /// <summary>Replaces the wordbook of this stream. An empty list removes it.</summary>
    public async Task SetTermsAsync(IEnumerable<string> terms, CancellationToken cancellationToken = default)
    {
        await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            _terms = terms.ToList();
            await SendJson(TermsMessage(_terms)).ConfigureAwait(false);
        }
        finally
        {
            _gate.Release();
        }
    }

    /// <summary>Flushes the audio the server holds. The server answers with <c>ack</c>.</summary>
    public async Task EndUtteranceAsync(CancellationToken cancellationToken = default)
    {
        await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            await SendJson(new JsonObject { ["type"] = "end_utterance" }).ConfigureAwait(false);
        }
        finally
        {
            _gate.Release();
        }
    }

    /// <summary>
    /// Ends the stream and keeps its tail: sends <c>end_utterance</c>, waits for the transcripts up to
    /// the ack's <c>processed_until</c> (or 3 s without a message), then closes.
    /// </summary>
    public async Task FinishAsync(CancellationToken cancellationToken = default)
    {
        Task task;
        await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            if (_done)
            {
                if (_failure is not null) throw _failure;
                return;
            }
            if (_finishing is null)
            {
                _finishing = new Finishing();
                if (_live) await SendJson(new JsonObject { ["type"] = "end_utterance" }).ConfigureAwait(false);
            }
            task = _finishing.Done.Task;
        }
        finally
        {
            _gate.Release();
        }
        using (cancellationToken.Register(() => { }))
        {
            await task.ConfigureAwait(false);
        }
    }

    /// <summary>Ends the stream at once. Audio not yet transcribed is lost.</summary>
    public async Task CloseAsync()
    {
        await _gate.WaitAsync().ConfigureAwait(false);
        try
        {
            if (_done) return;
            _closing = true;
            await SendJson(new JsonObject { ["type"] = "close" }).ConfigureAwait(false);
            await CloseSocket().ConfigureAwait(false);
            End(null);
        }
        finally
        {
            _gate.Release();
        }
    }

    /// <summary>Closes the stream.</summary>
    public async ValueTask DisposeAsync() => await CloseAsync().ConfigureAwait(false);

    /// <summary>Every event until the stream ends. Throws the failure of the stream at its end.</summary>
    public async IAsyncEnumerator<IStreamEvent> GetAsyncEnumerator(CancellationToken cancellationToken = default)
    {
        while (true)
        {
            await _signal.WaitAsync(cancellationToken).ConfigureAwait(false);
            if (_events.TryDequeue(out var e))
            {
                yield return e;
                continue;
            }
            // The end: keep the signal for other readers.
            _signal.Release();
            if (Volatile.Read(ref _failure) is { } failure) throw failure;
            yield break;
        }
    }

    // --- internals, called with _gate held unless noted ---

    private void Push(IStreamEvent e)
    {
        _events.Enqueue(e);
        _signal.Release();
    }

    private static IStreamEvent Typed(JsonElement message)
    {
        var type = message.TryGetProperty("type", out var t) && t.ValueKind == JsonValueKind.String ? t.GetString()! : "";
        var cls = StreamServerMessages.ForType(type);
        if (cls is null) return new UnknownStreamEvent(type, message.Clone());
        try
        {
            return (IStreamEvent)message.Deserialize(cls, Json.Options)!;
        }
        catch (JsonException)
        {
            return new UnknownStreamEvent(type, message.Clone());
        }
    }

    private static JsonObject TermsMessage(List<string> terms) => new() { ["type"] = "terms", ["terms"] = new JsonArray(terms.Select(x => (JsonNode)x!).ToArray()) };

    private async Task SendJson(JsonObject message)
    {
        if (_live && _ws is { } ws) await SendText(ws, message.ToJsonString()).ConfigureAwait(false);
    }

    private static async Task SendText(ClientWebSocket ws, string text)
    {
        try
        {
            await ws.SendAsync(new ArraySegment<byte>(Encoding.UTF8.GetBytes(text)), WebSocketMessageType.Text, true, CancellationToken.None).ConfigureAwait(false);
        }
        catch (Exception e) when (e is WebSocketException || e is ObjectDisposedException || e is InvalidOperationException)
        {
            // The socket closed: the receive loop sees it and decides.
        }
    }

    private static async Task SendBinary(ClientWebSocket ws, byte[] data)
    {
        try
        {
            await ws.SendAsync(new ArraySegment<byte>(data), WebSocketMessageType.Binary, true, CancellationToken.None).ConfigureAwait(false);
        }
        catch (Exception e) when (e is WebSocketException || e is ObjectDisposedException || e is InvalidOperationException)
        {
            // The socket closed: the receive loop sees it and decides.
        }
    }

    private async Task CloseSocket()
    {
        if (_ws is not { } ws) return;
        try
        {
            if (ws.State == WebSocketState.Open) await ws.CloseOutputAsync(WebSocketCloseStatus.NormalClosure, "", CancellationToken.None).ConfigureAwait(false);
        }
        catch (Exception e) when (e is WebSocketException || e is ObjectDisposedException || e is InvalidOperationException)
        {
            // Already closed.
        }
    }

    private void End(LiveStreamException? error)
    {
        if (_done) return;
        _done = true;
        Volatile.Write(ref _failure, error);
        _live = false;
        if (_finishing is { } f)
        {
            _finishing = null;
            if (error is not null) f.Done.TrySetException(error);
            else f.Done.TrySetResult(true);
        }
        if (_opened is { } o)
        {
            _opened = null;
            if (error is not null) o.TrySetException(error);
            else o.TrySetResult(true);
        }
        _signal.Release();
    }

    /// <summary>Called without _gate.</summary>
    private async Task<(Uri, Dictionary<string, string>?)> TargetAsync()
    {
        var b = _core.BaseUrl;
        var baseWs = b.StartsWith("http", StringComparison.Ordinal) ? "ws" + b.Substring(4) : b;
        if (_options.Token is { } token) return (new Uri($"{baseWs}/v1/audio/stream?token={Uri.EscapeDataString(token)}"), null);
        if (_options.TokenProvider is { } provider) return (new Uri($"{baseWs}/v1/audio/stream?token={Uri.EscapeDataString(await provider(CancellationToken.None).ConfigureAwait(false))}"), null);
        var headers = _core.BaseHeaders();
        headers["authorization"] = await _core.AuthorizationAsync("platform", CancellationToken.None).ConfigureAwait(false);
        return (new Uri($"{baseWs}/v1/audio/stream"), headers);
    }

    /// <summary>Called without _gate.</summary>
    private async Task ConnectAsync()
    {
        Uri uri;
        Dictionary<string, string>? headers;
        try
        {
            (uri, headers) = await TargetAsync().ConfigureAwait(false);
        }
        catch (Exception e)
        {
            await _gate.WaitAsync().ConfigureAwait(false);
            try
            {
                End(e as LiveStreamException ?? new LiveStreamException("handshake_failed", $"Could not open the stream: {e.Message}"));
            }
            finally
            {
                _gate.Release();
            }
            return;
        }
        int gen;
        double offset;
        await _gate.WaitAsync().ConfigureAwait(false);
        try
        {
            if (_done) return;
            gen = ++_generation;
            _live = false;
            _continueReason = null;
            _ws = null;
            offset = _lastEnd;
        }
        finally
        {
            _gate.Release();
        }
        var ws = new ClientWebSocket();
        if (headers is not null)
        {
            foreach (var kv in headers)
            {
                try
                {
                    ws.Options.SetRequestHeader(kv.Key, kv.Value);
                }
                catch (ArgumentException)
                {
                    // A header the WebSocket client sets itself.
                }
            }
        }
#if NET8_0_OR_GREATER
        ws.Options.CollectHttpResponseDetails = true;
#endif
        try
        {
            await ws.ConnectAsync(uri, CancellationToken.None).ConfigureAwait(false);
        }
        catch (Exception e) when (e is WebSocketException || e is System.Net.Http.HttpRequestException || e is IOException || e is OperationCanceledException)
        {
            await _gate.WaitAsync().ConfigureAwait(false);
            try
            {
                if (gen != _generation || _done) return;
                if (SessionId is null)
                {
                    var status = "";
#if NET8_0_OR_GREATER
                    if (ws.HttpStatusCode != 0) status = $" (HTTP {(int)ws.HttpStatusCode})";
#endif
                    End(new LiveStreamException("handshake_failed", $"The stream did not open{status}. Check the API key or stream token, the speech scope and the stream limit of the project."));
                    return;
                }
                await OnSocketClose(1006, "").ConfigureAwait(false);
            }
            finally
            {
                _gate.Release();
            }
            ws.Dispose();
            return;
        }
        await _gate.WaitAsync().ConfigureAwait(false);
        try
        {
            if (gen != _generation || _done)
            {
                ws.Abort();
                ws.Dispose();
                return;
            }
            _ws = ws;
            var message = (JsonObject)_config.DeepClone();
            message["type"] = "config";
            message["timeline_offset_seconds"] = offset;
            await SendText(ws, message.ToJsonString()).ConfigureAwait(false);
        }
        finally
        {
            _gate.Release();
        }
        _ = ReceiveLoopAsync(ws, gen, offset);
    }

    /// <summary>Called without _gate.</summary>
    private async Task ReceiveLoopAsync(ClientWebSocket ws, int gen, double offset)
    {
        var buffer = new byte[64 * 1024];
        var text = new MemoryStream();
        int closeCode = 1006;
        var reason = "";
        try
        {
            while (true)
            {
                var result = await ws.ReceiveAsync(new ArraySegment<byte>(buffer), CancellationToken.None).ConfigureAwait(false);
                if (result.MessageType == WebSocketMessageType.Close)
                {
                    closeCode = (int)(result.CloseStatus ?? (WebSocketCloseStatus)1005);
                    reason = result.CloseStatusDescription ?? "";
                    break;
                }
                if (result.MessageType != WebSocketMessageType.Text) continue;
                text.Write(buffer, 0, result.Count);
                if (!result.EndOfMessage) continue;
                var bytes = text.ToArray();
                text.SetLength(0);
                JsonElement json;
                try
                {
                    json = JsonDocument.Parse(bytes).RootElement.Clone();
                }
                catch (JsonException)
                {
                    continue;
                }
                if (json.ValueKind != JsonValueKind.Object) continue;
                await _gate.WaitAsync().ConfigureAwait(false);
                try
                {
                    if (gen == _generation && !_done) await OnMessage(ws, json, offset).ConfigureAwait(false);
                }
                finally
                {
                    _gate.Release();
                }
            }
        }
        catch (Exception e) when (e is WebSocketException || e is IOException || e is ObjectDisposedException || e is OperationCanceledException)
        {
            closeCode = 1006;
        }
        await _gate.WaitAsync().ConfigureAwait(false);
        try
        {
            if (gen == _generation && !_done) await OnSocketClose(closeCode, reason).ConfigureAwait(false);
        }
        finally
        {
            _gate.Release();
        }
        try
        {
            if (ws.State == WebSocketState.CloseReceived) await ws.CloseOutputAsync(WebSocketCloseStatus.NormalClosure, "", CancellationToken.None).ConfigureAwait(false);
        }
        catch (Exception e) when (e is WebSocketException || e is ObjectDisposedException || e is InvalidOperationException)
        {
            // Already closed.
        }
        ws.Dispose();
    }

    private static string? Str(JsonElement e, string name) => e.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() : null;

    private static double Num(JsonElement e, string name) => e.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.Number ? v.GetDouble() : 0;

    private async Task OnMessage(ClientWebSocket ws, JsonElement message, double offset)
    {
        switch (Str(message, "type"))
        {
            case "session_ready":
            {
                SessionId = Str(message, "session_id");
                _attempts = 0;
                if (_terms is not null) await SendText(ws, TermsMessage(_terms).ToJsonString()).ConfigureAwait(false);
                // The audio that has no transcript yet, from the offset on, then live audio.
                if (_bytesPerSecond is { } bps)
                {
                    foreach (var f in _frames)
                    {
                        if (f.End <= offset) continue;
                        var skip = (int)Math.Max(0, Math.Round((offset - f.Start) * bps / _sampleBytes)) * _sampleBytes;
                        await SendBinary(ws, skip > 0 ? f.Data.Skip(Math.Min(skip, f.Data.Length)).ToArray() : f.Data).ConfigureAwait(false);
                    }
                }
                _live = true;
                if (_finishing is { } fin)
                {
                    fin.Ack = null;
                    await SendJson(new JsonObject { ["type"] = "end_utterance" }).ConfigureAwait(false);
                }
                if (_opened is { } o)
                {
                    _opened = null;
                    o.TrySetResult(true);
                }
                break;
            }
            case "transcript":
            {
                var end = message.TryGetProperty("segment", out var seg) && seg.ValueKind == JsonValueKind.Object ? Num(seg, "end") : 0;
                if (end > _lastEnd) Volatile.Write(ref _lastEnd, end);
                while (_frames.First is { } first && first.Value.End <= _lastEnd) _frames.RemoveFirst();
                if (_finishing is { } fin)
                {
                    if (fin.Ack is { } ack && end >= ack)
                    {
                        Push(Typed(message));
                        await CompleteFinish().ConfigureAwait(false);
                        return;
                    }
                    ArmSilence();
                }
                break;
            }
            case "ack":
                if (_finishing is { } finAck)
                {
                    var until = Num(message, "processed_until");
                    finAck.Ack = until;
                    if (_lastEnd >= until)
                    {
                        Push(Typed(message));
                        await CompleteFinish().ConfigureAwait(false);
                        return;
                    }
                    ArmSilence();
                }
                break;
            case "error":
                if (message.TryGetProperty("fatal", out var fatal) && fatal.ValueKind == JsonValueKind.True)
                {
                    var code = Str(message, "code") ?? "error";
                    if (_reconnect && ContinueCodes.Contains(code))
                    {
                        _continueReason = code;
                    }
                    else
                    {
                        Push(Typed(message));
                        End(new LiveStreamException(code, Str(message, "message") ?? code, Str(message, "param"), Str(message, "request_id")));
                        await CloseSocket().ConfigureAwait(false);
                        return;
                    }
                }
                break;
        }
        Push(Typed(message));
    }

    private void ArmSilence()
    {
        if (_finishing is not { } f) return;
        var timer = ++f.Timer;
        _ = Task.Delay(Silence).ContinueWith(async _ =>
        {
            await _gate.WaitAsync().ConfigureAwait(false);
            try
            {
                if (_finishing == f && f.Timer == timer) await CompleteFinish().ConfigureAwait(false);
            }
            finally
            {
                _gate.Release();
            }
        }, TaskScheduler.Default);
    }

    private async Task CompleteFinish()
    {
        await SendJson(new JsonObject { ["type"] = "close" }).ConfigureAwait(false);
        _closing = true;
        await CloseSocket().ConfigureAwait(false);
        End(null);
    }

    private Task OnSocketClose(int code, string reason)
    {
        if (_done) return Task.CompletedTask;
        _live = false;
        var why = _continueReason ?? (reason.Length == 0 ? code.ToString(System.Globalization.CultureInfo.InvariantCulture) : reason);
        // Before the first session_ready a failure ends open(): a key, scope or stream limit problem
        // shows at once instead of after every reconnect attempt.
        var canContinue = _reconnect && !_closing && SessionId is not null && (_continueReason is not null || ContinueClose.Contains(code) || (code == 1008 && ContinueCodes.Contains(reason)));
        if (!canContinue)
        {
            if (code == 1000 || _closing) End(null);
            else if (SessionId is null && reason.Length == 0) End(new LiveStreamException("handshake_failed", $"The stream did not open (close {code}). Check the API key or stream token, the speech scope and the stream limit of the project.", closeCode: code));
            else End(new LiveStreamException(reason.Length == 0 ? "closed" : reason, $"The stream closed with {code}{(reason.Length == 0 ? "" : " " + reason)}.", closeCode: code));
            return Task.CompletedTask;
        }
        _attempts++;
        if (_attempts > _options.MaxReconnects)
        {
            End(new LiveStreamException("reconnect_failed", $"The stream could not connect again after {_attempts - 1} attempts ({why}).", closeCode: code));
            return Task.CompletedTask;
        }
        Push(new StreamReconnecting(_attempts, _lastEnd, why));
        _ws = null;
        var delay = Math.Min(1000 * Math.Pow(2, _attempts - 1), 30_000) * (0.5 + new Random().NextDouble() / 2);
        _ = Task.Delay(TimeSpan.FromMilliseconds(delay)).ContinueWith(_ => ConnectAsync(), TaskScheduler.Default);
        return Task.CompletedTask;
    }
}
