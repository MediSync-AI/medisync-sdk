"""Cursor pagination of MediSync Connect lists.

A list method answers a page. Iterate the page for every item of every page, or read `data` and
`next_cursor` for one page. The async client answers an `AsyncPaginator`: await it for the first
page, or iterate it with `async for`.
"""

from __future__ import annotations

from typing import Any, AsyncIterator, Awaitable, Callable, Dict, Generator, Generic, Iterator, List, Optional, TypeVar, cast

from ._models import construct

T = TypeVar("T")

__all__ = ["SyncCursorPage", "AsyncCursorPage", "AsyncPaginator"]


class _Page(Generic[T]):
    data: List[T]
    next_cursor: Optional[str]
    response: Any
    """The whole list answer as its model, with the fields beside `data`."""

    def __init__(self, raw: Dict[str, Any], *, item_type: Any, list_type: Any) -> None:
        items = cast(List[Any], raw.get("data") or [])
        self.data = [construct(item_type, item) for item in items]
        cursor = raw.get("next_cursor")
        self.next_cursor = cursor if isinstance(cursor, str) and cursor else None
        self.response = construct(list_type, raw)

    def has_next_page(self) -> bool:
        """True when `next_cursor` is set and this page has items. The event feed always sends a cursor; an empty page ends it."""
        return self.next_cursor is not None and len(self.data) > 0

    def __repr__(self) -> str:
        return f"{self.__class__.__name__}(items={len(self.data)}, next_cursor={self.next_cursor!r})"


class SyncCursorPage(_Page[T]):
    """One page of a list. `for item in page` walks every item of this and the following pages."""

    def __init__(self, raw: Dict[str, Any], *, item_type: Any, list_type: Any, next_page: Callable[[str], SyncCursorPage[T]]) -> None:
        super().__init__(raw, item_type=item_type, list_type=list_type)
        self._next_page = next_page

    def get_next_page(self) -> SyncCursorPage[T]:
        """The next page. Raises when there is none; check `has_next_page()` first."""
        if self.next_cursor is None:
            raise StopIteration("This is the last page.")
        return self._next_page(self.next_cursor)

    def iter_pages(self) -> Iterator[SyncCursorPage[T]]:
        """This page and every page after it."""
        page: SyncCursorPage[T] = self
        while True:
            yield page
            if not page.has_next_page():
                return
            page = page.get_next_page()

    def __iter__(self) -> Iterator[T]:
        for page in self.iter_pages():
            yield from page.data


class AsyncCursorPage(_Page[T]):
    """One page of a list. `async for item in page` walks every item of this and the following pages."""

    def __init__(
        self, raw: Dict[str, Any], *, item_type: Any, list_type: Any, next_page: Callable[[str], Awaitable[AsyncCursorPage[T]]]
    ) -> None:
        super().__init__(raw, item_type=item_type, list_type=list_type)
        self._next_page = next_page

    async def get_next_page(self) -> AsyncCursorPage[T]:
        """The next page. Raises when there is none; check `has_next_page()` first."""
        if self.next_cursor is None:
            raise StopAsyncIteration("This is the last page.")
        return await self._next_page(self.next_cursor)

    async def iter_pages(self) -> AsyncIterator[AsyncCursorPage[T]]:
        """This page and every page after it."""
        page: AsyncCursorPage[T] = self
        while True:
            yield page
            if not page.has_next_page():
                return
            page = await page.get_next_page()

    async def __aiter__(self) -> AsyncIterator[T]:
        async for page in self.iter_pages():
            for item in page.data:
                yield item


class AsyncPaginator(Generic[T]):
    """The answer of an async list method: `await` gives the first page, `async for` every item."""

    def __init__(self, load: Callable[[], Awaitable[AsyncCursorPage[T]]]) -> None:
        self._load = load
        self._first: Optional[AsyncCursorPage[T]] = None

    async def _page(self) -> AsyncCursorPage[T]:
        if self._first is None:
            self._first = await self._load()
        return self._first

    def __await__(self) -> Generator[Any, None, AsyncCursorPage[T]]:
        return self._page().__await__()

    async def __aiter__(self) -> AsyncIterator[T]:
        page = await self._page()
        async for item in page:
            yield item
