package com.star4droid.mobirig.rig;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.IntArray;

import java.util.Arrays;

/**
 * Skin weights by geodesic distance through the body volume ("voxel binding").
 * The mesh is voxelised and filled, every bone is drawn into the grid, and distances are
 * spread from each bone through solid voxels only, so an arm never reaches the chest
 * through the air and the legs do not pull on each other. Works on open meshes and on
 * meshes made of many pieces, where classic heat weighting fails.
 */
public class VoxelBinder {
    public interface Progress {
        void update(String stage, float fraction);
    }

    public int resolution = 112;      // voxels along the largest side
    public float falloff = 4f;        // weight ~ 1 / distance^falloff
    public int smoothIterations = 1;
    /** Smooth the geodesic result with surface heat diffusion (Pinocchio-style, distances from the voxels). */
    public boolean heat = true;
    public int heatMaxIterations = 400;
    /** Only bind to bones visible from the vertex through the inside of the body. */
    public boolean visibility = true;
    /** Strength of the pull toward the nearest bone vs. diffusion (Pinocchio uses 1; higher = tighter joints). */
    public float heatSharpness = Float.parseFloat(System.getProperty("mobirig.sharp", "4"));
    /** Influences below this are dropped (keeps the export clean). */
    public float minWeight = 0.02f;
    /** Debug: print the solve for the welded vertex nearest to this point. */
    public static float[] debugPoint;
    public float smoothFactor = 0.5f;
    public int maxInfluences = 4;
    /** Separate pieces smaller than this share of the height move rigidly with one bone. */
    public float rigidIslandSize = 0.045f;

    // results, per mesh vertex
    public int[] joints;   // 4 per vertex (skeleton joint indices)
    public float[] weights; // 4 per vertex

    // grid
    private int nx, ny, nz;
    private float vs;
    private final Vector3 origin = new Vector3();
    private byte[] solid; // 0 outside, 1 surface, 2 interior

