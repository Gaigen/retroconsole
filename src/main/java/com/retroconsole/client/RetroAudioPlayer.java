package com.retroconsole.client;

import org.lwjgl.openal.AL10;
import org.lwjgl.system.MemoryUtil;

/**
 * OpenAL streaming player for libretro PCM chunks.
 * Never silently drops packets — reclaims processed buffers and, if needed,
 * unqueues the oldest pending buffer to keep audio continuous.
 */
public class RetroAudioPlayer implements AutoCloseable {
    private static final int NUM_BUFFERS = 24;
    /** ~100–150 ms prebuffer before first play. */
    private static final int START_QUEUED = 3;

    private final int source;
    private final java.util.ArrayDeque<Integer> freeBuffers = new java.util.ArrayDeque<>();

    public RetroAudioPlayer(double x, double y, double z) {
        source = AL10.alGenSources();
        AL10.alSource3f(source, AL10.AL_POSITION, (float) x, (float) y, (float) z);
        AL10.alSourcef(source, AL10.AL_REFERENCE_DISTANCE, 4.0f);
        AL10.alSourcef(source, AL10.AL_MAX_DISTANCE, 256.0f);
        AL10.alSourcef(source, AL10.AL_ROLLOFF_FACTOR, 1.5f);
        for (int i = 0; i < NUM_BUFFERS; i++) freeBuffers.add(AL10.alGenBuffers());
    }

    /** Feed interleaved stereo PCM (16-bit signed LE). */
    public void feed(int sampleRate, byte[] pcmStereo16) {
        if (pcmStereo16.length < 4 || (pcmStereo16.length & 1) != 0) return;

        reclaimProcessedBuffers();
        if (freeBuffers.isEmpty()) {
            evictOldestQueuedBuffer();
        }
        if (freeBuffers.isEmpty()) return;

        int buf = freeBuffers.poll();
        java.nio.ByteBuffer bb = MemoryUtil.memAlloc(pcmStereo16.length);
        bb.put(pcmStereo16).flip();
        AL10.alBufferData(buf, AL10.AL_FORMAT_STEREO16, bb, sampleRate);
        MemoryUtil.memFree(bb);
        AL10.alSourceQueueBuffers(source, buf);

        int state = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE);
        int queued = AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED);
        if (state != AL10.AL_PLAYING && queued >= START_QUEUED) {
            AL10.alSourcePlay(source);
        } else if (state == AL10.AL_STOPPED && queued > 0) {
            AL10.alSourcePlay(source);
        }
    }

    private void reclaimProcessedBuffers() {
        int processed = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED);
        while (processed-- > 0) {
            freeBuffers.add(AL10.alSourceUnqueueBuffers(source));
        }
    }

    /** Make room when the producer briefly outruns playback. */
    private void evictOldestQueuedBuffer() {
        int queued = AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED);
        if (queued <= 1) return;
        int old = AL10.alSourceUnqueueBuffers(source);
        AL10.alDeleteBuffers(old);
        freeBuffers.add(AL10.alGenBuffers());
    }

    @Override
    public void close() {
        AL10.alSourceStop(source);
        reclaimProcessedBuffers();
        int queued = AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED);
        while (queued-- > 0) {
            AL10.alDeleteBuffers(AL10.alSourceUnqueueBuffers(source));
        }
        while (!freeBuffers.isEmpty()) AL10.alDeleteBuffers(freeBuffers.poll());
        AL10.alDeleteSources(source);
    }
}
