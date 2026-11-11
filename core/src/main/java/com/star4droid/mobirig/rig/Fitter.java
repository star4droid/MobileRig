package com.star4droid.mobirig.rig;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;

/**
 * Places a Mixamo skeleton inside the mesh from the six markers.
 * The markers give the ends of the limbs; slices of the mesh between them centre each joint
 * in the volume (elbow at the bend or thinnest point, knee at the narrowing, hips on the
 * thigh centre line, spine toward the back). Where the mesh gives no clear answer, standard
 * human proportions are kept.
 */
public class Fitter {
    private final RigMesh mesh;
    private final Slicer slicer;
    private final float h, y0;
    public final Array<String> notes = new Array<>();
    /** Detect and rig fingers. */
    public boolean fingers = true;
    /** Set after fit() when fingers were found. */
    public FingerDetector fingerDetector;
    /** Adds forearm roll bones. */
    public boolean twist;

    public Fitter(RigMesh mesh) {
        this.mesh = mesh;
        this.slicer = new Slicer(mesh);
        this.h = mesh.height();
        this.y0 = mesh.bounds.min.y;
    }

    public Skeleton fit(Markers mk) {
        Skeleton s = Skeleton.mixamo();
        Vector3 chin = mk.get(Markers.Kind.CHIN), neck = mk.get(Markers.Kind.NECK);
        Vector3 sh = mk.get(Markers.Kind.SHOULDER), wr = mk.get(Markers.Kind.WRIST);
        Vector3 pel = mk.get(Markers.Kind.PELVIS), ank = mk.get(Markers.Kind.ANKLE);
        if (wr.dst(sh) < 0.05f * h) throw new RigException("Wrist marker is too close to the shoulder");
        if (neck.y <= pel.y) throw new RigException("Neck marker must be above the pelvis marker");
        if (chin.y <= neck.y) throw new RigException("Chin marker must be above the neck marker");
        if (ank.y >= pel.y) throw new RigException("Ankle marker must be below the pelvis marker");

        // ---- spine
        Vector3 hips = new Vector3(0, pel.y, pel.z);
        set(s, "Hips", hips);
        float[] spineT = {0.20f, 0.44f, 0.72f};
        String[] spineN = {"Spine", "Spine1", "Spine2"};
        for (int i = 0; i < 3; i++) {
            float y = MathUtils.lerp(pel.y, neck.y, spineT[i]);
            float z = spineDepth(y, MathUtils.lerp(pel.z, neck.z, spineT[i]));
            set(s, spineN[i], new Vector3(0, y, z));
        }
        set(s, "Neck", new Vector3(0, neck.y, neck.z));
        float headY = chin.y + 0.01f * h;
        float headZ = centerZ(headY, chin.z);
        set(s, "Head", new Vector3(0, headY, headZ));
        set(s, "HeadTop_End", new Vector3(0, mesh.bounds.max.y, headZ));

        // ---- left arm (mirrored later)
        Vector3 arm = new Vector3(sh);
        Vector3 hand = new Vector3(wr);
        Vector3 elbow = elbow(arm, hand);
        Vector3 spine2 = s.get("Spine2").pos;
        Vector3 clav = new Vector3(0.3f * arm.x, arm.y + 0.15f * (neck.y - arm.y), MathUtils.lerp(spine2.z, arm.z, 0.5f));
        Vector3 tip = handTip(arm, hand);

        // ---- left leg
        float crotch = crotchHeight(pel.y, ank.y);
        float upY = pel.y - 0.045f * h;
        float thighX = Math.max(ank.x, sh.x * 0.45f);
        float thighZ = pel.z;
        if (!Float.isNaN(crotch)) {
            upY = Math.max(upY, crotch + 0.01f * h);
            Array<Slicer.Loop> loops = slicer.section(new Vector3(0, crotch - 0.02f * h, 0), Vector3.Y);
            Slicer.Loop leg = bestSide(loops, new Vector3(thighX, crotch - 0.02f * h, pel.z));
            if (leg != null) {
                thighX = leg.center.x;
                thighZ = leg.center.z;
            }
        } else notes.add("Legs not separated (long clothing?): hip joints use standard proportions");
        Vector3 upLeg = new Vector3(thighX, upY, thighZ);
        Vector3 foot = new Vector3(ank);
        Vector3 knee = knee(upLeg, foot);
        float[] toes = toes(foot);
        Vector3 toeBase = new Vector3(foot.x, y0 + toes[2], toes[0]);
        Vector3 toeEnd = new Vector3(foot.x, y0 + toes[2], toes[1]);

        for (int side = 1; side >= -1; side -= 2) {
            String p = side > 0 ? "Left" : "Right";
            set(s, p + "Shoulder", mirror(clav, side));
            set(s, p + "Arm", mirror(arm, side));
            set(s, p + "ForeArm", mirror(elbow, side));
            set(s, p + "Hand", mirror(hand, side));
            s.get(p + "Hand").tail.set(mirror(tip, side));
            set(s, p + "UpLeg", mirror(upLeg, side));
            set(s, p + "Leg", mirror(knee, side));
            set(s, p + "Foot", mirror(foot, side));
            set(s, p + "ToeBase", mirror(toeBase, side));
            set(s, p + "Toe_End", mirror(toeEnd, side));
        }

        // weight segments: tail = primary child (hand already has its tip)
        for (int j = 0; j < s.joints.size; j++) {
            Skeleton.Joint jt = s.joints.get(j);
            int c = s.primaryChild(j);
            if (c >= 0 && jt.tail.isZero()) jt.tail.set(s.joints.get(c).pos);
        }
        if (fingers) {
            // the wrist marker is never exact: read the hand from several start lines and keep the best
            FingerDetector fd = null;
            for (float back : new float[]{0.15f, 0f, 0.08f, 0.22f, 0.30f}) {
                FingerDetector t = new FingerDetector();
                t.back = back;
                t.detect(mesh, hand, tip);
                if (fd == null || t.fingers.size > fd.fingers.size) fd = t;
                if (fd.fingers.size >= 5) break;
            }
            if (fd.fingers.size >= 2) {
                fd.addJoints(s);
                fingerDetector = fd;
                notes.addAll(fd.notes);
            } else notes.add("No separate fingers found (mitten hand?): the hand is rigged as one bone");
        }
        if (twist) {
            for (int side = 1; side >= -1; side -= 2) {
                String pre = side > 0 ? "Left" : "Right";
                Skeleton.Joint fa = s.get(pre + "ForeArm"), hd = s.get(pre + "Hand");
                Skeleton.Joint r = s.joints.get(s.add(pre + "ForeArmRoll", s.index(pre + "ForeArm"), side));
                r.pos.set(fa.pos).lerp(hd.pos, 0.5f);
                r.tail.set(hd.pos);
                r.aim = new Vector3(hd.pos);
            }
        }
        // the hips bone covers the pelvis down to the thigh heads
        float hipLow = Float.isNaN(crotch) ? upY : (upY + crotch) * 0.5f;
        s.get("Hips").weightHead = new Vector3(0, Math.min(hipLow, pel.y), pel.z);
        s.computeFrames();
        return s;
    }

