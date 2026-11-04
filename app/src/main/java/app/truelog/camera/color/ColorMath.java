package app.truelog.camera.color;

/**
 * Pure colour maths with no Android dependencies, so it can be unit tested on a desktop JVM.
 * Matrices are row-major double[9].
 *
 * Output encoding is S-Log3 / S-Gamut3.Cine, because its curve and primaries are published and
 * every grading tool ships a matching transform.
 */
public final class ColorMath {
    private ColorMath() {}

    // ---- S-Log3 (Sony "Technical Summary for S-Gamut3.Cine/S-Log3") ----

    /** Scene-linear reflectance (0.18 = mid grey) to S-Log3 code value, normalised to 0..1 of 1023. */
    public static double slog3Encode(double x) {
        if (x >= 0.01125) {
            return (420.0 + Math.log10((x + 0.01) / 0.19) * 261.5) / 1023.0;
        }
        return (x * (171.2102946929 - 95.0) / 0.01125 + 95.0) / 1023.0;
    }

    public static double slog3Decode(double y) {
        double cv = y * 1023.0;
        if (cv >= 171.2102946929) {
            return Math.pow(10.0, (cv - 420.0) / 261.5) * 0.19 - 0.01;
        }
        return (cv - 95.0) * 0.01125 / (171.2102946929 - 95.0);
    }

    /**
     * Narrow-range signal value for a given S-Log3 code value. Sony publishes 18% grey as code 420
     * and 41 IRE, which only agree if the code value is stored directly in a narrow-range file:
     * (420 - 64) / 876 = 40.6%. Encoding this way makes a neutral pixel land on exactly the code
     * value a Sony camera would write, so grading tools treat the file like Sony footage.
     */
    public static double codeToNarrowSignal(double normalisedCode) {
        return (normalisedCode * 1023.0 - 64.0) / 876.0;
    }

    // ---- Matrix helpers ----

