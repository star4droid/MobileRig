package com.star4droid.mobirig.rig;

import com.badlogic.gdx.math.Matrix3;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Quaternion;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.IntArray;
import com.badlogic.gdx.utils.JsonValue;

import java.util.HashMap;

/**
 * All triangle primitives of a GLB, merged into one mesh in world space (orientation baked in).
 * Keeps a map back to every primitive so the exporter can write the original structure again.
 */
public class RigMesh {
    /** One glTF primitive instanced by one node. */
    public static class Part {
        public int node, mesh, prim;
        public int vertexOffset, vertexCount;
        public int[] localIndices;       // triangle indices local to the part
        public boolean flip;             // negative determinant: winding must be reversed
        public float[] linear;           // per-vertex 3x3 linear transform (9 floats) for normals/targets
        public String name;
    }

    public final Array<Part> parts = new Array<>();
    public float[] pos;      // 3 per vertex, world space
    public float[] nrm;      // 3 per vertex, may be zero if the source had none
    public int[] tris;       // global vertex indices
    public int vertexCount, triCount;

    // welded topology (positions merged)
    public int[] weld;       // vertex -> welded id
    public int weldedCount;
    public float[] wpos;     // welded positions
    public int[] adjStart, adj;  // CSR adjacency over welded vertices
    public int[] island;     // welded id -> island id
    public int islandCount;
    public int[] islandSize;

    public final BoundingBox bounds = new BoundingBox();

    public static RigMesh build(GlbFile glb, Matrix4 orient) {
        RigMesh m = new RigMesh();
        JsonValue json = glb.json;
        JsonValue nodes = json.get("nodes");
        if (nodes == null || json.get("meshes") == null) throw new RigException("The file has no meshes");
        int sceneIdx = json.getInt("scene", 0);
        JsonValue scenes = json.get("scenes");
        Matrix4[] global = new Matrix4[nodes.size];
        IntArray roots = new IntArray();
        if (scenes != null && scenes.size > sceneIdx) {
            JsonValue sn = scenes.get(sceneIdx).get("nodes");
            if (sn != null) for (JsonValue v = sn.child; v != null; v = v.next) roots.add(v.asInt());
        } else {
            boolean[] child = new boolean[nodes.size];
            for (JsonValue n = nodes.child; n != null; n = n.next) {
                JsonValue ch = n.get("children");
                if (ch != null) for (JsonValue c = ch.child; c != null; c = c.next) child[c.asInt()] = true;
            }
            for (int i = 0; i < nodes.size; i++) if (!child[i]) roots.add(i);
        }
        IntArray order = new IntArray();
        for (int i = 0; i < roots.size; i++) walk(nodes, roots.get(i), new Matrix4(), global, order);

        // collect positions per part
        Array<float[]> pPos = new Array<>(), pNrm = new Array<>();
        int total = 0;
        for (int oi = 0; oi < order.size; oi++) {
            int ni = order.get(oi);
            JsonValue node = nodes.get(ni);
            if (!node.has("mesh")) continue;
            int mi = node.getInt("mesh");
            JsonValue mesh = json.get("meshes").get(mi);
            JsonValue prims = mesh.get("primitives");
            Matrix4[] skinMats = node.has("skin") ? skinMatrices(glb, node.getInt("skin"), global) : null;
            for (int pi = 0; pi < prims.size; pi++) {
                JsonValue prim = prims.get(pi);
                if (prim.getInt("mode", 4) != 4) continue;
                JsonValue attrs = prim.get("attributes");
                if (!attrs.has("POSITION")) continue;
                float[] p = glb.readFloats(attrs.getInt("POSITION"));
                float[] n = attrs.has("NORMAL") ? glb.readFloats(attrs.getInt("NORMAL")) : null;
                int vc = p.length / 3;
                int[] idx;
                if (prim.has("indices")) idx = glb.readInts(prim.getInt("indices"));
                else {
                    idx = new int[vc];
                    for (int i = 0; i < vc; i++) idx[i] = i;
                }
                Part part = new Part();
                part.node = ni;
                part.mesh = mi;
                part.prim = pi;
                part.name = node.getString("name", mesh.getString("name", "mesh" + mi));
                part.vertexOffset = total;
                part.vertexCount = vc;
                part.localIndices = idx;
                part.linear = new float[vc * 9];

                float[] wp = new float[vc * 3], wn = new float[vc * 3];
                Matrix4 vm = new Matrix4();
                Vector3 t = new Vector3();
                float[] js = null, ws = null;
                if (skinMats != null && attrs.has("JOINTS_0") && attrs.has("WEIGHTS_0")) {
                    js = glb.readFloats(attrs.getInt("JOINTS_0"));
                    ws = glb.readFloats(attrs.getInt("WEIGHTS_0"));
                }
                float detSum = 0;
                for (int v = 0; v < vc; v++) {
                    if (js != null) {
                        float[] acc = new float[16];
                        float wsum = 0;
                        for (int k = 0; k < 4; k++) {
                            float w = ws[v * 4 + k];
                            if (w <= 0) continue;
                            float[] sm = skinMats[(int) js[v * 4 + k]].val;
                            for (int e = 0; e < 16; e++) acc[e] += w * sm[e];
                            wsum += w;
                        }
                        if (wsum <= 0) vm.set(global[ni]);
                        else {
                            for (int e = 0; e < 16; e++) acc[e] /= wsum;
                            vm.set(acc);
                        }
                    } else vm.set(global[ni]);
                    vm.mulLeft(orient);
                    t.set(p[v * 3], p[v * 3 + 1], p[v * 3 + 2]).mul(vm);
                    wp[v * 3] = t.x;
                    wp[v * 3 + 1] = t.y;
                    wp[v * 3 + 2] = t.z;
                    Matrix3 lin = new Matrix3().set(vm);
                    System.arraycopy(lin.val, 0, part.linear, v * 9, 9);
                    detSum += lin.det();
                    if (n != null) {
                        Matrix3 nm = new Matrix3(lin).inv().transpose();
                        t.set(n[v * 3], n[v * 3 + 1], n[v * 3 + 2]).mul(nm).nor();
                        wn[v * 3] = t.x;
                        wn[v * 3 + 1] = t.y;
                        wn[v * 3 + 2] = t.z;
                    }
                }
                part.flip = detSum < 0;
                pPos.add(wp);
                pNrm.add(wn);
                m.parts.add(part);
                total += vc;
            }
        }
        if (total == 0) throw new RigException("No triangle meshes found in the file");
        m.vertexCount = total;
        m.pos = new float[total * 3];
        m.nrm = new float[total * 3];
        IntArray tri = new IntArray();
        for (int i = 0; i < m.parts.size; i++) {
            Part part = m.parts.get(i);
            System.arraycopy(pPos.get(i), 0, m.pos, part.vertexOffset * 3, part.vertexCount * 3);
            System.arraycopy(pNrm.get(i), 0, m.nrm, part.vertexOffset * 3, part.vertexCount * 3);
            int[] li = part.localIndices;
            for (int k = 0; k + 2 < li.length; k += 3) {
                int a = li[k], b = li[k + 1], c = li[k + 2];
                if (part.flip) {
                    int s = b;
                    b = c;
                    c = s;
                }
                tri.add(a + part.vertexOffset, b + part.vertexOffset, c + part.vertexOffset);
            }
        }
        m.tris = tri.toArray();
        m.triCount = m.tris.length / 3;
        m.bounds.inf();
        Vector3 t = new Vector3();
        for (int v = 0; v < total; v++) m.bounds.ext(t.set(m.pos[v * 3], m.pos[v * 3 + 1], m.pos[v * 3 + 2]));
        m.buildTopology();
        return m;
    }

