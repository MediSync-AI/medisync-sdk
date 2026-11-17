package me.medisync.errors;

/** The token endpoint of MediSync Connect refused: {@link #getError()} is the OAuth error code (RFC 6749 5.2). */
public class AppAuthException extends MediSyncException {
  private static final long serialVersionUID = 1L;

  private final int status;
  private final String error;

  /** An exception with the HTTP status, the OAuth error and its description. */
  public AppAuthException(int status, String error, String description) {
    super(error + ": " + description);
    this.status = status;
    this.error = error;
  }

  /** HTTP status of the token endpoint. */
  public int getStatus() {
    return status;
  }

  /** The OAuth error, for example <code>invalid_client</code>. */
  public String getError() {
    return error;
  }
}
