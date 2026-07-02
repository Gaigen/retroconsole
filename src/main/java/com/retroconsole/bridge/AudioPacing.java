package com.retroconsole.bridge;

/**
 * Audio-based frame pacing for libretro cores.
 *
 * Many libretro cores (including Flycast) use the audio sample consumption
 * rate as their timing signal. If the audio callback always returns
 * immediately (claiming all samples were consumed), the core runs as fast
 * as possible with no throttle.
 *
 * This class tracks how many audio samples have been produced vs how many
 * SHOULD have been produced based on wall-clock time. When the core produces
 * too many samples, the callback sleeps to let "virtual time" catch up.
 */
public class AudioPacing {
    private final int sampleRate;
    private long startTime;
    private long totalSamplesConsumed;
    private boolean started;

    // Allow 2 frames of buffer to absorb jitter
    private static final int BUFFER_SAMPLES = 1472; // ~2 frames at 44100/60

    public AudioPacing(int sampleRate) {
        this.sampleRate = sampleRate;
    }

    /**
     * Called from the audio batch callback on the emulator thread.
     * Blocks if the core is producing audio faster than real-time.
     *
     * @param frames number of audio sample frames consumed
     */
    public void consumeSamples(int frames) {
        if (!started) {
            startTime = System.nanoTime();
            started = true;
        }

        totalSamplesConsumed += frames;

        // How many samples SHOULD we have produced by now?
        long elapsedNs = System.nanoTime() - startTime;
        long expectedSamples = elapsedNs * sampleRate / 1_000_000_000L;

        // If we're ahead of real-time, sleep to let it catch up
        long ahead = totalSamplesConsumed - expectedSamples;
        if (ahead > BUFFER_SAMPLES) {
            long sleepNs = (ahead - BUFFER_SAMPLES) * 1_000_000_000L / sampleRate;
            if (sleepNs > 0) {
                try {
                    Thread.sleep(sleepNs / 1_000_000, (int) (sleepNs % 1_000_000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /**
     * Reset pacing state (call when emulator restarts).
     */
    public void reset() {
        started = false;
        totalSamplesConsumed = 0;
    }
}
