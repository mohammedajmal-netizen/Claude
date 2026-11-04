#!/usr/bin/env python3
"""
Runs the app's real GLSL on a software GPU (Mesa llvmpipe), because the Android emulator only
offers OpenGL ES 3.0 and cannot run compute shaders.

Shader text and Bayer lookup tables are read out of the Java sources, so this tests what ships.
Expected values come from independent NumPy code in this file.

    pip install moderngl numpy
    python3 tools/shader_test.py
"""
import pathlib
import re
import sys
import textwrap

import moderngl
import numpy as np

ROOT = pathlib.Path(__file__).resolve().parent.parent
JAVA = ROOT / "app/src/main/java/app/truelog/camera/gl"

shaders_src = (JAVA / "Shaders.java").read_text()
SHADERS = {
    m.group(1): textwrap.dedent(m.group(2)).strip() + "\n"
    for m in re.finditer(r'static final String (\w+) = """\n(.*?)"""', shaders_src, re.S)
}
proc_src = (JAVA / "RawLogProcessor.java").read_text()


def table(name):
    body = re.search(name + r"\s*=\s*\{(.*?)\};", proc_src, re.S).group(1)
    return [[int(v) for v in row.split(",")] for row in re.findall(r"\{([\d,\s]+)\}", body)]


COLOR_AT_POS = table("COLOR_AT_POS")
SHADE_AT_POS = table("SHADE_AT_POS")
RED_POS = table("RED_POS")

ctx = moderngl.create_standalone_context(backend="egl", libgl="libGL.so.1", libegl="libEGL.so.1", require=430)
LINEARIZE = ctx.compute_shader(SHADERS["LINEARIZE"])
DEMOSAIC = ctx.compute_shader(SHADERS["DEMOSAIC_LOG"])
P010 = ctx.compute_shader(SHADERS["P010"])


# ---- Independent reference maths ----

def slog3_encode(x):
    x = np.asarray(x, dtype=np.float64)
    hi = (420.0 + np.log10(np.maximum(x + 0.01, 1e-9) / 0.19) * 261.5) / 1023.0
    lo = (x * (171.2102946929 - 95.0) / 0.01125 + 95.0) / 1023.0
    return np.where(x >= 0.01125, hi, lo)


def slog3_decode(y):
    cv = np.asarray(y, dtype=np.float64) * 1023.0
    hi = 10.0 ** ((cv - 420.0) / 261.5) * 0.19 - 0.01
    lo = (cv - 95.0) * 0.01125 / (171.2102946929 - 95.0)
    return np.where(cv >= 171.2102946929, hi, lo)


def expected_codes(rgb_scene):
    """Y, Cb, Cr 10-bit codes for scene-linear S-Gamut values."""
    sig = (slog3_encode(rgb_scene) * 1023.0 - 64.0) / 876.0
    y = 0.2627 * sig[..., 0] + 0.6780 * sig[..., 1] + 0.0593 * sig[..., 2]
    cb = (sig[..., 2] - y) / 1.8814
    cr = (sig[..., 0] - y) / 1.4746
    return np.stack([64 + 876 * y, 512 + 896 * cb, 512 + 896 * cr], axis=-1)


