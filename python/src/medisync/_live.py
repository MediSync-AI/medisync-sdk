"""The live speech stream (specs/asyncapi.yaml): one WebSocket per conversation.

The client sends a config, then audio frames; the server sends `session_ready`, then one final
transcript per segment. After a drop, or a fatal error that a new stream can continue, the stream
connects again with `timeline_offset_seconds` set to the last `segment.end` and sends the audio
after that point again, as the "Errors and reconnect" page of the docs describes.
"""

from __future__ import annotations

import asyncio
import json
import queue
import random
import threading
from typing import TYPE_CHECKING, Any, AsyncIterator, Dict, Iterator, List, Mapping, Optional, Tuple, Union, cast

from ._exceptions import MediSyncError
from ._generated.live_types import (
    StreamAck,
    StreamBackpressure,
    StreamError,
    StreamLanguage,
    StreamSessionReady,
    StreamSpeakerRoles,
    StreamStatus,
    StreamTranscript,
)
from ._models import BaseModel, construct

if TYPE_CHECKING:
    from ._core import AsyncCore, SyncCore

__all__ = ["LiveStream", "AsyncLiveStream", "LiveStreamError", "StreamReconnecting", "StreamEvent", "CONTINUE_CODES"]

CONTINUE_CODES = frozenset({"overloaded", "unavailable", "service_unavailable", "worker_lost", "max_duration_reached", "session_conflict"})
"""Fatal codes after which a new stream can continue the conversation."""
_CONTINUE_CLOSE = frozenset({1001, 1006, 1011, 1012})
_SILENCE = 3.0
"""After `end_utterance`, the stream ends when nothing has arrived for this many seconds."""

_MESSAGES: Dict[str, Any] = {
    "session_ready": StreamSessionReady,
    "transcript": StreamTranscript,
    "ack": StreamAck,
    "language": StreamLanguage,
    "speaker_roles": StreamSpeakerRoles,
    "backpressure": StreamBackpressure,
    "status": StreamStatus,
    "error": StreamError,
}


class LiveStreamError(MediSyncError):
    """A fatal stream error, a refused handshake or a close the stream cannot continue after."""

    def __init__(
        self,
        code: str,
        message: str,
        *,
        param: Optional[str] = None,
        request_id: Optional[str] = None,
        close_code: Optional[int] = None,
        status: Optional[int] = None,
    ) -> None:
        super().__init__(message)
        self.code = code
        """The error code, for example `config_invalid`, `invalid_api_key`, `handshake_failed` or `closed`."""
        self.param = param
        self.request_id = request_id
        self.close_code = close_code
        """The WebSocket close code, when the socket closed."""
        self.status = status
        """The HTTP status of a refused handshake."""


class StreamReconnecting(BaseModel):
    """Sent by the SDK, not the server: the stream lost its socket and connects again."""

    type: str = "reconnecting"
    attempt: int
    """1 for the first attempt after a drop."""
    offset_seconds: float
    """The `timeline_offset_seconds` of the new stream: the last `segment.end`."""
    reason: str
    """The error code or close code that ended the old stream."""


StreamEvent = Union[
    StreamSessionReady,
    StreamTranscript,
    StreamAck,
    StreamLanguage,
    StreamSpeakerRoles,
    StreamBackpressure,
    StreamStatus,
    StreamError,
    StreamReconnecting,
]


def _parse(raw: Union[str, bytes]) -> Optional[Any]:
    if not isinstance(raw, str):
        return None
    try:
        data = json.loads(raw)
    except ValueError:
        return None
    if not isinstance(data, dict):
        return None
    message = cast(Dict[str, Any], data)
    model = _MESSAGES.get(str(message.get("type")))
    return construct(model, message) if model is not None else None