    public void bind(RigMesh mesh, Skeleton sk, Progress progress) {
        IntArray deformList = new IntArray();
        for (int j = 0; j < sk.joints.size; j++) {
            Skeleton.Joint jt = sk.joints.get(j);
            if (jt.deform && !jt.finger && !jt.name.endsWith("Roll")) deformList.add(j); // finger weights come from the finger detector
        }
        int[] deform = deformList.toArray();
        int nb = deform.length;

        if (progress != null) progress.update("Voxelising", 0.05f);
        voxelise(mesh);

        // distances per bone, through solid voxels
        int nv = nx * ny * nz;
        int nw = mesh.weldedCount;
        float[] dist = new float[nw * nb];
        int[] vox = new int[nw];
        float[] voxOff = new float[nw];
        for (int i = 0; i < nw; i++) {
            vox[i] = nearestSolid(mesh.wpos[i * 3], mesh.wpos[i * 3 + 1], mesh.wpos[i * 3 + 2]);
            if (vox[i] >= 0) {
                int x = vox[i] % nx, y = (vox[i] / nx) % ny, z = vox[i] / (nx * ny);
                float cx = origin.x + (x + 0.5f) * vs, cy = origin.y + (y + 0.5f) * vs, cz = origin.z + (z + 0.5f) * vs;
                voxOff[i] = Vector3.dst(cx, cy, cz, mesh.wpos[i * 3], mesh.wpos[i * 3 + 1], mesh.wpos[i * 3 + 2]);
            }
        }
        float[] d = new float[nv];
        DistHeap heap = new DistHeap(nv);
        Vector3 a = new Vector3(), b = new Vector3();
        for (int bi = 0; bi < nb; bi++) {
            if (progress != null) progress.update("Bone distances", 0.15f + 0.6f * bi / nb);
            Skeleton.Joint jt = sk.joints.get(deform[bi]);
            a.set(jt.weightHead != null ? jt.weightHead : jt.pos);
            b.set(jt.tail.isZero() ? jt.pos : jt.tail);
            geodesic(a, b, d, heap);
            for (int i = 0; i < nw; i++) {
                float v = vox[i] >= 0 ? d[vox[i]] : Float.MAX_VALUE;
                if (v == Float.MAX_VALUE) {
                    // vertex with no path through the volume: straight distance to the bone
                    v = segDist(mesh.wpos, i, a, b) * 1.5f + vs;
                } else v += voxOff[i];
                dist[i * nb + bi] = v;
            }
        }

        if (progress != null) progress.update("Weights", 0.8f);
        // side rule: a vertex clearly on one side never takes bones of the other side
        float h = mesh.height();
        float[] w = new float[nw * nb];
        for (int i = 0; i < nw; i++) {
            float x = mesh.wpos[i * 3];
            int vside = x > 0.01f * h ? 1 : (x < -0.01f * h ? -1 : 0);
            float dmin = Float.MAX_VALUE;
            for (int bi = 0; bi < nb; bi++) {
                int s = sk.joints.get(deform[bi]).side;
                if (vside != 0 && s != 0 && s != vside) continue;
                dmin = Math.min(dmin, dist[i * nb + bi]);
            }
            dmin = Math.max(dmin, vs * 0.5f);
            float sum = 0;
            for (int bi = 0; bi < nb; bi++) {
                int s = sk.joints.get(deform[bi]).side;
                if (vside != 0 && s != 0 && s != vside) continue;
                float r = dmin / Math.max(dist[i * nb + bi], 1e-6f);
                float v = (float) Math.pow(r, falloff);
                if (v < 1e-4f) v = 0;
                w[i * nb + bi] = v;
                sum += v;
            }
            if (sum > 0) for (int bi = 0; bi < nb; bi++) w[i * nb + bi] /= sum;
        }
        if (heat) {
            if (progress != null) progress.update("Heat diffusion", 0.82f);
            heatDiffuse(mesh, sk, deform, dist, w, nb, progress);
        }
        limit(w, nw, nb, maxInfluences);

        // smoothing over the surface
        float[] tmp = new float[w.length];
        for (int it = 0; it < smoothIterations; it++) {
            for (int i = 0; i < nw; i++) {
                int s = mesh.adjStart[i], e = mesh.adjStart[i + 1];
                for (int bi = 0; bi < nb; bi++) {
                    float avg = 0;
                    for (int k = s; k < e; k++) avg += w[mesh.adj[k] * nb + bi];
                    float own = w[i * nb + bi];
                    tmp[i * nb + bi] = e > s ? own + smoothFactor * (avg / (e - s) - own) : own;
                }
            }
            System.arraycopy(tmp, 0, w, 0, w.length);
            limit(w, nw, nb, maxInfluences);
        }

        // small separate pieces (eyes, teeth, buttons): rigid on their main bone
        float[] imin = new float[mesh.islandCount * 3], imax = new float[mesh.islandCount * 3];
        Arrays.fill(imin, Float.MAX_VALUE);
        Arrays.fill(imax, -Float.MAX_VALUE);
        for (int i = 0; i < nw; i++) {
            int isl = mesh.island[i];
            for (int c = 0; c < 3; c++) {
                imin[isl * 3 + c] = Math.min(imin[isl * 3 + c], mesh.wpos[i * 3 + c]);
                imax[isl * 3 + c] = Math.max(imax[isl * 3 + c], mesh.wpos[i * 3 + c]);
            }
        }
        float[] islandAcc = new float[mesh.islandCount * nb];
        for (int i = 0; i < nw; i++) for (int bi = 0; bi < nb; bi++) islandAcc[mesh.island[i] * nb + bi] += w[i * nb + bi];
        for (int i = 0; i < nw; i++) {
            int isl = mesh.island[i];
            float ext = Math.max(imax[isl * 3] - imin[isl * 3], Math.max(imax[isl * 3 + 1] - imin[isl * 3 + 1], imax[isl * 3 + 2] - imin[isl * 3 + 2]));
            if (ext > rigidIslandSize * h) continue;
            int best = 0;
            for (int bi = 1; bi < nb; bi++) if (islandAcc[isl * nb + bi] > islandAcc[isl * nb + best]) best = bi;
            for (int bi = 0; bi < nb; bi++) w[i * nb + bi] = bi == best ? 1f : 0f;
        }

        // pack top influences per original vertex
        joints = new int[mesh.vertexCount * 4];
        weights = new float[mesh.vertexCount * 4];
        int[] topI = new int[4];
        float[] topW = new float[4];
        for (int v = 0; v < mesh.vertexCount; v++) {
            int i = mesh.weld[v];
            Arrays.fill(topI, 0);
            Arrays.fill(topW, 0);
            for (int bi = 0; bi < nb; bi++) {
                float x = w[i * nb + bi];
                if (x <= topW[3]) continue;
                int k = 3;
                while (k > 0 && topW[k - 1] < x) {
                    topW[k] = topW[k - 1];
                    topI[k] = topI[k - 1];
                    k--;
                }
                topW[k] = x;
                topI[k] = bi;
            }
            float sum = topW[0] + topW[1] + topW[2] + topW[3];
            if (sum <= 0) {
                topW[0] = 1;
                topI[0] = 0;
                sum = 1;
            }
            for (int k = 0; k < 4; k++) {
                joints[v * 4 + k] = topW[k] > 0 ? deform[topI[k]] : 0;
                weights[v * 4 + k] = topW[k] / sum;
            }
        }
        if (progress != null) progress.update("Done", 1f);
    }

