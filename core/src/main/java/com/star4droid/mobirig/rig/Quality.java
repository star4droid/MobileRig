package com.star4droid.mobirig.rig;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Quaternion;
import com.badlogic.gdx.utils.Array;

/**
 * Measures how the skin deforms in the test poses (CPU linear blend skinning, like the GPU):
 * stretched/squashed edges, collapsed or flipped triangles, weights that leak between
 * unrelated body parts, and unweighted vertices.
 */
public class Quality {
    public static class PoseScore {
        public String pose;
        public float badEdgePct;       // edges stretched > 1.5x or squashed < 0.6x
        public float collapsedTriPct;  // triangles whose area drops below 25% or that flip
        public float maxStretch;
        public float volumeChangePct;
    }

    public final Array<PoseScore> poses = new Array<>();
    public int bleedVertices;
    public int unweightedVertices;
    public float bleedPct;
    public final java.util.HashMap<String, Integer> bleedPairs = new java.util.HashMap<>();
    public final java.util.HashMap<String, float[]> bleedWhere = new java.util.HashMap<>();
    public int vertexCount;

    public static Quality measure(RigMesh mesh, Skeleton sk, int[] joints, float[] weights) {
        Quality q = new Quality();
        q.vertexCount = mesh.vertexCount;
        // weight hygiene
        for (int v = 0; v < mesh.vertexCount; v++) {
            float sum = 0;
            for (int k = 0; k < 4; k++) sum += weights[v * 4 + k];
            if (sum < 0.99f) q.unweightedVertices++;
            boolean bleed = false;
            for (int a = 0; a < 4 && !bleed; a++) {
                if (weights[v * 4 + a] < 0.1f) continue;
                for (int b = a + 1; b < 4; b++) {
                    if (weights[v * 4 + b] < 0.1f) continue;
                    int ja = joints[v * 4 + a], jb = joints[v * 4 + b];
                    int sa = sk.joints.get(ja).side, sb = sk.joints.get(jb).side;
                    boolean midline = Math.abs(mesh.pos[v * 3]) < 0.03f * mesh.height();
                    if ((sa != 0 && sb != 0 && sa != sb && !midline) || sk.hops(ja, jb) > 3) {
                        bleed = true;
                        String key = sk.joints.get(Math.min(ja, jb)).name + "+" + sk.joints.get(Math.max(ja, jb)).name;
                        q.bleedPairs.put(key, q.bleedPairs.getOrDefault(key, 0) + 1);
                        float[] acc = q.bleedWhere.computeIfAbsent(key, kk -> new float[4]);
                        acc[0] += mesh.pos[v * 3]; acc[1] += mesh.pos[v * 3 + 1]; acc[2] += mesh.pos[v * 3 + 2]; acc[3]++;
                        break;
                    }
                }
            }
            if (bleed) q.bleedVertices++;
        }
        q.bleedPct = 100f * q.bleedVertices / Math.max(1, mesh.vertexCount);

        // inverse bind matrices
        int nj = sk.joints.size;
        Matrix4[] ibm = new Matrix4[nj];
        for (int j = 0; j < nj; j++) ibm[j] = sk.worldMatrix(j, new Matrix4()).inv();
        float restVol = volume(mesh.pos, mesh.tris);
        for (Poses.Pose pose : Poses.all(sk)) {
            if (pose.name.equals("Rest")) continue;
            Quaternion[] local = Poses.localRotations(sk, pose);
            Matrix4[] g = Poses.world(sk, local);
            float[][] skin = new float[nj][];
            for (int j = 0; j < nj; j++) skin[j] = new Matrix4(g[j]).mul(ibm[j]).val;
            float[] posed = deform(mesh, skin, joints, weights);
            q.poses.add(score(mesh, posed, pose.name, restVol));
        }
        return q;
    }

    static float[] deform(RigMesh mesh, float[][] skin, int[] joints, float[] weights) {
        float[] out = new float[mesh.vertexCount * 3];
        for (int v = 0; v < mesh.vertexCount; v++) {
            float x = mesh.pos[v * 3], y = mesh.pos[v * 3 + 1], z = mesh.pos[v * 3 + 2];
            float ox = 0, oy = 0, oz = 0;
            for (int k = 0; k < 4; k++) {
                float w = weights[v * 4 + k];
                if (w <= 0) continue;
                float[] m = skin[joints[v * 4 + k]];
                ox += w * (m[0] * x + m[4] * y + m[8] * z + m[12]);
                oy += w * (m[1] * x + m[5] * y + m[9] * z + m[13]);
                oz += w * (m[2] * x + m[6] * y + m[10] * z + m[14]);
            }
            out[v * 3] = ox;
            out[v * 3 + 1] = oy;
            out[v * 3 + 2] = oz;
        }
        return out;
    }

