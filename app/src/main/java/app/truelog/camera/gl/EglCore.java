package app.truelog.camera.gl;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES30;

/** Owns one OpenGL ES 3.1 context. Everything here must run on the thread that created it. */
public final class EglCore {
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;

    private final EGLDisplay display;
    private final EGLConfig config;
    private final EGLContext context;
    private final EGLSurface pbuffer;

    public EglCore(boolean tenBitRecordable) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] version = new int[2];
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            throw new IllegalStateException("eglInitialize failed");
        }
        EGLConfig chosen = null;
        if (tenBitRecordable) chosen = pick(10, 2, true);
        if (chosen == null) chosen = pick(8, 8, false);
        if (chosen == null) throw new IllegalStateException("No usable EGL config");
        config = chosen;

        int[] ctxAttrs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE};
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, ctxAttrs, 0);
        if (context == EGL14.EGL_NO_CONTEXT) throw new IllegalStateException("eglCreateContext failed");

        int[] pbAttrs = {EGL14.EGL_WIDTH, 16, EGL14.EGL_HEIGHT, 16, EGL14.EGL_NONE};
        pbuffer = EGL14.eglCreatePbufferSurface(display, config, pbAttrs, 0);
        makeCurrent(pbuffer);

        int[] major = new int[1], minor = new int[1];
        GLES30.glGetIntegerv(GLES30.GL_MAJOR_VERSION, major, 0);
        GLES30.glGetIntegerv(GLES30.GL_MINOR_VERSION, minor, 0);
        if (major[0] < 3 || (major[0] == 3 && minor[0] < 1)) {
            release();
            throw new IllegalStateException("OpenGL ES 3.1 is required, found " + major[0] + "." + minor[0]);
        }
    }

    private EGLConfig pick(int rgb, int alpha, boolean recordable) {
        int[] attrs = {
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, rgb, EGL14.EGL_GREEN_SIZE, rgb, EGL14.EGL_BLUE_SIZE, rgb,
            EGL14.EGL_ALPHA_SIZE, alpha,
            recordable ? EGL_RECORDABLE_ANDROID : EGL14.EGL_NONE, recordable ? 1 : EGL14.EGL_NONE,
            EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        boolean ok = EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0);
        return ok && count[0] > 0 ? configs[0] : null;
    }

    public EGLSurface createWindow(Object surface) {
        int[] attrs = {EGL14.EGL_NONE};
        EGLSurface s = EGL14.eglCreateWindowSurface(display, config, surface, attrs, 0);
        if (s == EGL14.EGL_NO_SURFACE) throw new IllegalStateException("eglCreateWindowSurface failed");
        return s;
    }

    public void makeCurrent(EGLSurface s) {
        if (!EGL14.eglMakeCurrent(display, s, s, context)) {
            throw new IllegalStateException("eglMakeCurrent failed: " + EGL14.eglGetError());
        }
    }

    public void makeOffscreenCurrent() {
        makeCurrent(pbuffer);
    }

    /** Stops presenting a frame from blocking the processing thread on display refresh. */
    public void disableVsync() {
        EGL14.eglSwapInterval(display, 0);
    }

    public void swap(EGLSurface s) {
        EGL14.eglSwapBuffers(display, s);
    }

    public void setPresentationTime(EGLSurface s, long nanos) {
        EGLExt.eglPresentationTimeANDROID(display, s, nanos);
    }

    public int width(EGLSurface s) {
        int[] v = new int[1];
        EGL14.eglQuerySurface(display, s, EGL14.EGL_WIDTH, v, 0);
        return v[0];
    }

    public int height(EGLSurface s) {
        int[] v = new int[1];
        EGL14.eglQuerySurface(display, s, EGL14.EGL_HEIGHT, v, 0);
        return v[0];
    }

    public void destroy(EGLSurface s) {
        if (s != null && s != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, s);
    }

    public void release() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
        EGL14.eglDestroySurface(display, pbuffer);
        EGL14.eglDestroyContext(display, context);
        EGL14.eglReleaseThread();
        EGL14.eglTerminate(display);
    }
}
