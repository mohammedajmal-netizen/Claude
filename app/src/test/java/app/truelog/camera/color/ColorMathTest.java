package app.truelog.camera.color;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ColorMathTest {

    @Test public void slog3MatchesSonyPublishedCodes() {
        assertEquals(95.0, ColorMath.slog3Encode(0.0) * 1023.0, 0.01);
        assertEquals(420.0, ColorMath.slog3Encode(0.18) * 1023.0, 0.01);
        assertEquals(598.0, ColorMath.slog3Encode(0.90) * 1023.0, 0.5);
    }

    @Test public void slog3RoundTrips() {
        for (double x : new double[] {0, 0.001, 0.01125, 0.05, 0.18, 1, 8}) {
            assertEquals(x, ColorMath.slog3Decode(ColorMath.slog3Encode(x)), 1e-9);
        }
    }

    @Test public void midGreyIs41Ire() {
        assertEquals(41.0, ColorMath.codeToNarrowSignal(420.0 / 1023.0) * 100.0, 0.5);
    }

    @Test public void sGamut3CineMatchesPublishedMatrix() {
        double[] published = {0.5990839, 0.2489255, 0.1024464, 0.2150758, 0.8850685, -0.1001443,
            -0.0320658, -0.0276584, 1.1487819};
        assertArrayEquals(published, ColorMath.sGamut3CineToXyzD65(), 5e-4);
    }

    @Test public void neutralGreyEncodesToSonyCodeWithNoChroma() {
        double s = ColorMath.codeToNarrowSignal(ColorMath.slog3Encode(0.18));
        int[] codes = ColorMath.encodeP010Codes(s, s, s);
        assertEquals(420, codes[0]);
        assertEquals(512, codes[1]);
        assertEquals(512, codes[2]);
    }

    private static ColorMath.SensorColor camera(boolean withForward) {
        ColorMath.SensorColor s = new ColorMath.SensorColor();
        s.cct1 = 2856;
        s.cct2 = 6504;
        s.xyzToCam2 = new double[] {0.9, -0.3, -0.05, -0.35, 1.2, 0.15, -0.05, 0.2, 0.55};
        s.xyzToCam1 = new double[] {1.1, -0.4, -0.02, -0.4, 1.3, 0.08, -0.02, 0.12, 0.75};
        if (withForward) {
            // Each row sums to the D50 white point, as a real forward matrix must.
            s.forward1 = new double[] {0.62, 0.21, 0.1342, 0.28, 0.82, -0.10, 0.03, -0.14, 0.9349};
            s.forward2 = new double[] {0.58, 0.26, 0.1242, 0.25, 0.86, -0.11, 0.02, -0.11, 0.9149};
        }
        return s;
    }

    @Test public void cameraNeutralComesOutGreyWithAndWithoutForwardMatrix() {
        for (boolean forward : new boolean[] {true, false}) {
            ColorMath.SensorColor s = camera(forward);
            for (int kelvin : new int[] {2900, 4000, 5600, 6500}) {
                double[] n = ColorMath.xyToNeutral(s, ColorMath.cctToXy(kelvin));
                ColorMath.WhiteBalance wb = ColorMath.solve(s, n);
                double[] balanced = {n[0] * wb.gains[0], n[1] * wb.gains[1], n[2] * wb.gains[2]};
                assertEquals(balanced[0], balanced[1], 1e-9);
                assertEquals(balanced[1], balanced[2], 1e-9);
                double[] grey = ColorMath.mulVec(wb.camToSGamut, new double[] {1, 1, 1});
                assertEquals(grey[0], grey[1], 2e-3);
                assertEquals(grey[1], grey[2], 2e-3);
            }
        }
    }

    @Test public void smallestGainIsOne() {
        ColorMath.SensorColor s = camera(true);
        ColorMath.WhiteBalance wb = ColorMath.solve(s, ColorMath.xyToNeutral(s, ColorMath.D65_XY));
        assertEquals(1.0, Math.min(wb.gains[0], Math.min(wb.gains[1], wb.gains[2])), 1e-9);
    }
}