    private static Vector3 mirror(Vector3 v, int side) {
        return new Vector3(v.x * side, v.y, v.z);
    }

    private static void set(Skeleton s, String name, Vector3 p) {
        s.get(name).pos.set(p);
    }

    /** Spine joints sit at 60% of the torso depth from the front (closer to the back). */
    private float spineDepth(float y, float fallback) {
        Array<Slicer.Loop> loops = slicer.section(new Vector3(0, y, 0), Vector3.Y);
        Slicer.Loop torso = slicer.containing(loops, new Vector3(0, y, fallback), 0.05f * h);
        if (torso == null) return fallback;
        return torso.max.z - 0.6f * (torso.max.z - torso.min.z);
    }

    private float centerZ(float y, float fallback) {
        Array<Slicer.Loop> loops = slicer.section(new Vector3(0, y, 0), Vector3.Y);
        Slicer.Loop l = slicer.containing(loops, new Vector3(0, y, fallback), 0.03f * h);
        return l == null ? fallback : (l.min.z + l.max.z) * 0.5f;
    }

    /** Centre points of the limb from a to b, sliced perpendicular to it. */
    private Vector3[] trace(Vector3 a, Vector3 b, float[] ts, float[] radii) {
        Vector3 dir = new Vector3(b).sub(a).nor();
        Vector3[] out = new Vector3[ts.length];
        for (int i = 0; i < ts.length; i++) {
            Vector3 q = new Vector3(a).lerp(b, ts[i]);
            Array<Slicer.Loop> loops = slicer.section(q, dir);
            Slicer.Loop l = slicer.nearest(loops, q, 0.01f * h);
            if (l != null && l.center.dst(q) < 0.08f * h) {
                out[i] = new Vector3(l.center);
                radii[i] = l.radius;
            } else {
                out[i] = q;
                radii[i] = Float.NaN;
            }
        }
        return out;
    }

