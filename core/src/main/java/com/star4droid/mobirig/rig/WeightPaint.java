package com.star4droid.mobirig.rig;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.IntArray;

/** Hand editing of skin weights on the rest pose: add, subtract and smooth, with left/right mirror and undo. */
public class WeightPaint {
    public enum Mode { ADD, SUBTRACT, SMOOTH }

    private final RigMesh mesh;
    private final Skeleton sk;
    private final int[] joints;
    private final float[] weights;
    private final int[] rep;       // welded id -> one vertex
    private final Array<int[]> undoJ = new Array<>();
    private final Array<float[]> undoW = new Array<>();
    public boolean dirty;

    public WeightPaint(RigMesh mesh, Skeleton sk, int[] joints, float[] weights) {
        this.mesh = mesh;
        this.sk = sk;
        this.joints = joints;
        this.weights = weights;
        rep = new int[mesh.weldedCount];
        for (int v = mesh.vertexCount - 1; v >= 0; v--) rep[mesh.weld[v]] = v;
    }

    /** Call when a stroke starts. */
    public void begin() {
        undoJ.add(joints.clone());
        undoW.add(weights.clone());
        if (undoJ.size > 8) {
            undoJ.removeIndex(0);
            undoW.removeIndex(0);
        }
    }

    public boolean canUndo() {
        return undoJ.size > 0;
    }

    public void undo() {
        if (undoJ.size == 0) return;
        System.arraycopy(undoJ.pop(), 0, joints, 0, joints.length);
        System.arraycopy(undoW.pop(), 0, weights, 0, weights.length);
        dirty = true;
    }

    /** First triangle hit by the ray on the rest pose; returns false when the ray misses. */
    public boolean pick(Ray ray, Vector3 out) {
        float[] p = mesh.pos;
        float best = Float.MAX_VALUE;
        float ox = ray.origin.x, oy = ray.origin.y, oz = ray.origin.z, dx = ray.direction.x, dy = ray.direction.y, dz = ray.direction.z;
        for (int t = 0; t < mesh.triCount; t++) {
            int a = mesh.tris[t * 3] * 3, b = mesh.tris[t * 3 + 1] * 3, c = mesh.tris[t * 3 + 2] * 3;
            float e1x = p[b] - p[a], e1y = p[b + 1] - p[a + 1], e1z = p[b + 2] - p[a + 2];
            float e2x = p[c] - p[a], e2y = p[c + 1] - p[a + 1], e2z = p[c + 2] - p[a + 2];
            float px = dy * e2z - dz * e2y, py = dz * e2x - dx * e2z, pz = dx * e2y - dy * e2x;
            float det = e1x * px + e1y * py + e1z * pz;
            if (det > -1e-12f && det < 1e-12f) continue;
            float inv = 1f / det;
            float tx = ox - p[a], ty = oy - p[a + 1], tz = oz - p[a + 2];
            float u = (tx * px + ty * py + tz * pz) * inv;
            if (u < 0 || u > 1) continue;
            float qx = ty * e1z - tz * e1y, qy = tz * e1x - tx * e1z, qz = tx * e1y - ty * e1x;
            float v = (dx * qx + dy * qy + dz * qz) * inv;
            if (v < 0 || u + v > 1) continue;
            float d = (e2x * qx + e2y * qy + e2z * qz) * inv;
            if (d > 1e-6f && d < best) best = d;
        }
        if (best == Float.MAX_VALUE) return false;
        out.set(ox + dx * best, oy + dy * best, oz + dz * best);
        return true;
    }

    /** The bone with the most weight at the vertex closest to a point. */
    public int boneAt(Vector3 at) {
        int bv = -1;
        float bd = Float.MAX_VALUE;
        for (int v = 0; v < mesh.vertexCount; v++) {
            float d = Vector3.dst2(mesh.pos[v * 3], mesh.pos[v * 3 + 1], mesh.pos[v * 3 + 2], at.x, at.y, at.z);
            if (d < bd) { bd = d; bv = v; }
        }
        if (bv < 0) return -1;
        int k = 0;
        for (int i = 1; i < 4; i++) if (weights[bv * 4 + i] > weights[bv * 4 + k]) k = i;
        return joints[bv * 4 + k];
    }

    public int mirrorBone(int bone) {
        String n = sk.joints.get(bone).name;
        int m = n.startsWith("Left") ? sk.index("Right" + n.substring(4)) : n.startsWith("Right") ? sk.index("Left" + n.substring(5)) : bone;
        return m < 0 ? bone : m;
    }

