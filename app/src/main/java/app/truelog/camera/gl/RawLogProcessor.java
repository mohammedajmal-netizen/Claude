package app.truelog.camera.gl;

import android.opengl.GLES20;
import android.opengl.GLES30;
import android.opengl.GLES31;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Turns one RAW_SENSOR frame into S-Log3. GL-context bound and camera independent, so the same
 * class runs against real frames on the phone and synthetic frames in automated tests.
 */
public final class RawLogProcessor {

    public static final class Config {
        public int rawWidth, rawHeight;   // full sensor frame
        public int cropX, cropY;          // must be even so the Bayer pattern keeps its phase
        public int cropWidth, cropHeight;
        public int outWidth, outHeight;   // equal to the crop, or half of it for 1080p from 4K
        public int cfa;                   // CameraCharacteristics CFA arrangement: RGGB=0 GRBG=1 GBRG=2 BGGR=3
    }

    /** Per-frame values. Arrays are indexed by Bayer position: (0,0) (1,0) (0,1) (1,1). */
    public static final class Params {
        public float[] black = {0, 0, 0, 0};
        public float white = 1023;
        public float[] gainsRgb = {1, 1, 1};
        public float[] camToGamut = {1, 0, 0, 0, 1, 0, 0, 0, 1}; // row-major
        public float exposure = 2.0f;                            // scene value at sensor clip
    }

    /** Monitor options. */
    public static final class View {
        public boolean assist = true, zebra, peaking, rotate180;
        public float[] gamutToRec709 = {1, 0, 0, 0, 1, 0, 0, 0, 1}; // row-major
        public float clipSignal = 2f;
    }

    private static final int[][] COLOR_AT_POS = {
        {0, 1, 1, 2}, {1, 0, 2, 1}, {1, 2, 0, 1}, {2, 1, 1, 0}
    };
    // Android lens shading order is R, G-even, G-odd, B.
    private static final int[][] SHADE_AT_POS = {
        {0, 1, 2, 3}, {1, 0, 3, 2}, {1, 3, 0, 2}, {3, 1, 2, 0}
    };
    private static final int[][] RED_POS = {{0, 0}, {1, 0}, {0, 1}, {1, 1}};

    private final Config cfg;
    private final int rawTex, linTex, logTex, shadeTex;
    private final int linearizeProg, demosaicProg, p010Prog, previewProg, encoderProg;
    private final int vao;
    private final int ySsbo, uvSsbo, ySize, uvSize;
    private boolean shadeOn;

    public RawLogProcessor(Config c) {
        if ((c.cropX & 1) != 0 || (c.cropY & 1) != 0) throw new IllegalArgumentException("crop must be even");
        cfg = c;
        rawTex = texture(GLES30.GL_R16UI, c.cropWidth, c.cropHeight, GLES20.GL_NEAREST);
        linTex = texture(GLES30.GL_R32F, c.cropWidth, c.cropHeight, GLES20.GL_NEAREST);
        logTex = texture(GLES30.GL_RGBA16F, c.cropWidth, c.cropHeight, GLES20.GL_LINEAR);
        shadeTex = texture(GLES30.GL_RGBA16F, 2, 2, GLES20.GL_LINEAR);
        linearizeProg = compute(Shaders.LINEARIZE);
        demosaicProg = compute(Shaders.DEMOSAIC_LOG);
        p010Prog = compute(Shaders.P010);
        previewProg = graphics(Shaders.QUAD_VERT, Shaders.PREVIEW_FRAG);
        encoderProg = graphics(Shaders.QUAD_VERT, Shaders.ENCODER_FRAG);

        int[] ids = new int[1];
        GLES30.glGenVertexArrays(1, ids, 0);
        vao = ids[0];

        ySize = c.outWidth * c.outHeight * 2;
        uvSize = c.outWidth * c.outHeight;
        ySsbo = ssbo(ySize);
        uvSsbo = ssbo(uvSize);
        check("init");
    }

    private static int texture(int internal, int w, int h, int filter) {
        int[] id = new int[1];
        GLES20.glGenTextures(1, id, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id[0]);
        GLES30.glTexStorage2D(GLES20.GL_TEXTURE_2D, 1, internal, w, h);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        return id[0];
    }