    private Vector3 elbow(Vector3 shoulder, Vector3 wrist) {
        int n = 23;
        float[] ts = new float[n], r = new float[n];
        for (int i = 0; i < n; i++) ts[i] = 0.30f + 0.45f * i / (n - 1);
        Vector3[] c = trace(shoulder, wrist, ts, r);
        // bend: centre point furthest from the shoulder-wrist line
        Vector3 dir = new Vector3(wrist).sub(shoulder).nor();
        int bendI = -1;
        float bendD = 0;
        for (int i = 0; i < n; i++) {
            Vector3 d = new Vector3(c[i]).sub(shoulder);
            float off = new Vector3(d).mulAdd(dir, -d.dot(dir)).len();
            if (off > bendD) {
                bendD = off;
                bendI = i;
            }
        }
        float len = shoulder.dst(wrist);
        float bendAngle = MathUtils.atan2(bendD, len * 0.5f) * 2 * MathUtils.radiansToDegrees;
        Vector3 e;
        if (bendI >= 0 && bendAngle > 8f) e = new Vector3(c[bendI]);
        else {
            int best = -1;
            float score = Float.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                if (Float.isNaN(r[i])) continue;
                float sc = r[i] * (1f + 1.5f * Math.abs(ts[i] - 0.54f));
                if (sc < score) {
                    score = sc;
                    best = i;
                }
            }
            e = best >= 0 ? new Vector3(c[best]) : new Vector3(shoulder).lerp(wrist, 0.54f);
            // straight arm: nudge the elbow back so the bend direction is defined
            float lineZ = MathUtils.lerp(shoulder.z, wrist.z, best >= 0 ? ts[best] : 0.54f);
            e.z = Math.min(e.z, lineZ - 0.004f * h);
        }
        return e;
    }

    private Vector3 knee(Vector3 hip, Vector3 ankle) {
        int n = 23;
        float[] ts = new float[n], r = new float[n];
        for (int i = 0; i < n; i++) ts[i] = 0.32f + 0.36f * i / (n - 1);
        Vector3[] c = trace(hip, ankle, ts, r);
        int best = -1;
        float score = Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            if (Float.isNaN(r[i])) continue;
            float sc = r[i] * (1f + 1.2f * Math.abs(ts[i] - 0.5f));
            if (sc < score) {
                score = sc;
                best = i;
            }
        }
        Vector3 k = best >= 0 ? new Vector3(c[best]) : new Vector3(hip).lerp(ankle, 0.5f);
        float lineZ = MathUtils.lerp(hip.z, ankle.z, best >= 0 ? ts[best] : 0.5f);
        k.z = Math.max(k.z, lineZ + 0.004f * h);
        return k;
    }

    /** Lowest height where the body splits into two legs, scanning down from the pelvis. */
    private float crotchHeight(float pelvisY, float ankleY) {
        float step = 0.005f * h;
        for (float y = pelvisY; y > ankleY + 0.1f * h; y -= step) {
            Array<Slicer.Loop> loops = slicer.section(new Vector3(0, y, 0), Vector3.Y);
            boolean left = false, right = false, middle = false;
            for (Slicer.Loop l : loops) {
                if (l.length < 0.08f * h) continue;
                if (l.min.x > 0.002f * h) left = true;
                else if (l.max.x < -0.002f * h) right = true;
                else middle = true;
            }
            if (left && right && !middle) return y;
        }
        return Float.NaN;
    }

    private Slicer.Loop bestSide(Array<Slicer.Loop> loops, Vector3 hint) {
        Slicer.Loop best = null;
        float bd = Float.MAX_VALUE;
        for (Slicer.Loop l : loops) {
            if (l.length < 0.05f * h || l.center.x <= 0) continue;
            float d = l.center.dst2(hint);
            if (d < bd) {
                bd = d;
                best = l;
            }
        }
        return best;
    }

    /** Hand tip: furthest point of the hand along the forearm direction. */
    private Vector3 handTip(Vector3 shoulder, Vector3 wrist) {
        Vector3 dir = new Vector3(wrist).sub(shoulder).nor();
        float best = 0.06f * h;
        Vector3 t = new Vector3();
        for (int v = 0; v < mesh.vertexCount; v++) {
            t.set(mesh.pos[v * 3], mesh.pos[v * 3 + 1], mesh.pos[v * 3 + 2]);
            if (t.x <= 0 || t.dst(wrist) > 0.16f * h) continue;
            float proj = new Vector3(t).sub(wrist).dot(dir);
            if (proj > best) best = proj;
        }
        return new Vector3(wrist).mulAdd(dir, best * 0.92f);
    }

    /** {ball z, tip z, height above floor} of the left foot. */
    private float[] toes(Vector3 ankle) {
        float tipZ = ankle.z + 0.06f * h;
        for (int v = 0; v < mesh.vertexCount; v++) {
            float x = mesh.pos[v * 3], y = mesh.pos[v * 3 + 1], z = mesh.pos[v * 3 + 2];
            if (y < ankle.y + 0.01f * h && Math.abs(x - ankle.x) < 0.07f * h && z > tipZ) tipZ = z;
        }
        float ankleH = ankle.y - y0;
        return new float[]{ankle.z + 0.68f * (tipZ - ankle.z), tipZ, Math.max(0.2f * ankleH, 0.01f * h)};
    }
}
