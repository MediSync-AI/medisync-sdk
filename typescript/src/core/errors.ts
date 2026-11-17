// Typed errors. Every answer of the API outside 2xx becomes an APIError subclass, picked by
// `error.code` first, then by `error.type`, then by the HTTP status. The platform API and the
// gateway use one body shape; MediSync Connect 4xx answers have their own fields (verb, reason,
// licence). Both carry `error.code` and `error.request_id`.

/** The `error` object of an error body. Fields beyond code, type, message and param come from MediSync Connect. */
export interface ErrorObject {
  type?: string;
  code: string;
  message: string;
  param?: string | null;
  request_id?: string;
  doc_url?: string;
  verb?: string;
  connection_id?: string;
  reason?: string;
  alternative?: string;
  retryable?: boolean;
  retry_after?: number;
  target_error?: { vendor_code?: string; message?: string };
  licence?: { connector: string; reason: string };
  [key: string]: unknown;
}

/** Base of every error the SDK throws. */
export class MediSyncError extends Error {
  constructor(message: string, options?: { cause?: unknown }) {
    super(message, options);
    this.name = new.target.name;
  }
}

/** The API answered with a status outside 2xx. */
export class APIError extends MediSyncError {
  /** HTTP status. */
  readonly status: number;
  /** The exact reason, for example `missing_scope`. New codes can appear; then use `type` or `status`. */
  readonly code: string;
  /** The class of error, for example `permission_error`. */
  readonly type: string;
  /** The parameter or header the error is about, if any. */
  readonly param: string | null;
  /** Quote it to support. */
  readonly requestId: string | null;
  /** The `error` object of the body, or null when the body had none. */
  readonly error: ErrorObject | null;
  readonly headers: Headers;

  constructor(status: number, error: ErrorObject | null, headers: Headers, requestId: string | null, fallback?: string) {
    super(error?.message ?? fallback ?? `HTTP ${status}`);
    this.status = status;
    this.code = error?.code ?? 'unknown';
    this.type = error?.type ?? 'api_error';
    this.param = error?.param ?? null;
    this.requestId = requestId;
    this.error = error;
    this.headers = headers;
  }

  /** True when the same request may succeed later. */
  get retryable(): boolean {
    return this.error?.retryable ?? (this.status === 408 || this.status === 429 || this.status >= 500);
  }
}

export class InvalidRequestError extends APIError {}
export class AuthenticationError extends APIError {}
export class PermissionDeniedError extends APIError {}
/** `product_not_enabled`: the product, for example MediSync Connect, is off for the key's project. */
export class ProductNotEnabledError extends PermissionDeniedError {}
export class NotFoundError extends APIError {}
export class ConflictError extends APIError {}
/** MediSync Connect: the document went to this patient and connection before. */
export class AlreadyTransferredError extends ConflictError {}
/** MediSync Connect: the slots or the adapter changed since the plan. Plan again. */
export class PlanStaleError extends ConflictError {}
/** MediSync Connect: the plan is older than 30 minutes. Plan again. */
export class PlanExpiredError extends ConflictError {}
/** A body or file over the size limit. */
export class RequestTooLargeError extends APIError {}

/** MediSync Connect: the connection cannot run a verb, or the app may not use it. */
export class CapabilityError extends APIError {
  get verb(): string | undefined {
    return this.error?.verb;
  }
  get reason(): string | undefined {
    return this.error?.reason;
  }
  /** A verb that can do the job instead, if one exists. */
  get alternative(): string | undefined {
    return this.error?.alternative;
  }
}
/** `verb_unsupported`: the system or its protocol cannot do this verb. */
export class NotSupportedError extends CapabilityError {}
/** `verb_unverified`: built or planned, not proven on this system. */
export class UnverifiedVerbError extends CapabilityError {}
/** `verb_not_granted`: the practice admin has not granted the verb, or the role may not release it. */
export class VerbNotGrantedError extends CapabilityError {}

/** MediSync Connect `licence_required`: no active licence for the connector, or its seats are used up. */
export class LicenceRequiredError extends APIError {
  get licence(): ErrorObject['licence'] {
    return this.error?.licence;
  }
}