class _Timeline:
    """The audio sent so far, on one timeline, until a transcript covers it."""

    def __init__(self, config: Optional[Mapping[str, Any]], reconnect: bool, buffer_seconds: float) -> None:
        cfg: Dict[str, Any] = dict(config or {})
        audio = {"encoding": "pcm_s16le", "sample_rate": 16000, "channels": 1, **cast(Dict[str, Any], cfg.get("audio") or {})}
        cfg["audio"] = audio
        cfg.pop("type", None)
        self.config = cfg
        pcm = audio["encoding"] == "pcm_s16le"
        self.bytes_per_second: Optional[int] = int(audio["sample_rate"]) * 2 * int(audio["channels"]) if pcm else None
        self.sample_bytes = 2 * int(audio["channels"])
        self.reconnect = reconnect and pcm
        self.buffer_seconds = buffer_seconds
        self.sent = float(cfg.get("timeline_offset_seconds") or 0.0)
        self.last_end = self.sent
        self.frames: List[Tuple[float, float, bytes]] = []
        self.terms: Optional[List[str]] = None
        """The wordbook set with set_terms(), sent again on a new socket."""

    def config_message(self, offset: float) -> str:
        return json.dumps({**self.config, "type": "config", "timeline_offset_seconds": offset})

    def add(self, data: bytes) -> None:
        if self.bytes_per_second is None:
            return
        start = self.sent
        self.sent += len(data) / self.bytes_per_second
        self.frames.append((start, self.sent, data))
        keep = self.sent - self.buffer_seconds
        while self.frames and self.frames[0][1] < keep:
            self.frames.pop(0)

    def covered(self, end: float) -> None:
        if end > self.last_end:
            self.last_end = end
        while self.frames and self.frames[0][1] <= self.last_end:
            self.frames.pop(0)

    def piece(self, index: int, offset: float) -> Optional[bytes]:
        """Frame `index` from `offset` on, or None when the frame ends before it."""
        start, end, data = self.frames[index]
        if end <= offset or self.bytes_per_second is None:
            return None
        skip = max(0, round((offset - start) * self.bytes_per_second / self.sample_bytes)) * self.sample_bytes
        return data[skip:] if skip > 0 else data

    def can_continue(self, code: int, reason: str, continue_reason: Optional[str]) -> bool:
        return self.reconnect and (continue_reason is not None or code in _CONTINUE_CLOSE or (code == 1008 and reason in CONTINUE_CODES))


def _ws_target(client: Union[SyncCore, AsyncCore], token: Optional[str]) -> Tuple[str, Dict[str, str]]:
    base = client.base_url.replace("https://", "wss://", 1).replace("http://", "ws://", 1)
    headers = {"medisync-version": client.version, **client._default_headers}
    if token:
        return f"{base}/v1/audio/stream?token={token}", headers
    return f"{base}/v1/audio/stream", {**headers, "authorization": client._api_key_authorization()}


def _refused(e: Exception) -> LiveStreamError:
    """A refused handshake: the HTTP status and the usual error body."""
    response = getattr(e, "response", None)
    status = getattr(response, "status_code", None)
    body = getattr(response, "body", None) or b""
    headers = getattr(response, "headers", None)
    request_id = headers.get("request-id") if headers is not None else None
    code, message = "handshake_failed", f"The stream did not open: {e}"
    param: Optional[str] = None
    try:
        error = cast(Dict[str, Any], cast(Dict[str, Any], json.loads(body)).get("error") or {})
        code = str(error.get("code") or code)
        message = str(error.get("message") or message)
        param = cast(Optional[str], error.get("param"))
        request_id = cast(Optional[str], error.get("request_id") or request_id)
    except (ValueError, AttributeError, TypeError):
        pass
    return LiveStreamError(code, message, param=param, request_id=request_id, status=status)


def _fatal(message: StreamError) -> LiveStreamError:
    return LiveStreamError(message.code, message.message, param=message.param, request_id=message.request_id)


def _backoff(attempt: int) -> float:
    return min(2.0 ** (attempt - 1), 30.0) * (0.5 + random.random() / 2)


