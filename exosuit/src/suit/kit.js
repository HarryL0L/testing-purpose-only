// =====================================================================================
// GEOMETRY KIT — machined armour plates, shells, actuators, fasteners, wiring, energy lines
// =====================================================================================
// Region builders receive this module as ctx.kit. Everything here is a pure function of its
// arguments (no Math.random, no time, no hidden state): same call → same vertices.
//
// CONVENTIONS (apply to every function unless its entry says otherwise)
//   Units          metres; angles in radians (the few degree-valued options say "deg").
//   Geometry       builders marked →G return an indexed THREE.BufferGeometry with position,
//                  normal and uv (uv in metres along the surface). Normals are authored, not
//                  averaged: flats stay flat, bevels roll smoothly (they catch a highlight and
//                  trigger the materials' edge wear), creases stay crisp. Winding is always
//                  consistent with the normals. Geometries are yours to transform / merge.
//   Parts          builders marked →Group return a THREE.Group holding ONE pre-merged Mesh per
//                  material slot, with identity transforms (position / rotate the Group freely).
//   Materials      the kit never creates materials. Part builders take `mats` as their LAST
//                  argument: either the material library (ctx.mat — slots resolve by name, e.g.
//                  slot `steel` → mat.steel) or one single material for everything. Override or
//                  drop individual slots with opts.mats = { slot: material | null }.
//   SHEET frame    plates & anything "flat": lie in the XY plane, show face toward +Z with its
//                  surface at z = 0, thickness going down to z = -t. Width = X, height = Y.
//   AXIAL frame    lathes, cylinders, servos, hinges, coils…: axis along +Y. Pass
//                  axis: 'x' | 'y' | 'z' | '-x' | '-y' | '-z' to have the geometry re-aimed.
//   Angles about Y 0 = +Z (the suit's front), increasing toward +X (the suit's left) — the
//                  same sense as the --cam azimuth. Used by tubeShell / lathe arcs.
//   2D points      [x, y] or [x, y, r] where r = rounding radius of THAT corner (metres).
//                  Outlines may be listed clockwise or counter-clockwise.
//   3D points      [x, y, z] arrays or THREE.Vector3.
//   Brushing       the look-dev materials brush around each MESH's local Y axis; a dome whose
//                  pole lies on its mesh's local Y gets a "spun" highlight pinching at the pole.
//                  To avoid that, build the dome about another pole and rotate the Mesh instead
//                  (pole 'z' + mesh.rotation.x = -π/2 points the pole up; radii are then local).
//   Valid input    holes must lie inside their outline, clear of each other and of a `step`;
//                  bevels / steps / wall thickness must be smaller than the features they sit on.
//                  Bad input never throws, it just produces a damaged surface.
//
// TRIANGLE BUDGET (whole suit ≲ 450k tris, a few hundred draw calls)
//   plate ≈ 120–400 · bent / crowned plate ≈ 400–700 · tubeShell half ≈ 400 · domeShell ≈ 500–1300
//   bolt ≈ 24 (pan) … 106 · servo detail 0 / 1 / 2 ≈ 650 / 2000 / 3700 · piston ≈ 1200
//   hinge ≈ 950 · ballJoint ≈ 2000 · strut ≈ 700–1400 · loom(5 wires, 20 cm) ≈ 1500
//   Use count(object) while building, and merge(panel) when a panel is finished.
//
// -------------------------------------------------------------------------------------
// 1. OUTLINES — shape.* (all return arrays of [x, y, r?] points)
// -------------------------------------------------------------------------------------
//   shape.rect(w, h, r = 0, cx = 0, cy = 0)             r: number | [bl, br, tr, tl]
//   shape.chamferRect(w, h, cut = 0.01, r = 0, cx, cy)  45° clipped corners; cut: number | [bl, br, tr, tl]
//   shape.circle(r, cx = 0, cy = 0, n = auto)           smooth n-gon
//   shape.ellipse(rx, ry, cx = 0, cy = 0, n = auto)
//   shape.ngon(n, r, cx = 0, cy = 0, rot = 0, cornerR = 0)  regular polygon, first vertex at angle rot from +X
//   shape.slot(len, w, cx = 0, cy = 0, angle = 0)       stadium; len = overall length along X before rotation
//   shape.gear(teeth, r, depth = 0.12 r, cx = 0, cy = 0)   gear / spline outline (extrude with polyPlate, bore via holes)
//   shape.stroke(points, width, endR = 0)               outline of a thick polyline (mitred); endR = width/2 → round ends
//   shape.inset(pts, d)                                 outline offset inward by d (d < 0 grows it); rounded corners stay
//                                                       concentric → inlays / second-colour plates that nest in a `step` pocket
//   shape.split(pts, a, b, gap = 0.0015, r = 0)         cut along the line a → b: returns [left, right] outlines separated
//                                                       by `gap` — build both with polyPlate() for a real, bevelled panel seam
//                                                       (then warp the pieces together with a modifier, see section 4)
//   shape.move(pts, dx, dy) · shape.rotate(pts, angle) · shape.scale(pts, sx, sy = sx) · shape.mirror(pts)  (x → -x)
//
// -------------------------------------------------------------------------------------
// 2. PLATES  (SHEET frame: show face +Z at z = 0, back at z = -t)
// -------------------------------------------------------------------------------------
//   plate(opts) →G        rounded / clipped / tapered rectangle, centred on the origin.
//     w = 0.1, h = 0.1     size along X / Y
//     t = 0.006            thickness
//     r = 0.006            corner radius: number | [bl, br, tr, tl]
//     cut = 0              45° corner clip distance: number | [bl, br, tr, tl]
//     taper = 1            width of the top (+Y) edge ÷ width of the bottom edge
//     skew = 0             x shift of the top edge (parallelogram)
//     + every "sheet option" below
//
//   polyPlate(points, opts) →G   arbitrary outline [[x, y, r?], …]; origin = your coordinates.
//     r = 0                default corner radius for points that carry none
//     + every "sheet option" below
//
//   Sheet options (plate, polyPlate, grille; tubeShell / domeShell accept the marked ones*)
//     bevel = 0.0015 *     edge bevel on the show face (smooth-shaded roll-off). 0 = sharp.
//     bevelBack = min(bevel, 0.0008) *   chamfer height on the back edge
//     step *               { inset = 0.008, depth, wall = 0.7·|depth| } — an inner panel offset
//                          from the rim: depth > 0 sinks a pocket, depth < 0 raises a boss.
//                          Reads as a machined panel line. inset is measured from the outline.
//     holes *              [ outline, … ] through-cuts (same point format; bevelled like the rim)
//     holeR = 0 *          default corner radius for hole points that carry none
//     crown = 0            pillow the sheet: centre stays at z = 0, edge mid-points drop by `crown`
//     fold                 { at, angle, axis = 'y', width = 0.003, side, symmetric } or an array:
//                          a crisp crease along the line x = at (axis 'y') or y = at (axis 'x').
//                          The part beyond the line (the side away from the origin, or `side`
//                          = +1 / -1) swings back toward -Z by `angle` (negative = forward).
//                          symmetric: true leans both sides back by angle/2 (a keel).
//                          Keep |angle| < 1.4 when combining several folds.
//     bend                 R (number) or { radius | angle, axis = 'y', center, arcRes = 10 deg }:
//                          cylindrical curvature. axis 'y': curls about a line parallel to Y at
//                          (x = center, z = -R) — X becomes arc length; axis 'x': about a line
//                          parallel to X. R > 0 curls the edges back (-Z), R < 0 forward.
//                          `angle` = total wrap angle instead of a radius. center defaults to
//                          the outline's middle.  Order applied: crown → fold → bend.
//     segs = auto          [nx, ny] tessellation grid over the outline's bounding box (auto-sized
//                          for crown / bend). Set it (or `cell`) on FLAT geometry you intend to
//                          warp yourself afterwards with a modifier.
//     cell                 alternative to segs: maximum grid cell size in metres
//     xLines, yLines       extra tessellation lines at exact x / y coordinates
//     back = true *        false omits the back face (single-skin, cheaper; rim stays)
//     crease = 30 *        deg: outline corners sharper than this (with r = 0) shade as hard edges
//     detail = 1 *         multiplier for corner-arc segment counts;  cornerSegs: force a count
//
//   chamferBox(w, h, d, { bevel = min(1 mm, 20 % of the smallest side), r = bevel }) →G
//     box centred on the origin; the 4 edges parallel to Z are rounded by r, the other 8 bevelled.
//   grille(opts) →G        plate() with a row of through-slots: plate options plus
//     n = 6, margin = 0.007, slotW = half the pitch, dir = 'x' (slots run along X) | 'y'.
//
// -------------------------------------------------------------------------------------
// 3. SHELLS
// -------------------------------------------------------------------------------------
//   tubeShell(opts) →G     full or partial tapered elliptical tube wall about the Y axis.
//     profile              [[y, rx, rz = rx, cx = 0, cz = 0], …] ≥ 2 stations, any order: OUTER
//                          radii along X / Z at height y, optional centre offset — or the shorthand
//     y0 = 0, y1 | len, r0 = 0.05, r1 = r0   (r: number | [rx, rz]; r0 at y0, r1 at y1; len → y1 = y0 + len)
//     smooth = true        Catmull-Rom through ≥ 3 stations (false = straight segments)
//     power = 2            cross-section exponent: 2 = ellipse, 3–4 = squarer "squircle"
//     arc                  [a0, a1] angular range (angles about Y); omit for a closed tube
//     t = 0.005            wall thickness, measured INWARD from the given radii
//     bevel = 0.0012, bevelBack, step, back, detail, crease   as for sheets
//     — partial shells only —
//     r                    corner rounding in metres: number | [bottom·a0, bottom·a1, top·a1, top·a0]
//                          (bottom = the lower-y end, whatever order the stations were given in)
//     cut = 0              45° corner clips, same order
//     outline              [[angle, y, r?], …] replaces the rectangular patch
//     holes, holeR         [[angle, y, r?], …] cut-outs (r in metres)
//     segs                 [around, along];  arcRes = 11.25 deg per segment around
//     Origin: the tube axis is the Y axis; the shell sits exactly at the radii you gave.
//
//   domeShell(opts) →G     patch of an ellipsoid shell centred on the origin.
//     r | rx, ry, rz       OUTER semi-axes along X / Y / Z (of the result) — default 0.1
//     pole = 'z'           direction from the centre to the middle of the patch:
//                          'x' | 'y' | 'z' | '-x' | '-y' | '-z' | [x, y, z]
//     spanX = 1.2, spanY = spanX   total angular size of the patch (through the pole)
//     round = false        true → elliptical outline instead of a rounded rectangle
//     cornerR              corner radius in metres (default 30 % of the smaller side)
//     cut = 0              45° corner clips [bl, br, tr, tl]
//     outline, holes       custom flat outline [[x, y, r?], …] in metres of arc (1 m = 1 m on a
//                          sphere of radius Rref = (rx + ry + rz) / 3), centred on the pole
//     t = 0.005 (inward), bevel = 0.0012, bevelBack, step, back, detail, crease
//     cell = Rref · 12°    tessellation cell size (or arcRes in deg)
//     Flat +X / +Y of the patch map to (seen from outside, looking at the pole):
//       pole 'z': +X, +Y · '-z': -X, +Y · 'x': -Z, +Y · '-x': +Z, +Y · 'y': +X, -Z · '-y': +X, +Z
//
//   lathe(points, opts) →G   revolve a profile [[radius, y, cornerRadius?], …] about +Y.
//     Three profile modes:
//       default            the points are a CLOSED cross-section polygon (either winding). Edges
//                          lying on the axis are skipped, so list a solid from axis to axis.
//       t: thickness       the points are an OPEN polyline describing one surface; a wall of
//                          thickness t is grown toward the axis (t < 0: away from it; flat
//                          washer-like profiles grow toward -Y). rim = bevel of the 4 rim corners.
//       closed: false      OPEN single-sided skin facing the RIGHT of the direction of travel in
//                          the (radius, y) plane: list bottom → top for an outward-facing skin,
//                          top → bottom for an inward-facing bore, outside → in for an up-facing disc.
//     bevel = 0.0006       default corner radius for points that carry none (not in `t` mode,
//                          where only per-point radii and `rim` apply). Small radii become a
//                          single smooth-shaded chamfer.
//     arc                  [a0, a1] partial revolve (closed / thick profiles get flat end caps;
//                          caps: false omits them)
//     segs = 24            segments per FULL turn
//     facet = false        true → flat facets around the axis (hex nuts: segs 6, facet true)
//     crease = 30 deg, detail, axis = 'y'
//
//   cylinder(opts) →G      bevelled cylinder / cone / tube about +Y.
//     r = 0.01, r1 = r (radius at the +Y end), len = 0.02, y0 = -len/2 (centred by default),
//     bevel, bevel1 = bevel (the +Y end), hole = 0 (bore radius → tube), segs = auto, arc, facet, axis
//   bellows(opts) →G       rubber gaiter (open outward-facing skin) about +Y.
//     r0 = 0.02 (at y0), r1 = r0, len = 0.04, folds = 5, depth = 22 % of the radius, y0 = -len/2, segs = 20, arc, axis
//
// -------------------------------------------------------------------------------------
// 4. MODIFIERS — warp finished geometry; normals are re-derived exactly from the warp
// -------------------------------------------------------------------------------------
//   Every modifier takes ONE geometry or an ARRAY of geometries, changes it in place and returns
//   its argument. Author a plate, its bolts and its energy lines flat, then warp them together:
//       const p = kit.polyPlate(outline, { t: 0.006, cell: 0.012 });
//       const s = kit.energyStrip([[0, -0.1], [0, 0.05]], { maxSeg: 0.01 });
//       const b = kit.boltsAt([[0.05, 0.1], [-0.05, 0.1]], { d: 0.006 });
//       kit.wrapTube([p, s, b], { profile, Rref: 0.06 });      // z = 0 lands on the tube surface
//   Geometry must already be tessellated finely enough to follow the warp (segs / cell / maxSeg).
//
//   deform(geo, fn)        generic: fn(v: THREE.Vector3) moves v in place (any smooth map,
//                          mirroring included)
//   bend(geo, { radius | angle, axis = 'y', center = 0 })   as the `bend` sheet option
//   crown(geo, { amount = 0.003, w, h, cx, cy })            w, h default to the bounding box
//   fold(geo, { at = 0, angle, axis = 'y', width = 0.003, side, symmetric })   as the `fold` option
//                          (needs tessellation lines at at ± width/2 — polyPlate's fold adds them)
//   taper(geo, { axis = 'y', from, to, scale: [s0, s1] })   scale the other two coordinates (about
//                          the axis through the origin) from s0 at `from` to s1 at `to`; scale
//                          entries may be [sa, sb] pairs.
//                          from / to default to the bounding box.
//   twist(geo, { axis = 'y', angle, from, to })             0 at `from` → angle at `to` (right-handed)
//   wrapTube(geo, opts)    wrap a flat SHEET-frame geometry onto a tube about Y: flat x → arc
//                          length, flat y → y, flat z → radial offset from the surface.
//                          opts: profile | y0, y1, r0, r1 · smooth · power (as tubeShell) ·
//                          at = 0 (angle where flat x = 0 lands) · Rref (metres of flat x per
//                          radian; default = mean station radius).  A tubeShell with arc
//                          [a0, a1] is exactly a plate wrapped with at = (a0 + a1) / 2.
//   wrapDome(geo, opts)    drape a flat SHEET-frame geometry over an ellipsoid (flat origin → pole,
//                          distances from the pole preserved). opts: r | rx, ry, rz · pole · Rref.
//                          A domeShell is exactly a plate draped with the same opts.
//   mirrorX(geo) →G        NEW geometry mirrored across the YZ plane (left ↔ right parts)
//
//   The warps as plain functions (for deform / chain / placeOn; all parameters explicit):
//     bendMap({ radius, axis, center }) · crownMap({ amount, w, h, cx, cy }) · foldMap(opts) ·
//     tubeMap(opts) · domeMap(opts) · chain(map, map, …)
//   placeOn(object, map, [x, y, z = 0], spin = 0)
//                          seat an Object3D (a servo, a vent…) on a warped sheet: position =
//                          map([x, y, z]); its +Z follows the warped surface normal, its +X the
//                          warped +X direction; spin turns it about +Z. Returns the object.
//
// -------------------------------------------------------------------------------------
// 5. FASTENERS  (heads sit on z = 0 and rise along +Z — the SHEET frame's show side)
// -------------------------------------------------------------------------------------
//   bolt(opts) →G
//     type = 'socket'      'socket' cap screw with hex recess · 'hex' head on a washer flange ·
//                          'button' domed, hex recess · 'flush' countersunk, 0.07 d proud ·
//                          'rivet' dome · 'pan' plain low head (cheap: 24 tris)
//     d = 0.005            head diameter (hex: across flats)
//     h                    head height (defaults: socket 0.6 d, hex 0.5 d, button 0.32 d, rivet 0.3 d, pan 0.26 d)
//     washer = false, axis = 'z'
//   boltsAt(points, opts) →G    one merged geometry with a bolt at every [x, y, z = 0].
//     bolt options + spin = true (each head turned by a different, deterministic angle), seed,
//     axis (re-aims the WHOLE pattern from +Z)
//   boltCircle(opts) →G    n = 6 bolts on a circle R = 0.02 in the XY plane; start = 0 (angle
//                          from +X), arc = 2π, center = [0, 0, 0]; + boltsAt options
//   boltRow(opts) →G       n = 4 bolts from `from` = [-0.03, 0, 0] to `to` = [0.03, 0, 0] inclusive
//
// -------------------------------------------------------------------------------------
// 6. MECHANISMS  (→Group, one mesh per material slot; `mats` = ctx.mat or a single material)
// -------------------------------------------------------------------------------------
//   servo(opts, mats)      rotary actuator, AXIAL frame, centred on the origin; output shaft at
//                          the +axis end. Finned stator barrel with copper windings, bolted end
//                          caps, splined shaft, status ring, cable gland.
//     r = 0.022 (body radius), len = 0.05 (body length, shaft excluded), axis = 'y'
//     detail = 1           0 plain barrel · 1 fins + pan screws · 2 macro (socket screws, finer);
//                          a servo is up to 6 meshes (one per slot) until its panel is merge()d
//     shaft = 0.45 r       shaft length beyond the cap boss (false = none)
//     fins, bolts (per cap), segs     override the detail presets
//     accent = true, gland = true, box = true (junction box under the gland, detail ≥ 1),
//     glandAngle = π (angle about the axis, 0 = +Z, before re-aiming)
//     slots: body→frame · cap→titaniumDark · steel · rubber · accent→energyDim · copper
//     result.userData.gland = { pos, dir }  cable exit point / direction in the servo's frame
//
//   piston(opts, mats)     linear actuator between two pivots, with ports and a hard line.
//     a = [0, 0, 0], b = [0, 0.2, 0]   barrel-end and rod-end pivot, in the PARENT's space
//     r                    barrel radius (default 4.5 % of |b − a|, clamped to 3…20 mm)
//     min, max             working length range (defaults 0.78 / 1.22 × |b − a|; min is at least
//                          6.5 r, max at most 2·min − 4.45 r so the rod never leaves the barrel)
//     pole                 direction the piston's local +Z (the side carrying the ports) should
//                          face; the pivot pins run along local X. Default +Z.
//     ports = true, segs;  slots: body→titaniumDark · steel · rod→steel
//     Returns a Group (flagged dynamic) positioned at a and aimed at b, with
//       piston.aim(a: Vector3, b: Vector3)   re-aim every frame (parent-space points; no
//                          allocations; rod travel clamps to [min, max]);  returns the piston
//       piston.userData.piston = { min, max, rod }
//
//   hinge(opts, mats)      barrel hinge, AXIAL frame, centred on the origin.
//     len = 0.06, r = 0.004 (barrel radius), knuckles = 5, gap, axis = 'y'
//     leaf = 3.2 r         leaf width beyond the barrel (0 = barrel only); leafT = 0.45 r
//                          Leaf A extends toward +X, leaf B toward -X; show faces toward +Z,
//                          undersides at z = -r (seat that plane on the parts being joined).
//     angle = 0            swing of leaf B about the pin toward +Z (0 = laid flat)
//     half = 'both'        'a' = even knuckles + pin + leaf A, 'b' = odd knuckles + leaf B — build
//                          the two halves separately and parent them to the two moving sides
//     slots: body→titaniumDark · pin→steel
//
//   ballJoint(opts, mats)  ball stud in a bolted socket; origin = ball centre, stud along +axis.
//     r = 0.012 (ball radius), stud = 1.5 r (length above the ball), bolts = 4, boot = false
//     half = 'both' | 'socket' | 'ball', axis = 'y';  socket flange: radius ≈ 2 r at y ≈ -0.3 r
//     slots: socket→frame · ball→steel · boot→rubber
//
//   strut(opts) →G         inner-frame member with a pivot lug at each end; lies in the XY plane,
//                          pivots at (0, y0, 0) and (0, y1, 0), pivot axes along Z.
//     len = 0.2 | y0 = 0, y1     (y1 < y0 is fine: a limb bone hanging down)
//     w = 0.03 | w0, w1    width along X at y0 / y1
//     t = 0.012            depth along Z (lugs are 18 % longer)
//     section = 'I'        'I' web + flanges · 'flat' solid bar · 'tube' round tube with collars
//     holes = 'round'      lightening cut-outs: 'round' | 'slot' | 'truss' | false
//     web, flange = 0.0028, lug = 0.56 w0 (radius; 0 = none), lug1, bore = 0.42 (× lug radius)
//   bone(a, b, opts, material) → Mesh   a strut() spanning point a → b (parent space);
//                          opts = strut options + pole = [1, 0, 0] (direction of the pivot axes)
//
//   vent(opts, mats)       framed louvre vent facing +Z: front face at z = 0, body down to
//                          z = -depth (sink it into a polyPlate `holes` cut-out or stand it proud).
//     w = 0.08, h = 0.04, depth = 0.008, slats = 5, angle = 50 (deg, slat tilt out of the face),
//     cover = 0.8, frame = 0.005, r = 0.004, bevel = 0.001, dir = 'x' (slats along X) | 'y', axis = 'z'
//     slots: body→titaniumDark · back→rubber (dark backing; null = see-through)
//   fins(opts) →G          heat sink: base on z = 0, fins rising to z = d, running along Y.
//     w = 0.05 (across the fins), h = 0.04 (along), d = 0.012, n = 8, base, finT, axis = 'z'
//   connector(opts, mats)  multi-pin connector: mounting face z = 0, mating face at z = d.
//     w = 0.022, h = 0.011, d = 0.012, pins = [6, 2], flange = true, axis = 'z'
//     slots: shell→titaniumDark · body→frame · pin→copper
//
// -------------------------------------------------------------------------------------
// 7. WIRING
// -------------------------------------------------------------------------------------
//   cable(points, opts) →G   round cable through ≥ 2 control points (smooth spline).
//     r = 0.003, sag = 0 (droop at mid-length, metres, along sagDir = [0, -1, 0])
//     straight = false     true → straight runs joined by bends of radius bendR = 3 r (hard lines)
//     radial = 7, maxAngle = 7 (deg per segment), caps = true, closed = false
//     result.userData.ends = [{ pos, dir }, { pos, dir }]   end points, dir pointing outward
//   hose(points, opts, mats) →Group   cable + a hex ferrule on each end.
//     cable options, r = 0.004;  slots: hose→rubber · fitting→steel
//   ferrule({ r = 0.003 }) →G   hose end fitting about +Y; hose enters from -Y, nut face at y = 0
//   loom(points, opts, mats) →Group   bundle of wires along a path, held by tie bands.
//     n = 5 wires, r = 0.0018 (wire radius), twist = 0.6 (turns over the length),
//     tie = 0.045 (band spacing; 0 = none), accentEvery = 3 (every 3rd wire uses `accent`; 0 = none),
//     sag, sagDir, radial = 5, maxAngle;  slots: wire · accent→wireTeal · tie→rubber
//     result.userData.radius = bundle radius
//   coil(opts) →G          helical winding about +Y, centred.
//     r = 0.01 (helix radius to the wire centre), r1 (end radius → conical), len = 0.03,
//     wire = 0.0012 (wire radius), turns (default: tightly wound), perTurn = 14, radial = 5, y0, axis
//
// -------------------------------------------------------------------------------------
// 8. ENERGY ACCENTS  (use mat.energy / mat.energyDim; set mesh.userData.castShadow = false)
// -------------------------------------------------------------------------------------
//   energyStrip(points, opts) →G   thin raised strip along a polyline lying ON a surface, with
//                          mitred corners and rounded ends. Points [x, y, z = 0].
//     w = 0.004, h = 0.0008 (height above the surface), sink = 0.0004 (how far it digs in)
//     up = [0, 0, 1]       surface normal — or normals: [[x, y, z], …] one per point
//     ends = 'round' | 'square', closed = false, chamfer, endSegs = 6
//     maxSeg = 0.02        long runs are subdivided to this length so the strip can be warped
//                          Tips: on a `step` pocket floor translate the strip by -depth first;
//                          built with an armour material it doubles as a raised trim rib.
//   energyRing(opts) →G
//     facing = 'z'         flat annulus in the XY plane, raised toward +Z; r = centre-line radius
//     facing = 'radial'    band around a cylinder about +Y at y = 0, raised outward; r = cylinder radius
//     r = 0.03, w = 0.004, h = 0.0008, sink = 0.0004, arc: [a0, a1], segs, axis
//
// -------------------------------------------------------------------------------------
// 9. ASSEMBLY UTILITIES
// -------------------------------------------------------------------------------------
//   merge(root, { skip }) → root   STATIC BATCHING, in place: every static Mesh under root is
//                          baked into one Mesh per material (added as a direct child of root);
//                          emptied Groups are removed. A subtree flagged with dynamic() — or for
//                          which skip(object) returns true — keeps its node and transform (animate
//                          it as before) and is batched separately inside itself. Meshes with
//                          userData.noMerge, material arrays, instanced / skinned meshes are left
//                          alone. Call it once per finished panel: kit.merge(panel).
//   dynamic(object) → object     flag an Object3D as animated (hinged flap, finger, piston…)
//   count(object) → { tris, meshes }
//   mergeGeos([geo, …]) →G       concatenate geometries (inputs are consumed)
//   xf(geo, { pos, rot, scale }) →G   transform a geometry in place (rot = Euler XYZ)
//   mesh(geo, material, { pos, rot, scale, name, shadow }) → Mesh   (shadow: false → no shadow casting)
//   place(object, { pos, rot, scale }) → object
//   aim(object, a, b, pole) → distance   put object at a with +Y toward b and +Z near `pole`
//                          (Vector3s in the object's parent space; allocation-free)
//   mirrorObjectX(object) → copy of an Object3D tree mirrored across the YZ plane with properly
//                          mirrored geometry (no negative scale); materials are shared. Build
//                          pistons per side instead — a copy has no .aim().
//   buildDemo({ mat }) → Group   sample board of every primitive (node tools/shot.mjs --demo=kit)
//
// RECIPES
//   two-tone panel     carrier = polyPlate(outline, { step: { inset: 0.012, depth: 0.002 } }) in titanium,
//                      inlay = polyPlate(shape.inset(outline, 0.0165), { t: 0.004 }) in cobalt, 1 mm proud
//   real panel seam    [a, b] = shape.split(outline, p, q, 0.002) → two polyPlates (give them `cell`),
//                      then crown([ga, gb], …) / bend([ga, gb], …) / wrapTube([ga, gb], …) together
//   crisp facets       polyPlate(outline, { fold: [{ at: 0, angle: 0.4, symmetric: true }, { at: 0.06, angle: 0.5 }, { at: -0.06, angle: 0.5 }] })
//   detail on a shell  author flat (plate + boltsAt + energyStrip), wrap the array together; seat
//                      Groups (servo, vent, connector) with placeOn(group, tubeMap(opts), [x, y])
//   exploded views     shells are closed solids with a finished back face — leave back: true on
//                      anything the camera can see from behind
//
// EXAMPLES
//   // chest plate: angular outline, keel, pocket, wrapped around the torso
//   const g = kit.polyPlate([[-0.09, -0.1, 0.01], [0.09, -0.1, 0.01], [0.12, 0.06, 0.006], [0, 0.11, 0.02], [-0.12, 0.06, 0.006]],
//     { t: 0.008, fold: { at: 0, angle: 0.3, symmetric: true }, bend: 0.25, step: { inset: 0.01, depth: 0.001 } });
//   panel.add(kit.mesh(g, mat.cobalt, { pos: [0, 0.1, 0.15] }));
//
//   // forearm armour half + hinged flap, batched
//   const shell = kit.tubeShell({ y0: -0.27, y1: -0.03, r0: 0.05, r1: 0.062, arc: [-1.3, 1.3], t: 0.005, cut: [0.02, 0.02, 0, 0] });
//   panel.add(kit.mesh(shell, mat.titanium));
//   const flap = kit.dynamic(new THREE.Group());             // animate flap.rotation in update()
//   flap.add(kit.mesh(kit.plate({ w: 0.05, h: 0.09, t: 0.004 }), mat.cobalt), kit.hinge({ len: 0.05, half: 'b' }, mat));
//   panel.add(flap);
//   kit.merge(panel);
//
//   // piston that follows two moving anchors
//   const ram = kit.piston({ a: [0, 0.1, 0.03], b: [0, -0.08, 0.05], r: 0.007 }, mat);
//   joint.add(ram);            // per frame:  ram.aim(vecA, vecB)   (points in joint space)
// =====================================================================================
import * as THREE from 'three';
import { mergeGeometries } from 'three/examples/jsm/utils/BufferGeometryUtils.js';

