package com.star4droid.mobirig.rig;

/** Small helpers shared by everything that edits skin weights. */
public final class WeightOps {
    private WeightOps() {
    }

    /** Writes the 4 strongest of n influences to vertex v, normalised. */
    public static void put(int[] joints, float[] weights, int v, int[] ij, float[] iw, int n) {
        for (int a = 0; a < n; a++)
            for (int b = a + 1; b < n; b++)
                if (ij[a] == ij[b] && iw[b] > 0) {
                    iw[a] += iw[b];
                    iw[b] = 0;
                }
        for (int a = 0; a < n; a++)
            for (int b = a + 1; b < n; b++)
                if (iw[b] > iw[a]) {
                    float tw = iw[a]; iw[a] = iw[b]; iw[b] = tw;
                    int tj = ij[a]; ij[a] = ij[b]; ij[b] = tj;
                }
        float sum = 0;
        for (int k = 0; k < 4 && k < n; k++) if (iw[k] >= 0.01f) sum += iw[k];
        if (sum <= 0) return;
        for (int k = 0; k < 4; k++) {
            boolean keep = k < n && iw[k] >= 0.01f;
            joints[v * 4 + k] = keep ? ij[k] : 0;
            weights[v * 4 + k] = keep ? iw[k] / sum : 0;
        }
    }

    /** Moves part of the forearm weight onto the forearm roll bones (more toward the wrist). */
    public static void applyRoll(RigMesh mesh, Skeleton sk, int[] joints, float[] weights) {
        int[] ij = new int[5];
        float[] iw = new float[5];
        for (String pre : new String[]{"Left", "Right"}) {
            int roll = sk.index(pre + "ForeArmRoll"), fa = sk.index(pre + "ForeArm"), hand = sk.index(pre + "Hand");
            if (roll < 0 || fa < 0 || hand < 0) continue;
            com.badlogic.gdx.math.Vector3 a = sk.joints.get(fa).pos, b = sk.joints.get(hand).pos;
            float ax = b.x - a.x, ay = b.y - a.y, az = b.z - a.z;
            float l2 = ax * ax + ay * ay + az * az;
            if (l2 <= 0) continue;
            for (int v = 0; v < mesh.vertexCount; v++) {
                int slot = -1;
                for (int k = 0; k < 4; k++) if (joints[v * 4 + k] == fa && weights[v * 4 + k] > 0) slot = k;
                if (slot < 0) continue;
                float t = ((mesh.pos[v * 3] - a.x) * ax + (mesh.pos[v * 3 + 1] - a.y) * ay + (mesh.pos[v * 3 + 2] - a.z) * az) / l2;
                float f = (t - 0.2f) / 0.65f;
                f = f < 0 ? 0 : (f > 1 ? 1 : f);
                f = f * f * (3 - 2 * f);
                if (f <= 0.01f) continue;
                int n = 0;
                for (int k = 0; k < 4; k++) {
                    ij[n] = joints[v * 4 + k];
                    iw[n++] = k == slot ? weights[v * 4 + k] * (1 - f) : weights[v * 4 + k];
                }
                ij[n] = roll;
                iw[n++] = weights[v * 4 + slot] * f;
                put(joints, weights, v, ij, iw, n);
            }
        }
    }
}
