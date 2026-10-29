package com.retroconsole.client;

import com.retroconsole.style.ScreenDesign;
import com.retroconsole.style.ScreenMount;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shell geometry of a screen block, ported from the display lab rule set.
 *
 * <p>One screen is a wall of blocks; the shell belongs to the outer perimeter
 * only and the video aperture spans the whole wall, so a tile has to know where
 * it sits in the grid. Every piece is built in wall coordinates (0..16 per
 * block, origin bottom-left of the wall, +Z into the body) and clipped to the
 * requesting tile, which is exactly how the lab exports one model per tile.
 */
public final class ScreenShell {

    /** Material slots. {@link #BODY} is the one a tone recolours. */
    public enum Mat {
        BODY(ChassisPalette.CREAM),
        DARK(ChassisPalette.DARK),
        RUBBER(ChassisPalette.RUBBER),
        METAL(ChassisPalette.METAL),
        TEAL(ChassisPalette.TEAL),
        MINT(ChassisPalette.MINT),
        RED(ChassisPalette.RED),
        GOLD(ChassisPalette.GOLD);

        private final int rgb;

        Mat(int rgb) {
            this.rgb = rgb;
        }

        public int rgb() {
            return rgb;
        }
    }

    /**
     * Axis-aligned box in tile-local model units; z runs into the block.
     *
     * @param open true to leave the front face out, for a box the picture covers
     *             completely: it then sits flush with the picture instead of a
     *             hair in front of it, and the two never contest the same pixel
     */
    public record Box(float x0, float y0, float z0, float x1, float y1, float z1, Mat mat,
                      boolean open) {}

    /** Aperture and video plane of a whole wall, in wall units. */
    public record Aperture(float left, float bottom, float right, float top, float videoZ) {}

    private static final Map<Long, List<Box>> CACHE = new HashMap<>();

    private ScreenShell() {}

    /** Boxes of one tile of a wall. Cached: the geometry only depends on the grid slot. */
    public static List<Box> boxes(ScreenDesign design, ScreenMount mount,
                                  int gridW, int gridH, int col, int row) {
        long key = ((long) design.ordinal() << 48) | ((long) mount.ordinal() << 40)
                | ((long) (gridW & 0xFF) << 32) | ((long) (gridH & 0xFF) << 24)
                | ((long) (col & 0xFF) << 8) | (row & 0xFF);
        List<Box> cached = CACHE.get(key);
        if (cached != null) return cached;
        List<Box> out = new ArrayList<>();
        build(design, mount, gridW, gridH, col, row, out);
        List<Box> result = List.copyOf(out);
        CACHE.put(key, result);
        return result;
    }

    /** Picture rectangle of a whole wall, in wall units. */
    public static Aperture aperture(ScreenDesign design, int gridW, int gridH) {
        float w = gridW * 16.0f;
        float h = gridH * 16.0f;
        float left = design.bezel();
        float right = w - design.bezel();
        return new Aperture(left, design.bottom(), right, h - design.bezel(), design.videoZ());
    }

