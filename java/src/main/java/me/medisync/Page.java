package me.medisync;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import me.medisync.core.ClientCore;
import org.jspecify.annotations.Nullable;

/**
 * One page of a MediSync Connect list: the items, the cursor of the next page and the whole
 * answer. {@link #autoPager()} walks every item of every page and loads the next page when it
 * needs it.
 *
 * <pre>{@code
 * for (Site site : client.connect().sites().list().autoPager()) { ... }
 * }</pre>
 *
 * @param <T> the item type
 * @param <L> the type of the list answer
 */
public final class Page<T, L> {
  private final List<T> data;
  private final @Nullable String nextCursor;
  private final L response;
  private final Function<String, CompletableFuture<Page<T, L>>> next;

  /** Made by the client. */
  public Page(List<T> data, @Nullable String nextCursor, L response, Function<String, CompletableFuture<Page<T, L>>> next) {
    this.data = Collections.unmodifiableList(new ArrayList<>(data));
    this.nextCursor = nextCursor;
    this.response = response;
    this.next = next;
  }

  /** The items of this page. */
  public List<T> getData() {
    return data;
  }

  /** The cursor of the next page, or null. */
  public @Nullable String getNextCursor() {
    return nextCursor;
  }

  /** The whole answer, with the other fields of the list (for example <code>export_range</code>). */
  public L getResponse() {
    return response;
  }

  /** True when there is a cursor and this page has items. The event feed always sends a cursor; an empty page ends it. */
  public boolean hasNextPage() {
    return nextCursor != null && !nextCursor.isEmpty() && !data.isEmpty();
  }

  /** The next page. Check {@link #hasNextPage()} first. */
  public CompletableFuture<Page<T, L>> nextPageAsync() {
    String cursor = nextCursor;
    if (cursor == null || cursor.isEmpty()) throw new NoSuchElementException("This is the last page.");
    return next.apply(cursor);
  }

  /** The next page. Check {@link #hasNextPage()} first. */
  public Page<T, L> nextPage() {
    return ClientCore.join(nextPageAsync());
  }

  /** Every item from this page on, page by page. Loads a page when the iteration reaches it. */
  public Iterable<T> autoPager() {
    return () ->
        new Iterator<T>() {
          private Page<T, L> page = Page.this;
          private int index;

          @Override
          public boolean hasNext() {
            while (index >= page.data.size()) {
              if (!page.hasNextPage()) return false;
              page = page.nextPage();
              index = 0;
            }
            return true;
          }

          @Override
          public T next() {
            if (!hasNext()) throw new NoSuchElementException();
            return page.data.get(index++);
          }
        };
  }

  /** {@link #autoPager()} as a stream. */
  public Stream<T> stream() {
    return StreamSupport.stream(Spliterators.spliteratorUnknownSize(autoPager().iterator(), Spliterator.ORDERED), false);
  }

  /** Calls the action for every item from this page on, without blocking a thread. */
  public CompletableFuture<Void> forEachAsync(Consumer<? super T> action) {
    try {
      data.forEach(action);
    } catch (RuntimeException e) {
      CompletableFuture<Void> failed = new CompletableFuture<>();
      failed.completeExceptionally(e);
      return failed;
    }
    if (!hasNextPage()) return CompletableFuture.completedFuture(null);
    return nextPageAsync().thenCompose(p -> p.forEachAsync(action));
  }
}
