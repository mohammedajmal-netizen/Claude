package app.truelog.camera;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.DngCreator;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.ColorSpaceTransform;
import android.hardware.camera2.params.DynamicRangeProfiles;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.RggbChannelVector;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ExifInterface;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Range;
import android.util.Rational;
import android.util.Size;
import android.view.Surface;
import android.view.SurfaceHolder;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import app.truelog.camera.Settings.Mode;
import app.truelog.camera.color.ColorMath;
import app.truelog.camera.gl.RawLogProcessor;

/** All Camera2 work. Every field below is touched only on the camera thread. */
final class CameraController implements LogEngine.Listener {

    interface Listener {
        void onCameraReady(String summary);
        void onCameraError(String message);
        void onSaved(Uri uri, String what);
        void onRecordingChanged(boolean recording);
        void onReadout(int iso, long exposureNs, float focusDiopters);
        void onLogStats(LogEngine.Stats s);
        void onNotice(String message);
        int deviceOrientation();
    }

    /** The RAW stream chosen for Log, or the reason there is not one. */
    static final class LogPlan {
        Size raw;
        int outW, outH, cropW, cropH;
        long minFrameNs;
        String problem;
    }

    private final Context context;
    private final CameraManager manager;
    private final Settings settings;
    private final Listener listener;
    private final HandlerThread thread = new HandlerThread("camera");
    private final Handler handler;

    private int generation;
    private Lens lens;
    private CameraCharacteristics chars;
    private Surface previewSurface;
    private CameraDevice device;
    private CameraCaptureSession session;
    private CaptureRequest.Builder repeating;
    private ImageReader jpegReader, rawReader, logReader;
    private LogEngine engine;
    private ColorMath.SensorColor sensorColor;

    private AvRecorder recorder;
    private MediaSaver.PendingVideo pendingVideo;
    private boolean recording;

    private int lastIso = 100;
    private long lastExposure = 10_000_000L;
    private long lastReadoutAt;
    private TotalCaptureResult pendingDngResult;
    private Image pendingDngImage;
    private int pendingDngOrientation;

    CameraController(Context c, Settings s, Listener l) {
        context = c.getApplicationContext();
        manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        settings = s;
        listener = l;
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    // ---- Sizes ----

    static Size previewSizeFor(Lens lens, Mode mode) {
        StreamConfigurationMap map = lens.chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        double want = mode == Mode.VIDEO ? 16.0 / 9.0 : 4.0 / 3.0;
        Size best = null;
        for (Size s : map.getOutputSizes(SurfaceHolder.class)) {
            if (s.getWidth() > 1920 || s.getHeight() > 1440) continue;
            if (Math.abs((double) s.getWidth() / s.getHeight() - want) > 0.02) continue;
            if (best == null || s.getWidth() > best.getWidth()) best = s;
        }
        return best != null ? best : new Size(1920, 1080);
    }

    /** Picks the smallest RAW size that covers the output and can hold the frame rate. */
    static LogPlan planLog(Lens lens, int outH, int fps) {
        LogPlan plan = new LogPlan();
        int outW = outH * 16 / 9;
        if (!lens.hasRaw) {
            plan.problem = "This lens does not offer RAW to other apps";
            return plan;
        }
        StreamConfigurationMap map = lens.chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] sizes = map.getOutputSizes(ImageFormat.RAW_SENSOR);
        if (sizes == null || sizes.length == 0) {
            plan.problem = "This lens lists no RAW sizes";
            return plan;
        }
        long budget = 1_000_000_000L / fps;
        long fastest = Long.MAX_VALUE;
        Size pick = null;
        for (Size s : sizes) {
            long dur = map.getOutputMinFrameDuration(ImageFormat.RAW_SENSOR, s);
            if (s.getWidth() >= outW && s.getHeight() >= outH) {
                fastest = Math.min(fastest, dur);
                if (dur <= budget * 1.02 && (pick == null || s.getWidth() < pick.getWidth())) pick = s;
            }
        }
        if (pick == null) {
            plan.problem = fastest == Long.MAX_VALUE
                    ? "RAW on this lens is too small for " + outH + "p"
                    : String.format(Locale.US, "RAW tops out at %.0f fps at %dp on this lens", 1e9 / fastest, outH);
            return plan;
        }
        plan.raw = pick;
        plan.minFrameNs = map.getOutputMinFrameDuration(ImageFormat.RAW_SENSOR, pick);
        plan.outW = outW;
        plan.outH = outH;
        // 1080p from a frame that covers 4K is an exact 2x downscale, so it keeps the wider view.
        boolean half = outH == 1080 && pick.getWidth() >= 3840 && pick.getHeight() >= 2160;
        plan.cropW = half ? 3840 : outW;
        plan.cropH = half ? 2160 : outH;
        return plan;
    }

