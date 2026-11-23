package me.medisync.audio;

/**
 * The answer to one <code>end_utterance</code>, after every transcript of the audio it flushed. A
 * segment that failed sent an <code>error</code> with code <code>asr_failed</code> (not fatal) in
 * place of its transcript. With speaker roles on, a final <code>speaker_roles</code> message can
 * still follow. Several <code>end_utterance</code> messages get one <code>flushed</code> each, in
 * the same order. Servers before 23.11.2026 do not send it.
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
