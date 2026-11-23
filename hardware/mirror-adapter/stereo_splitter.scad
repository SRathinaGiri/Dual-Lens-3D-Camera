// Four-mirror stereo splitter holder for a single phone camera
// Target: Redmi Note 12 Pro 5G main camera (5.59 mm lens, 8.192 x 6.144 mm sensor).
//
// Coordinate frame (assembly):
//   X = stereo baseline axis. Align with the phone's LONG edge (the sensor's long side).
//   Y = mirror width axis (the phone's short edge).
//   Z = camera optical axis; z = 0 is the camera cover glass, the holder bottom sits there.
// The whole optical cross-section lives in the XZ plane and is extruded along Y, so the
// body prints standing on its fixed end cap with every mirror seat vertical (no supports).
//
// Light path (left eye; right is mirrored): lens -> inner mirror (45 deg V) -> outer mirror
// (turned TOE deg from 45 so each half-field looks straight ahead) -> scene.
//
// The layout is solved here from first principles (chief ray + footprint corner rays) and
// was verified against a separate 3D ray trace (design.py / verify_profile.py) for the
// presets in LAYOUTS. Other combinations render, but check the console for warnings.

/* [Mirror set] */
// Which mirrors you bought
preset = "custom3"; // [cart:50x50x6 outer + 25x25x3 inner, custom3:55x66x3 outer + 20x20x3 inner, custom2:55x66x3 outer + 20x20x2 inner]
// Stereo baseline in mm (distance between the two eyes)
baseline = 55; // [50:5:65]

/* [Output] */
// What to render
part = "assembly"; // [assembly, body, cap, profile, glass2d]

/* [Camera] */
focal_mm = 5.59;
sensor_w = 8.192;           // along X (baseline)
sensor_h = 6.144;           // along Y
f_number = 1.9;
pupil_depth = 1.0;          // entrance pupil below the cover glass (estimate)

/* [Fit] */
glue_gap = 0.15;            // clearance between glass and printed seats
wall = 2.4;                 // backer / roof wall thickness
base_t = 2.0;               // bottom plate thickness (sits on the camera glass)
cap_t = 2.0;                // end cap thickness
lip = 1.5;                  // how far end lips overlap the outer mirror face
lip_h = 1.2;                // how far lips stand proud of the outer mirror face
end_t = 1.6;                // end-stop wall thickness at mirror ends
min_glass_z = 0.6;          // lowest outer-mirror glass point above the phone
lens_hole_d = 10;           // hole over the main lens
lens_ring_d = 0;            // optional socket for a raised lens ring (0 = off)
lens_ring_h = 0.8;
base_pad_w = 28;            // width (X) of the solid mounting pad under the lens, full length in Y

$fn = 64;

// ---------------------------------------------------------------------------
// Data
// ---------------------------------------------------------------------------
// preset -> [outer_len (tilted side), outer_width (Y), outer_t, inner_side, inner_t]
SETS = [
    ["cart",    50, 50, 6, 25, 3],
    ["custom3", 55, 66, 3, 20, 3],
    ["custom2", 55, 66, 3, 20, 2],
];
// [preset, baseline] -> [apex height above glass, usable field fraction]
// From the ray-trace search: largest field with zero clipping.
LAYOUTS = [
    ["cart", 55, 4.0, 0.72], ["cart", 60, 4.5, 0.76], ["cart", 65, 4.0, 0.74],
    ["custom3", 50, 3.5, 0.84], ["custom3", 55, 3.5, 0.84], ["custom3", 60, 3.5, 0.84],
    ["custom2", 50, 3.5, 0.84], ["custom2", 55, 3.5, 0.84], ["custom2", 60, 3.5, 0.84],
];

function lookup_set(p) = [for (s = SETS) if (s[0] == p) s][0];
function lookup_layout(p, b) = [for (l = LAYOUTS) if (l[0] == p && l[1] == b) l][0];

S = lookup_set(preset);
assert(S != undef, str("Unknown preset ", preset));
OUT_L = S[1]; OUT_W = S[2]; OUT_T = S[3]; IN_S = S[4]; IN_T = S[5];
LAY = lookup_layout(preset, baseline);
APEX = LAY == undef ? 4.0 : LAY[2];
FIELD = LAY == undef ? 0.7 : LAY[3];
if (LAY == undef) echo("WARNING: no verified layout for this preset/baseline; using apex 4.0, field 0.70");