    private static PoseScore score(RigMesh mesh, float[] posed, String name, float restVol) {
        PoseScore s = new PoseScore();
        s.pose = name;
        int bad = 0, edges = 0;
        float maxS = 1;
        int[] t = mesh.tris;
        int collapsed = 0;
        float[] r = mesh.pos;
        for (int i = 0; i < mesh.triCount; i++) {
            int a = t[i * 3], b = t[i * 3 + 1], c = t[i * 3 + 2];
            for (int e = 0; e < 3; e++) {
                int u = t[i * 3 + e], v = t[i * 3 + (e + 1) % 3];
                if (u > v) continue; // count each shared edge roughly once
                float l0 = len(r, u, v), l1 = len(posed, u, v);
                if (l0 < 1e-7f) continue;
                float ratio = l1 / l0;
                edges++;
                if (ratio > 1.5f || ratio < 0.6f) bad++;
                maxS = Math.max(maxS, Math.max(ratio, 1f / Math.max(ratio, 1e-4f)));
            }
            float[] n0 = normal(r, a, b, c), n1 = normal(posed, a, b, c);
            float a0 = n0[3], a1 = n1[3];
            if (a0 < 1e-10f) continue;
            // flipped relative to the rest normal rotated along: compare against best rigid fit is costly,
            // so use the area drop plus a strong flip test (normal turned more than 150 degrees)
            float dot = (n0[0] * n1[0] + n0[1] * n1[1] + n0[2] * n1[2]);
            if (a1 / a0 < 0.25f || dot < -0.866f) collapsed++;
        }
        s.badEdgePct = 100f * bad / Math.max(1, edges);
        s.collapsedTriPct = 100f * collapsed / Math.max(1, mesh.triCount);
        s.maxStretch = maxS;
        s.volumeChangePct = 100f * (volume(posed, t) - restVol) / Math.max(Math.abs(restVol), 1e-9f);
        return s;
    }

    private static float len(float[] p, int a, int b) {
        float dx = p[a * 3] - p[b * 3], dy = p[a * 3 + 1] - p[b * 3 + 1], dz = p[a * 3 + 2] - p[b * 3 + 2];
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static float[] normal(float[] p, int a, int b, int c) {
        float ux = p[b * 3] - p[a * 3], uy = p[b * 3 + 1] - p[a * 3 + 1], uz = p[b * 3 + 2] - p[a * 3 + 2];
        float vx = p[c * 3] - p[a * 3], vy = p[c * 3 + 1] - p[a * 3 + 1], vz = p[c * 3 + 2] - p[a * 3 + 2];
        float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
        float l = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (l < 1e-12f) return new float[]{0, 0, 0, 0};
        return new float[]{nx / l, ny / l, nz / l, l * 0.5f};
    }

    private static float volume(float[] p, int[] t) {
        double v = 0;
        for (int i = 0; i < t.length; i += 3) {
            int a = t[i] * 3, b = t[i + 1] * 3, c = t[i + 2] * 3;
            v += p[a] * (p[b + 1] * p[c + 2] - p[b + 2] * p[c + 1])
                - p[a + 1] * (p[b] * p[c + 2] - p[b + 2] * p[c])
                + p[a + 2] * (p[b] * p[c + 1] - p[b + 1] * p[c]);
        }
        return (float) (v / 6.0);
    }

    public String summary() {
        StringBuilder sb = new StringBuilder();
        float worstBad = 0, worstCol = 0;
        String worstPose = "-";
        for (PoseScore p : poses) {
            if (p.collapsedTriPct + p.badEdgePct > worstBad + worstCol) {
                worstBad = p.badEdgePct;
                worstCol = p.collapsedTriPct;
                worstPose = p.pose;
            }
        }
        sb.append(String.format("Weights: %d unweighted, %.2f%% leaking between body parts. ", unweightedVertices, bleedPct));
        sb.append(String.format("Worst pose: %s (%.2f%% bad edges, %.2f%% collapsed triangles).", worstPose, worstBad, worstCol));
        return sb.toString();
    }
}
