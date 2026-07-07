package com.retroconsole.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.retroconsole.block.ScreenBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Клиентские экраны консолей.
 *
 * Архитектура "последний кадр побеждает":
 *  - submitFrame()  — вызывается с СЕТЕВОГО потока. Кадр конвертируется в ABGR
 *    и кладётся в PENDING-слот. Если рендер не успел забрать предыдущий кадр,
 *    тот молча затирается — очередь задач не растёт никогда.
 *  - uploadPendingFrame() — только рендер-поток (вызывается изнутри getScreen()).
 *    Забирает кадр из слота и заливает его в существующую текстуру одним
 *    bulk-копированием + glTexSubImage2D. Без аллокаций и попиксельных циклов.
 *
 * Потребители (TvScreen, ScreenBlockEntityRenderer) продолжают просто вызывать
 * getScreen(pos) — заливка происходит внутри него, их менять не нужно.
 */
public final class ClientConsoles {

    private ClientConsoles() {}

    /** Кадр, ожидающий заливки. Пиксели уже в формате ABGR (GL RGBA little-endian). */
    private record PendingFrame(int[] abgr, int width, int height) {}

    /** Экран консоли: текстура + персистентный staging-буфер. Живёт до dispose() или смены разрешения. */
    public static final class ScreenEntry {
        private final DynamicTexture tex;
        private final ResourceLocation id;
        private final int width;
        private final int height;
        private final IntBuffer staging; // direct-память, переиспользуется каждый кадр

        private ScreenEntry(BlockPos pos, int width, int height) {
            this.width = width;
            this.height = height;
            this.tex = new DynamicTexture(width, height, true);
            this.id = Minecraft.getInstance().getTextureManager()
                    .register("retro_screen_" + pos.asLong(), tex);
            this.staging = MemoryUtil.memAllocInt(width * height);
        }

        private void close() {
            Minecraft.getInstance().getTextureManager().release(id);
            MemoryUtil.memFree(staging);
        }

        public DynamicTexture tex() { return tex; }
        public ResourceLocation id() { return id; }
        public int width() { return width; }
        public int height() { return height; }
    }

    /** PENDING пишется с сетевого потока, читается с рендера — обязательно concurrent. */
    private static final Map<BlockPos, PendingFrame> PENDING = new ConcurrentHashMap<>();
    /** SCREENS трогается только с рендер-потока; concurrent — дешёвая страховка. */
    private static final Map<BlockPos, ScreenEntry> SCREENS = new ConcurrentHashMap<>();

    private static final Map<BlockPos, int[]> GRID_CACHE = new HashMap<>();
    private static int frameCounter = 0;

    // ------------------------------------------------------------------
    // Сетевой поток
    // ------------------------------------------------------------------

    /**
     * Принять кадр с сетевого потока. ARGB -> ABGR конвертируется здесь же,
     * in-place (массив пришёл из decompressFrame и больше никому не нужен).
     * Старый непоказанный кадр молча затирается.
     */
    public static void submitFrame(BlockPos pos, int[] argb, int width, int height) {
        if (argb == null || width <= 0 || height <= 0) return;
        int n = width * height;
        if (argb.length < n) return;

        for (int i = 0; i < n; i++) {
            int p = argb[i];
            // A и G остаются на месте, R <-> B меняются местами
            argb[i] = (p & 0xFF00FF00) | ((p & 0xFF) << 16) | ((p >> 16) & 0xFF);
        }
        PENDING.put(pos.immutable(), new PendingFrame(argb, width, height));
    }

    /** Совместимость со старым путём (ClientPacketHandler). Удали вместе с ним. */
    @Deprecated
    public static void updateFrame(BlockPos pos, int[] frame, int width, int height) {
        submitFrame(pos, frame, width, height);
    }

    // ------------------------------------------------------------------
    // Рендер-поток
    // ------------------------------------------------------------------

