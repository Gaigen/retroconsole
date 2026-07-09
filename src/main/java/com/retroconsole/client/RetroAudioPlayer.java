package com.retroconsole.client;

import org.lwjgl.openal.AL10;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;

/**
 * OpenAL streaming player for libretro PCM chunks.
 *
 * Changes from the earlier version:
 *  - User gain (setGain): AL_GAIN on the source, multiplied with distance attenuation.
 *  - Removed mid-queue buffer eviction (broken: on a playing source alSourceUnqueueBuffers
 *    only removes PROCESSED buffers; with none queued the call fails with AL_INVALID_VALUE).
 *    When the queue is full, incoming chunks are dropped instead (+ counter).
 *  - After underrun (AL_STOPPED) the source refills the prebuffer to START_QUEUED
 *    instead of restarting with a single tiny buffer.
 *  - Alignment check: stereo16 frame = 4 bytes → (length & 3).
 *  - alGetError() checks and diagnostics every 5 seconds.
 */
public class RetroAudioPlayer implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger("RetroAudioPlayer");

    private static final int NUM_BUFFERS = 24;
    /* Prebuffer before start/restart: ~100 ms at one frame per chunk (60 fps). */
    private static final int START_QUEUED = 6;

    private final int source;
    private final ArrayDeque<Integer> freeBuffers = new ArrayDeque<>();

    /* true while refilling prebuffer after start or underrun. */
    private boolean starving = true;

    /* User volume 0..1. volatile: setGain is called from AUDIO_EXEC. */
    private volatile float gain = 1.0f;

    private long droppedChunks;
    private long underruns;
    private long samplesFed;
    private long lastStatsNs;

    public RetroAudioPlayer(double x, double y, double z) {
        source = AL10.alGenSources();
        AL10.alSource3f(source, AL10.AL_POSITION, (float) x, (float) y, (float) z);
        AL10.alSourcef(source, AL10.AL_REFERENCE_DISTANCE, 4.0f);
        AL10.alSourcef(source, AL10.AL_MAX_DISTANCE, 256.0f);
        AL10.alSourcef(source, AL10.AL_ROLLOFF_FACTOR, 1.5f);
        AL10.alSourcef(source, AL10.AL_GAIN, gain);
        for (int i = 0; i < NUM_BUFFERS; i++) freeBuffers.add(AL10.alGenBuffers());
        AL10.alGetError(); // clear accumulated errors
        lastStatsNs = System.nanoTime();
    }

    /** User volume 0..1. Call from the audio thread (AUDIO_EXEC). */
    public void setGain(float g) {
        float clamped = Math.max(0f, Math.min(1f, g));
        gain = clamped;
        AL10.alSourcef(source, AL10.AL_GAIN, clamped);
        checkAlError("setGain");
    }

    public float getGain() {
        return gain;
    }

    /* Feed interleaved stereo PCM (16-bit signed LE). */
    public void feed(int sampleRate, byte[] pcmStereo16) {
        if (pcmStereo16 == null || pcmStereo16.length < 4 || (pcmStereo16.length & 3) != 0) {
            return;
        }

        int state = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE);
        if (state == AL10.AL_STOPPED) {
            // Real underrun: playback reached silence.
            underruns++;
            starving = true;
            // On a STOPPED source ALL queued buffers (even fresh, unplayed ones) count as
            // processed — if we keep queue + reclaim, the queue never refills and audio
            // never returns. Drain everything and move the source to AL_INITIAL where new
            // buffers accumulate as pending.
            reclaimProcessedBuffers();
            AL10.alSourceRewind(source);
            state = AL10.AL_INITIAL;
        } else {
            reclaimProcessedBuffers();
        }

        if (freeBuffers.isEmpty()) {
            droppedChunks++;
            maybeLogStats(sampleRate);
            return;
        }

        int buf = freeBuffers.poll();
        ByteBuffer bb = MemoryUtil.memAlloc(pcmStereo16.length);
        bb.put(pcmStereo16).flip();
        AL10.alBufferData(buf, AL10.AL_FORMAT_STEREO16, bb, sampleRate);
        MemoryUtil.memFree(bb);
        AL10.alSourceQueueBuffers(source, buf);
        samplesFed += pcmStereo16.length / 4L;

        int queued = AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED);
        if (state != AL10.AL_PLAYING && (!starving || queued >= START_QUEUED)) {
            AL10.alSourcePlay(source);
            starving = false;
        }

        checkAlError("feed");
        maybeLogStats(sampleRate);
    }

    private void reclaimProcessedBuffers() {
        int processed = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED);
        while (processed-- > 0) {
            freeBuffers.add(AL10.alSourceUnqueueBuffers(source));
        }
    }

    private void checkAlError(String where) {
        int err = AL10.alGetError();
        if (err != AL10.AL_NO_ERROR) {
            LOGGER.warn("OpenAL error 0x{} in {}", Integer.toHexString(err), where);
        }
    }

    /*
     * Every 5 seconds: actual input rate vs nominal, queue depth, drops and underruns.
     *  - rate consistently BELOW sampleRate + rising underruns → core/server can't keep real time;
     *  - rate consistently ABOVE + rising dropped → server loop runs faster than real time
     *    (FrameSender pacing drift).
     */
    private void maybeLogStats(int sampleRate) {
        long now = System.nanoTime();
        if (now - lastStatsNs < 5_000_000_000L) return;
        double sec = (now - lastStatsNs) / 1e9;
        int queued = AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED);
        LOGGER.info("audio in={} Hz (nominal {}), queued={}, dropped={}, underruns={}",
                Math.round(samplesFed / sec), sampleRate, queued, droppedChunks, underruns);
        samplesFed = 0;
        lastStatsNs = now;
    }

    @Override
    public void close() {
        AL10.alSourceStop(source);
        // After alSourceStop ALL queued buffers become processed and can be unqueued/deleted.
        int queued = AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED);
        while (queued-- > 0) {
            AL10.alDeleteBuffers(AL10.alSourceUnqueueBuffers(source));
        }
        while (!freeBuffers.isEmpty()) AL10.alDeleteBuffers(freeBuffers.poll());
        AL10.alDeleteSources(source);
        AL10.alGetError();
    }
}