// ---------------------------------------------------------------------------
// Optics (2D, XZ plane, left half). Vectors are [x, z].
// ---------------------------------------------------------------------------
TH_C = 18;                                   // centre of each eye's half-field (deg)
HX = atan(sensor_w / 2 / focal_mm);          // 36.2
HY = atan(sensor_h / 2 / focal_mm);          // 28.8
PUPIL = focal_mm / f_number;
PHI_IN = 135;                                // inner mirror line angle
TOE = TH_C / 2;                              // outer mirror turned this much from 45 deg
PHI_OUT = 135 - TOE;                         // 126
WX = TH_C * FIELD;
HYU = atan(tan(HY) * FIELD);

function dir(a) = [cos(a), sin(a)];
function dot2(a, b) = a[0] * b[0] + a[1] * b[1];
function len2(a) = sqrt(dot2(a, a));
// Intersection of p + t v with q + s u.
function lint(p, v, q, u) =
    let(det = -v[0] * u[1] + u[0] * v[1],
        d = q - p,
        t = (-d[0] * u[1] + u[0] * d[1]) / det)
    p + t * v;

O = [0, -pupil_depth];                       // entrance pupil centre
A = [0, APEX];                               // apex of the inner V (coated edges meet)
D_IN = dir(PHI_IN);
D_OUT = dir(PHI_OUT);
P1 = lint(O, dir(90 + TH_C), A, D_IN);       // chief ray on inner mirror
V1 = dir(2 * PHI_IN - (90 + TH_C));          // after inner reflection
P2 = lint(P1, V1, [-baseline / 2, 0], [0, 1]); // chief ray on outer mirror, x = -B/2
N_OUT0 = [-D_OUT[1], D_OUT[0]];
N_OUT = dot2(N_OUT0, V1) > 0 ? -N_OUT0 : N_OUT0;  // coated face normal (toward light)
BACK_OUT = -N_OUT;

// Footprint of the used field on the outer mirror (along-line s from P2, and half-height y).
FOOT = [
    for (i = [0:12]) for (px = [-PUPIL / 2, 0, PUPIL / 2])
        let(thx = TH_C - WX + 2 * WX * i / 12,
            p = [px, -pupil_depth],
            v = dir(90 + thx),
            h1 = lint(p, v, A, D_IN))
        if (h1[0] <= 0)
            let(v1 = dir(2 * PHI_IN - (90 + thx)),
                h2 = lint(h1, v1, P2, D_OUT),
                lxz = len2(h1 - p) + len2(h2 - h1))
            [dot2(h2 - P2, D_OUT), PUPIL / 2 + tan(HYU) * lxz * cos(thx), dot2(h1 - A, D_IN)]
];
F_LO = min([for (f = FOOT) f[0]]);
F_HI = max([for (f = FOOT) f[0]]);
F_Y = max([for (f = FOOT) f[1]]);
IN_USED = max([for (f = FOOT) f[2]]);

// Outer mirror placement: centre it on the footprint, then lift it until the glass clears the phone.
S_CENTRE = (F_LO + F_HI) / 2 - OUT_L / 2;
S_LIFT = (min_glass_z - P2[1] - min(0, BACK_OUT[1] * OUT_T)) / D_OUT[1];
S0 = max(S_CENTRE, S_LIFT);

echo(str("preset=", preset, " baseline=", baseline, " apex=", APEX, " field=", FIELD,
         " (~", round(2048 * FIELD * 3072 * FIELD / 1e5) / 10, " MP/eye)"));
echo(str("P2=", P2, " outer s0=", S0, " footprint s=", F_LO, "..", F_HI, " y=+-", F_Y,
         " inner used to s=", IN_USED));
if (S0 + lip > F_LO) echo("WARNING: lower outer lip shadows the image; reduce field or raise apex");
if (S0 + OUT_L - lip < F_HI) echo("WARNING: outer mirror too short for this field");
if (F_Y > OUT_W / 2 - 0.5) echo("WARNING: outer mirror too narrow for this field");
if (IN_USED > IN_S - 0.5) echo("WARNING: inner mirror too short for this field");

