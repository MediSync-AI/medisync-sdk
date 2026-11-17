// Cursor pagination of MediSync Connect lists. A list method answers a PagePromise: await it
// for one page, or iterate it with `for await` for every item of every page.

/** One page of a list: `data`, `next_cursor` and the other fields of the answer. */
export class CursorPage<Item> {
  readonly data: Item[];
  readonly next_cursor: string | null;
  readonly #next: (cursor: string) => Promise<CursorPage<Item>>;

  constructor(body: { data?: Item[]; next_cursor?: string | null }, next: (cursor: string) => Promise<CursorPage<Item>>) {
    Object.assign(this, body);
    this.data = body.data ?? [];
    this.next_cursor = body.next_cursor ?? null;
    this.#next = next;
  }

  /** True when `next_cursor` is set and this page has items. The event feed always sends a cursor; an empty page ends it. */
  hasNextPage(): boolean {
    return !!this.next_cursor && this.data.length > 0;
  }

  /** The next page. Throws when there is none; check `hasNextPage()` first. */
  getNextPage(): Promise<this> {
    if (!this.next_cursor) throw new Error('This is the last page.');
    return this.#next(this.next_cursor) as Promise<this>;
  }

  /** Every item from this page on, page by page. */
  async *[Symbol.asyncIterator](): AsyncGenerator<Item, void, undefined> {
    // eslint-disable-next-line @typescript-eslint/no-this-alias
    let page: CursorPage<Item> = this;
    for (;;) {
      yield* page.data;
      if (!page.hasNextPage()) return;
      page = await page.getNextPage();
    }
  }
}

/** A page with the typed fields of the list answer. */
export type Page<Item, Raw> = CursorPage<Item> & Omit<Raw, 'data' | 'next_cursor'>;

/**
 * The answer of a list method. `await` gives the first page; `for await` gives every item and
 * fetches the next page when it needs it. The first page loads on the first `then` or iteration.
 */
export class PagePromise<Item, Raw> extends Promise<Page<Item, Raw>> implements AsyncIterable<Item> {
  #first: Promise<Page<Item, Raw>> | null = null;
  readonly #load: () => Promise<Page<Item, Raw>>;

  constructor(load: () => Promise<Page<Item, Raw>>) {
    super((resolve) => resolve(null as never));
    this.#load = load;
  }

  // `then` of a subclass makes a new instance of the species; that must be a plain Promise.
  static override get [Symbol.species](): PromiseConstructor {
    return Promise;
  }

  #page(): Promise<Page<Item, Raw>> {
    this.#first ??= this.#load();
    return this.#first;
  }

  override then<A = Page<Item, Raw>, B = never>(
    onfulfilled?: ((value: Page<Item, Raw>) => A | PromiseLike<A>) | null,
    onrejected?: ((reason: unknown) => B | PromiseLike<B>) | null,
  ): Promise<A | B> {
    return this.#page().then(onfulfilled, onrejected);
  }

  override catch<B = never>(onrejected?: ((reason: unknown) => B | PromiseLike<B>) | null): Promise<Page<Item, Raw> | B> {
    return this.#page().catch(onrejected);
  }

  override finally(onfinally?: (() => void) | null): Promise<Page<Item, Raw>> {
    return this.#page().finally(onfinally);
  }

  async *[Symbol.asyncIterator](): AsyncGenerator<Item, void, undefined> {
    yield* await this.#page();
  }
}
