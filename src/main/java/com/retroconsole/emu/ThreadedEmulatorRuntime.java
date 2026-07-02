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
    private final int targetFps;

    public ThreadedEmulatorRuntime(FrameSource source, int defaultWidth, int defaultHeight) {
        this(source, defaultWidth, defaultHeight, 60);
    }

    public ThreadedEmulatorRuntime(FrameSource source, int defaultWidth, int defaultHeight, int targetFps) {
        this.source = source;
        this.currentWidth = defaultWidth;
        this.currentHeight = defaultHeight;
        this.targetFps = targetFps;
    }

    public synchronized void start() {
        stop();
        running = true;
        thread = new Thread(this::loop, "retro-emulator-thread");
        thread.setDaemon(true);
        thread.start();
    }

    private void loop() {
        long frameNs = 1_000_000_000L / targetFps;
        long next = System.nanoTime();

        while (running) {
            source.runFrame();
            newFrame = true;

            // Update dimensions in case they changed
            currentWidth = source.getWidth();
            currentHeight = source.getHeight();

            next += frameNs;
            long sleepTime = next - System.nanoTime();
            if (sleepTime > 0) {
                try {
                    Thread.sleep(sleepTime / 1_000_000, (int) (sleepTime % 1_000_000));
                } catch (InterruptedException e) {
                    break;
                }
            } else {
                // Fell behind — reset timing
                next = System.nanoTime();
            }
        }
    }

    /**
     * Non-blocking frame poll. Copies latest frame into dst.
     * Returns true if a new frame was copied.
     */
    public boolean pollFrame(int[] dst) {
        if (!newFrame) return false;

        int[] src = source.getFrameBuffer();
        if (src == null) return false;

        int copyLen = Math.min(src.length, dst.length);
        System.arraycopy(src, 0, dst, 0, copyLen);

        newFrame = false;
        return true;
    }

    public int getCurrentWidth() { return currentWidth; }
    public int getCurrentHeight() { return currentHeight; }

    public synchronized void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
    }

    public boolean isRunning() { return running; }
}