// ---------------------------------------------------------------------------
// 2D geometry helpers (all in XZ)
// ---------------------------------------------------------------------------
// Rectangle in a mirror's local frame: s along line dir d from origin o, t along normal n.
module local_rect(o, d, n, s0, s1, t0, t1) {
    polygon([o + d * s0 + n * t0, o + d * s1 + n * t0, o + d * s1 + n * t1, o + d * s0 + n * t1]);
}

// Inner plates. Left plate: coated face from A along D_IN, its end face lies on the right
// mirror's line. Right plate: starts IN_T along its line so its end butts the left plate's back.
D_R = [-D_IN[0], D_IN[1]];                   // 45 deg
N_IN_L = [D_IN[1], -D_IN[0]];                // [0.707,-0.707]? choose the one facing the lens
N_L = dot2(N_IN_L, [0, 1]) < 0 ? N_IN_L : -N_IN_L;   // coated normal (faces lens, -z side)
N_R = [-N_L[0], N_L[1]];
BACK_L = -N_L;
BACK_R = -N_R;

module inner_left_glass2d() { local_rect(A, D_IN, N_L, 0, IN_S, -IN_T, 0); }
module inner_right_glass2d() { local_rect(A, D_R, N_R, IN_T, IN_T + IN_S, -IN_T, 0); }
module outer_left_glass2d() { local_rect(P2, D_OUT, N_OUT, S0, S0 + OUT_L, -OUT_T, 0); }

module glass2d() {
    inner_left_glass2d();
    inner_right_glass2d();
    outer_left_glass2d();
    mirror([1, 0]) outer_left_glass2d();
}

L1B = A + D_IN * IN_S + BACK_L * IN_T;       // left plate, outer back corner
R1B = A + D_R * (IN_T + IN_S) + BACK_R * IN_T;
W0 = A + D_R * IN_T + BACK_R * IN_T;         // where both back faces meet
WEDGE_TOP = max(L1B[1], R1B[1]) + wall;

module wedge2d() {
    polygon([W0, R1B, [R1B[0], WEDGE_TOP], [L1B[0], WEDGE_TOP], L1B]);
    // End stops just past each plate's outer end (no lip in front of the coated face).
    local_rect(A, D_IN, N_L, IN_S, IN_S + end_t, -IN_T - wall, 0);
    local_rect(A, D_R, N_R, IN_T + IN_S, IN_T + IN_S + end_t, -IN_T - wall, 0);
}

module outer_seat_left2d() {
    tb = -OUT_T - glue_gap;                  // back of glass (with glue gap)
    local_rect(P2, D_OUT, N_OUT, S0 - end_t, S0 + OUT_L + end_t, tb - wall, tb);       // backer
    local_rect(P2, D_OUT, N_OUT, S0 - end_t, S0 - glue_gap, tb - wall, lip_h);          // low end stop
    local_rect(P2, D_OUT, N_OUT, S0 + OUT_L + glue_gap, S0 + OUT_L + end_t, tb - wall, lip_h); // high end stop
    local_rect(P2, D_OUT, N_OUT, S0 - end_t, S0 + lip, glue_gap, lip_h);                 // low lip
    local_rect(P2, D_OUT, N_OUT, S0 + OUT_L - lip, S0 + OUT_L + end_t, glue_gap, lip_h); // high lip
}

// Lowest end of the outer backer, used to tie it to the base plate.
OUT_LOW_IN = P2 + D_OUT * (S0 - end_t);
OUT_LOW_BK = OUT_LOW_IN + N_OUT * (-OUT_T - glue_gap - wall);
X_EXT = max(abs(OUT_LOW_BK[0]), abs((P2 + D_OUT * (S0 + OUT_L + end_t) + N_OUT * (-OUT_T - glue_gap - wall))[0]));

module base2d() {
    // Bottom plate from the inner edge of one outer backer's foot to the other.
    x0 = abs(OUT_LOW_IN[0]) + 1;
    translate([-x0, 0]) square([2 * x0, base_t]);
}

