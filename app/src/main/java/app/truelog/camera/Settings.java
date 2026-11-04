package app.truelog.camera;

/** Everything the user can change. Read on the camera thread, written on the UI thread. */
final class Settings {
    enum Mode { PHOTO, VIDEO, PRO, LOG }

    volatile Mode mode = Mode.PHOTO;

    // Exposure and focus. A value of "auto" hands that control back to the camera.
    volatile boolean isoAuto = true, shutterAuto = true, wbAuto = true, focusAuto = true;
    volatile int iso = 100;
    volatile long shutterNs = 10_000_000L;
    volatile int kelvin = 5600;
    volatile float focusDiopters = 0f;
    volatile int evSteps = 0;
    volatile boolean aeLock;
    volatile float zoom = 1f;

    // Stills
    volatile int flash;          // 0 off, 1 auto, 2 on, 3 torch
    volatile boolean saveDng;

    // Standard video
    volatile int videoHeight = 2160, videoFps = 30, videoMbps = 80;
    volatile boolean hlg;
    volatile boolean h264;
    volatile boolean stabilise = true;

    // Log video
    volatile int logHeight = 2160, logFps = 30, logMbps = 200;
    volatile double headroomStops = 3.5;
    volatile boolean assist = true, zebra, peaking;
}