    private static void walk(JsonValue nodes, int ni, Matrix4 parent, Matrix4[] global, IntArray order) {
        if (global[ni] != null) return; // guard against cycles / shared children
        JsonValue n = nodes.get(ni);
        Matrix4 local = new Matrix4();
        if (n.has("matrix")) local.set(n.get("matrix").asFloatArray());
        else {
            Vector3 tr = new Vector3(), sc = new Vector3(1, 1, 1);
            Quaternion q = new Quaternion();
            if (n.has("translation")) {
                float[] a = n.get("translation").asFloatArray();
                tr.set(a[0], a[1], a[2]);
            }
            if (n.has("rotation")) {
                float[] a = n.get("rotation").asFloatArray();
                q.set(a[0], a[1], a[2], a[3]);
            }
            if (n.has("scale")) {
                float[] a = n.get("scale").asFloatArray();
                sc.set(a[0], a[1], a[2]);
            }
            local.set(tr, q, sc);
        }
        global[ni] = new Matrix4(parent).mul(local);
        order.add(ni);
        JsonValue ch = n.get("children");
        if (ch != null) for (JsonValue c = ch.child; c != null; c = c.next) walk(nodes, c.asInt(), global[ni], global, order);
    }

    private static Matrix4[] skinMatrices(GlbFile glb, int skinIdx, Matrix4[] global) {
        JsonValue skin = glb.json.get("skins").get(skinIdx);
        int[] joints = skin.get("joints").asIntArray();
        float[] ibm = skin.has("inverseBindMatrices") ? glb.readFloats(skin.getInt("inverseBindMatrices")) : null;
        Matrix4[] out = new Matrix4[joints.length];
        for (int j = 0; j < joints.length; j++) {
            Matrix4 g = global[joints[j]] != null ? global[joints[j]] : new Matrix4();
            Matrix4 inv = new Matrix4();
            if (ibm != null) {
                float[] v = new float[16];
                System.arraycopy(ibm, j * 16, v, 0, 16);
                inv.set(v);
            }
            out[j] = new Matrix4(g).mul(inv);
        }
        return out;
    }

    // ------------------------------------------------------------------ topology

