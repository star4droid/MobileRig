package com.star4droid.mobirig.rig;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Quaternion;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.JsonValue;

import java.util.HashMap;
import java.util.Locale;

/**
 * Plays an animation from another GLB (Mixamo, Unreal or plain bone names) on our skeleton.
 * Bones are matched by name; every bone copies the source bone's rotation measured in world space
 * from the source rest pose, and limbs are first aligned to the source rest direction, so a
 * T-pose animation works on an A-pose character and bone roll conventions do not matter.
 */
public class Retarget implements Clip {
    private final String name;
    private final int n;
    private final int[] parent;
    private final Quaternion[] restRot;
    private final Matrix4[] restWorld;
    private final Quaternion[] restWorldRot;
    private final float[][] rotT, rotV, posT, posV;
    private final String[] key;
    private float length;
    private Skeleton bound;
    private int[] map;
    private Quaternion[] corr;
    private int srcHips = -1;
    private float scale = 1;
    public int matched;

    public Retarget(byte[] data, String name) {
        this.name = name;
        GlbFile g = GlbFile.parse(data);
        JsonValue nodes = g.json.get("nodes");
        JsonValue anims = g.json.get("animations");
        if (nodes == null || anims == null || anims.size == 0) throw new RigException("This file has no animation in it");
        n = nodes.size;
        parent = new int[n];
        java.util.Arrays.fill(parent, -1);
        restRot = new Quaternion[n];
        Vector3[] restPos = new Vector3[n], restScl = new Vector3[n];
        key = new String[n];
        int i = 0;
        for (JsonValue nd = nodes.child; nd != null; nd = nd.next, i++) {
            restRot[i] = new Quaternion();
            restPos[i] = new Vector3();
            restScl[i] = new Vector3(1, 1, 1);
            if (nd.has("matrix")) {
                Matrix4 m = new Matrix4(nd.get("matrix").asFloatArray());
                m.getTranslation(restPos[i]);
                m.getScale(restScl[i]);
                m.getRotation(restRot[i], true);
            } else {
                if (nd.has("translation")) { float[] a = nd.get("translation").asFloatArray(); restPos[i].set(a[0], a[1], a[2]); }
                if (nd.has("rotation")) { float[] a = nd.get("rotation").asFloatArray(); restRot[i].set(a[0], a[1], a[2], a[3]).nor(); }
                if (nd.has("scale")) { float[] a = nd.get("scale").asFloatArray(); restScl[i].set(a[0], a[1], a[2]); }
            }
            key[i] = norm(nd.getString("name", ""));
            if (nd.has("children")) for (int c : nd.get("children").asIntArray()) parent[c] = i;
        }
        restWorld = new Matrix4[n];
        restWorldRot = new Quaternion[n];
        for (i = 0; i < n; i++) world(i, restPos, restScl);
        rotT = new float[n][]; rotV = new float[n][]; posT = new float[n][]; posV = new float[n][];
        // the longest animation in the file
        JsonValue best = anims.child;
        for (JsonValue a = anims.child; a != null; a = a.next) if (a.get("channels").size > best.get("channels").size) best = a;
        JsonValue samplers = best.get("samplers");
        for (JsonValue ch = best.get("channels").child; ch != null; ch = ch.next) {
            JsonValue tg = ch.get("target");
            if (!tg.has("node")) continue;
            int node = tg.getInt("node");
            String path = tg.getString("path");
            JsonValue sm = samplers.get(ch.getInt("sampler"));
            boolean cubic = "CUBICSPLINE".equals(sm.getString("interpolation", "LINEAR"));
            float[] t = g.readFloats(sm.getInt("input")), v = g.readFloats(sm.getInt("output"));
            int comp = path.equals("rotation") ? 4 : 3;
            if (cubic) { // keep the values, drop the tangents
                float[] vv = new float[t.length * comp];
                for (int k = 0; k < t.length; k++) System.arraycopy(v, k * comp * 3 + comp, vv, k * comp, comp);
                v = vv;
            }
            if (t.length == 0) continue;
            if (path.equals("rotation")) { rotT[node] = t; rotV[node] = v; }
            else if (path.equals("translation")) { posT[node] = t; posV[node] = v; }
            else continue;
            length = Math.max(length, t[t.length - 1]);
        }
        if (length <= 0) length = 1f / 30f;
    }

