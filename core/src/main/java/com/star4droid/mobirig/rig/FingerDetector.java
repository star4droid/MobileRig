package com.star4droid.mobirig.rig;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.IntArray;

import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;

/**
 * Finds the fingers of the (left) hand from the mesh alone and places Mixamo finger joints.
 *
 * Distances are measured along the skin from the wrist. At a given distance the hand cuts
 * into separate rings, one per finger (exact iso-contours, so it works on low and high poly
 * meshes, spread or relaxed fingers, and thumbs pointing anywhere). Rings are tracked from the
 * finger tips down to where they join the palm; that join gives the knuckle, the ring centres
 * give the finger centre line. Weights per phalanx follow the same skin distance, so each
 * finger only ever moves its own skin. The right hand is the mirror image.
 */
public class FingerDetector {
    public static class Finger {
        public String name;
        public boolean thumb;
        public final Vector3[] joints = {new Vector3(), new Vector3(), new Vector3(), new Vector3()};
        final float[] lv = new float[4];
        float length;
        Vector3 base;
        int mergeLevel;
        Chain chain;
    }

    static class Loop {
        final Vector3 c = new Vector3();
        float r, len;
        int level;
        float[] pts;
        Chain chain;
    }

    static class Chain {
        final Array<Loop> loops = new Array<>(); // tip first
        boolean tip, closed;
        int mergeLevel = -1;
    }

    public final Array<Finger> fingers = new Array<>();
    public final Array<String> notes = new Array<>();

    private RigMesh mesh;
    private float[] g;
    private boolean[] mask;
    private int[] wtri;
    private float step, lh;
    private int levels;
    private final Array<Array<Loop>> byLevel = new Array<>();
    // per welded vertex of the left hand: finger index (-1 = none) and {hand, b1, b2, b3}
    private int[] vFinger;
    private float[] vW;
    private HashMap<Long, IntArray> grid;
    private float cell;

