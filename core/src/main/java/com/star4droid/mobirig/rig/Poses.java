package com.star4droid.mobirig.rig;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Quaternion;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;

/**
 * Test poses defined as world-space rotations of left-side / centre joints (right side is
 * mirrored), so they work whatever the bone roll. Used for the preview and the quality check.
 */
public class Poses {
    public static class Rot {
        final String joint;
        final Vector3 axis;
        final float deg;

        Rot(String joint, float ax, float ay, float az, float deg) {
            this.joint = joint;
            this.axis = new Vector3(ax, ay, az).nor();
            this.deg = deg;
        }
    }

    public static class Pose {
        public final String name;
        final Array<Rot> rots = new Array<>();
        final boolean mirror;
        boolean fist;
        /** Hips lift in leg lengths (clips). */
        public float hipsY;

        Pose(String name, boolean mirror) {
            this.name = name;
            this.mirror = mirror;
        }

        Pose r(String joint, float ax, float ay, float az, float deg) {
            rots.add(new Rot(joint, ax, ay, az, deg));
            return this;
        }
    }

    /** Arm angle from horizontal measured on the rig, so "arms down" works for T- and A-poses. */
    public static float armDrop(Skeleton sk) {
        Vector3 a = sk.get("LeftArm").pos, h = sk.get("LeftHand").pos;
        return MathUtils.atan2(a.y - h.y, h.x - a.x) * MathUtils.radiansToDegrees;
    }

    public static Array<Pose> all(Skeleton sk) {
        float drop = armDrop(sk);
        Array<Pose> p = new Array<>();
        p.add(new Pose("Rest", false));
        p.add(new Pose("Arms down", true).r("LeftArm", 0, 0, 1, -(75 - drop)));
        p.add(new Pose("Arms up", true).r("LeftArm", 0, 0, 1, 70 + drop));
        p.add(new Pose("Reach", true).r("LeftArm", 0, 1, 0, -80));
        p.add(new Pose("Elbows bent", true).r("LeftArm", 0, 0, 1, -(60 - drop)).r("LeftForeArm", 0, 1, 0, -110));
        p.add(new Pose("Wrist twist", true).r("LeftHand", 1, 0, 0, 75));
        p.add(new Pose("Squat", true).r("LeftUpLeg", 1, 0, 0, -95).r("LeftLeg", 1, 0, 0, 120).r("LeftFoot", 1, 0, 0, -25));
        p.add(new Pose("Kick", false).r("LeftUpLeg", 1, 0, 0, -80).r("RightUpLeg", 1, 0, 0, 35).r("RightLeg", 1, 0, 0, 60));
        if (sk.index("LeftHandIndex1") >= 0 || sk.index("LeftHandThumb1") >= 0) {
            Pose f = new Pose("Fist", true);
            f.fist = true;
            p.add(f);
        }
        p.add(new Pose("Bend & turn", false).r("Spine", 1, 0, 0, 20).r("Spine1", 0, 1, 0, 20).r("Spine2", 1, 0, 0, 15).r("Neck", 0, 1, 0, 25).r("Head", 1, 0, 0, -15));
        return p;
    }

    /** Walk cycle frame at time t (seconds). */
    public static Pose walk(Skeleton sk, float t) {
        float drop = armDrop(sk);
        float s = MathUtils.sin(t * 4.2f), c = MathUtils.cos(t * 4.2f);
        return new Pose("Walk", false)
            .r("LeftArm", 0, 0, 1, -(78 - drop)).r("RightArm", 0, 0, 1, (78 - drop))
            .r("LeftArm", 1, 0, 0, 25 * s).r("RightArm", 1, 0, 0, -25 * s)
            .r("LeftForeArm", 0, 1, 0, -20 - 10 * s).r("RightForeArm", 0, 1, 0, 20 - 10 * s)
            .r("LeftUpLeg", 1, 0, 0, -28 * s).r("RightUpLeg", 1, 0, 0, 28 * s)
            .r("LeftLeg", 1, 0, 0, 35 * Math.max(0, c)).r("RightLeg", 1, 0, 0, 35 * Math.max(0, -c))
            .r("Spine1", 0, 1, 0, 6 * s);
    }

    /**
     * Local rotations for every joint: rest local rotation followed by the pose delta.
     * Several rotations on the same joint are applied in order.
     */
    public static Quaternion[] localRotations(Skeleton sk, Pose pose) {
        int n = sk.joints.size;
        Quaternion[] out = new Quaternion[n];
        Quaternion[] worldDelta = new Quaternion[n];
        for (int j = 0; j < n; j++) {
            out[j] = sk.localRotation(j, new Quaternion());
            worldDelta[j] = new Quaternion();
        }
        for (Rot r : pose.rots) {
            add(sk, worldDelta, r.joint, r.axis, r.deg);
            if (pose.mirror && r.joint.startsWith("Left")) {
                add(sk, worldDelta, "Right" + r.joint.substring(4), new Vector3(r.axis.x, -r.axis.y, -r.axis.z), r.deg);
            }
        }
        if (pose.fist) addFist(sk, worldDelta);
        driveRoll(sk, worldDelta);
        // world-axis delta D on joint j: new local = local * (W^-1 * D * W)
        for (int j = 0; j < n; j++) {
            Quaternion w = sk.joints.get(j).worldRot;
            Quaternion l = new Quaternion(w).conjugate().mul(worldDelta[j]).mul(w);
            out[j].mul(l);
        }
        return out;
    }