export const KIT_VERSION = 2;

// =====================================================================================
// 0. Small maths
// =====================================================================================
const PI = Math.PI;
const TAU = PI * 2;
const DEG = PI / 180;
const GEPS = 1e-8; // "on the grid line" tolerance (metres) shared by the cap tessellator
const clamp = (x, a, b) => Math.min(b, Math.max(a, x));
const mix = (a, b, u) => a + (b - a) * u;
/** Deterministic hash → 0..1 (the kit never calls Math.random()). */
const hash = (n) => {
  const s = Math.sin(n * 127.1 + 311.7) * 43758.5453123;
  return s - Math.floor(s);
};
const toV3 = (p, out = new THREE.Vector3()) => (p && p.isVector3 ? out.copy(p) : out.set(p[0], p[1], p[2] || 0));

// =====================================================================================
// 1. Mesh builder — every generator authors positions + explicit normals + uvs through
//    this; triangle winding is derived from the authored normals, so a generator can never
//    emit an inside-out face.
// =====================================================================================
class MeshBuilder {
  constructor() {
    this.P = [];
    this.N = [];
    this.U = [];
    this.I = [];
    this.count = 0;
  }
  v(x, y, z, nx, ny, nz, u = 0, v = 0) {
    this.P.push(x, y, z);
    this.N.push(nx, ny, nz);
    this.U.push(u, v);
    return this.count++;
  }
  tri(a, b, c) {
    if (a !== b && b !== c && c !== a) this.I.push(a, b, c);
  }
  quad(a, b, c, d) {
    this.tri(a, b, c);
    this.tri(a, c, d);
  }
  build() {
    return makeGeometry(this.P, this.N, this.U, this.I);
  }
}

function makeGeometry(P, N, U, I) {
  const pos = new Float32Array(P);
  const nor = new Float32Array(N);
  for (let i = 0; i < nor.length; i += 3) {
    const l = Math.hypot(nor[i], nor[i + 1], nor[i + 2]) || 1;
    nor[i] /= l;
    nor[i + 1] /= l;
    nor[i + 2] /= l;
  }
  // drop triangles with a collapsed edge (coincident vertices) — they can never open up again
  let w = 0;
  for (let i = 0; i < I.length; i += 3) {
    const a = I[i] * 3, b = I[i + 1] * 3, c = I[i + 2] * 3;
    const same = (p, q) => Math.abs(pos[p] - pos[q]) + Math.abs(pos[p + 1] - pos[q + 1]) + Math.abs(pos[p + 2] - pos[q + 2]) < 1e-9;
    if (same(a, b) || same(b, c) || same(a, c)) continue;
    I[w++] = I[i]; I[w++] = I[i + 1]; I[w++] = I[i + 2];
  }
  I.length = w;
  const idx = pos.length / 3 > 65535 ? new Uint32Array(I) : new Uint16Array(I);
  orientTriangles(pos, nor, idx);
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.BufferAttribute(pos, 3));
  g.setAttribute('normal', new THREE.BufferAttribute(nor, 3));
  g.setAttribute('uv', new THREE.BufferAttribute(new Float32Array(U), 2));
  g.setIndex(new THREE.BufferAttribute(idx, 1));
  g.computeBoundingBox();
  g.computeBoundingSphere();
  return g;
}

/** Make every triangle's winding agree with its (authored) vertex normals. */
function orientTriangles(pos, nor, idx) {
  const n = idx ? idx.length : pos.length / 3;
  for (let i = 0; i < n; i += 3) {
    const ia = idx ? idx[i] : i, ib = idx ? idx[i + 1] : i + 1, ic = idx ? idx[i + 2] : i + 2;
    const a = ia * 3, b = ib * 3, c = ic * 3;
    const ux = pos[b] - pos[a], uy = pos[b + 1] - pos[a + 1], uz = pos[b + 2] - pos[a + 2];
    const vx = pos[c] - pos[a], vy = pos[c + 1] - pos[a + 1], vz = pos[c + 2] - pos[a + 2];
    const gx = uy * vz - uz * vy, gy = uz * vx - ux * vz, gz = ux * vy - uy * vx;
    const s = gx * (nor[a] + nor[b] + nor[c]) + gy * (nor[a + 1] + nor[b + 1] + nor[c + 1]) + gz * (nor[a + 2] + nor[b + 2] + nor[c + 2]);
    if (s < 0) {
      if (idx) {
        idx[i + 1] = ic;
        idx[i + 2] = ib;
      } else {
        // non-indexed: swap vertices b and c in place
        for (let k = 0; k < 3; k++) {
          let t = pos[b + k]; pos[b + k] = pos[c + k]; pos[c + k] = t;
          t = nor[b + k]; nor[b + k] = nor[c + k]; nor[c + k] = t;
        }
      }
    }
  }
}

// =====================================================================================
// 2. 2D outlines — polygons with rounded / chamfered corners and per-point normals.
//    Shared by plates (plan outline), lathes (radial profile) and shell rims.
// =====================================================================================
function arcStepFor(r, detail) {
  const s = r <= 0.0012 ? 90 : r <= 0.003 ? 45 : r <= 0.008 ? 30 : r <= 0.025 ? 22.5 : r <= 0.07 ? 15 : 10;
  return (s * DEG) / detail;
}

/**
 * Turn raw points [[x, y, cornerRadius?], …] into outline points
 *   { x, y, ax, ay (normal for the incoming edge), bx, by (normal for the outgoing edge), sharp, … }
 * Closed outlines are normalised so the material lies to the LEFT of travel (outer loops CCW,
 * holes CW) and the normals point away from the material.
 */
function buildOutline(src, o = {}) {
  const defR = o.r || 0;
  const crease = (o.crease ?? 30) * DEG;
  const detail = o.detail || 1;
  const closed = o.closed !== false;
  const pts = [];
  for (const p of src) {
    const q = pts[pts.length - 1];
    if (q && Math.hypot(q[0] - p[0], q[1] - p[1]) < 1e-9) continue;
    pts.push([p[0], p[1], p[2] === undefined || p[2] === null ? defR : p[2]]);
  }
  if (closed && pts.length > 2 && Math.hypot(pts[0][0] - pts[pts.length - 1][0], pts[0][1] - pts[pts.length - 1][1]) < 1e-9) pts.pop();
  const n = pts.length;
  if (closed) {
    let area = 0;
    for (let i = 0; i < n; i++) {
      const a = pts[i], b = pts[(i + 1) % n];
      area += a[0] * b[1] - b[0] * a[1];
    }
    if ((area < 0) !== !!o.hole) pts.reverse();
  }
  const out = [];
  for (let i = 0; i < n; i++) {
    const P1 = pts[i];
    if (!closed && (i === 0 || i === n - 1)) {
      const A = i === 0 ? P1 : pts[i - 1], B = i === 0 ? pts[1] : P1;
      const l = Math.hypot(B[0] - A[0], B[1] - A[1]) || 1;
      const nx = (B[1] - A[1]) / l, ny = -(B[0] - A[0]) / l;
      out.push({ x: P1[0], y: P1[1], ax: nx, ay: ny, bx: nx, by: ny, k: 0, mx: nx, my: ny, sharp: false });
      continue;
    }
    const P0 = pts[(i + n - 1) % n], P2 = pts[(i + 1) % n];
    let d0x = P1[0] - P0[0], d0y = P1[1] - P0[1], d1x = P2[0] - P1[0], d1y = P2[1] - P1[1];
    const l0 = Math.hypot(d0x, d0y) || 1, l1 = Math.hypot(d1x, d1y) || 1;
    d0x /= l0; d0y /= l0; d1x /= l1; d1y /= l1;
    const n0x = d0y, n0y = -d0x, n1x = d1y, n1y = -d1x;
    const cr = d0x * d1y - d0y * d1x;
    const phi = Math.atan2(Math.abs(cr), d0x * d1x + d0y * d1y);
    const k = 1 / Math.max(0.12, 1 + n0x * n1x + n0y * n1y); // mitre, limited for spikes
    const mx = (n0x + n1x) * k, my = (n0y + n1y) * k;
    if (phi < 1e-4) {
      out.push({ x: P1[0], y: P1[1], ax: n0x, ay: n0y, bx: n0x, by: n0y, k: 0, mx: n0x, my: n0y, sharp: false });
      continue;
    }
    let rr = P1[2], tl = 0;
    if (rr > 0) {
      tl = rr * Math.tan(phi / 2);
      const maxTl = 0.5 * Math.min(l0, l1);
      if (tl > maxTl) {
        rr *= maxTl / tl;
        tl = maxTl;
      }
    }
    if (rr > 1e-6) {
      const sg = cr > 0 ? 1 : -1; // +1 convex (material inside the turn), -1 concave
      const ax = P1[0] - d0x * tl, ay = P1[1] - d0y * tl;
      const cx = ax - sg * n0x * rr, cy = ay - sg * n0y * rr;
      const ns = o.cornerSegs || Math.max(1, Math.round(phi / arcStepFor(rr, detail)));
      for (let j = 0; j <= ns; j++) {
        const ang = (sg * phi * j) / ns, c = Math.cos(ang), s = Math.sin(ang);
        const nx = n0x * c - n0y * s, ny = n0x * s + n0y * c;
        out.push({ x: cx + sg * nx * rr, y: cy + sg * ny * rr, ax: nx, ay: ny, bx: nx, by: ny, k: sg > 0 ? 1 : 2, cx, cy, cr: rr, mx, my, sharp: false });
      }
    } else if (phi < crease) {
      const l = Math.hypot(n0x + n1x, n0y + n1y) || 1;
      const nx = (n0x + n1x) / l, ny = (n0y + n1y) / l;
      out.push({ x: P1[0], y: P1[1], ax: nx, ay: ny, bx: nx, by: ny, k: 0, mx, my, sharp: false });
    } else {
      out.push({ x: P1[0], y: P1[1], ax: n0x, ay: n0y, bx: n1x, by: n1y, k: 0, mx, my, sharp: true });
    }
  }
  return out;
}

/** Position of outline point p moved `d` metres into the material (exact for arcs and mitres). */
function insetXY(p, d, out, o) {
  if (p.k === 1) {
    if (d <= p.cr) {
      out[o] = p.cx + p.ax * (p.cr - d);
      out[o + 1] = p.cy + p.ay * (p.cr - d);
    } else {
      out[o] = p.cx - p.mx * (d - p.cr);
      out[o + 1] = p.cy - p.my * (d - p.cr);
    }
  } else if (p.k === 2) {
    out[o] = p.cx - p.ax * (p.cr + d);
    out[o + 1] = p.cy - p.ay * (p.cr + d);
  } else {
    out[o] = p.x - p.mx * d;
    out[o + 1] = p.y - p.my * d;
  }
}

/** 2D outline helpers. Every function returns an array of [x, y, cornerRadius?] points. */
export const shape = {
  /** Axis-aligned rectangle w × h centred on (cx, cy); r = corner radius (number or [bl, br, tr, tl]). */
  rect(w, h, r = 0, cx = 0, cy = 0) {
    const R = Array.isArray(r) ? r : [r, r, r, r];
    return [[cx - w / 2, cy - h / 2, R[0]], [cx + w / 2, cy - h / 2, R[1]], [cx + w / 2, cy + h / 2, R[2]], [cx - w / 2, cy + h / 2, R[3]]];
  },
  /** Rectangle with 45° clipped corners; cut = clip distance (number or [bl, br, tr, tl]), r = radius left on the new corners. */
  chamferRect(w, h, cut = 0.01, r = 0, cx = 0, cy = 0) {
    const C = Array.isArray(cut) ? cut : [cut, cut, cut, cut];
    const x0 = cx - w / 2, x1 = cx + w / 2, y0 = cy - h / 2, y1 = cy + h / 2;
    const pts = [];
    const corner = (x, y, c, dx, dy) => {
      // (dx, dy) = direction of travel INTO the corner; rotate 90° CCW to leave it
      if (c > 0) pts.push([x - dx * c, y - dy * c, r], [x - dy * c, y + dx * c, r]);
      else pts.push([x, y, r]);
    };
    corner(x1, y0, C[1], 1, 0);
    corner(x1, y1, C[2], 0, 1);
    corner(x0, y1, C[3], -1, 0);
    corner(x0, y0, C[0], 0, -1);
    return pts;
  },
  /** Circle of radius r as an n-gon (smooth shaded). n defaults to a size-appropriate count. */
  circle(r, cx = 0, cy = 0, n = 0) {
    n = n || clamp(Math.round(8 + r * 900), 10, 40);
    const pts = [];
    for (let i = 0; i < n; i++) pts.push([cx + r * Math.cos((i / n) * TAU), cy + r * Math.sin((i / n) * TAU)]);
    return pts;
  },
  /** Ellipse with semi-axes rx, ry. */
  ellipse(rx, ry, cx = 0, cy = 0, n = 0) {
    n = n || clamp(Math.round(10 + Math.max(rx, ry) * 500), 14, 48);
    const pts = [];
    for (let i = 0; i < n; i++) pts.push([cx + rx * Math.cos((i / n) * TAU), cy + ry * Math.sin((i / n) * TAU)]);
    return pts;
  },
  /** Regular n-gon, circumradius r, first vertex at angle rot (radians, 0 = +X), optional corner radius. */
  ngon(n, r, cx = 0, cy = 0, rot = 0, cornerR = 0) {
    const pts = [];
    for (let i = 0; i < n; i++) pts.push([cx + r * Math.cos(rot + (i / n) * TAU), cy + r * Math.sin(rot + (i / n) * TAU), cornerR]);
    return pts;
  },
  /** Gear / spline outline: `teeth` trapezoidal teeth, tip radius r, tooth depth (default 12 % of r). */
  gear(teeth, r, depth, cx = 0, cy = 0) {
    const d = depth ?? r * 0.12, pts = [], st = TAU / teeth;
    for (let i = 0; i < teeth; i++) {
      const a = i * st;
      for (const [f, rad] of [[0.04, r - d], [0.2, r], [0.5, r], [0.66, r - d]]) pts.push([cx + rad * Math.cos(a + f * st), cy + rad * Math.sin(a + f * st), 0]);
    }
    return pts;
  },
  /** Stadium slot: overall length len (along X before rotation), width w, centred on (cx, cy), rotated by angle (radians). */
  slot(len, w, cx = 0, cy = 0, angle = 0) {
    return shape.move(shape.rotate(shape.rect(len, w, w / 2), angle), cx, cy);
  },
  /** Outline of a polyline thickened to `width` (mitred corners, endR = radius of the 4 end corners; width/2 → round ends). */
  stroke(points, width, endR = 0) {
    const n = points.length, hw = width / 2, L = [], R = [];
    for (let i = 0; i < n; i++) {
      const a = points[Math.max(0, i - 1)], b = points[i], c = points[Math.min(n - 1, i + 1)];
      let d0x = b[0] - a[0], d0y = b[1] - a[1], d1x = c[0] - b[0], d1y = c[1] - b[1];
      if (i === 0) { d0x = d1x; d0y = d1y; }
      if (i === n - 1) { d1x = d0x; d1y = d0y; }
      const l0 = Math.hypot(d0x, d0y) || 1, l1 = Math.hypot(d1x, d1y) || 1;
      const n0x = -d0y / l0, n0y = d0x / l0, n1x = -d1y / l1, n1y = d1x / l1;
      const k = 1 / Math.max(0.2, 1 + n0x * n1x + n0y * n1y);
      const mx = (n0x + n1x) * k * hw, my = (n0y + n1y) * k * hw;
      const er = i === 0 || i === n - 1 ? endR : 0;
      L.push([b[0] + mx, b[1] + my, er]);
      R.push([b[0] - mx, b[1] - my, er]);
    }
    return [...R, ...L.reverse()];
  },
  /**
   * Offset an outline inward by d metres (d < 0 grows it). Corner radii are adjusted so rounded
   * corners stay concentric — an inlay made from inset(outline, 0.006) nests exactly in its parent.
   */
  inset(pts, d) {
    const n = pts.length;
    let area = 0;
    for (let i = 0; i < n; i++) area += pts[i][0] * pts[(i + 1) % n][1] - pts[(i + 1) % n][0] * pts[i][1];
    const sg = area >= 0 ? 1 : -1;
    const out = [];
    for (let i = 0; i < n; i++) {
      const P0 = pts[(i + n - 1) % n], P1 = pts[i], P2 = pts[(i + 1) % n];
      let d0x = P1[0] - P0[0], d0y = P1[1] - P0[1], d1x = P2[0] - P1[0], d1y = P2[1] - P1[1];
      const l0 = Math.hypot(d0x, d0y) || 1, l1 = Math.hypot(d1x, d1y) || 1;
      d0x /= l0; d0y /= l0; d1x /= l1; d1y /= l1;
      const n0x = sg * d0y, n0y = -sg * d0x, n1x = sg * d1y, n1y = -sg * d1x;
      const k = 1 / Math.max(0.12, 1 + n0x * n1x + n0y * n1y);
      const convex = sg * (d0x * d1y - d0y * d1x) > 0;
      const R = P1[2] || 0;
      out.push([P1[0] - (n0x + n1x) * k * d, P1[1] - (n0y + n1y) * k * d, R > 0 ? (convex ? Math.max(0, R - d) : Math.max(0, R + d)) : 0]);
    }
    return out;
  },
  /**
   * Cut an outline in two along the line a → b, leaving a gap between the halves: returns
   * [left, right] (left of the direction of travel first). r = corner radius given to the new
   * corners on the cut. Build each half with polyPlate() for a REAL panel seam whose bevelled
   * edges both catch the light. Meant for convex-ish outlines.
   */
  split(pts, a, b, gap = 0.0015, r = 0) {
    const dl = Math.hypot(b[0] - a[0], b[1] - a[1]) || 1;
    const nx = -(b[1] - a[1]) / dl, ny = (b[0] - a[0]) / dl;
    const half = (ox, oy, hx, hy) => {
      const out = [];
      for (let i = 0; i < pts.length; i++) {
        const P = pts[i], Q = pts[(i + 1) % pts.length];
        const dp = (P[0] - ox) * hx + (P[1] - oy) * hy, dq = (Q[0] - ox) * hx + (Q[1] - oy) * hy;
        if (dp >= 0) out.push(P.slice());
        if ((dp > 0 && dq < 0) || (dp < 0 && dq > 0)) {
          const t = dp / (dp - dq);
          out.push([P[0] + (Q[0] - P[0]) * t, P[1] + (Q[1] - P[1]) * t, r]);
        }
      }
      return out;
    };
    return [half(a[0] + (nx * gap) / 2, a[1] + (ny * gap) / 2, nx, ny), half(a[0] - (nx * gap) / 2, a[1] - (ny * gap) / 2, -nx, -ny)];
  },
  move: (pts, dx = 0, dy = 0) => pts.map((p) => [p[0] + dx, p[1] + dy, p[2]]),
  rotate(pts, ang) {
    const c = Math.cos(ang), s = Math.sin(ang);
    return pts.map((p) => [p[0] * c - p[1] * s, p[0] * s + p[1] * c, p[2]]);
  },
  scale: (pts, sx, sy = sx) => pts.map((p) => [p[0] * sx, p[1] * sy, p[2]]),
  /** Mirror across the Y axis (x → -x). */
  mirror: (pts) => pts.map((p) => [-p[0], p[1], p[2]]).reverse(),
};

// =====================================================================================
// 3. Plate engine — bevelled extrusion of an outline (with holes) + grid-tessellated caps
// =====================================================================================

