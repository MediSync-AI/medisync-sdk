package me.medisync.errors;

import me.medisync.models.connect.Transfer;

/** <code>connect.transfers.wait</code> ran out of time. {@link #getTransfer()} is the last state it read. */
public class TransferWaitTimeoutException extends MediSyncException {
  private static final long serialVersionUID = 1L;

  private final transient Transfer transfer;

  /** An exception with the last state of the transfer. */
  public TransferWaitTimeoutException(Transfer transfer) {
    super("Transfer " + transfer.getId() + " is still " + transfer.getStatus() + ".");
    this.transfer = transfer;
  }

  /** The last state the wait read. */
  public Transfer getTransfer() {
    return transfer;
  }
}
