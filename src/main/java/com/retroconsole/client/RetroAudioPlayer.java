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
 * Отличия от старой версии:
 *  - УБРАН eviction буфера из середины очереди. Он был сломан: на играющем
 *    источнике alSourceUnqueueBuffers снимает только PROCESSED-буферы; если
 *    их нет, вызов падает с AL_INVALID_VALUE и возвращает мусор, который
 *    старый код передавал в alDeleteBuffers (утечка + грязное состояние AL).
 *    Даже "рабочий" eviction — это слышимый щелчок. Теперь при полной
 *    очереди дропается ВХОДЯЩИЙ чанк (+ счётчик).
 *  - После underrun (AL_STOPPED) источник заново набирает пребуфер до
 *    START_QUEUED, а не рестартует с одним крошечным буфером (это и был
 *    цикл "stop -> restart -> stop" = похрипывание).
 *  - Правильная проверка выравнивания: кадр stereo16 = 4 байта, поэтому
 *    (length & 3), а не (length & 1). Чанк, порезанный не по границе кадра,
 *    сдвигает байты/каналы и даёт треск.
 *  - Проверка alGetError() и диагностика раз в 5 секунд.
 */
public class RetroAudioPlayer implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger("RetroAudioPlayer");

    private static final int NUM_BUFFERS = 24;
    /** Пребуфер перед стартом/рестартом: ~100 мс при чанке в 1 кадр (60 fps). */
    private static final int START_QUEUED = 6;

    private final int source;
    private final ArrayDeque<Integer> freeBuffers = new ArrayDeque<>();

    /** true, пока заново копим пребуфер после старта или underrun. */
    private boolean starving = true;

    // --- диагностика ---
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
        for (int i = 0; i < NUM_BUFFERS; i++) freeBuffers.add(AL10.alGenBuffers());
        AL10.alGetError(); // сбросить накопившиеся ошибки
        lastStatsNs = System.nanoTime();
    }

    /** Скормить interleaved stereo PCM (16-bit signed LE). */
    public void feed(int sampleRate, byte[] pcmStereo16) {
        if (pcmStereo16 == null || pcmStereo16.length < 4 || (pcmStereo16.length & 3) != 0) {
            return;
        }

        int state = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE);
        if (state == AL10.AL_STOPPED) {
            // Настоящий underrun: доиграли до тишины.
            underruns++;
            starving = true;
            // ВАЖНО: на STOPPED-источнике ВСЕ queued-буферы (даже свежие,
            // ещё не игравшие) считаются processed — если просто продолжать
            // queue + reclaim, очередь никогда не наберётся и звук не
            // вернётся. Снимаем всё и переводим источник в AL_INITIAL:
            // там новые буферы копятся как pending.
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

    /**
     * Раз в 5 секунд: фактическая входная частота vs заявленная, глубина
     * очереди, дропы и underrun'ы.
     *  - rate стабильно НИЖЕ sampleRate + растут underruns -> ядро/сервер
     *    не успевает за реальным временем;
     *  - rate стабильно ВЫШЕ + растут dropped -> цикл сервера гонит быстрее
     *    реального времени (дрейф пейсинга FrameSender).
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
        // После alSourceStop ВСЕ queued-буферы становятся processed,
        // и их можно легально снять и удалить.
        int queued = AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED);
        while (queued-- > 0) {
            AL10.alDeleteBuffers(AL10.alSourceUnqueueBuffers(source));
        }
        while (!freeBuffers.isEmpty()) AL10.alDeleteBuffers(freeBuffers.poll());
        AL10.alDeleteSources(source);
        AL10.alGetError();
    }
}