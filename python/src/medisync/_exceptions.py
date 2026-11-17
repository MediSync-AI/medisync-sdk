"""Typed errors.

Every answer of the API outside 2xx becomes an `APIError` subclass, picked by `error.code` first,
then by `error.type`, then by the HTTP status. The platform API and the gateway use one body
shape; MediSync Connect 4xx answers add their own fields (verb, reason, licence). Both carry
`error.code` and `error.request_id`.
"""

from __future__ import annotations

from typing import Any, Dict, Mapping, Optional, Type, cast

import httpx

__all__ = [
    "MediSyncError",
    "APIError",
    "InvalidRequestError",
    "AuthenticationError",
    "PermissionDeniedError",
    "ProductNotEnabledError",
    "NotFoundError",
    "ConflictError",
    "AlreadyTransferredError",
    "PlanStaleError",
    "PlanExpiredError",
    "RequestTooLargeError",
    "CapabilityError",
    "NotSupportedError",
    "UnverifiedVerbError",
    "VerbNotGrantedError",
    "LicenceRequiredError",
    "RateLimitError",
    "InternalServerError",
    "OperationNotImplementedError",
    "TargetError",
    "ServiceUnavailableError",
    "AgentOfflineError",
    "GatewayTimeoutError",
    "APIConnectionError",
    "APITimeoutError",
    "error_from_response",
]


class MediSyncError(Exception):
    """Base of every error the SDK raises."""


class APIError(MediSyncError):
    """The API answered with a status outside 2xx."""

    status: int
    """HTTP status."""
    code: str
    """The exact reason, for example `missing_scope`. New codes can appear; then use `type` or `status`."""
    type: str
    """The class of error, for example `permission_error`."""
    param: Optional[str]
    """The parameter or header the error is about, if any."""
    request_id: Optional[str]
    """Quote it to support."""
    error: Optional[Dict[str, Any]]
    """The `error` object of the body, or None when the body had none."""
    headers: httpx.Headers

    def __init__(
        self,
        status: int,
        error: Optional[Dict[str, Any]],
        headers: httpx.Headers,
        request_id: Optional[str],
        fallback: Optional[str] = None,
    ) -> None:
        message = str(error["message"]) if error else (fallback or f"HTTP {status}")
        super().__init__(message)
        self.message = message
        self.status = status
        self.code = str(error["code"]) if error else "unknown"
        self.type = str(error.get("type") or "api_error") if error else "api_error"
        param = error.get("param") if error else None
        self.param = param if isinstance(param, str) else None
        self.request_id = request_id
        self.error = error
        self.headers = headers

    @property
    def retryable(self) -> bool:
        """True when the same request may succeed later."""
        value = self.error.get("retryable") if self.error else None
        if isinstance(value, bool):
            return value
        return self.status in (408, 429) or self.status >= 500

    def __repr__(self) -> str:
        return f"{self.__class__.__name__}(status={self.status}, code={self.code!r}, request_id={self.request_id!r})"


class InvalidRequestError(APIError):
    pass


class AuthenticationError(APIError):
    pass


class PermissionDeniedError(APIError):
    pass


class ProductNotEnabledError(PermissionDeniedError):
    """`product_not_enabled`: the product, for example MediSync Connect, is off for the key's project."""


class NotFoundError(APIError):
    pass


class ConflictError(APIError):
    pass


class AlreadyTransferredError(ConflictError):
    """MediSync Connect: the document went to this patient and connection before."""


class PlanStaleError(ConflictError):
    """MediSync Connect: the slots or the adapter changed since the plan. Plan again."""


class PlanExpiredError(ConflictError):
    """MediSync Connect: the plan is older than 30 minutes. Plan again."""


class RequestTooLargeError(APIError):
    """A body or file over the size limit."""


class CapabilityError(APIError):
    """MediSync Connect: the connection cannot run a verb, or the app may not use it."""

    @property
    def verb(self) -> Optional[str]:
        return _str(self.error, "verb")

    @property
    def reason(self) -> Optional[str]:
        return _str(self.error, "reason")

    @property
    def alternative(self) -> Optional[str]:
        """A verb that can do the job instead, if one exists."""
        return _str(self.error, "alternative")


class NotSupportedError(CapabilityError):
    """`verb_unsupported`: the system or its protocol cannot do this verb."""


class UnverifiedVerbError(CapabilityError):
    """`verb_unverified`: built or planned, not proven on this system."""


class VerbNotGrantedError(CapabilityError):
    """`verb_not_granted`: the practice admin has not granted the verb, or the role may not release it."""


class LicenceRequiredError(APIError):
    """MediSync Connect `licence_required`: no active licence for the connector, or its seats are used up."""

    @property
    def licence(self) -> Optional[Dict[str, Any]]:
        value = self.error.get("licence") if self.error else None
        return cast(Dict[str, Any], value) if isinstance(value, dict) else None


