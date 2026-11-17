using System;
using System.Collections.Generic;
using System.Runtime.CompilerServices;
using System.Threading;
using System.Threading.Tasks;

namespace MediSync;

/// <summary>
/// One page of a MediSync Connect list: the items, the cursor of the next page and the whole answer.
/// </summary>
/// <typeparam name="T">The item type.</typeparam>
/// <typeparam name="TList">The type of the list answer.</typeparam>
public sealed class Page<T, TList>
{
    private readonly Func<string, CancellationToken, Task<Page<T, TList>>> _next;

    internal Page(IReadOnlyList<T> data, string? nextCursor, TList response, Func<string, CancellationToken, Task<Page<T, TList>>> next)
    {
        Data = data;
        NextCursor = nextCursor;
        Response = response;
        _next = next;
    }

    /// <summary>The items of this page.</summary>
    public IReadOnlyList<T> Data { get; }

    /// <summary>The cursor of the next page, or null.</summary>
    public string? NextCursor { get; }

    /// <summary>The whole answer, with the other fields of the list (for example <c>export_range</c>).</summary>
    public TList Response { get; }

    /// <summary>True when there is a cursor and this page has items. The event feed always sends a cursor; an empty page ends it.</summary>
    public bool HasNextPage => !string.IsNullOrEmpty(NextCursor) && Data.Count > 0;

    /// <summary>The next page. Check <see cref="HasNextPage"/> first.</summary>
    public Task<Page<T, TList>> GetNextPageAsync(CancellationToken cancellationToken = default)
    {
        if (string.IsNullOrEmpty(NextCursor)) throw new InvalidOperationException("This is the last page.");
        return _next(NextCursor!, cancellationToken);
    }

    /// <summary>Every item from this page on, page by page. Loads a page when the iteration reaches it.</summary>
    public async IAsyncEnumerable<T> AutoPagingAsync([EnumeratorCancellation] CancellationToken cancellationToken = default)
    {
        var page = this;
        while (true)
        {
            foreach (var item in page.Data) yield return item;
            if (!page.HasNextPage) yield break;
            page = await page.GetNextPageAsync(cancellationToken).ConfigureAwait(false);
        }
    }
}

/// <summary>
/// The answer of a list method. <c>await</c> gives the first page; <c>await foreach</c> gives every
/// item and loads the next page when it needs it. The first page loads on the first await or iteration.
/// </summary>
/// <typeparam name="T">The item type.</typeparam>
/// <typeparam name="TList">The type of the list answer.</typeparam>
public sealed class PageTask<T, TList> : IAsyncEnumerable<T>
{
    private readonly Func<Task<Page<T, TList>>> _load;
    private Task<Page<T, TList>>? _first;
    private readonly object _lock = new();

    internal PageTask(Func<Task<Page<T, TList>>> load) => _load = load;

    /// <summary>The first page as a task.</summary>
    public Task<Page<T, TList>> AsTask()
    {
        lock (_lock) return _first ??= _load();
    }

    /// <summary>Lets <c>await</c> give the first page.</summary>
    public TaskAwaiter<Page<T, TList>> GetAwaiter() => AsTask().GetAwaiter();

    /// <summary>Configures the await of the first page.</summary>
    public ConfiguredTaskAwaitable<Page<T, TList>> ConfigureAwait(bool continueOnCapturedContext) => AsTask().ConfigureAwait(continueOnCapturedContext);

    /// <summary>Every item of every page.</summary>
    public async IAsyncEnumerator<T> GetAsyncEnumerator(CancellationToken cancellationToken = default)
    {
        var page = await AsTask().ConfigureAwait(false);
        await foreach (var item in page.AutoPagingAsync(cancellationToken).ConfigureAwait(false)) yield return item;
    }
}