    // ---- Open and close ----

    void open(Lens l, Surface preview) {
        handler.post(() -> {
            closeInternal();
            int gen = ++generation;
            lens = l;
            chars = l.chars;
            previewSurface = preview;
            sensorColor = readSensorColor(chars);
            try {
                openDevice(gen);
            } catch (CameraAccessException | SecurityException | IllegalArgumentException e) {
                listener.onCameraError("Could not open lens " + l.label + ": " + e.getMessage());
            }
        });
    }

    @SuppressLint("MissingPermission")
    private void openDevice(int gen) throws CameraAccessException {
        manager.openCamera(lens.cameraId, new CameraDevice.StateCallback() {
            @Override public void onOpened(CameraDevice d) {
                if (gen != generation) {
                    d.close();
                    return;
                }
                device = d;
                try {
                    configureSession(gen);
                } catch (CameraAccessException | IOException | RuntimeException e) {
                    listener.onCameraError("Could not start camera: " + e.getMessage());
                }
            }
            @Override public void onDisconnected(CameraDevice d) {
                d.close();
                if (gen == generation) device = null;
            }
            @Override public void onError(CameraDevice d, int error) {
                d.close();
                if (gen != generation) return;
                device = null;
                listener.onCameraError("Camera error " + error + " on lens " + lens.label);
            }
        }, handler);
    }

    void close() {
        handler.post(() -> {
            generation++;
            closeInternal();
        });
    }

    void shutdown() {
        handler.post(() -> {
            generation++;
            closeInternal();
            thread.quitSafely();
        });
    }

    private void closeInternal() {
        if (recording) stopRecordingInternal();
        if (session != null) {
            try {
                session.close();
            } catch (RuntimeException ignored) {
                // Already closed by the framework.
            }
            session = null;
        }
        if (device != null) {
            device.close();
            device = null;
        }
        if (jpegReader != null) {
            jpegReader.close();
            jpegReader = null;
        }
        if (rawReader != null) {
            rawReader.close();
            rawReader = null;
        }
        if (engine != null) {
            LogEngine e = engine;
            ImageReader r = logReader;
            // The reader must close on the GL thread, or a frame in progress would lose its buffer.
            e.handler().post(() -> {
                if (r != null) r.close();
            });
            e.shutdown();
            engine = null;
            logReader = null;
        }
        repeating = null;
    }

    // ---- Session setup ----

    private OutputConfiguration output(Surface s) {
        OutputConfiguration o = new OutputConfiguration(s);
        if (lens.physicalId != null) o.setPhysicalCameraId(lens.physicalId);
        return o;
    }