class AsyncLiveStream:
    """An open live stream for asyncio.

        async with client.audio.stream.open(config={"language": "de"}) as stream:
            await stream.send(pcm)
            async for event in stream:
                ...

    Send audio with `send()`, read messages with `async for`, end with `finish()` to keep the last
    transcripts, or `close()` to drop them.
    """

    def __init__(
        self,
        client: AsyncCore,
        *,
        config: Optional[Mapping[str, Any]] = None,
        token: Optional[str] = None,
        reconnect: bool = True,
        max_reconnects: int = 5,
        buffer_seconds: float = 300.0,
    ) -> None:
        self._client = client
        self._token = token
        # A stream token is single-use: without a new one there is no reconnect.
        self._timeline = _Timeline(config, reconnect and token is None, buffer_seconds)
        self._max_reconnects = max_reconnects
        self._ws: Any = None
        self._live = False
        self._queue: asyncio.Queue[Optional[Any]] = asyncio.Queue()
        self._failure: Optional[LiveStreamError] = None
        self._done = asyncio.Event()
        self._closing = False
        self._finishing = False
        self._ack: Optional[float] = None
        self._reader: Optional[asyncio.Task[None]] = None
        self.session_id: Optional[str] = None
        """The session of the current socket, from `session_ready`."""

    @property
    def transcribed_until(self) -> float:
        """The largest `segment.end` so far: everything before it is transcribed."""
        return self._timeline.last_end

    async def __aenter__(self) -> AsyncLiveStream:
        await self.open()
        return self

    async def __aexit__(self, *args: object) -> None:
        await self.close()

    async def open(self) -> None:
        """Connects, sends the config and waits for `session_ready`."""
        continue_reason = await self._connect()
        if continue_reason is not None:
            raise LiveStreamError(continue_reason, f"The stream did not open: {continue_reason}.")
        self._reader = asyncio.ensure_future(self._run())

    async def _connect(self) -> Optional[str]:
        """Opens a socket and reads up to `session_ready`. Answers a continue code instead of raising for those."""
        from websockets.asyncio.client import connect
        from websockets.exceptions import InvalidStatus

        url, headers = _ws_target(self._client, self._token)
        try:
            ws = await connect(url, additional_headers=headers, user_agent_header=self._client.user_agent, max_size=None)
        except InvalidStatus as e:
            raise _refused(e) from e
        except OSError as e:
            raise LiveStreamError("handshake_failed", f"The stream did not open: {e}") from e
        self._ws = ws
        self._live = False
        offset = self._timeline.last_end
        await ws.send(self._timeline.config_message(offset))
        while True:
            message = _parse(await ws.recv())
            if message is None:
                continue
            if isinstance(message, StreamError) and message.fatal:
                if self._timeline.reconnect and message.code in CONTINUE_CODES and self.session_id is not None:
                    return message.code
                await ws.close()
                raise _fatal(message)
            await self._queue.put(message)
            if isinstance(message, StreamSessionReady):
                self.session_id = message.session_id
                break
        if self._timeline.terms is not None:
            await ws.send(json.dumps({"type": "terms", "terms": self._timeline.terms}))
        # The audio that has no transcript yet, from the offset on, then live audio.
        i = 0
        while i < len(self._timeline.frames):
            piece = self._timeline.piece(i, offset)
            i += 1
            if piece:
                await ws.send(piece)
        self._live = True
        if self._finishing:
            self._ack = None
            await ws.send(json.dumps({"type": "end_utterance"}))
        return None

    async def _run(self) -> None:
        from websockets.exceptions import ConnectionClosed

        attempts = 0
        while True:
            code, reason, continue_reason = 1000, "", None
            try:
                continue_reason = await self._read()
                if continue_reason is None:
                    self._end(None)
                    return
            except ConnectionClosed as e:
                rcvd = e.rcvd
                code, reason = (rcvd.code, rcvd.reason) if rcvd is not None else (1006, "")
            except LiveStreamError as e:
                self._end(e)
                return
            except OSError as e:
                code, reason = 1006, str(e)
            self._live = False
            if self._closing or self._done.is_set():
                self._end(None)
                return
            if not self._timeline.can_continue(code, reason, continue_reason):
                self._end(
                    None
                    if code == 1000
                    else LiveStreamError(reason or "closed", f"The stream closed with {code} {reason}".strip() + ".", close_code=code)
                )
                return
            while True:
                attempts += 1
                if attempts > self._max_reconnects:
                    self._end(
                        LiveStreamError(
                            "reconnect_failed", f"The stream could not connect again after {attempts - 1} attempts.", close_code=code
                        )
                    )
                    return
                await self._queue.put(
                    StreamReconnecting(
                        attempt=attempts, offset_seconds=self._timeline.last_end, reason=continue_reason or reason or str(code)
                    )
                )
                await asyncio.sleep(_backoff(attempts))
                try:
                    continue_reason = await self._connect()
                except LiveStreamError as e:
                    if e.status is not None and e.status in (401, 403):
                        self._end(e)
                        return
                    continue
                except (OSError, ConnectionClosed):
                    continue
                if continue_reason is None:
                    attempts = 0
                    break

    async def _read(self) -> Optional[str]:
        """Reads the current socket. Answers None at the end, or a continue code after a fatal error a new stream can continue."""
        ws = self._ws
        while True:
            silence = _SILENCE if self._finishing and self._ack is not None else None
            try:
                raw = await asyncio.wait_for(ws.recv(), silence) if silence else await ws.recv()
            except asyncio.TimeoutError:
                await self._complete()
                return None
            message = _parse(raw)
            if message is None:
                continue
            if isinstance(message, StreamError) and message.fatal:
                if self._timeline.reconnect and message.code in CONTINUE_CODES:
                    await self._queue.put(message)
                    return message.code
                await self._queue.put(message)
                await ws.close()
                raise _fatal(message)
            await self._queue.put(message)
            if isinstance(message, StreamTranscript):
                end = float(message.segment.end or 0.0) if message.segment else 0.0
                self._timeline.covered(end)
                if self._finishing and self._ack is not None and end >= self._ack:
                    await self._complete()
                    return None
            elif isinstance(message, StreamAck) and self._finishing:
                self._ack = message.processed_until
                if self._timeline.last_end >= message.processed_until:
                    await self._complete()
                    return None

    async def _complete(self) -> None:
        self._closing = True
        try:
            await self._ws.send(json.dumps({"type": "close"}))
            await self._ws.close()
        except Exception:  # noqa: BLE001 - the socket may be gone already
            pass

    def _end(self, failure: Optional[LiveStreamError]) -> None:
        if self._done.is_set():
            return
        self._failure = failure
        self._live = False
        self._done.set()
        self._queue.put_nowait(None)

    async def send(self, audio: bytes) -> None:
        """Sends one audio frame (at most 1 MiB) in the format of the config. Send at real-time speed."""
        if self._done.is_set() or self._closing:
            raise LiveStreamError("closed", "The stream is closed.")
        data = bytes(audio)
        self._timeline.add(data)
        if self._live:
            try:
                await self._ws.send(data)
            except Exception:  # noqa: BLE001 - kept in the timeline; a reconnect sends it again
                pass

    async def _control(self, message: Dict[str, Any]) -> None:
        # A socket that just closed drops the message; the reconnect sends the config with the
        # terms, and `end_utterance` again when the stream is finishing.
        if self._live:
            try:
                await self._ws.send(json.dumps(message))
            except Exception:  # noqa: BLE001
                pass

    async def set_terms(self, terms: List[str]) -> None:
        """Replaces the wordbook of this stream. An empty list removes it."""
        self._timeline.terms = list(terms)
        await self._control({"type": "terms", "terms": list(terms)})

    async def end_utterance(self) -> None:
        """Flushes the audio the server holds. The server answers with `ack`."""
        await self._control({"type": "end_utterance"})

    async def finish(self) -> None:
        """Ends the stream and keeps its tail: `end_utterance`, the last transcripts, then close."""
        if not self._done.is_set():
            self._finishing = True
            self._ack = None
            await self.end_utterance()
            await self._done.wait()
        if self._failure:
            raise self._failure

    async def close(self) -> None:
        """Ends the stream at once. Audio not yet transcribed is lost."""
        if not self._done.is_set():
            self._closing = True
            if self._ws is not None:
                await self._complete()
            if self._reader is not None:
                try:
                    await asyncio.wait_for(asyncio.shield(self._reader), 5.0)
                except asyncio.TimeoutError:
                    self._reader.cancel()
            self._end(None)

    def __aiter__(self) -> AsyncIterator[StreamEvent]:
        return self._events()

    async def _events(self) -> AsyncIterator[StreamEvent]:
        while True:
            event = await self._queue.get()
            if event is None:
                if self._failure:
                    raise self._failure
                return
            yield event


