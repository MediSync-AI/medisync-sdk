package me.medisync.errors;

import me.medisync.models.Transcription;

/** <code>audio.transcriptions.wait</code> ran out of time. {@link #getTranscription()} is the last state it read. */
public class TranscriptionWaitTimeoutException extends MediSyncException {
  private static final long serialVersionUID = 1L;

  private final transient Transcription transcription;

  /** An exception with the last state of the transcription. */
  public TranscriptionWaitTimeoutException(Transcription transcription) {
    super("Transcription " + transcription.getId() + " is still " + transcription.getStatus() + ".");
    this.transcription = transcription;
  }

  /** The last state the wait read. */
  public Transcription getTranscription() {
    return transcription;
  }
}
