package com.retroconsole.client;

import org.lwjgl.openal.AL10;
import org.lwjgl.system.MemoryUtil;

public class RetroAudioPlayer implements AutoCloseable {
    private static final int NUM_BUFFERS = 10;
    /** Не стартуем, пока не накопили 3 чанка (~150-200 мс джиттер-буфер). */
    private static final int START_QUEUED = 3;

    private final int source;
    private final java.util.ArrayDeque<Integer> freeBuffers = new java.util.ArrayDeque<>();

    public RetroAudioPlayer(double x, double y, double z) {
        source = AL10.alGenSources();
        AL10.alSource3f(source, AL10.AL_POSITION, (float) x, (float) y, (float) z);
        AL10.alSourcef(source, AL10.AL_REFERENCE_DISTANCE, 4.0f);
        AL10.alSourcef(source, AL10.AL_MAX_DISTANCE, 32.0f);
        AL10.alSourcef(source, AL10.AL_ROLLOFF_FACTOR, 1.5f);
        for (int i = 0; i < NUM_BUFFERS; i++) freeBuffers.add(AL10.alGenBuffers());
    }

    /** Скормить PCM-чанк (mono 16-bit signed LE). */
    public void feed(int sampleRate, byte[] pcmMono16) {
        if (pcmMono16.length == 0) return;
        int processed = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED);
        while (processed-- > 0) freeBuffers.add(AL10.alSourceUnqueueBuffers(source));
        if (freeBuffers.isEmpty()) return;
        int buf = freeBuffers.poll();
        java.nio.ByteBuffer bb = MemoryUtil.memAlloc(pcmMono16.length);
        bb.put(pcmMono16).flip();
        AL10.alBufferData(buf, AL10.AL_FORMAT_MONO16, bb, sampleRate);
        MemoryUtil.memFree(bb);
        AL10.alSourceQueueBuffers(source, buf);
        int state = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE);
        int queued = AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED);
        if (state != AL10.AL_PLAYING && queued >= START_QUEUED) {
            AL10.alSourcePlay(source);
        }
    }

    @Override
    public void close() {
        AL10.alSourceStop(source);
        int processed = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED);
        while (processed-- > 0) AL10.alDeleteBuffers(AL10.alSourceUnqueueBuffers(source));
        while (!freeBuffers.isEmpty()) AL10.alDeleteBuffers(freeBuffers.poll());
        AL10.alDeleteSources(source);
    }
}