def decode_p010(y_bytes, uv_bytes, w, h):
    """Treats the buffers exactly as an encoder would, so plane layout errors show up here."""
    y = (np.frombuffer(y_bytes, dtype="<u2").reshape(h, w) >> 6).astype(np.float64)
    uv = (np.frombuffer(uv_bytes, dtype="<u2").reshape(h // 2, w) >> 6).astype(np.float64)
    return y, uv[:, 0::2], uv[:, 1::2]


def codes_to_scene(y, cb, cr):
    y = (y - 64) / 876
    cb = (cb - 512) / 896
    cr = (cr - 512) / 896
    r = y + 1.4746 * cr
    b = y + 1.8814 * cb
    g = (y - 0.2627 * r - 0.0593 * b) / 0.6780
    sig = np.stack([r, g, b], axis=-1)
    return slog3_decode((sig * 876 + 64) / 1023)


# ---- Pipeline, in the same order as RawLogProcessor ----

def mosaic(rgb, cfa, black, white):
    """rgb is H,W,3 in 0..1 sensor linear. Returns 16-bit Bayer data."""
    h, w, _ = rgb.shape
    yy, xx = np.mgrid[0:h, 0:w]
    pos = (xx & 1) + ((yy & 1) << 1)
    color = np.array(COLOR_AT_POS[cfa])[pos]
    black = np.array(black, dtype=np.float64)[pos]
    v = np.take_along_axis(rgb, color[..., None], axis=2)[..., 0]
    return np.round(black + v * (white - black)).astype(np.uint16)


def run(raw, crop, out, cfa, black=(64, 64, 64, 64), white=1023.0, gains=(1, 1, 1),
        cam_to_gamut=(1, 0, 0, 0, 1, 0, 0, 0, 1), exposure=2.0, shading=None):
    cx, cy, cw, ch = crop
    ow, oh = out
    raw_h, raw_w = raw.shape
    tex_raw = ctx.texture((cw, ch), 1, raw[cy:cy + ch, cx:cx + cw].tobytes(), dtype="u2")
    tex_raw.filter = (moderngl.NEAREST, moderngl.NEAREST)
    lin = ctx.texture((cw, ch), 1, dtype="f4")
    lin.filter = (moderngl.NEAREST, moderngl.NEAREST)
    log = ctx.texture((cw, ch), 4, dtype="f2")
    log.filter = (moderngl.LINEAR, moderngl.LINEAR)
    log.repeat_x = log.repeat_y = False

    if shading is None:
        shade = ctx.texture((2, 2), 4, np.ones(16, "f4").tobytes(), dtype="f4")
    else:
        rows, cols = shading.shape[:2]
        shade = ctx.texture((cols, rows), 4, shading.astype("f4").tobytes(), dtype="f4")
    shade.filter = (moderngl.LINEAR, moderngl.LINEAR)
    shade.repeat_x = shade.repeat_y = False

    gain_at = [gains[c] for c in COLOR_AT_POS[cfa]]
    p = LINEARIZE
    p["uCrop"].value = (cx, cy)
    p["uRawSize"].value = (raw_w, raw_h)
    p["uBlack"].value = tuple(float(b) for b in black)
    p["uWhite"].value = float(white)
    p["uGain"].value = tuple(float(g) for g in gain_at)
    p["uShadeChannel"].value = tuple(SHADE_AT_POS[cfa])
    p["uShadeOn"].value = 1.0 if shading is not None else 0.0
    tex_raw.use(0)
    shade.use(1)
    lin.bind_to_image(0, read=False, write=True)
    p.run((cw + 7) // 8, (ch + 7) // 8, 1)
    ctx.memory_barrier()

    p = DEMOSAIC
    p["uRedPos"].value = tuple(RED_POS[cfa])
    m = np.array(cam_to_gamut, dtype=np.float64).reshape(3, 3)
    p["uCamToGamut"].value = tuple(m.T.flatten())  # GL wants column order
    p["uExposure"].value = float(exposure)
    lin.use(0)
    log.bind_to_image(0, read=False, write=True)
    p.run((cw + 7) // 8, (ch + 7) // 8, 1)
    ctx.memory_barrier()

    p = P010
    p["uOutSize"].value = (ow, oh)
    log.use(0)
    y_buf = ctx.buffer(reserve=ow * oh * 2)
    uv_buf = ctx.buffer(reserve=ow * oh)
    y_buf.bind_to_storage_buffer(0)
    uv_buf.bind_to_storage_buffer(1)
    p.run((ow // 2 + 7) // 8, (oh // 2 + 7) // 8, 1)
    ctx.memory_barrier()
    ctx.finish()
    return decode_p010(y_buf.read(), uv_buf.read(), ow, oh), log


# ---- Tests ----

FAILED = []


def check(name, ok, detail=""):
    print(f"{'ok  ' if ok else 'FAIL'} {name}  {detail}")
    if not ok:
        FAILED.append(name)


def flat(color, h=48, w=80):
    return np.broadcast_to(np.array(color, dtype=np.float64), (h, w, 3)).copy()


def centre(planes):
    y, cb, cr = planes
    h, w = y.shape
    return np.array([y[h // 2, w // 2], cb[h // 4, w // 4], cr[h // 4, w // 4]])


def test_mid_grey():
    raw = mosaic(flat((0.09, 0.09, 0.09)), 0, (64,) * 4, 1023)
    planes, _ = run(raw, (0, 0, 80, 48), (80, 48), 0)
    got = centre(planes)
    check("18% grey lands on Sony code 420, no chroma", np.all(np.abs(got - [420, 512, 512]) <= 2), got)


def test_every_cfa():
    rgb = (0.30, 0.20, 0.10)
    want = expected_codes(np.array(rgb) * 2.0)
    for cfa in range(4):
        raw = mosaic(flat(rgb), cfa, (100,) * 4, 4095)
        planes, _ = run(raw, (0, 0, 80, 48), (80, 48), cfa, black=(100,) * 4, white=4095.0)
        got = centre(planes)
        check(f"Bayer order {cfa} keeps colour", np.all(np.abs(got - want) <= 2), f"{got} want {want.round(1)}")


def test_black_per_position():
    for cfa in range(4):
        black = (60, 70, 80, 90)
        raw = mosaic(flat((0.2, 0.2, 0.2)), cfa, black, 1023)
        planes, _ = run(raw, (0, 0, 80, 48), (80, 48), cfa, black=black)
        got = centre(planes)
        want = expected_codes(np.array((0.2, 0.2, 0.2)) * 2.0)
        check(f"per-site black level, order {cfa}", np.all(np.abs(got - want) <= 3), got)


def test_white_balance_and_matrix():
    gains = (2.0, 1.0, 1.6)
    m = (1.2, -0.1, -0.1, -0.15, 1.3, -0.15, 0.0, -0.2, 1.2)
    rgb = (0.12, 0.25, 0.15)
    raw = mosaic(flat(rgb), 3, (64,) * 4, 1023)
    planes, _ = run(raw, (0, 0, 80, 48), (80, 48), 3, gains=gains, cam_to_gamut=m, exposure=1.6)
    scene = np.array(m).reshape(3, 3) @ (np.array(rgb) * gains) * 1.6
    want = expected_codes(scene)
    got = centre(planes)
    check("white balance then matrix", np.all(np.abs(got - want) <= 2), f"{got} want {want.round(1)}")


def test_shading_each_order():
    # Different gain on all four channels, so a swapped green or colour shows as a tint.
    rgb_raw = (0.10, 0.20, 0.10)
    for cfa in range(4):
        # R, G even, G odd, B. The greens must cancel the deliberate imbalance added below.
        shading = np.tile(np.array([2.0, 1.0, 1.25, 1.5]), (3, 4, 1))
        h, w = 48, 80
        raw_f = flat(rgb_raw)
        raw = mosaic(raw_f, cfa, (64,) * 4, 1023).astype(np.float64)
        yy, xx = np.mgrid[0:h, 0:w]
        pos = (xx & 1) + ((yy & 1) << 1)
        is_odd_green = (np.array(COLOR_AT_POS[cfa])[pos] == 1) & ((yy & 1) == 1)
        raw[is_odd_green] = 64 + (raw[is_odd_green] - 64) * 0.8
        planes, _ = run(raw.round().astype(np.uint16), (0, 0, w, h), (w, h), cfa, shading=shading)
        want = expected_codes(np.array((0.2, 0.2, 0.15)) * 2.0)
        got = centre(planes)
        check(f"lens shading channels, order {cfa}", np.all(np.abs(got - want) <= 3), f"{got} want {want.round(1)}")


def test_shading_position_with_crop():
    # Gain rises left to right across the full sensor. A crop must read the right part of the map.
    rows, cols = 2, 2
    shading = np.zeros((rows, cols, 4))
    shading[:, 0, :] = 1.0
    shading[:, 1, :] = 3.0
    raw_w, raw_h, cw, ch = 160, 90, 40, 20
    cx, cy = 100, 34
    raw = mosaic(flat((0.1, 0.1, 0.1), raw_h, raw_w), 0, (64,) * 4, 1023)
    planes, _ = run(raw, (cx, cy, cw, ch), (cw, ch), 0, shading=shading)
    px = cx + cw / 2
    # Texel centres of a 2x2 map sit at 0.25 and 0.75.
    u = (px / raw_w - 0.25) / 0.5
    gain = 1.0 + 2.0 * np.clip(u, 0, 1)
    want = expected_codes(np.full(3, 0.1 * gain * 2.0))
    got = centre(planes)
    check("shading map follows the crop position", abs(got[0] - want[0]) <= 3, f"{got} want {want.round(1)}")


def test_crop_phase():
    # The image must stay correct when the crop starts part way into the sensor.
    h, w = 90, 160
    img = np.zeros((h, w, 3))
    img[:, :, 0] = 0.05
    img[:, :, 1] = 0.05
    img[:, :, 2] = 0.05
    img[34:54, 60:100] = (0.3, 0.2, 0.1)  # the cropped window
    raw = mosaic(img, 1, (64,) * 4, 1023)
    planes, _ = run(raw, (60, 34, 40, 20), (40, 20), 1)
    got = centre(planes)
    want = expected_codes(np.array((0.3, 0.2, 0.1)) * 2.0)
    check("even crop keeps Bayer phase and position", np.all(np.abs(got - want) <= 2), f"{got} want {want.round(1)}")


def quadrant_image(h, w):
    img = np.zeros((h, w, 3))
    img[: h // 2, : w // 2] = (0.30, 0.05, 0.05)
    img[: h // 2, w // 2:] = (0.05, 0.30, 0.05)
    img[h // 2:, : w // 2] = (0.05, 0.05, 0.30)
    img[h // 2:, w // 2:] = (0.25, 0.25, 0.25)
    return img


def test_plane_layout():
    # Flat tests cannot see a stride or ordering bug. Four colours in four corners can.
    h, w = 48, 80
    img = quadrant_image(h, w)
    raw = mosaic(img, 0, (64,) * 4, 1023)
    planes, _ = run(raw, (0, 0, w, h), (w, h), 0)
    y, cb, cr = planes
    spots = {"top left": (h // 4, w // 4), "top right": (h // 4, 3 * w // 4),
             "bottom left": (3 * h // 4, w // 4), "bottom right": (3 * h // 4, 3 * w // 4)}
    for name, (r, c) in spots.items():
        want = expected_codes(img[r, c] * 2.0)
        got = np.array([y[r, c], cb[r // 2, c // 2], cr[r // 2, c // 2]])
        check(f"P010 layout, {name}", np.all(np.abs(got - want) <= 3), f"{got} want {want.round(1)}")


def test_half_scale():
    h, w = 48, 80
    img = quadrant_image(h, w)
    raw = mosaic(img, 0, (64,) * 4, 1023)
    planes, _ = run(raw, (0, 0, w, h), (w // 2, h // 2), 0)
    y, cb, cr = planes
    r, c = h // 8, 3 * w // 8
    want = expected_codes(img[h // 4, 3 * w // 4] * 2.0)
    got = np.array([y[r, c], cb[r // 2, c // 2], cr[r // 2, c // 2]])
    check("1080p from 4K half scale", np.all(np.abs(got - want) <= 3), f"{got} want {want.round(1)}")


def test_highlight_clip_neutral():
    raw = mosaic(flat((1, 1, 1)), 0, (64,) * 4, 1023)
    planes, _ = run(raw, (0, 0, 80, 48), (80, 48), 0, gains=(2.0, 1.0, 1.5))
    got = centre(planes)
    check("clipped highlight has no colour cast", abs(got[1] - 512) <= 2 and abs(got[2] - 512) <= 2, got)


def test_shadows_do_not_wrap():
    raw = np.full((48, 80), 40, dtype=np.uint16)  # below black
    planes, _ = run(raw, (0, 0, 80, 48), (80, 48), 0)
    y = planes[0]
    check("below black stays legal, no wraparound", y.min() >= 4 and y.max() <= 130, f"{y.min()}..{y.max()}")


def test_round_trip_on_detail():
    """End to end on a scene with edges: mosaic, process, decode the P010, compare to the truth."""
    h, w = 240, 320
    rng = np.random.default_rng(7)
    yy, xx = np.mgrid[0:h, 0:w]
    base = 0.02 + 0.7 * (xx / w)[..., None] * np.array([1.0, 0.8, 0.6])
    for _ in range(14):
        x0, y0 = rng.integers(0, w - 60), rng.integers(0, h - 60)
        base[y0:y0 + 50, x0:x0 + 50] = rng.uniform(0.03, 0.7, 3)
    raw = mosaic(base, 2, (64,) * 4, 1023)
    planes, _ = run(raw, (0, 0, w, h), (w, h), 2)
    y, cb, cr = planes
    cb = np.repeat(np.repeat(cb, 2, 0), 2, 1)
    cr = np.repeat(np.repeat(cr, 2, 0), 2, 1)
    got = codes_to_scene(y, cb, cr) / 2.0
    # Hard synthetic colour steps are the worst case for any Bayer sensor and for 4:2:0, so judge
    # the pipeline where it should be near perfect, and require no colour or level bias anywhere.
    edges = (np.abs(np.diff(base, axis=1, prepend=base[:, :1])).max(2)
             + np.abs(np.diff(base, axis=0, prepend=base[:1])).max(2)) > 0.02
    padded = np.pad(edges, 6, constant_values=False)
    near_edge = np.lib.stride_tricks.sliding_window_view(padded, (13, 13)).any(axis=(2, 3))
    inner = np.zeros((h, w), bool)
    inner[8:h - 8, 8:w - 8] = True
    flat_err = got[inner & ~near_edge] - base[inner & ~near_edge]
    psnr = 10 * np.log10(0.7 ** 2 / np.mean(flat_err ** 2))
    bias = np.abs((got[inner] - base[inner]).mean(axis=0)).max()
    check("round trip, areas away from edges", psnr > 45, f"PSNR {psnr:.1f} dB")
    check("round trip, no colour or level bias", bias < 0.002, f"worst channel bias {bias:.5f}")


def test_monitor_shaders():
    prog = ctx.program(vertex_shader=SHADERS["QUAD_VERT"], fragment_shader=SHADERS["PREVIEW_FRAG"])
    enc = ctx.program(vertex_shader=SHADERS["QUAD_VERT"], fragment_shader=SHADERS["ENCODER_FRAG"])
    h, w = 48, 80
    raw = mosaic(quadrant_image(h, w), 0, (64,) * 4, 1023)
    _, log = run(raw, (0, 0, w, h), (w, h), 0)
    vao = ctx.vertex_array(prog, [])
    fbo = ctx.simple_framebuffer((w, h), components=4)
    fbo.use()
    log.use(0)
    prog["uFlip"].value = (0.0, 0.0)
    prog["uGamutToRec709"].value = (1, 0, 0, 0, 1, 0, 0, 0, 1)
    prog["uAssist"].value = 1
    prog["uZebra"].value = 0
    prog["uPeaking"].value = 0
    prog["uClipSignal"].value = 2.0
    vao.render(moderngl.TRIANGLE_STRIP, vertices=4)
    px = np.frombuffer(fbo.read(components=3), dtype=np.uint8).reshape(h, w, 3)
    # GL reads bottom-up, and the shader puts sensor row 0 at the top of the framebuffer.
    top_left = px[h - h // 4, w // 4]
    check("preview shows top left of the sensor at the top left", top_left[0] > top_left[1] + 40, top_left)
    prog["uZebra"].value = 1
    prog["uClipSignal"].value = 0.0
    fbo.clear()
    vao.render(moderngl.TRIANGLE_STRIP, vertices=4)
    zebra = np.frombuffer(fbo.read(components=3), dtype=np.uint8).reshape(h, w, 3)
    check("zebra pattern draws", (zebra == [255, 0, 64]).all(axis=2).mean() > 0.2)
    prog["uZebra"].value = 0
    prog["uPeaking"].value = 1
    vao.render(moderngl.TRIANGLE_STRIP, vertices=4)
    check("encoder and peaking shaders link", enc.glo > 0)


for t in [test_mid_grey, test_every_cfa, test_black_per_position, test_white_balance_and_matrix,
          test_shading_each_order, test_shading_position_with_crop, test_crop_phase, test_plane_layout,
          test_half_scale, test_highlight_clip_neutral, test_shadows_do_not_wrap, test_round_trip_on_detail,
          test_monitor_shaders]:
    t()

print(f"\n{ctx.info['GL_RENDERER']}, {ctx.info['GL_VERSION']}")
print("ALL PASS" if not FAILED else f"{len(FAILED)} FAILED: {FAILED}")
sys.exit(1 if FAILED else 0)
