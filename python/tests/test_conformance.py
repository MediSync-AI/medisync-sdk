"""Runs the shared conformance cases of conformance/cases against the mock server, with the sync and the async client."""

from __future__ import annotations

import asyncio
import json
import keyword
import re
import time
from typing import Any, Dict, List, Optional, Tuple

import pytest

from medisync import AsyncMediSync, MediSync
from medisync._models import BaseModel

from .conftest import ROOT, Mock

CASES = ROOT / "conformance/cases"
NAMES = {o["typescript"]: o["python"] for o in json.loads((ROOT / "conformance/operations.json").read_text())["operations"]}
RESERVED = {"self", "idempotency_key", "extra_headers", "extra_query", "extra_body", "timeout"}


def load(name: str) -> List[Dict[str, Any]]:
    return list(json.loads((CASES / name).read_text())["cases"])


HTTP_CASES = [c for f in sorted(p.name for p in CASES.glob("*.json")) if f != "live.json" for c in load(f)]
LIVE_CASES = load("live.json")


def kw(name: str) -> str:
    return name + "_" if keyword.iskeyword(name) or name in RESERVED else name


def value(v: Any) -> Any:
    if isinstance(v, list):
        return [value(x) for x in v]
    if isinstance(v, dict):
        if "$file" in v:
            return (v["$file"]["filename"], bytes(v["$file"]["bytes"]))
        return {k: value(x) for k, x in v.items()}
    return v


def method(client: Any, dotted: str) -> Any:
    node = client
    for part in NAMES.get(dotted, dotted).split("."):
        node = getattr(node, part)
    return node


def call_args(spec: Dict[str, Any]) -> Tuple[List[Any], Dict[str, Any]]:
    args = spec.get("args", {})
    kwargs: Dict[str, Any] = {}
    for key, v in {**args.get("body", {}), **args.get("query", {})}.items():
        kwargs[kw(key)] = value(v)
    options = args.get("options", {})
    if "idempotencyKey" in options:
        kwargs["idempotency_key"] = options["idempotencyKey"]
    return list(args.get("path", [])), kwargs


def as_data(result: Any) -> Any:
    if isinstance(result, BaseModel):
        return result.to_dict()
    return result


def matches(expected: Any, actual: Any) -> bool:
    if isinstance(expected, str) and expected.startswith("~"):
        return isinstance(actual, str) and re.search(expected[1:], actual) is not None
    return bool(expected == actual)


def partial(expected: Any, actual: Any, where: str) -> None:
    if isinstance(expected, dict):
        assert isinstance(actual, dict), f"{where}: expected an object, got {actual!r}"
        for k, v in expected.items():
            partial(v, actual.get(k), f"{where}.{k}")
        return
    assert matches(expected, actual), f"{where}: expected {expected!r}, got {actual!r}"


def check_request(exp: Dict[str, Any], req: Optional[Dict[str, Any]], i: int) -> None:
    at = f"request {i}"
    assert req is not None, f"{at}: missing"
    if "method" in exp:
        assert req["method"] == exp["method"], at
    if "path" in exp:
        assert req["path"] == exp["path"], f"{at}: path {req['path']}"
    if "query" in exp:
        partial(exp["query"], req["query"], f"{at}.query")
    for k in exp.get("absentQuery", []):
        assert k not in req["query"], f"{at}: query {k} must be absent"
    if "rawQuery" in exp:
        assert matches(exp["rawQuery"], req["rawQuery"]), f"{at}: raw query {req['rawQuery']}"
    if "headers" in exp:
        partial(exp["headers"], req["headers"], f"{at}.headers")
    for h in exp.get("absentHeaders", []):
        assert h not in req["headers"], f"{at}: header {h} must be absent"
    if "body" in exp:
        assert req["body"] == exp["body"], f"{at}: body {req['body']}"
    if "multipart" in exp:
        partial(exp["multipart"], req.get("multipart"), f"{at}.multipart")
    if "problems" in exp:
        assert req["problems"] == exp["problems"], f"{at}: the mock found problems"


def check_result(exp: Dict[str, Any], result: Any) -> None:
    if exp.get("notNull"):
        assert result is not None
    if exp.get("null"):
        assert result is None
    if "bytes" in exp:
        assert isinstance(result, bytes) and len(result) == exp["bytes"]
    if exp.get("text"):
        assert isinstance(result, str)
    if "textStarts" in exp:
        assert str(result).startswith(exp["textStarts"])
    if "fields" in exp:
        partial(exp["fields"], as_data(result), "result")
    if "items" in exp:
        assert [x.id for x in result] == exp["items"]
    if "pageIds" in exp:
        assert [x.id for x in result.data] == exp["pageIds"]
    if "nextCursor" in exp:
        assert result.next_cursor == exp["nextCursor"]


