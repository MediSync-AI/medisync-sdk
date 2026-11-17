"""Following a MediSync Connect transfer and the event feed."""

from __future__ import annotations

import asyncio
import threading
import time
from typing import AsyncIterator, Awaitable, Callable, Iterator, Optional

from .._exceptions import MediSyncError
from .._generated.connect_types import Event, Transfer
from .._pagination import AsyncCursorPage, SyncCursorPage

FINAL_TRANSFER_STATUSES = ("completed", "partially_completed", "failed", "expired")
"""A transfer in one of these states changes no more."""
MAX_INTERVAL = 10.0


def is_final_transfer_status(status: str) -> bool:
    """True for `completed`, `partially_completed`, `failed` and `expired`. An unknown status is not final."""
    return status in FINAL_TRANSFER_STATUSES


def default_until(transfer: Transfer) -> bool:
    """A final status, or `in_doubt` (a person must check it)."""
    return is_final_transfer_status(transfer.status) or transfer.status == "in_doubt"


class TransferWaitTimeoutError(MediSyncError):
    """`transfers.wait()` ran out of time. `transfer` is the last state it read."""

    def __init__(self, transfer: Transfer) -> None:
        super().__init__(f"Transfer {transfer.id} is still {transfer.status}.")
        self.transfer = transfer


def wait_for_transfer(
    get: Callable[[str], Transfer],
    transfer_id: str,
    *,
    until: Optional[Callable[[Transfer], bool]] = None,
    timeout: float = 120.0,
    interval: float = 1.0,
    on_update: Optional[Callable[[Transfer], None]] = None,
    sleep: Callable[[float], None] = time.sleep,
) -> Transfer:
    """Reads a transfer until `until` holds. The pause doubles up to 10 s.

    A transfer waits in the queue while the site's hub is offline, up to 24 hours: for long waits,
    follow `events.poll()` instead.
    """
    check = until or default_until
    deadline = time.monotonic() + timeout
    last: Optional[str] = None
    while True:
        transfer = get(transfer_id)
        if transfer.status != last:
            last = transfer.status
            if on_update:
                on_update(transfer)
        if check(transfer):
            return transfer
        left = deadline - time.monotonic()
        if left <= 0:
            raise TransferWaitTimeoutError(transfer)
        sleep(min(interval, left))
        interval = min(interval * 2, MAX_INTERVAL)


async def async_wait_for_transfer(
    get: Callable[[str], Awaitable[Transfer]],
    transfer_id: str,
    *,
    until: Optional[Callable[[Transfer], bool]] = None,
    timeout: float = 120.0,
    interval: float = 1.0,
    on_update: Optional[Callable[[Transfer], None]] = None,
) -> Transfer:
    """The async form of `wait_for_transfer`."""
    check = until or default_until
    deadline = time.monotonic() + timeout
    last: Optional[str] = None
    while True:
        transfer = await get(transfer_id)
        if transfer.status != last:
            last = transfer.status
            if on_update:
                on_update(transfer)
        if check(transfer):
            return transfer
        left = deadline - time.monotonic()
        if left <= 0:
            raise TransferWaitTimeoutError(transfer)
        await asyncio.sleep(min(interval, left))
        interval = min(interval * 2, MAX_INTERVAL)


def poll_events(
    list_page: Callable[[Optional[str]], SyncCursorPage[Event]],
    cursor: Optional[str],
    *,
    interval: float = 5.0,
    stop: Optional[threading.Event] = None,
) -> Iterator[SyncCursorPage[Event]]:
    """Polls `events.list` and yields every page that has events.

    Save `page.next_cursor` after you handled a page and pass it as `cursor` on the next start,
    so no event is lost or read twice. Set `stop` to end the loop.
    """
    while not (stop and stop.is_set()):
        page = list_page(cursor)
        if page.next_cursor:
            cursor = page.next_cursor
        if page.data:
            yield page
            continue
        if stop:
            if stop.wait(interval):
                return
        else:
            time.sleep(interval)


async def async_poll_events(
    list_page: Callable[[Optional[str]], Awaitable[AsyncCursorPage[Event]]],
    cursor: Optional[str],
    *,
    interval: float = 5.0,
    stop: Optional[asyncio.Event] = None,
) -> AsyncIterator[AsyncCursorPage[Event]]:
    """The async form of `poll_events`. Set `stop`, or cancel the task, to end the loop."""
    while not (stop and stop.is_set()):
        page = await list_page(cursor)
        if page.next_cursor:
            cursor = page.next_cursor
        if page.data:
            yield page
            continue
        if stop:
            try:
                await asyncio.wait_for(stop.wait(), interval)
                return
            except asyncio.TimeoutError:
                continue
        else:
            await asyncio.sleep(interval)
