package app.truelog.camera;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.params.BlackLevelPattern;
import android.hardware.camera2.params.LensShadingMap;
import android.media.Image;
import android.media.ImageReader;
import android.opengl.EGLSurface;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.util.Rational;
import android.view.Surface;

import app.truelog.camera.color.ColorMath;
import app.truelog.camera.gl.EglCore;
import app.truelog.camera.gl.RawLogProcessor;

/**
 * Runs the RAW to S-Log3 pipeline on its own GL thread. The newest RAW frame is taken each time
 * the thread is free, so a slow frame costs a dropped frame rather than growing latency.
 */
final class LogEngine implements ImageReader.OnImageAvailableListener {

    interface Listener {
        void onLogStats(Stats s);
        void onLogError(String message);
    }

    static final class Stats {
        long frames;
        long dropped;
        long written;
        float msPerFrame;
        double cct;
        boolean recording;
        boolean shadingMissing;
    }

    /** Sensor values from the most recent capture result. */
    static final class FrameMeta {
        float[] black;
        float white;
        double[] neutral;
        float[] shading;
        int shadingCols, shadingRows;
    }

    static final class Setup {
        RawLogProcessor.Config config;
        ColorMath.SensorColor color;
        boolean shadingAlreadyApplied;
        long clockOffsetNs;
        AvRecorder.EncoderChoice encoder;
        boolean rotate180;
        int fps;
    }

    private static final String TAG = "TrueLog";

    private final Settings settings;
    private final Listener listener;
    private final HandlerThread thread = new HandlerThread("log-gl");
    private final Handler handler;

    private EglCore egl;
    private RawLogProcessor proc;
    private Setup setup;
    private EGLSurface previewSurface, encoderSurface;
    private final RawLogProcessor.Params params = new RawLogProcessor.Params();
    private final RawLogProcessor.View view = new RawLogProcessor.View();

    private volatile FrameMeta meta;
    private double[] appliedNeutral;
    private double appliedCct;
    private int appliedKelvin = -1;
    private double appliedHeadroom = -1;
    private boolean wbLocked;
    private int shadingAge;
    private long imagesSeen;

    private AvRecorder recorder;
    private long frames, dropped, lastTs;
    private float avgMs;
    private long lastStatsAt;

    LogEngine(Settings s, Listener l) {
        settings = s;
        listener = l;
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    Handler handler() {
        return handler;
    }

    void start(Setup s, Surface preview) {
        handler.post(() -> {
            try {
                setup = s;
                egl = new EglCore(s.encoder != null && !s.encoder.p010Input);
                previewSurface = egl.createWindow(preview);
                egl.makeCurrent(previewSurface);
                egl.disableVsync();
                proc = new RawLogProcessor(s.config);
                double[] m = ColorMath.mul(ColorMath.invert(ColorMath.rec709ToXyzD65()), ColorMath.sGamut3CineToXyzD65());
                view.gamutToRec709 = toFloat(m);
                view.rotate180 = s.rotate180;
                Log.i(TAG, "log engine ready, raw " + s.config.rawWidth + "x" + s.config.rawHeight
                        + " out " + s.config.outWidth + "x" + s.config.outHeight);
            } catch (RuntimeException e) {
                listener.onLogError("Could not start Log processing: " + e.getMessage());
            }
        });
    }

    void updateMeta(CaptureResult r, CameraCharacteristics c) {
        FrameMeta m = new FrameMeta();

        float[] dyn = r.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL);
        if (dyn != null && dyn.length == 4) {
            m.black = dyn;
        } else {
            BlackLevelPattern p = c.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN);
            m.black = p == null ? new float[4] : new float[] {
                p.getOffsetForIndex(0, 0), p.getOffsetForIndex(1, 0),
                p.getOffsetForIndex(0, 1), p.getOffsetForIndex(1, 1)
            };
        }

