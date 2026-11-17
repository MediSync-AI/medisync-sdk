from __future__ import annotations

from dataclasses import dataclass, field
from typing import Mapping, Optional, Tuple

from typing_extensions import Literal


@dataclass(frozen=True)
class OperationMeta:
    """What the client needs to know about one API operation. The generator writes one per operation."""

    api: Literal["platform", "connect"]
    operation_id: str
    method: Literal["GET", "POST", "PUT", "PATCH", "DELETE"]
    path: str
    body: Optional[Literal["json", "multipart"]]
    query: bool
    """The operation takes query parameters."""
    response: Literal["json", "binary", "text", "empty"]
    idempotency: Literal["none", "optional", "required"]
    """`optional` and `required`: the operation takes an Idempotency-Key, and the client sends one."""
    paginated: bool
    comma_query: Tuple[str, ...] = ()
    """Query arrays sent as one comma-separated value."""
    scope: Optional[str] = None
    verb: Optional[str] = None
    phi: Optional[bool] = None
    metered: Optional[str] = None
    usage: Optional[str] = None
    public: Optional[bool] = None


@dataclass(frozen=True)
class RequestOptions:
    """Options of one call. Every generated method takes them as keyword arguments."""

    idempotency_key: Optional[str] = None
    extra_headers: Optional[Mapping[str, str]] = None
    extra_query: Optional[Mapping[str, object]] = None
    extra_body: Optional[Mapping[str, object]] = None
    timeout: Optional[float] = None
    max_retries: Optional[int] = None
    headers: Mapping[str, str] = field(default_factory=lambda: {})