    private void buildTopology() {
        float diag = bounds.getDimensions(new Vector3()).len();
        float eps = Math.max(diag * 1e-5f, 1e-7f);
        HashMap<Long, Integer> cells = new HashMap<>();
        weld = new int[vertexCount];
        IntArray firsts = new IntArray();
        for (int v = 0; v < vertexCount; v++) {
            long kx = Math.round(pos[v * 3] / eps), ky = Math.round(pos[v * 3 + 1] / eps), kz = Math.round(pos[v * 3 + 2] / eps);
            long key = (kx * 73856093L) ^ (ky * 19349663L) ^ (kz * 83492791L);
            Integer id = cells.get(key);
            if (id != null) {
                int f = firsts.get(id);
                if (Math.abs(pos[f * 3] - pos[v * 3]) > eps * 2 || Math.abs(pos[f * 3 + 1] - pos[v * 3 + 1]) > eps * 2
                    || Math.abs(pos[f * 3 + 2] - pos[v * 3 + 2]) > eps * 2) id = null; // hash collision
            }
            if (id == null) {
                id = firsts.size;
                firsts.add(v);
                cells.put(key, id);
            }
            weld[v] = id;
        }
        weldedCount = firsts.size;
        wpos = new float[weldedCount * 3];
        for (int i = 0; i < weldedCount; i++) System.arraycopy(pos, firsts.get(i) * 3, wpos, i * 3, 3);

        // adjacency (CSR) without duplicates
        int[] deg = new int[weldedCount];
        java.util.HashSet<Long> edges = new java.util.HashSet<>();
        for (int t = 0; t < triCount; t++) {
            for (int e = 0; e < 3; e++) {
                int a = weld[tris[t * 3 + e]], b = weld[tris[t * 3 + (e + 1) % 3]];
                if (a == b) continue;
                long key = a < b ? ((long) a << 32) | b : ((long) b << 32) | a;
                if (edges.add(key)) {
                    deg[a]++;
                    deg[b]++;
                }
            }
        }
        adjStart = new int[weldedCount + 1];
        for (int i = 0; i < weldedCount; i++) adjStart[i + 1] = adjStart[i] + deg[i];
        adj = new int[adjStart[weldedCount]];
        int[] fill = new int[weldedCount];
        for (long key : edges) {
            int a = (int) (key >>> 32), b = (int) key;
            adj[adjStart[a] + fill[a]++] = b;
            adj[adjStart[b] + fill[b]++] = a;
        }

        // islands
        island = new int[weldedCount];
        java.util.Arrays.fill(island, -1);
        IntArray stack = new IntArray();
        IntArray sizes = new IntArray();
        for (int s = 0; s < weldedCount; s++) {
            if (island[s] >= 0) continue;
            int id = sizes.size;
            int size = 0;
            stack.add(s);
            island[s] = id;
            while (stack.size > 0) {
                int v = stack.pop();
                size++;
                for (int k = adjStart[v]; k < adjStart[v + 1]; k++) {
                    int w = adj[k];
                    if (island[w] < 0) {
                        island[w] = id;
                        stack.add(w);
                    }
                }
            }
            sizes.add(size);
        }
        islandCount = sizes.size;
        islandSize = sizes.toArray();
    }

    public float height() {
        return bounds.getHeight();
    }

    /**
     * Middle of the mesh volume along the Z axis at (x, y): the midpoint between where a ray from
     * the front enters and leaves the body. Falls back to the bounds centre when nothing is hit.
     */
    public float depthCenter(float x, float y) {
        com.badlogic.gdx.utils.FloatArray hits = new com.badlogic.gdx.utils.FloatArray();
        for (int t = 0; t < triCount; t++) {
            int a = tris[t * 3] * 3, b = tris[t * 3 + 1] * 3, c = tris[t * 3 + 2] * 3;
            float ax = pos[a], ay = pos[a + 1], bx = pos[b], by = pos[b + 1], cx = pos[c], cy = pos[c + 1];
            float d = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy);
            if (Math.abs(d) < 1e-12f) continue;
            float l1 = ((by - cy) * (x - cx) + (cx - bx) * (y - cy)) / d;
            float l2 = ((cy - ay) * (x - cx) + (ax - cx) * (y - cy)) / d;
            float l3 = 1 - l1 - l2;
            if (l1 < 0 || l2 < 0 || l3 < 0) continue;
            hits.add(l1 * pos[a + 2] + l2 * pos[b + 2] + l3 * pos[c + 2]);
        }
        if (hits.size < 2) return bounds.getCenterZ();
        hits.sort();
        // from the front (largest z): entry = largest, exit = next distinct below it
        float entry = hits.get(hits.size - 1);
        float exit = entry;
        float minGap = height() * 0.004f;
        for (int i = hits.size - 2; i >= 0; i--) {
            if (entry - hits.get(i) > minGap) {
                exit = hits.get(i);
                break;
            }
        }
        return (entry + exit) * 0.5f;
    }
}