/** Split every triangle crossing the line coord[axis] = c. V = flat [x,y,…] (grows), T = index triples. */
function splitTris(V, T, axis, c) {
  const out = [];
  const cache = new Map();
  const side = (i) => {
    const d = V[i * 2 + axis] - c;
    return d > GEPS ? 1 : d < -GEPS ? -1 : 0;
  };
  const cut = (i, j) => {
    const lo = i < j ? i : j, hi = i < j ? j : i;
    const key = lo * 2097152 + hi;
    let k = cache.get(key);
    if (k === undefined) {
      const a = V[lo * 2 + axis], b = V[hi * 2 + axis];
      const s = (c - a) / (b - a);
      const x = V[lo * 2] + (V[hi * 2] - V[lo * 2]) * s, y = V[lo * 2 + 1] + (V[hi * 2 + 1] - V[lo * 2 + 1]) * s;
      k = V.length / 2;
      V.push(axis === 0 ? c : x, axis === 1 ? c : y);
      cache.set(key, k);
    }
    return k;
  };
  for (let t = 0; t < T.length; t += 3) {
    const id = [T[t], T[t + 1], T[t + 2]];
    const s = [side(id[0]), side(id[1]), side(id[2])];
    if ((s[0] >= 0 && s[1] >= 0 && s[2] >= 0) || (s[0] <= 0 && s[1] <= 0 && s[2] <= 0)) {
      out.push(id[0], id[1], id[2]);
      continue;
    }
    const z = s.indexOf(0);
    if (z >= 0) {
      const a = id[z], b = id[(z + 1) % 3], d = id[(z + 2) % 3];
      const m = cut(b, d);
      out.push(a, b, m, a, m, d);
      continue;
    }
    const l = s[0] !== s[1] && s[0] !== s[2] ? 0 : s[1] !== s[0] && s[1] !== s[2] ? 1 : 2;
    const a = id[l], b = id[(l + 1) % 3], d = id[(l + 2) % 3];
    const ab = cut(a, b), ad = cut(a, d);
    out.push(a, ab, ad, ab, b, d, ab, d, ad);
  }
  return out;
}

/**
 * Triangulate a polygon with holes. loops = arrays of indices into V (flat [x,y,…]); loops[0]
 * is the outer contour. Returns a flat array of index triples.
 * Robustness: coincident neighbours are welded and (near-)collinear boundary points are held
 * back from earcut — which would otherwise build zero-area slivers along straight edges — then
 * stitched back in as fans. The result references EVERY distinct boundary point, so the cap
 * shares its whole rim with the bevel ring (no T-junctions → no cracks once the sheet is bent).
 */
function triangulateLoops(V, loops) {
  const X = (i) => V[i * 2], Y = (i) => V[i * 2 + 1];
  const TOL = 1e-9;
  // 1. weld coincident neighbours
  const L1 = loops.map((L) => {
    const r = [];
    for (const i of L) {
      const p = r[r.length - 1];
      if (p !== undefined && Math.abs(X(p) - X(i)) < TOL && Math.abs(Y(p) - Y(i)) < TOL) continue;
      r.push(i);
    }
    while (r.length > 1 && Math.abs(X(r[0]) - X(r[r.length - 1])) < TOL && Math.abs(Y(r[0]) - Y(r[r.length - 1])) < TOL) r.pop();
    return r;
  });
  // 2. hold back collinear points
  const keepMask = L1.map((L) => {
    const n = L.length, keep = new Uint8Array(n).fill(1);
    let left = n, changed = true;
    while (changed && left > 3) {
      changed = false;
      for (let i = 0; i < n && left > 3; i++) {
        if (!keep[i]) continue;
        let p = (i + n - 1) % n, q = (i + 1) % n;
        while (!keep[p]) p = (p + n - 1) % n;
        while (!keep[q]) q = (q + 1) % n;
        const ax = X(L[p]), ay = Y(L[p]), bx = X(L[q]), by = Y(L[q]), px = X(L[i]), py = Y(L[i]);
        const dx = bx - ax, dy = by - ay, len = Math.hypot(dx, dy);
        if (len < TOL) continue;
        const dist = Math.abs((px - ax) * dy - (py - ay) * dx) / len;
        const along = ((px - ax) * dx + (py - ay) * dy) / (len * len);
        if (dist < TOL && along > 0 && along < 1) {
          keep[i] = 0;
          left--;
          changed = true;
        }
      }
    }
    return keep;
  });
  // 3. earcut on the remaining corners (local index space = concatenated L1)
  const base = [];
  let total = 0;
  for (const L of L1) { base.push(total); total += L.length; }
  const mk = (L, li) => {
    const r = [];
    for (let i = 0; i < L.length; i++) if (keepMask[li][i]) r.push({ x: X(L[i]), y: Y(L[i]), id: base[li] + i, equals: () => false });
    return r;
  };
  const contours = L1.map(mk);
  if (contours[0].length < 3) return [];
  const holes = contours.slice(1).filter((c) => c.length >= 3);
  const flatC = [...contours[0]];
  for (const h of holes) flatC.push(...h);
  const faces = THREE.ShapeUtils.triangulateShape(contours[0], holes);
  const T = [];
  for (const f of faces) T.push(flatC[f[0]].id, flatC[f[1]].id, flatC[f[2]].id);
  // 4. stitch every unused boundary point back in
  const used = new Uint8Array(total);
  for (let i = 0; i < T.length; i++) used[T[i]] = 1;
  for (let li = 0; li < L1.length; li++) {
    const n = L1[li].length, b0 = base[li];
    let start = -1;
    for (let i = 0; i < n; i++) if (used[b0 + i]) { start = i; break; }
    if (start < 0) continue;
    let i = start;
    do {
      let j = (i + 1) % n;
      const run = [];
      while (!used[b0 + j]) {
        run.push(b0 + j);
        j = (j + 1) % n;
      }
      if (run.length && j !== i) {
        const u = b0 + i, w = b0 + j;
        search: for (let t = 0; t < T.length; t += 3) {
          for (let e = 0; e < 3; e++) {
            const p = T[t + e], q = T[t + ((e + 1) % 3)], apex = T[t + ((e + 2) % 3)];
            if ((p === u && q === w) || (p === w && q === u)) {
              const chain = p === u ? [u, ...run, w] : [w, ...run.slice().reverse(), u];
              T[t] = chain[0]; T[t + 1] = chain[1]; T[t + 2] = apex;
              for (let k = 1; k < chain.length - 1; k++) T.push(chain[k], chain[k + 1], apex);
              break search;
            }
          }
        }
      }
      i = j;
    } while (i !== start);
  }
  const flat = [];
  for (const L of L1) for (const i of L) flat.push(i);
  for (let i = 0; i < T.length; i++) T[i] = flat[T[i]];
  return T;
}

/**
 * After grid splitting every triangle sits inside one grid cell, but earcut's long fans have
 * been diced into slivers. Rebuild each cell from its outline: interior cells become two
 * triangles, boundary cells a handful. Split-created vertices in the middle of a cell edge are
 * dropped (consistently on both sides); plate-boundary vertices (index < nRing) always stay.
 */
function retriangulateCells(V, T, xs, ys, nRing) {
  const nx = xs.length + 1;
  const M = 2097152;
  const cells = new Map();
  for (let t = 0; t < T.length; t += 3) {
    const a = T[t], b = T[t + 1], c = T[t + 2];
    const mx = (V[a * 2] + V[b * 2] + V[c * 2]) / 3, my = (V[a * 2 + 1] + V[b * 2 + 1] + V[c * 2 + 1]) / 3;
    let ix = 0, iy = 0;
    while (ix < xs.length && mx > xs[ix]) ix++;
    while (iy < ys.length && my > ys[iy]) iy++;
    const id = ix + iy * nx;
    let list = cells.get(id);
    if (!list) cells.set(id, (list = []));
    list.push(a, b, c);
  }
  const out = [];
  const areaOf = (L) => {
    let ar = 0;
    for (let i = 0; i < L.length; i++) {
      const p = L[i], q = L[(i + 1) % L.length];
      ar += V[p * 2] * V[q * 2 + 1] - V[q * 2] * V[p * 2 + 1];
    }
    return ar;
  };
  // pass 1: outline of every cell = edges used an odd number of times (orientation-free, so
  // zero-area slivers cannot corrupt it). Cells whose outline cannot be traced keep their
  // triangles, and pin their vertices so the neighbours do not simplify them away.
  const pinned = new Set();
  const traced = [];
  for (const [id, tris] of cells) {
    const E = new Map();
    for (let t = 0; t < tris.length; t += 3) {
      for (let e = 0; e < 3; e++) {
        const p = tris[t + e], q = tris[t + ((e + 1) % 3)];
        const key = p < q ? p * M + q : q * M + p;
        if (E.has(key)) E.delete(key);
        else E.set(key, 1);
      }
    }
    const adj = new Map();
    let ok = true;
    const link = (p, q) => {
      const l = adj.get(p);
      if (!l) adj.set(p, [q]);
      else if (l.length === 1) l.push(q);
      else ok = false;
    };
    for (const key of E.keys()) {
      const p = Math.floor(key / M), q = key % M;
      link(p, q);
      link(q, p);
    }
    const loops = [];
    if (ok) for (const l of adj.values()) if (l.length !== 2) { ok = false; break; }
    while (ok && adj.size) {
      const start = adj.keys().next().value;
      const loop = [];
      let prev = -1, v = start;
      do {
        loop.push(v);
        const l = adj.get(v);
        adj.delete(v);
        const w = l[0] === prev ? l[1] : l[0];
        prev = v;
        v = w;
      } while (v !== start && adj.has(v));
      if (v !== start) ok = false;
      else loops.push(loop);
    }
    if (!ok) {
      for (const i of tris) { out.push(i); pinned.add(i); }
      continue;
    }
    traced.push({ loops, id });
  }
  // pass 2: simplify + triangulate
  for (const { loops, id } of traced) {
    // the four lines bounding this cell; a split-created vertex survives only at a cell corner
    const ix = id % nx, iy = Math.floor(id / nx);
    const xa = ix > 0 ? xs[ix - 1] : NaN, xb = ix < xs.length ? xs[ix] : NaN;
    const ya = iy > 0 ? ys[iy - 1] : NaN, yb = iy < ys.length ? ys[iy] : NaN;
    const corner = (v) => {
      const x = V[v * 2], y = V[v * 2 + 1];
      return (Math.abs(x - xa) < GEPS || Math.abs(x - xb) < GEPS) && (Math.abs(y - ya) < GEPS || Math.abs(y - yb) < GEPS);
    };
    const polys = [];
    for (const loop of loops) {
      const L = loop.filter((v) => v < nRing || pinned.has(v) || corner(v));
      if (L.length < 3) continue;
      const ar = areaOf(L);
      if (ar < 0) L.reverse();
      polys.push({ L, ar: Math.abs(ar), holes: [], depth: 0 });
    }
    if (polys.length > 1) {
      // nesting: a loop inside an odd number of others is a hole of its smallest container
      polys.sort((p, q) => q.ar - p.ar);
      for (let i = 1; i < polys.length; i++) {
        let parent = null;
        const x = V[polys[i].L[0] * 2], y = V[polys[i].L[0] * 2 + 1];
        for (let j = 0; j < i; j++) if (pointInLoop(V, polys[j].L, x, y)) { polys[i].depth++; parent = polys[j]; }
        if (polys[i].depth % 2 === 1 && parent) parent.holes.push(polys[i].L);
      }
    }
    for (const p of polys) {
      if (p.depth % 2 === 1) continue;
      if (!p.holes.length && p.L.length === 3) out.push(p.L[0], p.L[1], p.L[2]);
      else for (const i of triangulateLoops(V, [p.L, ...p.holes])) out.push(i);
    }
  }
  return out;
}
function pointInLoop(V, L, x, y) {
  let inside = false;
  for (let i = 0, j = L.length - 1; i < L.length; j = i++) {
    const xi = V[L[i] * 2], yi = V[L[i] * 2 + 1], xj = V[L[j] * 2], yj = V[L[j] * 2 + 1];
    if (yi > y !== yj > y && x < ((xj - xi) * (y - yi)) / (yj - yi) + xi) inside = !inside;
  }
  return inside;
}

/**
 * Core of plate()/polyPlate(): flat bevelled extrusion. Top (show) face at z = 0 facing +Z,
 * back face at z = -t. loops[0] = outer outline, loops[1…] = holes; points [x, y, r?].
 */
function extrudeLoops(loops, o = {}) {
  const t = Math.max(1e-5, o.t ?? 0.006);
  let b = Math.max(0, o.bevel ?? 0.0015);
  let bb = Math.max(0, o.bevelBack ?? Math.min(b, 0.0008));
  b = Math.min(b, t * 0.6);
  bb = Math.min(bb, t - b);
  const hasBack = o.back !== false;

  // ---- cross-section strips: [dA, zA, ndA, nzA, dB, zB, ndB, nzB, uvMode] ---------------
  // d = distance inward from the outline, (nd, nz) = normal as (outward component, z component).
  const strips = [];
  if (t - b - bb > 1e-7) strips.push([0, -t + bb, 1, 0, 0, -b, 1, 0, 1]);
  if (b > 0) strips.push([0, -b, 1, 0, b, 0, 0, 1, 0]);
  let dTop = b, zTop = 0;
  const step = o.step;
  if (step && step.depth) {
    const si = Math.max(step.inset ?? 0.008, b + 1e-5);
    const dp = step.depth;
    const sw = Math.max(1e-5, step.wall ?? Math.abs(dp) * 0.7);
    const L = Math.hypot(sw, dp);
    const nd = -dp / L, nz = sw / L;
    strips.push([b, 0, 0, 1, si, 0, 0, 1, 0]);
    strips.push([si, 0, nd, nz, si + sw, -dp, nd, nz, 0]);
    dTop = si + sw;
    zTop = -dp;
  }
  // back: a shallow chamfer straight to the cap ring (one strip); with a step, chamfer + annulus
  const bw = step && step.depth ? Math.min(bb, dTop) : dTop;
  if (bb > 0) strips.push([0, -t + bb, 1, 0, bw, -t, 0, -1, 0]);
  if (hasBack && dTop - bw > 1e-7) strips.push([bw, -t, 0, -1, dTop, -t, 0, -1, 0]);

  // distinct inset distances → column index
  const dists = [];
  const col = (d) => {
    let i = dists.findIndex((x) => Math.abs(x - d) < 1e-9);
    if (i < 0) i = dists.push(d) - 1;
    return i;
  };
  col(0);
  for (const s of strips) { col(s[0]); col(s[4]); }
  const capCol = col(dTop);
  const nd = dists.length;

  // ---- outlines with inset columns -------------------------------------------------
  let outl = loops.map((pts, i) => buildOutline(pts, { r: i === 0 ? o.r : o.holeR, hole: i > 0, crease: o.crease, detail: o.detail, cornerSegs: o.cornerSegs }));
  let x0 = Infinity, x1 = -Infinity, y0 = Infinity, y1 = -Infinity;
  for (const L of outl) {
    for (const p of L) {
      p.c = new Float64Array(nd * 2);
      for (let k = 0; k < nd; k++) insetXY(p, dists[k], p.c, k * 2);
    }
  }
  for (const p of outl[0]) {
    x0 = Math.min(x0, p.x); x1 = Math.max(x1, p.x);
    y0 = Math.min(y0, p.y); y1 = Math.max(y1, p.y);
  }

  // ---- tessellation grid -----------------------------------------------------------
  let nx = 1, ny = 1;
  if (o.segs) { nx = Math.max(1, o.segs[0] | 0); ny = Math.max(1, o.segs[1] | 0); }
  else if (o.cell) { nx = Math.max(1, Math.ceil((x1 - x0) / o.cell - 1e-6)); ny = Math.max(1, Math.ceil((y1 - y0) / o.cell - 1e-6)); }
  nx = Math.min(nx, 96); ny = Math.min(ny, 96);
  const xs = [], ys = [];
  for (let i = 1; i < nx; i++) xs.push(x0 + ((x1 - x0) * i) / nx);
  for (let i = 1; i < ny; i++) ys.push(y0 + ((y1 - y0) * i) / ny);
  // extra tessellation lines at exact coordinates (fold lines, feature edges)
  const addLines = (arr, extra, lo, hi) => {
    for (const c of extra || []) if (c > lo + 1e-6 && c < hi - 1e-6 && !arr.some((q) => Math.abs(q - c) < 1e-6)) arr.push(c);
    arr.sort((a, b) => a - b);
  };
  addLines(xs, o.xLines, x0, x1);
  addLines(ys, o.yLines, y0, y1);

  // insert boundary points where the cap ring crosses a grid line (all rings stay 1:1)
  if (xs.length || ys.length) {
    outl = outl.map((L) => {
      const res = [];
      const n = L.length;
      for (let i = 0; i < n; i++) {
        const p = L[i], q = L[(i + 1) % n];
        res.push(p);
        const ax = p.c[capCol * 2], ay = p.c[capCol * 2 + 1], bx = q.c[capCol * 2], by = q.c[capCol * 2 + 1];
        const cuts = [];
        for (const c of xs) if ((ax - c > GEPS && bx - c < -GEPS) || (ax - c < -GEPS && bx - c > GEPS)) cuts.push([(c - ax) / (bx - ax), 0, c]);
        for (const c of ys) if ((ay - c > GEPS && by - c < -GEPS) || (ay - c < -GEPS && by - c > GEPS)) cuts.push([(c - ay) / (by - ay), 1, c]);
        if (!cuts.length) continue;
        cuts.sort((u, v) => u[0] - v[0]);
        let last = null, lastS = -1;
        for (const [s, axis, c] of cuts) {
          if (last && s - lastS < 1e-9) { last.c[capCol * 2 + axis] = c; continue; }
          const m = { c: new Float64Array(nd * 2), sharp: false };
          for (let k = 0; k < nd * 2; k++) m.c[k] = p.c[k] + (q.c[k] - p.c[k]) * s;
          m.c[capCol * 2 + axis] = c;
          const nxx = p.bx + (q.ax - p.bx) * s, nyy = p.by + (q.ay - p.by) * s, l = Math.hypot(nxx, nyy) || 1;
          m.ax = m.bx = nxx / l;
          m.ay = m.by = nyy / l;
          res.push(m);
          last = m; lastS = s;
        }
      }
      return res;
    });
  }

  // ---- rings ---------------------------------------------------------------------
  const mb = new MeshBuilder();
  const ringCache = new Map();
  const perim = outl.map((L) => {
    const S = [0];
    for (let i = 0; i < L.length; i++) {
      const p = L[i], q = L[(i + 1) % L.length];
      S.push(S[i] + Math.hypot(q.c[0] - p.c[0], q.c[1] - p.c[1]));
    }
    return S;
  });
  const ring = (li, d, z, ndn, nzn, uvMode) => {
    const key = li + '|' + col(d) + '|' + z.toFixed(7) + '|' + ndn.toFixed(4) + '|' + nzn.toFixed(4) + '|' + uvMode;
    let r = ringCache.get(key);
    if (r) return r;
    const L = outl[li], n = L.length, c = col(d) * 2, S = perim[li];
    const inn = new Array(n), out = new Array(n);
    for (let i = 0; i < n; i++) {
      const p = L[i], X = p.c[c], Y = p.c[c + 1];
      const split = (ndn !== 0 && p.sharp) || (uvMode === 1 && i === 0);
      out[i] = mb.v(X, Y, z, ndn * p.bx, ndn * p.by, nzn, uvMode ? S[i] : X, uvMode ? z : Y);
      inn[i] = split ? mb.v(X, Y, z, ndn * p.ax, ndn * p.ay, nzn, uvMode ? (i === 0 ? S[n] : S[i]) : X, uvMode ? z : Y) : out[i];
    }
    r = { inn, out };
    ringCache.set(key, r);
    return r;
  };
  for (const s of strips) {
    for (let li = 0; li < outl.length; li++) {
      const A = ring(li, s[0], s[1], s[2], s[3], s[8]), B = ring(li, s[4], s[5], s[6], s[7], s[8]);
      const n = outl[li].length;
      for (let i = 0; i < n; i++) {
        const j = (i + 1) % n;
        mb.quad(A.out[i], A.inn[j], B.inn[j], B.out[i]);
      }
    }
  }

  // ---- caps (one 2D triangulation shared by the top and back faces) -------------------
  const V = [];
  const info = [];
  for (const L of outl) {
    info.push({ base: V.length / 2, n: L.length });
    for (const p of L) V.push(p.c[capCol * 2], p.c[capCol * 2 + 1]);
  }
  const nRing = V.length / 2;
  let T = triangulateLoops(V, info.map(({ base, n }) => Array.from({ length: n }, (_, i) => base + i)));
  if (xs.length || ys.length) {
    for (const c of xs) T = splitTris(V, T, 0, c);
    for (const c of ys) T = splitTris(V, T, 1, c);
    T = retriangulateCells(V, T, xs, ys, nRing);
  }
  const emitCap = (z, nz) => {
    const ids = new Int32Array(V.length / 2);
    for (let li = 0; li < outl.length; li++) {
      const r = ring(li, dTop, z, 0, nz, 0);
      for (let i = 0; i < info[li].n; i++) ids[info[li].base + i] = r.out[i];
    }
    for (let i = nRing; i < ids.length; i++) ids[i] = -1;
    const id = (i) => (ids[i] >= 0 ? ids[i] : (ids[i] = mb.v(V[i * 2], V[i * 2 + 1], z, 0, 0, nz, V[i * 2], V[i * 2 + 1])));
    for (let k = 0; k < T.length; k += 3) mb.tri(id(T[k]), id(T[k + 1]), id(T[k + 2]));
  };
  emitCap(zTop, 1);
  if (hasBack) emitCap(-t, -1);

  return mb.build();
}

/**
 * polyPlate(points, opts) — see the API section at the top of this file.
 */
export function polyPlate(points, o = {}) {
  const bendO = o.bend ? (typeof o.bend === 'number' ? { radius: o.bend } : o.bend) : null;
  let x0 = Infinity, x1 = -Infinity, y0 = Infinity, y1 = -Infinity;
  for (const p of points) {
    x0 = Math.min(x0, p[0]); x1 = Math.max(x1, p[0]);
    y0 = Math.min(y0, p[1]); y1 = Math.max(y1, p[1]);
  }
  const W = x1 - x0, H = y1 - y0;
  let segs = o.segs;
  if (!segs && !o.cell) {
    let nx = 1, ny = 1;
    if (o.crown) {
      nx = clamp(Math.round(W / 0.028), 4, 10);
      ny = clamp(Math.round(H / 0.028), 4, 10);
    }
    if (bendO) {
      const R = bendRadius(bendO, bendO.axis === 'x' ? H : W);
      const n = clamp(Math.ceil((bendO.axis === 'x' ? H : W) / Math.abs(R) / ((bendO.step ?? 10) * DEG)), 1, 64);
      if (bendO.axis === 'x') ny = Math.max(ny, n);
      else nx = Math.max(nx, n);
    }
    segs = [nx, ny];
  }
  // folds: tessellation lines on both edges and the middle of every crease band
  const folds = o.fold ? (Array.isArray(o.fold) ? o.fold : [o.fold]) : [];
  let xLines = o.xLines, yLines = o.yLines;
  for (const f of folds) {
    const w = f.width ?? 0.003, at = f.at ?? 0, ls = [at - w / 2, at, at + w / 2];
    if (f.axis === 'x') yLines = [...(yLines || []), ...ls];
    else xLines = [...(xLines || []), ...ls];
  }
  const g = extrudeLoops([points, ...(o.holes || [])], { ...o, segs, xLines, yLines });
  if (o.crown) crown(g, { amount: o.crown, w: W, h: H, cx: (x0 + x1) / 2, cy: (y0 + y1) / 2 });
  // apply folds from the outside in so each one sees undisturbed coordinates on its far side
  for (const f of folds.slice().sort((a, b) => Math.abs(b.at ?? 0) - Math.abs(a.at ?? 0))) fold(g, f);
  if (bendO) bend(g, { center: bendO.axis === 'x' ? (y0 + y1) / 2 : (x0 + x1) / 2, ...bendO, radius: bendRadius(bendO, bendO.axis === 'x' ? H : W) });
  return g;
}

/**
 * plate(opts) — rounded / clipped / tapered rectangle plate. See the API section.
 */
export function plate(o = {}) {
  const w = o.w ?? 0.1, h = o.h ?? 0.1, tp = o.taper ?? 1, sk = o.skew || 0;
  const pts = cornerPts([[-w / 2, -h / 2], [w / 2, -h / 2], [(w / 2) * tp + sk, h / 2], [(-w / 2) * tp + sk, h / 2]], four(o.r, 0.006), four(o.cut, 0));
  return polyPlate(pts, { ...o, r: 0 });
}

/** Box w × h × d centred on the origin: the four edges parallel to Z are rounded by `r`, the
 *  other eight are bevelled by `bevel`. */
export function chamferBox(w, h, d, o = {}) {
  const bevel = o.bevel ?? Math.min(0.001, Math.min(w, h, d) * 0.2);
  const g = extrudeLoops([shape.rect(w, h, o.r ?? bevel)], { t: d, bevel, bevelBack: bevel, detail: o.detail, cornerSegs: o.cornerSegs });
  g.translate(0, 0, d / 2);
  return g;
}

// =====================================================================================
// 4. Modifiers — smooth space warps with exact normals
// =====================================================================================
const _v0 = new THREE.Vector3(), _v1 = new THREE.Vector3(), _v2 = new THREE.Vector3(), _v3 = new THREE.Vector3();

/**
 * deform(geometry, fn) — move every vertex through fn(v: THREE.Vector3) (mutate v in place).
 * Normals are re-derived from the local Jacobian of fn (finite differences), so authored
 * creases, bevel roll-offs and smooth areas all survive exactly; winding is fixed up if fn
 * mirrors. Accepts one geometry or an array; modifies in place and returns its argument.
 */