    private static int ssbo(int bytes) {
        int[] id = new int[1];
        GLES20.glGenBuffers(1, id, 0);
        GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, id[0]);
        GLES20.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, bytes, null, GLES30.GL_STREAM_READ);
        return id[0];
    }

    private static int shader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new IllegalStateException("Shader compile: " + GLES20.glGetShaderInfoLog(s));
        return s;
    }

    private static int link(int... shaders) {
        int p = GLES20.glCreateProgram();
        for (int s : shaders) GLES20.glAttachShader(p, s);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new IllegalStateException("Program link: " + GLES20.glGetProgramInfoLog(p));
        for (int s : shaders) GLES20.glDeleteShader(s);
        return p;
    }

    private static int compute(String src) {
        return link(shader(GLES31.GL_COMPUTE_SHADER, src));
    }

    private static int graphics(String vert, String frag) {
        return link(shader(GLES20.GL_VERTEX_SHADER, vert), shader(GLES20.GL_FRAGMENT_SHADER, frag));
    }

    private static void check(String where) {
        int e = GLES20.glGetError();
        if (e != GLES20.GL_NO_ERROR) throw new IllegalStateException("GL error 0x" + Integer.toHexString(e) + " at " + where);
    }

    /** Lens shading map as cols*rows*4 floats, or null to disable correction. */
    public void setLensShading(float[] gains, int cols, int rows) {
        shadeOn = gains != null;
        if (gains == null) return;
        FloatBuffer b = ByteBuffer.allocateDirect(gains.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        b.put(gains).position(0);
        GLES20.glDeleteTextures(1, new int[] {shadeTexCurrent()}, 0);
        shadeTexOverride = texture(GLES30.GL_RGBA16F, cols, rows, GLES20.GL_LINEAR);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, shadeTexOverride);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4);
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, 0);
        GLES30.glPixelStorei(GLES30.GL_UNPACK_SKIP_ROWS, 0);
        GLES30.glPixelStorei(GLES30.GL_UNPACK_SKIP_PIXELS, 0);
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, cols, rows, GLES20.GL_RGBA, GLES20.GL_FLOAT, b);
        check("shading");
    }

    private int shadeTexOverride;

    private int shadeTexCurrent() {
        return shadeTexOverride != 0 ? shadeTexOverride : shadeTex;
    }

    /** Runs the full RAW to Log pass for one frame. Plane data is 16-bit little endian. */
    public void process(ByteBuffer raw, int rowStrideBytes, Params p) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, rawTex);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 2);
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, rowStrideBytes / 2);
        GLES30.glPixelStorei(GLES30.GL_UNPACK_SKIP_PIXELS, cfg.cropX);
        GLES30.glPixelStorei(GLES30.GL_UNPACK_SKIP_ROWS, cfg.cropY);
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, cfg.cropWidth, cfg.cropHeight,
                GLES30.GL_RED_INTEGER, GLES20.GL_UNSIGNED_SHORT, raw);
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, 0);
        GLES30.glPixelStorei(GLES30.GL_UNPACK_SKIP_PIXELS, 0);
        GLES30.glPixelStorei(GLES30.GL_UNPACK_SKIP_ROWS, 0);
        check("upload");

        int[] colors = COLOR_AT_POS[cfg.cfa];
        float[] gain = new float[4];
        for (int i = 0; i < 4; i++) gain[i] = p.gainsRgb[colors[i]];
        int[] shade = SHADE_AT_POS[cfg.cfa];

        GLES20.glUseProgram(linearizeProg);
        GLES20.glUniform2i(loc(linearizeProg, "uCrop"), cfg.cropX, cfg.cropY);
        GLES20.glUniform2i(loc(linearizeProg, "uRawSize"), cfg.rawWidth, cfg.rawHeight);
        GLES20.glUniform4fv(loc(linearizeProg, "uBlack"), 1, p.black, 0);
        GLES20.glUniform1f(loc(linearizeProg, "uWhite"), p.white);
        GLES20.glUniform4fv(loc(linearizeProg, "uGain"), 1, gain, 0);
        GLES20.glUniform4iv(loc(linearizeProg, "uShadeChannel"), 1, shade, 0);
        GLES20.glUniform1f(loc(linearizeProg, "uShadeOn"), shadeOn ? 1f : 0f);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, shadeTexCurrent());
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES31.glBindImageTexture(0, linTex, 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_R32F);
        dispatch(cfg.cropWidth, cfg.cropHeight);
        GLES31.glMemoryBarrier(GLES31.GL_TEXTURE_FETCH_BARRIER_BIT | GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

        GLES20.glUseProgram(demosaicProg);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, linTex);
        int[] red = RED_POS[cfg.cfa];
        GLES20.glUniform2i(loc(demosaicProg, "uRedPos"), red[0], red[1]);
        GLES20.glUniformMatrix3fv(loc(demosaicProg, "uCamToGamut"), 1, true, p.camToGamut, 0);
        GLES20.glUniform1f(loc(demosaicProg, "uExposure"), p.exposure);
        GLES31.glBindImageTexture(0, logTex, 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA16F);
        dispatch(cfg.cropWidth, cfg.cropHeight);
        GLES31.glMemoryBarrier(GLES31.GL_TEXTURE_FETCH_BARRIER_BIT);
        check("process");
    }

    private static int loc(int program, String name) {
        return GLES20.glGetUniformLocation(program, name);
    }

    /** Group size matches local_size in the shaders. 8x8 sits inside the limit the spec guarantees. */
    private static void dispatch(int w, int h) {
        int group = 8;
        GLES31.glDispatchCompute((w + group - 1) / group, (h + group - 1) / group, 1);
    }

    /** Converts the current Log image to P010 and hands the mapped planes to the sink. */
    public void readP010(PlaneSink sink) {
        GLES20.glUseProgram(p010Prog);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, logTex);
        GLES20.glUniform2i(loc(p010Prog, "uOutSize"), cfg.outWidth, cfg.outHeight);
        GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, ySsbo);
        GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, uvSsbo);
        dispatch(cfg.outWidth / 2, cfg.outHeight / 2);
        GLES31.glMemoryBarrier(GLES31.GL_BUFFER_UPDATE_BARRIER_BIT);
        GLES20.glFinish();

        GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, ySsbo);
        ByteBuffer y = (ByteBuffer) GLES30.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER, 0, ySize, GLES30.GL_MAP_READ_BIT);
        GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, uvSsbo);
        ByteBuffer uv = (ByteBuffer) GLES30.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER, 0, uvSize, GLES30.GL_MAP_READ_BIT);
        try {
            if (y == null || uv == null) throw new IllegalStateException("Could not map P010 buffers");
            y.order(ByteOrder.LITTLE_ENDIAN);
            uv.order(ByteOrder.LITTLE_ENDIAN);
            sink.accept(y, uv, cfg.outWidth, cfg.outHeight);
        } finally {
            GLES30.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER);
            GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, ySsbo);
            GLES30.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER);
        }
        check("p010");
    }

    public interface PlaneSink {
        void accept(ByteBuffer y, ByteBuffer uv, int width, int height);
    }

    public void drawPreview(int surfaceW, int surfaceH, View v) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        float aspect = (float) cfg.outWidth / cfg.outHeight;
        int w = surfaceW, h = Math.round(w / aspect);
        if (h > surfaceH) { h = surfaceH; w = Math.round(h * aspect); }
        GLES20.glViewport((surfaceW - w) / 2, (surfaceH - h) / 2, w, h);
        GLES20.glUseProgram(previewProg);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, logTex);
        GLES20.glUniform2f(loc(previewProg, "uFlip"), v.rotate180 ? 1f : 0f, v.rotate180 ? 1f : 0f);
        GLES20.glUniformMatrix3fv(loc(previewProg, "uGamutToRec709"), 1, true, v.gamutToRec709, 0);
        GLES20.glUniform1i(loc(previewProg, "uAssist"), v.assist ? 1 : 0);
        GLES20.glUniform1i(loc(previewProg, "uZebra"), v.zebra ? 1 : 0);
        GLES20.glUniform1i(loc(previewProg, "uPeaking"), v.peaking ? 1 : 0);
        GLES20.glUniform1f(loc(previewProg, "uClipSignal"), v.clipSignal);
        drawQuad();
    }

    /** Used only when the encoder has no P010 buffer input. */
    public void drawForEncoder(int surfaceW, int surfaceH) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, surfaceW, surfaceH);
        GLES20.glUseProgram(encoderProg);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, logTex);
        GLES20.glUniform2f(loc(encoderProg, "uFlip"), 0f, 0f);
        drawQuad();
    }

    private void drawQuad() {
        GLES30.glBindVertexArray(vao);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES30.glBindVertexArray(0);
        check("draw");
    }

    public void release() {
        GLES20.glDeleteTextures(4, new int[] {rawTex, linTex, logTex, shadeTexCurrent()}, 0);
        GLES20.glDeleteBuffers(2, new int[] {ySsbo, uvSsbo}, 0);
        GLES30.glDeleteVertexArrays(1, new int[] {vao}, 0);
        GLES20.glDeleteProgram(linearizeProg);
        GLES20.glDeleteProgram(demosaicProg);
        GLES20.glDeleteProgram(p010Prog);
        GLES20.glDeleteProgram(previewProg);
        GLES20.glDeleteProgram(encoderProg);
    }
}
