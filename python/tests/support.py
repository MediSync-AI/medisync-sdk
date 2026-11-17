from __future__ import annotations

from typing import Any, Callable, Dict, List, Tuple, Union

import httpx

from medisync import AsyncMediSync, MediSync

Answer = Union[httpx.Response, Callable[[httpx.Request], httpx.Response]]


def json_answer(status: int, body: Any, headers: Dict[str, str] | None = None) -> httpx.Response:
    return httpx.Response(status, json=body, headers=headers or {})


def mock_api(answers: List[Answer], **options: Any) -> Tuple[MediSync, List[httpx.Request], List[float]]:
    calls: List[httpx.Request] = []
    sleeps: List[float] = []

    def handler(request: httpx.Request) -> httpx.Response:
        request.read()
        calls.append(request)
        if not answers:
            raise AssertionError("no more answers")
        nxt = answers.pop(0)
        return nxt(request) if callable(nxt) else nxt

    client = MediSync(
        **{
            "api_key": "msk_test_abc",
            "base_url": "https://api.example.test",
            "http_client": httpx.Client(transport=httpx.MockTransport(handler)),
            "_sleep": sleeps.append,
            **options,
        }
    )
    return client, calls, sleeps


def mock_async_api(answers: List[Answer], **options: Any) -> Tuple[AsyncMediSync, List[httpx.Request], List[float]]:
    calls: List[httpx.Request] = []
    sleeps: List[float] = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        nxt = answers.pop(0)
        return nxt(request) if callable(nxt) else nxt

    async def sleep(seconds: float) -> None:
        sleeps.append(seconds)

    client = AsyncMediSync(
        **{
            "api_key": "msk_test_abc",
            "base_url": "https://api.example.test",
            "http_client": httpx.AsyncClient(transport=httpx.MockTransport(handler)),
            "_sleep": sleep,
            **options,
        }
    )
    return client, calls, sleeps


def transfer(status: str, **extra: Any) -> Dict[str, Any]:
    return {
        "id": "trf_7Yc3Nd9LxB",
        "plan_id": "pln_2Vh8Ms6TqW",
        "connection_id": "con_4Rt8Vb2MnQ",
        "site_id": "ste_7Hq2Lk9PwX",
        "verb": "record.write",
        "status": status,
        "release": {},
        "operations": [],
        "history": [],
        "expires_at": "2026-11-05T09:00:00Z",
        "created_at": "2026-11-04T09:00:00Z",
        "updated_at": "2026-11-04T09:00:00Z",
        **extra,
    }
