"""The HTTP core of the clients: auth, headers, retries, idempotency keys, errors and pages.

The generated resource classes call `_request()` and `_request_page()` with the operation's meta.
"""

from __future__ import annotations

import asyncio
import email.utils
import json
import os
import random
import time
import uuid
from dataclasses import dataclass
from typing import TYPE_CHECKING, Any, Awaitable, Callable, Dict, List, Mapping, Optional, Tuple, TypeVar, Union, cast
from urllib.parse import quote

import httpx

from ._exceptions import APIConnectionError, APIError, APITimeoutError, MediSyncError, error_from_response
from ._files import HttpxFile, file_opener, is_file
from ._generated.operations import API_VERSION
from ._models import BaseModel, construct
from ._operation import OperationMeta, RequestOptions
from ._pagination import AsyncCursorPage, AsyncPaginator, SyncCursorPage
from ._types import NotGiven
from ._version import __version__

if TYPE_CHECKING:
    from ._app_auth import AppCredentials, AppTokenSource, AsyncAppTokenSource

DEFAULT_BASE_URL = "https://api.medisync.me"
DEFAULT_TIMEOUT = 120.0
DEFAULT_MAX_RETRIES = 2
MAX_RETRY_WAIT = 60.0
_SAFE = frozenset({"GET", "HEAD", "OPTIONS"})
_NO_OPTIONS = RequestOptions()
_S = TypeVar("_S", bound="SyncCore")
_A = TypeVar("_A", bound="AsyncCore")


@dataclass(frozen=True)
class ResponseInfo:
    """What `on_response` gets after each answer, also for retries and errors."""

    operation: str
    status: int
    request_id: Optional[str]
    """Quote it to support."""
    attempt: int
    headers: httpx.Headers


def backoff(attempt: int) -> float:
    """Exponential backoff with jitter: about 0.5 s, 1 s, 2 s, ... up to 8 s."""
    base = min(0.5 * 2.0**attempt, 8.0)
    return base * (1 - random.random() * 0.25)


def retryable_status(status: int, error: APIError) -> bool:
    """True when an answer with this status and error may succeed on a retry."""
    if error.error and error.error.get("retryable") is False:
        return False
    if status == 408:
        return True
    if status == 409:
        return error.code == "idempotency_key_in_use"
    if status == 429:
        # A spend cap or the daily test allowance does not reset within a retry.
        return error.code not in ("spend_cap_reached", "test_daily_limit_reached")
    return status >= 500 and status != 501


def retry_after(headers: httpx.Headers, error: Optional[Dict[str, Any]]) -> Optional[float]:
    header = headers.get("retry-after")
    if header:
        try:
            seconds = float(header)
            if seconds >= 0:
                return seconds
        except ValueError:
            parsed = email.utils.parsedate_to_datetime(header) if header else None
            if parsed is not None:
                return max(parsed.timestamp() - time.time(), 0.0)
    value: object = error.get("retry_after") if error else None
    return float(value) if isinstance(value, (int, float)) else None


def _strip(value: Any) -> Any:
    """JSON-ready data: models as dicts with API field names, NOT_GIVEN left out."""
    if isinstance(value, BaseModel):
        return value.to_dict()
    if isinstance(value, Mapping):
        mapping: Mapping[Any, Any] = value  # pyright: ignore[reportUnknownVariableType]
        return {str(k): _strip(v) for k, v in mapping.items() if not isinstance(v, NotGiven)}
    if isinstance(value, (list, tuple)):
        return [_strip(v) for v in cast(List[Any], value)]
    return value


def _form_value(value: Any) -> str:
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, (int, float, str)):
        return str(value)
    return json.dumps(_strip(value))


