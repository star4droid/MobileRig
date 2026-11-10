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
        p.add(new Pose("Arms forward", true).r("LeftArm", 0, 1, 0, -80));
        p.add(new Pose("Elbows bent", true).r("LeftArm", 0, 0, 1, -(60 - drop)).r("LeftForeArm", 0, 1, 0, -110));
        p.add(new Pose("Wrist twist", true).r("LeftForeArm", 1, 0, 0, 70));
        p.add(new Pose("Squat", true).r("LeftUpLeg", 1, 0, 0, -95).r("LeftLeg", 1, 0, 0, 120).r("LeftFoot", 1, 0, 0, -25));
        p.add(new Pose("Kick", false).r("LeftUpLeg", 1, 0, 0, -80).r("RightUpLeg", 1, 0, 0, 35).r("RightLeg", 1, 0, 0, 60));
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
        // world-axis delta D on joint j: new local = local * (W^-1 * D * W)
        for (int j = 0; j < n; j++) {
            Quaternion w = sk.joints.get(j).worldRot;
            Quaternion l = new Quaternion(w).conjugate().mul(worldDelta[j]).mul(w);
            out[j].mul(l);
        }
        return out;
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