export function deform(geo, fn) {
  if (Array.isArray(geo)) {
    for (const g of geo) deform(g, fn);
    return geo;
  }
  const P = geo.attributes.position.array;
  const N = geo.attributes.normal ? geo.attributes.normal.array : null;
  const e = 2e-5;
  for (let i = 0; i < P.length; i += 3) {
    const px = P[i], py = P[i + 1], pz = P[i + 2];
    _v0.set(px, py, pz);
    fn(_v0);
    if (N) {
      const nx = N[i], ny = N[i + 1], nz = N[i + 2];
      let tx, ty, tz;
      if (Math.abs(nx) < 0.6) { tx = 0; ty = nz; tz = -ny; } else { tx = -nz; ty = 0; tz = nx; }
      const tl = Math.hypot(tx, ty, tz) || 1;
      tx /= tl; ty /= tl; tz /= tl;
      const bx = ny * tz - nz * ty, by = nz * tx - nx * tz, bz = nx * ty - ny * tx;
      _v1.set(px + tx * e, py + ty * e, pz + tz * e); fn(_v1); _v1.sub(_v0);
      _v2.set(px + bx * e, py + by * e, pz + bz * e); fn(_v2); _v2.sub(_v0);
      _v3.set(px + nx * e, py + ny * e, pz + nz * e); fn(_v3); _v3.sub(_v0);
      _v1.cross(_v2);
      if (_v1.dot(_v3) < 0) _v1.negate();
      const l = _v1.length();
      if (l > 1e-30) _v1.multiplyScalar(1 / l);
      else if (_v3.lengthSq() > 0) _v1.copy(_v3).normalize();
      else _v1.set(nx, ny, nz);
      N[i] = _v1.x; N[i + 1] = _v1.y; N[i + 2] = _v1.z;
    }
    P[i] = _v0.x; P[i + 1] = _v0.y; P[i + 2] = _v0.z;
  }
  if (N) orientTriangles(P, N, geo.index ? geo.index.array : null);
  geo.attributes.position.needsUpdate = true;
  if (N) geo.attributes.normal.needsUpdate = true;
  if (geo.index) geo.index.needsUpdate = true;
  geo.computeBoundingBox();
  geo.computeBoundingSphere();
  return geo;
}

function spanOf(geo, axis) {
  const list = Array.isArray(geo) ? geo : [geo];
  let lo = Infinity, hi = -Infinity;
  for (const g of list) {
    if (!g.boundingBox) g.computeBoundingBox();
    lo = Math.min(lo, g.boundingBox.min[axis]);
    hi = Math.max(hi, g.boundingBox.max[axis]);
  }
  return [lo, hi];
}
function bendRadius(o, span) {
  if (o.radius !== undefined) return o.radius;
  if (o.angle) return span / o.angle;
  return Infinity;
}

/**
 * Space-warp factories. Each returns fn(v: THREE.Vector3) that moves v in place — pass it to
 * deform(), chain() or placeOn(). All parameters must be explicit (no bounding-box defaults).
 */
export function bendMap(o = {}) {
  if (typeof o === 'number') o = { radius: o };
  const R = o.radius, c = o.center ?? 0;
  if (!isFinite(R) || !R) return () => {};
  return o.axis === 'x'
    ? (v) => { const th = (v.y - c) / R, r = R + v.z; v.y = c + r * Math.sin(th); v.z = r * Math.cos(th) - R; }
    : (v) => { const th = (v.x - c) / R, r = R + v.z; v.x = c + r * Math.sin(th); v.z = r * Math.cos(th) - R; };
}
export function crownMap(o = {}) {
  const hw = (o.w ?? 0.2) / 2, hh = (o.h ?? 0.2) / 2, cx = o.cx ?? 0, cy = o.cy ?? 0, a = o.amount ?? 0.003;
  return (v) => {
    const u = clamp((v.x - cx) / hw, -1, 1), w = clamp((v.y - cy) / hh, -1, 1);
    v.z -= a * (1 - (1 - u * u) * (1 - w * w));
  };
}
export function foldMap(o = {}) {
  const A = o.angle ?? 0.3, w = Math.max(1e-5, o.width ?? 0.003), at = o.at ?? 0;
  if (!A) return () => {};
  const side = o.side ?? (at < 0 ? -1 : 1); // which side of the crease swings
  const R = w / A, c0 = -w / 2, cA = Math.cos(A), sA = Math.sin(A);
  const k = o.axis === 'x' ? 'y' : 'x';
  const h = o.symmetric ? -A / 2 : 0, ch = Math.cos(h), sh = Math.sin(h);
  return (v) => {
    // work in "distance past the crease" on the swinging side
    let p = (v[k] - at) * side, z = v.z;
    const s = p - c0;
    if (s > 0) {
      const th = Math.min(s, w) / R, r = R + v.z, e = Math.max(0, s - w);
      p = c0 + r * Math.sin(th) + e * cA;
      z = r * Math.cos(th) - R - e * sA;
    }
    if (h) {
      // tilt the whole sheet by half the fold so both wings lean back equally
      const q = p;
      p = q * ch + z * sh;
      z = -q * sh + z * ch;
    }
    v[k] = at + p * side;
    v.z = z;
  };
}
/** chain(fn, fn, …) → one map applying the given maps in order. */
export function chain(...fns) {
  return (v) => {
    for (const f of fns) f(v);
  };
}

/** bend(geometry, { radius | angle, axis = 'y', center = 0 }) — cylindrical bend. See API section. */
export function bend(geo, o = {}) {
  if (typeof o === 'number') o = { radius: o };
  const sp = spanOf(geo, o.axis === 'x' ? 'y' : 'x');
  const R = bendRadius(o, sp[1] - sp[0]);
  if (!isFinite(R) || R === 0) return geo;
  return deform(geo, bendMap({ ...o, radius: R }));
}

/** crown(geometry, { amount, w, h, cx = 0, cy = 0 }) — pillow the XY sheet: centre stays, edges drop by `amount`. */
export function crown(geo, o = {}) {
  if (typeof o === 'number') o = { amount: o };
  const sx = spanOf(geo, 'x'), sy = spanOf(geo, 'y');
  return deform(geo, crownMap({
    amount: o.amount, w: o.w ?? sx[1] - sx[0], h: o.h ?? sy[1] - sy[0],
    cx: o.cx ?? (o.w === undefined ? (sx[0] + sx[1]) / 2 : 0), cy: o.cy ?? (o.h === undefined ? (sy[0] + sy[1]) / 2 : 0),
  }));
}

/** fold(geometry, { at, angle, axis = 'y', width = 0.003, side, symmetric }) — crease a sheet. See API section. */
export function fold(geo, o = {}) {
  return o.angle === 0 ? geo : deform(geo, foldMap(o));
}

/** taper(geometry, { axis = 'y', from, to, scale: [s0, s1] | [[sa0, sb0], [sa1, sb1]] }) — scale the cross-section along an axis. */
export function taper(geo, o = {}) {
  const axis = o.axis || 'y';
  const sp = spanOf(geo, axis);
  const a = o.from ?? sp[0], b = o.to ?? sp[1];
  const sc = o.scale ?? [1, 0.7];
  const s0 = Array.isArray(sc[0]) ? sc[0] : [sc[0], sc[0]], s1 = Array.isArray(sc[1]) ? sc[1] : [sc[1], sc[1]];
  const [p, q] = axis === 'y' ? ['x', 'z'] : axis === 'x' ? ['y', 'z'] : ['x', 'y'];
  return deform(geo, (v) => {
    const u = clamp((v[axis] - a) / (b - a || 1), 0, 1);
    v[p] *= mix(s0[0], s1[0], u);
    v[q] *= mix(s0[1], s1[1], u);
  });
}

/** twist(geometry, { axis = 'y', angle, from, to }) — rotate progressively about an axis (0 at `from`, `angle` at `to`). */
export function twist(geo, o = {}) {
  const axis = o.axis || 'y';
  const sp = spanOf(geo, axis);
  const a = o.from ?? sp[0], b = o.to ?? sp[1], ang = o.angle ?? 0.5;
  // rotation about +axis by the right-hand rule
  const [p, q] = axis === 'y' ? ['z', 'x'] : axis === 'x' ? ['y', 'z'] : ['x', 'y'];
  return deform(geo, (v) => {
    const th = ang * clamp((v[axis] - a) / (b - a || 1), 0, 1), c = Math.cos(th), s = Math.sin(th);
    const P = v[p], Q = v[q];
    v[p] = P * c - Q * s;
    v[q] = P * s + Q * c;
  });
}

/** mirrorX(geometry) → NEW geometry mirrored across the YZ plane (x → -x), correctly wound. */
export function mirrorX(geo) {
  const g = geo.clone();
  const P = g.attributes.position.array, N = g.attributes.normal ? g.attributes.normal.array : null;
  for (let i = 0; i < P.length; i += 3) {
    P[i] = -P[i];
    if (N) N[i] = -N[i];
  }
  if (g.index) {
    const I = g.index.array;
    for (let i = 0; i < I.length; i += 3) { const t = I[i + 1]; I[i + 1] = I[i + 2]; I[i + 2] = t; }
  } else if (N) orientTriangles(P, N, null);
  g.computeBoundingBox();
  g.computeBoundingSphere();
  return g;
}

// ---- wraps ---------------------------------------------------------------------------
function normStations(o) {
  let st;
  if (o.profile) st = o.profile.map((s) => [s[0], s[1], s[2] ?? s[1], s[3] || 0, s[4] || 0]);
  else {
    const y0 = o.y0 ?? 0, y1 = o.y1 ?? (o.len !== undefined ? y0 + o.len : 0.2);
    const r0 = Array.isArray(o.r0) ? o.r0 : [o.r0 ?? o.r ?? 0.05, o.r0 ?? o.r ?? 0.05];
    const r1 = o.r1 === undefined ? r0 : Array.isArray(o.r1) ? o.r1 : [o.r1, o.r1];
    st = [[y0, r0[0], r0[1], 0, 0], [y1, r1[0], r1[1], 0, 0]];
  }
  st.sort((a, b) => a[0] - b[0]);
  return st;
}
/** Interpolator over stations [y, …values]; smooth = Catmull-Rom (non-uniform), linear extrapolation outside. */
function stationInterp(st, smooth) {
  const n = st.length, m = st[0].length;
  const tan = st.map((s, i) => {
    const a = st[Math.max(0, i - 1)], b = st[Math.min(n - 1, i + 1)];
    const dy = b[0] - a[0] || 1;
    return s.map((_, k) => (b[k] - a[k]) / dy);
  });
  return (y, out) => {
    let i = 0;
    while (i < n - 2 && y > st[i + 1][0]) i++;
    const a = st[i], b = st[Math.min(n - 1, i + 1)];
    const h = b[0] - a[0] || 1;
    const u = (y - a[0]) / h;
    for (let k = 1; k < m; k++) {
      if (!smooth || n < 3) out[k - 1] = a[k] + (b[k] - a[k]) * u;
      else if (u < 0) out[k - 1] = a[k] + tan[i][k] * (y - a[0]);
      else if (u > 1) out[k - 1] = b[k] + tan[i + 1][k] * (y - b[0]);
      else {
        const u2 = u * u, u3 = u2 * u;
        out[k - 1] = (2 * u3 - 3 * u2 + 1) * a[k] + (u3 - 2 * u2 + u) * h * tan[i][k] + (-2 * u3 + 3 * u2) * b[k] + (u3 - u2) * h * tan[i + 1][k];
      }
    }
  };
}
function tubeSetup(o) {
  const st = normStations(o);
  const smooth = o.smooth !== false && st.length > 2;
  const f = stationInterp(st, smooth);
  let mean = 0;
  for (const s of st) mean += (s[1] + s[2]) / 2;
  mean /= st.length;
  const Rref = o.Rref ?? mean;
  const at = o.at ?? 0, pw = o.power ?? 2;
  const tmp = [0, 0, 0, 0];
  const base = (th, out) => {
    let q = 1;
    if (pw !== 2) q = Math.pow(Math.pow(Math.abs(Math.cos(th)), pw) + Math.pow(Math.abs(Math.sin(th)), pw), -1 / pw);
    out[0] = tmp[0] * q * Math.sin(th);
    out[1] = tmp[1] * q * Math.cos(th);
  };
  const b0 = [0, 0], b1 = [0, 0], b2 = [0, 0];
  const fn = (v) => {
    const th = at + v.x / Rref;
    f(v.y, tmp);
    base(th, b0);
    let nx, nz;
    if (pw === 2) { nx = tmp[1] * Math.sin(th); nz = tmp[0] * Math.cos(th); }
    else { base(th + 1e-4, b1); base(th - 1e-4, b2); nx = -(b1[1] - b2[1]); nz = b1[0] - b2[0]; }
    const l = Math.hypot(nx, nz) || 1;
    v.set(tmp[2] + b0[0] + (v.z * nx) / l, v.y, tmp[3] + b0[1] + (v.z * nz) / l);
  };
  return { st, smooth, Rref, fn, y0: st[0][0], y1: st[st.length - 1][0] };
}

/** tubeMap(opts) / domeMap(opts) → the space warps behind wrapTube() / wrapDome() (and tubeShell / domeShell). */
export const tubeMap = (o = {}) => tubeSetup(o).fn;
export const domeMap = (o = {}) => domeSetup(o).fn;

/**
 * placeOn(object, map, [x, y, z = 0], spin = 0) — seat an Object3D on a warped sheet: its
 * position becomes map([x, y, z]) and it is rotated so local +Z follows the warped surface
 * normal and local +X the warped +X direction (then turned `spin` radians about +Z). Returns it.
 */
export function placeOn(obj, map, p, spin = 0) {
  const e = 1e-4;
  const o = _v0.set(p[0], p[1], p[2] || 0);
  const px = _v1.copy(o), py = _v2.copy(o);
  px.x += e;
  py.y += e;
  map(o);
  map(px);
  map(py);
  _mx.subVectors(px, o).normalize();
  _my.subVectors(py, o);
  _mz.crossVectors(_mx, _my).normalize();
  _my.crossVectors(_mz, _mx);
  obj.quaternion.setFromRotationMatrix(_mb.makeBasis(_mx, _my, _mz));
  if (spin) obj.quaternion.multiply(_q0.setFromAxisAngle(POLE_A, spin));
  obj.position.copy(o);
  return obj;
}

/** wrapTube(geometry, opts) — wrap flat geometry onto a tapered elliptical tube about Y. See API section. */
export function wrapTube(geo, o = {}) {
  return deform(geo, tubeSetup(o).fn);
}

const POLES = { z: [0, 0, 0], '+z': [0, 0, 0], '-z': [0, PI, 0], y: [-PI / 2, 0, 0], '+y': [-PI / 2, 0, 0], '-y': [PI / 2, 0, 0], x: [0, PI / 2, 0], '+x': [0, PI / 2, 0], '-x': [0, -PI / 2, 0] };
function domeSetup(o) {
  const rx = o.rx ?? o.r ?? 0.1, ry = o.ry ?? o.r ?? rx, rz = o.rz ?? o.r ?? rx;
  const Rref = o.Rref ?? (rx + ry + rz) / 3;
  const q = new THREE.Quaternion();
  const pole = o.pole ?? 'z';
  if (typeof pole === 'string') q.setFromEuler(new THREE.Euler(...(POLES[pole] || POLES.z)));
  else q.setFromUnitVectors(new THREE.Vector3(0, 0, 1), toV3(pole).normalize());
  const d = new THREE.Vector3();
  const fn = (v) => {
    const rho = Math.hypot(v.x, v.y), al = rho / Rref;
    const s = rho < 1e-9 ? 1 / Rref : Math.sin(al) / rho;
    const z = v.z;
    d.set(v.x * s, v.y * s, Math.cos(al)).applyQuaternion(q);
    const nx = d.x / rx, ny = d.y / ry, nz = d.z / rz, l = Math.hypot(nx, ny, nz) || 1;
    v.set(d.x * rx + (z * nx) / l, d.y * ry + (z * ny) / l, d.z * rz + (z * nz) / l);
  };
  return { rx, ry, rz, Rref, fn };
}

/** wrapDome(geometry, opts) — drape flat geometry over an ellipsoid (azimuthal-equidistant). See API section. */
export function wrapDome(geo, o = {}) {
  return deform(geo, domeSetup(o).fn);
}

// =====================================================================================
// 5. Revolve engine + lathe
// =====================================================================================
function revolve(outl, o) {
  const closedLoop = o.closedLoop !== false;
  const a0 = o.a0 ?? 0, a1 = o.a1 ?? TAU;
  const full = Math.abs(a1 - a0) >= TAU - 1e-6;
  const ns = Math.max(full ? 3 : 1, Math.round(((o.segs ?? 24) * Math.abs(a1 - a0)) / TAU));
  const n = outl.length;
  const mb = new MeshBuilder();
  const S = [0];
  let rRef = 0;
  for (let j = 0; j < n; j++) {
    const p = outl[j], q = outl[(j + 1) % n];
    S.push(S[j] + Math.hypot(q.x - p.x, q.y - p.y));
    rRef = Math.max(rRef, p.x);
  }
  const edges = closedLoop ? n : n - 1;
  const AX = 1e-7;
  if (!o.facet) {
    const inn = [], out = [];
    for (let i = 0; i <= ns; i++) {
      const th = a0 + ((a1 - a0) * i) / ns, s = Math.sin(th), c = Math.cos(th);
      inn.push(new Array(n));
      out.push(new Array(n));
      for (let j = 0; j < n; j++) {
        const p = outl[j], r = Math.max(0, p.x);
        out[i][j] = mb.v(r * s, p.y, r * c, p.bx * s, p.by, p.bx * c, th * rRef, S[j]);
        inn[i][j] = p.sharp || (closedLoop && j === 0) ? mb.v(r * s, p.y, r * c, p.ax * s, p.ay, p.ax * c, th * rRef, j === 0 && closedLoop ? S[n] : S[j]) : out[i][j];
      }
    }
    for (let j = 0; j < edges; j++) {
      const k = (j + 1) % n, rj = outl[j].x, rk = outl[k].x;
      if (rj < AX && rk < AX) continue;
      for (let i = 0; i < ns; i++) {
        if (rj < AX) mb.tri(out[i][j], inn[i][k], inn[i + 1][k]);
        else if (rk < AX) mb.tri(out[i][j], inn[i][k], out[i + 1][j]);
        else mb.quad(out[i][j], inn[i][k], inn[i + 1][k], out[i + 1][j]);
      }
    }
  } else {
    // faceted: flat facets around the axis (hex heads, nuts), profile creases still honoured
    for (let i = 0; i < ns; i++) {
      const t0 = a0 + ((a1 - a0) * i) / ns, t1 = a0 + ((a1 - a0) * (i + 1)) / ns, tm = (t0 + t1) / 2;
      const s0 = Math.sin(t0), c0 = Math.cos(t0), s1 = Math.sin(t1), c1 = Math.cos(t1), sm = Math.sin(tm), cm = Math.cos(tm);
      for (let j = 0; j < edges; j++) {
        const k = (j + 1) % n, p = outl[j], q = outl[k], rj = Math.max(0, p.x), rk = Math.max(0, q.x);
        if (rj < AX && rk < AX) continue;
        const A = mb.v(rj * s0, p.y, rj * c0, p.bx * sm, p.by, p.bx * cm, t0 * rRef, S[j]);
        const B = mb.v(rk * s0, q.y, rk * c0, q.ax * sm, q.ay, q.ax * cm, t0 * rRef, S[j + 1]);
        const C = mb.v(rk * s1, q.y, rk * c1, q.ax * sm, q.ay, q.ax * cm, t1 * rRef, S[j + 1]);
        const D = mb.v(rj * s1, p.y, rj * c1, p.bx * sm, p.by, p.bx * cm, t1 * rRef, S[j]);
        mb.quad(A, B, C, D);
      }
    }
  }
  if (!full && closedLoop && o.caps !== false) {
    const V = [];
    for (const p of outl) V.push(Math.max(0, p.x), p.y);
    const T = triangulateLoops(V, [Array.from({ length: n }, (_, i) => i)]);
    for (const [th, sg] of [[a0, a1 > a0 ? -1 : 1], [a1, a1 > a0 ? 1 : -1]]) {
      const s = Math.sin(th), c = Math.cos(th);
      const ids = [];
      for (let j = 0; j < n; j++) ids.push(mb.v(V[j * 2] * s, V[j * 2 + 1], V[j * 2] * c, sg * c, 0, -sg * s, V[j * 2], V[j * 2 + 1]));
      for (let k = 0; k < T.length; k += 3) mb.tri(ids[T[k]], ids[T[k + 1]], ids[T[k + 2]]);
    }
  }
  return mb.build();
}

/** Turn an open profile polyline into a closed cross-section of wall thickness t. */
function thickenProfile(pts, t, rim) {
  const n = pts.length, off = [];
  for (let i = 0; i < n; i++) {
    const a = pts[Math.max(0, i - 1)], b = pts[i], c = pts[Math.min(n - 1, i + 1)];
    let d0x = b[0] - a[0], d0y = b[1] - a[1], d1x = c[0] - b[0], d1y = c[1] - b[1];
    if (i === 0) { d0x = d1x; d0y = d1y; }
    if (i === n - 1) { d1x = d0x; d1y = d0y; }
    const l0 = Math.hypot(d0x, d0y) || 1, l1 = Math.hypot(d1x, d1y) || 1;
    const n0x = -d0y / l0, n0y = d0x / l0, n1x = -d1y / l1, n1y = d1x / l1; // left normals
    const k = 1 / Math.max(0.2, 1 + n0x * n1x + n0y * n1y);
    off.push([(n0x + n1x) * k, (n0y + n1y) * k, (d0x / l0) * (d1y / l1) - (d0y / l0) * (d1x / l1)]);
  }
  let mL = 0, mY = 0;
  for (let i = 0; i < n; i++) { mL += off[i][0]; mY += off[i][1]; }
  // thicken toward the axis; flat (washer-like) profiles thicken toward -Y
  let sgn = Math.abs(mL) > 1e-6 * n ? (mL < 0 ? 1 : -1) : mY < 0 ? 1 : -1;
  if (t < 0) sgn = -sgn;
  const T = Math.abs(t);
  const outer = pts.map((p) => [p[0], p[1], p[2] || 0]);
  const inner = pts.map((p, i) => {
    let rr = 0;
    if (p[2] > 0 && i > 0 && i < n - 1) rr = off[i][2] * sgn > 0 ? Math.max(0, p[2] - T) : p[2] + T;
    return [Math.max(0, p[0] + sgn * off[i][0] * T), p[1] + sgn * off[i][1] * T, rr];
  });
  outer[0][2] = outer[n - 1][2] = inner[0][2] = inner[n - 1][2] = rim;
  return [...outer, ...inner.reverse()];
}

/**
 * lathe(points, opts) — revolve a profile [[radius, y, cornerRadius?], …] about +Y. See API section.
 */
export function lathe(points, o = {}) {
  const bevel = o.bevel ?? 0.0006;
  let outl, closedLoop = true;
  // points on the axis are never rounded
  const fix = (pts) => pts.map((p) => [p[0], p[1], p[0] < 1e-7 ? 0 : p[2]]);
  if (o.t) outl = buildOutline(fix(thickenProfile(points.map((p) => [p[0], p[1], p[2] ?? 0]), o.t, o.rim ?? Math.min(bevel, Math.abs(o.t) * 0.35))), { r: 0, crease: o.crease, detail: o.detail });
  else if (o.closed === false) {
    closedLoop = false;
    outl = buildOutline(fix(points), { r: bevel, crease: o.crease, detail: o.detail, closed: false });
  } else outl = buildOutline(fix(points), { r: bevel, crease: o.crease, detail: o.detail });
  const g = revolve(outl, { a0: o.arc ? o.arc[0] : 0, a1: o.arc ? o.arc[1] : TAU, segs: o.segs ?? 24, facet: o.facet, closedLoop, caps: o.caps });
  return orientAxis(g, o.axis);
}

const AXIS_ROT = { y: null, '+y': null, x: [0, 0, -PI / 2], '+x': [0, 0, -PI / 2], '-x': [0, 0, PI / 2], z: [PI / 2, 0, 0], '+z': [PI / 2, 0, 0], '-z': [-PI / 2, 0, 0], '-y': [PI, 0, 0] };
const _m4 = new THREE.Matrix4(), _eu = new THREE.Euler();
/** Re-aim a geometry built along +Y so its axis points along `axis` ('x' | 'y' | 'z' | '-x' | '-y' | '-z'). */
function orientAxis(g, axis) {
  const r = AXIS_ROT[axis || 'y'];
  if (r) g.applyMatrix4(_m4.makeRotationFromEuler(_eu.set(r[0], r[1], r[2])));
  return g;
}

/** cylinder(opts) — bevelled cylinder / cone / tube about +Y. See API section. */
export function cylinder(o = {}) {
  const r = o.r ?? 0.01, r1 = o.r1 ?? r, len = o.len ?? 0.02;
  const y0 = o.y0 ?? -len / 2, y1 = y0 + len;
  const b = o.bevel ?? Math.min(0.0006, r * 0.2, len * 0.2), b1 = o.bevel1 ?? b;
  const h = o.hole || 0;
  const pts = h > 0
    ? [[h, y0, Math.min(b, (r - h) * 0.3)], [r, y0, b], [r1, y1, b1], [Math.min(h, r1 * 0.95), y1, Math.min(b1, (r1 - h) * 0.3)]]
    : [[0, y0], [r, y0, b], [r1, y1, b1], [0, y1]];
  return lathe(pts, { segs: o.segs ?? clamp(Math.round(10 + r * 900), 10, 40), bevel: 0, arc: o.arc, facet: o.facet, axis: o.axis, detail: o.detail });
}