@dataclass
class _Prepared:
    method: str
    url: str
    headers: Dict[str, str]
    json_body: Optional[bytes]
    form: Optional[Dict[str, str]]
    files: Optional[Dict[str, Callable[[], HttpxFile]]]
    can_retry: bool
    max_retries: int
    timeout: float

    def build(self, client: Union[httpx.Client, httpx.AsyncClient], authorization: str) -> httpx.Request:
        headers = {**self.headers, "authorization": authorization}
        files = {k: opener() for k, opener in self.files.items()} if self.files else None
        return client.build_request(
            self.method,
            self.url,
            headers=headers,
            content=self.json_body,
            data=self.form,
            files=files,
            timeout=self.timeout,
        )


def _env(name: str) -> Optional[str]:
    value = os.environ.get(name)
    return value if value else None


class _BaseCore:
    base_url: str
    version: str
    timeout: float
    max_retries: int

    def __init__(
        self,
        *,
        api_key: Optional[str],
        base_url: Optional[str],
        version: Optional[str],
        timeout: Optional[float],
        max_retries: Optional[int],
        default_headers: Optional[Mapping[str, str]],
        on_response: Optional[Callable[[ResponseInfo], None]],
        has_app: bool,
        idempotency_key: Optional[Callable[[], str]],
    ) -> None:
        self._api_key = api_key if api_key is not None else _env("MEDISYNC_API_KEY")
        if not self._api_key and not has_app:
            raise MediSyncError("No API key. Pass api_key, or set the MEDISYNC_API_KEY environment variable.")
        self.base_url = (base_url or _env("MEDISYNC_BASE_URL") or DEFAULT_BASE_URL).rstrip("/")
        self.version = version or API_VERSION
        self.timeout = DEFAULT_TIMEOUT if timeout is None else timeout
        self.max_retries = DEFAULT_MAX_RETRIES if max_retries is None else max_retries
        self._default_headers = dict(default_headers or {})
        self._on_response = on_response
        self._new_key = idempotency_key or (lambda: str(uuid.uuid4()))

    @property
    def user_agent(self) -> str:
        return f"medisync-python/{__version__}"

    def _base_headers(self) -> Dict[str, str]:
        return {
            "medisync-version": self.version,
            "user-agent": self.user_agent,
            **self._default_headers,
        }

    def _api_key_authorization(self) -> str:
        if not self._api_key:
            raise MediSyncError("This call needs an API key: the app credentials work for MediSync Connect only.")
        return f"Bearer {self._api_key}"

    def _url(
        self,
        op: OperationMeta,
        path: Optional[Mapping[str, Any]],
        query: Optional[Mapping[str, Any]],
        extra: Optional[Mapping[str, object]],
    ) -> str:
        def fill(name: str) -> str:
            value = (path or {}).get(name)
            if value is None or value == "":
                raise MediSyncError(f"Missing the path parameter {name}.")
            return quote(str(value), safe="")

        url_path = op.path
        for name in [p[1:-1] for p in _placeholders(op.path)]:
            url_path = url_path.replace("{" + name + "}", fill(name))
        params: List[Tuple[str, Union[str, int, float, bool, None]]] = []
        for key, value in {**(query or {}), **(extra or {})}.items():
            if value is None or isinstance(value, NotGiven):
                continue
            if isinstance(value, (list, tuple)):
                items = [_form_value(v) for v in cast(List[Any], value)]
                if key in op.comma_query:
                    params.append((key, ",".join(items)))
                else:
                    params.extend((key, v) for v in items)
            else:
                params.append((key, _form_value(value)))
        url = httpx.URL(self.base_url + url_path)
        return str(url.copy_merge_params(params)) if params else str(url)

    def _prepare(
        self,
        op: OperationMeta,
        path: Optional[Mapping[str, Any]],
        query: Optional[Mapping[str, Any]],
        body: Optional[Mapping[str, Any]],
        options: RequestOptions,
    ) -> _Prepared:
        headers = self._base_headers()
        headers["accept"] = "application/json" if op.response == "json" else "*/*"
        json_body: Optional[bytes] = None
        form: Optional[Dict[str, str]] = None
        files: Optional[Dict[str, Callable[[], HttpxFile]]] = None
        if op.body is not None:
            data: Dict[str, Any] = {**(body or {}), **(options.extra_body or {})}
            data = {k: v for k, v in data.items() if not isinstance(v, NotGiven)}
            if op.body == "json":
                headers["content-type"] = "application/json"
                json_body = json.dumps(_strip(data)).encode()
            else:
                form = {}
                files = {}
                for key, value in data.items():
                    if value is None:
                        continue
                    if is_file(value):
                        files[key] = file_opener(value, key)
                    else:
                        form[key] = _form_value(value)
        if op.idempotency != "none":
            headers["idempotency-key"] = options.idempotency_key or self._new_key()
        headers.update(options.extra_headers or {})
        can_retry = op.method in _SAFE or "idempotency-key" in headers
        max_retries = (self.max_retries if options.max_retries is None else options.max_retries) if can_retry else 0
        return _Prepared(
            method=op.method,
            url=self._url(op, path, query, options.extra_query),
            headers=headers,
            json_body=json_body,
            form=form,
            files=files,
            can_retry=can_retry,
            max_retries=max_retries,
            timeout=self.timeout if options.timeout is None else options.timeout,
        )

    def _report(self, op: OperationMeta, response: httpx.Response, attempt: int) -> None:
        if self._on_response:
            request_id = response.headers.get("request-id") or response.headers.get("medisync-request-id")
            self._on_response(ResponseInfo(op.operation_id, response.status_code, request_id, attempt, response.headers))

    @staticmethod
    def _error(response: httpx.Response) -> APIError:
        text = response.text
        payload: object = text
        try:
            payload = json.loads(text) if text else None
        except ValueError:
            pass
        return error_from_response(response.status_code, payload, response.headers)

    @staticmethod
    def _parse(op: OperationMeta, response: httpx.Response, cast_to: Any) -> Any:
        if op.response == "empty":
            return None
        if op.response == "binary":
            return response.content
        if op.response == "text":
            return response.text
        if not response.content:
            return None
        return construct(cast_to, response.json())


