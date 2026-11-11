package com.star4droid.mobirig.rig;

/**
 * Front-view silhouette of the character's left half (x >= 0) on a grid, used to find landmarks
 * from the actual shape (works for realistic and big-headed stylised bodies, T and A pose).
 */
class Silhouette {
    final int w, h;
    final float cs, y0;
    final boolean[] f;

    Silhouette(RigMesh mesh, int rows) {
        float hgt = mesh.height();
        h = rows;
        cs = hgt / rows;
        y0 = mesh.bounds.min.y;
        float maxX = Math.max(mesh.bounds.max.x, cs);
        w = (int) Math.ceil(maxX / cs) + 3;
        f = new boolean[w * h];
        float[] p = mesh.pos;
        for (int t = 0; t < mesh.triCount; t++) {
            int a = mesh.tris[t * 3] * 3, b = mesh.tris[t * 3 + 1] * 3, c = mesh.tris[t * 3 + 2] * 3;
            float ax = p[a] / cs, ay = (p[a + 1] - y0) / cs, bx = p[b] / cs, by = (p[b + 1] - y0) / cs, cx = p[c] / cs, cy = (p[c + 1] - y0) / cs;
            int i0 = (int) Math.floor(Math.min(ax, Math.min(bx, cx))), i1 = (int) Math.floor(Math.max(ax, Math.max(bx, cx)));
            int j0 = (int) Math.floor(Math.min(ay, Math.min(by, cy))), j1 = (int) Math.floor(Math.max(ay, Math.max(by, cy)));
            if (i1 < 0) continue;
            i0 = Math.max(i0, 0); i1 = Math.min(i1, w - 1); j0 = Math.max(j0, 0); j1 = Math.min(j1, h - 1);
            float den = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy);
            for (int j = j0; j <= j1; j++)
                for (int i = i0; i <= i1; i++) {
                    if (f[j * w + i]) continue;
                    if (i0 == i1 || j0 == j1 || Math.abs(den) < 1e-9f) { f[j * w + i] = true; continue; }
                    float px = i + 0.5f, py = j + 0.5f;
                    float l1 = ((by - cy) * (px - cx) + (cx - bx) * (py - cy)) / den;
                    float l2 = ((cy - ay) * (px - cx) + (ax - cx) * (py - cy)) / den;
                    float l3 = 1 - l1 - l2;
                    float e = -0.08f;
                    if (l1 >= e && l2 >= e && l3 >= e) f[j * w + i] = true;
                }
        }
    }

    boolean at(int i, int j) {
        return i >= 0 && j >= 0 && i < w && j < h && f[j * w + i];
    }

    /** Cells filled without a break from the centre line outwards (0 when the centre is empty). */
    int run0(int j) {
        if (!at(0, j) && !at(1, j)) return 0;
        int i = 0;
        while (i < w && (at(i, j) || at(i + 1, j))) i++;
        return i;
    }

    float y(float j) {
        return y0 + (j + 0.5f) * cs;
    }

    float x(float i) {
        return (i + 0.5f) * cs;
    }
}