class RateLimitError(APIError):
    @property
    def retry_after(self) -> Optional[float]:
        """Seconds to wait, from the `retry-after` header or the body."""
        header = self.headers.get("retry-after")
        try:
            if header is not None and float(header) > 0:
                return float(header)
        except ValueError:
            pass
        value = self.error.get("retry_after") if self.error else None
        return float(value) if isinstance(value, (int, float)) else None


class InternalServerError(APIError):
    pass


class OperationNotImplementedError(APIError):
    """MediSync Connect: the operation is not built for this system yet."""


class TargetError(APIError):
    """The practice or hospital system answered with an error, or could not be reached."""

    @property
    def target_error(self) -> Optional[Dict[str, Any]]:
        value = self.error.get("target_error") if self.error else None
        return cast(Dict[str, Any], value) if isinstance(value, dict) else None


class ServiceUnavailableError(APIError):
    """A service is busy or down. Retry after `retry-after`."""


class AgentOfflineError(ServiceUnavailableError):
    """MediSync Connect: the hub of the site is offline. Reads cannot run; writes queue."""


class GatewayTimeoutError(APIError):
    """The gateway waited too long for a service. Retry with the same Idempotency-Key."""


class APIConnectionError(MediSyncError):
    """No answer: a network failure after all retries."""


class APITimeoutError(APIConnectionError):
    """No answer within the timeout, after all retries."""


def _str(error: Optional[Dict[str, Any]], key: str) -> Optional[str]:
    value = error.get(key) if error else None
    return value if isinstance(value, str) else None


_BY_CODE: Dict[str, Type[APIError]] = {
    "product_not_enabled": ProductNotEnabledError,
    "already_transferred": AlreadyTransferredError,
    "plan_stale": PlanStaleError,
    "plan_expired": PlanExpiredError,
    "verb_unsupported": NotSupportedError,
    "verb_unverified": UnverifiedVerbError,
    "verb_not_granted": VerbNotGrantedError,
    "licence_required": LicenceRequiredError,
    "not_implemented": OperationNotImplementedError,
    "agent_offline": AgentOfflineError,
    "target_refused": TargetError,
    "connection_failed": TargetError,
    "payload_too_large": RequestTooLargeError,
    # MediSync Connect codes whose type is shared with other statuses.
    "unauthenticated": AuthenticationError,
    "invalid_token": AuthenticationError,
    "unavailable": ServiceUnavailableError,
    "internal": InternalServerError,
}

_BY_TYPE: Dict[str, Type[APIError]] = {
    "invalid_request_error": InvalidRequestError,
    "authentication_error": AuthenticationError,
    "permission_error": PermissionDeniedError,
    "not_found_error": NotFoundError,
    "conflict_error": ConflictError,
    "request_too_large": RequestTooLargeError,
    "rate_limit_error": RateLimitError,
    "api_error": InternalServerError,
    "overloaded_error": ServiceUnavailableError,
    "timeout_error": GatewayTimeoutError,
    "target_error": TargetError,
    "capability_error": CapabilityError,
    "licence_error": LicenceRequiredError,
}

_BY_STATUS: Mapping[int, Type[APIError]] = {
    401: AuthenticationError,
    403: PermissionDeniedError,
    404: NotFoundError,
    409: ConflictError,
    413: RequestTooLargeError,
    422: CapabilityError,
    429: RateLimitError,
    501: OperationNotImplementedError,
    502: TargetError,
    503: ServiceUnavailableError,
    504: GatewayTimeoutError,
}


def _by_status(status: int) -> Type[APIError]:
    if status in _BY_STATUS:
        return _BY_STATUS[status]
    return InternalServerError if status >= 500 else InvalidRequestError


def _error_object(payload: object) -> Optional[Dict[str, Any]]:
    if not isinstance(payload, dict):
        return None
    error = cast(Dict[str, Any], payload).get("error")
    if isinstance(error, dict):
        e = cast(Dict[str, Any], error)
        if isinstance(e.get("code"), str) and isinstance(e.get("message"), str):
            return e
    return None


def error_from_response(status: int, payload: object, headers: Optional[httpx.Headers] = None) -> APIError:
    """The typed error for an answer outside 2xx. `payload` is the parsed body, or its text."""
    headers = headers if headers is not None else httpx.Headers()
    error = _error_object(payload)
    request_id: Optional[str] = None
    if error and isinstance(error.get("request_id"), str):
        request_id = error["request_id"]
    elif isinstance(payload, dict) and isinstance(cast(Dict[str, Any], payload).get("request_id"), str):
        request_id = cast(Dict[str, Any], payload)["request_id"]
    else:
        request_id = headers.get("request-id") or headers.get("medisync-request-id")
    cls: Optional[Type[APIError]] = None
    if error:
        cls = _BY_CODE.get(str(error["code"])) or _BY_TYPE.get(str(error.get("type") or ""))
    fallback = payload[:200] if isinstance(payload, str) and payload else None
    return (cls or _by_status(status))(status, error, headers, request_id, fallback)
