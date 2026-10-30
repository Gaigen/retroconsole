package com.retroconsole.client;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class TvToolbarLayoutTest {
    @Test void controlsFitEveryVanillaGuiWidth() {
        for (int width = 320; width <= 3840; width++) {
            int[] sizes = TvToolbarLayout.widths(width);
            assertEquals(5, sizes.length);
            assertTrue(Arrays.stream(sizes).allMatch(w -> w >= 32), "Unusable control at " + width);
            assertTrue(Arrays.stream(sizes).sum() + 24 <= width, "Overlapping controls at " + width);
        }
    }

    @Test void wideLayoutKeepsOriginalSizesAndResultsAreIndependent() {
        int[] expected = {48, 88, 56, 56, 110};
        assertArrayEquals(expected, TvToolbarLayout.widths(427));
        assertArrayEquals(expected, TvToolbarLayout.widths(1920));
        TvToolbarLayout.widths(427)[0] = 0;
        assertArrayEquals(expected, TvToolbarLayout.widths(427));
    }
}
