package app.truelog.camera.gl;

/** GLSL sources for the RAW to S-Log3 pipeline. */
final class Shaders {
    private Shaders() {}

    /** Black level, lens shading and white balance, applied to each photosite before demosaic. */
    static final String LINEARIZE = """
        #version 310 es
        precision highp float;
        precision highp int;
        precision highp sampler2D;
        precision highp usampler2D;
        layout(local_size_x = 64, local_size_y = 64) in;
        layout(binding = 0) uniform usampler2D uRaw;
        layout(binding = 1) uniform sampler2D uShade;
        layout(r32f, binding = 0) writeonly uniform highp image2D uOut;
        uniform ivec2 uCrop;
        uniform ivec2 uRawSize;
        uniform vec4 uBlack;
        uniform float uWhite;
        uniform vec4 uGain;
        uniform ivec4 uShadeChannel;
        uniform float uShadeOn;
        void main() {
            ivec2 p = ivec2(gl_GlobalInvocationID.xy);
            ivec2 size = imageSize(uOut);
            if (p.x >= size.x || p.y >= size.y) return;
            int pos = (p.x & 1) + ((p.y & 1) << 1);
            float v = float(texelFetch(uRaw, p, 0).r);
            float black = uBlack[pos];
            v = (v - black) / (uWhite - black);
            if (uShadeOn > 0.5) {
                vec2 uv = (vec2(p + uCrop) + 0.5) / vec2(uRawSize);
                v *= textureLod(uShade, uv, 0.0)[uShadeChannel[pos]];
            }
            v *= uGain[pos];
            imageStore(uOut, p, vec4(clamp(v, -0.02, 1.0), 0.0, 0.0, 1.0));
        }
        """;

    /** Malvar-He-Cutler demosaic, sensor to S-Gamut3.Cine, then the S-Log3 curve. */
    static final String DEMOSAIC_LOG = """
        #version 310 es
        precision highp float;
        precision highp int;
        precision highp sampler2D;
        layout(local_size_x = 64, local_size_y = 64) in;
        layout(binding = 0) uniform sampler2D uLin;
        layout(rgba16f, binding = 0) writeonly uniform highp image2D uOut;
        uniform ivec2 uRedPos;
        uniform mat3 uCamToGamut;
        uniform float uExposure;
        ivec2 gSize;
        ivec2 gP;
        float px(int dx, int dy) {
            ivec2 q = gP + ivec2(dx, dy);
            q = abs(q);
            ivec2 m = gSize - ivec2(1);
            q = mix(q, 2 * m - q, greaterThan(q, m));
            return texelFetch(uLin, q, 0).r;
        }
        vec3 slog3(vec3 x) {
            vec3 hi = (420.0 + log(max(x + 0.01, vec3(1e-6)) / 0.19) / log(10.0) * 261.5) / 1023.0;
            vec3 lo = (x * (171.2102946929 - 95.0) / 0.01125 + 95.0) / 1023.0;
            return mix(lo, hi, step(vec3(0.01125), x));
        }
        void main() {
            gP = ivec2(gl_GlobalInvocationID.xy);
            gSize = imageSize(uOut);
            if (gP.x >= gSize.x || gP.y >= gSize.y) return;
            float c = px(0, 0);
            float n1 = px(0, -1), s1 = px(0, 1), w1 = px(-1, 0), e1 = px(1, 0);
            float n2 = px(0, -2), s2 = px(0, 2), w2 = px(-2, 0), e2 = px(2, 0);
            float diag = px(-1, -1) + px(1, -1) + px(-1, 1) + px(1, 1);
            ivec2 q = (gP ^ uRedPos) & 1;
            vec3 rgb;
            if (q.x == q.y) {
                float g = (4.0 * c + 2.0 * (n1 + s1 + w1 + e1) - (n2 + s2 + w2 + e2)) / 8.0;
                float o = (6.0 * c + 2.0 * diag - 1.5 * (n2 + s2 + w2 + e2)) / 8.0;
                rgb = q.x == 0 ? vec3(c, g, o) : vec3(o, g, c);
            } else {
                float h = (5.0 * c + 4.0 * (w1 + e1) - (w2 + e2) - diag + 0.5 * (n2 + s2)) / 8.0;
                float v = (5.0 * c + 4.0 * (n1 + s1) - (n2 + s2) - diag + 0.5 * (w2 + e2)) / 8.0;
                rgb = q.x == 1 ? vec3(h, c, v) : vec3(v, c, h);
            }
            rgb = min(rgb, vec3(1.0));
            vec3 scene = uCamToGamut * rgb * uExposure;
            vec3 code = slog3(scene);
            vec3 signal = (code * 1023.0 - 64.0) / 876.0;
            imageStore(uOut, gP, vec4(signal, 1.0));
        }
        """;

