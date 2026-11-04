package app.truelog.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

import app.truelog.camera.color.ColorMath;
import app.truelog.camera.gl.EglCore;
import app.truelog.camera.gl.RawLogProcessor;

/** Runs the real shaders on synthetic Bayer frames and checks the P010 output against ColorMath. */
@RunWith(AndroidJUnit4.class)
public class RawLogProcessorTest {
    private static final int W = 64, H = 36;
    private EglCore egl;

    /** The emulator may only offer ES 3.0. tools/shader_test.py covers the shaders in that case. */
    @Before public void setUp() {
        try {
            egl = new EglCore(false);
        } catch (IllegalStateException e) {
            assumeTrue(e.getMessage(), false);
        }
    }

    @After public void tearDown() {
        if (egl != null) egl.release();
    }

    private static RawLogProcessor.Config config(int cfa) {
        RawLogProcessor.Config c = new RawLogProcessor.Config();
        c.rawWidth = W + 8;
        c.rawHeight = H + 8;
        c.cropX = 4;
        c.cropY = 4;
        c.cropWidth = W;
        c.cropHeight = H;
        c.outWidth = W;
        c.outHeight = H;
        c.cfa = cfa;
        return c;
    }

    /** Bayer frame of one flat colour. rgb is linear 0..1 at the sensor, before white balance. */
    private static ByteBuffer frame(RawLogProcessor.Config c, double[] rgb, int stridePixels, float black, float white) {
        int[][] colorAtPos = {{0, 1, 1, 2}, {1, 0, 2, 1}, {1, 2, 0, 1}, {2, 1, 1, 0}};
        ByteBuffer b = ByteBuffer.allocateDirect(stridePixels * c.rawHeight * 2).order(ByteOrder.nativeOrder());
        ShortBuffer s = b.asShortBuffer();
        for (int y = 0; y < c.rawHeight; y++) {
            for (int x = 0; x < c.rawWidth; x++) {
                int color = colorAtPos[c.cfa][(x & 1) + ((y & 1) << 1)];
                double v = black + rgb[color] * (white - black);
                s.put(y * stridePixels + x, (short) Math.round(v));
            }
        }
        return b;
    }

    private static int[] sampleCentre(RawLogProcessor proc) {
        int[] out = new int[3];
        proc.readP010((y, uv, w, h) -> {
            int cx = w / 2 & ~1, cy = h / 2 & ~1;
            out[0] = (y.getShort((cy * w + cx) * 2) & 0xFFFF) >> 6;
            int offset = (cy / 2) * w + cx;
            out[1] = (uv.getShort(offset) & 0xFFFF) >> 6;
            out[2] = (uv.getShort(offset + 2) & 0xFFFF) >> 6;
        });
        return out;
    }

    private static int[] expected(double[] camRgb, RawLogProcessor.Params p) {
        double[] m = new double[9];
        for (int i = 0; i < 9; i++) m[i] = p.camToGamut[i];
        double[] balanced = {
            Math.min(1, camRgb[0] * p.gainsRgb[0]),
            Math.min(1, camRgb[1] * p.gainsRgb[1]),
            Math.min(1, camRgb[2] * p.gainsRgb[2])
        };
        double[] scene = ColorMath.mulVec(m, balanced);
        double[] sig = new double[3];
        for (int i = 0; i < 3; i++) {
            sig[i] = ColorMath.codeToNarrowSignal(ColorMath.slog3Encode(scene[i] * p.exposure));
        }
        return ColorMath.encodeP010Codes(sig[0], sig[1], sig[2]);
    }

    private static void assertCodes(String what, int[] want, int[] got, int tolerance) {
        for (int i = 0; i < 3; i++) {
            assertTrue(what + " channel " + i + ": want " + want[i] + " got " + got[i],
                    Math.abs(want[i] - got[i]) <= tolerance);
        }
    }

    @Test public void flatGreyReachesSonyMidGreyCode() {
        RawLogProcessor.Config c = config(0);
        RawLogProcessor p = new RawLogProcessor(c);
        RawLogProcessor.Params params = new RawLogProcessor.Params();
        params.black = new float[] {64, 64, 64, 64};
        params.white = 1023;
        params.exposure = 2.0f;
        // Sensor value that should land on scene 0.18 once exposure is applied.
        double[] rgb = {0.09, 0.09, 0.09};
        p.process(frame(c, rgb, c.rawWidth, 64, 1023), c.rawWidth * 2, params);
        int[] got = sampleCentre(p);
        assertCodes("grey", new int[] {420, 512, 512}, got, 2);
        p.release();
    }

    @Test public void everyBayerOrderGivesTheSameColour() {
        double[] rgb = {0.30, 0.20, 0.10};
        for (int cfa = 0; cfa < 4; cfa++) {
            RawLogProcessor.Config c = config(cfa);
            RawLogProcessor p = new RawLogProcessor(c);
            RawLogProcessor.Params params = new RawLogProcessor.Params();
            params.black = new float[] {100, 100, 100, 100};
            params.white = 4095;
            params.exposure = 2.0f;
            p.process(frame(c, rgb, c.rawWidth, 100, 4095), c.rawWidth * 2, params);
            assertCodes("cfa " + cfa, expected(rgb, params), sampleCentre(p), 2);
            p.release();
        }
    }

