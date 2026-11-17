"""Waiting for a batch transcription."""

from __future__ import annotations

import asyncio
import time
from typing import Awaitable, Callable, Optional

from .._exceptions import MediSyncError
from .._generated.platform_types import Transcription

FINAL_TRANSCRIPTION_STATUSES = ("completed", "failed", "expired")
"""A transcription in one of these states changes no more."""


class TranscriptionWaitTimeoutError(MediSyncError):
    """`audio.transcriptions.wait()` ran out of time. `transcription` is the last state it read."""

    def __init__(self, transcription: Transcription) -> None:
        super().__init__(f"Transcription {transcription.id} is still {transcription.status}.")
        self.transcription = transcription


def wait_for_transcription(
    get: Callable[[str], Transcription],
    id: str,
    *,
    timeout: float = 600.0,
    interval: float = 2.0,
    on_update: Optional[Callable[[Transcription], None]] = None,
    sleep: Callable[[float], None] = time.sleep,
) -> Transcription:
    """Reads a transcription until it is completed, failed or expired. A failed one is returned, not raised."""
    deadline = time.monotonic() + timeout
    last: Optional[str] = None
    while True:
        t = get(id)
        if t.status != last:
            last = t.status
            if on_update:
                on_update(t)
        if t.status in FINAL_TRANSCRIPTION_STATUSES:
            return t
        left = deadline - time.monotonic()
        if left <= 0:
            raise TranscriptionWaitTimeoutError(t)
        sleep(min(interval, left))
        interval = min(interval * 1.5, 10.0)


async def async_wait_for_transcription(
    get: Callable[[str], Awaitable[Transcription]],
    id: str,
    *,
    timeout: float = 600.0,
    interval: float = 2.0,
    on_update: Optional[Callable[[Transcription], None]] = None,
) -> Transcription:
    """The async form of `wait_for_transcription`."""
    deadline = time.monotonic() + timeout
    last: Optional[str] = None
    while True:
        t = await get(id)
        if t.status != last:
            last = t.status
            if on_update:
                on_update(t)
        if t.status in FINAL_TRANSCRIPTION_STATUSES:
            return t
        left = deadline - time.monotonic()
        if left <= 0:
            raise TranscriptionWaitTimeoutError(t)
        await asyncio.sleep(min(interval, left))
        interval = min(interval * 1.5, 10.0)
