# Dual Lens 3D Camera

[![Download APK](https://img.shields.io/badge/Download-APK-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://github.com/SRathinaGiri/Dual-Lens-3D-Camera/releases/latest)

[![Latest release](https://img.shields.io/github/v/release/SRathinaGiri/Dual-Lens-3D-Camera?label=release&color=blue)](https://github.com/SRathinaGiri/Dual-Lens-3D-Camera/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/SRathinaGiri/Dual-Lens-3D-Camera/total?color=brightgreen)](https://github.com/SRathinaGiri/Dual-Lens-3D-Camera/releases)
[![Android 11+](https://img.shields.io/badge/Android-11%2B%20%28API%2030%29-3DDC84?logo=android&logoColor=white)](#supported-phones)
[![Kotlin](https://img.shields.io/badge/Kotlin-Camera2-7F52FF?logo=kotlin&logoColor=white)](app/src/main/java/com/example/duallens3dcamera)
[![OpenCV](https://img.shields.io/badge/OpenCV-4.12-5C3EE8?logo=opencv&logoColor=white)](#3d-correction-photos-and-3d-videos)
[![Last commit](https://img.shields.io/github/last-commit/SRathinaGiri/Dual-Lens-3D-Camera)](https://github.com/SRathinaGiri/Dual-Lens-3D-Camera/commits/master)
[![Forked from jcpeterson](https://img.shields.io/badge/fork%20of-jcpeterson%2FDual--Lens--3D--Camera-lightgrey?logo=github)](https://github.com/jcpeterson/Dual-Lens-3D-Camera)

An Android app for taking stereo (3D) photos and videos with a phone's **wide** (main) and
**ultrawide** rear lenses at the same time.

This is a fork of [jcpeterson/Dual-Lens-3D-Camera](https://github.com/jcpeterson/Dual-Lens-3D-Camera)
that adds support for phones that hide their dual-lens camera (Redmi Note 12 Pro 5G), automatic
3D correction, live 3D preview, recording-to-3D-video conversion and a compatibility test.

## Supported phones

Tested and working on:
- **Redmi Note 12 Pro 5G** (`ruby`, HyperOS 2 / Android 14): photos, video, live 3D preview.
- Samsung S22 (photos only), S24 and Fold5.
- Pixel 7, 7a, 7 Pro, 8, 8 Pro, 9a and 10.

Other phones: run **Settings > 3D output > Compatibility test** (see below). It reports whether
3D photos, 3D video and live 3D preview work, and can share the report.

Sync between the two lenses is best on the Pixel 7 series (~1-4 ms for photos, ~4 ms for video).
On the Redmi Note 12 Pro both lenses are triggered by the same capture (0 ms apart).

## Install

Download the APK from [Releases](../../releases) (or build it, see below) and open it on the phone.

Release builds of this fork are signed with this fork's own key. If you have a build from the
original project or another fork installed under the same app ID, uninstall it first.

## How to use

1. **Hold the phone in landscape** on phones whose rear lenses sit in a vertical column (Samsung,
   Redmi): the two lenses then sit side by side, which gives left/right depth. Pixels: portrait.
2. Press **PHOTO** or **RECORD**.
3. Outputs are saved under `Pictures/StereoCapture`:
   - `sbs/` corrected side-by-side 3D photos (`*_sbs.jpg`)
   - `jpg/` individual lens photos (optional setting)
   - `videos/` one MP4 per lens (`*_wide.mp4`, `*_ultrawide.mp4`)
   - `3d/` combined 3D videos made with "Make 3D video"

Top bar:
- **2D / 3D / ANA**: live preview mode. 3D shows both lenses side by side, ANA a red-cyan anaglyph.
  Take one 3D photo first; the live view then uses that photo's alignment. The preview returns to
  2D while recording. If a phone can't run the extra camera stream, the app says so and stays 2D.
- **EIS**, **JPG/RAW** and **1x/2x** as before (2x needs a phone that exposes a cropped wide lens).

**Landscape-friendly labels:** the screen layout stays portrait so the camera view never jumps, but
every button label and icon, the self-timer digits, the level reading and the time-lapse bar turn
to stay upright when you hold the phone sideways (either way) or upside down. They switch once the
phone is about 10° past the halfway point, so they don't flicker. In landscape, SETTINGS shows as
a gear and the time-lapse STOP label is shortened. Dialogs and the Settings screen still open in
portrait.

Tools row (icons with labels, above the PHOTO and RECORD buttons, which show a camera and a video-camera icon; RECORD shows a stop square while recording):
- **Torch**: on / off.
- **Timer**: self-timer of OFF, 3 s, 5 s or 10 s for photos. A large countdown and a beep each second;
  PHOTO becomes **CANCEL** while it counts down. The digits stay upright in landscape.
- **Grid**: cycles *Grid off → Grid 3x3 → Grid+Lvl (grid and level) → Level*. The level uses the gravity sensor and
  draws the true horizon; it turns **green** within 1° of level (portrait or landscape) and shows the
  tilt in degrees. It only appears in the viewfinder, never in photos.
- **T-lapse** (time-lapse): choose the interval (seconds) and number of photos; the dialog shows the total time.
  Photos are saved as `<start time>_TL0001_sbs.jpg`, `…_TL0002_sbs.jpg`, … so they sort in order.
  While it runs the screen stays on (the phone won't lock) and dims after 20 s to reduce heat; tap
  the progress bar to brighten it. PHOTO becomes **STOP TIME-LAPSE**. Keep the phone on a charger and
  keep the app open: leaving the app stops the time-lapse. The shortest interval is 5 s with 3D
  correction on (each photo takes about 2–4 s to process) and 2 s with it off.

### Main lens focus (Settings → 3D output → Main lens focus)

The two lenses focus very differently. On the Redmi Note 12 Pro:
- **Ultrawide:** fixed focus. It is set at about 0.9 m, so everything from about **45 cm to infinity**
  is sharp, always.
- **Main lens:** autofocus with a much brighter, shallower lens (f/1.88). When it focuses on
  something near, its background goes soft.

| Option | What it does | Best for |
|---|---|---|
| **Auto** (default) | The main lens keeps refocusing on the subject | Close subjects, everyday photos |
| **Match ultrawide** | The main lens is fixed at its hyperfocal distance: roughly 1.3 m to infinity is sharp, like the ultrawide | Landscapes, buildings, groups, 3D video |
| **Lock at start** | Autofocus until you press RECORD or start a time-lapse, then that focus is held until it ends | A flower time-lapse, videos of a fixed subject |

Why matching or locking focus can be better:
1. **Both eyes stay equally sharp.** If the main lens focuses near, its background blurs while the
   ultrawide's background stays sharp. Your eyes then get a sharp and a blurred version of the same
   background, which is tiring to view in 3D.
2. **Steady videos and time-lapses.** When autofocus refocuses, the main lens's picture grows or
   shrinks slightly ("focus breathing"). "Make 3D video" measures the alignment from a few frames
   and assumes it stays fixed, so refocusing makes the eyes drift. Time-lapse frames can also
   jump in sharpness and size.
3. **Little detail is lost.** The ultrawide image is cropped and enlarged in the 3D correction, so it
   has less detail anyway. A main lens fixed for distance is not noticeably softer than its partner.

When it is worse: **close subjects.** With *Match ultrawide*, anything nearer than about 1.2–1.5 m
gets soft on the main lens. Use *Auto* or *Lock at start* for close-ups. Below about 45 cm the
ultrawide itself is soft, so very close 3D shots are limited anyway.

The focus distances this phone reports are approximate ("uncalibrated"). *Match ultrawide* therefore
uses the main lens's own reported hyperfocal setting, not a distance in metres. Exposure, colour
and white balance stay automatic in all three modes.

### Pro mode: ISO and shutter speed (PRO button, top bar)

**PRO** opens a panel with two rows: **ISO** (AUTO, 100–1600) and **SHUTTER** (AUTO, 1/4000 s to
0.8 s). The choices are limited to values both lenses support. On the Redmi:
- **ISO:** the main lens goes 50–6400, the ultrawide only 50–2000.
- **Shutter:** the main lens goes up to 30 s, the ultrawide only up to 0.8 s.

The same settings go to both lenses, and everything else (focus, white balance) stays automatic.
**PRO•** turns amber while any manual value is on, so you don't forget it. Choose AUTO in both
rows to go back to fully automatic.

- **Both set:** used exactly as chosen.
- **Only one set** (for example shutter 1/125, ISO AUTO): the app measures the scene with
  auto-exposure for a moment and then picks the other value so the brightness stays the same.
  Android has no "shutter priority" mode, so this is done by the app. The measured brightness is
  then fixed. Choose a value again if the light changes a lot.
- **Video:** the shutter can't be slower than one frame (1/30 s at 30 fps). Slower choices are capped
  while recording.
- **Time-lapse:** fixed values stop frame-to-frame brightness flicker.
- **The phone adjusts the ultrawide's ISO.** On the Redmi the shutter speed is identical on both
  lenses, but the phone's camera system re-scales the ultrawide's ISO (ISO 200 asked for, 156 used
  on the ultrawide), probably to compensate for its different sensor. Apps can't override this.
  The 3D colour matching evens out any remaining brightness difference.
- **Depth of field can't be set.** Both apertures are fixed (f/1.88 and f/2.2), so no app can change
  depth of field directly. Only the main lens's focus can be chosen; see *Main lens focus* above.

### 3D correction (photos and 3D videos)

On by default (**Settings > 3D output > 3D correction**). Similar to Stereo Photo Maker's
auto-alignment, but both views are corrected instead of fitting one to the other:

- **Size and rotation**: matching points are found in both images (SIFT). The main image is shrunk
  and the ultrawide enlarged until they meet in the middle, and each is turned by half the angle.
- **Vertical alignment**: fitted from vertical differences only, so the horizontal offset that
  carries the depth is untouched; each view takes half of the vertical shift.
- **Lens distortion**: the ultrawide's barrel distortion is removed. The main image already comes
  out distortion-corrected from the camera, so it is kept straight.
- **Stereo window**: the nearest objects are placed at the screen plane, everything else behind it.
- **Colour and brightness**: both views are brought to their average with a smooth per-channel
  adjustment (or, with "Average colour of both lenses" off, the ultrawide copies the main lens).
- **Crop**: both views are cropped to the area they share, so there are no black edges.

If a scene gives too few matching points (for example a blank wall or a hand right in front of the
lenses), the last good alignment from the session is reused; if there is none, the original simple
alignment is used.

Other 3D output settings: **Cross-eyed layout** (right|left instead of left|right) and **Swap left
and right** (if near things look far away).

### Make 3D video

**Settings > 3D output > Make 3D video from a recording** lists your recordings. Pick one and choose
full or half side-by-side (half is the usual 3D TV format). The correction is measured once per clip
from several frames, so the result is steady; the original sound is kept. A 5-10 s clip takes about
half a minute on the Redmi Note 12 Pro.

### Compatibility test

**Settings > 3D output > Compatibility test** checks, in about 15 seconds:
1. every camera ID, including ones the phone hides from apps;
2. whether a rear camera pair (main + ultrawide) exists;
3. a real simultaneous photo from both lenses (blank check, time gap);
4. real video into two encoders, retrying with the hidden-camera workaround if needed;
5. the extra stream needed for live 3D preview.

It ends with YES / YES (with workaround) / NO for 3D photos, 3D video and live preview.
**Share report** sends the text, which is the most useful thing to include when reporting a phone.

## Known issues

- Video recording doesn't work on Samsung S22.
- Not working at all on Samsung A or FE models.
- RAW shooting doesn't work on any Samsung device.
- Alignment not working on Samsung Fold5.
- Left-right photo sync is ~30 ms+ on Samsung and ~20 ms+ on Pixel 8 / 8 Pro.
- Redmi Note 12 Pro: the lenses are about 1-2 cm apart, so depth is subtle beyond a few metres;
  video runs at the 15 fps default; the ultrawide is 8 MP and noisier than the main lens.
- The main lens has a shallower depth of field than the ultrawide. Background blur differs between
  the two views and can't be corrected in software.

## PC tools

`tools/stereo_fix/` has Python versions of the correction (OpenCV + NumPy; `ffmpeg` for video):

```powershell
python tools\stereo_fix\stereo_fix.py <folder with *_wide.jpg/*_ultrawide.jpg> <output folder>
python tools\stereo_fix\stereo_video.py <stem>_wide.mp4 <stem>_ultrawide.mp4 out.mp4 [--cross] [--half]
```

The photo tool writes left/right, parallel, cross-eyed and anaglyph images plus a self-contained
`index.html` viewer. It is the reference implementation: `StereoAlignMathTest` checks the app's
Kotlin port against a fixture exported from it (`tools/stereo_fix/export_fixture.py`). See
[tools/stereo_fix/README.md](tools/stereo_fix/README.md).

## Mirror adapter (optional hardware)

For a wider stereo baseline (stronger 3D, or phones without a usable lens pair), `hardware/mirror-adapter/`
contains a parametric OpenSCAD holder for a four-mirror side-by-side splitter on the main lens and
`verify_profile.py`, which ray-traces the printed cross-section for clipping and mirror coverage.

## How to assess sync between left and right photos

Method 1 (easiest):
1. Enable "Photo sync toast" in the settings.
2. A brief message will be displayed.
3. `Δstart` is the difference in sensor timestamps (wide - ultra).
4. `overlap` is the duration where both lenses were integrating light at the same time.

Method 2 (advanced / more detail):
1. Enable "Stereo photo JSON log" in the settings.
2. Take a photo. Then check your `Documents/StereoCapture` folder for the json output.

The JSON file contains several fields, such as:

- `sensorTimestampMs`: start time of exposure (milliseconds, monotonic sensor clock)
- `exposureTimeMs`: how long the sensor integrated light (milliseconds)
- `deltaStartMs`: difference in sensor timestamps (wide - ultra)
- `overlapMs`: duration where both lenses were integrating light at the same time
- `nonOverlapMs`: duration where exactly one lens was integrating (wide+ultra - 2*overlap)
- `idleGapMs`: if exposures are disjoint, time when neither lens was integrating (else 0)
- `unionMs`: total span from earliest start to latest end (overlap + nonOverlap + idleGap)
- `overlapPctOfShorter`: % of shorter exposure was that was simultaneous
- `overlapPctOfLonger`: % of longer exposure was that was simultaneous

## How to assess sync between left and right videos

1. Enable "Stereo video JSON log" in the settings.
2. Optional: Enable "frames only" for less logging detail.
3. Take a video. Then check your `Documents/StereoCapture` folder for the json output.

The JSON file contains several fields, such as:
- `frameNumber`: `TotalCaptureResult.frameNumber` for the capture request.
- `sensorTimestampNs`: `CaptureResult.SENSOR_TIMESTAMP` (nanoseconds).
This is the **start of exposure** of the first sensor row.
- `exposureTimeNs` (full mode): `CaptureResult.SENSOR_EXPOSURE_TIME`.
- `codecPresentationTimeUs` (full mode): `MediaCodec.BufferInfo.presentationTimeUs`.
- `muxerPresentationTimeUs` (full mode): the PTS written to `MediaMuxer` (per-track normalized to
start at 0 in this app).

## Implementation details

The app uses the Android **Camera2** API to capture from the rear **logical** camera and route output
buffers to the **wide** and **ultrawide** physical cameras simultaneously.

Camera discovery:
- **LensDiscovery.kt** finds the rear logical multi-camera and its wide/ultrawide physical IDs. Some
  vendors (Xiaomi HyperOS) leave it out of `getCameraIdList()` but still open it by ID, so numeric
  IDs 0-15 missing from the list are probed too (`StereoRig.logicalIsHidden`).

Capture:
- **MainActivity** owns the UI and delegates camera work to `StereoCameraController`.
- **StereoCameraController.kt**:
    - **Preview session**: wide preview surface + ImageReaders for stereo stills. With live 3D
      preview on, two small YUV readers (`LivePreview3d.kt`) replace the preview surface.
    - **Recording session**: wide + ultrawide `MediaCodec` input surfaces + preview surface, bound to
      physical lenses with `OutputConfiguration.setPhysicalCameraId()`. On hidden logical cameras the
      wide encoder takes the logical stream, because those HALs deliver empty frames to an encoder
      bound to the master physical camera while a preview uses it.
    - Requests fixed FPS and minimizes hidden cropping when stabilization is OFF; optionally restricts
      ultrawide 3A to the region that overlaps the wide view.

3D processing:
- **StereoAlignMath.kt**: pure-Kotlin numerics (vertical-only refinement, ultrawide distortion,
  stereo window, symmetric plan, colour LUTs, crop), unit-tested against the Python reference.
- **StereoSbs.kt**: OpenCV part for photos (SIFT matching, `initUndistortRectifyMap` + `remap`, LUTs,
  JPEG). Falls back to the original ORB similarity alignment on failure.
- **StereoVideoComposer.kt** / **Make3dVideoActivity.kt**: decode both recordings with `MediaCodec`,
  measure the correction from 8 frame pairs, render, encode H.264 and copy the audio.
- **CompatibilityTester.kt**: the compatibility test.

Encoding / muxing:
- **VideoEncoder.kt**: one H.264 encoder per lens. **AudioEncoder.kt**: AAC, written into both MP4s.
- **Mp4Muxer.kt**: synchronized `MediaMuxer` wrapper. **StereoRecordingLogger.kt**: optional JSON log.

## Building

Use the Gradle wrapper with JDK 17 or newer (see [AGENTS.md](AGENTS.md) for build variants and
device-safety rules):

```powershell
.\gradlew.bat :app:assembleAgent :app:testAgentUnitTest :app:lintAgent   # development build
.\gradlew.bat :app:assembleRelease                                         # release APK
```

Release signing reads a git-ignored `keystore.properties` in the project root:

```properties
storeFile=D:/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Without that file the release APK is built unsigned.

## Development log

See [DEVELOPMENT_LOG.md](DEVELOPMENT_LOG.md) for chronological investigation notes, design
decisions, failed approaches, validation results, and links back to relevant Git history.
