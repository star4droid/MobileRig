package com.star4droid.mobirig.rig;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;

/**
 * The six landmarks placed from the front view (same set as Rigger's Smart Markers).
 * Side markers are on the character's LEFT (+X, screen right in front view) and mirrored.
 * Stored in world space after orientation; z is filled with the volume centre.
 */
public class Markers {
    public enum Kind {
        CHIN("Chin", "bottom of the chin", true),
        NECK("Neck", "base of the neck", true),
        SHOULDER("Shoulder", "left shoulder joint (screen right)", false),
        WRIST("Wrist", "left wrist (screen right)", false),
        PELVIS("Pelvis", "middle of the hips / groin", true),
        ANKLE("Ankle", "left ankle (screen right)", false);

        public final String label, hint;
        public final boolean center;

        Kind(String label, String hint, boolean center) {
            this.label = label;
            this.hint = hint;
            this.center = center;
        }
    }

    public final Vector3[] p = new Vector3[Kind.values().length];

    public Markers() {
        for (int i = 0; i < p.length; i++) p[i] = new Vector3();
    }

    public Vector3 get(Kind k) {
        return p[k.ordinal()];
    }

    /** Keeps centre markers on x = 0 and side markers on the left (+X), then fills depth from the mesh. */
    public void constrain(RigMesh mesh, Kind k) {
        Vector3 v = get(k);
        if (k.center) v.x = 0;
        else v.x = Math.max(v.x, mesh.height() * 0.005f);
        v.z = mesh.depthCenter(v.x, v.y);
    }

    public void constrainAll(RigMesh mesh) {
        for (Kind k : Kind.values()) constrain(mesh, k);
    }

    /** What the last guess could not read from the shape (shown to the user). */
    public String guessNote = "";

