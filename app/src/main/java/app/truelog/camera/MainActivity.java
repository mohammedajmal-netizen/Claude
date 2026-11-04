package app.truelog.camera;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.StatFs;
import android.os.Environment;
import android.os.SystemClock;
import android.util.Range;
import android.util.Size;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import app.truelog.camera.Settings.Mode;

/** The camera screen. Landscape only, as most cine camera apps are. */
public class MainActivity extends Activity implements CameraController.Listener {

    private static final int AMBER = 0xFFFFB020;
    private static final int RED = 0xFFFF3B30;
    private static final int TEXT = 0xFFF2F2F2;
    private static final int DIM = 0xFF9A9A9A;
    private static final int PANEL = 0xCC101010;

    private enum Dial { ISO, SHUTTER, WB, FOCUS, EV }

    private final Settings settings = new Settings();
    private final Handler main = new Handler(Looper.getMainLooper());
    private CameraController camera;
    private List<Lens> lenses = new ArrayList<>();
    private Lens lens;

    private FrameLayout previewBox;
    private SurfaceView cameraView, logView;
    private boolean cameraSurfaceReady, logSurfaceReady;
    private Size wantedPreview;
    private boolean resumed, permitted, openPending;

    private TextView status, readout, notice;
    private LinearLayout lensRail, modeRail, dialRow, logRow;
    private View shutter;
    private SeekBar dialBar;
    private Dial activeDial;

    private boolean recording;
    private long recordStartedAt;
    private String summary = "";
    private String statsLine = "";
    private String thermal = "";
    private int orientationDegrees;
    private OrientationEventListener orientationListener;
    private PowerManager power;
    private final PowerManager.OnThermalStatusChangedListener thermalListener = this::onThermal;
    private ScaleGestureDetector pinch;
    private Dial dialForReadout;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            renderStatus();
            main.postDelayed(this, 500);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        camera = new CameraController(this, settings, this);
        power = getSystemService(PowerManager.class);
        buildUi();
        lenses = Lens.scan(getSystemService(CameraManager.class));
        for (Lens l : lenses) {
            if ("1".equals(l.label)) lens = l;
        }
        if (lens == null && !lenses.isEmpty()) lens = lenses.get(0);
        rebuildLensRail();
        rebuildModeRail();
        rebuildControls();
        for (String line : DeviceReport.build(lenses).split("\n")) android.util.Log.i("TrueLog", line);

