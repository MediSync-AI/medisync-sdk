package me.medisync.connect;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import me.medisync.Page;
import me.medisync.core.ClientCore;
import me.medisync.core.Json;
import me.medisync.core.Sleeper;
import me.medisync.models.connect.Event;
import me.medisync.models.connect.EventList;
import me.medisync.models.connect.EventsListQuery;
import me.medisync.resources.ConnectEventsServiceAsync;
import org.jspecify.annotations.Nullable;

/**
 * Polls <code>events.list</code> from a cursor on and gives every page that has events. Save
 * {@code page.getNextCursor()} after you handled a page and pass it as the cursor on the next
 * start, so no event is lost or read twice. {@link #close()} ends the iteration.
 *
 * <pre>{@code
 * try (EventPoller poller = client.connect().events().poll(new EventsListQuery().siteId(siteId))) {
 *   for (Page<Event, EventList> page : poller) { ... }
 * }
 * }</pre>
 */
public final class EventPoller implements Iterable<Page<Event, EventList>>, AutoCloseable {
  private final ConnectEventsServiceAsync events;
  private final @Nullable EventsListQuery query;
  private final PollOptions options;
  private volatile boolean closed;
  private final Object lock = new Object();

  /** Made by <code>connect.events.poll</code>. */
  public EventPoller(ConnectEventsServiceAsync events, @Nullable EventsListQuery query, PollOptions options) {
    this.events = events;
    this.query = query;
    this.options = options;
  }

  private static EventsListQuery withCursor(@Nullable EventsListQuery query, @Nullable String cursor) {
    EventsListQuery copy = query == null ? new EventsListQuery() : Json.MAPPER.convertValue(Json.MAPPER.valueToTree(query), EventsListQuery.class);
    if (cursor != null) copy.setCursor(cursor);
    return copy;
  }

  /** Ends the iteration; a pause in progress ends at once. */
  @Override
  public void close() {
    closed = true;
    synchronized (lock) {
      lock.notifyAll();
    }
  }

  @Override
  public Iterator<Page<Event, EventList>> iterator() {
    return new Iterator<Page<Event, EventList>>() {
      private @Nullable String cursor = query == null ? null : query.getCursor();
      private @Nullable Page<Event, EventList> next;

      @Override
      public boolean hasNext() {
        while (next == null && !closed) {
          Page<Event, EventList> page = ClientCore.join(events.list(withCursor(query, cursor)));
          if (page.getNextCursor() != null) cursor = page.getNextCursor();
          if (!page.getData().isEmpty()) {
            next = page;
            break;
          }
          synchronized (lock) {
            if (closed) break;
            try {
              lock.wait(Math.max(1, options.getInterval().toMillis()));
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              closed = true;
            }
          }
        }
        return next != null;
      }

      @Override
      public Page<Event, EventList> next() {
        if (!hasNext()) throw new NoSuchElementException();
        Page<Event, EventList> page = next;
        next = null;
        return page;
      }
    };
  }

  /**
   * The async form: calls onPage for every page with events until the returned future is cancelled
   * or a call fails.
   */
  public static CompletableFuture<Void> pollAsync(ConnectEventsServiceAsync events, @Nullable EventsListQuery query, PollOptions options, Consumer<Page<Event, EventList>> onPage, Sleeper sleeper) {
    CompletableFuture<Void> result = new CompletableFuture<>();
    step(events, query, query == null ? null : query.getCursor(), options, onPage, sleeper, result);
    return result;
  }

  private static void step(ConnectEventsServiceAsync events, @Nullable EventsListQuery query, @Nullable String cursor, PollOptions options, Consumer<Page<Event, EventList>> onPage, Sleeper sleeper, CompletableFuture<Void> result) {
    if (result.isDone()) return;
    events
        .list(withCursor(query, cursor))
        .whenComplete(
            (page, err) -> {
              if (result.isDone()) return;
              if (err != null) {
                result.completeExceptionally(ClientCore.unwrap(err));
                return;
              }
              String next = page.getNextCursor() != null ? page.getNextCursor() : cursor;
              if (!page.getData().isEmpty()) {
                try {
                  onPage.accept(page);
                } catch (RuntimeException e) {
                  result.completeExceptionally(e);
                  return;
                }
                step(events, query, next, options, onPage, sleeper, result);
                return;
              }
              sleeper.sleep(options.getInterval()).whenComplete((v, e) -> step(events, query, next, options, onPage, sleeper, result));
            });
  }
}
