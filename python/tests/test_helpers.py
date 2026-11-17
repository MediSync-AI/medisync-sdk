"""Helpers that are no API operation: release, waits, the event poll and the capability view."""

from __future__ import annotations

import threading
from datetime import datetime, timezone
from typing import Any, Dict, List

import httpx
import pytest

from medisync import TranscriptionWaitTimeoutError, TransferWaitTimeoutError
from medisync._exceptions import CapabilityError, NotSupportedError, VerbNotGrantedError
from medisync.connect import build_release

from .support import json_answer, mock_api, mock_async_api, transfer

PLAN = {"document_hash": "a" * 64, "plan_hash": "b" * 64}


def test_build_release_takes_the_hashes_and_writes_seconds() -> None:
    release = build_release(
        PLAN,
        member_id="mem_9Ks3Jd7TyA",
        display_name="Dr. Jonas Keller",
        role="doctor",
        practitioner_link="ARZT-03",
        patient_confirmed={"id": "10482", "family": "Beispiel", "given": "Erika", "birth_date": "1961-03-12"},
        released_at=datetime(2026, 11, 4, 10, 15, 30, 456000, tzinfo=timezone.utc),
    )
    assert release.to_dict() == {
        "released_by": {"member_id": "mem_9Ks3Jd7TyA", "display_name": "Dr. Jonas Keller"},
        "role": "doctor",
        "practitioner_link": "ARZT-03",
        "patient_confirmed": {"id": "10482", "family": "Beispiel", "given": "Erika", "birth_date": "1961-03-12"},
        "document_hash": PLAN["document_hash"],
        "plan_hash": PLAN["plan_hash"],
        "released_at": "2026-11-04T10:15:30Z",
    }
    with pytest.raises(ValueError):
        build_release(PLAN, member_id="m", role="doctor", practitioner_link="x", patient_confirmed={"id": "1"}, released_at="gestern")


def test_transfers_wait_reports_each_change_once() -> None:
    client, calls, _ = mock_api([json_answer(200, transfer(s)) for s in ("queued", "queued", "delivered", "completed")])
    seen: List[str] = []
    done = client.connect.transfers.wait("trf_7Yc3Nd9LxB", interval=0.001, on_update=lambda t: seen.append(t.status))
    assert done.status == "completed" and seen == ["queued", "delivered", "completed"] and len(calls) == 4


def test_transfers_wait_stops_at_in_doubt_and_times_out() -> None:
    client, _, _ = mock_api([json_answer(200, transfer("in_doubt"))])
    assert client.connect.transfers.wait("trf_1").status == "in_doubt"
    client, _, _ = mock_api([json_answer(200, transfer("waiting_for_vendor")) for _ in range(100)])
    with pytest.raises(TransferWaitTimeoutError) as err:
        client.connect.transfers.wait("trf_1", interval=0.001, timeout=0.02)
    assert err.value.transfer.status == "waiting_for_vendor"


def test_transcriptions_wait() -> None:
    def t(status: str) -> Dict[str, Any]:
        return {"id": "tr_1", "object": "transcription", "status": status, "created_at": "2026-11-16T09:00:00Z", "result": None}

    client, _, _ = mock_api([json_answer(200, t("queued")), json_answer(200, t("completed"))])
    assert client.audio.transcriptions.wait("tr_1", interval=0.001).status == "completed"
    client, _, _ = mock_api([json_answer(200, t("processing")) for _ in range(100)])
    with pytest.raises(TranscriptionWaitTimeoutError):
        client.audio.transcriptions.wait("tr_1", interval=0.001, timeout=0.02)


def event(i: str, kind: str = "entry.filed") -> Dict[str, Any]:
    return {"id": i, "type": kind, "site_id": "ste_1", "connection_id": "con_1", "created_at": "2026-11-04T09:24:13Z", "data": {}}


def test_events_poll_keeps_the_cursor_and_stops() -> None:
    stop = threading.Event()

    def last(request: httpx.Request) -> httpx.Response:
        stop.set()
        return json_answer(200, {"data": [], "next_cursor": "c3"})

    client, calls, _ = mock_api(
        [
            json_answer(200, {"data": [event("evt_1"), event("evt_2")], "next_cursor": "c2"}),
            json_answer(200, {"data": [], "next_cursor": "c2"}),
            json_answer(200, {"data": [event("evt_3", "entry.archived")], "next_cursor": "c3"}),
            last,
        ]
    )
    pages = [
        [f"{e.id}:{e.type}" for e in page.data]
        for page in client.connect.events.poll(site_id="ste_1", cursor="c1", interval=0.001, stop=stop)
    ]
    assert pages == [["evt_1:entry.filed", "evt_2:entry.filed"], ["evt_3:entry.archived"]]
    assert [c.url.params.get("cursor") for c in calls] == ["c1", "c2", "c2", "c3"]


CAPS = {
    "connection_id": "con_1",
    "system": "duria",
    "adapter": "duria@0.2.0",
    "as_of": "2026-11-04T09:00:00Z",
    "source": "live",
    "verbs": {
        "record.write": {"status": "supported"},
        "diagnosis.write.text": {"status": "partial", "limits": ["text_only"]},
        "diagnosis.write": {"status": "unsupported", "reason": "system_cannot", "alternative": "diagnosis.write.text"},
        "patient.search": {"status": "deprecated"},
        "medication.order": {"status": "supported", "granted": False},
    },
}


def test_capabilities_view() -> None:
    client, calls, _ = mock_api([json_answer(200, CAPS)])
    caps = client.connect.capabilities.for_connection("con_1")
    assert caps.supports("record.write") and not caps.supports("patient.search")
    assert caps.callable() == ["diagnosis.write.text", "record.write"]
    assert caps.limits("diagnosis.write.text") == ["text_only"]
    with pytest.raises(NotSupportedError) as unsupported:
        caps.require("diagnosis.write")
    assert unsupported.value.alternative == "diagnosis.write.text"
    with pytest.raises(VerbNotGrantedError):
        caps.require("medication.order")
    with pytest.raises(CapabilityError) as unknown:
        caps.require("patient.search")
    assert unknown.value.code == "capability_status_unknown"
    assert len(calls) == 1


async def test_async_helpers() -> None:
    client, _, _ = mock_async_api([json_answer(200, CAPS), json_answer(200, transfer("queued")), json_answer(200, transfer("completed"))])
    caps = client.connect.capabilities.for_connection("con_1")
    assert await caps.supports("record.write")
    assert (await client.connect.transfers.wait("trf_1", interval=0.001)).status == "completed"
    await client.close()
