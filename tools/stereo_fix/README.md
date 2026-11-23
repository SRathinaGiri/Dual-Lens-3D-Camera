# stereo_fix

Turns the app's `*_wide.jpg` / `*_ultrawide.jpg` photo pairs into comfortable stereo images: the
two views are aligned, size-matched, colour-matched and framed, then written in several viewing
formats with a self-contained HTML preview.

## Usage

```powershell
python tools\stereo_fix\stereo_fix.py <input_dir> <output_dir>
```

Requires Python 3 with `opencv-python` (4.5+, for SIFT) and `numpy`.

Options:

| Option | Effect |
|---|---|
| `--swap` | Ultrawide is the left eye (default: main/wide is left). |
| `--window 98` | Percentile of nearest content put at the screen plane; lower values bring more of the scene in front of the screen. |
| `--no-distortion` | Skip the ultrawide radial distortion fit. |
| `--work 1600` | Width used for feature matching. |

## Outputs per pair

- `<stem>_L.jpg`, `<stem>_R.jpg`: corrected left and right views at full resolution.
- `<stem>_parallel.jpg` (L|R) for VR viewers and parallel free-viewing.
- `<stem>_cross.jpg` (R|L) for cross-eyed free-viewing.
- `<stem>_anaglyph.jpg`: red-cyan Dubois anaglyph.
- `index.html`: self-contained viewer with cross-eyed (default), parallel, anaglyph and wiggle
  modes, plus per-pair alignment figures.
- `report.json`: scale, rotation, shifts, distortion coefficient, residual vertical error and
  parallax range for each pair.

## Method

1. **Features.** SIFT on contrast-equalised greyscale copies, ratio-tested FLANN matches.
2. **Alignment** (as in Stereo Photo Maker's auto-align). A RANSAC similarity gives inliers; then
   scale, rotation and vertical offset are refined from **vertical** errors only (robust IRLS), so
   horizontal parallax, which is the depth signal, is left intact.
3. **Ultrawide distortion.** One radial coefficient on the ultrawide is chosen to minimise the
   remaining vertical error; the image is resampled through the inverse model.
4. **Stereo window.** A horizontal shift puts the nearest content (98th percentile of parallax)
   at zero parallax, so the scene sits at or behind the screen.
5. **Colour.** The ultrawide is histogram-matched to the main lens per Lab channel over the shared
   area.
6. **Crop** both views to the largest rectangle the warped ultrawide fully covers.

## Notes

- Shoot in **landscape**. On phones with vertically stacked lenses, portrait shots put the
  baseline vertically in the scene; those pairs align but cannot be viewed as left/right 3D.
- On the Redmi Note 12 Pro 5G held as the app expects (upright landscape JPEGs) the main lens is
  the left eye, confirmed from parallax on objects at known depths.
- The lenses differ in depth of field and sharpness (8 MP ultrawide upscaled to the main frame);
  colour and geometry are corrected, those are not.
- Typical residual vertical error is 2 to 4 px on 3264 px wide frames (about 0.1%).