    /**
     * Guess from the front silhouette: crotch = top of the gap between the legs, armpit = where the
     * arm joins the torso, neck = narrowest row under the head, wrist = narrowest part of the arm
     * behind the hand, ankle = narrowest part of the leg above the foot. Anything that cannot be
     * read (robes, joined legs, long hair over the neck) falls back to body proportions.
     */
    public static Markers guess(RigMesh mesh) {
        Markers m = new Markers();
        float h = mesh.height();
        final int R = 320;
        Silhouette s = new Silhouette(mesh, R);
        int[] w0 = new int[R];
        int top = 0;
        for (int j = 0; j < R; j++) {
            w0[j] = s.run0(j);
            for (int i = 0; i < s.w; i++) if (s.at(i, j)) { top = j; break; }
        }
        StringBuilder note = new StringBuilder();

        // ---- crotch
        int crotch = -1;
        boolean seenGap = false;
        for (int j = (int) (0.12f * R); j < (int) (0.70f * R); j++) {
            if (w0[j] == 0) seenGap = true;
            else if (seenGap) {
                boolean stays = true;
                for (int k = 1; k <= R / 40; k++) if (w0[Math.min(R - 1, j + k)] == 0) stays = false;
                if (stays) { crotch = j; break; }
            }
        }
        if (crotch < 0) {
            crotch = (int) (0.47f * R);
            note.append("legs not separated; ");
        }

        // ---- armpit: the centre run suddenly reaches out into the arm
        int armpit = -1;
        int[] buf = new int[R];
        for (int j = crotch + (int) (0.06f * R); j < (int) (0.93f * R); j++) {
            int n = 0;
            for (int k = j - (int) (0.10f * R); k <= j - (int) (0.015f * R); k++) if (k > crotch && w0[k] > 0) buf[n++] = w0[k];
            if (n < 3) continue;
            java.util.Arrays.sort(buf, 0, n);
            int ref = buf[n / 2];
            if (w0[j] > 1.4f * ref + 3 && w0[Math.min(R - 1, j + 2)] > 1.4f * ref + 3) { armpit = j; break; }
        }
        // ---- hand tip: furthest point to the side
        int tipI = 0;
        for (int j = Math.max(0, crotch - (int) (0.12f * R)); j < R; j++)
            for (int i = s.w - 1; i > tipI; i--) if (s.at(i, j)) { tipI = i; break; }
        float tj = 0; int tn = 0;
        for (int j = Math.max(0, crotch - (int) (0.12f * R)); j < R; j++)
            for (int i = Math.max(0, tipI - 1); i <= tipI; i++) if (s.at(i, j)) { tj += j; tn++; }
        tj = tn > 0 ? tj / tn : 0.5f * R;

        float shI, shJ; int armTop;
        if (armpit > 0) {
            int n = 0;
            for (int k = armpit - (int) (0.05f * R); k < armpit; k++) if (k > crotch && w0[k] > 0) buf[n++] = w0[k];
            java.util.Arrays.sort(buf, 0, n);
            int wT = n > 0 ? buf[n / 2] : w0[armpit - 1];
            int ic = Math.min(s.w - 1, Math.round(wT * 1.18f) + 2);
            int b = armpit;
            while (b < R && !s.at(ic, b)) b++;
            int t = b;
            while (t + 1 < R && s.at(ic, t + 1)) t++;
            // a big head can sit right on the arm: stop the arm where the outline starts widening again
            int limit = b + (int) (0.16f * R);
            if (t > limit) t = limit;
            armTop = t;
            float th = Math.max(1, t - b + 1);
            shJ = t - Math.min(0.5f * th, 0.05f * R) + 0.012f * R;
            shI = wT * 1.0f;
        } else {
            note.append("arms not separated from the body; ");
            shI = Math.min(0.16f * R, Math.max(0.09f * R, w0[(int) (0.70f * R)] * 0.92f));
            shJ = 0.80f * R;
            armTop = (int) (0.83f * R);
        }

        // ---- neck and chin
        int headMax = armTop, neck = -1;
        for (int j = armTop + Math.round(0.35f * (top - armTop)); j <= top; j++) if (w0[j] > w0[headMax] || headMax == armTop) headMax = j;
        int minW = Integer.MAX_VALUE;
        for (int j = armTop; j <= headMax; j++) if (w0[j] > 0 && w0[j] <= minW) { minW = w0[j]; neck = j; }
        // lowest row that is about as narrow (the narrow part can be long)
        int neckLow = neck;
        for (int j = neck; j >= armTop; j--) { if (w0[j] <= minW * 1.12f + 1) neckLow = j; else break; }
        int neckHigh = neck;
        for (int j = neck; j <= headMax; j++) { if (w0[j] <= minW * 1.12f + 1) neckHigh = j; else break; }
        float neckJ, chinJ;
        if (neck < 0 || w0[headMax] < minW * 1.12f || minW > 0.9f * shI) {
            note.append("neck hidden; ");
            neckJ = armTop + 0.10f * (top - armTop);
            chinJ = armTop + 0.28f * (top - armTop);
        } else {
            neckJ = neckLow + NECK_T * (neckHigh - neckLow) - 0.015f * R;
            chinJ = neckHigh + CHIN_T * (headMax - neckHigh);
        }
        if (neckJ < shJ) neckJ = shJ + 1;
        if (chinJ <= neckJ + 0.012f * R) chinJ = neckJ + 0.012f * R;

        // ---- wrist: walk from the tip toward the shoulder, measure the arm across
        float dx = shI - tipI, dy = shJ - tj;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        float wrI = tipI + dx * 0.24f, wrJ = tj + dy * 0.24f;
        if (len > 8) {
            dx /= len; dy /= len;
            float nx = -dy, ny = dx;
            int steps = (int) (len * 0.62f);
            float[] span = new float[steps], ci = new float[steps], cj = new float[steps];
            float px = tipI, py = tj;
            int gapMax = Math.max(2, (int) (0.012f * R)), reach = (int) (0.14f * R);
            for (int k = 0; k < steps; k++) {
                px += dx; py += dy;
                int lo = 0, hi = 0, gap = 0; boolean any = false;
                for (int q = 0; q <= reach; q++) {
                    if (s.at(Math.round(px + nx * q), Math.round(py + ny * q))) { hi = q; gap = 0; any = true; }
                    else if (++gap > gapMax && (any || q > gapMax * 2)) break;
                }
                gap = 0;
                for (int q = 0; q >= -reach; q--) {
                    if (s.at(Math.round(px + nx * q), Math.round(py + ny * q))) { lo = q; gap = 0; any = true; }
                    else if (++gap > gapMax && (any || q < -gapMax * 2)) break;
                }
                span[k] = any ? hi - lo + 1 : 0;
                float mid = (hi + lo) * 0.5f;
                // follow the arm, but gently (fingers pull the middle around)
                px += nx * mid * 0.5f; py += ny * mid * 0.5f;
                ci[k] = px; cj[k] = py;
            }
            int kMax = 0;
            int handEnd = Math.max(2, (int) (len * 0.30f));
            for (int k = 0; k < Math.min(steps, handEnd); k++) if (span[k] > span[kMax]) kMax = k;
            int kMin = -1;
            for (int k = kMax + 1; k < Math.min(steps, kMax + (int) (len * 0.30f)); k++)
                if (span[k] > 0 && (kMin < 0 || span[k] < span[kMin])) kMin = k;
            if (kMin > 0 && span[kMin] < 0.85f * span[kMax]) {
                int k = kMax + 1;
                while (k < kMin && span[k] > span[kMin] * 1.10f + 0.5f) k++;
                wrI = ci[k]; wrJ = cj[k];
            } else note.append("wrist not clear; ");
        }

        // ---- ankle: narrowest part of the leg above the foot
        int aLo = (int) (0.035f * R), aHi = (int) (0.105f * R), bestJ = -1;
        float bestW = Float.MAX_VALUE, bestC = 0.07f * R;
        float[] legC = new float[R]; int[] legW = new int[R];
        for (int j = aLo; j <= aHi; j++) {
            int bw = 0, bs = 0, i = 0;
            while (i < s.w) {
                if (!s.at(i, j)) { i++; continue; }
                int st = i;
                while (i < s.w && s.at(i, j)) i++;
                if (i - st > bw) { bw = i - st; bs = st; }
            }
            legW[j] = bw; legC[j] = bs + bw * 0.5f;
        }
        for (int j = aLo + 2; j <= aHi - 2; j++) {
            float avg = (legW[j - 2] + legW[j - 1] + legW[j] + legW[j + 1] + legW[j + 2]) / 5f;
            if (legW[j] > 0 && avg < bestW - 0.5f) { bestW = avg; bestJ = j; bestC = legC[j]; }
        }
        if (bestJ < 0) { bestJ = (int) (0.06f * R); note.append("ankle not clear; "); }

        m.get(Kind.CHIN).set(0, s.y(chinJ), 0);
        m.get(Kind.NECK).set(0, s.y(neckJ), 0);
        m.get(Kind.SHOULDER).set(s.x(shI) * SHOULDER_X, s.y(shJ), 0);
        m.get(Kind.WRIST).set(s.x(wrI), s.y(wrJ), 0);
        m.get(Kind.PELVIS).set(0, s.y(crotch) + PELVIS_UP * h, 0);
        m.get(Kind.ANKLE).set(s.x(bestC), s.y(bestJ - 0.015f * R), 0);
        m.constrainAll(mesh);
        m.guessNote = note.toString();
        return m;
    }

    public static float NECK_T = 0f, CHIN_T = 0.03f, SHOULDER_X = 1.06f, PELVIS_UP = 0.075f;
}
