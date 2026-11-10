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

    /** First guess from the mesh shape; the user then drags the markers into place. */
    public static Markers guess(RigMesh mesh) {
        Markers m = new Markers();
        float h = mesh.height(), y0 = mesh.bounds.min.y;
        Slicer sl = new Slicer(mesh);
        // torso width a bit below the shoulders
        Array<Slicer.Loop> chest = sl.section(new Vector3(0, y0 + 0.70f * h, 0), Vector3.Y);
        Slicer.Loop torso = sl.containing(chest, new Vector3(0, y0 + 0.70f * h, mesh.depthCenter(0, y0 + 0.70f * h)), h * 0.05f);
        float shoulderX = torso != null ? Math.max(torso.max.x, -torso.min.x) * 0.92f : 0.11f * h;
        shoulderX = Math.min(shoulderX, 0.16f * h);
        m.get(Kind.CHIN).set(0, y0 + 0.865f * h, 0);
        m.get(Kind.NECK).set(0, y0 + 0.82f * h, 0);
        m.get(Kind.SHOULDER).set(shoulderX, y0 + 0.80f * h, 0);
        m.get(Kind.PELVIS).set(0, y0 + 0.53f * h, 0);
        // hand tip: furthest vertex to the left above knee height
        float tipX = -Float.MAX_VALUE, tipY = y0 + 0.5f * h;
        for (int v = 0; v < mesh.vertexCount; v++) {
            float x = mesh.pos[v * 3], y = mesh.pos[v * 3 + 1];
            if (y > y0 + 0.3f * h && x > tipX) {
                tipX = x;
                tipY = y;
            }
        }
        Vector3 sh = m.get(Kind.SHOULDER);
        Vector3 dir = new Vector3(tipX - sh.x, tipY - sh.y, 0).nor();
        m.get(Kind.WRIST).set(tipX, tipY, 0).mulAdd(dir, -0.10f * h);
        // ankle: centre of the left leg just above the floor
        Array<Slicer.Loop> low = sl.section(new Vector3(0, y0 + 0.09f * h, 0), Vector3.Y);
        float ax = 0.07f * h;
        float best = Float.MAX_VALUE;
        for (Slicer.Loop l : low) {
            if (l.center.x > 0 && l.length > h * 0.03f && Math.abs(l.center.x - 0.07f * h) < best) {
                best = Math.abs(l.center.x - 0.07f * h);
                ax = l.center.x;
            }
        }
        m.get(Kind.ANKLE).set(ax, y0 + 0.05f * h, 0);
        m.constrainAll(mesh);
        return m;
    }
}