    /** Returns true when at least one finger was found. */
    public boolean detect(RigMesh mesh, Vector3 wrist, Vector3 tipGuess) {
        this.mesh = mesh;
        int nw = mesh.weldedCount;
        float[] p = mesh.wpos;
        Vector3 d = new Vector3(tipGuess).sub(wrist).nor();
        // hand extent along the hand direction
        lh = 0;
        float reach = wrist.dst(tipGuess) * 1.6f;
        for (int i = 0; i < nw; i++) {
            if (p[i * 3] <= 0) continue;
            float dx = p[i * 3] - wrist.x, dy = p[i * 3 + 1] - wrist.y, dz = p[i * 3 + 2] - wrist.z;
            if (dx * dx + dy * dy + dz * dz > reach * reach) continue;
            lh = Math.max(lh, dx * d.x + dy * d.y + dz * d.z);
        }
        if (lh <= 0) return fail("Hand not found next to the wrist marker");
        mask = new boolean[nw];
        IntArray seeds = new IntArray();
        for (int i = 0; i < nw; i++) {
            if (p[i * 3] <= 0) continue;
            float dx = p[i * 3] - wrist.x, dy = p[i * 3 + 1] - wrist.y, dz = p[i * 3 + 2] - wrist.z;
            float along = dx * d.x + dy * d.y + dz * d.z;
            if (along < -0.06f * lh || dx * dx + dy * dy + dz * dz > (1.5f * lh) * (1.5f * lh)) continue;
            mask[i] = true;
            if (Math.abs(along) <= 0.05f * lh) seeds.add(i);
        }
        if (seeds.size == 0) return fail("No skin found at the wrist marker");
        geodesic(seeds);
        float gmax = 0;
        for (int i = 0; i < nw; i++) if (mask[i] && g[i] < Float.MAX_VALUE) gmax = Math.max(gmax, g[i]);
        if (gmax < 0.3f * lh) return fail("Hand too short to find fingers");

        // welded triangles inside the hand
        IntArray t = new IntArray();
        for (int i = 0; i < mesh.triCount; i++) {
            int a = mesh.weld[mesh.tris[i * 3]], b = mesh.weld[mesh.tris[i * 3 + 1]], c = mesh.weld[mesh.tris[i * 3 + 2]];
            if (a == b || b == c || a == c) continue;
            if (!reached(a) || !reached(b) || !reached(c)) continue;
            t.add(a, b, c);
        }
        wtri = t.toArray();

        levels = 72;
        step = gmax / levels;
        byLevel.clear();
        for (int k = 0; k < levels; k++) byLevel.add(new Array<Loop>());
        Array<Chain> all = new Array<>();
        Array<Chain> active = new Array<>();
        for (int k = levels - 1; k >= 1; k--) {
            Array<Loop> loops = contours(k * step, k);
            byLevel.set(k, loops);
            IdentityHashMap<Loop, Array<Chain>> assigned = new IdentityHashMap<>();
            for (Chain ch : active) {
                Loop last = ch.loops.peek();
                Loop best = null;
                float bd = Float.MAX_VALUE;
                for (Loop l : loops) {
                    float dist = pointDistance(l, last.c);
                    if (dist < bd) {
                        bd = dist;
                        best = l;
                    }
                }
                if (best != null && bd <= Math.max(2.5f * last.r, 0.12f * lh)) {
                    Array<Chain> list = assigned.get(best);
                    if (list == null) assigned.put(best, list = new Array<>());
                    list.add(ch);
                } else {
                    ch.closed = true;
                    ch.mergeLevel = k;
                }
            }
            Array<Chain> next = new Array<>();
            for (Loop l : loops) {
                Array<Chain> list = assigned.get(l);
                Chain ch;
                if (list == null) {
                    ch = new Chain();
                    ch.tip = true;
                    all.add(ch);
                } else if (list.size == 1) {
                    ch = list.first();
                } else {
                    for (Chain c : list) {
                        c.closed = true;
                        c.mergeLevel = k;
                    }
                    ch = new Chain();
                    all.add(ch);
                }
                ch.loops.add(l);
                l.chain = ch;
                next.add(ch);
            }
            active = next;
        }
        for (Chain ch : active) if (!ch.closed) ch.mergeLevel = 0;

        // finger candidates: started at a tip, high enough, long enough, and not the whole hand
        Array<Finger> cand = new Array<>();
        for (Chain ch : all) {
            if (!ch.tip || ch.loops.size < 3) continue;
            int top = ch.loops.first().level, bottom = ch.loops.peek().level;
            float span = (top - bottom) * step;
            if (top * step < 0.3f * gmax || span < 0.12f * lh) continue;
            if (bottom * step < 0.12f * gmax) continue; // never left the palm: whole hand or sleeve
            Finger f = new Finger();
            f.chain = ch;
            f.mergeLevel = ch.mergeLevel;
            cand.add(f);
        }
        if (cand.size == 0) return fail("No separate fingers found (mitten hand?): the hand is rigged as one bone");
        cand.sort((a, b) -> Float.compare(spanOf(b), spanOf(a)));
        while (cand.size > 5) cand.pop();

        // thumb: the finger that joins the palm closest to the wrist
        Finger thumb = null;
        if (cand.size >= 2) {
            for (Finger f : cand) if (thumb == null || f.mergeLevel < thumb.mergeLevel) thumb = f;
        } else thumb = cand.first();
        for (Finger f : cand) place(f, f == thumb);
        Array<Finger> others = new Array<>();
        for (Finger f : cand) if (f != thumb) others.add(f);
        final Vector3 tb = thumb.base;
        others.sort((a, b) -> Float.compare(a.base.dst2(tb), b.base.dst2(tb)));
        String[] names = {"Index", "Middle", "Ring", "Pinky"};
        thumb.name = "Thumb";
        thumb.thumb = true;
        fingers.add(thumb);
        for (int i = 0; i < others.size; i++) {
            others.get(i).name = names[i];
            fingers.add(others.get(i));
        }
        notes.add("Fingers: " + fingers.size + " per hand detected");
        buildWeights();
        return true;
    }