class LiveStream:
    """An open live stream for synchronous code. A thread reads the socket.

    with client.audio.stream.open(config={"language": "de"}) as stream:
        stream.send(pcm)
        for event in stream:
            ...
    """

    def __init__(
        self,
        client: SyncCore,
        *,
        config: Optional[Mapping[str, Any]] = None,
        token: Optional[str] = None,
        reconnect: bool = True,
        max_reconnects: int = 5,
        buffer_seconds: float = 300.0,
    ) -> None:
        self._client = client
        self._token = token
        self._timeline = _Timeline(config, reconnect and token is None, buffer_seconds)
        self._max_reconnects = max_reconnects
        self._ws: Any = None
        self._live = False
        self._lock = threading.RLock()
        self._queue: queue.Queue[Optional[Any]] = queue.Queue()
        self._failure: Optional[LiveStreamError] = None
        self._done = threading.Event()
        self._closing = False
        self._finishing = False
        self._ack: Optional[float] = None
        self._thread: Optional[threading.Thread] = None
        self.session_id: Optional[str] = None
        """The session of the current socket, from `session_ready`."""

    @property
    def transcribed_until(self) -> float:
        """The largest `segment.end` so far: everything before it is transcribed."""
        return self._timeline.last_end

    def __enter__(self) -> LiveStream:
        self.open()
        return self

    def __exit__(self, *args: object) -> None:
        self.close()

    def open(self) -> None:
        """Connects, sends the config and waits for `session_ready`."""
        continue_reason = self._connect()
        if continue_reason is not None:
            raise LiveStreamError(continue_reason, f"The stream did not open: {continue_reason}.")
        self._thread = threading.Thread(target=self._run, name="medisync-live-stream", daemon=True)
        self._thread.start()

    def _connect(self) -> Optional[str]:
        from websockets.exceptions import InvalidStatus
        from websockets.sync.client import connect

        url, headers = _ws_target(self._client, self._token)
        try:
            ws = connect(url, additional_headers=headers, user_agent_header=self._client.user_agent, max_size=None)
        except InvalidStatus as e:
            raise _refused(e) from e
        except OSError as e:
            raise LiveStreamError("handshake_failed", f"The stream did not open: {e}") from e
        offset = self._timeline.last_end
        ws.send(self._timeline.config_message(offset))
        while True:
            message = _parse(ws.recv())
            if message is None:
                continue
            if isinstance(message, StreamError) and message.fatal:
                if self._timeline.reconnect and message.code in CONTINUE_CODES and self.session_id is not None:
                    return message.code
                ws.close()
                raise _fatal(message)
            self._queue.put(message)
            if isinstance(message, StreamSessionReady):
                self.session_id = message.session_id
                break
        with self._lock:
            self._ws = ws
            if self._timeline.terms is not None:
                ws.send(json.dumps({"type": "terms", "terms": self._timeline.terms}))
            for i in range(len(self._timeline.frames)):
                piece = self._timeline.piece(i, offset)
                if piece:
                    ws.send(piece)
            self._live = True
            if self._finishing:
                self._ack = None
                ws.send(json.dumps({"type": "end_utterance"}))
        return None

    def _run(self) -> None:
        from websockets.exceptions import ConnectionClosed

        attempts = 0
        while True:
            code, reason, continue_reason = 1000, "", None
            try:
                continue_reason = self._read()
                if continue_reason is None:
                    self._end(None)
                    return
            except ConnectionClosed as e:
                rcvd = e.rcvd
                code, reason = (rcvd.code, rcvd.reason) if rcvd is not None else (1006, "")
            except LiveStreamError as e:
                self._end(e)
                return
            except OSError as e:
                code, reason = 1006, str(e)
            with self._lock:
                self._live = False
            if self._closing or self._done.is_set():
                self._end(None)
                return
            if not self._timeline.can_continue(code, reason, continue_reason):
                self._end(
                    None
                    if code == 1000
                    else LiveStreamError(reason or "closed", f"The stream closed with {code} {reason}".strip() + ".", close_code=code)
                )
                return
            while True:
                attempts += 1
                if attempts > self._max_reconnects:
                    self._end(
                        LiveStreamError(
                            "reconnect_failed", f"The stream could not connect again after {attempts - 1} attempts.", close_code=code
                        )
                    )
                    return
                self._queue.put(
                    StreamReconnecting(
                        attempt=attempts, offset_seconds=self._timeline.last_end, reason=continue_reason or reason or str(code)
                    )
                )
                if self._done.wait(_backoff(attempts)):
                    return
                try:
                    continue_reason = self._connect()
                except LiveStreamError as e:
                    if e.status is not None and e.status in (401, 403):
                        self._end(e)
                        return
                    continue
                except (OSError, ConnectionClosed):
                    continue
                if continue_reason is None:
                    attempts = 0
                    break

    def _read(self) -> Optional[str]:
        ws = self._ws
        while True:
            silence = _SILENCE if self._finishing and self._ack is not None else None
            try:
                raw = ws.recv(timeout=silence)
            except TimeoutError:
                self._complete()
                return None
            message = _parse(raw)
            if message is None:
                continue
            if isinstance(message, StreamError) and message.fatal:
                self._queue.put(message)
                if self._timeline.reconnect and message.code in CONTINUE_CODES:
                    return message.code
                ws.close()
                raise _fatal(message)
            self._queue.put(message)
            if isinstance(message, StreamTranscript):
                end = float(message.segment.end or 0.0) if message.segment else 0.0
                with self._lock:
                    self._timeline.covered(end)
                if self._finishing and self._ack is not None and end >= self._ack:
                    self._complete()
                    return None
            elif isinstance(message, StreamAck) and self._finishing:
                self._ack = message.processed_until
                if self._timeline.last_end >= message.processed_until:
                    self._complete()
                    return None

    def _complete(self) -> None:
        self._closing = True
        try:
            self._ws.send(json.dumps({"type": "close"}))
            self._ws.close()
        except Exception:  # noqa: BLE001 - the socket may be gone already
            pass

    def _end(self, failure: Optional[LiveStreamError]) -> None:
        if self._done.is_set():
            return
        self._failure = failure
        self._live = False
        self._done.set()
        self._queue.put(None)

    def send(self, audio: bytes) -> None:
        """Sends one audio frame (at most 1 MiB) in the format of the config. Send at real-time speed."""
        if self._done.is_set() or self._closing:
            raise LiveStreamError("closed", "The stream is closed.")
        data = bytes(audio)
        with self._lock:
            self._timeline.add(data)
            if self._live:
                try:
                    self._ws.send(data)
                except Exception:  # noqa: BLE001 - kept in the timeline; a reconnect sends it again
                    pass

    def _control(self, message: Dict[str, Any]) -> None:
        # A socket that just closed drops the message; the reconnect sends the config with the
        # terms, and `end_utterance` again when the stream is finishing.
        with self._lock:
            if self._live:
                try:
                    self._ws.send(json.dumps(message))
                except Exception:  # noqa: BLE001
                    pass

    def set_terms(self, terms: List[str]) -> None:
        """Replaces the wordbook of this stream. An empty list removes it."""
        with self._lock:
            self._timeline.terms = list(terms)
        self._control({"type": "terms", "terms": list(terms)})

    def end_utterance(self) -> None:
        """Flushes the audio the server holds. The server answers with `ack`."""
        self._control({"type": "end_utterance"})

    def finish(self, timeout: Optional[float] = None) -> None:
        """Ends the stream and keeps its tail: `end_utterance`, the last transcripts, then close."""
        if not self._done.is_set():
            self._finishing = True
            self._ack = None
            self.end_utterance()
            self._done.wait(timeout)
        if self._failure:
            raise self._failure

    def close(self) -> None:
        """Ends the stream at once. Audio not yet transcribed is lost."""
        if not self._done.is_set():
            self._closing = True
            if self._ws is not None:
                self._complete()
            if self._thread is not None:
                self._thread.join(5.0)
            self._end(None)

    def __iter__(self) -> Iterator[StreamEvent]:
        while True:
            event = self._queue.get()
            if event is None:
                if self._failure:
                    raise self._failure
                return
            yield event
