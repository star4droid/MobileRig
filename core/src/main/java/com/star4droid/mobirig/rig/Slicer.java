package com.star4droid.mobirig.rig;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;

import java.util.HashMap;

/**
 * Cross-sections of the mesh: where a plane cuts the surface, split into separate closed
 * loops (an arm, a leg, the torso...). Used to centre joints inside the volume.
 */
public class Slicer {
    public static class Loop {
        public final Vector3 center = new Vector3();
        public float radius;      // mean distance of the outline to its centre
        public float length;      // outline length
        public final Vector3 min = new Vector3(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);
        public final Vector3 max = new Vector3(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE);
    }

    private final RigMesh mesh;
    private final int[] wtris; // welded triangle indices

    public Slicer(RigMesh mesh) {
        this.mesh = mesh;
        wtris = new int[mesh.tris.length];
        for (int i = 0; i < wtris.length; i++) wtris[i] = mesh.weld[mesh.tris[i]];
    }

    /** Loops cut by the plane through origin with the given normal. */
    public Array<Loop> section(Vector3 origin, Vector3 normalIn) {
        Vector3 n = new Vector3(normalIn).nor();
        float[] p = mesh.wpos;
        HashMap<Long, Integer> edgeId = new HashMap<>();
        Array<float[]> points = new Array<>();
        // segments as pairs of edge ids
        com.badlogic.gdx.utils.IntArray seg = new com.badlogic.gdx.utils.IntArray();
        float ox = origin.x, oy = origin.y, oz = origin.z;
        int triCount = wtris.length / 3;
        int[] ids = new int[2];
        for (int t = 0; t < triCount; t++) {
            int found = 0;
            for (int e = 0; e < 3 && found < 2; e++) {
                int a = wtris[t * 3 + e], b = wtris[t * 3 + (e + 1) % 3];
                float da = (p[a * 3] - ox) * n.x + (p[a * 3 + 1] - oy) * n.y + (p[a * 3 + 2] - oz) * n.z;
                float db = (p[b * 3] - ox) * n.x + (p[b * 3 + 1] - oy) * n.y + (p[b * 3 + 2] - oz) * n.z;
                if (da == 0) da = 1e-9f;
                if (db == 0) db = 1e-9f;
                if (da * db >= 0) continue;
                long key = a < b ? ((long) a << 32) | b : ((long) b << 32) | a;
                Integer id = edgeId.get(key);
                if (id == null) {
                    float f = da / (da - db);
                    id = points.size;
                    points.add(new float[]{
                        p[a * 3] + (p[b * 3] - p[a * 3]) * f,
                        p[a * 3 + 1] + (p[b * 3 + 1] - p[a * 3 + 1]) * f,
                        p[a * 3 + 2] + (p[b * 3 + 2] - p[a * 3 + 2]) * f});
                    edgeId.put(key, id);
                }
                ids[found++] = id;
            }
            if (found == 2) seg.add(ids[0], ids[1]);
        }
        int np = points.size;
        int[] parent = new int[np];
        for (int i = 0; i < np; i++) parent[i] = i;
        for (int s = 0; s < seg.size; s += 2) union(parent, seg.get(s), seg.get(s + 1));
        HashMap<Integer, Loop> loops = new HashMap<>();
        for (int s = 0; s < seg.size; s += 2) {
            int r = find(parent, seg.get(s));
            Loop l = loops.get(r);
            if (l == null) {
                l = new Loop();
                loops.put(r, l);
            }
            float[] a = points.get(seg.get(s)), b = points.get(seg.get(s + 1));
            float len = (float) Math.sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]));
            l.length += len;
            l.center.add((a[0] + b[0]) * 0.5f * len, (a[1] + b[1]) * 0.5f * len, (a[2] + b[2]) * 0.5f * len);
            l.min.set(Math.min(l.min.x, a[0]), Math.min(l.min.y, a[1]), Math.min(l.min.z, a[2]));
            l.max.set(Math.max(l.max.x, a[0]), Math.max(l.max.y, a[1]), Math.max(l.max.z, a[2]));
        }
        Array<Loop> out = new Array<>();
        for (Loop l : loops.values()) {
            if (l.length <= 0) continue;
            l.center.scl(1f / l.length);
            out.add(l);
        }
        // radius pass
        for (int s = 0; s < seg.size; s += 2) {
            Loop l = loops.get(find(parent, seg.get(s)));
            if (l == null || l.length <= 0) continue;
            float[] a = points.get(seg.get(s)), b = points.get(seg.get(s + 1));
            float len = (float) Math.sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]));
            float mx = (a[0] + b[0]) * 0.5f, my = (a[1] + b[1]) * 0.5f, mz = (a[2] + b[2]) * 0.5f;
            l.radius += len * Vector3.dst(mx, my, mz, l.center.x, l.center.y, l.center.z) / l.length;
        }
        return out;
    }

    /** The loop whose centre is closest to the hint, ignoring tiny loops (buttons, eyelids). */
    public Loop nearest(Array<Loop> loops, Vector3 hint, float minLength) {
        Loop best = null;
        float bd = Float.MAX_VALUE;
        for (Loop l : loops) {
            if (l.length < minLength) continue;
            float d = l.center.dst2(hint);
            if (d < bd) {
                bd = d;
                best = l;
            }
        }
        return best;
    }

    /** The loop that contains the hint inside its bounding box (in the plane), else the nearest. */
    public Loop containing(Array<Loop> loops, Vector3 hint, float minLength) {
        Loop best = null;
        float bestLen = Float.MAX_VALUE;
        float eps = mesh.height() * 0.002f;
        for (Loop l : loops) {
            if (l.length < minLength) continue;
            boolean inside = hint.x >= l.min.x - eps && hint.x <= l.max.x + eps
                && hint.y >= l.min.y - eps && hint.y <= l.max.y + eps
                && hint.z >= l.min.z - eps && hint.z <= l.max.z + eps;
            if (inside && l.length < bestLen) { // innermost (smallest) containing loop
                bestLen = l.length;
                best = l;
            }
        }
        return best != null ? best : nearest(loops, hint, minLength);
    }

    private static int find(int[] p, int i) {
        while (p[i] != i) {
            p[i] = p[p[i]];
            i = p[i];
        }
        return i;
    }

    private static void union(int[] p, int a, int b) {
        a = find(p, a);
        b = find(p, b);
        if (a != b) p[a] = b;
    }
}