    private static void build(ScreenDesign design, ScreenMount mount, int gridW, int gridH,
                              int col, int row, List<Box> out) {
        float w = gridW * 16.0f;
        float h = gridH * 16.0f;
        Tile tile = new Tile(out, col, row, mount.shift(design));

        if (design.bezel() <= 0.0f) {
            // Frameless front: the whole face is video and only the body behind it is
            // solid. The body starts on the very plane the picture is drawn on and its
            // front face is left out, so the two share that plane without a gap and
            // without contesting the same pixels. Edge keeps a thin panel, solid fills
            // the whole block.
            tile.addOpen(0, 0, 0.0625f, w, h, design.depth(), Mat.BODY);
            return;
        }

        float depth = design.depth();
        float bezel = design.bezel();
        float bottom = design.bottom();
        // Width of the outer rim. A wall panel is only half a unit from its edge to
        // the picture, so its rim has to be that thin or it would reach across the video.
        float rim = design == ScreenDesign.WALL ? 0.5f : 0.75f;
        // Retro frames its picture in the body colour; the slimmer designs keep a dark
        // inner surround, which the lab paints on the aperture strips.
        Mat surround = design == ScreenDesign.RETRO ? Mat.BODY : Mat.RUBBER;

        // Flat back plate: the floor of the cavity, one unit behind the picture.
        tile.add(0, 0, 1.0f, w, h, depth, Mat.BODY);

        // Front slab, one layer deep, z .25..1. Every band owns a footprint that is
        // disjoint from its neighbours'. That is the point: the lab's bands overlap,
        // which puts two faces of *different* colour on the very same plane (a cream
        // rim under a rubber strip) and those two would take turns in the depth
        // buffer, which is exactly what reads as torn stripes at sharp angles.
        tile.frame(0, 0, w, h, rim, rim, w - rim, h - rim, 0.25f, 1.0f, Mat.BODY);
        tile.add(rim, rim, 0.25f, w - rim, bottom, 1.0f, Mat.BODY);
        if (bezel > rim + 0.001f) {
            tile.add(rim, bottom, 0.25f, bezel, h - rim, 1.0f, surround);
            tile.add(w - bezel, bottom, 0.25f, w - rim, h - rim, 1.0f, surround);
            tile.add(bezel, h - bezel, 0.25f, w - bezel, h - rim, 1.0f, surround);
        }

        // Recess lip: the raised rim around the opening. The lab runs it into the slab
        // (.0625..4375), so its inner wall and the slab's inner wall lie on the same
        // plane over a third of a unit — the wall you look along whenever you see the
        // screen from the side, drawn twice in two different colours. Here the lip
        // stops on the slab's front plane: the two walls meet edge to edge instead.
        tile.ring(bezel - 0.1875f, bottom - 0.1875f, w - bezel + 0.1875f, h - bezel + 0.1875f,
                0.0625f, 0.25f, 0.1875f, Mat.RUBBER);

        // Controls grow forwards from the front plane by their own relief, the way the
        // lab stacks them, so none of them floats and none of them cuts the slab.
        switch (design) {
            case RETRO -> {
                for (int i = 0; i < 6; i++) {
                    tile.add(2 + i * 0.5f, 0.875f, 0.1875f,
                            2.1875f + i * 0.5f, 1.5625f, 0.25f, Mat.RUBBER);
                }
                tile.add(w - 3.25f, 0.625f, 0.0625f, w - 1.625f, 1.875f, 0.25f, Mat.RUBBER);
                // The key stops inside the mount, so its back plane is buried rather
                // than laid on the slab together with the mount's.
                tile.add(w - 3.0f, 0.875f, 0.0f, w - 2.0f, 1.625f, 0.1875f, Mat.RED);
                tile.add(w - 4.0f, 0.9375f, 0.1875f, w - 3.625f, 1.3125f, 0.25f, Mat.MINT);
            }
            case STUDIO -> {
                float[] stops = {1.0f, 1.25f, 3.0f, w - 2.0f, w - 1.5f, w - 1.0f};
                for (int i = 0; i + 1 < stops.length; i++) {
                    float x = stops[i];
                    float x2 = stops[i + 1];
                    Mat mat = (x >= 1.25f && x2 <= 3.0f) ? Mat.TEAL
                            : (x >= w - 2.0f && x2 <= w - 1.5f) ? Mat.MINT
                            : Mat.METAL;
                    tile.add(x, 0.375f, 0.0625f, x2, 0.5625f, 0.25f, mat);
                }
            }
            case WALL -> tile.add(w - 1.5f, 0.1875f, 0.1875f, w - 1.0f, 0.375f, 0.25f, Mat.TEAL);
            default -> { }
        }
    }

    /** Adds boxes to one tile, clipping them to the tile's 16x16 face. */
    private record Tile(List<Box> out, int col, int row, float shift) {

        void add(float x0, float y0, float z0, float x1, float y1, float z1, Mat mat) {
            add(x0, y0, z0, x1, y1, z1, mat, false);
        }

        void addOpen(float x0, float y0, float z0, float x1, float y1, float z1, Mat mat) {
            add(x0, y0, z0, x1, y1, z1, mat, true);
        }

        private void add(float x0, float y0, float z0, float x1, float y1, float z1, Mat mat,
                         boolean open) {
            float ox = col * 16.0f;
            float oy = row * 16.0f;
            float ax = Math.max(x0, ox);
            float bx = Math.min(x1, ox + 16.0f);
            float ay = Math.max(y0, oy);
            float by = Math.min(y1, oy + 16.0f);
            if (bx - ax <= 0.001f || by - ay <= 0.001f) return;
            out.add(new Box(ax - ox, ay - oy, z0 + shift, bx - ox, by - oy, z1 + shift, mat, open));
        }

        void ring(float x, float y, float x2, float y2, float z, float z2, float w, Mat mat) {
            add(x, y, z, x2, y + w, z2, mat);
            add(x, y2 - w, z, x2, y2, z2, mat);
            add(x, y + w, z, x + w, y2 - w, z2, mat);
            add(x2 - w, y + w, z, x2, y2 - w, z2, mat);
        }

        /** Four bands tiling the gap between two rectangles without overlapping. */
        void frame(float x, float y, float x2, float y2,
                   float ix, float iy, float ix2, float iy2,
                   float z, float z2, Mat mat) {
            add(x, y, z, x2, iy, z2, mat);
            add(x, iy2, z, x2, y2, z2, mat);
            add(x, iy, z, ix, iy2, z2, mat);
            add(ix2, iy, z, x2, iy2, z2, mat);
        }
    }
}