module outer_foot_left2d() {
    // Web from the backer's lower end down to the base plate.
    hull() {
        local_rect(P2, D_OUT, N_OUT, S0 - end_t, S0 + 6, -OUT_T - glue_gap - wall, -OUT_T - glue_gap);
        translate([OUT_LOW_IN[0] - 1, 0]) square([2, base_t]);
        translate([OUT_LOW_BK[0], 0]) square([0.01, base_t]);
    }
}

module profile2d() {
    difference() {
        union() {
            base2d();
            wedge2d();
            outer_seat_left2d();
            mirror([1, 0]) outer_seat_left2d();
            outer_foot_left2d();
            mirror([1, 0]) outer_foot_left2d();
        }
        offset(delta = glue_gap) glass2d();
        // Lens window through the base (2D cut is through the whole extrusion; the 3D hole is
        // round and added in body(); here keep a slot so the 2D check sees the light path).
        translate([-lens_hole_d / 2, -1]) square([lens_hole_d, base_t + 2]);
    }
}

// End cap outline: everything the profile covers, closed, minus the base strip.
module cap2d() {
    difference() {
        hull() profile2d();
        translate([-500, -1]) square([1000, base_t + 1]);
    }
}

// ---------------------------------------------------------------------------
// 3D
// ---------------------------------------------------------------------------
W = OUT_W + 2 * glue_gap;                    // inside width between end caps

module extrude_y(h, y0) {
    // 2D (x, z) -> 3D, occupying y in [y0, y0 + h]
    translate([0, y0 + h, 0]) rotate([90, 0, 0]) linear_extrude(height = h) children();
}

module body() {
    difference() {
        union() {
            // Profile without the lens slot: the base is solid except the round lens hole.
            extrude_y(W, -W / 2) difference() {
                union() {
                    base2d(); wedge2d();
                    outer_seat_left2d(); mirror([1, 0]) outer_seat_left2d();
                    outer_foot_left2d(); mirror([1, 0]) outer_foot_left2d();
                }
                offset(delta = glue_gap) glass2d();
            }
            // Fixed end cap (print bed side) at -Y.
            extrude_y(cap_t, -W / 2 - cap_t) cap2d();
            // Mounting pad: base plate continues under both end caps.
            extrude_y(W + 2 * cap_t, -W / 2 - cap_t)
                translate([-base_pad_w / 2, 0]) square([base_pad_w, base_t]);
            // Inner plate spacers: hold the narrower inner mirrors centred in Y from the fixed cap.
            sp = (W - IN_S) / 2 - glue_gap;
            if (sp > 0.4) extrude_y(sp, -W / 2) {
                inner_left_glass2d();
                inner_right_glass2d();
            }
        }
        // Round lens hole and optional ring socket.
        translate([0, 0, -1]) cylinder(d = lens_hole_d, h = base_t + 2);
        if (lens_ring_d > 0) translate([0, 0, -0.01]) cylinder(d = lens_ring_d, h = lens_ring_h);
    }
}

module cap() {
    // Removable end cap (+Y side). Glue it on after the mirrors are in.
    extrude_y(cap_t, W / 2) cap2d();
}

module glass3d() {
    color("silver", 0.6) {
        extrude_y(IN_S, -IN_S / 2) { inner_left_glass2d(); inner_right_glass2d(); }
        extrude_y(OUT_W, -OUT_W / 2) { outer_left_glass2d(); mirror([1, 0]) outer_left_glass2d(); }
    }
}

if (part == "assembly") {
    color("dimgray") body();
    color("gray") cap();
    glass3d();
    // Phone camera glass reference
    %translate([0, 0, -0.5]) cylinder(d = 12, h = 0.5);
} else if (part == "body") {
    // Print orientation: fixed end cap flat on the bed, mirror seats vertical.
    rotate([90, 0, 0]) translate([0, W / 2 + cap_t, 0]) body();
} else if (part == "cap") {
    rotate([90, 0, 0]) translate([0, -W / 2, 0]) cap();
} else if (part == "profile") {
    profile2d();
} else if (part == "glass2d") {
    glass2d();
}
