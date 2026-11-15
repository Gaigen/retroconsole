package com.retroconsole.network;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Bounded, ordered reassembly. One pending frame per console; stale frames are discarded. */
public final class FrameChunkAssembler {
    private static final long TIMEOUT_NS = 10_000_000_000L;
    private static final long MAX_PENDING_BYTES = 64L * 1024 * 1024;
    private static final int MAX_PENDING_CONSOLES = 32;
    private final Map<UUID, Pending> pending = new LinkedHashMap<>();
    private long pendingBytes;

    private static final class Pending {
        final long frameId; final int width, height; final byte[] bytes;
        int next; int written; long updated;
        Pending(RetroFrameChunk c, long now) {
            frameId = c.frameId(); width = c.width(); height = c.height();
            bytes = new byte[c.totalBytes()]; updated = now;
        }
    }

    public synchronized RetroFramePacket accept(RetroFrameChunk c) {
        long now = System.nanoTime();
        Iterator<Pending> it = pending.values().iterator();
        while (it.hasNext()) { Pending p = it.next(); if (now - p.updated > TIMEOUT_NS) { pendingBytes -= p.bytes.length; it.remove(); } }
        Pending p = pending.get(c.consoleId());
        if (c.index() == 0) {
            remove(c.consoleId());
            while (!pending.isEmpty() && (pending.size() >= MAX_PENDING_CONSOLES || pendingBytes + c.totalBytes() > MAX_PENDING_BYTES))
                remove(pending.keySet().iterator().next());
            if (pendingBytes + c.totalBytes() > MAX_PENDING_BYTES) return null;
            p = new Pending(c, now); pending.put(c.consoleId(), p); pendingBytes += p.bytes.length;
        }
        if (p == null) return null;
        if (p.frameId != c.frameId() || p.next != c.index() || p.width != c.width()
                || p.height != c.height() || p.bytes.length != c.totalBytes()) {
            remove(c.consoleId()); return null;
        }
        System.arraycopy(c.data(), 0, p.bytes, p.written, c.data().length);
        p.written += c.data().length; p.next++; p.updated = now;
        if (p.written != p.bytes.length) return null;
        remove(c.consoleId());
        return new RetroFramePacket(c.consoleId(), p.width, p.height, p.bytes);
    }

    public synchronized void remove(UUID id) {
        Pending p = pending.remove(id); if (p != null) pendingBytes -= p.bytes.length;
    }
}