def _placeholders(path: str) -> List[str]:
    out: List[str] = []
    start = path.find("{")
    while start >= 0:
        end = path.index("}", start)
        out.append(path[start : end + 1])
        start = path.find("{", end)
    return out


class SyncCore(_BaseCore):
    """The synchronous HTTP core."""

    def __init__(
        self,
        api_key: Optional[str] = None,
        *,
        connect_app: Union[AppCredentials, AppTokenSource, None] = None,
        base_url: Optional[str] = None,
        version: Optional[str] = None,
        timeout: Optional[float] = None,
        max_retries: Optional[int] = None,
        default_headers: Optional[Mapping[str, str]] = None,
        http_client: Optional[httpx.Client] = None,
        on_response: Optional[Callable[[ResponseInfo], None]] = None,
        _idempotency_key: Optional[Callable[[], str]] = None,
        _sleep: Optional[Callable[[float], None]] = None,
    ) -> None:
        super().__init__(
            api_key=api_key,
            base_url=base_url,
            version=version,
            timeout=timeout,
            max_retries=max_retries,
            default_headers=default_headers,
            on_response=on_response,
            has_app=connect_app is not None,
            idempotency_key=_idempotency_key,
        )
        self._http = http_client or httpx.Client(follow_redirects=False)
        self._owns_http = http_client is None
        self._sleep = _sleep or time.sleep
        self._tokens: Optional[AppTokenSource] = None
        if connect_app is not None:
            from ._app_auth import AppTokenSource

            self._tokens = connect_app if isinstance(connect_app, AppTokenSource) else AppTokenSource(connect_app, http_client=self._http)
        self._init_resources()

    def _init_resources(self) -> None:
        """Set by the generated resources class."""

    def close(self) -> None:
        """Closes the HTTP connections the client opened."""
        if self._owns_http:
            self._http.close()

    def __enter__(self) -> SyncCore:
        return self

    def __exit__(self, *args: object) -> None:
        self.close()

    def _authorization(self, op: OperationMeta) -> str:
        if op.api == "connect" and self._tokens is not None:
            return f"Bearer {self._tokens.get()}"
        return self._api_key_authorization()

    def _request(
        self,
        op: OperationMeta,
        *,
        path: Optional[Mapping[str, Any]] = None,
        query: Optional[Mapping[str, Any]] = None,
        body: Optional[Mapping[str, Any]] = None,
        options: RequestOptions = _NO_OPTIONS,
        cast_to: Any = None,
    ) -> Any:
        prepared = self._prepare(op, path, query, body, options)
        token_retried = False
        attempt = 0
        while True:
            try:
                request = prepared.build(self._http, self._authorization(op))
                response = self._http.send(request)
            except httpx.TimeoutException as e:
                if attempt < prepared.max_retries:
                    self._sleep(backoff(attempt))
                    attempt += 1
                    continue
                raise APITimeoutError(f"{op.method} {op.path} timed out after {prepared.timeout} s.") from e
            except httpx.TransportError as e:
                if attempt < prepared.max_retries:
                    self._sleep(backoff(attempt))
                    attempt += 1
                    continue
                raise APIConnectionError(f"{op.method} {op.path} failed: {e}") from e
            self._report(op, response, attempt)
            if response.is_success:
                return self._parse(op, response, cast_to)
            error = self._error(response)
            if (
                response.status_code == 401
                and self._tokens is not None
                and op.api == "connect"
                and error.code == "invalid_token"
                and not token_retried
            ):
                self._tokens.clear()
                token_retried = True
                continue
            if attempt < prepared.max_retries and retryable_status(response.status_code, error):
                wait = retry_after(response.headers, error.error)
                self._sleep(min(backoff(attempt) if wait is None else wait, MAX_RETRY_WAIT))
                attempt += 1
                continue
            raise error

    def _request_page(
        self,
        op: OperationMeta,
        *,
        path: Optional[Mapping[str, Any]] = None,
        query: Optional[Mapping[str, Any]] = None,
        options: RequestOptions = _NO_OPTIONS,
        item_type: Any,
        list_type: Any,
    ) -> SyncCursorPage[Any]:
        def load(q: Optional[Mapping[str, Any]]) -> SyncCursorPage[Any]:
            raw = self._request_raw_json(op, path=path, query=q, options=options)
            return SyncCursorPage(
                raw, item_type=item_type, list_type=list_type, next_page=lambda cursor: load({**(q or {}), "cursor": cursor})
            )

        return load(query)

    def _request_raw_json(
        self, op: OperationMeta, *, path: Optional[Mapping[str, Any]], query: Optional[Mapping[str, Any]], options: RequestOptions
    ) -> Dict[str, Any]:
        raw = self._request(op, path=path, query=query, options=options, cast_to=None)
        return cast(Dict[str, Any], raw) if isinstance(raw, dict) else {}