        orientationListener = new OrientationEventListener(this) {
            @Override public void onOrientationChanged(int o) {
                if (o != ORIENTATION_UNKNOWN) orientationDegrees = ((o + 45) / 90 * 90) % 360;
            }
        };
        requestAccess();
    }

    private void requestAccess() {
        boolean cam = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        permitted = cam;
        if (!cam || !mic) {
            requestPermissions(new String[] {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, 1);
        }
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        permitted = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        if (permitted) requestOpen();
        else toast("Camera permission is needed");
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        hideBars();
        orientationListener.enable();
        power.addThermalStatusListener(thermalListener);
        main.post(ticker);
        requestOpen();
    }

    @Override protected void onPause() {
        super.onPause();
        resumed = false;
        orientationListener.disable();
        power.removeThermalStatusListener(thermalListener);
        main.removeCallbacks(ticker);
        camera.close();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        camera.shutdown();
    }

    private void hideBars() {
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.systemBars());
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    // ---- Opening the camera once its surface is ready ----

    private void requestOpen() {
        if (!resumed || !permitted || lens == null) return;
        openPending = true;
        boolean log = settings.mode == Mode.LOG;
        logView.setVisibility(log ? View.VISIBLE : View.GONE);
        cameraView.setVisibility(log ? View.GONE : View.VISIBLE);
        float aspect;
        if (log) {
            aspect = 16f / 9f;
            logView.getHolder().setFixedSize(1920, 1080);
        } else {
            wantedPreview = CameraController.previewSizeFor(lens, settings.mode);
            aspect = (float) wantedPreview.getWidth() / wantedPreview.getHeight();
            cameraView.getHolder().setFixedSize(wantedPreview.getWidth(), wantedPreview.getHeight());
        }
        fitPreview(aspect);
        tryOpen(-1, -1);
    }

    private void tryOpen(int w, int h) {
        if (!openPending || !resumed) return;
        if (settings.mode == Mode.LOG) {
            if (!logSurfaceReady) return;
            openPending = false;
            camera.open(lens, logView.getHolder().getSurface());
        } else {
            if (!cameraSurfaceReady) return;
            if (w > 0 && (w != wantedPreview.getWidth() || h != wantedPreview.getHeight())) return;
            Surface s = cameraView.getHolder().getSurface();
            Size frame = new Size(cameraView.getHolder().getSurfaceFrame().width(),
                    cameraView.getHolder().getSurfaceFrame().height());
            if (!frame.equals(wantedPreview)) return;
            openPending = false;
            camera.open(lens, s);
        }
    }

    private SurfaceHolder.Callback surfaceCallback(boolean log) {
        return new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) { }
            @Override public void surfaceChanged(SurfaceHolder h, int format, int w, int ht) {
                if (log) logSurfaceReady = true;
                else cameraSurfaceReady = true;
                tryOpen(w, ht);
            }
            @Override public void surfaceDestroyed(SurfaceHolder h) {
                if (log) logSurfaceReady = false;
                else cameraSurfaceReady = false;
            }
        };
    }

    private void fitPreview(float aspect) {
        previewBox.post(() -> {
            int bw = previewBox.getWidth(), bh = previewBox.getHeight();
            if (bw == 0 || bh == 0) return;
            int w = bw, h = Math.round(bw / aspect);
            if (h > bh) {
                h = bh;
                w = Math.round(bh * aspect);
            }
            for (View v : new View[] {cameraView, logView}) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
                lp.width = w;
                lp.height = h;
                lp.gravity = Gravity.CENTER;
                v.setLayoutParams(lp);
            }
        });
    }

    // ---- Interface ----

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView label(int size, int color, boolean mono) {
        TextView t = new TextView(this);
        t.setTextSize(size);
        t.setTextColor(color);
        if (mono) t.setTypeface(Typeface.MONOSPACE);
        return t;
    }

    private GradientDrawable pill(int fill, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(18));
        d.setColor(fill);
        if (stroke != 0) d.setStroke(dp(1), stroke);
        return d;
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        previewBox = new FrameLayout(this);
        cameraView = new SurfaceView(this);
        logView = new SurfaceView(this);
        cameraView.getHolder().addCallback(surfaceCallback(false));
        logView.getHolder().addCallback(surfaceCallback(true));
        previewBox.addView(cameraView, new FrameLayout.LayoutParams(1, 1, Gravity.CENTER));
        previewBox.addView(logView, new FrameLayout.LayoutParams(1, 1, Gravity.CENTER));
        logView.setVisibility(View.GONE);
        root.addView(previewBox, new FrameLayout.LayoutParams(-1, -1));

        pinch = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                if (settings.mode == Mode.LOG || lens == null) return true;
                Range<Float> r = lens.chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
                float z = settings.zoom * d.getScaleFactor();
                settings.zoom = r == null ? 1f : r.clamp(z);
                camera.applyChanges();
                showNotice(String.format(Locale.US, "%.1fx", settings.zoom));
                return true;
            }
        });
        previewBox.setOnTouchListener((v, e) -> {
            pinch.onTouchEvent(e);
            if (e.getActionMasked() == MotionEvent.ACTION_UP && !pinch.isInProgress()) tapFocus(e);
            return true;
        });

        status = label(11, TEXT, true);
        status.setPadding(dp(10), dp(4), dp(10), dp(4));
        status.setBackground(pill(PANEL, 0));
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        sp.topMargin = dp(8);
        root.addView(status, sp);

        notice = label(13, TEXT, false);
        notice.setPadding(dp(14), dp(8), dp(14), dp(8));
        notice.setBackground(pill(PANEL, 0));
        notice.setVisibility(View.GONE);
        FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        root.addView(notice, np);

        lensRail = new LinearLayout(this);
        lensRail.setOrientation(LinearLayout.VERTICAL);
        lensRail.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -1, Gravity.START);
        lp.leftMargin = dp(14);
        root.addView(lensRail, lp);

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.CENTER);
        modeRail = new LinearLayout(this);
        modeRail.setOrientation(LinearLayout.VERTICAL);
        modeRail.setGravity(Gravity.CENTER);
        right.addView(modeRail);
        shutter = new View(this);
        shutter.setContentDescription("Shutter");
        shutter.setOnClickListener(v -> onShutter());
        LinearLayout.LayoutParams shp = new LinearLayout.LayoutParams(dp(72), dp(72));
        shp.topMargin = dp(10);
        shp.bottomMargin = dp(10);
        right.addView(shutter, shp);
        TextView more = railButton("SET");
        more.setOnClickListener(v -> openSettings());
        right.addView(more);
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(-2, -1, Gravity.END);
        rp.rightMargin = dp(14);
        root.addView(right, rp);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setGravity(Gravity.CENTER_HORIZONTAL);
        readout = label(11, DIM, true);
        bottom.addView(readout);
        dialBar = new SeekBar(this);
        dialBar.setVisibility(View.GONE);
        dialBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (user) dialMoved(p);
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        bottom.addView(dialBar, new LinearLayout.LayoutParams(dp(360), dp(32)));
        logRow = new LinearLayout(this);
        logRow.setGravity(Gravity.CENTER);
        bottom.addView(logRow);
        dialRow = new LinearLayout(this);
        dialRow.setGravity(Gravity.CENTER);
        bottom.addView(dialRow);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        bp.bottomMargin = dp(8);
        root.addView(bottom, bp);

        setContentView(root);
        root.setOnApplyWindowInsetsListener((v, i) -> i);
        renderShutter();
    }

    private TextView railButton(String text) {
        TextView t = label(12, TEXT, false);
        t.setText(text);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(8), dp(12), dp(8));
        t.setBackground(pill(PANEL, 0));
        return t;
    }

    private TextView chip(String text, boolean on, View.OnClickListener click) {
        TextView t = label(12, on ? Color.BLACK : TEXT, false);
        t.setText(text);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(6), dp(12), dp(6));
        t.setBackground(pill(on ? AMBER : PANEL, 0));
        t.setOnClickListener(click);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.setMargins(dp(3), dp(3), dp(3), dp(3));
        t.setLayoutParams(p);
        return t;
    }

    private void rebuildLensRail() {
        lensRail.removeAllViews();
        for (Lens l : lenses) {
            boolean on = l == lens;
            lensRail.addView(chip(l.label + (l.label.equals("F") ? "" : "x"), on, v -> selectLens(l)));
        }
    }

    private void selectLens(Lens l) {
        if (recording || l == lens) return;
        lens = l;
        settings.zoom = 1f;
        rebuildLensRail();
        requestOpen();
    }

    private void rebuildModeRail() {
        modeRail.removeAllViews();
        for (Mode m : Mode.values()) {
            modeRail.addView(chip(m.name(), m == settings.mode, v -> selectMode(m)));
        }
    }

    private void selectMode(Mode m) {
        if (recording || m == settings.mode) return;
        settings.mode = m;
        activeDial = null;
        rebuildModeRail();
        rebuildControls();
        renderShutter();
        requestOpen();
    }

    private void rebuildControls() {
        dialRow.removeAllViews();
        logRow.removeAllViews();
        dialBar.setVisibility(View.GONE);
        boolean manual = settings.mode == Mode.PRO || settings.mode == Mode.LOG;
        if (manual) {
            for (Dial d : Dial.values()) dialRow.addView(chip(dialText(d), d == activeDial, v -> selectDial(d)));
        }
        if (settings.mode == Mode.LOG) {
            logRow.addView(chip("709 VIEW", settings.assist, v -> {
                settings.assist = !settings.assist;
                rebuildControls();
            }));
            logRow.addView(chip("ZEBRA", settings.zebra, v -> {
                settings.zebra = !settings.zebra;
                rebuildControls();
            }));
            logRow.addView(chip("PEAK", settings.peaking, v -> {
                settings.peaking = !settings.peaking;
                rebuildControls();
            }));
            logRow.addView(chip("AE LOCK", settings.aeLock, v -> {
                settings.aeLock = !settings.aeLock;
                camera.applyChanges();
                rebuildControls();
            }));
        }
        if (manual && activeDial != null) showDial(activeDial);
    }

    private String dialText(Dial d) {
        switch (d) {
            case ISO: return settings.isoAuto ? "ISO A" : "ISO " + settings.iso;
            case SHUTTER: return settings.shutterAuto ? "S A" : shutterText(settings.shutterNs);
            case WB: return settings.wbAuto ? "WB A" : settings.kelvin + "K";
            case FOCUS: return settings.focusAuto ? "MF off" : String.format(Locale.US, "F %.1f", settings.focusDiopters);
            default: return String.format(Locale.US, "EV %+.1f", evValue());
        }
    }

    private float evValue() {
        android.util.Rational step = lens == null ? null : lens.chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
        return settings.evSteps * (step == null ? 0f : step.floatValue());
    }

    private static String shutterText(long ns) {
        if (ns >= 500_000_000L) return String.format(Locale.US, "%.1fs", ns / 1e9);
        return "1/" + Math.round(1e9 / ns);
    }

    private void selectDial(Dial d) {
        if (activeDial == d) {
            // A second tap on the same control hands it back to the camera.
            switch (d) {
                case ISO: settings.isoAuto = !settings.isoAuto; break;
                case SHUTTER: settings.shutterAuto = !settings.shutterAuto; break;
                case WB: settings.wbAuto = !settings.wbAuto; break;
                case FOCUS: settings.focusAuto = !settings.focusAuto; break;
                default: settings.evSteps = 0; break;
            }
            camera.applyChanges();
        }
        activeDial = d;
        rebuildControls();
    }

    private void showDial(Dial d) {
        dialBar.setVisibility(View.VISIBLE);
        dialBar.setMax(1000);
        dialBar.setProgress(dialPosition(d));
    }

    private int dialPosition(Dial d) {
        CameraCharacteristics c = lens.chars;
        switch (d) {
            case ISO: {
                Range<Integer> r = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
                if (r == null) return 0;
                return logPos(settings.iso, r.getLower(), r.getUpper());
            }
            case SHUTTER: {
                Range<Long> r = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
                if (r == null) return 0;
                return logPos(settings.shutterNs, r.getLower(), r.getUpper());
            }
            case WB: return (settings.kelvin - 2000) * 1000 / 8000;
            case FOCUS: {
                Float max = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
                return max == null || max == 0f ? 0 : Math.round(settings.focusDiopters / max * 1000f);
            }
            default: {
                Range<Integer> r = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
                if (r == null || r.getUpper().equals(r.getLower())) return 500;
                return (settings.evSteps - r.getLower()) * 1000 / (r.getUpper() - r.getLower());
            }
        }
    }

    private static int logPos(double v, double lo, double hi) {
        return (int) Math.round(Math.log(v / lo) / Math.log(hi / lo) * 1000.0);
    }

    private static double logVal(int p, double lo, double hi) {
        return lo * Math.pow(hi / lo, p / 1000.0);
    }

    private void dialMoved(int p) {
        Dial d = activeDial;
        if (d == null) return;
        CameraCharacteristics c = lens.chars;
        switch (d) {
            case ISO: {
                Range<Integer> r = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
                if (r == null) return;
                settings.iso = (int) Math.round(logVal(p, r.getLower(), r.getUpper()));
                settings.isoAuto = false;
                break;
            }
            case SHUTTER: {
                Range<Long> r = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
                if (r == null) return;
                long ns = Math.round(logVal(p, r.getLower(), r.getUpper()));
                if (settings.mode == Mode.LOG) ns = Math.min(ns, 1_000_000_000L / settings.logFps);
                settings.shutterNs = ns;
                settings.shutterAuto = false;
                break;
            }
            case WB:
                settings.kelvin = 2000 + p * 8000 / 1000 / 50 * 50;
                settings.wbAuto = false;
                break;
            case FOCUS: {
                Float max = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
                settings.focusDiopters = (max == null ? 0f : max) * p / 1000f;
                settings.focusAuto = false;
                break;
            }
            default: {
                Range<Integer> r = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
                if (r == null) return;
                settings.evSteps = r.getLower() + (r.getUpper() - r.getLower()) * p / 1000;
                break;
            }
        }
        for (int i = 0; i < dialRow.getChildCount(); i++) {
            ((TextView) dialRow.getChildAt(i)).setText(dialText(Dial.values()[i]));
        }
        camera.applyChanges();
    }

    private void tapFocus(MotionEvent e) {
        View v = settings.mode == Mode.LOG ? logView : cameraView;
        if (v.getWidth() == 0) return;
        float x = (e.getX() - v.getLeft()) / v.getWidth();
        float y = (e.getY() - v.getTop()) / v.getHeight();
        if (x < 0 || x > 1 || y < 0 || y > 1) return;
        camera.focusAt(x, y, (float) v.getWidth() / v.getHeight());
    }

    private void renderShutter() {
        boolean video = settings.mode == Mode.VIDEO || settings.mode == Mode.LOG;
        GradientDrawable d = new GradientDrawable();
        d.setShape(recording ? GradientDrawable.RECTANGLE : GradientDrawable.OVAL);
        if (recording) d.setCornerRadius(dp(12));
        d.setColor(video ? RED : TEXT);
        d.setStroke(dp(4), recording ? TEXT : 0x66FFFFFF);
        shutter.setBackground(d);
    }

    private void onShutter() {
        if (settings.mode == Mode.VIDEO || settings.mode == Mode.LOG) {
            camera.toggleRecording();
        } else {
            camera.capturePhoto();
        }
    }

    // ---- Settings sheet ----

    private interface Pick {
        void chose(int index);
    }

    private void choose(String title, String[] items, Pick p) {
        new AlertDialog.Builder(this).setTitle(title).setItems(items, (d, i) -> p.chose(i)).show();
    }

    private void openSettings() {
        if (recording) return;
        Mode m = settings.mode;
        List<String> items = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (m == Mode.LOG) {
            items.add("Log resolution: " + settings.logHeight + "p");
            actions.add(() -> choose("Log resolution", new String[] {"4K", "1080p"}, i -> {
                settings.logHeight = i == 0 ? 2160 : 1080;
                requestOpen();
            }));
            items.add("Log frame rate: " + settings.logFps);
            actions.add(() -> choose("Log frame rate", new String[] {"24", "25", "30", "60"}, i -> {
                settings.logFps = new int[] {24, 25, 30, 60}[i];
                requestOpen();
            }));
            items.add("Log bitrate: " + settings.logMbps + " Mb/s");
            actions.add(() -> choose("Log bitrate", new String[] {"100 Mb/s", "150 Mb/s", "200 Mb/s", "300 Mb/s"}, i ->
                    settings.logMbps = new int[] {100, 150, 200, 300}[i]));
            items.add(String.format(Locale.US, "Highlight headroom: %.1f stops", settings.headroomStops));
            actions.add(() -> choose("Stops above mid grey at sensor clip", new String[] {"2.5", "3.0", "3.5", "4.0", "4.5", "5.0"}, i ->
                    settings.headroomStops = 2.5 + i * 0.5));
        }
        if (m == Mode.VIDEO) {
            items.add("Resolution: " + settings.videoHeight + "p");
            actions.add(() -> choose("Resolution", new String[] {"8K", "4K", "1080p"}, i -> {
                settings.videoHeight = new int[] {4320, 2160, 1080}[i];
                requestOpen();
            }));
            items.add("Frame rate: " + settings.videoFps);
            actions.add(() -> choose("Frame rate", new String[] {"24", "30", "60"}, i -> {
                settings.videoFps = new int[] {24, 30, 60}[i];
                requestOpen();
            }));
            items.add("Bitrate: " + settings.videoMbps + " Mb/s");
            actions.add(() -> choose("Bitrate", new String[] {"30 Mb/s", "50 Mb/s", "80 Mb/s", "120 Mb/s"}, i ->
                    settings.videoMbps = new int[] {30, 50, 80, 120}[i]));
            items.add("HLG 10-bit: " + (settings.hlg ? "on" : "off"));
            actions.add(() -> {
                if (!CameraController.hlgSupported(lens)) {
                    toast("This lens does not offer HLG");
                    return;
                }
                settings.hlg = !settings.hlg;
                requestOpen();
            });
            items.add("Stabilisation: " + (settings.stabilise ? "on" : "off"));
            actions.add(() -> {
                settings.stabilise = !settings.stabilise;
                camera.applyChanges();
            });
        }
        if (m == Mode.PHOTO || m == Mode.PRO) {
            String[] flash = {"Off", "Auto", "On", "Torch"};
            items.add("Flash: " + flash[settings.flash]);
            actions.add(() -> choose("Flash", flash, i -> {
                settings.flash = i;
                camera.applyChanges();
            }));
        }
        if (m == Mode.PRO) {
            items.add("Save RAW (DNG): " + (settings.saveDng ? "on" : "off"));
            actions.add(() -> {
                if (!lens.hasRaw) {
                    toast("This lens does not offer RAW");
                    return;
                }
                settings.saveDng = !settings.saveDng;
                requestOpen();
            });
        }
        items.add("Device report");
        actions.add(this::showReport);

        new AlertDialog.Builder(this).setTitle("Settings")
                .setItems(items.toArray(new String[0]), (d, i) -> actions.get(i).run())
                .show();
    }

    private void showReport() {
        String report = DeviceReport.build(lenses);
        TextView t = label(10, TEXT, true);
        t.setText(report);
        t.setTextIsSelectable(true);
        t.setPadding(dp(16), dp(8), dp(16), dp(8));
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(t);
        new AlertDialog.Builder(this).setTitle("Device report").setView(sv)
                .setPositiveButton("Copy", (d, i) -> {
                    ClipboardManager cm = getSystemService(ClipboardManager.class);
                    cm.setPrimaryClip(ClipData.newPlainText("TrueLog device report", report));
                    toast("Copied");
                })
                .setNeutralButton("Share", (d, i) -> {
                    Intent s = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report);
                    startActivity(Intent.createChooser(s, "Share report"));
                })
                .setNegativeButton("Close", null).show();
    }

    // ---- Status ----

    private void renderStatus() {
        StringBuilder b = new StringBuilder();
        if (recording) {
            long s = (SystemClock.elapsedRealtime() - recordStartedAt) / 1000;
            b.append(String.format(Locale.US, "REC %02d:%02d  ", s / 60, s % 60));
        }
        b.append(summary);
        if (!statsLine.isEmpty()) b.append("  ").append(statsLine);
        try {
            StatFs fs = new StatFs(Environment.getExternalStorageDirectory().getPath());
            b.append(String.format(Locale.US, "  %.0fGB free", fs.getAvailableBytes() / 1e9));
        } catch (RuntimeException ignored) {
            // Free space is a nicety and must never stop the status line.
        }
        if (!thermal.isEmpty()) b.append("  ").append(thermal);
        status.setText(b.toString());
        status.setTextColor(recording ? RED : TEXT);
    }

    private void onThermal(int s) {
        thermal = s >= PowerManager.THERMAL_STATUS_SEVERE ? "HOT"
                : s >= PowerManager.THERMAL_STATUS_MODERATE ? "WARM" : "";
    }

    private void showNotice(String text) {
        notice.setText(text);
        notice.setVisibility(View.VISIBLE);
        main.removeCallbacks(hideNotice);
        main.postDelayed(hideNotice, 2800);
    }

    private final Runnable hideNotice = () -> notice.setVisibility(View.GONE);

    private void toast(String text) {
        showNotice(text);
    }

    // ---- Camera callbacks, always moved to the main thread ----

    @Override public void onCameraReady(String text) {
        main.post(() -> {
            summary = text;
            if (settings.mode != Mode.LOG) statsLine = "";
            renderStatus();
        });
    }

    @Override public void onCameraError(String message) {
        android.util.Log.e("TrueLog", message);
        main.post(() -> {
            showNotice(message);
            summary = message;
            renderStatus();
        });
    }

    @Override public void onSaved(Uri uri, String what) {
        android.util.Log.i("TrueLog", what + " " + uri);
        main.post(() -> showNotice(what));
    }

    @Override public void onRecordingChanged(boolean r) {
        main.post(() -> {
            recording = r;
            if (r) recordStartedAt = SystemClock.elapsedRealtime();
            renderShutter();
            renderStatus();
        });
    }

    @Override public void onReadout(int iso, long exposureNs, float focus) {
        main.post(() -> readout.setText(String.format(Locale.US, "ISO %d   %s   %s",
                iso, shutterText(exposureNs),
                focus > 0.01f ? String.format(Locale.US, "%.2fm", 1f / focus) : "focus inf")));
    }

    @Override public void onLogStats(LogEngine.Stats s) {
        android.util.Log.i("TrueLog", "log frames=" + s.frames + " ms=" + s.msPerFrame + " cct=" + s.cct
                + " shadingMissing=" + s.shadingMissing);
        main.post(() -> statsLine = String.format(Locale.US, "%.0fms/f  %.0fK%s%s",
                s.msPerFrame, s.cct,
                s.recording ? "  drop " + s.dropped : "",
                s.shadingMissing ? "  NO SHADING MAP" : ""));
    }

    @Override public void onNotice(String message) {
        main.post(() -> showNotice(message));
    }

    @Override public int deviceOrientation() {
        return orientationDegrees;
    }
}
