package com.retroconsole.client;

/** Geometry only: keeps the five TV controls disjoint at the vanilla minimum GUI width. */
public final class TvToolbarLayout {
    private TvToolbarLayout() {}

    public static int[] widths(int guiWidth) {
        // Two 4 px margins and four 4 px gaps. Preserve the upstream sizes on wide screens.
        int[] widths = {48, 88, 56, 56, 110};
        double scale = Math.min(1.0, Math.max(0, guiWidth - 24) / 358.0);
        for (int i = 0; i < widths.length; i++) {
            widths[i] = Math.max(1, (int) Math.floor(widths[i] * scale));
        }
        return widths;
    }
}
