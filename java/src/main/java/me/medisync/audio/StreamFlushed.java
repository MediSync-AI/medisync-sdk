package me.medisync.audio;

/**
 * The answer to one <code>end_utterance</code>: the server has finished its work on the audio up to
 * <code>processed_until</code>. Audio it dropped under load (<code>backpressure</code>) or could
 * not transcribe (<code>asr_failed</code> or <code>language_unsupported</code>, both not fatal) has
 * no transcript. With speaker roles on, a final <code>speaker_roles</code> usually follows; under
 * load it can come earlier, or not at all for a later <code>end_utterance</code>. Several
 * <code>end_utterance</code> messages get one <code>flushed</code> each, in the same order. Servers
 * before 23.11.2026 do not send it.
 */
public final class StreamFlushed implements StreamEvent {
  private final double processedUntil;

  StreamFlushed(double processedUntil) {
    this.processedUntil = processedUntil;
  }

  @Override
  public String getType() {
    return "flushed";
  }

  /**
   * The same value as the <code>ack</code> of the same <code>end_utterance</code>: seconds of audio
   * the server had read.
   */
  public double getProcessedUntil() {
    return processedUntil;
  }

  @Override
  public String toString() {
    return "StreamFlushed{processedUntil=" + processedUntil + "}";
  }
}