ERROR_FIELDS = {
    "status": "status",
    "code": "code",
    "type": "type",
    "requestId": "request_id",
    "param": "param",
    "verb": "verb",
    "reason": "reason",
    "alternative": "alternative",
}


def check_error(exp: Dict[str, Any], err: Optional[BaseException]) -> None:
    assert err is not None, f"expected {exp['class']}, got no error"
    assert type(err).__name__ == exp["class"], f"error class: {type(err).__name__} {err}"
    for k, attr in ERROR_FIELDS.items():
        if k in exp:
            assert getattr(err, attr) == exp[k], f"error.{attr}: {getattr(err, attr)!r}"
    if "codeOneOf" in exp:
        assert err.code in exp["codeOneOf"]


def check_sleeps(exp: List[Any], sleeps: List[float]) -> None:
    assert len(sleeps) == len(exp), f"sleeps: {sleeps}"
    for i, e in enumerate(exp):
        ms = sleeps[i] * 1000
        if isinstance(e, (int, float)):
            assert round(ms) == e, f"sleep {i}: {ms}"
        else:
            assert e["min"] <= ms <= e["max"], f"sleep {i}: {ms}"


def check_http(case: Dict[str, Any], mock: Mock, result: Any, error: Optional[BaseException], sleeps: List[float]) -> None:
    exp = case["expect"]
    if "error" not in exp and error is not None:
        raise error
    requests = mock.requests()
    if "requestCount" in exp:
        assert len(requests) == exp["requestCount"], "request count"
    for i, r in enumerate(exp.get("requests", [])):
        check_request(r, requests[i] if i < len(requests) else None, i)
    if "sameHeaderAcrossRequests" in exp:
        assert len({r["headers"].get(exp["sameHeaderAcrossRequests"]) for r in requests}) == 1
    if "sleepsMs" in exp:
        check_sleeps(exp["sleepsMs"], sleeps)
    if "result" in exp:
        check_result(exp["result"], result)
    if "error" in exp:
        check_error(exp["error"], error)


def client_options(case: Dict[str, Any]) -> Dict[str, Any]:
    c = case.get("client", {})
    out: Dict[str, Any] = {"max_retries": c.get("maxRetries", 2)}
    if "version" in c:
        out["version"] = c["version"]
    return out


@pytest.mark.parametrize("case", HTTP_CASES, ids=[c["name"] for c in HTTP_CASES])
def test_sync(case: Dict[str, Any], mock: Mock) -> None:
    mock.reset(case.get("responses"))
    sleeps: List[float] = []
    client = MediSync(api_key="msk_test_conformance", base_url=mock.url, _sleep=sleeps.append, **client_options(case))
    spec = case["call"]
    path, kwargs = call_args(spec)
    result: Any = None
    error: Optional[BaseException] = None
    try:
        result = method(client, spec["method"])(*path, **kwargs)
        if spec.get("mode") == "iterate":
            result = list(result)
    except Exception as e:  # noqa: BLE001
        error = e
    check_http(case, mock, result, error, sleeps)


@pytest.mark.parametrize("case", HTTP_CASES, ids=[c["name"] for c in HTTP_CASES])
async def test_async(case: Dict[str, Any], mock: Mock) -> None:
    mock.reset(case.get("responses"))
    sleeps: List[float] = []

    async def sleep(seconds: float) -> None:
        sleeps.append(seconds)

    client = AsyncMediSync(api_key="msk_test_conformance", base_url=mock.url, _sleep=sleep, **client_options(case))
    spec = case["call"]
    path, kwargs = call_args(spec)
    result: Any = None
    error: Optional[BaseException] = None
    try:
        answer = method(client, spec["method"])(*path, **kwargs)
        if spec.get("mode") == "iterate":
            result = [x async for x in answer]
        else:
            result = await answer
    except Exception as e:  # noqa: BLE001
        error = e
    finally:
        await client.close()
    check_http(case, mock, result, error, sleeps)