    public static double[] mul(double[] a, double[] b) {
        double[] r = new double[9];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                r[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j];
            }
        }
        return r;
    }

    public static double[] mulVec(double[] m, double[] v) {
        return new double[] {
            m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
            m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
            m[6] * v[0] + m[7] * v[1] + m[8] * v[2]
        };
    }

    public static double[] invert(double[] m) {
        double a = m[0], b = m[1], c = m[2], d = m[3], e = m[4], f = m[5], g = m[6], h = m[7], i = m[8];
        double A = e * i - f * h, B = -(d * i - f * g), C = d * h - e * g;
        double det = a * A + b * B + c * C;
        if (Math.abs(det) < 1e-12) throw new IllegalArgumentException("singular matrix");
        double s = 1.0 / det;
        return new double[] {
            A * s, -(b * i - c * h) * s, (b * f - c * e) * s,
            B * s, (a * i - c * g) * s, -(a * f - c * d) * s,
            C * s, -(a * h - b * g) * s, (a * e - b * d) * s
        };
    }

    public static double[] identity() {
        return new double[] {1, 0, 0, 0, 1, 0, 0, 0, 1};
    }

    public static double[] diag(double a, double b, double c) {
        return new double[] {a, 0, 0, 0, b, 0, 0, 0, c};
    }

    public static double[] lerp(double[] a, double[] b, double t) {
        double[] r = new double[a.length];
        for (int i = 0; i < a.length; i++) r[i] = a[i] * t + b[i] * (1.0 - t);
        return r;
    }

    public static double[] xyToXyz(double x, double y) {
        return new double[] {x / y, 1.0, (1.0 - x - y) / y};
    }

    public static double[] xyzToXy(double[] v) {
        double s = v[0] + v[1] + v[2];
        return new double[] {v[0] / s, v[1] / s};
    }

    /** RGB to XYZ for a set of primaries and a white point, all as xy. */
    public static double[] rgbToXyz(double rx, double ry, double gx, double gy, double bx, double by,
                                    double wx, double wy) {
        double[] r = xyToXyz(rx, ry), g = xyToXyz(gx, gy), b = xyToXyz(bx, by);
        double[] p = {r[0], g[0], b[0], r[1], g[1], b[1], r[2], g[2], b[2]};
        double[] s = mulVec(invert(p), xyToXyz(wx, wy));
        return mul(p, diag(s[0], s[1], s[2]));
    }

    // ---- Standard spaces ----

    public static final double[] D50_XY = {0.3457, 0.3585};
    public static final double[] D65_XY = {0.3127, 0.3290};

    public static double[] sGamut3CineToXyzD65() {
        return rgbToXyz(0.766, 0.275, 0.225, 0.800, 0.089, -0.087, D65_XY[0], D65_XY[1]);
    }

    public static double[] rec709ToXyzD65() {
        return rgbToXyz(0.64, 0.33, 0.30, 0.60, 0.15, 0.06, D65_XY[0], D65_XY[1]);
    }

    /** Bradford chromatic adaptation between two white points given as xy. */
    public static double[] bradford(double[] srcXy, double[] dstXy) {
        double[] m = {0.8951, 0.2664, -0.1614, -0.7502, 1.7135, 0.0367, 0.0389, -0.0685, 1.0296};
        double[] s = mulVec(m, xyToXyz(srcXy[0], srcXy[1]));
        double[] d = mulVec(m, xyToXyz(dstXy[0], dstXy[1]));
        return mul(invert(m), mul(diag(d[0] / s[0], d[1] / s[1], d[2] / s[2]), m));
    }

    /** Colour temperature to xy on the Planckian locus (Kim et al. cubic fit, 1667K to 25000K). */
    public static double[] cctToXy(double t) {
        t = Math.max(1667.0, Math.min(25000.0, t));
        double i = 1000.0 / t;
        double x = t <= 4000.0
                ? -0.2661239 * i * i * i - 0.2343589 * i * i + 0.8776956 * i + 0.179910
                : -3.0258469 * i * i * i + 2.1070379 * i * i + 0.2226347 * i + 0.240390;
        double y;
        if (t <= 2222.0) {
            y = -1.1063814 * x * x * x - 1.34811020 * x * x + 2.18555832 * x - 0.20219683;
        } else if (t <= 4000.0) {
            y = -0.9549476 * x * x * x - 1.37418593 * x * x + 2.09137015 * x - 0.16748867;
        } else {
            y = 3.0817580 * x * x * x - 5.87338670 * x * x + 3.75112997 * x - 0.37001483;
        }
        return new double[] {x, y};
    }

    /** McCamy's approximation, good enough to pick the matrix interpolation weight. */
    public static double xyToCct(double[] xy) {
        double n = (xy[0] - 0.3320) / (0.1858 - xy[1]);
        return 449.0 * n * n * n + 3525.0 * n * n + 6823.3 * n + 5520.33;
    }

    /** Illuminant numbers from Android's SENSOR_REFERENCE_ILLUMINANT1 to colour temperature. */
    public static double illuminantToCct(int illuminant) {
        switch (illuminant) {
            case 3: case 17: return 2856.0;  // tungsten, standard A
            case 15: return 3450.0;          // white fluorescent
            case 2: case 14: return 4150.0;  // fluorescent, cool white
            case 18: return 4874.0;          // standard B
            case 13: return 5000.0;          // day white fluorescent
            case 23: return 5003.0;          // D50
            case 4: case 20: return 5503.0;  // flash, D55
            case 12: return 6430.0;          // daylight fluorescent
            case 19: return 6774.0;          // standard C
            case 11: case 22: return 7504.0; // shade, D75
            case 24: return 3200.0;          // ISO studio tungsten
            default: return 6504.0;          // daylight, fine weather, cloudy, D65
        }
    }

    // ---- Sensor colour, following the DNG specification's camera model ----

    /** Calibration data for one lens, taken from CameraCharacteristics. */
    public static final class SensorColor {
        public double cct1, cct2;
        public double[] xyzToCam1, xyzToCam2;       // SENSOR_COLOR_TRANSFORM (colour matrix)
        public double[] forward1, forward2;         // SENSOR_FORWARD_MATRIX, may be null
        public double[] calib1 = identity(), calib2 = identity();
    }

    /** White-balance result: per-channel gains plus the matrix to apply after them. */
    public static final class WhiteBalance {
        public double cct;
        public double[] gains;           // R, G, B multipliers. The smallest is 1.
        public double[] camToSGamut;     // white-balanced camera RGB to linear S-Gamut3.Cine
        public double[] camToRec709;     // white-balanced camera RGB to linear Rec.709
    }

    private static double weight(double cct, SensorColor s) {
        double lo = Math.min(s.cct1, s.cct2), hi = Math.max(s.cct1, s.cct2);
        if (hi - lo < 1.0) return 1.0;
        double t = Math.max(lo, Math.min(hi, cct));
        double g = (1.0 / t - 1.0 / hi) / (1.0 / lo - 1.0 / hi);
        return s.cct1 <= s.cct2 ? g : 1.0 - g;
    }

    private static double[] xyzToCamera(SensorColor s, double g) {
        return mul(lerp(s.calib1, s.calib2, g), lerp(s.xyzToCam1, s.xyzToCam2, g));
    }

    /** Solve for the white point of a camera-space neutral, as in the DNG specification. */
    public static double[] neutralToXy(SensorColor s, double[] neutral) {
        double[] xy = D50_XY.clone();
        for (int i = 0; i < 30; i++) {
            double g = weight(xyToCct(xy), s);
            double[] next = xyzToXy(mulVec(invert(xyzToCamera(s, g)), neutral));
            double d = Math.abs(next[0] - xy[0]) + Math.abs(next[1] - xy[1]);
            xy = next;
            if (d < 1e-7) break;
        }
        return xy;
    }

    public static double[] xyToNeutral(SensorColor s, double[] xy) {
        double g = weight(xyToCct(xy), s);
        double[] n = mulVec(xyzToCamera(s, g), xyToXyz(xy[0], xy[1]));
        double max = Math.max(n[0], Math.max(n[1], n[2]));
        return new double[] {n[0] / max, n[1] / max, n[2] / max};
    }

    public static WhiteBalance solve(SensorColor s, double[] neutral) {
        double[] xy = neutralToXy(s, neutral);
        double cct = xyToCct(xy);
        double g = weight(cct, s);

        double[] calib = lerp(s.calib1, s.calib2, g);
        double[] refNeutral = mulVec(invert(calib), neutral);
        double[] d = diag(1.0 / refNeutral[0], 1.0 / refNeutral[1], 1.0 / refNeutral[2]);

        double[] camToD50;
        if (s.forward1 != null && s.forward2 != null) {
            double[] fm = lerp(s.forward1, s.forward2, g);
            camToD50 = mul(fm, mul(d, invert(calib)));
        } else {
            camToD50 = mul(bradford(xy, D50_XY), invert(xyzToCamera(s, g)));
        }

        double minInv = Math.min(1.0 / neutral[0], Math.min(1.0 / neutral[1], 1.0 / neutral[2]));
        WhiteBalance wb = new WhiteBalance();
        wb.cct = cct;
        wb.gains = new double[] {
            1.0 / neutral[0] / minInv, 1.0 / neutral[1] / minInv, 1.0 / neutral[2] / minInv
        };

        // camToD50 expects raw camera RGB. Undo the gains so the matrix follows them.
        double[] undo = diag(1.0 / wb.gains[0], 1.0 / wb.gains[1], 1.0 / wb.gains[2]);
        double[] wbToD50 = mul(camToD50, undo);
        double[] wbToD65 = mul(bradford(D50_XY, D65_XY), wbToD50);
        wb.camToSGamut = normaliseWhite(mul(invert(sGamut3CineToXyzD65()), wbToD65));
        wb.camToRec709 = normaliseWhite(mul(invert(rec709ToXyzD65()), wbToD65));
        return wb;
    }

    /** Scale so that camera white (1,1,1) maps to output luminance 1. Keeps ratios. */
    private static double[] normaliseWhite(double[] m) {
        double[] w = mulVec(m, new double[] {1, 1, 1});
        double k = 1.0 / ((w[0] + w[1] + w[2]) / 3.0);
        double[] r = m.clone();
        for (int i = 0; i < 9; i++) r[i] *= k;
        return r;
    }

    /** BT.2020 non-constant-luminance narrow-range 10-bit encode. Returns Y, Cb, Cr code values. */
    public static int[] encodeP010Codes(double r, double g, double b) {
        double y = 0.2627 * r + 0.6780 * g + 0.0593 * b;
        double cb = (b - y) / 1.8814;
        double cr = (r - y) / 1.4746;
        int yc = clampCode(64.0 + 876.0 * y);
        int cbc = clampCode(512.0 + 896.0 * cb);
        int crc = clampCode(512.0 + 896.0 * cr);
        return new int[] {yc, cbc, crc};
    }

    private static int clampCode(double v) {
        return (int) Math.max(4, Math.min(1019, Math.rint(v)));
    }
}
