"""A cached view of what one MediSync Connect connection supports."""

from __future__ import annotations

from typing import Any, Awaitable, Callable, Dict, List, Optional

from .._exceptions import error_from_response
from .._generated.connect_types import CapabilitySet, VerbCapability

KNOWN_STATUSES = frozenset({"supported", "partial", "unverified", "unsupported"})


def _callable(cap: VerbCapability, allow_unverified: bool) -> bool:
    status_ok = cap.status in ("supported", "partial") or (allow_unverified and cap.status == "unverified")
    return status_ok and cap.granted is not False and cap.licensed is not False


def _verb(caps: CapabilitySet, verb: str) -> VerbCapability:
    verbs: Dict[str, Any] = dict(caps.verbs) if isinstance(caps.verbs, dict) else {}
    value = verbs.get(verb)
    if isinstance(value, VerbCapability):
        return value
    if isinstance(value, dict):
        return VerbCapability.model_validate(value)
    return VerbCapability(status="unsupported")


def _check(connection_id: str, verb: str, cap: VerbCapability, allow_unverified: bool) -> None:
    base: Dict[str, Any] = {"request_id": "local", "verb": verb, "connection_id": connection_id}
    if cap.status == "unsupported":
        extra = {k: v for k, v in (("reason", cap.reason), ("alternative", cap.alternative)) if v}
        raise error_from_response(
            422,
            {
                "error": {
                    **base,
                    "type": "capability_error",
                    "code": "verb_unsupported",
                    "message": f"This connection cannot run {verb}.",
                    **extra,
                }
            },
        )
    if cap.status == "unverified" and not allow_unverified:
        extra = {"reason": cap.reason} if cap.reason else {}
        raise error_from_response(
            422,
            {
                "error": {
                    **base,
                    "type": "capability_error",
                    "code": "verb_unverified",
                    "message": f"{verb} is not proven on this system.",
                    **extra,
                }
            },
        )
    if cap.status not in KNOWN_STATUSES:
        # A status added to the contract after this SDK version: not callable until the SDK knows it.
        raise error_from_response(
            422,
            {
                "error": {
                    **base,
                    "type": "capability_error",
                    "code": "capability_status_unknown",
                    "message": f"{verb} has the status {cap.status}, which this SDK version does not know.",
                }
            },
        )
    if cap.granted is False:
        raise error_from_response(
            403,
            {
                "error": {
                    **base,
                    "type": "capability_error",
                    "code": "verb_not_granted",
                    "message": f"The practice admin has not granted {verb} to this app.",
                }
            },
        )
    if cap.licensed is False:
        raise error_from_response(
            403,
            {"error": {**base, "type": "licence_error", "code": "licence_required", "message": "No active licence for this connector."}},
        )


class Capabilities:
    """What one connection supports. Loads `capabilities.get` once and answers from that copy;
    call `refresh()` after `connection.capabilities_changed`.

        if client.connect.capabilities.for_connection(connection_id).supports("record.write"): ...
    """

    def __init__(self, connection_id: str, fetch: Callable[[], CapabilitySet]) -> None:
        self.connection_id = connection_id
        self._fetch = fetch
        self._set: Optional[CapabilitySet] = None

    def load(self) -> CapabilitySet:
        if self._set is None:
            self._set = self._fetch()
        return self._set

    def refresh(self) -> CapabilitySet:
        self._set = None
        return self.load()

    def verb(self, verb: str) -> VerbCapability:
        """The entry for one verb. A verb the API does not list is unsupported."""
        return _verb(self.load(), verb)

    def supports(self, verb: str, *, allow_unverified: bool = False) -> bool:
        """True when the verb is supported (fully or `partial`), granted to this app and licensed."""
        return _callable(self.verb(verb), allow_unverified)

    def callable(self, *, allow_unverified: bool = False) -> List[str]:
        """Every verb that `supports()` answers True for, sorted."""
        caps = self.load()
        verbs: Dict[str, Any] = dict(caps.verbs) if isinstance(caps.verbs, dict) else {}
        return sorted(v for v in verbs if _callable(_verb(caps, v), allow_unverified))

    def limits(self, verb: str) -> List[str]:
        """The `limits` codes of a `partial` verb, else an empty list."""
        cap = self.verb(verb)
        return list(cap.limits or []) if cap.status == "partial" else []

    def require(self, verb: str, *, allow_unverified: bool = False) -> None:
        """Raises the error the API would answer for this verb, so a UI can explain it before a call."""
        _check(self.connection_id, verb, self.verb(verb), allow_unverified)


class AsyncCapabilities:
    """The async form of `Capabilities`."""

    def __init__(self, connection_id: str, fetch: Callable[[], Awaitable[CapabilitySet]]) -> None:
        self.connection_id = connection_id
        self._fetch = fetch
        self._set: Optional[CapabilitySet] = None

    async def load(self) -> CapabilitySet:
        if self._set is None:
            self._set = await self._fetch()
        return self._set

    async def refresh(self) -> CapabilitySet:
        self._set = None
        return await self.load()

    async def verb(self, verb: str) -> VerbCapability:
        return _verb(await self.load(), verb)

    async def supports(self, verb: str, *, allow_unverified: bool = False) -> bool:
        return _callable(await self.verb(verb), allow_unverified)

    async def callable(self, *, allow_unverified: bool = False) -> List[str]:
        caps = await self.load()
        verbs: Dict[str, Any] = dict(caps.verbs) if isinstance(caps.verbs, dict) else {}
        return sorted(v for v in verbs if _callable(_verb(caps, v), allow_unverified))

    async def limits(self, verb: str) -> List[str]:
        cap = await self.verb(verb)
        return list(cap.limits or []) if cap.status == "partial" else []

    async def require(self, verb: str, *, allow_unverified: bool = False) -> None:
        _check(self.connection_id, verb, await self.verb(verb), allow_unverified)