    /**
     * Solves (L + A·H) w_b = A·H·p_b for every bone: L is the cotangent Laplacian of the surface,
     * A the vertex areas, H = 1/d² with d the geodesic distance to the closest bone and p_b = 1 where
     * bone b is the closest. Gives smooth, natural transitions at joints. Distances come from the
     * voxel grid, so it never fails on open or multi-piece meshes the way classic bone heat does.
     */
    private void heatDiffuse(RigMesh mesh, Skeleton sk, int[] deform, float[] dist, float[] w, int nb, Progress progress) {
        int nw = mesh.weldedCount;
        float[] p = mesh.wpos;
        // welded triangles, cotangent weights and vertex areas
        java.util.HashMap<Long, Float> ew = new java.util.HashMap<>();
        float[] area = new float[nw];
        for (int t = 0; t < mesh.triCount; t++) {
            int a = mesh.weld[mesh.tris[t * 3]], b = mesh.weld[mesh.tris[t * 3 + 1]], c = mesh.weld[mesh.tris[t * 3 + 2]];
            if (a == b || b == c || a == c) continue;
            int[] v = {a, b, c};
            float ar = triArea(p, a, b, c);
            if (ar < 1e-14f) continue;
            for (int k = 0; k < 3; k++) {
                area[v[k]] += ar / 3f;
                int i = v[k], j = v[(k + 1) % 3], o = v[(k + 2) % 3];
                float cot = cot(p, o, i, j);
                long key = i < j ? ((long) i << 32) | j : ((long) j << 32) | i;
                Float old = ew.get(key);
                ew.put(key, (old == null ? 0f : old) + 0.5f * cot);
            }
        }
        int[] deg = new int[nw];
        for (long key : ew.keySet()) {
            deg[(int) (key >>> 32)]++;
            deg[(int) key]++;
        }
        int[] start = new int[nw + 1];
        for (int i = 0; i < nw; i++) start[i + 1] = start[i] + deg[i];
        int[] col = new int[start[nw]];
        float[] val = new float[start[nw]];
        int[] fill = new int[nw];
        float[] diag = new float[nw];
        float meanEdge = 0;
        for (java.util.Map.Entry<Long, Float> e : ew.entrySet()) {
            long key = e.getKey();
            int i = (int) (key >>> 32), j = (int) key;
            float c = Math.max(e.getValue(), 1e-4f); // clamp obtuse (negative) weights
            col[start[i] + fill[i]] = j;
            val[start[i] + fill[i]++] = -c;
            col[start[j] + fill[j]] = i;
            val[start[j] + fill[j]++] = -c;
            diag[i] += c;
            diag[j] += c;
            meanEdge += c;
        }
        // H and the nearest-bone indicator (side rule as in the geodesic pass)
        float h = mesh.height();
        float[] hd = new float[nw];
        float[] rhsScale = new float[nw];
        int[] nearest = new int[nw];
        int[] ties = new int[nw * 2];
        // bone segments for the line-of-sight test
        float[] seg = new float[nb * 6];
        for (int bi = 0; bi < nb; bi++) {
            Skeleton.Joint jt = sk.joints.get(deform[bi]);
            Vector3 a = jt.weightHead != null ? jt.weightHead : jt.pos;
            Vector3 b = jt.tail.isZero() ? jt.pos : jt.tail;
            seg[bi * 6] = a.x; seg[bi * 6 + 1] = a.y; seg[bi * 6 + 2] = a.z;
            seg[bi * 6 + 3] = b.x; seg[bi * 6 + 4] = b.y; seg[bi * 6 + 5] = b.z;
        }
        TriGrid grid = visibility ? new TriGrid(mesh, 96, smallIslands(mesh)) : null;
        int[] order = new int[nb];
        float[] od = new float[nb];
        int hidden = 0;
        java.util.HashMap<String, Integer> hiddenBy = new java.util.HashMap<>();
        java.util.ArrayList<String> hiddenSample = new java.util.ArrayList<>();
        for (int i = 0; i < nw; i++) {
            float x = p[i * 3];
            int vside = x > 0.01f * h ? 1 : (x < -0.01f * h ? -1 : 0);
            // candidate bones sorted by geodesic distance
            int nc = 0;
            for (int bi = 0; bi < nb; bi++) {
                int s = sk.joints.get(deform[bi]).side;
                if (vside != 0 && s != 0 && s != vside) continue;
                float dv = dist[i * nb + bi];
                int k = nc++;
                while (k > 0 && od[k - 1] > dv) {
                    od[k] = od[k - 1];
                    order[k] = order[k - 1];
                    k--;
                }
                od[k] = dv;
                order[k] = bi;
            }
            int best = order[0];
            float dmin = od[0];
            boolean seen = grid == null;
            if (grid != null) {
                // the closest bone the vertex can see through the inside of the body (like bone heat)
                for (int c = 0; c < Math.min(nc, 5); c++) {
                    int bi = order[c];
                    float[] q = closest(seg, bi, p[i * 3], p[i * 3 + 1], p[i * 3 + 2]);
                    if (!grid.blocked(i, q[0], q[1], q[2])) {
                        best = bi;
                        dmin = od[c];
                        seen = true;
                        break;
                    }
                }
                if (!seen) {
                    hidden++;
                    if (debugPoint != null) {
                        String nm = sk.joints.get(deform[order[0]]).name;
                        hiddenBy.put(nm, hiddenBy.getOrDefault(nm, 0) + 1);
                        if (hiddenSample.size() < 6 && hidden % 300 == 0) hiddenSample.add(String.format("(%.3f,%.3f,%.3f)->%s", p[i * 3], p[i * 3 + 1], p[i * 3 + 2], nm));
                    }
                }
            }
            nearest[i] = best;
            float d = Math.max(dmin, vs * 0.5f);
            float hh = heatSharpness / (d * d);
            float a = Math.max(area[i], 1e-12f);
            hd[i] = a * hh;
            rhsScale[i] = a * hh;
        }
        float[] m = new float[nw];
        for (int i = 0; i < nw; i++) m[i] = diag[i] + hd[i];
        float[] x = new float[nw], r = new float[nw], z = new float[nw], d = new float[nw], q = new float[nw], b = new float[nw];
        float[] out = new float[nw * nb];
        for (int bi = 0; bi < nb; bi++) {
            if (progress != null) progress.update("Heat diffusion", 0.82f + 0.1f * bi / nb);
            boolean any = false;
            for (int i = 0; i < nw; i++) {
                b[i] = nearest[i] == bi ? rhsScale[i] : 0f;
                x[i] = w[i * nb + bi];
                if (b[i] > 0) any = true;
            }
            if (!any) continue;
            // conjugate gradient, Jacobi preconditioner, warm start from the geodesic weights
            mul(start, col, val, hd, diag, x, q);
            double bn = 0, rz = 0;
            for (int i = 0; i < nw; i++) {
                r[i] = b[i] - q[i];
                z[i] = r[i] / m[i];
                d[i] = z[i];
                rz += r[i] * z[i];
                bn += b[i] * (double) b[i];
            }
            double tol = 1e-8 * bn;
            for (int it = 0; it < heatMaxIterations; it++) {
                mul(start, col, val, hd, diag, d, q);
                double dq = 0;
                for (int i = 0; i < nw; i++) dq += d[i] * (double) q[i];
                if (dq <= 0) break;
                float alpha = (float) (rz / dq);
                double rr = 0;
                for (int i = 0; i < nw; i++) {
                    x[i] += alpha * d[i];
                    r[i] -= alpha * q[i];
                    rr += r[i] * (double) r[i];
                }
                if (rr < tol) break;
                double rzNew = 0;
                for (int i = 0; i < nw; i++) {
                    z[i] = r[i] / m[i];
                    rzNew += r[i] * z[i];
                }
                float beta = (float) (rzNew / rz);
                rz = rzNew;
                for (int i = 0; i < nw; i++) d[i] = z[i] + beta * d[i];
            }
            for (int i = 0; i < nw; i++) out[i * nb + bi] = Math.max(0f, x[i]);
        }
        if (debugPoint != null) {
            int dv = 0;
            float bd = Float.MAX_VALUE;
            for (int i = 0; i < nw; i++) {
                float dd = Vector3.dst2(p[i * 3], p[i * 3 + 1], p[i * 3 + 2], debugPoint[0], debugPoint[1], debugPoint[2]);
                if (dd < bd) { bd = dd; dv = i; }
            }
            System.out.printf("DEBUG vertex %d at (%.3f,%.3f,%.3f) nearest=%s H=%.3g area=%.3g diagL=%.3g%n", dv, p[dv * 3], p[dv * 3 + 1], p[dv * 3 + 2],
                sk.joints.get(deform[nearest[dv]]).name, hd[dv] / Math.max(area[dv], 1e-12f), area[dv], diag[dv]);
            for (int bi = 0; bi < nb; bi++) {
                if (out[dv * nb + bi] > 0.01f || dist[dv * nb + bi] < 0.2f)
                    System.out.printf("DEBUG   %-14s geo=%.3f heat=%.3f%n", sk.joints.get(deform[bi]).name, dist[dv * nb + bi], out[dv * nb + bi]);
            }
            System.out.println("DEBUG hidden vertices " + hidden + " of " + nw + " " + hiddenBy + " " + hiddenSample);
        }
        // never give a vertex to a bone on the other side
        for (int i = 0; i < nw; i++) {
            float xx = p[i * 3];
            int vside = xx > 0.01f * h ? 1 : (xx < -0.01f * h ? -1 : 0);
            float sum = 0;
            for (int bi = 0; bi < nb; bi++) {
                int s = sk.joints.get(deform[bi]).side;
                if (vside != 0 && s != 0 && s != vside) out[i * nb + bi] = 0;
                sum += out[i * nb + bi];
            }
            if (sum > 1e-6f) for (int bi = 0; bi < nb; bi++) w[i * nb + bi] = out[i * nb + bi] / sum;
            else for (int bi = 0; bi < nb; bi++) w[i * nb + bi] = bi == nearest[i] ? 1f : 0f;
        }
    }