class AsyncCore(_BaseCore):
    """The asyncio HTTP core."""

    def __init__(
        self,
        api_key: Optional[str] = None,
        *,
        connect_app: Union[AppCredentials, AsyncAppTokenSource, None] = None,
        base_url: Optional[str] = None,
        version: Optional[str] = None,
        timeout: Optional[float] = None,
        max_retries: Optional[int] = None,
        default_headers: Optional[Mapping[str, str]] = None,
        http_client: Optional[httpx.AsyncClient] = None,
        on_response: Optional[Callable[[ResponseInfo], None]] = None,
        _idempotency_key: Optional[Callable[[], str]] = None,
        _sleep: Optional[Callable[[float], Awaitable[None]]] = None,
    ) -> None:
        super().__init__(
            api_key=api_key,
            base_url=base_url,
            version=version,
            timeout=timeout,
            max_retries=max_retries,
            default_headers=default_headers,
            on_response=on_response,
            has_app=connect_app is not None,
            idempotency_key=_idempotency_key,
        )
        self._http = http_client or httpx.AsyncClient(follow_redirects=False)
        self._owns_http = http_client is None
        self._sleep = _sleep or asyncio.sleep
        self._tokens: Optional[AsyncAppTokenSource] = None
        if connect_app is not None:
            from ._app_auth import AsyncAppTokenSource

            self._tokens = (
                connect_app if isinstance(connect_app, AsyncAppTokenSource) else AsyncAppTokenSource(connect_app, http_client=self._http)
            )
        self._init_resources()

    def _init_resources(self) -> None:
        """Set by the generated resources class."""

    async def close(self) -> None:
        """Closes the HTTP connections the client opened."""
        if self._owns_http:
            await self._http.aclose()

    async def __aenter__(self) -> AsyncCore:
        return self

    async def __aexit__(self, *args: object) -> None:
        await self.close()

    async def _authorization(self, op: OperationMeta) -> str:
        if op.api == "connect" and self._tokens is not None:
            return f"Bearer {await self._tokens.get()}"
        return self._api_key_authorization()

    async def _request(
        self,
        op: OperationMeta,
        *,
        path: Optional[Mapping[str, Any]] = None,
        query: Optional[Mapping[str, Any]] = None,
        body: Optional[Mapping[str, Any]] = None,
        options: RequestOptions = _NO_OPTIONS,
        cast_to: Any = None,
    ) -> Any:
        prepared = self._prepare(op, path, query, body, options)
        token_retried = False
        attempt = 0
        while True:
            try:
                request = prepared.build(self._http, await self._authorization(op))
                response = await self._http.send(request)
            except httpx.TimeoutException as e:
                if attempt < prepared.max_retries:
                    await self._sleep(backoff(attempt))
                    attempt += 1
                    continue
                raise APITimeoutError(f"{op.method} {op.path} timed out after {prepared.timeout} s.") from e
            except httpx.TransportError as e:
                if attempt < prepared.max_retries:
                    await self._sleep(backoff(attempt))
                    attempt += 1
                    continue
                raise APIConnectionError(f"{op.method} {op.path} failed: {e}") from e
            self._report(op, response, attempt)
            if response.is_success:
                return self._parse(op, response, cast_to)
            error = self._error(response)
            if (
                response.status_code == 401
                and self._tokens is not None
                and op.api == "connect"
                and error.code == "invalid_token"
                and not token_retried
            ):
                self._tokens.clear()
                token_retried = True
                continue
            if attempt < prepared.max_retries and retryable_status(response.status_code, error):
                wait = retry_after(response.headers, error.error)
                await self._sleep(min(backoff(attempt) if wait is None else wait, MAX_RETRY_WAIT))
                attempt += 1
                continue
            raise error

    def _request_page(
        self,
        op: OperationMeta,
        *,
        path: Optional[Mapping[str, Any]] = None,
        query: Optional[Mapping[str, Any]] = None,
        options: RequestOptions = _NO_OPTIONS,
        item_type: Any,
        list_type: Any,
    ) -> AsyncPaginator[Any]:
        async def load(q: Optional[Mapping[str, Any]]) -> AsyncCursorPage[Any]:
            raw = await self._request(op, path=path, query=q, options=options, cast_to=None)
            data = cast(Dict[str, Any], raw) if isinstance(raw, dict) else {}
            return AsyncCursorPage(
                data, item_type=item_type, list_type=list_type, next_page=lambda cursor: load({**(q or {}), "cursor": cursor})
            )

        return AsyncPaginator(lambda: load(query))


class SyncAPIResource:
    """Base of the generated synchronous resource classes."""

    def __init__(self, client: SyncCore) -> None:
        self._client = client


class AsyncAPIResource:
    """Base of the generated asyncio resource classes."""

    def __init__(self, client: AsyncCore) -> None:
        self._client = client