// =====================================================================================
// 6. Shells
// =====================================================================================
/** bl, br, tr, tl corner list → outline points with per-corner radius / 45° clips (shared by plate & shells). */
function cornerPts(corners, R, C) {
  const pts = [];
  for (let i = 0; i < 4; i++) {
    const P = corners[i], A = corners[(i + 3) % 4], B = corners[(i + 1) % 4];
    if (C[i] > 0) {
      const la = Math.hypot(A[0] - P[0], A[1] - P[1]), lb = Math.hypot(B[0] - P[0], B[1] - P[1]);
      const ca = Math.min(C[i], la * 0.45), cb = Math.min(C[i], lb * 0.45);
      pts.push([P[0] + ((A[0] - P[0]) / la) * ca, P[1] + ((A[1] - P[1]) / la) * ca, R[i]]);
      pts.push([P[0] + ((B[0] - P[0]) / lb) * cb, P[1] + ((B[1] - P[1]) / lb) * cb, R[i]]);
    } else pts.push([P[0], P[1], R[i]]);
  }
  return pts;
}
const four = (v, d) => (Array.isArray(v) ? v : new Array(4).fill(v ?? d));

/** Flat ribbon: closed 2D profile (outline x → Z, outline y → Y) extruded along X. */
function extrudeProfileX(outl, x0, x1, nx) {
  const mb = new MeshBuilder();
  const n = outl.length;
  const S = [0];
  for (let j = 0; j < n; j++) {
    const p = outl[j], q = outl[(j + 1) % n];
    S.push(S[j] + Math.hypot(q.x - p.x, q.y - p.y));
  }
  const inn = [], out = [];
  for (let i = 0; i <= nx; i++) {
    const x = mix(x0, x1, i / nx);
    inn.push(new Array(n));
    out.push(new Array(n));
    for (let j = 0; j < n; j++) {
      const p = outl[j];
      out[i][j] = mb.v(x, p.y, p.x, 0, p.by, p.bx, x, S[j]);
      inn[i][j] = p.sharp || j === 0 ? mb.v(x, p.y, p.x, 0, p.ay, p.ax, x, j === 0 ? S[n] : S[j]) : out[i][j];
    }
  }
  for (let j = 0; j < n; j++) {
    const k = (j + 1) % n;
    for (let i = 0; i < nx; i++) mb.quad(out[i][j], inn[i][k], inn[i + 1][k], out[i + 1][j]);
  }
  return mb.build();
}

/** tubeShell(opts) — full or partial tapered elliptical tube section with wall thickness. See API section. */
export function tubeShell(o = {}) {
  const arc = o.arc;
  const full = !arc || Math.abs(arc[1] - arc[0]) >= TAU - 1e-6;
  const mid = full ? o.at ?? 0 : (arc[0] + arc[1]) / 2;
  const tube = tubeSetup({ ...o, at: mid });
  const { y0, y1, Rref } = tube;
  const t = o.t ?? 0.005, bevel = o.bevel ?? 0.0012;
  const H = y1 - y0;
  const ny = o.segs ? o.segs[1] : tube.st.length > 2 ? clamp(Math.ceil(H / 0.03), 4, 14) : 1;
  let g;
  if (full) {
    const nx = o.segs ? o.segs[0] : 32;
    const bi = Math.min(o.bevelBack ?? bevel * 0.6, t * 0.4), bo = Math.min(bevel, t * 0.5);
    const pts = [[-t, y0, bi], [0, y0, bo]];
    for (let i = 1; i < ny; i++) pts.push([0, mix(y0, y1, i / ny), 0]);
    pts.push([0, y1, bo], [-t, y1, bi]);
    for (let i = ny - 1; i > 0; i--) pts.push([-t, mix(y0, y1, i / ny), 0]);
    g = extrudeProfileX(buildOutline(pts, { cornerSegs: 1 }), -PI * Rref, PI * Rref, nx);
  } else {
    const W = (arc[1] - arc[0]) * Rref;
    const A = ([a, y, r]) => [(a - mid) * Rref, y, r];
    const outline = o.outline
      ? o.outline.map(A)
      : cornerPts([[-W / 2, y0], [W / 2, y0], [W / 2, y1], [-W / 2, y1]], four(o.r, Math.min(0.006, Math.abs(W) * 0.2, H * 0.2)), four(o.cut, 0));
    const holes = (o.holes || []).map((h) => h.map(A));
    const nx = o.segs ? o.segs[0] : clamp(Math.ceil(Math.abs(arc[1] - arc[0]) / ((o.arcRes ?? 11.25) * DEG)), 2, 64);
    g = extrudeLoops([outline, ...holes], { t, bevel, bevelBack: o.bevelBack, step: o.step, segs: [nx, ny], back: o.back, detail: o.detail, crease: o.crease, holeR: o.holeR });
  }
  return deform(g, tube.fn);
}

/** domeShell(opts) — ellipsoid patch with thickness. See API section. */
export function domeShell(o = {}) {
  const dome = domeSetup(o);
  const R = dome.Rref;
  let outline = o.outline;
  if (!outline) {
    const W = (o.spanX ?? 1.2) * R, H = (o.spanY ?? o.spanX ?? 1.2) * R;
    outline = o.round ? shape.ellipse(W / 2, H / 2) : cornerPts([[-W / 2, -H / 2], [W / 2, -H / 2], [W / 2, H / 2], [-W / 2, H / 2]], four(o.cornerR, Math.min(W, H) * 0.3), four(o.cut, 0));
  }
  const cell = o.cell ?? R * (o.arcRes ?? 12) * DEG;
  const g = extrudeLoops([outline, ...(o.holes || [])], { t: o.t ?? 0.005, bevel: o.bevel ?? 0.0012, bevelBack: o.bevelBack, step: o.step, cell, back: o.back, detail: o.detail, crease: o.crease, holeR: o.holeR });
  return deform(g, dome.fn);
}

// =====================================================================================
// 7. Utilities: geometry merging, meshes, placement
// =====================================================================================
function normGeo(g) {
  for (const k of Object.keys(g.attributes)) if (k !== 'position' && k !== 'normal' && k !== 'uv') g.deleteAttribute(k);
  if (!g.attributes.normal) g.computeVertexNormals();
  if (!g.attributes.uv) g.setAttribute('uv', new THREE.BufferAttribute(new Float32Array(g.attributes.position.count * 2), 2));
  if (!g.index) {
    const n = g.attributes.position.count;
    const idx = n > 65535 ? new Uint32Array(n) : new Uint16Array(n);
    for (let i = 0; i < n; i++) idx[i] = i;
    g.setIndex(new THREE.BufferAttribute(idx, 1));
  }
  g.morphAttributes = {};
  return g;
}

/** mergeGeos([geometry, …]) → one BufferGeometry (position, normal, uv, indexed). Inputs are consumed. */
export function mergeGeos(list) {
  const gs = list.filter(Boolean).map(normGeo);
  if (!gs.length) return new THREE.BufferGeometry();
  if (gs.length === 1) return gs[0];
  const g = mergeGeometries(gs, false);
  g.computeBoundingBox();
  g.computeBoundingSphere();
  return g;
}

/** xf(geometry, { pos, rot, scale }) — transform a geometry in place (rot = Euler XYZ radians) and return it. */
export function xf(g, o = {}) {
  const s = o.scale === undefined ? [1, 1, 1] : typeof o.scale === 'number' ? [o.scale, o.scale, o.scale] : o.scale;
  const r = o.rot || [0, 0, 0], p = o.pos || [0, 0, 0];
  _m4.compose(_v0.set(p[0], p[1], p[2]), _q0.setFromEuler(_eu.set(r[0], r[1], r[2])), _v1.set(s[0], s[1], s[2]));
  g.applyMatrix4(_m4);
  if (s[0] * s[1] * s[2] < 0 && g.index) {
    const I = g.index.array;
    for (let i = 0; i < I.length; i += 3) { const t = I[i + 1]; I[i + 1] = I[i + 2]; I[i + 2] = t; }
  }
  return g;
}
const _q0 = new THREE.Quaternion();

/** mesh(geometry, material, { pos, rot, scale, name, shadow }) → THREE.Mesh. */
export function mesh(geometry, material, o = {}) {
  const m = new THREE.Mesh(geometry, material);
  place(m, o);
  if (o.name) m.name = o.name;
  if (o.shadow === false) m.userData.castShadow = false;
  return m;
}

/** place(object, { pos, rot, scale }) — set an object's transform (rot = Euler XYZ radians); returns the object. */
export function place(obj, o = {}) {
  if (o.pos) obj.position.set(o.pos[0], o.pos[1], o.pos[2] || 0);
  if (o.rot) obj.rotation.set(o.rot[0], o.rot[1], o.rot[2]);
  if (o.scale !== undefined) typeof o.scale === 'number' ? obj.scale.setScalar(o.scale) : obj.scale.set(o.scale[0], o.scale[1], o.scale[2]);
  return obj;
}

/** count(object) → { tris, meshes } for an Object3D subtree (budget checks while building). */
export function count(obj) {
  let tris = 0, meshes = 0;
  obj.traverse((o) => {
    if (!o.isMesh || !o.geometry) return;
    meshes++;
    tris += (o.geometry.index ? o.geometry.index.count : o.geometry.attributes.position.count) / 3;
  });
  return { tris, meshes };
}

/** dynamic(object) — flag an Object3D as animated so merge() keeps it (and batches inside it separately). */
export function dynamic(obj) {
  obj.userData.dynamic = true;
  return obj;
}

const _mx = new THREE.Vector3(), _my = new THREE.Vector3(), _mz = new THREE.Vector3(), _mb = new THREE.Matrix4();
const POLE_A = new THREE.Vector3(0, 0, 1), POLE_B = new THREE.Vector3(1, 0, 0);
/**
 * aim(object, a, b, pole?) — put `object` at point a with its +Y axis pointing at point b and
 * its +Z axis as close as possible to `pole` (default +Z; falls back to +X when a→b is parallel
 * to the pole). a, b, pole are THREE.Vector3 in the object's PARENT space. Allocation-free;
 * returns the distance |b - a|.
 */
export function aim(obj, a, b, pole) {
  _my.subVectors(b, a);
  const dist = _my.length();
  if (dist < 1e-9) _my.set(0, 1, 0);
  else _my.multiplyScalar(1 / dist);
  let p = pole || POLE_A;
  if (Math.abs(_my.dot(p)) > 0.985 * p.length()) p = Math.abs(_my.x) < 0.9 ? POLE_B : POLE_A;
  _mx.crossVectors(_my, p).normalize();
  _mz.crossVectors(_mx, _my);
  obj.quaternion.setFromRotationMatrix(_mb.makeBasis(_mx, _my, _mz));
  obj.position.copy(a);
  return dist;
}

function flipWinding(g) {
  const I = g.index.array;
  for (let i = 0; i < I.length; i += 3) { const t = I[i + 1]; I[i + 1] = I[i + 2]; I[i + 2] = t; }
}
function isIdentityXf(o) {
  return o.position.lengthSq() === 0 && o.quaternion.x === 0 && o.quaternion.y === 0 && o.quaternion.z === 0 && o.scale.x === 1 && o.scale.y === 1 && o.scale.z === 1;
}

/**
 * merge(root, opts) — static batching, IN PLACE. See API section.
 */
export function merge(root, o = {}) {
  const buckets = new Map();
  const visit = (node, M) => {
    for (const child of node.children.slice()) {
      if (child.userData.dynamic || (o.skip && o.skip(child))) {
        merge(child, o); // rigid sub-assembly: batch its own static contents
        continue;
      }
      child.updateMatrix();
      const m = new THREE.Matrix4().multiplyMatrices(M, child.matrix);
      if (child.isMesh && !child.isInstancedMesh && !child.isSkinnedMesh && !Array.isArray(child.material) && child.material && child.visible && !child.userData.noMerge && child.geometry && child.geometry.attributes.position) {
        const key = child.material.uuid + '|' + (child.userData.castShadow === false ? 0 : 1) + (child.userData.receiveShadow === false ? 0 : 1) + '|' + child.renderOrder;
        let b = buckets.get(key);
        if (!b) buckets.set(key, (b = { material: child.material, proto: child, items: [] }));
        b.items.push({ mesh: child, matrix: m });
      }
      visit(child, m);
    }
  };
  visit(root, new THREE.Matrix4());
  for (const b of buckets.values()) {
    if (b.items.length === 1 && b.items[0].mesh.parent === root && isIdentityXf(b.items[0].mesh)) continue; // already a single draw
    const geos = b.items.map(({ mesh: me, matrix }) => {
      const g = normGeo(me.geometry.clone());
      g.applyMatrix4(matrix);
      if (matrix.determinant() < 0) flipWinding(g);
      return g;
    });
    const merged = new THREE.Mesh(mergeGeos(geos), b.material);
    merged.name = 'merged:' + (b.material.name || b.proto.name || 'mesh');
    merged.renderOrder = b.proto.renderOrder;
    if (b.proto.userData.castShadow === false) merged.userData.castShadow = false;
    if (b.proto.userData.receiveShadow === false) merged.userData.receiveShadow = false;
    merged.castShadow = b.proto.castShadow;
    merged.receiveShadow = b.proto.receiveShadow;
    for (const { mesh: me } of b.items) {
      let parent = me.parent;
      if (me.children.length) {
        // keep the node (as a plain Group) for the sake of its children
        const g = new THREE.Group();
        g.name = me.name;
        g.position.copy(me.position);
        g.quaternion.copy(me.quaternion);
        g.scale.copy(me.scale);
        Object.assign(g.userData, me.userData);
        for (const c of me.children.slice()) g.add(c);
        parent.add(g);
        parent.remove(me);
        continue;
      }
      parent.remove(me);
      // drop groups that the merge emptied
      while (parent && parent !== root && parent.children.length === 0 && parent.type === 'Group' && !parent.userData.dynamic && !parent.userData.keep) {
        const up = parent.parent;
        if (!up) break;
        up.remove(parent);
        parent = up;
      }
    }
    root.add(merged);
  }
  return root;
}

/**
 * mirrorObjectX(object) → deep copy of an Object3D tree mirrored across the YZ plane (x → -x)
 * with properly mirrored geometry (no negative scales). Materials are shared.
 */
export function mirrorObjectX(obj, cache = new Map()) {
  let c;
  if (obj.isMesh) {
    let g = cache.get(obj.geometry);
    if (!g) cache.set(obj.geometry, (g = mirrorX(obj.geometry)));
    c = new THREE.Mesh(g, obj.material);
    c.castShadow = obj.castShadow;
    c.receiveShadow = obj.receiveShadow;
    c.renderOrder = obj.renderOrder;
  } else c = new obj.constructor();
  c.name = obj.name;
  c.visible = obj.visible;
  Object.assign(c.userData, obj.userData);
  c.position.set(-obj.position.x, obj.position.y, obj.position.z);
  c.quaternion.set(obj.quaternion.x, -obj.quaternion.y, -obj.quaternion.z, obj.quaternion.w);
  c.scale.copy(obj.scale);
  for (const ch of obj.children) c.add(mirrorObjectX(ch, cache));
  return c;
}

// ---- internal: multi-material part assembly ----------------------------------------------
let _fallbackMat = null;
/** Resolve material slots: opts.mats[slot] → library[name…] → the single material passed. null/false omits a slot. */
function slots(m, overrides, spec) {
  const out = {};
  const single = m && m.isMaterial ? m : null;
  for (const k in spec) {
    let v = overrides ? overrides[k] : undefined;
    if (v === undefined) {
      if (single) v = single;
      else if (m) for (const name of spec[k]) if (m[name] && m[name].isMaterial) { v = m[name]; break; }
      if (v === undefined) {
        if (!_fallbackMat) {
          _fallbackMat = new THREE.MeshStandardMaterial({ color: 0x777b84, metalness: 0.8, roughness: 0.45 });
          console.warn('[kit] part built without materials — pass ctx.mat (or opts.mats) as the last argument');
        }
        v = _fallbackMat;
      }
    }
    out[k] = v || null;
  }
  return out;
}
class Part {
  constructor(name) {
    this.name = name;
    this.map = new Map();
  }
  add(material, geo, o) {
    if (!material || !geo) return this;
    if (o) xf(geo, o);
    let l = this.map.get(material);
    if (!l) this.map.set(material, (l = []));
    l.push(geo);
    return this;
  }
  /** → Group with one merged mesh per material; geometry re-aimed from +Y (or +Z if fromZ) to `axis`. */
  build(axis, into, fromZ) {
    const g = into || new THREE.Group();
    if (!into) g.name = this.name;
    for (const [m, list] of this.map) {
      const me = new THREE.Mesh((fromZ ? orientFromZ : orientAxis)(mergeGeos(list), axis), m);
      me.name = this.name + ':' + (m.name || 'part');
      g.add(me);
    }
    return g;
  }
}
/** Re-aim a geometry built facing +Z so it faces `axis`. */
const FROM_Z = { z: null, '+z': null, '-z': [0, PI, 0], y: [-PI / 2, 0, 0], '+y': [-PI / 2, 0, 0], '-y': [PI / 2, 0, 0], x: [0, PI / 2, 0], '+x': [0, PI / 2, 0], '-x': [0, -PI / 2, 0] };
function orientFromZ(g, axis) {
  const r = FROM_Z[axis || 'z'];
  if (r) g.applyMatrix4(_m4.makeRotationFromEuler(_eu.set(r[0], r[1], r[2])));
  return g;
}

// =====================================================================================
// 8. Paths, tubes, cables, coils
// =====================================================================================
/** Control points → smooth, adaptively sampled path (array of Vector3). */
function smoothPath(points, o = {}) {
  const pts = points.map((p) => toV3(p));
  let fine;
  if (o.straight && pts.length > 2 && !o.sag) return filletPolyline(pts, o.bendR ?? (o.r ?? 0.003) * 3);
  if (pts.length === 2 || o.straight) {
    fine = [];
    for (let i = 0; i < pts.length - 1; i++) {
      const n = o.sag ? 28 : 1;
      for (let k = 0; k < n; k++) fine.push(pts[i].clone().lerp(pts[i + 1], k / n));
    }
    fine.push(pts[pts.length - 1].clone());
  } else {
    fine = new THREE.CatmullRomCurve3(pts, !!o.closed, 'centripetal').getPoints((pts.length - (o.closed ? 0 : 1)) * 14);
    if (o.closed) fine.pop();
  }
  if (o.sag) {
    const S = [0];
    for (let i = 1; i < fine.length; i++) S.push(S[i - 1] + fine[i].distanceTo(fine[i - 1]));
    const dir = toV3(o.sagDir || [0, -1, 0]);
    for (let i = 0; i < fine.length; i++) {
      const u = S[i] / (S[S.length - 1] || 1);
      fine[i].addScaledVector(dir, o.sag * 4 * u * (1 - u));
    }
  }
  if (o.straight && !o.sag) return fine;
  // adaptive decimation: keep a sample whenever the path has turned by maxAngle since the last kept one
  const maxA = (o.maxAngle ?? 7) * DEG;
  const out = [fine[0]];
  let acc = 0;
  const d0 = new THREE.Vector3(), d1 = new THREE.Vector3();
  for (let i = 1; i < fine.length - 1; i++) {
    d0.subVectors(fine[i], fine[i - 1]).normalize();
    d1.subVectors(fine[i + 1], fine[i]).normalize();
    acc += Math.acos(clamp(d0.dot(d1), -1, 1));
    if (acc >= maxA) {
      out.push(fine[i]);
      acc = 0;
    }
  }
  if (!o.closed) out.push(fine[fine.length - 1]);
  return out;
}

/** Straight runs joined by circular bends of radius R (hard lines, formed tube). */
function filletPolyline(pts, R) {
  const out = [pts[0].clone()];
  const d0 = new THREE.Vector3(), d1 = new THREE.Vector3(), c = new THREE.Vector3(), a = new THREE.Vector3(), b = new THREE.Vector3();
  for (let i = 1; i < pts.length - 1; i++) {
    d0.subVectors(pts[i], pts[i - 1]);
    d1.subVectors(pts[i + 1], pts[i]);
    const l0 = d0.length(), l1 = d1.length();
    d0.multiplyScalar(1 / l0);
    d1.multiplyScalar(1 / l1);
    const phi = Math.acos(clamp(d0.dot(d1), -1, 1));
    if (phi < 0.02 || R <= 0) { out.push(pts[i].clone()); continue; }
    const tl = Math.min(R * Math.tan(phi / 2), l0 * 0.48, l1 * 0.48), r = tl / Math.tan(phi / 2);
    a.copy(pts[i]).addScaledVector(d0, -tl);
    b.copy(pts[i]).addScaledVector(d1, tl);
    // centre: from the corner along the inner bisector
    c.subVectors(d1, d0).normalize().multiplyScalar(r / Math.cos(phi / 2)).add(pts[i]);
    const n = Math.max(2, Math.ceil(phi / (12 * DEG)));
    const ra = a.clone().sub(c), rb = b.clone().sub(c);
    for (let k = 0; k <= n; k++) {
      // slerp between the two radius vectors
      const u = k / n, s = Math.sin(phi);
      const wa = Math.sin((1 - u) * phi) / s, wb = Math.sin(u * phi) / s;
      out.push(new THREE.Vector3().copy(c).addScaledVector(ra, wa).addScaledVector(rb, wb));
    }
  }
  out.push(pts[pts.length - 1].clone());
  return out;
}

/** Parallel-transport frames along a polyline → { T, N, B, S } arrays. */
function pathFrames(P, closed, up) {
  const n = P.length;
  const T = [], N = [], B = [], S = [0];
  for (let i = 0; i < n; i++) {
    const a = P[closed ? (i + n - 1) % n : Math.max(0, i - 1)], b = P[closed ? (i + 1) % n : Math.min(n - 1, i + 1)];
    T.push(new THREE.Vector3().subVectors(b, a).normalize());
    if (i > 0) S.push(S[i - 1] + P[i].distanceTo(P[i - 1]));
  }
  const n0 = new THREE.Vector3();
  if (up) n0.copy(up);
  else {
    const t = T[0], ax = Math.abs(t.x), ay = Math.abs(t.y), az = Math.abs(t.z);
    n0.set(ax <= ay && ax <= az ? 1 : 0, ay < ax && ay <= az ? 1 : 0, az < ax && az < ay ? 1 : 0);
  }
  n0.addScaledVector(T[0], -n0.dot(T[0])).normalize();
  N.push(n0);
  const axis = new THREE.Vector3(), q = new THREE.Quaternion();
  for (let i = 1; i < n; i++) {
    const nn = N[i - 1].clone();
    axis.crossVectors(T[i - 1], T[i]);
    const l = axis.length();
    if (l > 1e-9) {
      q.setFromAxisAngle(axis.multiplyScalar(1 / l), Math.atan2(l, T[i - 1].dot(T[i])));
      nn.applyQuaternion(q);
    }
    nn.addScaledVector(T[i], -nn.dot(T[i])).normalize();
    N.push(nn);
  }
  for (let i = 0; i < n; i++) B.push(new THREE.Vector3().crossVectors(T[i], N[i]));
  return { T, N, B, S };
}

/** Round tube along a polyline. r = number | (u:0..1) => radius. */
function sweepTube(P, o = {}) {
  const n = P.length, radial = o.radial ?? 7, closed = !!o.closed;
  const { T, N, B, S } = pathFrames(P, closed, o.up);
  const total = S[n - 1] || 1;
  const rad = typeof o.r === 'function' ? o.r : () => o.r ?? 0.003;
  const mb = new MeshBuilder();
  const rings = [];
  for (let i = 0; i < n; i++) {
    const r = rad(S[i] / total);
    const ring = [];
    for (let j = 0; j <= radial; j++) {
      const a = (j / radial) * TAU, c = Math.cos(a), s = Math.sin(a);
      const dx = N[i].x * c + B[i].x * s, dy = N[i].y * c + B[i].y * s, dz = N[i].z * c + B[i].z * s;
      ring.push(mb.v(P[i].x + dx * r, P[i].y + dy * r, P[i].z + dz * r, dx, dy, dz, a * r, S[i]));
    }
    rings.push(ring);
  }
  const m = closed ? n : n - 1;
  for (let i = 0; i < m; i++) {
    const A = rings[i], Bn = rings[(i + 1) % n];
    for (let j = 0; j < radial; j++) mb.quad(A[j], A[j + 1], Bn[j + 1], Bn[j]);
  }
  if (!closed && o.caps !== false) {
    for (const [i, sg] of [[0, -1], [n - 1, 1]]) {
      const r = rad(i === 0 ? 0 : 1);
      const c = mb.v(P[i].x, P[i].y, P[i].z, sg * T[i].x, sg * T[i].y, sg * T[i].z, 0, 0);
      const ids = [];
      for (let j = 0; j <= radial; j++) {
        const a = (j / radial) * TAU, cs = Math.cos(a), sn = Math.sin(a);
        const dx = N[i].x * cs + B[i].x * sn, dy = N[i].y * cs + B[i].y * sn, dz = N[i].z * cs + B[i].z * sn;
        ids.push(mb.v(P[i].x + dx * r, P[i].y + dy * r, P[i].z + dz * r, sg * T[i].x, sg * T[i].y, sg * T[i].z, dx * r, dy * r));
      }
      for (let j = 0; j < radial; j++) mb.tri(c, ids[j], ids[j + 1]);
    }
  }
  const g = mb.build();
  g.userData.ends = [{ pos: P[0].clone(), dir: T[0].clone().negate() }, { pos: P[n - 1].clone(), dir: T[n - 1].clone() }];
  return g;
}