    @Test public void whiteBalanceMatrixAndRowStrideAreApplied() {
        RawLogProcessor.Config c = config(3);
        RawLogProcessor p = new RawLogProcessor(c);
        RawLogProcessor.Params params = new RawLogProcessor.Params();
        params.black = new float[] {64, 64, 64, 64};
        params.white = 1023;
        params.gainsRgb = new float[] {2.0f, 1.0f, 1.6f};
        params.camToGamut = new float[] {1.2f, -0.1f, -0.1f, -0.15f, 1.3f, -0.15f, 0.0f, -0.2f, 1.2f};
        params.exposure = 1.6f;
        double[] rgb = {0.12, 0.25, 0.15};
        int stride = c.rawWidth + 6;
        p.process(frame(c, rgb, stride, 64, 1023), stride * 2, params);
        assertCodes("wb", expected(rgb, params), sampleCentre(p), 2);
        p.release();
    }

    @Test public void shadingGainIsAppliedPerChannel() {
        RawLogProcessor.Config c = config(0);
        RawLogProcessor p = new RawLogProcessor(c);
        float[] map = new float[2 * 2 * 4];
        for (int i = 0; i < map.length; i += 4) {
            map[i] = 2f;       // R
            map[i + 1] = 1f;   // G even
            map[i + 2] = 1f;   // G odd
            map[i + 3] = 1.5f; // B
        }
        p.setLensShading(map, 2, 2);
        RawLogProcessor.Params params = new RawLogProcessor.Params();
        params.black = new float[] {64, 64, 64, 64};
        params.white = 1023;
        params.exposure = 2.0f;
        double[] raw = {0.1, 0.2, 0.1};
        p.process(frame(c, raw, c.rawWidth, 64, 1023), c.rawWidth * 2, params);
        assertCodes("shading", expected(new double[] {0.2, 0.2, 0.15}, params), sampleCentre(p), 2);
        p.release();
    }

    @Test public void halfScaleOutputKeepsFlatColour() {
        RawLogProcessor.Config c = config(0);
        c.outWidth = W / 2;
        c.outHeight = H / 2;
        RawLogProcessor p = new RawLogProcessor(c);
        RawLogProcessor.Params params = new RawLogProcessor.Params();
        params.black = new float[] {64, 64, 64, 64};
        params.white = 1023;
        params.exposure = 2.0f;
        double[] rgb = {0.25, 0.2, 0.15};
        p.process(frame(c, rgb, c.rawWidth, 64, 1023), c.rawWidth * 2, params);
        assertCodes("half", expected(rgb, params), sampleCentre(p), 2);
        p.release();
    }

    @Test public void fullSizeFrameFinishes() {
        RawLogProcessor.Config c = new RawLogProcessor.Config();
        c.rawWidth = 1856;
        c.rawHeight = 1392;
        c.cropX = 0;
        c.cropY = 176;
        c.cropWidth = 1856;
        c.cropHeight = 1040;
        c.outWidth = 1856;
        c.outHeight = 1040;
        c.cfa = 0;
        RawLogProcessor p = new RawLogProcessor(c);
        RawLogProcessor.Params params = new RawLogProcessor.Params();
        params.black = new float[] {64, 64, 64, 64};
        params.white = 1023;
        ByteBuffer raw = frame(c, new double[] {0.09, 0.09, 0.09}, c.rawWidth, 64, 1023);
        long t0 = android.os.SystemClock.elapsedRealtime();
        p.process(raw, c.rawWidth * 2, params);
        android.opengl.GLES20.glFinish();
        long t1 = android.os.SystemClock.elapsedRealtime();
        int[] got = sampleCentre(p);
        long t2 = android.os.SystemClock.elapsedRealtime();
        android.util.Log.i("TrueLogPerf", "process " + (t1 - t0) + " ms, P010 " + (t2 - t1) + " ms, centre Y " + got[0]);
        System.out.println("PERF process " + (t1 - t0) + " ms, P010 " + (t2 - t1) + " ms");
        assertCodes("full size", new int[] {420, 512, 512}, got, 2);
        p.release();
    }

    @Test public void sensorClipStaysNeutral() {
        RawLogProcessor.Config c = config(0);
        RawLogProcessor p = new RawLogProcessor(c);
        RawLogProcessor.Params params = new RawLogProcessor.Params();
        params.black = new float[] {64, 64, 64, 64};
        params.white = 1023;
        params.gainsRgb = new float[] {2.0f, 1.0f, 1.5f};
        params.exposure = 2.0f;
        p.process(frame(c, new double[] {1, 1, 1}, c.rawWidth, 64, 1023), c.rawWidth * 2, params);
        int[] got = sampleCentre(p);
        assertEquals("clip must not tint Cb", 512, got[1], 2);
        assertEquals("clip must not tint Cr", 512, got[2], 2);
        p.release();
    }
}