    private void world(int i, Vector3[] pos, Vector3[] scl) {
        if (restWorld[i] != null) return;
        Matrix4 l = new Matrix4().set(pos[i], restRot[i], scl[i]);
        if (parent[i] < 0) {
            restWorld[i] = l;
            restWorldRot[i] = new Quaternion(restRot[i]);
        } else {
            world(parent[i], pos, scl);
            restWorld[i] = new Matrix4(restWorld[parent[i]]).mul(l);
            restWorldRot[i] = new Quaternion(restWorldRot[parent[i]]).mul(restRot[i]);
        }
    }

    /** "mixamorig:LeftArm", "mixamorig1_LeftArm", "LeftArm", "upperarm_l" all become one key. */
    static String norm(String s) {
        int c = s.lastIndexOf(':');
        if (c >= 0) s = s.substring(c + 1);
        s = s.toLowerCase(Locale.US);
        if (s.startsWith("mixamorig")) {
            s = s.substring(9);
            while (!s.isEmpty() && (Character.isDigit(s.charAt(0)) || s.charAt(0) == '_')) s = s.substring(1);
        }
        return s.replace("_", "").replace(" ", "").replace(".", "");
    }

    private void bind(Skeleton sk) {
        bound = sk;
        HashMap<String, Integer> byKey = new HashMap<>();
        for (int i = 0; i < n; i++) if (!byKey.containsKey(key[i])) byKey.put(key[i], i);
        GlbExporter unreal = new GlbExporter();
        unreal.naming = 2;
        int nj = sk.joints.size;
        map = new int[nj];
        corr = new Quaternion[nj];
        matched = 0;
        for (int j = 0; j < nj; j++) {
            String nm = sk.joints.get(j).name;
            Integer s = byKey.get(norm(nm));
            if (s == null) s = byKey.get(norm(unreal.boneName(nm)));
            map[j] = s == null ? -1 : s;
            if (s != null) matched++;
        }
        if (matched < 8) throw new RigException("The animation's bones were not recognised (Mixamo or Unreal bone names are needed)");
        Vector3 a = new Vector3(), b = new Vector3(), od = new Vector3(), sd = new Vector3();
        for (int j = 0; j < nj; j++) {
            corr[j] = new Quaternion();
            String nm = sk.joints.get(j).name;
            boolean limb = (nm.endsWith("Arm") || nm.endsWith("UpLeg") || nm.endsWith("Leg")) && !nm.contains("Hand");
            int c = sk.primaryChild(j);
            if (!limb || map[j] < 0 || c < 0 || map[c] < 0) continue;
            od.set(sk.joints.get(c).pos).sub(sk.joints.get(j).pos).nor();
            restWorld[map[c]].getTranslation(b);
            restWorld[map[j]].getTranslation(a);
            sd.set(b).sub(a).nor();
            if (od.len2() > 0.5f && sd.len2() > 0.5f) corr[j].setFromCross(od, sd);
        }
        srcHips = map[0];
        scale = 1;
        int ul = sk.index("LeftUpLeg"), ll = sk.index("LeftLeg"), lf = sk.index("LeftFoot");
        if (srcHips >= 0 && ul >= 0 && ll >= 0 && lf >= 0 && map[ul] >= 0 && map[ll] >= 0 && map[lf] >= 0) {
            Vector3 p1 = restWorld[map[ul]].getTranslation(new Vector3()), p2 = restWorld[map[ll]].getTranslation(new Vector3()),
                p3 = restWorld[map[lf]].getTranslation(new Vector3());
            float src = p1.dst(p2) + p2.dst(p3);
            if (src > 1e-6f) scale = Poses.legLength(sk) / src;
        }
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public float length() {
        return length;
    }

    private static int find(float[] t, float x) {
        int lo = 0, hi = t.length - 1;
        while (hi - lo > 1) {
            int m = (lo + hi) >>> 1;
            if (t[m] <= x) lo = m; else hi = m;
        }
        return lo;
    }

    private Quaternion localRot(int i, float t, Quaternion out) {
        float[] tt = rotT[i];
        if (tt == null) return out.set(restRot[i]);
        float[] v = rotV[i];
        if (tt.length == 1 || t <= tt[0]) return out.set(v[0], v[1], v[2], v[3]).nor();
        int k = find(tt, t);
        int k2 = Math.min(tt.length - 1, k + 1);
        float f = tt[k2] > tt[k] ? Math.min(1, (t - tt[k]) / (tt[k2] - tt[k])) : 0;
        out.set(v[k * 4], v[k * 4 + 1], v[k * 4 + 2], v[k * 4 + 3]).nor();
        return out.slerp(new Quaternion(v[k2 * 4], v[k2 * 4 + 1], v[k2 * 4 + 2], v[k2 * 4 + 3]).nor(), f);
    }

    private Quaternion worldRot(int i, float t, Quaternion[] memo) {
        if (memo[i] != null) return memo[i];
        Quaternion l = localRot(i, t, new Quaternion());
        memo[i] = parent[i] < 0 ? l : new Quaternion(worldRot(parent[i], t, memo)).mul(l);
        return memo[i];
    }

    @Override
    public Quaternion[] sample(Skeleton sk, float t, Vector3 hipsOffset) {
        if (bound != sk) bind(sk);
        t = t % length;
        int nj = sk.joints.size;
        Quaternion[] memo = new Quaternion[n];
        Quaternion[] g = new Quaternion[nj], out = new Quaternion[nj];
        for (int j = 0; j < nj; j++) {
            Skeleton.Joint jt = sk.joints.get(j);
            if (map[j] >= 0) {
                Quaternion d = new Quaternion(worldRot(map[j], t, memo)).mul(new Quaternion(restWorldRot[map[j]]).conjugate());
                g[j] = d.mul(corr[j]).mul(jt.worldRot);
            } else {
                Quaternion rl = sk.localRotation(j, new Quaternion());
                g[j] = jt.parent < 0 ? rl : new Quaternion(g[jt.parent]).mul(rl);
            }
            out[j] = jt.parent < 0 ? new Quaternion(g[j]) : new Quaternion(g[jt.parent]).conjugate().mul(g[j]);
            out[j].nor();
        }
        // roll bones follow the hand
        for (String pre : new String[]{"Left", "Right"}) {
            int roll = sk.index(pre + "ForeArmRoll"), hand = sk.index(pre + "Hand"), fa = sk.index(pre + "ForeArm");
            if (roll < 0 || hand < 0 || fa < 0) continue;
            Quaternion wh = sk.joints.get(hand).worldRot, wr = sk.joints.get(roll).worldRot;
            Quaternion dl = sk.localRotation(hand, new Quaternion()).conjugate().mul(out[hand]);
            Quaternion d = new Quaternion(wh).mul(dl).mul(new Quaternion(wh).conjugate());
            Quaternion half = Poses.halfTwist(d, new Vector3(sk.joints.get(hand).pos).sub(sk.joints.get(fa).pos).nor());
            out[roll] = sk.localRotation(roll, new Quaternion()).mul(new Quaternion(wr).conjugate().mul(half).mul(wr));
        }
        if (hipsOffset != null) {
            hipsOffset.setZero();
            if (srcHips >= 0 && posT[srcHips] != null) {
                float[] tt = posT[srcHips], v = posV[srcHips];
                int k = tt.length == 1 || t <= tt[0] ? 0 : find(tt, t);
                int k2 = Math.min(tt.length - 1, k + 1);
                float f = tt[k2] > tt[k] ? Math.max(0, Math.min(1, (t - tt[k]) / (tt[k2] - tt[k]))) : 0;
                Vector3 p = new Vector3(v[k * 3] + (v[k2 * 3] - v[k * 3]) * f, v[k * 3 + 1] + (v[k2 * 3 + 1] - v[k * 3 + 1]) * f,
                    v[k * 3 + 2] + (v[k2 * 3 + 2] - v[k * 3 + 2]) * f);
                Vector3 r = restWorld[srcHips].getTranslation(new Vector3());
                if (parent[srcHips] >= 0) p.mul(restWorld[parent[srcHips]]);
                // in place: keep the up and down motion only
                hipsOffset.set(0, (p.y - r.y) * scale, 0);
            }
        }
        return out;
    }
}