/** cable(points, opts) — round cable / hose / hard line through control points. See API section. */
export function cable(points, o = {}) {
  const P = smoothPath(points, o);
  return sweepTube(P, { r: o.r ?? 0.003, radial: o.radial ?? (o.r > 0.006 ? 10 : 7), caps: o.caps, closed: o.closed });
}

/** coil(opts) — helical winding about +Y. See API section. */
export function coil(o = {}) {
  const r = o.r ?? 0.01, len = o.len ?? 0.03, wire = o.wire ?? 0.0012;
  const turns = o.turns ?? Math.max(1, len / (wire * 2.15));
  const per = o.perTurn ?? 14;
  const y0 = o.y0 ?? -len / 2;
  const n = Math.max(2, Math.round(turns * per));
  const P = [];
  for (let i = 0; i <= n; i++) {
    const u = i / n, a = u * turns * TAU;
    const rr = r + (o.r1 !== undefined ? (o.r1 - r) * u : 0);
    P.push(new THREE.Vector3(rr * Math.sin(a), y0 + wire + (len - 2 * wire) * u, rr * Math.cos(a)));
  }
  return orientAxis(sweepTube(P, { r: wire, radial: o.radial ?? 5, up: new THREE.Vector3(0, 1, 0) }), o.axis);
}

/** bellows(opts) — rubber gaiter / flexible boot about +Y. See API section. */
export function bellows(o = {}) {
  const r0 = o.r0 ?? o.r ?? 0.02, r1 = o.r1 ?? r0, len = o.len ?? 0.04, folds = o.folds ?? 5;
  const depth = o.depth ?? Math.min(r0, r1) * 0.22;
  const y0 = o.y0 ?? -len / 2;
  const pts = [];
  const n = folds * 2;
  for (let i = 0; i <= n; i++) {
    const u = i / n, r = mix(r0, r1, u) - (i % 2 === 1 ? 0 : depth);
    pts.push([i === 0 || i === n ? mix(r0, r1, u) - depth * 0.5 : r, y0 + len * u, i === 0 || i === n ? 0 : (len / n) * 0.42]);
  }
  return lathe(pts, { closed: false, bevel: 0, segs: o.segs ?? 20, axis: o.axis, crease: 180, arc: o.arc });
}

// =====================================================================================
// 9. Energy accents
// =====================================================================================
/** energyStrip(points, opts) — thin raised emissive strip along a polyline. See API section. */
export function energyStrip(points, o = {}) {
  const w = o.w ?? 0.004, h = o.h ?? 0.0008, sink = o.sink ?? 0.0004;
  const ch = Math.min(o.chamfer ?? h * 0.8, w * 0.3);
  const closed = !!o.closed, maxSeg = o.maxSeg ?? 0.02;
  const up0 = toV3(o.up || [0, 0, 1]).normalize();
  const src = points.map((p) => toV3(p));
  const srcN = o.normals ? o.normals.map((p) => toV3(p).normalize()) : null;
  const P = [], U = [];
  const m = closed ? src.length : src.length - 1;
  for (let i = 0; i < m; i++) {
    const a = src[i], b = src[(i + 1) % src.length];
    const k = Math.max(1, Math.ceil(a.distanceTo(b) / maxSeg));
    for (let j = 0; j < k; j++) {
      P.push(a.clone().lerp(b, j / k));
      U.push(srcN ? srcN[i].clone().lerp(srcN[(i + 1) % src.length], j / k).normalize() : up0.clone());
    }
  }
  if (!closed) {
    P.push(src[src.length - 1].clone());
    U.push(srcN ? srcN[src.length - 1].clone() : up0.clone());
  }
  const n = P.length;
  const mb = new MeshBuilder();
  const tin = new THREE.Vector3(), tout = new THREE.Vector3(), T = new THREE.Vector3(), L = new THREE.Vector3(), Uv = new THREE.Vector3();
  const st = [];
  let S = 0;
  const frames = [];
  for (let i = 0; i < n; i++) {
    const prev = P[closed ? (i + n - 1) % n : Math.max(0, i - 1)], next = P[closed ? (i + 1) % n : Math.min(n - 1, i + 1)];
    tin.subVectors(P[i], prev);
    tout.subVectors(next, P[i]);
    if (tin.lengthSq() < 1e-18) tin.copy(tout);
    if (tout.lengthSq() < 1e-18) tout.copy(tin);
    tin.normalize();
    tout.normalize();
    T.addVectors(tin, tout).normalize();
    Uv.copy(U[i]).addScaledVector(T, -U[i].dot(T)).normalize();
    L.crossVectors(Uv, T);
    const k = 1 / Math.max(0.35, Math.sqrt(Math.max(0, (1 + tin.dot(tout)) / 2)));
    if (i > 0) S += P[i].distanceTo(P[i - 1]);
    const p = P[i];
    const hw = (w / 2) * k, tw = (w / 2 - ch) * k;
    const mk = (side, top) => {
      const d = side * (top ? tw : hw), z = top ? h : -sink;
      const nx = top ? Uv.x : side * L.x, ny = top ? Uv.y : side * L.y, nz = top ? Uv.z : side * L.z;
      return mb.v(p.x + L.x * d + Uv.x * z, p.y + L.y * d + Uv.y * z, p.z + L.z * d + Uv.z * z, nx, ny, nz, S, side * (top ? w / 2 - ch : w / 2));
    };
    st.push([mk(1, false), mk(1, true), mk(-1, true), mk(-1, false)]);
    frames.push({ T: T.clone(), L: L.clone(), U: Uv.clone() });
  }
  const segs = closed ? n : n - 1;
  for (let i = 0; i < segs; i++) {
    const a = st[i], b = st[(i + 1) % n];
    mb.quad(a[0], a[1], b[1], b[0]);
    mb.quad(a[1], a[2], b[2], b[1]);
    mb.quad(a[2], a[3], b[3], b[2]);
  }
  if (!closed) {
    const round = o.ends !== 'square';
    for (const [i, sg] of [[0, -1], [n - 1, 1]]) {
      const f = frames[i], p = P[i];
      if (!round) {
        const s = st[i];
        const q = [0, 1, 2, 3].map((k) => {
          const a = s[k] * 3, top = k === 1 || k === 2;
          return mb.v(mb.P[a], mb.P[a + 1], mb.P[a + 2], top ? f.U.x : sg * f.T.x, top ? f.U.y : sg * f.T.y, top ? f.U.z : sg * f.T.z, 0, 0);
        });
        mb.quad(q[0], q[1], q[2], q[3]);
        continue;
      }
      const ms = o.endSegs ?? 6;
      const c = mb.v(p.x + f.U.x * h, p.y + f.U.y * h, p.z + f.U.z * h, f.U.x, f.U.y, f.U.z, 0, 0);
      let pb = -1, pt = -1;
      for (let j = 0; j <= ms; j++) {
        const be = (PI * j) / ms, cs = Math.cos(be), sn = Math.sin(be) * sg;
        const dx = f.L.x * cs + f.T.x * sn, dy = f.L.y * cs + f.T.y * sn, dz = f.L.z * cs + f.T.z * sn;
        const rb = w / 2, rt = w / 2 - ch;
        const vb = mb.v(p.x + dx * rb - f.U.x * sink, p.y + dy * rb - f.U.y * sink, p.z + dz * rb - f.U.z * sink, dx, dy, dz, 0, rb);
        const vt = mb.v(p.x + dx * rt + f.U.x * h, p.y + dy * rt + f.U.y * h, p.z + dz * rt + f.U.z * h, f.U.x, f.U.y, f.U.z, 0, rt);
        if (j > 0) {
          mb.quad(pb, pt, vt, vb);
          mb.tri(c, pt, vt);
        }
        pb = vb;
        pt = vt;
      }
    }
  }
  return mb.build();
}

/** energyRing(opts) — emissive ring: flat annulus ('z') or band around a cylinder ('radial'). See API section. */
export function energyRing(o = {}) {
  const r = o.r ?? 0.03, w = o.w ?? 0.004, h = o.h ?? 0.0008, sink = o.sink ?? 0.0004;
  const ch = Math.min(o.chamfer ?? h * 0.8, w * 0.3);
  const segs = o.segs ?? clamp(Math.round(16 + r * 500), 16, 64);
  if (o.facing === 'radial') {
    return lathe([[r - sink, -w / 2], [r + h, -w / 2 + ch], [r + h, w / 2 - ch], [r - sink, w / 2]], { closed: false, bevel: 0, segs, arc: o.arc, axis: o.axis });
  }
  const g = lathe([[r + w / 2, -sink], [r + w / 2 - ch, h], [r - w / 2 + ch, h], [r - w / 2, -sink]], { closed: false, bevel: 0, segs, arc: o.arc });
  return orientFromZ(orientAxis(g, 'z'), o.axis);
}

// =====================================================================================
// 10. Fasteners
// =====================================================================================
/** Head with an optional hex recess. prof = outer skin [[r, y, nr, ny], …] bottom → top (12 sides). */
function recessHead(prof, rh, depth, segs = 12) {
  const mb = new MeshBuilder();
  const rings = prof.map(([r, y, nr, ny]) => {
    const ids = [];
    for (let j = 0; j <= segs; j++) {
      const th = (j / segs) * TAU, s = Math.sin(th), c = Math.cos(th);
      ids.push(mb.v(r * s, y, r * c, nr * s, ny, nr * c, th * r, y));
    }
    return ids;
  });
  for (let k = 0; k < prof.length - 1; k++) for (let j = 0; j < segs; j++) mb.quad(rings[k][j], rings[k + 1][j], rings[k + 1][j + 1], rings[k][j + 1]);
  const [rt, yt] = prof[prof.length - 1];
  const C = [];
  for (let j = 0; j <= segs; j++) {
    const th = (j / segs) * TAU;
    C.push(mb.v(rt * Math.sin(th), yt, rt * Math.cos(th), 0, 1, 0, rt * Math.sin(th), rt * Math.cos(th)));
  }
  if (!(rh > 0)) {
    const c = mb.v(0, yt, 0, 0, 1, 0, 0, 0);
    for (let j = 0; j < segs; j++) mb.tri(c, C[j], C[j + 1]);
    return mb.build();
  }
  const hx = (k, y, nx, ny, nz) => {
    const th = (k / 6) * TAU;
    return mb.v(rh * Math.sin(th), y, rh * Math.cos(th), nx, ny, nz, rh * Math.sin(th), rh * Math.cos(th));
  };
  const H = [];
  for (let k = 0; k <= 6; k++) H.push(hx(k, yt, 0, 1, 0));
  for (let k = 0; k < 6; k++) {
    mb.tri(C[2 * k], C[2 * k + 1], H[k]);
    mb.tri(C[2 * k + 1], H[k + 1], H[k]);
    mb.tri(C[2 * k + 1], C[2 * k + 2], H[k + 1]);
    const tm = ((k + 0.5) / 6) * TAU, nx = -Math.sin(tm), nz = -Math.cos(tm);
    mb.quad(hx(k, yt, nx, 0, nz), hx(k + 1, yt, nx, 0, nz), hx(k + 1, yt - depth, nx, 0, nz), hx(k, yt - depth, nx, 0, nz));
  }
  const f = [];
  for (let k = 0; k < 6; k++) f.push(hx(k, yt - depth, 0, 1, 0));
  mb.tri(f[0], f[1], f[2]); mb.tri(f[0], f[2], f[3]); mb.tri(f[0], f[3], f[4]); mb.tri(f[0], f[4], f[5]);
  return mb.build();
}

/** bolt(opts) — one fastener head, seated on z = 0 and rising along +Z. See API section. */
export function bolt(o = {}) {
  const type = o.type || 'socket', d = o.d ?? 0.005, R = d / 2;
  let g;
  if (type === 'hex') {
    const h = o.h ?? d * 0.5, Rc = R / Math.cos(PI / 6), hf = h * 0.2, c = h * 0.16;
    g = mergeGeos([
      lathe([[R * 1.25, 0], [R * 1.25, hf, hf * 0.6], [R * 0.9, hf]], { closed: false, bevel: 0, segs: 12 }),
      lathe([[Rc, hf * 0.9], [Rc, h - c], [Rc - c * 1.5, h], [0, h]], { closed: false, bevel: 0, segs: 6, facet: true }),
    ]);
  } else if (type === 'button') {
    const h = o.h ?? d * 0.32;
    g = recessHead([[R, 0, 1, 0], [R, h * 0.2, 0.96, 0.28], [R * 0.86, h * 0.72, 0.6, 0.8], [R * 0.62, h, 0.12, 0.99]], d * 0.22, h * 0.62);
  } else if (type === 'flush') {
    const h = o.h ?? Math.max(0.00025, d * 0.07);
    g = recessHead([[R, 0, 0.7, 0.7], [R - h * 1.2, h, 0, 1]], d * 0.24, h * 0.9);
  } else if (type === 'rivet') {
    const h = o.h ?? d * 0.3;
    g = recessHead([[R, 0, 1, 0], [R * 0.94, h * 0.45, 0.85, 0.52], [R * 0.66, h * 0.86, 0.5, 0.87], [R * 0.3, h, 0.15, 0.99]], 0, 0);
  } else if (type === 'pan') {
    const h = o.h ?? d * 0.26;
    g = recessHead([[R, 0, 1, 0], [R * 0.86, h, 0.45, 0.9]], 0, 0, 8);
  } else {
    const h = o.h ?? d * 0.6, c = d * 0.07;
    g = recessHead([[R, 0, 1, 0], [R, h - c, 1, 0], [R - c, h, 0, 1]], d * 0.29, h * 0.52);
  }
  if (o.washer) g = mergeGeos([g, lathe([[R * 1.45, 0], [R * 1.45, d * 0.07, d * 0.03], [R * 0.9, d * 0.07]], { closed: false, bevel: 0, segs: 12 })]);
  return orientFromZ(orientAxis(g, 'z'), o.axis);
}

/** boltsAt(points, opts) — merged bolts at [[x, y, z?], …] (heads along +Z, then re-aimed by opts.axis). See API section. */
export function boltsAt(points, o = {}) {
  const base = bolt({ ...o, axis: 'z' });
  const list = points.map((p, i) => {
    const g = base.clone();
    if (o.spin !== false) g.rotateZ(hash(i * 3.17 + (o.seed || 0)) * TAU);
    g.translate(p[0], p[1], p[2] || 0);
    return g;
  });
  return orientFromZ(mergeGeos(list), o.axis);
}

/** boltCircle(opts) — n bolts on a circle of radius R in the XY plane (before opts.axis). See API section. */
export function boltCircle(o = {}) {
  const n = o.n ?? 6, R = o.R ?? 0.02, start = o.start ?? 0, arc = o.arc ?? TAU, c = o.center || [0, 0, 0];
  const pts = [];
  const full = Math.abs(arc) >= TAU - 1e-6;
  for (let i = 0; i < n; i++) {
    const a = start + (arc * i) / (full ? n : Math.max(1, n - 1));
    pts.push([c[0] + R * Math.cos(a), c[1] + R * Math.sin(a), c[2] || 0]);
  }
  return boltsAt(pts, o);
}

/** boltRow(opts) — n bolts evenly spaced from `from` to `to` (inclusive). See API section. */
export function boltRow(o = {}) {
  const n = o.n ?? 4, a = o.from || [-0.03, 0, 0], b = o.to || [0.03, 0, 0];
  const pts = [];
  for (let i = 0; i < n; i++) {
    const u = n === 1 ? 0.5 : i / (n - 1);
    pts.push([mix(a[0], b[0], u), mix(a[1], b[1], u), mix(a[2] || 0, b[2] || 0, u)]);
  }
  return boltsAt(pts, o);
}

// =====================================================================================
// 11. Mechanical parts (Groups: one pre-merged mesh per material slot)
// =====================================================================================
/** servo(opts, mats) — rotary actuator about +Y, centred on the origin. See API section. */
export function servo(o = {}, m) {
  const M = slots(m, o.mats, { body: ['frame'], cap: ['titaniumDark', 'titanium'], steel: ['steel'], rubber: ['rubber'], accent: ['energyDim', 'energy'], copper: ['copper'] });
  const r = o.r ?? 0.022, len = o.len ?? 0.05, detail = o.detail ?? 1;
  const segs = o.segs ?? (detail >= 2 ? 32 : detail >= 1 ? 20 : 12);
  const L2 = len / 2, hb = L2 * 0.56, rc = r * 0.94, rb = r * 0.82;
  const P = new Part('servo');
  // finned stator barrel, copper windings glinting between the fins
  const nf = detail >= 1 ? o.fins ?? (detail >= 2 ? 5 : 4) : 0;
  const prof = [[rc * 0.97, -hb]];
  if (nf > 0) {
    const pitch = (2 * hb) / nf, e = pitch * 0.07;
    for (let k = 0; k < nf; k++) {
      const ya = -hb + k * pitch + pitch * 0.24, yb = ya + pitch * 0.52;
      prof.push([rb, ya - e], [r, ya + e], [r, yb - e], [rb, yb + e]);
    }
    if (M.copper) P.add(M.copper, lathe([[rb + r * 0.012, -hb * 0.98], [rb + r * 0.012, hb * 0.98]], { closed: false, bevel: 0, segs }));
  } else prof.push([rb, -hb * 0.86], [rb, hb * 0.86]);
  prof.push([rc * 0.97, hb]);
  P.add(M.body, lathe(prof, { closed: false, bevel: 0, segs }));
  // end caps
  const c = Math.min(0.0015, r * 0.07), bh = r * 0.09;
  P.add(M.cap, lathe([[0, -L2], [rc, -L2, c], [rc, -hb]], { closed: false, bevel: 0, segs }));
  P.add(M.cap, lathe([[rc, hb], [rc, L2, c], [rc * 0.6, L2, 0], [rc * 0.6, L2 + bh, c * 0.5], [r * 0.2, L2 + bh, 0]], { closed: false, bevel: 0, segs }));
  // output shaft with splined tip
  const sl = o.shaft === false ? 0 : o.shaft ?? r * 0.45, rs = r * 0.24;
  if (sl > 0) {
    P.add(M.steel, lathe([[rs, L2 + bh], [rs, L2 + bh + sl * 0.5, 0], [rs * 0.86, L2 + bh + sl * 0.5, 0]], { closed: false, bevel: 0, segs: 16 }));
    P.add(M.steel, lathe([[rs * 0.9, L2 + bh + sl * 0.5], [rs * 0.9, L2 + bh + sl, rs * 0.12], [0, L2 + bh + sl]], { closed: false, bevel: 0, segs: 8, facet: true }));
  } else P.add(M.cap, lathe([[r * 0.2, L2 + bh], [0, L2 + bh]], { closed: false, bevel: 0, segs }));
  // cap screws
  const nb = o.bolts ?? (detail >= 1 ? 6 : 0);
  if (nb > 0) {
    const bo = { n: nb, R: rc * 0.8, d: r * 0.17, type: detail >= 2 ? 'socket' : 'pan' };
    P.add(M.steel, boltCircle({ ...bo, axis: 'y', center: [0, 0, 0] }), { pos: [0, L2, 0] });
    P.add(M.steel, boltCircle({ ...bo, axis: '-y', start: 0.5 }), { pos: [0, -L2, 0] });
  }
  // status ring on the output-side cap
  if (M.accent && o.accent !== false) P.add(M.accent, energyRing({ r: rc, w: (L2 - hb) * 0.24, h: r * 0.012, sink: r * 0.01, facing: 'radial', segs }), { pos: [0, hb + (L2 - hb) * 0.5, 0] });
  // cable gland on the rear cap
  const ga = o.glandAngle ?? PI;
  const gy = -(hb + (L2 - hb) * 0.5), rg = Math.min((L2 - hb) * 0.36, r * 0.2);
  // junction box on the rear cap (detail ≥ 1): the gland leaves through it
  const jb = detail >= 1 && o.gland !== false && o.box !== false ? r * 0.26 : 0;
  const g0 = jb ? rc * 0.9 + jb : rc * 0.92; // radius where the gland starts
  const gl = { pos: new THREE.Vector3(Math.sin(ga) * (g0 + rg * 3.4), gy, Math.cos(ga) * (g0 + rg * 3.4)), dir: new THREE.Vector3(Math.sin(ga), 0, Math.cos(ga)) };
  if (jb) {
    const b = chamferBox(r * 0.78, (L2 - hb) * 0.92, jb + rc * 0.25, { bevel: Math.min(0.0008, r * 0.03), r: Math.min(0.002, r * 0.08) });
    b.translate(0, 0, rc * 0.9 + jb - (jb + rc * 0.25) / 2);
    b.applyMatrix4(_m4.makeRotationY(ga));
    b.translate(0, gy, 0);
    P.add(M.cap, b);
  }
  if (sl > 0 && M.rubber && detail >= 1) P.add(M.rubber, lathe([[rs * 1.75, L2 + bh], [rs * 1.75, L2 + bh + r * 0.03, r * 0.012], [rs * 1.02, L2 + bh + r * 0.03]], { closed: false, bevel: 0, segs: 16 }));
  if (o.gland !== false) {
    const T = { pos: [Math.sin(ga) * g0, gy, Math.cos(ga) * g0] };
    // tip the +Y-built fitting over to +Z, then swing it about Y to the gland angle
    const gx = (g) => { g.applyMatrix4(_m4.makeRotationX(PI / 2)); g.applyMatrix4(_m4.makeRotationY(ga)); g.translate(T.pos[0], T.pos[1], T.pos[2]); return g; };
    P.add(M.cap, gx(lathe([[rg * 1.3, 0], [rg * 1.3, rg * 0.9, rg * 0.15], [rg * 0.95, rg * 0.9]], { closed: false, bevel: 0, segs: 6, facet: true })));
    P.add(M.steel, gx(lathe([[rg * 0.95, rg * 0.9], [rg * 0.95, rg * 1.7, rg * 0.2], [rg * 0.7, rg * 1.7]], { closed: false, bevel: 0, segs: 10 })));
    P.add(M.rubber, gx(lathe([[rg * 0.74, rg * 1.7], [rg * 0.62, rg * 3.4, rg * 0.2], [0, rg * 3.4]], { closed: false, bevel: 0, segs: 10 })));
  }
  const g = P.build(o.axis);
  // the glowing status ring must not cast shadows
  if (M.accent && M.accent !== M.body && M.accent !== M.cap) for (const c of g.children) if (c.material === M.accent) c.userData.castShadow = false;
  const ar = AXIS_ROT[o.axis || 'y'];
  if (ar) {
    _eu.set(ar[0], ar[1], ar[2]);
    gl.pos.applyEuler(_eu);
    gl.dir.applyEuler(_eu);
  }
  g.userData.gland = gl;
  return g;
}

