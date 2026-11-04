# TrueLog Camera

A camera app for the Galaxy S23 Ultra with Log video recording, which Samsung's own camera does not offer on this phone.

## How the Log mode works

Log mode does not ask the phone for processed video. It reads RAW sensor frames and develops them on the GPU:

1. Black level, lens shading and white balance are applied to the RAW data.
2. Malvar-He-Cutler demosaic.
3. Sensor colour to S-Gamut3.Cine, using the phone's own calibration matrices.
4. The S-Log3 curve.
5. 10-bit HEVC with AAC audio.

No sharpening, noise reduction or tone mapping is applied. Files are saved to `DCIM/TrueLog`.

## Grading the footage

In DaVinci Resolve, or any tool with a colour space transform, set the input to:

- Colour space: S-Gamut3.Cine
- Gamma: S-Log3

The file is tagged BT.2020 so that players do not treat it as HDR. Ignore that tag and set the input by hand.

## Modes

| Mode | What it does |
| --- | --- |
| PHOTO | Automatic JPEG with lens switching, pinch zoom, tap to focus and flash |
| VIDEO | HEVC up to what the phone allows, with optional HLG 10-bit and stabilisation |
| PRO | Manual ISO, shutter, white balance, focus and EV, with optional DNG |
| LOG | S-Log3 from RAW, with 709 view, zebras, focus peaking and AE lock |

In PRO and LOG, tap a control to open its slider. Tap it again to hand it back to the camera.

## Known limits

- Log resolution and frame rate depend on what the phone exposes as a RAW stream. Settings > Device report shows it.
- There is no electronic stabilisation in Log, and no lens distortion correction. Distortion is most visible on the ultrawide.
- There is no noise reduction in Log, so shadows at high ISO will be grainy.
- Mid grey sits at the right code only if exposure matches the Highlight headroom setting. Check it with a grey card.
- Samsung features tied to its own camera app, such as multi-frame Night mode and probably 200MP, are not included.

## Building

Every push to `truelog-camera` builds an APK on GitHub Actions. To build locally, use `./gradlew assembleRelease`.

`app/sideload.keystore` is committed on purpose so that each build installs over the last one. It is a personal sideloading key, so do not use it for anything published.
