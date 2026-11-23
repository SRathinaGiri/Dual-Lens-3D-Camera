"""Ray-check the OpenSCAD holder profile for one preset/baseline.

Renders the 2D cross-section (part="profile") and the glass outline (part="glass2d") with
OpenSCAD, reads the solved layout from its ECHO lines, then traces the used field of both
eyes through the cross-section and reports any ray that is blocked or misses a mirror.

All parts are extrusions along Y, so blocking is fully decided in the XZ plane; Y coverage is
checked from the 3D ray slope.

Usage: python verify_profile.py [preset] [baseline] [openscad.com path]
"""
import math, re, subprocess, sys, tempfile, pathlib

HERE = pathlib.Path(__file__).resolve().parent
SCAD = HERE / "stereo_splitter.scad"
preset = sys.argv[1] if len(sys.argv) > 1 else "custom3"
baseline = float(sys.argv[2]) if len(sys.argv) > 2 else 55
OPENSCAD = sys.argv[3] if len(sys.argv) > 3 else r"D:\OpenSCAD\openscad.com"

SETS = {"cart": (50, 50, 6, 25, 3), "custom3": (55, 66, 3, 20, 3), "custom2": (55, 66, 3, 20, 2)}
OUT_L, OUT_W, OUT_T, IN_S, IN_T = SETS[preset]
F, SW, SH, FNUM, PD = 5.59, 8.192, 6.144, 1.9, 1.0
PUPIL = F / FNUM
LIP, LIP_H, GLUE = 1.5, 1.2, 0.15
TH_C = 18.0
HY = math.degrees(math.atan(SH / 2 / F))


def run(part, out):
    cmd = [OPENSCAD, "-o", str(out), "-D", f'part="{part}"', "-D", f'preset="{preset}"',
           "-D", f"baseline={baseline:g}", str(SCAD)]
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit(r.stdout + r.stderr)
    return r.stdout + r.stderr


def svg_loops(path):
    txt = pathlib.Path(path).read_text()
    loops = []
    for d in re.findall(r'd="([^"]+)"', txt):
        for sub in re.split(r"[Mm]", d):
            nums = re.findall(r"-?\d+(?:\.\d+)?(?:e-?\d+)?", sub)
            pts = [(float(nums[i]), -float(nums[i + 1])) for i in range(0, len(nums) - 1, 2)]
            if len(pts) >= 3:
                loops.append(pts)
    return loops


def seg_cross(p, q, a, b):
    def orient(o, s, t):
        return (s[0] - o[0]) * (t[1] - o[1]) - (s[1] - o[1]) * (t[0] - o[0])
    d1, d2 = orient(a, b, p), orient(a, b, q)
    d3, d4 = orient(p, q, a), orient(p, q, b)
    return (d1 > 0) != (d2 > 0) and (d3 > 0) != (d4 > 0)


def blocked(p, q, loops):
    for loop in loops:
        for i in range(len(loop)):
            if seg_cross(p, q, loop[i], loop[(i + 1) % len(loop)]):
                return True
    return False


def lint(p, v, q, u):
    det = -v[0] * u[1] + u[0] * v[1]
    dx, dz = q[0] - p[0], q[1] - p[1]
    t = (-dx * u[1] + u[0] * dz) / det
    return (p[0] + t * v[0], p[1] + t * v[1])


def d(a):
    return (math.cos(math.radians(a)), math.sin(math.radians(a)))


def refl_dir(alpha, phi):
    return 2 * phi - alpha


with tempfile.TemporaryDirectory() as tmp:
    prof = pathlib.Path(tmp) / "profile.svg"
    glas = pathlib.Path(tmp) / "glass.svg"
    log = run("profile", prof)
    run("glass2d", glas)
    solid = svg_loops(prof)
    glass = svg_loops(glas)

m = re.search(r"apex=([\d.]+) field=([\d.]+)", log)
APEX, FIELD = float(m.group(1)), float(m.group(2))
m = re.search(r"P2=\[([-\d.e]+), ([-\d.e]+)\] outer s0=([-\d.e]+)", log)
P2 = (float(m.group(1)), float(m.group(2)))
S0 = float(m.group(3))
warnings = [l for l in log.splitlines() if "WARNING" in l]