    /** Separate pieces smaller than rigidIslandSize (eyes, teeth, buttons). */
    private boolean[] smallIslands(RigMesh mesh) {
        float[] mn = new float[mesh.islandCount * 3], mx = new float[mesh.islandCount * 3];
        Arrays.fill(mn, Float.MAX_VALUE);
        Arrays.fill(mx, -Float.MAX_VALUE);
        for (int i = 0; i < mesh.weldedCount; i++) {
            int isl = mesh.island[i];
            for (int c = 0; c < 3; c++) {
                mn[isl * 3 + c] = Math.min(mn[isl * 3 + c], mesh.wpos[i * 3 + c]);
                mx[isl * 3 + c] = Math.max(mx[isl * 3 + c], mesh.wpos[i * 3 + c]);
            }
        }
        boolean[] small = new boolean[mesh.islandCount];
        float lim = rigidIslandSize * mesh.height();
        for (int k = 0; k < mesh.islandCount; k++) {
            float ext = Math.max(mx[k * 3] - mn[k * 3], Math.max(mx[k * 3 + 1] - mn[k * 3 + 1], mx[k * 3 + 2] - mn[k * 3 + 2]));
            small[k] = ext <= lim;
        }
        return small;
    }

    private static float[] closest(float[] seg, int bi, float px, float py, float pz) {
        float ax = seg[bi * 6], ay = seg[bi * 6 + 1], az = seg[bi * 6 + 2];
        float abx = seg[bi * 6 + 3] - ax, aby = seg[bi * 6 + 4] - ay, abz = seg[bi * 6 + 5] - az;
        float l2 = abx * abx + aby * aby + abz * abz;
        float t = l2 > 0 ? ((px - ax) * abx + (py - ay) * aby + (pz - az) * abz) / l2 : 0;
        t = Math.max(0, Math.min(1, t));
        return new float[]{ax + abx * t, ay + aby * t, az + abz * t};
    }