    /**
     * Curls every finger toward the palm. The palm side comes from the hand's shape (see
     * FingerDetector), so fingers never bend backwards whatever the hand orientation. The thumb
     * folds across the palm toward the middle finger instead of straight down.
     */
    private static void addFist(Skeleton sk, Quaternion[] worldDelta) {
        Skeleton.Joint hand = sk.get("LeftHand");
        Vector3 n = new Vector3(0, -1, 0);
        if (hand.rollHint != null) n.set(hand.rollHint).nor();
        Skeleton.Joint mid = sk.get("LeftHandMiddle1");
        if (mid == null) mid = sk.get("LeftHandIndex1");
        float[] ang = {70, 90, 60}, angT = {25, 30, 45};
        for (int j = 0; j < sk.joints.size; j++) {
            Skeleton.Joint jt = sk.joints.get(j);
            if (!jt.finger || !jt.deform || !jt.name.startsWith("Left")) continue;
            int c = sk.primaryChild(j);
            if (c < 0) continue;
            Vector3 d = new Vector3(sk.joints.get(c).pos).sub(jt.pos).nor();
            Vector3 toward = new Vector3(n);
            boolean thumb = jt.name.contains("Thumb");
            if (thumb && mid != null) {
                Vector3 across = new Vector3(mid.pos).sub(jt.pos);
                across.mulAdd(d, -across.dot(d));
                if (across.len2() > 1e-12f) toward.add(across.nor()).nor();
            }
            Vector3 axis = new Vector3(d).crs(toward);
            if (axis.len2() < 1e-12f) continue;
            axis.nor();
            int seg = jt.name.charAt(jt.name.length() - 1) - '1';
            float a = thumb ? angT[seg] : ang[seg];
            add(sk, worldDelta, jt.name, axis, a);
            add(sk, worldDelta, "Right" + jt.name.substring(4), new Vector3(axis.x, -axis.y, -axis.z), a);
        }
    }

    /** Forearm roll bones take half of the hand's twist around the forearm, so the wrist does not pinch. */
    static void driveRoll(Skeleton sk, Quaternion[] worldDelta) {
        for (String pre : new String[]{"Left", "Right"}) {
            int roll = sk.index(pre + "ForeArmRoll"), hand = sk.index(pre + "Hand"), fa = sk.index(pre + "ForeArm");
            if (roll < 0 || hand < 0 || fa < 0) continue;
            worldDelta[roll].set(halfTwist(worldDelta[hand], new Vector3(sk.joints.get(hand).pos).sub(sk.joints.get(fa).pos).nor()));
        }
    }

    static Quaternion halfTwist(Quaternion d, Vector3 axis) {
        float dot = d.x * axis.x + d.y * axis.y + d.z * axis.z;
        Quaternion tw = new Quaternion(axis.x * dot, axis.y * dot, axis.z * dot, d.w);
        if (tw.len2() < 1e-10f) return new Quaternion();
        tw.nor();
        return new Quaternion().slerp(tw, 0.5f);
    }

    public static float legLength(Skeleton sk) {
        Skeleton.Joint a = sk.get("LeftUpLeg"), b = sk.get("LeftLeg"), c = sk.get("LeftFoot");
        return a.pos.dst(b.pos) + b.pos.dst(c.pos);
    }

    // ------------------------------------------------------------------ built-in clips

    public static final String[] CLIPS = {"Idle", "Walk", "Run", "Jump", "Wave"};