/** piston(opts, mats) — linear actuator between two points with a per-frame .aim(a, b). See API section. */
export function piston(o = {}, m) {
  const M = slots(m, o.mats, { body: ['titaniumDark', 'frame'], steel: ['steel'], rod: ['steel'] });
  const a = toV3(o.a || [0, 0, 0]), b = toV3(o.b || [0, 0.2, 0]);
  const d0 = a.distanceTo(b) || 0.2;
  const r = o.r ?? clamp(d0 * 0.045, 0.003, 0.02);
  const min = Math.max(o.min ?? d0 * 0.78, r * 6.5);
  const yB = min - 1.75 * r; // gland face (top of the barrel)
  const rodLen = min - 1.5 * r; // rod reaches this far below the rod-eye centre
  const max = Math.max(min, Math.min(o.max ?? d0 * 1.22, yB - 1.2 * r + rodLen));
  const rr = r * 0.42, segs = o.segs ?? clamp(Math.round(10 + r * 600), 12, 20);
  const root = dynamic(new THREE.Group());
  root.name = 'piston';
  const P = new Part('piston');
  // base eye + pin
  P.add(M.body, cylinder({ r: r * 0.9, len: r * 1.5, bevel: r * 0.14, segs, axis: 'x' }));
  P.add(M.steel, cylinder({ r: r * 0.42, len: r * 2.1, bevel: r * 0.08, segs: 10, axis: 'x' }));
  // barrel: neck, base cap, tube, gland collar
  P.add(M.body, lathe([[r * 0.52, r * 0.5], [r * 0.52, r * 0.95, 0], [r * 1.12, r * 0.95, r * 0.1], [r * 1.12, r * 1.85, r * 0.1], [r, r * 1.85, 0], [r, yB - r * 1.35, 0], [r * 1.14, yB - r * 1.35, r * 0.08], [r * 1.14, yB - r * 0.3, r * 0.14], [r * 0.66, yB, r * 0.05], [rr * 1.1, yB, 0], [rr * 1.1, yB - r * 0.3, 0]], { closed: false, bevel: 0, segs }));
  if (o.ports !== false && yB > r * 5) {
    const y1 = r * 1.4, y2 = yB - r * 0.82, z = r * 1.12;
    for (const y of [y1, y2]) P.add(M.steel, cylinder({ r: r * 0.24, len: r * 0.6, bevel: r * 0.05, segs: 8, axis: 'z' }), { pos: [0, y, z + r * 0.2] });
    P.add(M.steel, cable([[0, y1, z + r * 0.45], [0, y1 + r * 0.5, z + r * 0.75], [0, y2 - r * 0.5, z + r * 0.75], [0, y2, z + r * 0.45]], { r: r * 0.13, radial: 5, maxAngle: 25 }));
  }
  P.build('y', root);
  // rod assembly (origin = rod-eye centre = point b)
  const rod = dynamic(new THREE.Group());
  rod.name = 'piston:rod';
  const Q = new Part('piston:rod');
  Q.add(M.rod, lathe([[rr, -rodLen], [rr, -r * 1.5]], { closed: false, bevel: 0, segs: Math.max(10, segs - 4) }));
  Q.add(M.rod, lathe([[rr, -r * 1.56], [r * 0.7, -r * 1.56, r * 0.06], [r * 0.7, -r * 1.0, r * 0.06], [r * 0.5, -r * 1.0]], { closed: false, bevel: 0, segs: 6, facet: true }));
  Q.add(M.rod, lathe([[r * 0.5, -r * 1.0], [r * 0.5, -r * 0.55]], { closed: false, bevel: 0, segs: 10 }));
  Q.add(M.rod, cylinder({ r: r * 0.82, len: r * 1.2, hole: r * 0.36, bevel: r * 0.14, segs, axis: 'x' }));
  Q.build('y', rod);
  root.add(rod);
  const pole = o.pole ? toV3(o.pole) : null;
  /** Re-aim: a, b = THREE.Vector3 in the piston's parent space (allocation-free). */
  root.aim = (pa, pb) => {
    rod.position.y = clamp(aim(root, pa, pb, pole), min, max);
    return root;
  };
  root.userData.piston = { min, max, rod };
  root.aim(a, b);
  return root;
}

/** hinge(opts, mats) — barrel hinge about +Y, centred on the origin. See API section. */
export function hinge(o = {}, m) {
  const M = slots(m, o.mats, { body: ['titaniumDark', 'frame'], pin: ['steel'] });
  const len = o.len ?? 0.06, r = o.r ?? 0.004, n = Math.max(2, o.knuckles ?? 5), gap = o.gap ?? Math.min(0.0005, len * 0.01);
  const leaf = o.leaf ?? r * 3.2, lt = o.leafT ?? r * 0.45, half = o.half || 'both', ang = o.angle || 0;
  const segs = clamp(Math.round(8 + r * 1200), 10, 20);
  const kl = len / n;
  const P = new Part('hinge');
  for (let k = 0; k < n; k++) {
    const isA = k % 2 === 0;
    if (half !== 'both' && (half === 'a') !== isA) continue;
    const ya = -len / 2 + k * kl + gap / 2, yb = ya + kl - gap, bv = r * 0.14;
    const inner = half === 'both';
    const pr = [];
    if (!inner || k === 0) pr.push([0, ya]);
    pr.push([r - bv, ya], [r, ya + bv], [r, yb - bv], [r - bv, yb]);
    if (!inner || k === n - 1) pr.push([0, yb]);
    P.add(M.body, lathe(pr, { closed: false, bevel: 0, segs, crease: 60 }));
  }
  if (half !== 'b') {
    P.add(M.pin, cylinder({ r: r * 0.5, len: len, segs: 8, bevel: 0 }));
    for (const s of [-1, 1]) P.add(M.pin, lathe([[r * 0.64, 0], [r * 0.64, r * 0.4, r * 0.3], [0, r * 0.4]], { closed: false, bevel: 0, segs: 10 }), { pos: [0, s * len / 2, 0], rot: [s < 0 ? PI : 0, 0, 0] });
  }
  if (leaf > 0) {
    const mk = (isA) => {
      const g = plate({ w: leaf + r * 0.7, h: len, t: lt, r: Math.min(leaf * 0.25, 0.003), bevel: Math.min(lt * 0.35, 0.0006) });
      g.translate(r * 0.3 + (leaf + r * 0.7) / 2, 0, -r + lt);
      const d = Math.min(leaf * 0.42, len * 0.2);
      const bl = boltsAt([[r + leaf * 0.52, -len * 0.3, -r + lt], [r + leaf * 0.52, len * 0.3, -r + lt]], { d, type: 'flush' });
      const gg = [g, bl];
      if (!isA) for (const q of gg) { q.rotateY(PI); q.rotateX(PI); q.rotateY(ang); }
      return gg;
    };
    if (half !== 'b') { const [g, bl] = mk(true); P.add(M.body, g); P.add(M.pin, bl); }
    if (half !== 'a') { const [g, bl] = mk(false); P.add(M.body, g); P.add(M.pin, bl); }
  }
  return P.build(o.axis);
}

/** ballJoint(opts, mats) — ball stud in a bolted socket; origin = ball centre, stud along +Y. See API section. */
export function ballJoint(o = {}, m) {
  const M = slots(m, o.mats, { socket: ['frame', 'titaniumDark'], ball: ['steel'], boot: ['rubber'] });
  const r = o.r ?? 0.012, stud = o.stud ?? r * 1.5, half = o.half || 'both';
  const segs = o.segs ?? clamp(Math.round(12 + r * 700), 16, 32);
  const P = new Part('ballJoint');
  const ro = r * 1.3, lip = 8 * DEG;
  if (half !== 'ball') {
    const arc = [];
    const na = 6;
    for (let i = 1; i <= na; i++) {
      const al = mix(-PI / 2 + 0.5, lip, i / na);
      arc.push([ro * Math.cos(al), ro * Math.sin(al)]);
    }
    arc.push([r * 1.03 * Math.cos(lip), r * 1.03 * Math.sin(lip) - r * 0.02], [r * 1.0 * Math.cos(lip), r * 1.03 * Math.sin(lip) - r * 0.3]);
    P.add(M.socket, lathe(arc, { closed: false, bevel: 0, segs, crease: 40 }));
    P.add(M.socket, lathe([[ro * 0.9, -r * 0.42], [ro * 1.55, -r * 0.42, r * 0.06], [ro * 1.55, -r * 0.12, r * 0.06], [ro * 0.9, -r * 0.12]], { closed: false, bevel: 0, segs }));
    P.add(M.ball, boltCircle({ n: o.bolts ?? 4, R: ro * 1.27, d: r * 0.3, type: 'socket', axis: 'y', start: PI / 4 }), { pos: [0, -r * 0.12, 0] });
  }
  if (half !== 'socket') {
    const prof = [];
    const top = 60 * DEG, nb = 8;
    for (let i = 0; i <= nb; i++) {
      const al = mix(-PI / 2, top, i / nb);
      prof.push([r * Math.cos(al), r * Math.sin(al), 0]);
    }
    const y0 = r * Math.sin(top);
    prof.push([r * 0.44, y0 + r * 0.12, r * 0.08], [r * 0.44, r * 1.28, 0], [r * 0.74, r * 1.28, r * 0.05], [r * 0.74, r * 1.55, r * 0.05], [r * 0.5, r * 1.55, 0], [r * 0.5, r + stud, r * 0.06], [0, r + stud, 0]);
    P.add(M.ball, lathe(prof, { closed: false, bevel: 0, segs, crease: 35 }));
    if (o.boot && M.boot) P.add(M.boot, bellows({ r0: ro * 0.99, r1: r * 0.8, len: r * 1.28 - ro * Math.sin(lip), y0: ro * Math.sin(lip), folds: 3, depth: r * 0.14, segs }));
  }
  return P.build(o.axis);
}

function pathPoint(P, S, s, out) {
  let i = 0;
  while (i < S.length - 2 && S[i + 1] < s) i++;
  const u = clamp((s - S[i]) / (S[i + 1] - S[i] || 1), 0, 1);
  out.pos.copy(P[i]).lerp(P[i + 1], u);
  out.dir.subVectors(P[i + 1], P[i]).normalize();
  return out;
}
const UP_Y = new THREE.Vector3(0, 1, 0);
/** Place a geometry built along +Y at pos, pointing along dir. */
function alongDir(g, pos, dir) {
  _m4.compose(pos, _q0.setFromUnitVectors(UP_Y, dir), _v1.set(1, 1, 1));
  g.applyMatrix4(_m4);
  return g;
}

/** ferrule(opts) — hose end fitting about +Y: hose enters from -Y, nut face at y = 0. See API section. */
export function ferrule(o = {}) {
  const r = o.r ?? 0.003;
  return mergeGeos([
    lathe([[r * 1.02, -r * 5.2], [r * 1.34, -r * 4.9, 0], [r * 1.34, -r * 3.9, 0], [r * 1.2, -r * 3.75, 0], [r * 1.2, -r * 3.35, 0], [r * 1.34, -r * 3.2, 0], [r * 1.34, -r * 1.7, 0], [r * 1.0, -r * 1.6, 0]], { closed: false, bevel: 0, segs: 12 }),
    lathe([[r * 1.0, -r * 1.6], [r * 1.85, -r * 1.6, r * 0.14], [r * 1.85, 0, r * 0.14], [r * 0.9, 0]], { closed: false, bevel: 0, segs: 6, facet: true }),
    lathe([[r * 0.9, 0], [r * 0.9, r * 0.9, r * 0.12], [0, r * 0.9]], { closed: false, bevel: 0, segs: 10 }),
  ]);
}

/** hose(points, opts, mats) — cable with a ferrule on each end. See API section. */
export function hose(points, o = {}, m) {
  const M = slots(m, o.mats, { hose: ['rubber', 'wire'], fitting: ['steel'] });
  const r = o.r ?? 0.004;
  const g = cable(points, { ...o, r, caps: false });
  const P = new Part('hose');
  P.add(M.hose, g);
  for (const e of g.userData.ends) P.add(M.fitting, alongDir(ferrule({ r }), e.pos, e.dir));
  return P.build('y');
}

/** loom(points, opts, mats) — bundle of wires following a path, with tie bands. See API section. */
export function loom(points, o = {}, m) {
  const M = slots(m, o.mats, { wire: ['wire', 'rubber'], accent: ['wireTeal', 'wire'], tie: ['rubber', 'frame'] });
  const n = Math.max(1, o.n ?? 5), r = o.r ?? 0.0018;
  const C = smoothPath(points, o);
  const { T, N, B, S } = pathFrames(C, false);
  const total = S[S.length - 1] || 1;
  const twist = (o.twist ?? 0.6) * TAU;
  const centre = n >= 5, ringN = centre ? n - 1 : n;
  const rho = ringN > 1 ? (r * 1.06) / Math.sin(PI / ringN) : 0;
  const every = o.accentEvery ?? 3;
  const P = new Part('loom');
  for (let j = 0; j < n; j++) {
    const isC = centre && j === n - 1;
    const pts = C.map((p, i) => {
      if (isC) return p.clone();
      const ph = (j / ringN) * TAU + (twist * S[i]) / total;
      return p.clone().addScaledVector(N[i], Math.cos(ph) * rho).addScaledVector(B[i], Math.sin(ph) * rho);
    });
    P.add(every > 0 && j % every === every - 1 ? M.accent : M.wire, sweepTube(pts, { r, radial: o.radial ?? 5 }));
  }
  const pitch = o.tie ?? 0.045;
  if (pitch > 0 && M.tie) {
    const R = rho + r * 1.12, tmp = { pos: new THREE.Vector3(), dir: new THREE.Vector3() };
    const k = Math.max(1, Math.round(total / pitch));
    for (let i = 0; i < k; i++) {
      pathPoint(C, S, (total * (i + 0.5)) / k, tmp);
      P.add(M.tie, alongDir(lathe([[R, -r * 1.3], [R + r * 0.3, -r * 1.0, 0], [R + r * 0.3, r * 1.0, 0], [R, r * 1.3]], { closed: false, bevel: 0, segs: 10 }), tmp.pos, tmp.dir));
    }
  }
  const g = P.build('y');
  g.userData.radius = rho + r;
  return g;
}

/** strut(opts) — inner-frame member along +Y with pivot lugs, lightening holes / I-section. See API section. */
export function strut(o = {}) {
  const y0 = o.y0 ?? 0, y1 = o.y1 ?? y0 + (o.len ?? 0.2);
  const len = Math.abs(y1 - y0);
  const w0 = o.w0 ?? o.w ?? 0.03, w1 = o.w1 ?? o.w ?? w0, d = o.t ?? 0.012;
  const section = o.section || 'I';
  const l0 = o.lug ?? w0 * 0.56, l1 = o.lug1 ?? (o.lug !== undefined ? o.lug : w1 * 0.56);
  const bore = o.bore ?? 0.42;
  const geos = [];
  const wAt = (y) => mix(w0, w1, clamp(y / len, 0, 1));
  const ya = l0 * 1.05, yb = len - l1 * 1.05; // clear span between the lugs
  if (section === 'tube') {
    geos.push(cylinder({ r: w0 * 0.36, r1: w1 * 0.36, len: len, y0: 0, segs: 14, bevel: 0 }));
    for (const [y, w] of [[ya, w0], [yb, w1]]) geos.push(cylinder({ r: wAt(y) * 0.44, len: w * 0.3, y0: y - w * 0.15, segs: 14 }));
  } else {
    const web = section === 'flat' ? d : o.web ?? Math.min(0.003, d * 0.3);
    const holes = [];
    const mode = o.holes === undefined ? 'round' : o.holes;
    const span = yb - ya;
    if (mode && span > 0.02) {
      const inset = section === 'I' ? (o.flange ?? 0.0028) + 0.002 : 0.004;
      if (mode === 'truss') {
        const cnt = Math.max(1, Math.round(span / (((w0 + w1) / 2) * 0.9)));
        const st = span / cnt;
        for (let i = 0; i < cnt; i++) {
          const a = ya + i * st + st * 0.08, b = ya + (i + 1) * st - st * 0.08, up = i % 2 === 0;
          const wa = wAt(a) / 2 - inset, wb = wAt(b) / 2 - inset, rr = Math.min(0.003, st * 0.12);
          holes.push(up ? [[-wa, a, rr], [wa, a, rr], [-wb, b, rr]] : [[wa, a, rr], [wb, b, rr], [-wb, b, rr]]);
        }
      } else {
        const pitchK = mode === 'slot' ? 1.7 : 0.82;
        const cnt = Math.max(1, Math.floor(span / (((w0 + w1) / 2) * pitchK)));
        const st = span / cnt;
        for (let i = 0; i < cnt; i++) {
          const y = ya + (i + 0.5) * st, hr = Math.min(wAt(y) / 2 - inset, st * 0.4);
          if (hr < 0.0015) continue;
          holes.push(mode === 'slot' ? shape.slot(st * 0.78, hr * 1.5, 0, y, PI / 2) : shape.circle(hr, 0, y, clamp(Math.round(10 + hr * 900), 12, 24)));
        }
      }
    }
    const wb = web >= 0.004 ? Math.min(0.0008, web * 0.2) : 0;
    const g = polyPlate([[-w0 / 2, 0], [w0 / 2, 0], [w1 / 2, len], [-w1 / 2, len]], { t: web, bevel: wb, bevelBack: wb, holes, r: 0 });
    g.translate(0, 0, web / 2);
    geos.push(g);
    if (section === 'I') {
      const fl = o.flange ?? 0.0028;
      for (const s of [-1, 1]) {
        const ax = (s * w0) / 2, bx = (s * w1) / 2;
        const L = Math.hypot(bx - ax, len);
        const f = chamferBox(fl, L, d, { bevel: Math.min(0.0007, fl * 0.3) });
        f.rotateZ(-Math.atan2(bx - ax, len));
        f.translate((ax + bx) / 2 - s * fl * 0.5, len / 2, 0);
        geos.push(f);
      }
    }
  }
  for (const [y, R] of [[0, l0], [len, l1]]) {
    if (!(R > 0)) continue;
    const hl = d * 0.59, bv = Math.min(0.001, R * 0.12), rb = R * bore;
    geos.push(xf(lathe([[rb, -hl], [R, -hl, bv], [R, hl, bv], [rb, hl]], { bevel: 0, segs: clamp(Math.round(10 + R * 400), 12, 24), axis: 'z' }), { pos: [0, y, 0] }));
  }
  const g = mergeGeos(geos);
  if (y1 < y0) g.rotateZ(PI);
  g.translate(0, y0, 0);
  return g;
}

const POLE_X = new THREE.Vector3(1, 0, 0);
/** bone(a, b, opts, material) — a strut() Mesh spanning point a → point b. See API section. */
export function bone(a, b, o = {}, material) {
  const va = toV3(a), vb = toV3(b);
  const me = new THREE.Mesh(strut({ ...o, y0: 0, y1: undefined, len: va.distanceTo(vb) }), material);
  aim(me, va, vb, o.pole ? toV3(o.pole) : POLE_X);
  me.name = o.name || 'bone';
  return me;
}

/** grille(opts) — plate with a row of through-slots. Takes every plate() option. See API section. */
export function grille(o = {}) {
  const w = o.w ?? 0.08, h = o.h ?? 0.05, n = o.n ?? 6, mg = o.margin ?? 0.007, vertical = o.dir === 'y';
  const span = (vertical ? w : h) - 2 * mg, pitch = span / n, sw = o.slotW ?? pitch * 0.5;
  const L = (vertical ? h : w) - 2 * mg;
  const holes = [];
  for (let i = 0; i < n; i++) {
    const c = -span / 2 + (i + 0.5) * pitch;
    holes.push(vertical ? shape.slot(L, sw, c, 0, PI / 2) : shape.slot(L, sw, 0, c, 0));
  }
  return plate({ ...o, w, h, holes: [...(o.holes || []), ...holes] });
}

/** vent(opts, mats) — framed louvre vent facing +Z; front face at z = 0, body sinks to z = -depth. See API section. */
export function vent(o = {}, m) {
  const M = slots(m, o.mats, { body: ['titaniumDark', 'frame'], back: ['rubber', 'frame'] });
  const vertical = o.dir === 'y';
  const w = vertical ? o.h ?? 0.04 : o.w ?? 0.08, h = vertical ? o.w ?? 0.08 : o.h ?? 0.04;
  const depth = o.depth ?? 0.008, n = o.slats ?? 5, fr = o.frame ?? 0.005, r = o.r ?? 0.004;
  const ang = clamp(o.angle ?? 50, 10, 85) * DEG; // slat tilt out of the face plane
  const iw = w - 2 * fr, ih = h - 2 * fr, pitch = ih / n;
  const P = new Part('vent');
  P.add(M.body, polyPlate(shape.rect(w, h, r), { t: depth, bevel: o.bevel ?? 0.001, holes: [shape.rect(iw, ih, Math.max(0.0005, r - fr * 0.6))] }));
  const chord = Math.min((depth * 0.9) / Math.sin(ang), (pitch * (o.cover ?? 0.8)) / Math.cos(ang));
  const st = o.slatT ?? Math.min(0.0012, pitch * 0.2);
  for (let k = 0; k < n; k++) {
    const g = chamferBox(iw + 0.0006, st, chord, { bevel: st * 0.3 });
    g.rotateX(-(PI / 2 - ang));
    g.translate(0, -ih / 2 + (k + 0.5) * pitch, -depth * 0.5);
    P.add(M.body, g);
  }
  if (M.back) P.add(M.back, new THREE.PlaneGeometry(iw + 0.001, ih + 0.001), { pos: [0, 0, -depth + 0.0004] });
  const g = P.build('z', null, true);
  if (vertical) for (const c of g.children) c.geometry.rotateZ(PI / 2);
  if (o.axis && o.axis !== 'z') for (const c of g.children) orientFromZ(c.geometry, o.axis);
  return g;
}

/** fins(opts) — heat-sink block: base on z = 0, fins rising along +Z and running along Y. See API section. */
export function fins(o = {}) {
  const w = o.w ?? 0.05, h = o.h ?? 0.04, d = o.d ?? 0.012, n = Math.max(2, o.n ?? 8), base = o.base ?? Math.min(0.002, d * 0.25);
  const ft = o.finT ?? (w / n) * 0.42;
  const geos = [xf(chamferBox(w, h, base, { bevel: Math.min(0.0005, base * 0.3) }), { pos: [0, 0, base / 2] })];
  for (let k = 0; k < n; k++) {
    const x = -w / 2 + ft / 2 + (k * (w - ft)) / (n - 1);
    geos.push(xf(chamferBox(ft, h, d - base + 0.0002, { bevel: Math.min(0.0004, ft * 0.28) }), { pos: [x, 0, base + (d - base) / 2 - 0.0001] }));
  }
  return orientFromZ(mergeGeos(geos), o.axis);
}

/** connector(opts, mats) — multi-pin connector block; mounting face on z = 0, mating face toward +Z. See API section. */
export function connector(o = {}, m) {
  const M = slots(m, o.mats, { shell: ['titaniumDark', 'steel'], body: ['frame', 'rubber'], pin: ['copper', 'steel'] });
  const w = o.w ?? 0.022, h = o.h ?? 0.011, d = o.d ?? 0.012;
  const [cols, rows] = o.pins ?? [6, 2];
  const wall = Math.min(0.0012, h * 0.14), rr = h * 0.24;
  const P = new Part('connector');
  if (o.flange !== false) {
    const fw = Math.max(0.004, h * 0.4), ft = Math.min(0.0016, d * 0.16);
    P.add(M.shell, plate({ w: w + 2 * fw, h: h + 0.003, t: ft, r: Math.min(0.002, h * 0.2), bevel: ft * 0.3 }), { pos: [0, 0, ft] });
    P.add(M.pin, boltsAt([[-(w / 2 + fw * 0.52), 0, ft], [w / 2 + fw * 0.52, 0, ft]], { d: fw * 0.62, type: 'socket' }));
  }
  P.add(M.shell, polyPlate(shape.rect(w, h, rr), { t: d, bevel: Math.min(0.0006, wall * 0.5), bevelBack: 0, holes: [shape.rect(w - 2 * wall, h - 2 * wall, Math.max(0.0003, rr - wall))] }), { pos: [0, 0, d] });
  P.add(M.shell, chamferBox(w * 0.34, wall * 1.4, d * 0.5, { bevel: wall * 0.3 }), { pos: [0, h / 2 + wall * 0.5, d * 0.6] });
  P.add(M.body, plate({ w: w - 2 * wall + 0.0002, h: h - 2 * wall + 0.0002, t: d * 0.3, r: Math.max(0.0003, rr - wall), bevel: 0, back: false }), { pos: [0, 0, d * 0.58] });
  const px = (w - 2 * wall) / (cols + 0.6), py = (h - 2 * wall) / (rows + 0.4), pr = Math.min(px, py) * 0.2;
  const pins = [];
  for (let i = 0; i < cols; i++) for (let j = 0; j < rows; j++) pins.push(xf(cylinder({ r: pr, len: d * 0.3, y0: 0, segs: 6, bevel: pr * 0.3, axis: 'z' }), { pos: [(i - (cols - 1) / 2) * px, (j - (rows - 1) / 2) * py, d * 0.56] }));
  P.add(M.pin, mergeGeos(pins));
  return P.build(o.axis, null, true);
}

