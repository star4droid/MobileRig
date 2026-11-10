package com.star4droid.mobirig.rig;

import com.badlogic.gdx.math.Matrix3;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Quaternion;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;

/** A skeleton in world space (rest pose). Joint frames follow the Mixamo idea: local Y points to the child. */
public class Skeleton {
    public static class Joint {
        public final String name;
        public final int parent;
        public final Vector3 pos = new Vector3();
        public final Quaternion worldRot = new Quaternion();
        public boolean deform = true;
        /** -1 right, 0 centre, +1 left (character's sides). */
        public int side;
        /** End of the bone used for weights (child joint or an extra tip point). */
        public final Vector3 tail = new Vector3();
        /** Optional start of the weight segment if it should differ from pos. */
        public Vector3 weightHead;
        /** Optional point the joint's Y axis aims at (instead of its first child). */
        public Vector3 aim;
        /** Optional roll: the local Z axis is turned toward this direction (fingers: out of the palm). */
        public Vector3 rollHint;
        /** Finger joints get their weights from the finger detector, not the voxel binder. */
        public boolean finger;

        Joint(String name, int parent, int side) {
            this.name = name;
            this.parent = parent;
            this.side = side;
        }
    }

    public final Array<Joint> joints = new Array<>();

    public int add(String name, int parent, int side) {
        joints.add(new Joint(name, parent, side));
        return joints.size - 1;
    }

    public int index(String name) {
        for (int i = 0; i < joints.size; i++) if (joints.get(i).name.equals(name)) return i;
        return -1;
    }

    public Joint get(String name) {
        int i = index(name);
        return i < 0 ? null : joints.get(i);
    }

    public int primaryChild(int j) {
        for (int i = j + 1; i < joints.size; i++) if (joints.get(i).parent == j) return i;
        return -1;
    }

    /** Number of steps between two joints in the hierarchy. */
    public int hops(int a, int b) {
        int da = depth(a), db = depth(b), h = 0;
        while (da > db) { a = joints.get(a).parent; da--; h++; }
        while (db > da) { b = joints.get(b).parent; db--; h++; }
        while (a != b) { a = joints.get(a).parent; b = joints.get(b).parent; h += 2; }
        return h;
    }

    private int depth(int j) {
        int d = 0;
        while (joints.get(j).parent >= 0) { j = joints.get(j).parent; d++; }
        return d;
    }

    /** Builds joint orientations: Y toward the primary child, Z as close to forward (+Z) as possible. */
    public void computeFrames() {
        Vector3 y = new Vector3(), z = new Vector3(), x = new Vector3();
        Matrix3 m = new Matrix3();
        for (int j = 0; j < joints.size; j++) {
            Joint jt = joints.get(j);
            int c = primaryChild(j);
            Vector3 target = jt.aim != null ? jt.aim : c >= 0 ? joints.get(c).pos : (jt.tail.isZero() ? null : jt.tail);
            if (target == null || target.dst2(jt.pos) < 1e-12f) {
                if (jt.parent >= 0) jt.worldRot.set(joints.get(jt.parent).worldRot);
                else jt.worldRot.idt();
                continue;
            }
            y.set(target).sub(jt.pos).nor();
            Vector3 hint = jt.rollHint != null && Math.abs(jt.rollHint.dot(y)) < 0.95f ? jt.rollHint
                : Math.abs(y.z) > 0.7f ? Vector3.Y : Vector3.Z;
            z.set(hint).mulAdd(y, -hint.dot(y)).nor();
            x.set(y).crs(z).nor();
            float[] v = m.val;
            v[0] = x.x; v[1] = x.y; v[2] = x.z;
            v[3] = y.x; v[4] = y.y; v[5] = y.z;
            v[6] = z.x; v[7] = z.y; v[8] = z.z;
            jt.worldRot.setFromMatrix(true, m);
        }
    }

    public Matrix4 worldMatrix(int j, Matrix4 out) {
        Joint jt = joints.get(j);
        return out.set(jt.pos, jt.worldRot);
    }

    public Quaternion localRotation(int j, Quaternion out) {
        Joint jt = joints.get(j);
        if (jt.parent < 0) return out.set(jt.worldRot);
        Quaternion pr = new Quaternion(joints.get(jt.parent).worldRot).conjugate();
        return out.set(pr).mul(jt.worldRot);
    }

    public Vector3 localTranslation(int j, Vector3 out) {
        Joint jt = joints.get(j);
        if (jt.parent < 0) return out.set(jt.pos);
        Joint p = joints.get(jt.parent);
        Quaternion inv = new Quaternion(p.worldRot).conjugate();
        return inv.transform(out.set(jt.pos).sub(p.pos));
    }

    // ------------------------------------------------------------------ Mixamo

    public static final String[] MIXAMO = {
        "Hips", "Spine", "Spine1", "Spine2", "Neck", "Head", "HeadTop_End",
        "LeftShoulder", "LeftArm", "LeftForeArm", "LeftHand",
        "RightShoulder", "RightArm", "RightForeArm", "RightHand",
        "LeftUpLeg", "LeftLeg", "LeftFoot", "LeftToeBase", "LeftToe_End",
        "RightUpLeg", "RightLeg", "RightFoot", "RightToeBase", "RightToe_End"};

    /** Mixamo skeleton without fingers (25 joints); positions are filled in by the fitter. */
    public static Skeleton mixamo() {
        Skeleton s = new Skeleton();
        int hips = s.add("Hips", -1, 0);
        int spine = s.add("Spine", hips, 0);
        int spine1 = s.add("Spine1", spine, 0);
        int spine2 = s.add("Spine2", spine1, 0);
        int neck = s.add("Neck", spine2, 0);
        int head = s.add("Head", neck, 0);
        s.add("HeadTop_End", head, 0);
        for (int side = 1; side >= -1; side -= 2) {
            String p = side > 0 ? "Left" : "Right";
            int sh = s.add(p + "Shoulder", spine2, side);
            int arm = s.add(p + "Arm", sh, side);
            int fore = s.add(p + "ForeArm", arm, side);
            s.add(p + "Hand", fore, side);
        }
        for (int side = 1; side >= -1; side -= 2) {
            String p = side > 0 ? "Left" : "Right";
            int up = s.add(p + "UpLeg", hips, side);
            int leg = s.add(p + "Leg", up, side);
            int foot = s.add(p + "Foot", leg, side);
            int toe = s.add(p + "ToeBase", foot, side);
            s.add(p + "Toe_End", toe, side);
        }
        s.get("HeadTop_End").deform = false;
        s.get("LeftToe_End").deform = false;
        s.get("RightToe_End").deform = false;
        return s;
    }
}