A = (0.0, APEX)
WX = TH_C * FIELD
HYU = math.degrees(math.atan(math.tan(math.radians(HY)) * FIELD))
PHI_IN, PHI_OUT = 135.0, 135.0 - TH_C / 2
y_limit_out = OUT_W / 2
y_limit_in = IN_S / 2

stats = dict(rays=0, blocked_solid=0, blocked_glass=0, miss_inner=0, miss_outer=0, seam=0)
worst = dict(y_out=0.0, y_in=0.0)
for side in (-1, 1):                       # -1 = left eye, +1 = right eye
    for i in range(41):
        thx = TH_C - WX + 2 * WX * i / 40
        for k in range(9):
            px = -PUPIL / 2 + PUPIL * k / 8
            stats["rays"] += 1
            # Solve on the left and mirror x for the right eye.
            p = (px * -side if side == 1 else px, -PD)
            pl = (-p[0], p[1]) if side == 1 else p
            v = d(90 + thx)
            h1 = lint(pl, v, A, d(PHI_IN))
            if h1[0] > 0:
                continue                   # this ray belongs to the other eye
            s_in = (h1[0] - A[0]) * d(PHI_IN)[0] + (h1[1] - A[1]) * d(PHI_IN)[1]
            a1 = refl_dir(90 + thx, PHI_IN)
            h2 = lint(h1, d(a1), P2, d(PHI_OUT))
            s_out = (h2[0] - P2[0]) * d(PHI_OUT)[0] + (h2[1] - P2[1]) * d(PHI_OUT)[1]
            a2 = refl_dir(a1, PHI_OUT)
            exit_pt = (h2[0] + 120 * d(a2)[0], h2[1] + 120 * d(a2)[1])
            lxz = math.dist(pl, h1) + math.dist(h1, h2)
            y_out = PUPIL / 2 + math.tan(math.radians(HYU)) * lxz * math.cos(math.radians(thx))
            y_in = PUPIL / 2 + math.tan(math.radians(HYU)) * math.dist(pl, h1) * math.cos(math.radians(thx))
            worst["y_out"] = max(worst["y_out"], y_out)
            worst["y_in"] = max(worst["y_in"], y_in)
            # Right inner mirror starts IN_T along its line: rays below that hit the left plate's end.
            s_start = IN_T if side == 1 else 0.0
            if s_in < s_start:
                stats["seam"] += 1
                continue
            if s_in > s_start + IN_S - 0.3 or y_in > y_limit_in - 0.2:
                stats["miss_inner"] += 1
            if s_out < S0 + LIP or s_out > S0 + OUT_L - LIP or y_out > y_limit_out - 0.2:
                stats["miss_outer"] += 1
            segs = [(pl, h1), (h1, h2), (h2, exit_pt)]
            if side == 1:
                segs = [((-a[0], a[1]), (-b[0], b[1])) for a, b in segs]
            shrunk = []
            for a, b in segs:
                L = math.dist(a, b)
                u = ((b[0] - a[0]) / L, (b[1] - a[1]) / L)
                shrunk.append(((a[0] + u[0] * 0.05, a[1] + u[1] * 0.05), (b[0] - u[0] * 0.05, b[1] - u[1] * 0.05)))
            if any(blocked(a, b, solid) for a, b in shrunk):
                stats["blocked_solid"] += 1
            elif any(blocked(a, b, glass) for a, b in shrunk):
                stats["blocked_glass"] += 1

print(f"{preset} B={baseline:g} apex={APEX} field={FIELD}  rays={stats['rays']}")
print(f"  blocked by printed part: {stats['blocked_solid']}   blocked by glass edges: {stats['blocked_glass']}")
print(f"  miss inner mirror: {stats['miss_inner']}   miss outer mirror/lips: {stats['miss_outer']}   in seam: {stats['seam']}")
print(f"  max y on outer {worst['y_out']:.1f} (limit {y_limit_out})  on inner {worst['y_in']:.1f} (limit {y_limit_in})")
for w in warnings:
    print("  " + w.strip())
ok = stats["blocked_solid"] == stats["blocked_glass"] == stats["miss_inner"] == stats["miss_outer"] == 0 and not warnings
print("  RESULT:", "CLEAN" if ok else "PROBLEMS")
sys.exit(0 if ok else 1)