// =====================================================================================
// 99. Demo board — one sample of every primitive:  node tools/shot.mjs --name=kit --demo=kit
//     The board is 2.38 × 1.9 m, centred on the platform, facing +Z. Samples sit on a grid:
//     columns 0…7 at x = -1.05 + 0.3·i, rows at y = A 1.77 · B 1.40 · C 1.02 · D 0.66 · E 0.28.
//     A  plates & modifiers   0 polyPlate + step pocket + shape.inset inlay + boltsAt · 1 plate
//        (cut, taper, step) crowned together with energyStrips · 2 plate bend + raised step ·
//        3 shape.split seam pair with holes, crowned · 4 polyPlate with cut-out, bend ·
//        5 grille, bend · 6 taper + twist strip, and a three-fold keeled polyPlate · 7 mirrorX pair
//     B  shells & lathes      0 tubeShell front / back halves · 1 closed tubeShell (profile,
//        power 3) · 2 tubeShell with custom outline + slots · 3 domeShell + wrapDome'd strip ·
//        4 round domeShell, pole 'y' · 5 lathe (thick, partial arc) + inner ring · 6 lathe nozzle,
//        hex boltCircle, radial energyRing · 7 bellows, cylinders, chamferBox
//     C  mechanisms           0 servo detail 2 · 1 servo detail 1 and 0 · 2 pistons (short / long
//        stroke) · 3 hinge flat and opened · 4 ballJoint plain and booted · 5 the six bolt types
//        (6× size) · 6 lathed flange, boltCircles, shape.gear, boltRow · 7 connector, vent
//     D  frame, wiring, energy  0 strut I + strut flat/truss · 1 strut flat/slot + bone (tube) ·
//        2 cable (sag), cable (spline), hose, hard line (straight) · 3 looms · 4 coil on a shaft ·
//        5 fins · 6 energyRing flat / arc / radial · 7 energyStrips bent with their plate
//     E  assemblies           0–1 powered joint (struts, servo, piston, loom) batched by merge() ·
//        2–3 armour wrapped with wrapTube (plate + strips + bolts) and its mirrorObjectX twin ·
//        5–7 equipment bay (cut-out plate, vent, fins, connectors, hose) batched by merge()
// =====================================================================================
export function buildDemo({ mat }) {
  const root = new THREE.Group();
  root.name = 'kit-demo';
  const M = mat;
  const dark = M.titaniumDark || M.frame;
  const X = (i) => -1.05 + 0.3 * i;
  const ROW = { A: 1.77, B: 1.4, C: 1.02, D: 0.66, E: 0.28 };
  const add = (geoOrObj, m, x, y, z = 0, rot, scale) => {
    const o = geoOrObj.isObject3D ? geoOrObj : new THREE.Mesh(geoOrObj, m);
    const holder = new THREE.Group();
    holder.position.set(x, y, z);
    if (rot) holder.rotation.set(rot[0], rot[1], rot[2]);
    if (scale) holder.scale.setScalar(scale);
    holder.add(o);
    root.add(holder);
    return o;
  };

  // backing board
  add(plate({ w: 2.38, h: 1.9, t: 0.02, r: 0.04, bevel: 0.004 }), dark, 0, 1.02, -0.09);

  // ---- A: plates & modifiers ---------------------------------------------------------
  {
    // two-tone layering: titanium carrier with a pocket, cobalt inlay made from the inset outline
    const outline = shape.chamferRect(0.2, 0.15, [0.025, 0, 0.025, 0], 0.008);
    add(polyPlate(outline, { t: 0.008, step: { inset: 0.012, depth: 0.002 } }), M.titanium, X(0), ROW.A);
    add(polyPlate(shape.inset(outline, 0.0165), { t: 0.004, bevel: 0.001 }), M.cobalt, X(0), ROW.A, 0.001);
    add(boltsAt([[-0.092, 0.03], [0.092, -0.03], [0.07, 0.067], [-0.07, -0.067]], { d: 0.006, type: 'socket' }), M.steel, X(0), ROW.A);
  }
  {
    const p = plate({ w: 0.22, h: 0.16, t: 0.008, r: 0.006, cut: [0, 0.03, 0, 0.035], taper: 0.78, step: { inset: 0.012, depth: 0.0012 }, segs: [7, 6] });
    const s = energyStrip([[-0.06, -0.045], [0.02, -0.045], [0.045, -0.02], [0.045, 0.04]], { w: 0.004 });
    const s2 = energyStrip([[-0.06, -0.02], [-0.01, -0.02], [0.012, 0.002], [0.012, 0.04]], { w: 0.0025 });
    for (const g of [s, s2]) g.translate(0, 0, -0.0012);
    crown([p, s, s2], { amount: 0.007, w: 0.22, h: 0.16 });
    add(p, M.cobalt, X(1), ROW.A);
    add(mergeGeos([s, s2]), M.energy, X(1), ROW.A);
  }
  add(plate({ w: 0.24, h: 0.16, t: 0.006, r: 0.02, bend: 0.085, step: { inset: 0.01, depth: -0.001 } }), M.titanium, X(2), ROW.A, 0.03);
  {
    // one outline cut into two plates by a real seam, then crowned together
    const outline = [[-0.1, -0.08, 0.004], [0.06, -0.08, 0.012], [0.11, -0.02, 0.004], [0.08, 0.08, 0.004], [-0.04, 0.08, 0.02], [-0.11, 0.02, 0.004]];
    const [up, low] = shape.split(outline, [-0.12, -0.02], [0.12, 0.012], 0.002, 0.002);
    const a = polyPlate(up, { t: 0.008, segs: [8, 4], holes: [shape.slot(0.06, 0.012, 0.0, 0.045, 0.2)] });
    const b = polyPlate(low, { t: 0.008, segs: [8, 4], holes: [shape.circle(0.012, -0.045, -0.045), shape.ngon(6, 0.012, 0.045, -0.04, 0, 0.002)] });
    crown([a, b], { amount: 0.006 });
    add(a, M.cobalt, X(3), ROW.A);
    add(b, M.titanium, X(3), ROW.A);
  }
  add(polyPlate([[-0.07, -0.09, 0.01], [0.07, -0.09, 0.01], [0.095, 0.0, 0.006], [0.07, 0.085, 0.02], [-0.07, 0.085, 0.02], [-0.095, 0.0, 0.006]], { t: 0.005, bend: 0.1, holes: [[[-0.062, 0.012, 0.004], [0.062, 0.012, 0.004], [0.05, 0.05, 0.012], [-0.05, 0.05, 0.012]]], step: { inset: 0.006, depth: 0.0008 } }), M.titanium, X(4), ROW.A, 0.03);
  add(grille({ w: 0.2, h: 0.14, t: 0.004, r: 0.012, n: 7, bend: 0.14 }), dark, X(5), ROW.A, 0.02);
  {
    const g = plate({ w: 0.05, h: 0.24, t: 0.005, r: 0.008, segs: [3, 18] });
    taper(g, { scale: [1, 0.6] });
    twist(g, { angle: 0.9 });
    add(g, M.titanium, X(6) - 0.08, ROW.A - 0.02);
    // keeled plate: centre crease + two side folds, all exact creases with rolled highlights
    add(polyPlate([[-0.085, -0.1, 0.006], [0.085, -0.1, 0.006], [0.1, 0.02, 0.006], [0.06, 0.1, 0.006], [-0.06, 0.1, 0.006], [-0.1, 0.02, 0.006]], { t: 0.006, fold: [{ at: 0, angle: 0.45, symmetric: true }, { at: 0.055, angle: 0.5 }, { at: -0.055, angle: 0.5 }], step: { inset: 0.008, depth: 0.001 } }), M.cobalt, X(6) + 0.06, ROW.A);
  }
  {
    const pts = [[0.012, -0.11, 0.004], [0.1, -0.11, 0.03], [0.12, 0.06, 0.006], [0.05, 0.11, 0.006], [0.012, 0.07, 0.004]];
    const g = polyPlate(pts, { t: 0.006, crown: 0.004, bend: { radius: 0.2, center: 0 }, step: { inset: 0.008, depth: 0.001 } });
    add(g, M.cobalt, X(7), ROW.A);
    add(mirrorX(g), M.cobalt, X(7), ROW.A);
  }

  // ---- B: shells & lathes ------------------------------------------------------------
  add(tubeShell({ y0: -0.14, y1: 0.14, r0: [0.052, 0.058], r1: [0.066, 0.072], arc: [-1.35, 1.35], t: 0.006, r: 0.016, cut: [0.03, 0.03, 0, 0], step: { inset: 0.008, depth: 0.001 } }), M.titanium, X(0), ROW.B, 0.01);
  add(tubeShell({ y0: -0.14, y1: 0.14, r0: [0.052, 0.058], r1: [0.066, 0.072], arc: [PI - 1.6, PI + 1.6], t: 0.006, r: 0.016 }), M.cobalt, X(0), ROW.B, -0.03);
  add(tubeShell({ profile: [[-0.15, 0.04, 0.046], [-0.06, 0.058, 0.07, 0, -0.008], [0.06, 0.062, 0.068], [0.15, 0.05, 0.052]], power: 3, t: 0.005 }), M.cobalt, X(1), ROW.B);
  add(tubeShell({ y0: -0.13, y1: 0.13, r0: 0.05, r1: 0.062, arc: [-1.5, 1.5], t: 0.005, outline: [[-1.5, -0.13, 0.01], [-0.3, -0.13, 0.01], [0, -0.09, 0.006], [0.3, -0.13, 0.01], [1.5, -0.13, 0.01], [1.5, 0.05, 0.01], [0.6, 0.13, 0.02], [-0.6, 0.13, 0.02], [-1.5, 0.05, 0.01]], holes: [shape.slot(0.9, 0.012, 0, 0.0), shape.slot(0.7, 0.012, 0, 0.03), shape.slot(0.5, 0.012, 0, 0.06)] }), M.titanium, X(2), ROW.B);
  {
    const dome = { r: 0.105, spanX: 1.7, spanY: 1.35, t: 0.006, cornerR: 0.03, cut: [0, 0, 0.03, 0.03], step: { inset: 0.012, depth: 0.001 } };
    add(domeShell(dome), M.cobalt, X(3), ROW.B, -0.07);
    add(wrapDome(energyStrip([[-0.05, -0.03], [0.0, -0.03], [0.02, -0.01], [0.05, -0.01]], { w: 0.004, maxSeg: 0.008 }).translate(0, 0, -0.001), dome), M.energy, X(3), ROW.B, -0.07);
  }
  add(domeShell({ rx: 0.105, ry: 0.13, rz: 0.125, spanX: 2.5, round: true, t: 0.005, pole: 'y' }), M.titanium, X(4), ROW.B - 0.07, 0, [0.5, 0.4, 0]);
  add(lathe([[0.05, -0.05], [0.088, -0.02, 0.012], [0.088, 0.03, 0.004], [0.07, 0.05]], { t: 0.007, arc: [-2.2, 2.2], segs: 40 }), dark, X(5), ROW.B, -0.02, [0.5, 0, 0]);
  add(lathe([[0.045, -0.02], [0.045, 0.025, 0.004], [0.03, 0.035]], { t: 0.004, segs: 32 }), M.steel, X(5), ROW.B, -0.02, [0.5, 0, 0]);
  {
    const g = new THREE.Group();
    g.add(new THREE.Mesh(lathe([[0.03, -0.06], [0.04, -0.05, 0.004], [0.042, -0.01, 0.01], [0.062, 0.035, 0.03], [0.07, 0.08]], { t: 0.004, segs: 40 }), M.thruster || dark));
    g.add(new THREE.Mesh(boltCircle({ n: 10, R: 0.0435, d: 0.005, type: 'hex', axis: 'y' }).translate(0, -0.0495, 0).rotateX(PI), M.steel));
    g.add(new THREE.Mesh(energyRing({ r: 0.0645, facing: 'radial', w: 0.004 }).translate(0, 0.05, 0), M.energy));
    add(g, null, X(6), ROW.B - 0.01, 0, [1.05, 0, 0.3]);
  }
  add(bellows({ r0: 0.045, r1: 0.032, len: 0.09, folds: 6 }), M.rubber, X(7) - 0.06, ROW.B + 0.04);
  add(cylinder({ r: 0.03, len: 0.07, hole: 0.016 }), M.steel, X(7) + 0.05, ROW.B + 0.07, 0, [0.9, 0, 0.4]);
  add(cylinder({ r: 0.028, r1: 0.014, len: 0.06, bevel: 0.002 }), M.copper, X(7) + 0.07, ROW.B - 0.06, 0, [0.3, 0, 0]);
  add(chamferBox(0.09, 0.05, 0.035, { bevel: 0.002, r: 0.006 }), M.frame, X(7) - 0.05, ROW.B - 0.09, 0, [0.5, 0.5, 0]);

  // ---- C: actuators, joints, fasteners ------------------------------------------------
  add(servo({ r: 0.05, len: 0.1, detail: 2, axis: 'z' }, M), null, X(0), ROW.C, -0.02);
  add(servo({ r: 0.036, len: 0.085, detail: 1, axis: 'x' }, M), null, X(1), ROW.C + 0.07);
  add(servo({ r: 0.024, len: 0.05, detail: 0, axis: 'x' }, M), null, X(1), ROW.C - 0.07);
  {
    const p1 = piston({ a: [-0.09, -0.1, 0], b: [-0.05, 0.1, 0], r: 0.012 }, M);
    const p2 = piston({ a: [0.03, -0.12, 0], b: [0.1, 0.13, 0], r: 0.011, min: 0.2, max: 0.3 }, M);
    const g = new THREE.Group();
    g.add(p1, p2);
    add(g, null, X(2), ROW.C);
  }
  add(hinge({ len: 0.12, r: 0.009, leaf: 0.03, knuckles: 5, angle: 0 }, M), null, X(3) - 0.02, ROW.C + 0.085, 0, [0, 0, PI / 2]);
  add(hinge({ len: 0.12, r: 0.009, leaf: 0.03, knuckles: 5, angle: 1.1 }, M), null, X(3), ROW.C - 0.07, 0, [0.5, 0.5, 0]);
  add(ballJoint({ r: 0.03, boot: false }, M), null, X(4) - 0.06, ROW.C - 0.02, 0, [0.5, 0, 0.2]);
  add(ballJoint({ r: 0.022, boot: true }, M), null, X(4) + 0.07, ROW.C - 0.03, 0, [0.35, 0, -0.3]);
  {
    const types = ['hex', 'socket', 'button', 'flush', 'rivet', 'pan'];
    add(plate({ w: 0.24, h: 0.17, t: 0.006, r: 0.01 }), M.titanium, X(5), ROW.C);
    types.forEach((t, i) => add(bolt({ type: t, d: 0.03, washer: i === 1 }), M.steel, X(5) - 0.075 + (i % 3) * 0.075, ROW.C + 0.04 - Math.floor(i / 3) * 0.08));
  }
  add(lathe([[0.03, 0], [0.085, 0, 0.002], [0.085, 0.012, 0.002], [0.055, 0.012, 0], [0.055, 0.03, 0.003], [0.03, 0.03, 0.002]], { segs: 48 }), dark, X(6), ROW.C + 0.02, 0, [PI / 2, 0, 0]);
  add(boltCircle({ n: 8, R: 0.07, d: 0.011, type: 'socket' }), M.steel, X(6), ROW.C + 0.02, 0.012);
  add(boltCircle({ n: 6, R: 0.043, d: 0.007, type: 'hex', washer: true }), M.steel, X(6), ROW.C + 0.02, 0.03);
  add(polyPlate(shape.gear(22, 0.026), { t: 0.008, bevel: 0.0006, holes: [shape.ngon(6, 0.009)] }), M.steel, X(6), ROW.C + 0.02, 0.008);
  add(chamferBox(0.2, 0.022, 0.008), M.cobalt, X(6), ROW.C - 0.1, 0.004);
  add(boltRow({ n: 6, from: [-0.085, 0], to: [0.085, 0], d: 0.009, type: 'button' }), M.steel, X(6), ROW.C - 0.1, 0.008);
  add(connector({ w: 0.07, h: 0.034, d: 0.03, pins: [7, 3] }, M), null, X(7), ROW.C + 0.08, 0, [-0.3, 0.4, 0]);
  add(vent({ w: 0.2, h: 0.1, depth: 0.016, slats: 6, frame: 0.01, r: 0.01 }, M), null, X(7), ROW.C - 0.07, 0.016);

  // ---- D: frame, wiring, cooling, energy ----------------------------------------------
  add(strut({ len: 0.26, w0: 0.045, w1: 0.03, t: 0.014 }), M.titanium, X(0) - 0.07, ROW.D - 0.13, 0, [0, 0.6, 0]);
  add(strut({ len: 0.26, w: 0.04, t: 0.007, section: 'flat', holes: 'truss' }), dark, X(0) + 0.06, ROW.D - 0.13, 0, [0, -0.4, 0]);
  add(strut({ len: 0.26, w: 0.036, t: 0.007, section: 'flat', holes: 'slot' }), M.frame, X(1) - 0.07, ROW.D - 0.13);
  add(bone([0, -0.13, 0], [0.06, 0.13, 0.0], { section: 'tube', w: 0.034, pole: [0, 0, 1] }, M.titanium), null, X(1) + 0.04, ROW.D);
  {
    const g = new THREE.Group();
    g.add(new THREE.Mesh(cable([[-0.12, 0.1, 0], [0.12, 0.1, 0]], { r: 0.004, sag: 0.04 }), M.rubber));
    g.add(new THREE.Mesh(cable([[-0.12, 0.06, 0], [-0.04, 0.0, 0.03], [0.04, 0.05, 0.03], [0.12, -0.02, 0]], { r: 0.003 }), M.wireTeal || M.wire));
    g.add(hose([[-0.12, -0.02, 0], [-0.06, -0.09, 0.03], [0.05, -0.1, 0.03], [0.12, -0.06, 0]], { r: 0.006 }, M));
    g.add(new THREE.Mesh(cable([[-0.12, -0.13, 0], [-0.04, -0.13, 0], [0.0, -0.11, 0], [0.12, -0.11, 0]], { r: 0.0025, straight: true }), M.copper));
    add(g, null, X(2), ROW.D);
  }
  add(loom([[-0.12, -0.1, 0], [-0.05, 0.0, 0.03], [0.04, -0.03, 0.03], [0.12, 0.1, 0]], { n: 7, r: 0.004, tie: 0.07 }, M), null, X(3), ROW.D + 0.02);
  add(loom([[-0.12, -0.12, 0], [0.12, -0.12, 0]], { n: 4, r: 0.003, twist: 2, tie: 0.08, sag: 0.02 }, M), null, X(3), ROW.D + 0.0);
  add(cylinder({ r: 0.024, len: 0.2, bevel: 0.002 }), M.steel, X(4), ROW.D, 0, [0, 0, 0.5]);
  add(coil({ r: 0.0275, len: 0.11, wire: 0.0032 }), M.copper, X(4), ROW.D, 0, [0, 0, 0.5]);
  add(fins({ w: 0.2, h: 0.12, d: 0.035, n: 12 }), dark, X(5), ROW.D + 0.01, 0, [-0.5, 0.4, 0]);
  add(cylinder({ r: 0.07, len: 0.02, bevel: 0.002, segs: 48, axis: 'z' }), dark, X(6) - 0.03, ROW.D + 0.06);
  add(energyRing({ r: 0.05, w: 0.008, h: 0.0012 }), M.energy, X(6) - 0.03, ROW.D + 0.06, 0.01);
  add(energyRing({ r: 0.028, w: 0.004, arc: [0.3, 5.2] }), M.energy, X(6) - 0.03, ROW.D + 0.06, 0.01);
  add(cylinder({ r: 0.035, len: 0.12, bevel: 0.003 }), M.cobalt, X(6) + 0.07, ROW.D - 0.08, 0, [0.4, 0, -0.6]);
  add(energyRing({ r: 0.035, w: 0.006, facing: 'radial' }), M.energy, X(6) + 0.07, ROW.D - 0.08, 0, [0.4, 0, -0.6]);
  {
    const p = plate({ w: 0.24, h: 0.2, t: 0.006, r: 0.016, cut: [0.03, 0, 0.03, 0], segs: [14, 2] });
    const lines = [
      energyStrip([[-0.09, -0.07], [-0.03, -0.07], [0.0, -0.04], [0.0, 0.03], [0.03, 0.06], [0.09, 0.06]], { w: 0.005, maxSeg: 0.01 }),
      energyStrip([[-0.09, 0.0], [-0.04, 0.0], [-0.02, 0.02], [-0.02, 0.07]], { w: 0.003, maxSeg: 0.01 }),
      energyStrip([[0.03, -0.07], [0.09, -0.07], [0.09, -0.01]], { w: 0.003, maxSeg: 0.01, ends: 'square' }),
      energyStrip([[0.045, -0.03], [0.07, -0.03], [0.07, 0.01], [0.045, 0.01]], { w: 0.002, closed: true }),
    ];
    bend([p, ...lines], { radius: 0.13 });
    add(p, M.cobalt, X(7), ROW.D, 0.02);
    add(mergeGeos(lines), M.energy, X(7), ROW.D, 0.02);
  }

  // ---- E: small assemblies, statically batched with merge() ----------------------------
  {
    // powered joint: two frame members, servo at the pivot, piston across, loom along the bone
    const g = new THREE.Group();
    g.add(new THREE.Mesh(strut({ y0: 0, y1: 0.2, w0: 0.05, w1: 0.036, t: 0.016 }), M.frame));
    const lower = new THREE.Group();
    lower.rotation.z = 0.9;
    lower.add(new THREE.Mesh(strut({ y0: 0, y1: -0.2, w0: 0.05, w1: 0.032, t: 0.012, section: 'flat', holes: 'truss' }).translate(0, 0, -0.016), dark));
    g.add(lower);
    g.add(place(servo({ r: 0.034, len: 0.06, detail: 2, axis: 'z' }, M), { pos: [0, 0, 0.012] }));
    g.add(piston({ a: [0.0, 0.15, 0.02], b: [0.118, -0.094, 0.02], r: 0.008 }, M));
    g.add(place(loom([[-0.012, 0.19, 0.012], [-0.016, 0.1, 0.016], [-0.03, 0.04, 0.02]], { n: 4, r: 0.002, tie: 0.05 }, M), {}));
    merge(g);
    add(g, null, X(0) + 0.08, ROW.E + 0.02, 0, [0, 0.35, 0]);
  }
  {
    // wrapped armour: custom outline + energy lines + bolts all draped by the same wrapTube()
    const tube = { profile: [[-0.16, 0.05, 0.055], [-0.04, 0.062, 0.075], [0.08, 0.058, 0.07], [0.16, 0.048, 0.052]], Rref: 0.062 };
    const outline = [[-0.085, -0.16, 0.012], [0.085, -0.16, 0.012], [0.1, -0.02, 0.01], [0.07, 0.1, 0.01], [0.0, 0.16, 0.02], [-0.07, 0.1, 0.01], [-0.1, -0.02, 0.01]];
    const p = polyPlate(outline, { t: 0.006, cell: 0.012, step: { inset: 0.009, depth: 0.001 } });
    const s = [energyStrip([[0, -0.12], [0, 0.02], [-0.035, 0.07]], { w: 0.004, maxSeg: 0.01 }), energyStrip([[0.03, -0.12], [0.03, 0.0], [0.05, 0.03]], { w: 0.0025, maxSeg: 0.01 })].map((q) => q.translate(0, 0, -0.001));
    const bl = boltsAt([[-0.07, -0.14], [0.07, -0.14], [-0.078, -0.02], [0.078, -0.02], [0, 0.135]], { d: 0.006, type: 'button' }).translate(0, 0, -0.001);
    wrapTube([p, bl, ...s], tube);
    const g = new THREE.Group();
    g.add(new THREE.Mesh(p, M.titanium), new THREE.Mesh(mergeGeos(s), M.energy), new THREE.Mesh(bl, M.steel));
    add(g, null, X(2) + 0.04, ROW.E + 0.03, -0.04, [0, -0.5, 0]);
    const g2 = mirrorObjectX(g);
    g2.children[0].material = M.cobalt;
    add(g2, null, X(3) + 0.08, ROW.E + 0.03, -0.04, [0, 0.5, 0]);
  }
  {
    // equipment bay: plate with cut-outs, vent, heat sink, connector and hose — merged per material
    const g = new THREE.Group();
    g.add(new THREE.Mesh(polyPlate(shape.chamferRect(0.5, 0.26, [0.04, 0, 0.04, 0], 0.008), { t: 0.008, holes: [shape.rect(0.146, 0.086, 0.012, -0.14, 0.03)], step: { inset: 0.012, depth: 0.0012 } }), M.cobalt));
    g.add(place(vent({ w: 0.15, h: 0.09, depth: 0.012, slats: 6, frame: 0.008, r: 0.012 }, M), { pos: [-0.14, 0.03, 0.002] }));
    g.add(new THREE.Mesh(fins({ w: 0.12, h: 0.08, d: 0.022, n: 9 }).translate(0.04, 0.045, -0.0012), dark));
    g.add(place(connector({ w: 0.05, h: 0.024, d: 0.02 }, M), { pos: [0.17, 0.05, -0.0012] }));
    g.add(place(connector({ w: 0.05, h: 0.024, d: 0.02 }, M), { pos: [-0.17, -0.075, -0.0012] }));
    g.add(hose([[0.17, 0.05, 0.03], [0.19, 0.0, 0.06], [0.12, -0.07, 0.05], [-0.09, -0.085, 0.045], [-0.17, -0.075, 0.03]], { r: 0.006 }, M));
    g.add(new THREE.Mesh(boltsAt([[-0.2, -0.105], [0.23, -0.115], [0.23, 0.08], [-0.03, 0.115], [-0.23, 0.115], [0.0, -0.115]], { d: 0.008, type: 'socket' }).translate(0, 0, -0.0012), M.steel));
    g.add(new THREE.Mesh(energyStrip([[-0.04, -0.03], [0.1, -0.03], [0.13, -0.06], [0.21, -0.06]], { w: 0.004 }).translate(0, 0, -0.0012), M.energy));
    merge(g);
    add(g, null, X(6) - 0.05, ROW.E + 0.02, 0, [-0.15, -0.2, 0]);
  }
  return root;
}