    public static Clip builtin(final String name) {
        final float len = name.equals("Idle") ? 3f : name.equals("Walk") ? MathUtils.PI2 / 4.2f : name.equals("Run") ? MathUtils.PI2 / 9f
            : name.equals("Jump") ? 1.5f : 2f;
        return new Clip() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public float length() {
                return len;
            }

            @Override
            public Quaternion[] sample(Skeleton sk, float t, Vector3 hipsOffset) {
                Pose p = clipPose(sk, name, t % len, len);
                if (hipsOffset != null) hipsOffset.set(0, p.hipsY * legLength(sk), 0);
                return localRotations(sk, p);
            }
        };
    }

    private static float sm(float a, float b, float x) {
        float t = (x - a) / (b - a);
        t = t < 0 ? 0 : (t > 1 ? 1 : t);
        return t * t * (3 - 2 * t);
    }

    static Pose clipPose(Skeleton sk, String name, float t, float len) {
        float drop = armDrop(sk);
        float ph = MathUtils.PI2 * t / len;
        float s = MathUtils.sin(ph), c = MathUtils.cos(ph);
        Pose p = new Pose(name, false);
        float down = 72 - drop;
        switch (name) {
            case "Walk":
                return walk(sk, t);
            case "Run": {
                p.r("LeftArm", 0, 0, 1, -(down + 4)).r("RightArm", 0, 0, 1, down + 4)
                    .r("LeftArm", 1, 0, 0, 45 * s).r("RightArm", 1, 0, 0, -45 * s)
                    .r("LeftForeArm", 0, 1, 0, -80).r("RightForeArm", 0, 1, 0, 80)
                    .r("LeftUpLeg", 1, 0, 0, -12 - 42 * s).r("RightUpLeg", 1, 0, 0, -12 + 42 * s)
                    .r("LeftLeg", 1, 0, 0, 20 + 75 * Math.max(0, c)).r("RightLeg", 1, 0, 0, 20 + 75 * Math.max(0, -c))
                    .r("Spine", 1, 0, 0, 10).r("Spine1", 0, 1, 0, 10 * s).r("Head", 1, 0, 0, -8);
                p.hipsY = -0.04f + 0.035f * Math.abs(s);
                return p;
            }
            case "Jump": {
                float u = t / len;
                float k = sm(0f, 0.22f, u) * (1 - sm(0.22f, 0.32f, u)) + 0.65f * sm(0.68f, 0.76f, u) * (1 - sm(0.78f, 1f, u));
                float air = u > 0.32f && u < 0.70f ? (u - 0.32f) / 0.38f : -1;
                float tuck = air >= 0 ? 0.3f * MathUtils.sin(MathUtils.PI * air) : 0;
                float kk = k + tuck;
                float a = 55 * kk, knee = 105 * kk;
                p.r("LeftUpLeg", 1, 0, 0, -a).r("RightUpLeg", 1, 0, 0, -a).r("LeftLeg", 1, 0, 0, knee).r("RightLeg", 1, 0, 0, knee)
                    .r("LeftFoot", 1, 0, 0, -(knee - a) * 0.6f).r("RightFoot", 1, 0, 0, -(knee - a) * 0.6f)
                    .r("Spine", 1, 0, 0, 22 * k)
                    .r("LeftArm", 0, 0, 1, -down).r("RightArm", 0, 0, 1, down);
                float swing = 40 * k - (air >= 0 ? 120 * MathUtils.sin(MathUtils.PI * air) : 0);
                p.r("LeftArm", 1, 0, 0, swing).r("RightArm", 1, 0, 0, swing);
                float dropK = 1 - (MathUtils.cosDeg(55 * k) + MathUtils.cosDeg(50 * k)) * 0.5f;
                p.hipsY = -dropK + (air >= 0 ? 0.55f * 4 * air * (1 - air) : 0);
                return p;
            }
            case "Wave": {
                p.r("LeftArm", 0, 0, 1, -down).r("RightArm", 0, 0, 1, -(35 + drop))
                    .r("RightForeArm", 0, 0, 1, -(55 + 28 * MathUtils.sin(ph * 2)))
                    .r("Spine1", 0, 0, 1, -3).r("Head", 0, 0, 1, -6 + 2 * s).r("Spine2", 1, 0, 0, 1.2f * s);
                return p;
            }
            default: { // Idle
                p.r("LeftArm", 0, 0, 1, -(down + 1.5f * s)).r("RightArm", 0, 0, 1, down + 1.5f * s)
                    .r("LeftForeArm", 0, 1, 0, -12).r("RightForeArm", 0, 1, 0, 12)
                    .r("Spine1", 1, 0, 0, 1.5f * s).r("Spine2", 1, 0, 0, 1.5f * s).r("Neck", 1, 0, 0, -1.5f * s)
                    .r("Head", 0, 1, 0, 4 * MathUtils.sin(ph * 0.5f + 0.4f)).r("Spine", 0, 0, 1, 1.2f * c);
                return p;
            }
        }
    }

    /** Later rotations on the same joint are applied after the earlier ones (in world space). */
    private static void add(Skeleton sk, Quaternion[] worldDelta, String name, Vector3 axis, float deg) {
        int j = sk.index(name);
        if (j < 0) return;
        Quaternion d = new Quaternion(axis, deg);
        worldDelta[j].set(d.mul(worldDelta[j]));
    }

    /** World matrices of all joints for the given local rotations (rest translations). */
    public static Matrix4[] world(Skeleton sk, Quaternion[] local) {
        int n = sk.joints.size;
        Matrix4[] g = new Matrix4[n];
        Vector3 t = new Vector3();
        for (int j = 0; j < n; j++) {
            Matrix4 l = new Matrix4().set(sk.localTranslation(j, t), local[j]);
            int p = sk.joints.get(j).parent;
            g[j] = p < 0 ? l : new Matrix4(g[p]).mul(l);
        }
        return g;
    }
}