    /**
     * Получить экран консоли. Перед возвратом заливает свежий кадр, если он есть.
     * Повторные вызовы в том же кадре рендера бесплатны (PENDING уже пуст).
     */
    public static ScreenEntry getScreen(BlockPos consolePos) {
        if (consolePos == null) return null;
        BlockPos pos = consolePos.immutable();
        uploadPendingFrame(pos);
        return SCREENS.get(pos);
    }

    /** Только рендер-поток. Забирает последний кадр из слота и заливает в текстуру. */
    private static void uploadPendingFrame(BlockPos pos) {
        if (!RenderSystem.isOnRenderThread()) return;

        PendingFrame f = PENDING.remove(pos);
        if (f == null) return;

        ScreenEntry entry = SCREENS.get(pos);
        if (entry != null && (entry.width != f.width() || entry.height != f.height())) {
            entry.close();
            SCREENS.remove(pos);
            entry = null;
        }
        if (entry == null) {
            entry = new ScreenEntry(pos, f.width(), f.height());
            SCREENS.put(pos, entry);
        }

        int n = f.width() * f.height();
        entry.staging.clear();
        entry.staging.put(f.abgr(), 0, n);
        entry.staging.flip();

        GlStateManager._bindTexture(entry.tex.getId());
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);
        GlStateManager._texSubImage2D(
                GL11.GL_TEXTURE_2D, 0, 0, 0,
                f.width(), f.height(),
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE,
                MemoryUtil.memAddress(entry.staging));
    }

    /** Вызывать с рендер-потока (через enqueueWork из обработчика stop-пакета). */
    public static void dispose(BlockPos pos) {
        pos = pos.immutable();
        PENDING.remove(pos);
        ScreenEntry entry = SCREENS.remove(pos);
        if (entry != null) {
            entry.close();
        }
        GRID_CACHE.clear();
    }

    // ------------------------------------------------------------------
    // Multi-block TV grid (без изменений)
    // ------------------------------------------------------------------

    public static int[] getGridInfo(ScreenBlockEntity be) {
        BlockPos pos = be.getBlockPos().immutable();
        if (++frameCounter % 60 == 0) GRID_CACHE.clear();
        int[] cached = GRID_CACHE.get(pos);
        if (cached != null) return cached;

        Set<BlockPos> group = findScreenGroup(be);
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : group) {
            minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
            minY = Math.min(minY, p.getY()); maxY = Math.max(maxY, p.getY());
            minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
        }
        int xSpan = maxX - minX + 1, zSpan = maxZ - minZ + 1;
        int gridW = Math.max(xSpan, zSpan), gridH = maxY - minY + 1;
        int gridX = (xSpan >= zSpan) ? pos.getX() - minX : pos.getZ() - minZ;
        int gridYFromTop = maxY - pos.getY();
        int[] result = {gridX, gridYFromTop, gridW, gridH};
        GRID_CACHE.put(pos, result);
        return result;
    }

    private static Set<BlockPos> findScreenGroup(ScreenBlockEntity be) {
        Set<BlockPos> visited = new HashSet<>();
        Queue<BlockPos> queue = new LinkedList<>();
        BlockPos start = be.getBlockPos().immutable();
        BlockPos consolePos = be.getConsolePos();
        queue.add(start);
        visited.add(start);
        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = -1; dy <= 1; dy++)
                    for (int dz = -1; dz <= 1; dz++) {
                        if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) != 1) continue;
                        BlockPos neighbor = current.offset(dx, dy, dz);
                        if (visited.contains(neighbor) || be.getLevel() == null) continue;
                        var blockEntity = be.getLevel().getBlockEntity(neighbor);
                        if (blockEntity instanceof ScreenBlockEntity sbe
                                && consolePos != null && consolePos.equals(sbe.getConsolePos())) {
                            visited.add(neighbor);
                            queue.add(neighbor);
                        }
                    }
        }
        return visited;
    }
}