export class RateLimitError extends APIError {
  /** Seconds to wait, from the `retry-after` header or the body. */
  get retryAfter(): number | undefined {
    const header = Number(this.headers.get('retry-after'));
    return Number.isFinite(header) && header > 0 ? header : this.error?.retry_after;
  }
}
export class InternalServerError extends APIError {}
/** MediSync Connect: the operation is not built for this system yet. */
export class OperationNotImplementedError extends APIError {}
/** The practice or hospital system answered with an error, or could not be reached. */
export class TargetError extends APIError {
  get targetError(): ErrorObject['target_error'] {
    return this.error?.target_error;
  }
}
/** A service is busy or down. Retry after `retry-after`. */
export class ServiceUnavailableError extends APIError {}
/** MediSync Connect: the hub of the site is offline. Reads cannot run; writes queue. */
export class AgentOfflineError extends ServiceUnavailableError {}
/** The gateway waited too long for a service. Retry with the same Idempotency-Key. */
export class GatewayTimeoutError extends APIError {}

/** No answer: a network failure after all retries. */
export class APIConnectionError extends MediSyncError {}
/** No answer within the timeout, after all retries. */
export class APITimeoutError extends APIConnectionError {}

type ErrorClass = new (status: number, error: ErrorObject | null, headers: Headers, requestId: string | null, fallback?: string) => APIError;

/** Codes with their own class. Codes not listed fall back to the type, then the status. */
const BY_CODE: Record<string, ErrorClass> = {
  product_not_enabled: ProductNotEnabledError,
  already_transferred: AlreadyTransferredError,
  plan_stale: PlanStaleError,
  plan_expired: PlanExpiredError,
  verb_unsupported: NotSupportedError,
  verb_unverified: UnverifiedVerbError,
  verb_not_granted: VerbNotGrantedError,
  licence_required: LicenceRequiredError,
  not_implemented: OperationNotImplementedError,
  agent_offline: AgentOfflineError,
  target_refused: TargetError,
  connection_failed: TargetError,
  payload_too_large: RequestTooLargeError,
  // MediSync Connect codes whose type is shared with other statuses.
  unauthenticated: AuthenticationError,
  invalid_token: AuthenticationError,
  unavailable: ServiceUnavailableError,
  internal: InternalServerError,
};

const BY_TYPE: Record<string, ErrorClass> = {
  invalid_request_error: InvalidRequestError,
  authentication_error: AuthenticationError,
  permission_error: PermissionDeniedError,
  not_found_error: NotFoundError,
  conflict_error: ConflictError,
  request_too_large: RequestTooLargeError,
  rate_limit_error: RateLimitError,
  api_error: InternalServerError,
  overloaded_error: ServiceUnavailableError,
  timeout_error: GatewayTimeoutError,
  target_error: TargetError,
  capability_error: CapabilityError,
  licence_error: LicenceRequiredError,
};

function byStatus(status: number): ErrorClass {
  if (status === 401) return AuthenticationError;
  if (status === 403) return PermissionDeniedError;
  if (status === 404) return NotFoundError;
  if (status === 409) return ConflictError;
  if (status === 413) return RequestTooLargeError;
  if (status === 422) return CapabilityError;
  if (status === 429) return RateLimitError;
  if (status === 501) return OperationNotImplementedError;
  if (status === 502) return TargetError;
  if (status === 503) return ServiceUnavailableError;
  if (status === 504) return GatewayTimeoutError;
  if (status >= 500) return InternalServerError;
  return InvalidRequestError;
}

function errorObject(payload: unknown): ErrorObject | null {
  const e = (payload as { error?: unknown } | null)?.error as Record<string, unknown> | undefined;
  return e && typeof e === 'object' && typeof e.code === 'string' && typeof e.message === 'string' ? (e as ErrorObject) : null;
}

/** The typed error for an answer outside 2xx. `payload` is the parsed body, or its text. */
export function errorFromResponse(status: number, payload: unknown, headers: Headers = new Headers()): APIError {
  const error = errorObject(payload);
  const outer = (payload as { request_id?: unknown } | null)?.request_id;
  const requestId = error?.request_id ?? (typeof outer === 'string' ? outer : null) ?? headers.get('request-id') ?? headers.get('medisync-request-id');
  const Class = (error && (BY_CODE[error.code] ?? (error.type ? BY_TYPE[error.type] : undefined))) || byStatus(status);
  const fallback = typeof payload === 'string' && payload ? payload.slice(0, 200) : undefined;
  return new Class(status, error, headers, requestId ?? null, fallback);
}
