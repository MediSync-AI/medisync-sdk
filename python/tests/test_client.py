"""The HTTP core with a mock transport: options, retries, timeouts, errors, models and pages."""

from __future__ import annotations

import io
from pathlib import Path
from typing import List

import httpx
import pytest

from medisync import (
    NOT_GIVEN,
    APIConnectionError,
    APITimeoutError,
    InternalServerError,
    MediSync,
    MediSyncError,
    PermissionDeniedError,
    ProductNotEnabledError,
    RateLimitError,
    ResponseInfo,
    ServiceUnavailableError,
)
from medisync.types import Transcription
from medisync.types.connect import Transfer

from .support import json_answer, mock_api, mock_async_api, transfer


def test_reads_the_environment_and_refuses_no_key(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("MEDISYNC_API_KEY", "msk_test_from_env")
    monkeypatch.setenv("MEDISYNC_BASE_URL", "https://env.example.test/")
    client = MediSync()
    assert client.base_url == "https://env.example.test"
    assert client._api_key == "msk_test_from_env"
    monkeypatch.delenv("MEDISYNC_API_KEY")
    with pytest.raises(MediSyncError):
        MediSync()


def test_headers_and_on_response() -> None:
    seen: List[ResponseInfo] = []
    client, calls, _ = mock_api(
        [json_answer(200, {"data": []}, {"request-id": "req_1"})], default_headers={"x-team": "praxis-beispiel"}, on_response=seen.append
    )
    client.models.list(extra_headers={"x-trace": "abc"})
    assert calls[0].headers["x-team"] == "praxis-beispiel"
    assert calls[0].headers["x-trace"] == "abc"
    assert calls[0].headers["user-agent"].startswith("medisync-python/")
    assert [(i.operation, i.status, i.request_id) for i in seen] == [("listModels", 200, "req_1")]


def test_not_given_fields_are_left_out_and_none_is_sent_as_null() -> None:
    client, calls, _ = mock_api([json_answer(200, {"id": "ste_1", "name": "x"})])
    client.connect.sites.update("ste_1", name="Praxis Beispiel", bsnr=None, timezone=NOT_GIVEN)  # type: ignore[arg-type]
    import json

    body = json.loads(calls[0].content)
    assert body == {"name": "Praxis Beispiel", "bsnr": None}


def test_network_errors_are_retried_then_raised() -> None:
    def fail(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("refused", request=request)

    client, calls, sleeps = mock_api([fail, fail, fail], max_retries=2)
    with pytest.raises(APIConnectionError):
        client.models.list()
    assert len(calls) == 3
    assert len(sleeps) == 2


def test_timeouts_raise_api_timeout_error() -> None:
    def slow(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("slow", request=request)

    client, calls, _ = mock_api([slow, slow], max_retries=1)
    with pytest.raises(APITimeoutError):
        client.models.list(timeout=0.5)
    assert len(calls) == 2


def test_post_retries_keep_the_idempotency_key() -> None:
    client, calls, _ = mock_api([json_answer(503, {}), json_answer(200, {"results": []})])
    client.coding.icd10gm(mentions=[{"id": "m1", "text": "Hypertonie"}])
    assert len(calls) == 2
    assert calls[0].headers["idempotency-key"] == calls[1].headers["idempotency-key"]


def test_error_shapes_and_header_request_id() -> None:
    client, _, _ = mock_api(
        [
            json_answer(
                403,
                {
                    "type": "error",
                    "error": {
                        "type": "permission_error",
                        "code": "product_not_enabled",
                        "message": "Off.",
                        "param": None,
                        "request_id": "req_01",
                    },
                    "request_id": "req_01",
                },
            ),
            json_answer(
                403,
                {"error": {"type": "permission_error", "code": "forbidden", "message": "No.", "request_id": "req_02", "reason": "role"}},
            ),
            httpx.Response(500, text="upstream broke", headers={"request-id": "req_from_header"}),
            json_answer(
                429,
                {"error": {"type": "rate_limit_error", "code": "rate_limited", "message": "Wait.", "request_id": "r", "retry_after": 12}},
            ),
        ],
        max_retries=0,
    )
    with pytest.raises(ProductNotEnabledError) as off:
        client.connect.systems.list()
    assert isinstance(off.value, PermissionDeniedError) and off.value.request_id == "req_01"
    with pytest.raises(PermissionDeniedError) as forbidden:
        client.connect.sites.list()
    assert forbidden.value.error and forbidden.value.error["reason"] == "role"
    with pytest.raises(InternalServerError) as broken:
        client.connect.sites.list()
    assert broken.value.request_id == "req_from_header" and str(broken.value) == "upstream broke"
    with pytest.raises(RateLimitError) as limited:
        client.connect.sites.list()
    assert limited.value.retry_after == 12


def test_unknown_fields_and_enum_values_pass_through() -> None:
    client, _, _ = mock_api([json_answer(200, transfer("archived", priority="high"))])
    t = client.connect.transfers.get("trf_7Yc3Nd9LxB")
    assert isinstance(t, Transfer)
    assert t.status == "archived"
    assert t.to_dict()["priority"] == "high"


def test_an_answer_that_breaks_the_spec_is_kept() -> None:
    client, _, _ = mock_api([json_answer(200, {"id": "tr_1", "status": "queued"})])
    t = client.audio.transcriptions.get("tr_1")
    assert isinstance(t, Transcription)
    assert t.id == "tr_1"


def test_pages_iterate_every_item() -> None:
    client, calls, _ = mock_api(
        [
            json_answer(200, {"data": [{"id": "ste_A"}, {"id": "ste_B"}], "next_cursor": "c2"}),
            json_answer(200, {"data": [{"id": "ste_C"}], "next_cursor": None}),
        ]
    )
    page = client.connect.sites.list(limit=2)
    assert [s.id for s in page.data] == ["ste_A", "ste_B"] and page.has_next_page()
    assert [s.id for s in page] == ["ste_A", "ste_B", "ste_C"]
    assert [dict(c.url.params) for c in calls] == [{"limit": "2"}, {"limit": "2", "cursor": "c2"}]


def test_multipart_files_from_path_stream_and_bytes(tmp_path: Path) -> None:
    audio = tmp_path / "visit.wav"
    audio.write_bytes(b"RIFF" + bytes(10))
    answer = json_answer(202, {"id": "tr_1", "object": "transcription", "status": "queued", "created_at": "2026-11-16T09:00:00Z"})
    client, calls, _ = mock_api([answer, answer, answer, answer], max_retries=1)
    client.audio.transcriptions.create(file=audio, language="de")
    client.audio.transcriptions.create(file=io.BytesIO(b"abc"))
    client.audio.transcriptions.create(file=("note.flac", b"xyz", "audio/flac"), diarization=False)
    assert b'filename="visit.wav"' in calls[0].content and b'name="language"\r\n\r\nde' in calls[0].content
    assert b'filename="file"' in calls[1].content
    assert b'filename="note.flac"' in calls[2].content and b"audio/flac" in calls[2].content and b"false" in calls[2].content


def test_a_retried_upload_sends_the_whole_stream_again() -> None:
    answer = json_answer(202, {"id": "tr_1", "object": "transcription", "status": "queued", "created_at": "2026-11-16T09:00:00Z"})
    client, calls, _ = mock_api([json_answer(503, {}), answer], max_retries=1)
    client.audio.transcriptions.create(file=io.BytesIO(b"0123456789"))
    assert len(calls) == 2
    assert b"0123456789" in calls[0].content and b"0123456789" in calls[1].content


async def test_async_client_retries_and_pages() -> None:
    client, calls, sleeps = mock_async_api(
        [
            json_answer(503, {}),
            json_answer(200, {"data": [{"id": "ste_A"}], "next_cursor": "c2"}),
            json_answer(200, {"data": [{"id": "ste_B"}], "next_cursor": None}),
        ]
    )
    ids = [s.id async for s in client.connect.sites.list()]
    assert ids == ["ste_A", "ste_B"]
    assert len(calls) == 3 and len(sleeps) == 1
    with pytest.raises(ServiceUnavailableError):
        client2, _, _ = mock_async_api([json_answer(503, {})], max_retries=0)
        await client2.models.list()
    await client.close()