    private void configureSession(int gen) throws CameraAccessException, IOException {
        Mode mode = settings.mode;
        List<OutputConfiguration> outs = new ArrayList<>();
        int template;
        if (mode == Mode.LOG) {
            template = CameraDevice.TEMPLATE_RECORD;
            if (!startLogEngine(outs)) return;
        } else if (mode == Mode.VIDEO) {
            template = CameraDevice.TEMPLATE_RECORD;
            boolean hlg = settings.hlg && hlgSupported();
            OutputConfiguration p = output(previewSurface);
            if (hlg) p.setDynamicRangeProfile(DynamicRangeProfiles.HLG10);
            outs.add(p);
            if (recorder != null) {
                OutputConfiguration e = output(recorder.inputSurface());
                if (hlg) e.setDynamicRangeProfile(DynamicRangeProfiles.HLG10);
                outs.add(e);
            }
        } else {
            template = CameraDevice.TEMPLATE_PREVIEW;
            outs.add(output(previewSurface));
            Size jpeg = largest(ImageFormat.JPEG);
            jpegReader = ImageReader.newInstance(jpeg.getWidth(), jpeg.getHeight(), ImageFormat.JPEG, 3);
            jpegReader.setOnImageAvailableListener(this::onJpeg, handler);
            outs.add(output(jpegReader.getSurface()));
            if (mode == Mode.PRO && settings.saveDng && lens.hasRaw) {
                Size raw = largest(ImageFormat.RAW_SENSOR);
                rawReader = ImageReader.newInstance(raw.getWidth(), raw.getHeight(), ImageFormat.RAW_SENSOR, 2);
                rawReader.setOnImageAvailableListener(this::onRaw, handler);
                outs.add(output(rawReader.getSurface()));
            }
        }

        // Stills add their own target at capture time, so the preview request stays lightweight.
        List<Surface> previewTargets = new ArrayList<>();
        previewTargets.add(outs.get(0).getSurface());
        if (recorder != null) previewTargets.add(recorder.inputSurface());

        SessionConfiguration sc = new SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outs,
                handler::post, new CameraCaptureSession.StateCallback() {
            @Override public void onConfigured(CameraCaptureSession s) {
                if (gen != generation) {
                    s.close();
                    return;
                }
                session = s;
                try {
                    repeating = device.createCaptureRequest(template);
                    for (Surface t : previewTargets) repeating.addTarget(t);
                    applySettings(repeating);
                    if (recorder != null) recorder.start();
                    s.setRepeatingRequest(repeating.build(), resultCallback, handler);
                    if (recorder != null) {
                        recording = true;
                        listener.onRecordingChanged(true);
                    }
                    listener.onCameraReady(summary());
                } catch (CameraAccessException | RuntimeException e) {
                    listener.onCameraError("Could not start preview: " + e.getMessage());
                }
            }
            @Override public void onConfigureFailed(CameraCaptureSession s) {
                if (gen == generation) {
                    listener.onCameraError(mode == Mode.LOG
                            ? "This lens refused a RAW video stream"
                            : "The camera refused this combination of settings");
                }
            }
        });
        device.createCaptureSession(sc);
    }

    private boolean startLogEngine(List<OutputConfiguration> outs) {
        LogPlan plan = planLog(lens, settings.logHeight, settings.logFps);
        if (plan.problem != null) {
            listener.onCameraError(plan.problem);
            return false;
        }
        AvRecorder.EncoderChoice enc = AvRecorder.chooseTenBit(plan.outW, plan.outH, settings.logFps);
        if (enc == null) {
            listener.onCameraError("No 10-bit HEVC encoder for " + plan.outH + "p" + settings.logFps);
            return false;
        }
        Integer cfa = chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT);
        if (cfa == null || cfa > CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR) {
            listener.onCameraError("This lens has a sensor layout the Log engine cannot read");
            return false;
        }
        if (sensorColor == null) {
            listener.onCameraError("This lens does not publish colour calibration");
            return false;
        }

        RawLogProcessor.Config cfg = new RawLogProcessor.Config();
        cfg.rawWidth = plan.raw.getWidth();
        cfg.rawHeight = plan.raw.getHeight();
        cfg.cropWidth = plan.cropW;
        cfg.cropHeight = plan.cropH;
        cfg.cropX = ((cfg.rawWidth - cfg.cropWidth) / 2) & ~1;
        cfg.cropY = ((cfg.rawHeight - cfg.cropHeight) / 2) & ~1;
        cfg.outWidth = plan.outW;
        cfg.outHeight = plan.outH;
        cfg.cfa = cfa;

        LogEngine.Setup s = new LogEngine.Setup();
        s.config = cfg;
        s.color = sensorColor;
        Boolean applied = chars.get(CameraCharacteristics.SENSOR_INFO_LENS_SHADING_APPLIED);
        s.shadingAlreadyApplied = applied != null && applied;
        s.clockOffsetNs = clockOffsetNs();
        s.encoder = enc;
        s.fps = settings.logFps;
        Integer so = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
        s.rotate180 = so != null && so == 270;

        engine = new LogEngine(settings, this);
        engine.start(s, previewSurface);
        logReader = ImageReader.newInstance(cfg.rawWidth, cfg.rawHeight, ImageFormat.RAW_SENSOR, 4);
        logReader.setOnImageAvailableListener(engine, engine.handler());
        outs.add(output(logReader.getSurface()));
        return true;
    }

    private long clockOffsetNs() {
        Integer src = chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
        if (src != null && src == CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME) return 0;
        return SystemClock.elapsedRealtimeNanos() - System.nanoTime();
    }

    private Size largest(int format) {
        StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size best = null;
        for (Size s : map.getOutputSizes(format)) {
            if (best == null || (long) s.getWidth() * s.getHeight() > (long) best.getWidth() * best.getHeight()) {
                best = s;
            }
        }
        return best;
    }

    static boolean hlgSupported(Lens l) {
        DynamicRangeProfiles p = l.chars.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES);
        return p != null && p.getSupportedProfiles().contains(DynamicRangeProfiles.HLG10);
    }

    private boolean hlgSupported() {
        return hlgSupported(lens);
    }

    private String summary() {
        Mode m = settings.mode;
        if (m == Mode.LOG) {
            return String.format(Locale.US, "S-Log3 / S-Gamut3.Cine  %dp%d  %d Mb/s",
                    settings.logHeight, settings.logFps, settings.logMbps);
        }
        if (m == Mode.VIDEO) {
            return String.format(Locale.US, "%s  %dp%d  %d Mb/s", settings.hlg ? "HLG 10-bit" : "8-bit",
                    settings.videoHeight, settings.videoFps, settings.videoMbps);
        }
        return m == Mode.PRO ? "Pro" + (rawReader != null ? "  JPEG + DNG" : "  JPEG") : "Photo";
    }

    // ---- Request settings ----

    private void put(CaptureRequest.Builder b, CaptureRequest.Key<Integer> key, int value,
                     CameraCharacteristics.Key<int[]> available) {
        int[] modes = chars.get(available);
        if (modes == null) return;
        for (int m : modes) {
            if (m == value) {
                b.set(key, value);
                return;
            }
        }
    }

    private void applySettings(CaptureRequest.Builder b) {
        Mode mode = settings.mode;
        boolean video = mode == Mode.VIDEO || mode == Mode.LOG;
        boolean manualControls = mode == Mode.PRO || mode == Mode.LOG;
        int fps = mode == Mode.LOG ? settings.logFps : settings.videoFps;

        boolean manualExposure = !settings.isoAuto || !settings.shutterAuto;
        boolean canManual = Lens.has(chars, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR);
        if (manualExposure && canManual && manualControls) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF);
            b.set(CaptureRequest.SENSOR_SENSITIVITY, settings.isoAuto ? lastIso : settings.iso);
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, settings.shutterAuto ? lastExposure : settings.shutterNs);
            if (mode == Mode.LOG) b.set(CaptureRequest.SENSOR_FRAME_DURATION, 1_000_000_000L / fps);
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, flashAeMode());
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, settings.evSteps);
            b.set(CaptureRequest.CONTROL_AE_LOCK, settings.aeLock);
            if (video) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(fps, fps));
        }
        b.set(CaptureRequest.FLASH_MODE, settings.flash == 3 && !video
                ? CameraMetadata.FLASH_MODE_TORCH : CameraMetadata.FLASH_MODE_OFF);

        if (!settings.focusAuto && manualControls) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF);
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, settings.focusDiopters);
        } else {
            put(b, CaptureRequest.CONTROL_AF_MODE, video ? CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                    : CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                    CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        }

        if (mode == Mode.LOG) {
            // White balance happens in our shader, so the camera's value is only a suggestion.
            b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO);
            b.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                    CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON);
            b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
            b.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_OFF);
            b.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF);
        } else {
            applyManualWhite(b, mode);
            if (mode == Mode.VIDEO) {
                put(b, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, settings.stabilise
                        ? CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
                        : CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
                        CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
            }
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, clampZoom(settings.zoom));
        }
        put(b, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON,
                CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
    }

    private int flashAeMode() {
        if (settings.mode == Mode.PHOTO || settings.mode == Mode.PRO) {
            if (settings.flash == 1) return CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH;
            if (settings.flash == 2) return CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH;
        }
        return CameraMetadata.CONTROL_AE_MODE_ON;
    }

    private void applyManualWhite(CaptureRequest.Builder b, Mode mode) {
        if (settings.wbAuto || mode != Mode.PRO || sensorColor == null
                || !Lens.has(chars, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING)) {
            b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO);
            return;
        }
        double[] neutral = ColorMath.xyToNeutral(sensorColor, ColorMath.cctToXy(settings.kelvin));
        ColorMath.WhiteBalance wb = ColorMath.solve(sensorColor, neutral);
        b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF);
        b.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX);
        b.set(CaptureRequest.COLOR_CORRECTION_GAINS, new RggbChannelVector((float) wb.gains[0],
                (float) wb.gains[1], (float) wb.gains[1], (float) wb.gains[2]));
        Rational[] t = new Rational[9];
        for (int i = 0; i < 9; i++) t[i] = new Rational((int) Math.round(wb.camToRec709[i] * 1024), 1024);
        b.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, new ColorSpaceTransform(t));
    }

    private float clampZoom(float z) {
        Range<Float> r = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
        return r == null ? 1f : r.clamp(z);
    }

    /** Pushes changed settings into the running preview. */
    void applyChanges() {
        handler.post(() -> {
            if (session == null || repeating == null) return;
            try {
                applySettings(repeating);
                session.setRepeatingRequest(repeating.build(), resultCallback, handler);
            } catch (CameraAccessException | RuntimeException e) {
                listener.onNotice("Could not apply that setting: " + e.getMessage());
            }
        });
    }

    /** Tap to focus. x and y are 0..1 within the visible picture. */
    void focusAt(float x, float y, float visibleAspect) {
        handler.post(() -> {
            if (session == null || repeating == null || !settings.focusAuto) return;
            Rect a = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            Integer maxAf = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
            if (a == null || maxAf == null || maxAf < 1) return;
            float activeAspect = (float) a.width() / a.height();
            float w = a.width(), h = a.height();
            if (visibleAspect > activeAspect) h = w / visibleAspect;
            else w = h * visibleAspect;
            int cx = Math.round(a.centerX() + (x - 0.5f) * w);
            int cy = Math.round(a.centerY() + (y - 0.5f) * h);
            int half = a.width() / 16;
            Rect r = new Rect(Math.max(a.left, cx - half), Math.max(a.top, cy - half),
                    Math.min(a.right, cx + half), Math.min(a.bottom, cy + half));
            MeteringRectangle[] regions = {new MeteringRectangle(r, MeteringRectangle.METERING_WEIGHT_MAX)};
            try {
                repeating.set(CaptureRequest.CONTROL_AF_REGIONS, regions);
                repeating.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO);
                repeating.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
                session.capture(repeating.build(), resultCallback, handler);
                repeating.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
                session.setRepeatingRequest(repeating.build(), resultCallback, handler);
            } catch (CameraAccessException | RuntimeException e) {
                listener.onNotice("Could not focus there");
            }
        });
    }

    private final CameraCaptureSession.CaptureCallback resultCallback = new CameraCaptureSession.CaptureCallback() {
        @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult result) {
            LogEngine e = engine;
            if (e != null) e.updateMeta(result, chars);
            Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
            Long exp = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            Float focus = result.get(CaptureResult.LENS_FOCUS_DISTANCE);
            if (iso != null) lastIso = iso;
            if (exp != null) lastExposure = exp;
            long now = SystemClock.elapsedRealtime();
            if (now - lastReadoutAt > 250) {
                lastReadoutAt = now;
                listener.onReadout(lastIso, lastExposure, focus != null ? focus : 0f);
            }
        }
    };

    // ---- Stills ----

    private int imageOrientation() {
        Integer so = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
        int deviceDegrees = listener.deviceOrientation();
        if (lens.front) deviceDegrees = -deviceDegrees;
        return ((so == null ? 90 : so) + deviceDegrees + 360) % 360;
    }

    void capturePhoto() {
        handler.post(() -> {
            if (session == null || jpegReader == null) return;
            try {
                CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                b.addTarget(jpegReader.getSurface());
                if (rawReader != null) b.addTarget(rawReader.getSurface());
                applySettings(b);
                int orientation = imageOrientation();
                b.set(CaptureRequest.JPEG_ORIENTATION, orientation);
                b.set(CaptureRequest.JPEG_QUALITY, (byte) 97);
                pendingDngOrientation = orientation;
                if (rawReader != null) {
                    b.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                            CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON);
                }
                session.capture(b.build(), new CameraCaptureSession.CaptureCallback() {
                    @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r,
                                                             TotalCaptureResult result) {
                        if (rawReader != null) {
                            pendingDngResult = result;
                            maybeWriteDng();
                        }
                    }
                }, handler);
            } catch (CameraAccessException | RuntimeException e) {
                listener.onCameraError("Could not take the photo: " + e.getMessage());
            }
        });
    }

    private void onJpeg(ImageReader r) {
        try (Image img = r.acquireNextImage()) {
            if (img == null) return;
            ByteBuffer b = img.getPlanes()[0].getBuffer();
            byte[] data = new byte[b.remaining()];
            b.get(data);
            Uri uri = MediaSaver.saveImage(context, "TL_" + MediaSaver.stamp() + ".jpg", "image/jpeg",
                    os -> os.write(data));
            listener.onSaved(uri, "Photo saved");
        } catch (IOException | RuntimeException e) {
            listener.onCameraError("Could not save the photo: " + e.getMessage());
        }
    }

    private void onRaw(ImageReader r) {
        Image img = r.acquireNextImage();
        if (img == null) return;
        if (pendingDngImage != null) pendingDngImage.close();
        pendingDngImage = img;
        maybeWriteDng();
    }

    private void maybeWriteDng() {
        if (pendingDngImage == null || pendingDngResult == null) return;
        Image img = pendingDngImage;
        TotalCaptureResult res = pendingDngResult;
        pendingDngImage = null;
        pendingDngResult = null;
        try (DngCreator dng = new DngCreator(chars, res)) {
            dng.setOrientation(exifOrientation(pendingDngOrientation));
            Uri uri = MediaSaver.saveImage(context, "TL_" + MediaSaver.stamp() + ".dng", "image/x-adobe-dng",
                    os -> dng.writeImage(os, img));
            listener.onSaved(uri, "RAW saved");
        } catch (IOException | RuntimeException e) {
            listener.onCameraError("Could not save RAW: " + e.getMessage());
        } finally {
            img.close();
        }
    }

    private static int exifOrientation(int degrees) {
        switch (degrees) {
            case 90: return ExifInterface.ORIENTATION_ROTATE_90;
            case 180: return ExifInterface.ORIENTATION_ROTATE_180;
            case 270: return ExifInterface.ORIENTATION_ROTATE_270;
            default: return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    // ---- Video ----

    void toggleRecording() {
        handler.post(() -> {
            if (recording) stopRecordingInternal();
            else startRecordingInternal();
        });
    }

    private void startRecordingInternal() {
        if (session == null) return;
        try {
            if (settings.mode == Mode.LOG) startLogRecording();
            else if (settings.mode == Mode.VIDEO) startVideoRecording();
        } catch (IOException | CameraAccessException | RuntimeException e) {
            listener.onCameraError("Could not start recording: " + e.getMessage());
        }
    }

    private void startLogRecording() throws IOException {
        LogPlan plan = planLog(lens, settings.logHeight, settings.logFps);
        AvRecorder.EncoderChoice enc = AvRecorder.chooseTenBit(plan.outW, plan.outH, settings.logFps);
        pendingVideo = MediaSaver.beginVideo(context, "TL_" + MediaSaver.stamp() + "_SLog3.mp4");

        AvRecorder.Params p = new AvRecorder.Params();
        p.width = plan.outW;
        p.height = plan.outH;
        p.fps = settings.logFps;
        p.bitrate = Math.min(settings.logMbps * 1_000_000, enc.maxBitrate);
        p.tenBit = true;
        p.log = true;
        p.p010Input = enc.p010Input;
        p.encoderName = enc.name;
        p.audio = true;
        p.orientationHint = imageOrientation();

        repeating.set(CaptureRequest.CONTROL_AWB_LOCK, true);
        applyChanges();
        engine.startRecording(p, pendingVideo.fd.getFileDescriptor(), () -> handler.post(() -> {
            recording = true;
            listener.onRecordingChanged(true);
            listener.onNotice(enc.describe());
        }));
    }

    private void startVideoRecording() throws IOException, CameraAccessException {
        pendingVideo = MediaSaver.beginVideo(context, "TL_" + MediaSaver.stamp() + ".mp4");
        AvRecorder.Params p = new AvRecorder.Params();
        p.height = settings.videoHeight;
        p.width = p.height * 16 / 9;
        p.fps = settings.videoFps;
        p.bitrate = settings.videoMbps * 1_000_000;
        p.hlg = settings.hlg && hlgSupported();
        p.tenBit = p.hlg;
        p.audio = true;
        p.orientationHint = imageOrientation();

        session.close();
        session = null;
        recorder = new AvRecorder(p, pendingVideo.fd.getFileDescriptor());
        recorder.setClockOffsetNs(clockOffsetNs());
        configureSession(generation);
    }

    private void stopRecordingInternal() {
        recording = false;
        listener.onRecordingChanged(false);
        if (settings.mode == Mode.LOG && engine != null) {
            MediaSaver.PendingVideo pv = pendingVideo;
            pendingVideo = null;
            engine.stopRecording((error, dropped, written, warning) -> {
                finishFile(pv, error, dropped, written, warning);
                handler.post(() -> {
                    if (repeating != null) {
                        repeating.set(CaptureRequest.CONTROL_AWB_LOCK, false);
                        applyChanges();
                    }
                });
            });
            return;
        }
        if (recorder != null) {
            AvRecorder r = recorder;
            recorder = null;
            MediaSaver.PendingVideo pv = pendingVideo;
            pendingVideo = null;
            try {
                if (session != null) {
                    session.stopRepeating();
                    session.abortCaptures();
                }
            } catch (CameraAccessException | RuntimeException ignored) {
                // The session may already be closing.
            }
            String error = r.stop();
            finishFile(pv, error, 0, r.framesWritten(), r.warning());
            if (session != null) {
                session.close();
                session = null;
            }
            if (device != null) {
                try {
                    configureSession(generation);
                } catch (CameraAccessException | IOException | RuntimeException e) {
                    listener.onCameraError("Could not resume preview: " + e.getMessage());
                }
            }
        }
    }

    private void finishFile(MediaSaver.PendingVideo pv, String error, long dropped, long written, String warning) {
        boolean keep = pv != null && written > 0;
        if (pv != null) MediaSaver.finishVideo(context, pv, keep);
        if (error != null) {
            listener.onCameraError(error + (keep ? " (the clip may still play)" : ""));
        } else if (keep) {
            String msg = "Clip saved, " + written + " frames" + (dropped > 0 ? ", " + dropped + " dropped" : "");
            listener.onSaved(pv.uri, warning != null ? msg + ". " + warning : msg);
        }
    }

    // ---- Calibration ----

    private static double[] matrix(ColorSpaceTransform t) {
        if (t == null) return null;
        double[] m = new double[9];
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) m[row * 3 + col] = t.getElement(col, row).doubleValue();
        }
        return m;
    }

    static ColorMath.SensorColor readSensorColor(CameraCharacteristics c) {
        double[] cm1 = matrix(c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1));
        double[] cm2 = matrix(c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2));
        if (cm1 == null && cm2 == null) return null;
        ColorMath.SensorColor s = new ColorMath.SensorColor();
        Integer i1 = c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1);
        Byte i2 = c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2);
        s.cct1 = ColorMath.illuminantToCct(i1 == null ? 21 : i1);
        s.cct2 = i2 == null || i2 == 0 ? s.cct1 : ColorMath.illuminantToCct(i2);
        s.xyzToCam1 = cm1 != null ? cm1 : cm2;
        s.xyzToCam2 = cm2 != null ? cm2 : cm1;
        double[] fm1 = matrix(c.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1));
        double[] fm2 = matrix(c.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2));
        s.forward1 = fm1 != null ? fm1 : fm2;
        s.forward2 = fm2 != null ? fm2 : fm1;
        double[] cal1 = matrix(c.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1));
        double[] cal2 = matrix(c.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2));
        if (cal1 != null) s.calib1 = cal1;
        s.calib2 = cal2 != null ? cal2 : s.calib1;
        return s;
    }

    // ---- LogEngine.Listener ----

    @Override public void onLogStats(LogEngine.Stats s) {
        listener.onLogStats(s);
    }

    @Override public void onLogError(String message) {
        listener.onCameraError(message);
    }
}
