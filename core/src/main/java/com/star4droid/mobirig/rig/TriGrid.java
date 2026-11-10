package com.star4droid.mobirig.rig;

import com.badlogic.gdx.utils.IntArray;

/** Uniform grid over the welded triangles for fast segment-vs-surface tests (line of sight). */
public class TriGrid {
    private final float[] p;
    private final int[] tri; // welded triangle indices
    private final int nx, ny, nz;
    private final float cs, ox, oy, oz;
    private final int[] cellStart, cellTris;
    private final int[] mailbox;
    private int stamp;

    /** @param skipIsland islands whose triangles never block (small separate pieces like eyes), may be null */
    public TriGrid(RigMesh mesh, int res, boolean[] skipIsland) {
        p = mesh.wpos;
        IntArray t = new IntArray();
        for (int i = 0; i < mesh.triCount; i++) {
            int a = mesh.weld[mesh.tris[i * 3]], b = mesh.weld[mesh.tris[i * 3 + 1]], c = mesh.weld[mesh.tris[i * 3 + 2]];
            if (skipIsland != null && skipIsland[mesh.island[a]]) continue;
            if (a != b && b != c && a != c) t.add(a, b, c);
        }
        tri = t.toArray();
        float dx = mesh.bounds.getWidth(), dy = mesh.bounds.getHeight(), dz = mesh.bounds.getDepth();
        float max = Math.max(dx, Math.max(dy, dz));
        cs = max / res;
        ox = mesh.bounds.min.x - cs;
        oy = mesh.bounds.min.y - cs;
        oz = mesh.bounds.min.z - cs;
        nx = (int) (dx / cs) + 3;
        ny = (int) (dy / cs) + 3;
        nz = (int) (dz / cs) + 3;
        int nt = tri.length / 3;
        int[] count = new int[nx * ny * nz + 1];
        for (int pass = 0; pass < 2; pass++) {
            int[] fill = pass == 1 ? new int[nx * ny * nz] : null;
            for (int i = 0; i < nt; i++) {
                int a = tri[i * 3] * 3, b = tri[i * 3 + 1] * 3, c = tri[i * 3 + 2] * 3;
                int x0 = cx(Math.min(p[a], Math.min(p[b], p[c]))), x1 = cx(Math.max(p[a], Math.max(p[b], p[c])));
                int y0 = cy(Math.min(p[a + 1], Math.min(p[b + 1], p[c + 1]))), y1 = cy(Math.max(p[a + 1], Math.max(p[b + 1], p[c + 1])));
                int z0 = cz(Math.min(p[a + 2], Math.min(p[b + 2], p[c + 2]))), z1 = cz(Math.max(p[a + 2], Math.max(p[b + 2], p[c + 2])));
                for (int z = z0; z <= z1; z++)
                    for (int y = y0; y <= y1; y++)
                        for (int x = x0; x <= x1; x++) {
                            int cell = x + nx * (y + ny * z);
                            if (pass == 0) count[cell + 1]++;
                            else cellTrisTmp[cellStartTmp[cell] + fill[cell]++] = i;
                        }
            }
            if (pass == 0) {
                for (int i = 0; i < nx * ny * nz; i++) count[i + 1] += count[i];
                cellStartTmp = count;
                cellTrisTmp = new int[count[nx * ny * nz]];
            }
        }
        cellStart = cellStartTmp;
        cellTris = cellTrisTmp;
        mailbox = new int[nt];
    }

    private int[] cellStartTmp, cellTrisTmp;

    private int cx(float v) { return clamp((int) ((v - ox) / cs), nx); }
    private int cy(float v) { return clamp((int) ((v - oy) / cs), ny); }
    private int cz(float v) { return clamp((int) ((v - oz) / cs), nz); }

    private static int clamp(int v, int n) {
        return v < 0 ? 0 : (v >= n ? n - 1 : v);
    }

    /**
     * True if the segment from welded vertex v to point (bx,by,bz) crosses the surface,
     * ignoring triangles that touch v itself.
     */
    public boolean blocked(int v, float bx, float by, float bz) {
        float ax = p[v * 3], ay = p[v * 3 + 1], az = p[v * 3 + 2];
        float dx = bx - ax, dy = by - ay, dz = bz - az;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-9f) return false;
        stamp++;
        if (stamp == Integer.MAX_VALUE) {
            java.util.Arrays.fill(mailbox, 0);
            stamp = 1;
        }
        // march through cells in steps of half a cell (simple and robust)
        int steps = (int) Math.ceil(len / (cs * 0.5f)) + 1;
        int lastCell = -1;
        for (int s = 0; s <= steps; s++) {
            float t = s / (float) steps;
            int cell = cx(ax + dx * t) + nx * (cy(ay + dy * t) + ny * cz(az + dz * t));
            if (cell == lastCell) continue;
            lastCell = cell;
            for (int k = cellStart[cell]; k < cellStart[cell + 1]; k++) {
                int ti = cellTris[k];
                if (mailbox[ti] == stamp) continue;
                mailbox[ti] = stamp;
                int a = tri[ti * 3], b = tri[ti * 3 + 1], c = tri[ti * 3 + 2];
                if (a == v || b == v || c == v) continue;
                if (hit(ax, ay, az, dx, dy, dz, a, b, c)) return true;
            }
        }
        return false;
    }

    /** Möller–Trumbore, segment parameter in (0.001, 0.999). */
    private boolean hit(float ox, float oy, float oz, float dx, float dy, float dz, int a, int b, int c) {
        float e1x = p[b * 3] - p[a * 3], e1y = p[b * 3 + 1] - p[a * 3 + 1], e1z = p[b * 3 + 2] - p[a * 3 + 2];
        float e2x = p[c * 3] - p[a * 3], e2y = p[c * 3 + 1] - p[a * 3 + 1], e2z = p[c * 3 + 2] - p[a * 3 + 2];
        float px = dy * e2z - dz * e2y, py = dz * e2x - dx * e2z, pz = dx * e2y - dy * e2x;
        float det = e1x * px + e1y * py + e1z * pz;
        if (Math.abs(det) < 1e-14f) return false;
        float inv = 1f / det;
        float tx = ox - p[a * 3], ty = oy - p[a * 3 + 1], tz = oz - p[a * 3 + 2];
        float u = (tx * px + ty * py + tz * pz) * inv;
        if (u < 0 || u > 1) return false;
        float qx = ty * e1z - tz * e1y, qy = tz * e1x - tx * e1z, qz = tx * e1y - ty * e1x;
        float w = (dx * qx + dy * qy + dz * qz) * inv;
        if (w < 0 || u + w > 1) return false;
        float t = (e2x * qx + e2y * qy + e2z * qz) * inv;
        return t > 0.001f && t < 0.999f;
    }
}