    private boolean reached(int i) {
        return mask[i] && g[i] < Float.MAX_VALUE;
    }

    private boolean fail(String why) {
        notes.add(why);
        return false;
    }

    private float spanOf(Finger f) {
        return (f.chain.loops.first().level - f.chain.loops.peek().level) * step;
    }

    private void geodesic(IntArray seeds) {
        int nw = mesh.weldedCount;
        g = new float[nw];
        Arrays.fill(g, Float.MAX_VALUE);
        java.util.PriorityQueue<long[]> q = new java.util.PriorityQueue<>((a, b) -> Long.compare(a[0], b[0]));
        for (int i = 0; i < seeds.size; i++) {
            g[seeds.get(i)] = 0;
            q.add(new long[]{0, seeds.get(i)});
        }
        float[] p = mesh.wpos;
        while (!q.isEmpty()) {
            long[] e = q.poll();
            int v = (int) e[1];
            float dv = Float.intBitsToFloat((int) e[0]);
            if (dv > g[v]) continue;
            for (int k = mesh.adjStart[v]; k < mesh.adjStart[v + 1]; k++) {
                int w = mesh.adj[k];
                if (!mask[w]) continue;
                float dx = p[v * 3] - p[w * 3], dy = p[v * 3 + 1] - p[w * 3 + 1], dz = p[v * 3 + 2] - p[w * 3 + 2];
                float nd = dv + (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (nd < g[w]) {
                    g[w] = nd;
                    q.add(new long[]{Float.floatToIntBits(nd), w}); // positive floats sort like their bits
                }
            }
        }
    }

    /** Iso-contours of the skin distance at level c, as separate loops. */
    private Array<Loop> contours(float c, int level) {
        float[] p = mesh.wpos;
        HashMap<Long, Integer> edgeId = new HashMap<>();
        com.badlogic.gdx.utils.FloatArray pts = new com.badlogic.gdx.utils.FloatArray();
        IntArray seg = new IntArray();
        int[] ids = new int[2];
        for (int t = 0; t < wtri.length; t += 3) {
            int found = 0;
            for (int e = 0; e < 3 && found < 2; e++) {
                int a = wtri[t + e], b = wtri[t + (e + 1) % 3];
                float da = g[a] - c, db = g[b] - c;
                if (da == 0) da = 1e-7f;
                if (db == 0) db = 1e-7f;
                if (da * db >= 0) continue;
                long key = a < b ? ((long) a << 32) | b : ((long) b << 32) | a;
                Integer id = edgeId.get(key);
                if (id == null) {
                    float f = da / (da - db);
                    id = pts.size / 3;
                    pts.add(p[a * 3] + (p[b * 3] - p[a * 3]) * f, p[a * 3 + 1] + (p[b * 3 + 1] - p[a * 3 + 1]) * f,
                        p[a * 3 + 2] + (p[b * 3 + 2] - p[a * 3 + 2]) * f);
                    edgeId.put(key, id);
                }
                ids[found++] = id;
            }
            if (found == 2) seg.add(ids[0], ids[1]);
        }
        int np = pts.size / 3;
        int[] parent = new int[np];
        for (int i = 0; i < np; i++) parent[i] = i;
        for (int s = 0; s < seg.size; s += 2) union(parent, seg.get(s), seg.get(s + 1));
        HashMap<Integer, IntArray> groups = new HashMap<>();
        for (int i = 0; i < np; i++) {
            int r = find(parent, i);
            IntArray l = groups.get(r);
            if (l == null) groups.put(r, l = new IntArray());
            l.add(i);
        }
        Array<Loop> out = new Array<>();
        for (IntArray grp : groups.values()) {
            if (grp.size < 3) continue;
            Loop l = new Loop();
            l.level = level;
            l.pts = new float[grp.size * 3];
            for (int i = 0; i < grp.size; i++) {
                int q = grp.get(i);
                l.pts[i * 3] = pts.get(q * 3);
                l.pts[i * 3 + 1] = pts.get(q * 3 + 1);
                l.pts[i * 3 + 2] = pts.get(q * 3 + 2);
                l.c.add(l.pts[i * 3], l.pts[i * 3 + 1], l.pts[i * 3 + 2]);
            }
            l.c.scl(1f / grp.size);
            float r = 0, mx = 0;
            for (int i = 0; i < grp.size; i++) {
                float dd = Vector3.dst(l.pts[i * 3], l.pts[i * 3 + 1], l.pts[i * 3 + 2], l.c.x, l.c.y, l.c.z);
                r += dd;
                mx = Math.max(mx, dd);
            }
            l.r = r / grp.size;
            l.len = l.r * 6.283f;
            if (mx < 0.015f * lh) continue; // speck
            out.add(l);
        }
        return out;
    }

    private static float pointDistance(Loop l, Vector3 q) {
        float best = Float.MAX_VALUE;
        for (int i = 0; i < l.pts.length; i += 3) {
            float d = Vector3.dst2(l.pts[i], l.pts[i + 1], l.pts[i + 2], q.x, q.y, q.z);
            if (d < best) best = d;
        }
        return Math.min((float) Math.sqrt(best), l.c.dst(q));
    }

    /** Joint positions along the finger centre line, knuckle pushed back into the palm. */
    private void place(Finger f, boolean thumb) {
        Array<Loop> ls = f.chain.loops; // tip first
        int n = ls.size;
        Array<Vector3> pts = new Array<>();
        for (int i = n - 1; i >= 0; i--) pts.add(new Vector3(ls.get(i).c));
        Vector3 s = pts.first();
        Vector3 tipDir = new Vector3(pts.peek()).sub(pts.get(n - 2)).nor();
        Vector3 tip = new Vector3(pts.peek()).mulAdd(tipDir, ls.first().r);
        pts.add(tip);
        float lenP = 0;
        for (int i = 1; i < pts.size; i++) lenP += pts.get(i).dst(pts.get(i - 1));
        Vector3 u = new Vector3(pts.get(Math.min(2, pts.size - 1))).sub(s).nor();
        Vector3 knuckle = new Vector3(s).mulAdd(u, -(thumb ? 0.45f : 0.28f) * lenP);
        pts.insert(0, knuckle);
        float total = 0;
        for (int i = 1; i < pts.size; i++) total += pts.get(i).dst(pts.get(i - 1));
        float[] frac = thumb ? new float[]{0f, 0.38f, 0.70f, 1f} : new float[]{0f, 0.42f, 0.72f, 1f};
        float levelS = ls.peek().level * step;
        float levelK = levelS - knuckle.dst(s);
        for (int j = 0; j < 4; j++) {
            f.joints[j].set(pointAt(pts, frac[j] * total));
            f.lv[j] = levelK + frac[j] * total;
        }
        f.length = total;
        f.base = s;
    }

    private static Vector3 pointAt(Array<Vector3> poly, float dist) {
        for (int i = 1; i < poly.size; i++) {
            float seg = poly.get(i).dst(poly.get(i - 1));
            if (dist <= seg || i == poly.size - 1) {
                float t = seg > 0 ? Math.min(1f, dist / seg) : 0;
                return new Vector3(poly.get(i - 1)).lerp(poly.get(i), t);
            }
            dist -= seg;
        }
        return new Vector3(poly.peek());
    }

    // ------------------------------------------------------------------ weights

    private void buildWeights() {
        int nw = mesh.weldedCount;
        float[] p = mesh.wpos;
        vFinger = new int[nw];
        Arrays.fill(vFinger, -1);
        vW = new float[nw * 4];
        IdentityHashMap<Chain, Integer> fingerOf = new IdentityHashMap<>();
        for (int i = 0; i < fingers.size; i++) fingerOf.put(fingers.get(i).chain, i);
        Vector3 q = new Vector3();
        grid = new HashMap<>();
        cell = Math.max(lh * 0.04f, 1e-4f);
        for (int v = 0; v < nw; v++) {
            if (!reached(v)) continue;
            q.set(p[v * 3], p[v * 3 + 1], p[v * 3 + 2]);
            long key = key(q.x, q.y, q.z);
            IntArray cl = grid.get(key);
            if (cl == null) grid.put(key, cl = new IntArray());
            cl.add(v);
            int k = Math.max(1, Math.min(levels - 1, Math.round(g[v] / step)));
            Loop best = null;
            float bd = Float.MAX_VALUE;
            for (Loop l : byLevel.get(k)) {
                float dd = pointDistance(l, q);
                if (dd < bd) {
                    bd = dd;
                    best = l;
                }
            }
            int f = best != null && fingerOf.containsKey(best.chain) ? fingerOf.get(best.chain) : -1;
            if (f < 0) {
                // palm: join the closest knuckle when near it
                float kd = Float.MAX_VALUE;
                for (int i = 0; i < fingers.size; i++) {
                    float dd = fingers.get(i).joints[0].dst(q);
                    if (dd < kd && dd < 0.6f * fingers.get(i).length) {
                        kd = dd;
                        f = i;
                    }
                }
            }
            if (f < 0) continue;
            Finger fg = fingers.get(f);
            float b = Math.max(0.09f * fg.length, step * 0.6f);
            float x = g[v];
            float s0 = smooth((x - fg.lv[0] + b) / (2 * b));
            float s1 = smooth((x - fg.lv[1] + b) / (2 * b));
            float s2 = smooth((x - fg.lv[2] + b) / (2 * b));
            vFinger[v] = f;
            vW[v * 4] = 1 - s0;
            vW[v * 4 + 1] = s0 * (1 - s1);
            vW[v * 4 + 2] = s1 * (1 - s2);
            vW[v * 4 + 3] = s2;
        }
    }

    private static float smooth(float t) {
        t = t < 0 ? 0 : (t > 1 ? 1 : t);
        return t * t * (3 - 2 * t);
    }

    private long key(float x, float y, float z) {
        long ix = (long) Math.floor(x / cell), iy = (long) Math.floor(y / cell), iz = (long) Math.floor(z / cell);
        return (ix * 73856093L) ^ (iy * 19349663L) ^ (iz * 83492791L);
    }

    private int nearestLeft(float x, float y, float z) {
        int best = -1;
        float bd = Float.MAX_VALUE;
        long ix = (long) Math.floor(x / cell), iy = (long) Math.floor(y / cell), iz = (long) Math.floor(z / cell);
        float[] p = mesh.wpos;
        for (long a = -1; a <= 1; a++)
            for (long b = -1; b <= 1; b++)
                for (long c = -1; c <= 1; c++) {
                    long k = ((ix + a) * 73856093L) ^ ((iy + b) * 19349663L) ^ ((iz + c) * 83492791L);
                    IntArray cl = grid.get(k);
                    if (cl == null) continue;
                    for (int i = 0; i < cl.size; i++) {
                        int v = cl.get(i);
                        float d = Vector3.dst2(p[v * 3], p[v * 3 + 1], p[v * 3 + 2], x, y, z);
                        if (d < bd) {
                            bd = d;
                            best = v;
                        }
                    }
                }
        return best;
    }

    /** Adds the finger joints (both hands, mirrored) to a fitted Mixamo skeleton. */
    public void addJoints(Skeleton sk) {
        Vector3 aim = new Vector3();
        int count = 0;
        for (Finger f : fingers) {
            if (f.thumb) continue;
            aim.add(f.joints[0]);
            count++;
        }
        for (int side = 1; side >= -1; side -= 2) {
            String pre = side > 0 ? "Left" : "Right";
            int hand = sk.index(pre + "Hand");
            Skeleton.Joint hj = sk.joints.get(hand);
            if (count > 0) {
                Vector3 a = new Vector3(aim).scl(1f / count);
                a.x *= side;
                hj.aim = a;
                hj.tail.set(a);
            }
            for (Finger f : fingers) {
                int parent = hand;
                for (int j = 0; j < 4; j++) {
                    int idx = sk.add(pre + "Hand" + f.name + (j + 1), parent, side);
                    Skeleton.Joint jt = sk.joints.get(idx);
                    jt.pos.set(f.joints[j].x * side, f.joints[j].y, f.joints[j].z);
                    jt.finger = true;
                    jt.deform = j < 3;
                    parent = idx;
                }
            }
        }
        for (int j = 0; j < sk.joints.size; j++) {
            Skeleton.Joint jt = sk.joints.get(j);
            if (!jt.finger) continue;
            int c = sk.primaryChild(j);
            if (c >= 0) jt.tail.set(sk.joints.get(c).pos);
        }
    }

    /** Splits each hand vertex's Hand weight over the hand and its finger bones. */
    public void applyWeights(RigMesh mesh, Skeleton sk, int[] joints, float[] weights) {
        int[][][] bone = new int[2][fingers.size][3];
        int[] hand = {sk.index("LeftHand"), sk.index("RightHand")};
        for (int s = 0; s < 2; s++)
            for (int f = 0; f < fingers.size; f++)
                for (int k = 0; k < 3; k++)
                    bone[s][f][k] = sk.index((s == 0 ? "Left" : "Right") + "Hand" + fingers.get(f).name + (k + 1));
        float[] p = mesh.pos;
        int[] ij = new int[8];
        float[] iw = new float[8];
        for (int v = 0; v < mesh.vertexCount; v++) {
            int side = -1, slot = -1;
            for (int k = 0; k < 4; k++) {
                int j = joints[v * 4 + k];
                if (weights[v * 4 + k] <= 0) continue;
                if (j == hand[0]) { side = 0; slot = k; }
                else if (j == hand[1]) { side = 1; slot = k; }
            }
            if (slot < 0) continue;
            float x = p[v * 3], y = p[v * 3 + 1], z = p[v * 3 + 2];
            int src = side == 0 ? nearestLeft(x, y, z) : nearestLeft(-x, y, z);
            if (src < 0 || vFinger[src] < 0) continue;
            int f = vFinger[src];
            float wh = weights[v * 4 + slot];
            int n = 0;
            for (int k = 0; k < 4; k++) {
                if (k == slot || weights[v * 4 + k] <= 0) continue;
                ij[n] = joints[v * 4 + k];
                iw[n++] = weights[v * 4 + k];
            }
            ij[n] = hand[side];
            iw[n++] = wh * vW[src * 4];
            for (int k = 0; k < 3; k++) {
                ij[n] = bone[side][f][k];
                iw[n++] = wh * vW[src * 4 + 1 + k];
            }
            // keep the 4 strongest
            for (int a = 0; a < n; a++)
                for (int b = a + 1; b < n; b++)
                    if (iw[b] > iw[a]) {
                        float tw = iw[a]; iw[a] = iw[b]; iw[b] = tw;
                        int tj = ij[a]; ij[a] = ij[b]; ij[b] = tj;
                    }
            float sum = 0;
            for (int k = 0; k < 4 && k < n; k++) if (iw[k] >= 0.01f) sum += iw[k];
            for (int k = 0; k < 4; k++) {
                boolean keep = k < n && iw[k] >= 0.01f && sum > 0;
                joints[v * 4 + k] = keep ? ij[k] : 0;
                weights[v * 4 + k] = keep ? iw[k] / sum : 0;
            }
        }
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