    /** BT.2020 narrow-range 4:2:0, written as P010 into two storage buffers. */
    static final String P010 = """
        #version 310 es
        precision highp float;
        precision highp int;
        precision highp sampler2D;
        layout(local_size_x = 32, local_size_y = 32) in;
        layout(binding = 0) uniform sampler2D uLog;
        layout(std430, binding = 0) writeonly buffer YPlane { uint y[]; };
        layout(std430, binding = 1) writeonly buffer UvPlane { uint uv[]; };
        uniform ivec2 uOutSize;
        const vec3 W = vec3(0.2627, 0.6780, 0.0593);
        vec3 at(ivec2 p) {
            return textureLod(uLog, (vec2(p) + 0.5) / vec2(uOutSize), 0.0).rgb;
        }
        uint code(float v) {
            return uint(clamp(floor(v + 0.5), 4.0, 1019.0)) << 6;
        }
        void main() {
            ivec2 b = ivec2(gl_GlobalInvocationID.xy);
            ivec2 grid = uOutSize / 2;
            if (b.x >= grid.x || b.y >= grid.y) return;
            ivec2 o = b * 2;
            vec3 p00 = at(o), p10 = at(o + ivec2(1, 0));
            vec3 p01 = at(o + ivec2(0, 1)), p11 = at(o + ivec2(1, 1));
            float y00 = dot(p00, W), y10 = dot(p10, W), y01 = dot(p01, W), y11 = dot(p11, W);
            int stride = grid.x;
            y[o.y * stride + b.x] = code(64.0 + 876.0 * y00) | (code(64.0 + 876.0 * y10) << 16);
            y[(o.y + 1) * stride + b.x] = code(64.0 + 876.0 * y01) | (code(64.0 + 876.0 * y11) << 16);
            vec3 m = (p00 + p10 + p01 + p11) * 0.25;
            float ym = dot(m, W);
            float cb = (m.b - ym) / 1.8814;
            float cr = (m.r - ym) / 1.4746;
            uv[b.y * stride + b.x] = code(512.0 + 896.0 * cb) | (code(512.0 + 896.0 * cr) << 16);
        }
        """;

    static final String QUAD_VERT = """
        #version 310 es
        precision highp float;
        uniform vec2 uFlip;
        out vec2 vUv;
        void main() {
            vec2 p = vec2(float((gl_VertexID & 1) * 2) - 1.0, float((gl_VertexID & 2)) - 1.0);
            vUv = p * 0.5 + 0.5;
            vUv.y = 1.0 - vUv.y;
            vUv = mix(vUv, 1.0 - vUv, uFlip);
            gl_Position = vec4(p, 0.0, 1.0);
        }
        """;

    /** Straight copy, used when the encoder only accepts surface input. */
    static final String ENCODER_FRAG = """
        #version 310 es
        precision highp float;
        precision highp sampler2D;
        layout(binding = 0) uniform sampler2D uLog;
        in vec2 vUv;
        out vec4 frag;
        void main() { frag = vec4(texture(uLog, vUv).rgb, 1.0); }
        """;

    /** Monitor view: flat Log, or a Rec.709 view assist, with exposure and focus aids. */
    static final String PREVIEW_FRAG = """
        #version 310 es
        precision highp float;
        precision highp sampler2D;
        layout(binding = 0) uniform sampler2D uLog;
        uniform mat3 uGamutToRec709;
        uniform int uAssist;
        uniform int uZebra;
        uniform int uPeaking;
        uniform float uClipSignal;
        in vec2 vUv;
        out vec4 frag;
        vec3 decode(vec3 signal) {
            vec3 cv = signal * 876.0 + 64.0;
            vec3 hi = pow(vec3(10.0), (cv - 420.0) / 261.5) * 0.19 - 0.01;
            vec3 lo = (cv - 95.0) * 0.01125 / (171.2102946929 - 95.0);
            return mix(lo, hi, step(vec3(171.2102946929), cv));
        }
        vec3 srgb(vec3 x) {
            x = clamp(x, 0.0, 1.0);
            return mix(12.92 * x, 1.055 * pow(x, vec3(1.0 / 2.4)) - 0.055, step(vec3(0.0031308), x));
        }
        void main() {
            vec3 signal = texture(uLog, vUv).rgb;
            vec3 color;
            if (uAssist == 1) {
                vec3 lin = max(uGamutToRec709 * decode(signal), vec3(0.0));
                color = srgb(lin * (1.0 + lin / 16.0) / (1.0 + lin));
            } else {
                color = clamp(signal, 0.0, 1.0);
            }
            if (uPeaking == 1) {
                vec2 t = 1.0 / vec2(textureSize(uLog, 0));
                vec3 lum = vec3(0.2627, 0.6780, 0.0593);
                float c = dot(signal, lum);
                float e = abs(4.0 * c
                    - dot(texture(uLog, vUv + vec2(t.x, 0.0)).rgb, lum)
                    - dot(texture(uLog, vUv - vec2(t.x, 0.0)).rgb, lum)
                    - dot(texture(uLog, vUv + vec2(0.0, t.y)).rgb, lum)
                    - dot(texture(uLog, vUv - vec2(0.0, t.y)).rgb, lum));
                if (e > 0.10) color = vec3(0.1, 1.0, 0.45);
            }
            if (uZebra == 1 && max(signal.r, max(signal.g, signal.b)) >= uClipSignal) {
                float s = mod(gl_FragCoord.x + gl_FragCoord.y, 24.0);
                if (s < 12.0) color = vec3(1.0, 0.0, 0.25);
            }
            frag = vec4(color, 1.0);
        }
        """;
}