        Integer dynWhite = r.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL);
        Integer white = c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL);
        m.white = dynWhite != null ? dynWhite : white != null ? white : 1023;

        Rational[] n = r.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT);
        if (n != null && n.length == 3) {
            m.neutral = new double[] {n[0].doubleValue(), n[1].doubleValue(), n[2].doubleValue()};
        }

        LensShadingMap map = r.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP);
        if (map != null) {
            m.shadingCols = map.getColumnCount();
            m.shadingRows = map.getRowCount();
            m.shading = new float[map.getGainFactorCount()];
            map.copyGainFactors(m.shading, 0);
        }
        meta = m;
    }

    @Override
    public void onImageAvailable(ImageReader reader) {
        Image img = reader.acquireLatestImage();
        if (img == null) return;
        try {
            FrameMeta m = meta;
            if (imagesSeen++ == 0) Log.i(TAG, "first RAW image, proc " + (proc != null) + " meta " + (m != null));
            if (proc != null && m != null) processFrame(img, m);
        } catch (RuntimeException e) {
            listener.onLogError("Frame processing failed: " + e.getMessage());
        } finally {
            img.close();
        }
    }

    private void processFrame(Image img, FrameMeta m) {
        long started = SystemClock.elapsedRealtimeNanos();
        boolean trace = frames < 3;
        if (trace) Log.i(TAG, "frame " + frames + " start");
        applyColor(m);
        if (trace) Log.i(TAG, "frame " + frames + " colour done, gains " + java.util.Arrays.toString(params.gainsRgb));
        params.black = m.black;
        params.white = m.white;

        boolean wantShading = !setup.shadingAlreadyApplied && m.shading != null;
        if (wantShading && --shadingAge <= 0) {
            proc.setLensShading(m.shading, m.shadingCols, m.shadingRows);
            shadingAge = 30;
        }

        Image.Plane plane = img.getPlanes()[0];
        proc.process(plane.getBuffer(), plane.getRowStride(), params);
        if (trace) {
            android.opengl.GLES20.glFinish();
            Log.i(TAG, "frame " + frames + " RAW to Log done at "
                    + (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000 + " ms");
        }

        long ts = img.getTimestamp();
        AvRecorder rec = recorder;
        if (rec != null) {
            long nominal = 1_000_000_000L / setup.fps;
            if (lastTs != 0) {
                long missed = Math.round((double) (ts - lastTs) / nominal) - 1;
                if (missed > 0) dropped += missed;
            }
            lastTs = ts;
            if (setup.encoder.p010Input) {
                proc.readP010((y, uv, w, h) -> {
                    if (!rec.queueP010(y, uv, w, h, ts)) dropped++;
                });
            } else if (encoderSurface != null) {
                egl.makeCurrent(encoderSurface);
                proc.drawForEncoder(egl.width(encoderSurface), egl.height(encoderSurface));
                egl.setPresentationTime(encoderSurface, ts);
                egl.swap(encoderSurface);
                egl.makeCurrent(previewSurface);
            }
        }

        view.assist = settings.assist;
        view.zebra = settings.zebra;
        view.peaking = settings.peaking;
        proc.drawPreview(egl.width(previewSurface), egl.height(previewSurface), view);
        if (trace) Log.i(TAG, "frame " + frames + " preview drawn");
        egl.swap(previewSurface);

        if (frames++ == 0) {
            Log.i(TAG, "first Log frame took " + (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000 + " ms");
        }
        float ms = (SystemClock.elapsedRealtimeNanos() - started) / 1e6f;
        avgMs = avgMs == 0 ? ms : avgMs * 0.95f + ms * 0.05f;
        long now = SystemClock.elapsedRealtime();
        if (now - lastStatsAt > 500) {
            lastStatsAt = now;
            Stats s = new Stats();
            s.frames = frames;
            s.dropped = dropped;
            s.msPerFrame = avgMs;
            s.cct = appliedCct;
            s.recording = rec != null;
            s.shadingMissing = !setup.shadingAlreadyApplied && m.shading == null;
            s.written = rec != null ? rec.framesWritten() : 0;
            listener.onLogStats(s);
        }
    }

    private void applyColor(FrameMeta m) {
        double headroom = settings.headroomStops;
        if (headroom != appliedHeadroom) {
            appliedHeadroom = headroom;
            params.exposure = (float) (0.18 * Math.pow(2.0, headroom));
            view.clipSignal = (float) ColorMath.codeToNarrowSignal(ColorMath.slog3Encode(params.exposure * 0.93));
        }
        if (wbLocked) return;

        int kelvin = settings.wbAuto ? 0 : settings.kelvin;
        double[] neutral;
        if (kelvin > 0) {
            if (kelvin == appliedKelvin) return;
            neutral = ColorMath.xyToNeutral(setup.color, ColorMath.cctToXy(kelvin));
        } else {
            if (m.neutral == null) {
                if (appliedNeutral != null) return;
                neutral = ColorMath.xyToNeutral(setup.color, ColorMath.D65_XY);
            } else {
                neutral = m.neutral;
                if (appliedKelvin == 0 && appliedNeutral != null && close(appliedNeutral, neutral)) return;
            }
        }
        ColorMath.WhiteBalance wb = ColorMath.solve(setup.color, neutral);
        params.gainsRgb = new float[] {(float) wb.gains[0], (float) wb.gains[1], (float) wb.gains[2]};
        params.camToGamut = toFloat(wb.camToSGamut);
        appliedNeutral = neutral;
        appliedKelvin = kelvin;
        appliedCct = wb.cct;
    }

    private static boolean close(double[] a, double[] b) {
        double d = 0;
        for (int i = 0; i < 3; i++) d = Math.max(d, Math.abs(a[i] - b[i]) / Math.max(1e-6, a[i]));
        return d < 0.004;
    }

    private static float[] toFloat(double[] m) {
        float[] f = new float[m.length];
        for (int i = 0; i < m.length; i++) f[i] = (float) m[i];
        return f;
    }

    /** Begins recording. White balance locks so colour cannot drift during a take. */
    void startRecording(AvRecorder.Params p, java.io.FileDescriptor fd, Runnable onStarted) {
        handler.post(() -> {
            try {
                AvRecorder r = new AvRecorder(p, fd);
                r.setClockOffsetNs(setup.clockOffsetNs);
                if (!p.p010Input) encoderSurface = egl.createWindow(r.inputSurface());
                dropped = 0;
                lastTs = 0;
                wbLocked = true;
                r.start();
                recorder = r;
                onStarted.run();
            } catch (Exception e) {
                wbLocked = false;
                listener.onLogError("Could not start recording: " + e.getMessage());
            }
        });
    }

    interface StopCallback {
        void onStopped(String errorOrNull, long dropped, long written, String warning);
    }

    void stopRecording(StopCallback cb) {
        handler.post(() -> {
            AvRecorder r = recorder;
            recorder = null;
            wbLocked = false;
            if (r == null) {
                cb.onStopped("Not recording", 0, 0, null);
                return;
            }
            String error = r.stop();
            if (encoderSurface != null) {
                egl.destroy(encoderSurface);
                encoderSurface = null;
            }
            cb.onStopped(error, dropped, r.framesWritten(), r.warning());
        });
    }

    /** Stops the thread after releasing GL. Safe to call more than once. */
    void shutdown() {
        handler.post(() -> {
            try {
                if (proc != null) proc.release();
                if (egl != null) {
                    egl.makeOffscreenCurrent();
                    egl.destroy(previewSurface);
                    egl.release();
                }
            } catch (RuntimeException ignored) {
                // Tearing down after the surface has gone can fail harmlessly.
            }
            proc = null;
            egl = null;
            thread.quitSafely();
        });
    }
}
