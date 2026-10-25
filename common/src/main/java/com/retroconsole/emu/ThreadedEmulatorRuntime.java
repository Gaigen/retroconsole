package com.retroconsole.emu;

/**
 * Wraps any FrameSource in its own daemon thread with a configurable frame loop.
 * Server tick just polls frames — no blocking.
 */
public class ThreadedEmulatorRuntime {
    private final FrameSource source;
    private volatile boolean running = false;
    private Thread thread;
    private volatile boolean newFrame = false;

    private volatile int currentWidth;
    private volatile int currentHeight;

    public ThreadedEmulatorRuntime(FrameSource source, int defaultWidth, int defaultHeight) {
        this(source, defaultWidth, defaultHeight, 60);
    }

    /** @deprecated initial targetFps is ignored — loop uses {@link FrameSource#getTimingFps()}. */
    public ThreadedEmulatorRuntime(FrameSource source, int defaultWidth, int defaultHeight, int targetFps) {
        this.source = source;
        this.currentWidth = defaultWidth;
        this.currentHeight = defaultHeight;
    }

    public synchronized void start() {
        stop();
        running = true;
        Thread t = new Thread(this::loop, "retro-emulator-thread");
        t.setDaemon(true);
        // If the loop throws something our try/catch does not catch
        // (e.g. an Error that escapes retro_run), log it instead of
        // dumping a raw stack to stderr and silently exiting.
        t.setUncaughtExceptionHandler((thread, ex) -> {
            running = false;
            System.err.println("[retro-emulator-thread] UNCAUGHT exception in loop:");
            ex.printStackTrace(System.err);
        });
        thread = t;
        thread.start();
    }

    private void loop() {
        long next = System.nanoTime();

        while (running) {
            double fps = Math.max(1.0, source.getTimingFps());
            long frameNs = (long) (1_000_000_000L / fps);
            next += frameNs;

            source.runFrame();
            newFrame = true;

            currentWidth = source.getWidth();
            currentHeight = source.getHeight();

            long sleepTime = next - System.nanoTime();
            if (sleepTime > 0) {
                try {
                    Thread.sleep(sleepTime / 1_000_000, (int) (sleepTime % 1_000_000));
                } catch (InterruptedException e) {
                    break;
                }
            } else if (sleepTime < -frameNs) {
                next = System.nanoTime();
            }
        }
    }

    /** True while a frame is waiting for {@link #pollFrame(int[])}. */
    public boolean hasNewFrame() {
        return newFrame;
    }

    /**
     * Non-blocking frame poll. Copies the latest frame into dst.
     * Returns true if a new frame was copied.
     */
    public boolean pollFrame(int[] dst) {
        if (!newFrame) return false;
        // Straight from the source: no intermediate buffer, so a hardware core
        // copies out of its own (locked) framebuffer once per frame, and skips
        // the GPU readback entirely while nobody is watching.
        if (!source.pollFrame(dst)) return false;
        newFrame = false;
        return true;
    }

    public int getCurrentWidth() { return currentWidth; }
    public int getCurrentHeight() { return currentHeight; }

    public synchronized void stop() {
        running = false;
        if (thread != null) {
            try {
                thread.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (thread.isAlive()) {
                thread.interrupt();
                try {
                    thread.join(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            thread = null;
        }
    }

    public boolean isRunning() { return running; }
}
