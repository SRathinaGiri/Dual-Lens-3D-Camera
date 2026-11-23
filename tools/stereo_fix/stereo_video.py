"""Combine a wide/ultrawide video pair into one corrected side-by-side 3D video.

Same correction as stereo_fix.py, measured once per clip (the lenses are rigid, so a single
transform keeps the result steady):
  * several frame pairs are sampled; their SIFT matches are pooled
  * scale / rotation / vertical offset / ultrawide distortion from vertical errors only
  * stereo window from the pooled parallax
  * one colour lookup table per B, G, R channel (fast per frame; mirrors the Android port)
  * crop to the shared area; audio copied from the wide clip

Usage:
  python stereo_video.py WIDE.mp4 ULTRAWIDE.mp4 OUT.mp4 [--cross] [--half] [--swap] [--samples 8]
Needs ffmpeg on PATH.
"""
import argparse, subprocess, sys
import cv2
import numpy as np
import stereo_fix as sf


def read_frame_at(cap, idx):
    cap.set(cv2.CAP_PROP_POS_FRAMES, idx)
    ok, frame = cap.read()
    return frame if ok else None


def analyse(wide_path, ultra_path, samples, swap, window):
    cw, cu = cv2.VideoCapture(wide_path), cv2.VideoCapture(ultra_path)
    n = int(min(cw.get(cv2.CAP_PROP_FRAME_COUNT), cu.get(cv2.CAP_PROP_FRAME_COUNT)))
    idxs = np.linspace(n * 0.1, n * 0.9, samples).astype(int)
    PR, PM, BASE, pairs = [], [], [], []
    ushape = None
    for i in idxs:
        w, u = read_frame_at(cw, i), read_frame_at(cu, i)
        if w is None or u is None:
            continue
        ushape = u.shape
        pr, pm = sf.detect_matches(w, u, 1600)
        if len(pr) < 20:
            continue
        M, inl = cv2.estimateAffinePartial2D(pm, pr, method=cv2.RANSAC, ransacReprojThreshold=0.03 * u.shape[1],
                                             maxIters=5000, confidence=0.999)
        if M is None:
            continue
        PR.append(pr); PM.append(pm); BASE.append(inl.ravel().astype(bool)); pairs.append((w, u))
    cw.release(); cu.release()
    if not PR:
        raise RuntimeError("no usable frames for alignment")
    pr, pm, base = np.vstack(PR), np.vstack(PM), np.concatenate(BASE)
    model, use, vres, pmc = sf.refine_alignment(pr, pm, base, ushape, True)
    d = sf.horizontal_offsets(pr, pmc, model["a"], model["b"])[use]
    wide_left = not swap
    tx = float(np.percentile(d, window if wide_left else 100 - window))

    # Maps, valid area and colour LUTs from the sampled frames.
    h, w = pairs[0][0].shape[:2]
    a, b, ty, k, c, R = (model[x] for x in ("a", "b", "ty", "k", "c", "R"))
    K = np.float64([[R, 0, c[0]], [0, R, c[1]], [0, 0, 1]])
    newK = np.float64([[R * a, -R * b, a * c[0] - b * c[1] + tx], [R * b, R * a, b * c[0] + a * c[1] + ty], [0, 0, 1]])
    map1, map2 = cv2.initUndistortRectifyMap(K, np.float64([k, 0, 0, 0, 0]), np.eye(3), newK, (w, h), cv2.CV_16SC2)
    valid = cv2.remap(np.full(ushape[:2], 255, np.uint8), map1, map2, cv2.INTER_NEAREST)
    valid = cv2.erode(valid, np.ones((5, 5), np.uint8)) == 255
    x0, y0, x1, y1 = sf.largest_valid_rect(valid.astype(np.uint8))
    hs = np.zeros((3, 256)); hr = np.zeros((3, 256))
    for wf, uf in pairs:
        warped = cv2.remap(uf, map1, map2, cv2.INTER_LINEAR)
        for ch in range(3):
            hs[ch] += np.bincount(warped[..., ch][valid], minlength=256)
            hr[ch] += np.bincount(wf[..., ch][valid], minlength=256)
    luts = []
    for ch in range(3):
        s_cdf = np.cumsum(hs[ch]) / hs[ch].sum(); r_cdf = np.cumsum(hr[ch]) / hr[ch].sum()
        luts.append(np.interp(s_cdf, r_cdf, np.arange(256)).clip(0, 255).astype(np.uint8))
    info = dict(frames_used=len(pairs), matches=int(len(pr)), inliers=int(use.sum()),
                scale=float(np.hypot(a, b)), rotation_deg=float(np.degrees(np.arctan2(b, a))), ty=float(ty),
                k=float(k), tx=tx, vertical_error_median=float(np.median(np.abs(vres[use]))),
                crop=(x0, y0, x1, y1), wide_left=wide_left)
    return map1, map2, (x0, y0, x1, y1), np.stack(luts), info


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("wide"); ap.add_argument("ultra"); ap.add_argument("out")
    ap.add_argument("--cross", action="store_true", help="right|left layout for cross-eyed viewing")
    ap.add_argument("--half", action="store_true", help="half-width SBS (3D TV format, same size as one eye)")
    ap.add_argument("--swap", action="store_true", help="ultrawide is the left eye")
    ap.add_argument("--samples", type=int, default=8, help="frame pairs used for alignment")
    ap.add_argument("--window", type=float, default=98.0)
    ap.add_argument("--crf", type=int, default=18)
    args = ap.parse_args()

    map1, map2, (x0, y0, x1, y1), luts, info = analyse(args.wide, args.ultra, args.samples, args.swap, args.window)
    print("alignment:", info)
    cw, cu = cv2.VideoCapture(args.wide), cv2.VideoCapture(args.ultra)
    fps = cw.get(cv2.CAP_PROP_FPS) or 30.0
    ew, eh = (x1 - x0) & ~1, (y1 - y0) & ~1
    out_w = ew if args.half else ew * 2
    out_w &= ~1
    cmd = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
           "-f", "rawvideo", "-pix_fmt", "bgr24", "-s", f"{out_w}x{eh}", "-r", f"{fps:.6f}", "-i", "-",
           "-i", args.wide, "-map", "0:v", "-map", "1:a?", "-c:v", "libx264", "-crf", str(args.crf),
           "-preset", "medium", "-pix_fmt", "yuv420p", "-c:a", "copy", "-shortest", args.out]
    ff = subprocess.Popen(cmd, stdin=subprocess.PIPE)
    frames = 0
    while True:
        okw, wf = cw.read(); oku, uf = cu.read()
        if not (okw and oku):
            break
        warped = cv2.remap(uf, map1, map2, cv2.INTER_CUBIC)
        for ch in range(3):
            warped[..., ch] = luts[ch][warped[..., ch]]
        wc = wf[y0:y0 + eh, x0:x0 + ew]; uc = warped[y0:y0 + eh, x0:x0 + ew]
        left, right = (wc, uc) if info["wide_left"] else (uc, wc)
        a_, b_ = (right, left) if args.cross else (left, right)
        sbs = np.hstack([a_, b_])
        if args.half:
            sbs = cv2.resize(sbs, (out_w, eh), interpolation=cv2.INTER_AREA)
        ff.stdin.write(np.ascontiguousarray(sbs).tobytes())
        frames += 1
    ff.stdin.close(); ff.wait()
    print(f"wrote {frames} frames, {out_w}x{eh} @ {fps:.2f} fps -> {args.out}")
    sys.exit(ff.returncode)


if __name__ == "__main__":
    main()