def check_live(
    case: Dict[str, Any], mock: Mock, events: List[Any], error: Optional[BaseException], finish_ms: Optional[float] = None
) -> None:
    exp = case["expect"]
    if "error" not in exp and error is not None:
        raise error
    if "error" in exp:
        check_error(exp["error"], error)
    log = mock.ws()
    if "connectionCount" in exp:
        assert len(log) == exp["connectionCount"], f"connections: {len(log)}"
    for i, e in enumerate(exp.get("connections", [])):
        conn = log[i]
        if "headers" in e:
            partial(e["headers"], conn["headers"], f"connection {i}.headers")
        for h in e.get("absentHeaders", []):
            assert h not in conn["headers"], f"connection {i}: header {h} must be absent"
        if "query" in e:
            partial(e["query"], conn["query"], f"connection {i}.query")
        for q in e.get("absentQuery", []):
            assert q not in conn["query"]
        if "config" in e:
            partial(e["config"], conn["config"], f"connection {i}.config")
        if "audioBytes" in e:
            assert conn["audioBytes"] == e["audioBytes"], f"connection {i}: audio bytes {conn['audioBytes']}"
        if "firstAudioAt" in e:
            assert abs(conn["firstAudioAt"] - e["firstAudioAt"]) < 1e-6
        if "messageTypes" in e:
            assert [m["type"] for m in conn["messages"]] == e["messageTypes"]
        if "terms" in e:
            assert next(m for m in conn["messages"] if m["type"] == "terms")["terms"] == e["terms"]
    types = [ev.type for ev in events]
    if "eventTypes" in exp:
        assert types == exp["eventTypes"]
    for t in exp.get("eventTypesInclude", []):
        assert t in types, f"{t} missing in {types}"
    if "finishMs" in exp:
        assert finish_ms is not None, "finish did not return"
        if "min" in exp["finishMs"]:
            assert finish_ms >= exp["finishMs"]["min"], f"finish took {finish_ms:.0f} ms"
        if "max" in exp["finishMs"]:
            assert finish_ms <= exp["finishMs"]["max"], f"finish took {finish_ms:.0f} ms"
    if "lastSegmentEnd" in exp:
        assert max(ev.segment.end for ev in events if ev.type == "transcript") == exp["lastSegmentEnd"]


def finish_args(step: Dict[str, Any]) -> Dict[str, Any]:
    return {"flush_timeout": step["flushTimeoutMs"] / 1000} if "flushTimeoutMs" in step else {}


def frames(step: Dict[str, Any]) -> List[bytes]:
    frame = bytes(round(step["frameSeconds"] * 32000))
    return [frame] * round(step["seconds"] / step["frameSeconds"])


@pytest.mark.parametrize("case", LIVE_CASES, ids=[c["name"] for c in LIVE_CASES])
async def test_live_async(case: Dict[str, Any], mock: Mock) -> None:
    mock.reset(ws=case.get("ws"))
    client = AsyncMediSync(api_key="msk_test_conformance", base_url=mock.url)
    events: List[Any] = []
    error: Optional[BaseException] = None
    reader: Optional[asyncio.Task[None]] = None
    finish_ms: Optional[float] = None
    try:
        stream: Any = None
        for step in case["steps"]:
            if "open" in step:
                stream = client.audio.stream.open(config=step["open"].get("config"), token=step["open"].get("token"))
                await stream.open()

                async def read(s: Any = stream) -> None:
                    async for ev in s:
                        events.append(ev)

                reader = asyncio.ensure_future(read())
            elif "setTerms" in step:
                await stream.set_terms(step["setTerms"])
            elif "sendAudio" in step:
                for f in frames(step["sendAudio"]):
                    await stream.send(f)
            elif "endUtterance" in step:
                await stream.end_utterance()
            elif "finish" in step:
                started = time.monotonic()
                await asyncio.wait_for(stream.finish(**finish_args(step["finish"])), 20)
                finish_ms = (time.monotonic() - started) * 1000
            elif "close" in step:
                await stream.close()
        if reader is not None:
            await asyncio.wait_for(reader, 20)
    except Exception as e:  # noqa: BLE001
        error = e
    finally:
        await client.close()
    check_live(case, mock, events, error, finish_ms)


@pytest.mark.parametrize("case", LIVE_CASES, ids=[c["name"] for c in LIVE_CASES])
def test_live_sync(case: Dict[str, Any], mock: Mock) -> None:
    import threading

    mock.reset(ws=case.get("ws"))
    client = MediSync(api_key="msk_test_conformance", base_url=mock.url)
    events: List[Any] = []
    error: Optional[BaseException] = None
    reader: Optional[threading.Thread] = None
    finish_ms: Optional[float] = None
    try:
        stream: Any = None
        for step in case["steps"]:
            if "open" in step:
                stream = client.audio.stream.open(config=step["open"].get("config"), token=step["open"].get("token"))
                stream.open()

                def read(s: Any = stream) -> None:
                    for ev in s:
                        events.append(ev)

                reader = threading.Thread(target=read, daemon=True)
                reader.start()
            elif "setTerms" in step:
                stream.set_terms(step["setTerms"])
            elif "sendAudio" in step:
                for f in frames(step["sendAudio"]):
                    stream.send(f)
            elif "endUtterance" in step:
                stream.end_utterance()
            elif "finish" in step:
                started = time.monotonic()
                stream.finish(timeout=20, **finish_args(step["finish"]))
                finish_ms = (time.monotonic() - started) * 1000
            elif "close" in step:
                stream.close()
        if reader is not None:
            reader.join(20)
    except Exception as e:  # noqa: BLE001
        error = e
    finally:
        client.close()
    check_live(case, mock, events, error, finish_ms)
