"""The MediSync Python SDK.

    from medisync import MediSync

    client = MediSync()  # reads MEDISYNC_API_KEY
    models = client.models.list()

Everything the API generates is a draft that a clinician reviews and releases.
"""

from ._app_auth import AppAuthError, AppCredentials, AppTokenSource, AsyncAppTokenSource
from ._client import AsyncMediSync, MediSync
from ._core import DEFAULT_BASE_URL, ResponseInfo
from ._exceptions import (
    AgentOfflineError,
    AlreadyTransferredError,
    APIConnectionError,
    APIError,
    APITimeoutError,
    AuthenticationError,
    CapabilityError,
    ConflictError,
    GatewayTimeoutError,
    InternalServerError,
    InvalidRequestError,
    LicenceRequiredError,
    MediSyncError,
    NotFoundError,
    NotSupportedError,
    OperationNotImplementedError,
    PermissionDeniedError,
    PlanExpiredError,
    PlanStaleError,
    ProductNotEnabledError,
    RateLimitError,
    RequestTooLargeError,
    ServiceUnavailableError,
    TargetError,
    UnverifiedVerbError,
    VerbNotGrantedError,
)
from ._files import FileTypes
from ._generated.operations import API_VERSION, HELPERS, OPERATIONS
from ._helpers.follow import TransferWaitTimeoutError
from ._helpers.transcriptions import TranscriptionWaitTimeoutError
from ._live import FLUSH_TIMEOUT, AsyncLiveStream, LiveStream, LiveStreamError, StreamFlushed, StreamReconnecting
from ._pagination import AsyncCursorPage, AsyncPaginator, SyncCursorPage
from ._types import NOT_GIVEN, NotGiven
from ._version import __version__

__all__ = [
    "MediSync",
    "AsyncMediSync",
    "DEFAULT_BASE_URL",
    "API_VERSION",
    "OPERATIONS",
    "HELPERS",
    "ResponseInfo",
    "NOT_GIVEN",
    "NotGiven",
    "FileTypes",
    "SyncCursorPage",
    "AsyncCursorPage",
    "AsyncPaginator",
    "LiveStream",
    "AsyncLiveStream",
    "LiveStreamError",
    "StreamFlushed",
    "StreamReconnecting",
    "FLUSH_TIMEOUT",
    "AppCredentials",
    "AppTokenSource",
    "AsyncAppTokenSource",
    "AppAuthError",
    "TransferWaitTimeoutError",
    "TranscriptionWaitTimeoutError",
    "MediSyncError",
    "APIError",
    "APIConnectionError",
    "APITimeoutError",
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
    "__version__",
]