    private static void mul(int[] start, int[] col, float[] val, float[] hd, float[] diag, float[] x, float[] out) {
        int n = diag.length;
        for (int i = 0; i < n; i++) {
            float s = (diag[i] + hd[i]) * x[i];
            for (int k = start[i]; k < start[i + 1]; k++) s += val[k] * x[col[k]];
            out[i] = s;
        }
    }

    private static float triArea(float[] p, int a, int b, int c) {
        float ux = p[b * 3] - p[a * 3], uy = p[b * 3 + 1] - p[a * 3 + 1], uz = p[b * 3 + 2] - p[a * 3 + 2];
        float vx = p[c * 3] - p[a * 3], vy = p[c * 3 + 1] - p[a * 3 + 1], vz = p[c * 3 + 2] - p[a * 3 + 2];
        float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
        return 0.5f * (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
    }

    /** Cotangent of the angle at vertex o in triangle (o, i, j). */
    private static float cot(float[] p, int o, int i, int j) {
        float ux = p[i * 3] - p[o * 3], uy = p[i * 3 + 1] - p[o * 3 + 1], uz = p[i * 3 + 2] - p[o * 3 + 2];
        float vx = p[j * 3] - p[o * 3], vy = p[j * 3 + 1] - p[o * 3 + 1], vz = p[j * 3 + 2] - p[o * 3 + 2];
        float dot = ux * vx + uy * vy + uz * vz;
        float cx = uy * vz - uz * vy, cy = uz * vx - ux * vz, cz = ux * vy - uy * vx;
        float cr = (float) Math.sqrt(cx * cx + cy * cy + cz * cz);
        return cr < 1e-12f ? 0f : dot / cr;
    }

    private void limit(float[] w, int nw, int nb, int max) {
        float[] tw = new float[max];
        for (int i = 0; i < nw; i++) {
            Arrays.fill(tw, 0);
            for (int bi = 0; bi < nb; bi++) {
                float x = w[i * nb + bi];
                if (x <= tw[max - 1]) continue;
                int k = max - 1;
                while (k > 0 && tw[k - 1] < x) {
                    tw[k] = tw[k - 1];
                    k--;
                }
                tw[k] = x;
            }
            float th = Math.max(tw[max - 1], minWeight);
            float sum = 0;
            int kept = 0;
            for (int bi = 0; bi < nb; bi++) {
                float x = w[i * nb + bi];
                if (x < th || (x == th && kept >= max)) w[i * nb + bi] = 0;
                else {
                    kept++;
                    sum += x;
                }
            }
            if (sum > 0) for (int bi = 0; bi < nb; bi++) w[i * nb + bi] /= sum;
        }
    }

    private static float segDist(float[] p, int i, Vector3 a, Vector3 b) {
        float px = p[i * 3], py = p[i * 3 + 1], pz = p[i * 3 + 2];
        float abx = b.x - a.x, aby = b.y - a.y, abz = b.z - a.z;
        float l2 = abx * abx + aby * aby + abz * abz;
        float t = l2 > 0 ? ((px - a.x) * abx + (py - a.y) * aby + (pz - a.z) * abz) / l2 : 0;
        t = Math.max(0, Math.min(1, t));
        float dx = px - (a.x + abx * t), dy = py - (a.y + aby * t), dz = pz - (a.z + abz * t);
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    // ------------------------------------------------------------------ voxels

    private void voxelise(RigMesh mesh) {
        Vector3 dim = mesh.bounds.getDimensions(new Vector3());
        float maxDim = Math.max(dim.x, Math.max(dim.y, dim.z));
        vs = maxDim / resolution;
        origin.set(mesh.bounds.min).sub(vs * 2);
        nx = (int) Math.ceil(dim.x / vs) + 5;
        ny = (int) Math.ceil(dim.y / vs) + 5;
        nz = (int) Math.ceil(dim.z / vs) + 5;
        solid = new byte[nx * ny * nz];
        float[] p = mesh.pos;
        // surface: sample every triangle densely
        for (int t = 0; t < mesh.triCount; t++) {
            int a = mesh.tris[t * 3] * 3, b = mesh.tris[t * 3 + 1] * 3, c = mesh.tris[t * 3 + 2] * 3;
            float lab = dist(p, a, b), lac = dist(p, a, c), lbc = dist(p, b, c);
            int n = (int) Math.ceil(Math.max(lab, Math.max(lac, lbc)) / (vs * 0.5f)) + 1;
            for (int i = 0; i <= n; i++) {
                float u = i / (float) n;
                for (int j = 0; j <= n - i; j++) {
                    float v = j / (float) n, wgt = 1 - u - v;
                    float x = p[a] * wgt + p[b] * u + p[c] * v;
                    float y = p[a + 1] * wgt + p[b + 1] * u + p[c + 1] * v;
                    float z = p[a + 2] * wgt + p[b + 2] * u + p[c + 2] * v;
                    int idx = cell(x, y, z);
                    if (idx >= 0) solid[idx] = 1;
                }
            }
        }
        // close small holes: flood the outside through a 1-voxel-dilated shell
        byte[] shell = Arrays.copyOf(solid, solid.length);
        for (int z = 1; z < nz - 1; z++)
            for (int y = 1; y < ny - 1; y++)
                for (int x = 1; x < nx - 1; x++) {
                    int i = x + nx * (y + ny * z);
                    if (solid[i] != 1) continue;
                    for (int k = 0; k < 6; k++) shell[i + off6(k)] = 1;
                }
        byte[] outside = new byte[solid.length];
        IntArray stack = new IntArray();
        stack.add(0);
        outside[0] = 1;
        while (stack.size > 0) {
            int i = stack.pop();
            int x = i % nx, y = (i / nx) % ny, z = i / (nx * ny);
            for (int k = 0; k < 6; k++) {
                int xx = x + (k == 0 ? 1 : k == 1 ? -1 : 0), yy = y + (k == 2 ? 1 : k == 3 ? -1 : 0), zz = z + (k == 4 ? 1 : k == 5 ? -1 : 0);
                if (xx < 0 || yy < 0 || zz < 0 || xx >= nx || yy >= ny || zz >= nz) continue;
                int j = xx + nx * (yy + ny * zz);
                if (outside[j] == 0 && shell[j] == 0) {
                    outside[j] = 1;
                    stack.add(j);
                }
            }
        }
        for (int i = 0; i < solid.length; i++) if (solid[i] == 0 && outside[i] == 0) solid[i] = 2;
    }

    private int off6(int k) {
        switch (k) {
            case 0: return 1;
            case 1: return -1;
            case 2: return nx;
            case 3: return -nx;
            case 4: return nx * ny;
            default: return -nx * ny;
        }
    }

    private static float dist(float[] p, int a, int b) {
        float dx = p[a] - p[b], dy = p[a + 1] - p[b + 1], dz = p[a + 2] - p[b + 2];
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private int cell(float x, float y, float z) {
        int ix = (int) ((x - origin.x) / vs), iy = (int) ((y - origin.y) / vs), iz = (int) ((z - origin.z) / vs);
        if (ix < 0 || iy < 0 || iz < 0 || ix >= nx || iy >= ny || iz >= nz) return -1;
        return ix + nx * (iy + ny * iz);
    }

    private int nearestSolid(float x, float y, float z) {
        int c = cell(x, y, z);
        if (c >= 0 && solid[c] != 0) return c;
        int ix = (int) ((x - origin.x) / vs), iy = (int) ((y - origin.y) / vs), iz = (int) ((z - origin.z) / vs);
        int best = -1;
        float bd = Float.MAX_VALUE;
        for (int r = 1; r <= 2; r++)
            for (int dz = -r; dz <= r; dz++)
                for (int dy = -r; dy <= r; dy++)
                    for (int dx = -r; dx <= r; dx++) {
                        int xx = ix + dx, yy = iy + dy, zz = iz + dz;
                        if (xx < 0 || yy < 0 || zz < 0 || xx >= nx || yy >= ny || zz >= nz) continue;
                        int j = xx + nx * (yy + ny * zz);
                        if (solid[j] == 0) continue;
                        float d = dx * dx + dy * dy + dz * dz;
                        if (d < bd) {
                            bd = d;
                            best = j;
                        }
                    }
        return best;
    }

    private static final float S2 = (float) Math.sqrt(2), S3 = (float) Math.sqrt(3);

    /** Multi-source Dijkstra from the voxels on segment a-b, through solid voxels (26-connected). */
    private void geodesic(Vector3 a, Vector3 b, float[] d, DistHeap heap) {
        Arrays.fill(d, Float.MAX_VALUE);
        heap.clear();
        float len = a.dst(b);
        int steps = Math.max(2, (int) Math.ceil(len / (vs * 0.5f)));
        int seeded = 0;
        Vector3 q = new Vector3();
        for (int s = 0; s <= steps; s++) {
            q.set(a).lerp(b, s / (float) steps);
            int c = cell(q.x, q.y, q.z);
            if (c >= 0 && solid[c] != 0 && d[c] > 0) {
                d[c] = 0;
                heap.push(c, 0);
                seeded++;
            }
        }
        if (seeded == 0) {
            // bone outside the volume: seed from the nearest solid voxel to its middle
            q.set(a).lerp(b, 0.5f);
            int c = nearestSolidFar(q);
            if (c < 0) return;
            d[c] = 0;
            heap.push(c, 0);
        }
        int plane = nx * ny;
        while (!heap.isEmpty()) {
            int i = heap.pop();
            float di = d[i];
            int x = i % nx, y = (i / nx) % ny, z = i / plane;
            for (int dz = -1; dz <= 1; dz++) {
                int zz = z + dz;
                if (zz < 0 || zz >= nz) continue;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = y + dy;
                    if (yy < 0 || yy >= ny) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx;
                        if (xx < 0 || xx >= nx || (dx == 0 && dy == 0 && dz == 0)) continue;
                        int j = xx + nx * (yy + ny * zz);
                        if (solid[j] == 0) continue;
                        int n = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                        float nd = di + vs * (n == 1 ? 1f : n == 2 ? S2 : S3);
                        if (nd < d[j]) {
                            d[j] = nd;
                            heap.push(j, nd);
                        }
                    }
                }
            }
        }
    }

    private int nearestSolidFar(Vector3 q) {
        int best = -1;
        float bd = Float.MAX_VALUE;
        for (int i = 0; i < solid.length; i++) {
            if (solid[i] == 0) continue;
            int x = i % nx, y = (i / nx) % ny, z = i / (nx * ny);
            float cx = origin.x + (x + 0.5f) * vs, cy = origin.y + (y + 0.5f) * vs, cz = origin.z + (z + 0.5f) * vs;
            float d = q.dst2(cx, cy, cz);
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        return best;
    }

    /** Binary min-heap of voxel indices keyed by distance (lazy deletion). */
    static final class DistHeap {
        int[] idx;
        float[] key;
        int size;

        DistHeap(int cap) {
            idx = new int[Math.max(16, cap / 4)];
            key = new float[idx.length];
        }

        void clear() {
            size = 0;
        }

        boolean isEmpty() {
            return size == 0;
        }

        void push(int i, float k) {
            if (size == idx.length) {
                idx = Arrays.copyOf(idx, size * 2);
                key = Arrays.copyOf(key, size * 2);
            }
            int c = size++;
            while (c > 0) {
                int p = (c - 1) >> 1;
                if (key[p] <= k) break;
                idx[c] = idx[p];
                key[c] = key[p];
                c = p;
            }
            idx[c] = i;
            key[c] = k;
        }

        /** Pops the smallest entry; stale duplicates are fine (Dijkstra relaxes monotonically). */
        int pop() {
            int top = idx[0];
            int li = idx[--size];
            float lk = key[size];
            int c = 0;
            while (true) {
                int l = 2 * c + 1;
                if (l >= size) break;
                int r = l + 1;
                int m = (r < size && key[r] < key[l]) ? r : l;
                if (key[m] >= lk) break;
                idx[c] = idx[m];
                key[c] = key[m];
                c = m;
            }
            if (size > 0) {
                idx[c] = li;
                key[c] = lk;
            }
            return top;
        }
    }
}