    /** One dab of the brush. */
    public void dab(Vector3 at, float radius, int bone, Mode mode, float strength, boolean mirror) {
        apply(at.x, at.y, at.z, radius, bone, mode, strength);
        if (mirror) apply(-at.x, at.y, at.z, radius, mirrorBone(bone), mode, strength);
        dirty = true;
    }

    private void apply(float cx, float cy, float cz, float radius, int bone, Mode mode, float strength) {
        float r2 = radius * radius;
        int[] ij = new int[5];
        float[] iw = new float[5];
        if (mode == Mode.SMOOTH) {
            IntArray inside = new IntArray();
            for (int w = 0; w < mesh.weldedCount; w++)
                if (Vector3.dst2(mesh.wpos[w * 3], mesh.wpos[w * 3 + 1], mesh.wpos[w * 3 + 2], cx, cy, cz) <= r2) inside.add(w);
            int nj = sk.joints.size;
            float[] acc = new float[nj];
            float[] res = new float[inside.size * 4];
            int[] resJ = new int[inside.size * 4];
            for (int i = 0; i < inside.size; i++) {
                int w = inside.get(i);
                java.util.Arrays.fill(acc, 0);
                float fall = 1 - (float) Math.sqrt(Vector3.dst2(mesh.wpos[w * 3], mesh.wpos[w * 3 + 1], mesh.wpos[w * 3 + 2], cx, cy, cz)) / radius;
                float mix = Math.min(1, strength * (0.4f + 0.6f * fall));
                int cnt = mesh.adjStart[w + 1] - mesh.adjStart[w];
                int v0 = rep[w];
                for (int k = 0; k < 4; k++) acc[joints[v0 * 4 + k]] += weights[v0 * 4 + k] * (1 - mix);
                for (int a = mesh.adjStart[w]; a < mesh.adjStart[w + 1]; a++) {
                    int v = rep[mesh.adj[a]];
                    for (int k = 0; k < 4; k++) acc[joints[v * 4 + k]] += weights[v * 4 + k] * mix / Math.max(1, cnt);
                }
                // 4 strongest
                for (int k = 0; k < 4; k++) {
                    int bj = 0;
                    for (int j = 1; j < nj; j++) if (acc[j] > acc[bj]) bj = j;
                    resJ[i * 4 + k] = bj;
                    res[i * 4 + k] = acc[bj];
                    acc[bj] = -1;
                }
            }
            int[] slot = new int[mesh.weldedCount];
            java.util.Arrays.fill(slot, -1);
            for (int i = 0; i < inside.size; i++) slot[inside.get(i)] = i;
            for (int v = 0; v < mesh.vertexCount; v++) {
                int i = slot[mesh.weld[v]];
                if (i < 0) continue;
                for (int k = 0; k < 4; k++) { ij[k] = resJ[i * 4 + k]; iw[k] = Math.max(0, res[i * 4 + k]); }
                WeightOps.put(joints, weights, v, ij, iw, 4);
            }
            return;
        }
        for (int v = 0; v < mesh.vertexCount; v++) {
            float d2 = Vector3.dst2(mesh.pos[v * 3], mesh.pos[v * 3 + 1], mesh.pos[v * 3 + 2], cx, cy, cz);
            if (d2 > r2) continue;
            float fall = 1 - (float) Math.sqrt(d2) / radius;
            float amt = strength * (0.35f + 0.65f * fall);
            float cur = 0;
            int n = 0;
            for (int k = 0; k < 4; k++) {
                if (weights[v * 4 + k] <= 0) continue;
                if (joints[v * 4 + k] == bone) cur += weights[v * 4 + k];
                else { ij[n] = joints[v * 4 + k]; iw[n++] = weights[v * 4 + k]; }
            }
            float others = 1 - cur;
            float target = mode == Mode.ADD ? Math.min(1, cur + amt) : Math.max(0, cur - amt);
            if (mode == Mode.SUBTRACT && others <= 1e-4f) continue; // nothing else could take the weight
            float sc = others > 1e-6f ? (1 - target) / others : 0;
            for (int k = 0; k < n; k++) iw[k] *= sc;
            ij[n] = bone;
            iw[n++] = target;
            WeightOps.put(joints, weights, v, ij, iw, n);
        }
    }
}
