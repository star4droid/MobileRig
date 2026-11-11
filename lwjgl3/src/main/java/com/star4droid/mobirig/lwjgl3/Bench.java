package com.star4droid.mobirig.lwjgl3;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Quaternion;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.star4droid.mobirig.rig.GlbFile;
import com.star4droid.mobirig.rig.Markers;
import com.star4droid.mobirig.rig.Quality;
import com.star4droid.mobirig.rig.RigMesh;
import com.star4droid.mobirig.rig.RigPipeline;
import com.star4droid.mobirig.rig.Skeleton;

import java.io.File;
import java.nio.file.Files;
import java.util.Locale;

/**
 * Headless benchmark.
 *   rig:     Bench rig <in.glb> <markers.json> <out.glb> [resolution]
 *   measure: Bench measure <rigged.glb>      (quality of any Mixamo-named rig, e.g. Blender's)
 * markers.json: {"chin":[x,y,z], "neck":..., "shoulder":..., "wrist":..., "pelvis":..., "ankle":...} (glTF axes)
 */
public class Bench {
    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.US);
        if (args[0].equals("gate")) gate(args);
        else if (args[0].equals("guess")) guess(args);
        else if (args[0].equals("rig")) rig(args);
        else measure(args[1]);
    }

    /**
     * gate <dir>: rigs every body that has reference markers and fails (exit 1) when quality drops.
     * Run with: ./gradlew :lwjgl3:qualityGate
     */
    static void gate(String[] args) throws Exception {
        File[] files = new File(args[1]).listFiles((d, n) -> n.endsWith(".glb"));
        java.util.Arrays.sort(files);
        int fails = 0;
        for (File f : files) {
            File mk = new File(f.getPath().replace(".glb", ".markers.json"));
            if (!mk.exists()) continue;
            byte[] src = Files.readAllBytes(f.toPath());
            JsonValue mj = new JsonReader().parse(new String(Files.readAllBytes(mk.toPath()), "UTF-8"));
            RigPipeline p = new RigPipeline(src, new Matrix4());
            Markers g = p.guessMarkers();
            float h = p.mesh.height(), worst = 0;
            Markers m = new Markers();
            for (Markers.Kind k : Markers.Kind.values()) {
                float[] v = mj.get(k.name().toLowerCase()).asFloatArray();
                m.get(k).set(v[0], v[1], v[2]);
                worst = Math.max(worst, (float) Math.hypot(g.get(k).x - v[0], g.get(k).y - v[1]) / h * 100);
            }
            m.constrainAll(p.mesh);
            p.markers = m;
            p.twist = true;
            p.rig(96, null);
            float bad = 0;
            for (Quality.PoseScore sc : p.quality.poses) bad += sc.badEdgePct / p.quality.poses.size;
            for (String c : com.star4droid.mobirig.rig.Poses.CLIPS) p.clips.add(com.star4droid.mobirig.rig.Poses.builtin(c));
            byte[] out = p.exportFinal();
            GlbFile.parse(out);
            boolean ok = bad < 0.6f && p.quality.unweightedVertices == 0 && p.quality.bleedPct < 1.5f && worst < 8f;
            if (!ok) fails++;
            System.out.printf("%s %-30s badEdges=%.3f%% bleed=%.2f%% unweighted=%d guessWorst=%.1f%% fingers=%d%n", ok ? "PASS" : "FAIL",
                f.getName(), bad, p.quality.bleedPct, p.quality.unweightedVertices, worst, p.fingerCount);
        }
        if (fails > 0) {
            System.out.println(fails + " model(s) below the quality bar");
            System.exit(1);
        }
        System.out.println("Quality gate passed");
    }

    /** guess <in.glb> <ref markers.json> : error of the guessed markers, as % of the height. */
    static void guess(String[] args) throws Exception {
        Matrix4 orient = new Matrix4();
        if (System.getProperty("yaw") != null) orient.setToRotation(Vector3.Y, Float.parseFloat(System.getProperty("yaw")));
        for (String k : new String[]{"NECK_T", "CHIN_T", "SHOULDER_X", "PELVIS_UP"})
            if (System.getProperty(k) != null) Markers.class.getField(k).setFloat(null, Float.parseFloat(System.getProperty(k)));
        RigPipeline p = new RigPipeline(Files.readAllBytes(new File(args[1]).toPath()), orient);
        Markers g = p.guessMarkers();
        JsonValue mj = new JsonReader().parse(new String(Files.readAllBytes(new File(args[2]).toPath()), "UTF-8"));
        StringBuilder sb = new StringBuilder(String.format("%-28s", new File(args[1]).getName()));
        float h = p.mesh.height(), worst = 0;
        for (Markers.Kind k : Markers.Kind.values()) {
            float[] v = mj.get(k.name().toLowerCase()).asFloatArray();
            float ex = (g.get(k).x - v[0]) / h * 100, ey = (g.get(k).y - v[1]) / h * 100;
            worst = Math.max(worst, (float) Math.hypot(ex, ey));
            sb.append(String.format(" %s(%+.1f,%+.1f)", k.name().substring(0, 2), ex, ey));
        }
        System.out.println(sb + String.format(" worst=%.1f %s", worst, g.guessNote));
    }

    static void rig(String[] args) throws Exception {
        byte[] src = Files.readAllBytes(new File(args[1]).toPath());
        int res = args.length > 4 ? Integer.parseInt(args[4]) : 112;
        if (System.getProperty("debug") != null) {
            String[] d = System.getProperty("debug").split(",");
            com.star4droid.mobirig.rig.VoxelBinder.debugPoint = new float[]{Float.parseFloat(d[0]), Float.parseFloat(d[1]), Float.parseFloat(d[2])};
        }
        long t0 = System.currentTimeMillis();
        Matrix4 orient = new Matrix4();
        if (System.getProperty("yaw") != null) orient.setToRotation(Vector3.Y, Float.parseFloat(System.getProperty("yaw")));
        if (System.getProperty("mirror") != null) orient.mulLeft(new Matrix4().setToScaling(-1, 1, 1));
        com.star4droid.mobirig.rig.FingerDetector.debug = true;
        if (System.getProperty("band") != null) com.star4droid.mobirig.rig.FingerDetector.BAND = Float.parseFloat(System.getProperty("band"));
        RigPipeline p = new RigPipeline(src, orient);
        long tLoad = System.currentTimeMillis() - t0;
        if (args[2].equals("guess")) p.guessMarkers();
        else {
            JsonValue mj = new JsonReader().parse(new String(Files.readAllBytes(new File(args[2]).toPath()), "UTF-8"));
            Markers m = new Markers();
            for (Markers.Kind k : Markers.Kind.values()) {
                float[] v = mj.get(k.name().toLowerCase()).asFloatArray();
                m.get(k).set(v[0], v[1], v[2]);
            }
            m.constrainAll(p.mesh);
            p.markers = m;
        }
        for (Markers.Kind k : Markers.Kind.values()) System.out.println("MARKER " + k + " " + p.markers.get(k));
        com.star4droid.mobirig.rig.Quality.dumpPose = System.getProperty("dump");
        p.twist = System.getProperty("twist") != null;
        p.rig(res, null);
        if (System.getProperty("clips") != null) {
            for (String c : com.star4droid.mobirig.rig.Poses.CLIPS) p.clips.add(com.star4droid.mobirig.rig.Poses.builtin(c));
            if (System.getProperty("naming") != null) p.naming = Integer.parseInt(System.getProperty("naming"));
            p.output = p.exportFinal();
        }
        if (System.getProperty("retarget") != null) {
            com.star4droid.mobirig.rig.Retarget rt = new com.star4droid.mobirig.rig.Retarget(Files.readAllBytes(new File(System.getProperty("retarget")).toPath()), "imported");
            float bestErr = Float.MAX_VALUE; String bestName = "";
            for (String c : com.star4droid.mobirig.rig.Poses.CLIPS) {
                com.star4droid.mobirig.rig.Clip b = com.star4droid.mobirig.rig.Poses.builtin(c);
                float err = 0;
                for (int f = 0; f < 10; f++) {
                    float t = rt.length() * f / 10f;
                    Quaternion[] a = rt.sample(p.skeleton, t, new Vector3()), q = b.sample(p.skeleton, t, new Vector3());
                    com.badlogic.gdx.math.Matrix4[] ga = com.star4droid.mobirig.rig.Poses.world(p.skeleton, a), gq = com.star4droid.mobirig.rig.Poses.world(p.skeleton, q);
                    for (int j = 0; j < a.length; j++) err = Math.max(err, ga[j].getTranslation(new Vector3()).dst(gq[j].getTranslation(new Vector3())) / p.mesh.height());
                }
                if (err < bestErr) { bestErr = err; bestName = c; }
            }
            System.out.printf("RETARGET matched=%d length=%.2f closest=%s maxJointError=%.2f%% of height%n", rt.matched, rt.length(), bestName, bestErr * 100);
        }
        if (com.star4droid.mobirig.rig.Quality.dumped != null) {
            float[] d = com.star4droid.mobirig.rig.Quality.dumped;
            java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(8 + d.length * 8 + p.mesh.tris.length * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            bb.putInt(p.mesh.vertexCount).putInt(p.mesh.triCount);
            for (float f : p.mesh.pos) bb.putFloat(f);
            for (float f : d) bb.putFloat(f);
            for (int t : p.mesh.tris) bb.putInt(t);
            Files.write(new File(args[3] + ".pose.bin").toPath(), bb.array());
        }
        Files.write(new File(args[3]).toPath(), p.output);
        // skeleton dump for the Blender baseline
        StringBuilder sb = new StringBuilder("{");
        for (int j = 0; j < p.skeleton.joints.size; j++) {
            Skeleton.Joint jt = p.skeleton.joints.get(j);
            Vector3 tail = jt.tail.isZero() ? jt.pos : jt.tail;
            sb.append(String.format("\"%s\":{\"head\":[%f,%f,%f],\"tail\":[%f,%f,%f],\"parent\":\"%s\",\"deform\":%b}%s",
                jt.name, jt.pos.x, jt.pos.y, jt.pos.z, tail.x, tail.y, tail.z,
                jt.parent < 0 ? "" : p.skeleton.joints.get(jt.parent).name, jt.deform, j + 1 < p.skeleton.joints.size ? "," : ""));
        }
        sb.append("}");
        Files.write(new File(args[3] + ".skeleton.json").toPath(), sb.toString().getBytes("UTF-8"));
        System.out.printf("RESULT %s verts=%d tris=%d islands=%d load=%dms fit=%dms bind=%dms export=%dms quality=%dms%n",
            new File(args[1]).getName(), p.mesh.vertexCount, p.mesh.triCount, p.mesh.islandCount, tLoad, p.fitMs, p.bindMs, p.exportMs, p.qualityMs);
        for (String n : p.notes) System.out.println("NOTE " + n);
        for (int j = 0; j < p.skeleton.joints.size; j++) {
            Skeleton.Joint jt = p.skeleton.joints.get(j);
            if (jt.name.startsWith("LeftHand")) System.out.printf("JOINT %s %.4f %.4f %.4f%n", jt.name, jt.pos.x, jt.pos.y, jt.pos.z);
        }
        print(p.quality);
    }

    static void measure(String path) throws Exception {
        byte[] src = Files.readAllBytes(new File(path).toPath());
        GlbFile glb = GlbFile.parse(src);
        RigMesh mesh = RigMesh.build(glb, new Matrix4());
        JsonValue json = glb.json;
        JsonValue nodes = json.get("nodes");
        JsonValue skin = json.get("skins").get(0);
        int[] jn = skin.get("joints").asIntArray();
        // world matrices of all nodes
        Matrix4[] g = new Matrix4[nodes.size];
        for (int i = 0; i < nodes.size; i++) if (g[i] == null) globalOf(nodes, i, g);
        Skeleton sk = Skeleton.mixamo();
        int[] map = new int[jn.length];
        for (int i = 0; i < jn.length; i++) {
            String name = nodes.get(jn[i]).getString("name", "");
            int c = name.lastIndexOf(':');
            if (c >= 0) name = name.substring(c + 1);
            if (name.startsWith("mixamorig_")) name = name.substring(10);
            map[i] = sk.index(name);
            if (map[i] >= 0) g[jn[i]].getTranslation(sk.joints.get(map[i]).pos);
        }
        sk.computeFrames();
        int[] joints = new int[mesh.vertexCount * 4];
        float[] weights = new float[mesh.vertexCount * 4];
        for (RigMesh.Part part : mesh.parts) {
            JsonValue attrs = json.get("meshes").get(part.mesh).get("primitives").get(part.prim).get("attributes");
            float[] js = glb.readFloats(attrs.getInt("JOINTS_0"));
            float[] ws = glb.readFloats(attrs.getInt("WEIGHTS_0"));
            for (int v = 0; v < part.vertexCount; v++) {
                for (int k = 0; k < 4; k++) {
                    int o = (part.vertexOffset + v) * 4 + k;
                    int src2 = map[(int) js[v * 4 + k]];
                    joints[o] = Math.max(src2, 0);
                    weights[o] = src2 >= 0 ? ws[v * 4 + k] : 0;
                }
            }
        }
        System.out.printf("RESULT %s verts=%d (measured existing rig)%n", new File(path).getName(), mesh.vertexCount);
        print(Quality.measure(mesh, sk, joints, weights));
    }

    private static Matrix4 globalOf(JsonValue nodes, int i, Matrix4[] g) {
        if (g[i] != null) return g[i];
        int parent = -1;
        for (int p = 0; p < nodes.size && parent < 0; p++) {
            JsonValue ch = nodes.get(p).get("children");
            if (ch != null) for (JsonValue c = ch.child; c != null; c = c.next) if (c.asInt() == i) parent = p;
        }
        JsonValue n = nodes.get(i);
        Matrix4 local = new Matrix4();
        if (n.has("matrix")) local.set(n.get("matrix").asFloatArray());
        else {
            Vector3 t = new Vector3(), s = new Vector3(1, 1, 1);
            Quaternion q = new Quaternion();
            if (n.has("translation")) { float[] a = n.get("translation").asFloatArray(); t.set(a[0], a[1], a[2]); }
            if (n.has("rotation")) { float[] a = n.get("rotation").asFloatArray(); q.set(a[0], a[1], a[2], a[3]); }
            if (n.has("scale")) { float[] a = n.get("scale").asFloatArray(); s.set(a[0], a[1], a[2]); }
            local.set(t, q, s);
        }
        g[i] = parent < 0 ? local : new Matrix4(globalOf(nodes, parent, g)).mul(local);
        return g[i];
    }

    static void print(Quality q) {
        System.out.printf("WEIGHTS unweighted=%d bleed=%.2f%%%n", q.unweightedVertices, q.bleedPct);
        q.bleedPairs.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(6)
            .forEach(e -> {
                float[] w = q.bleedWhere.get(e.getKey());
                System.out.printf("BLEED %s %d at (%.3f, %.3f, %.3f)%n", e.getKey(), e.getValue(), w[0] / w[3], w[1] / w[3], w[2] / w[3]);
            });
        float sumBad = 0, sumCol = 0;
        for (Quality.PoseScore s : q.poses) {
            System.out.printf("POSE %-13s badEdges=%6.2f%% collapsed=%6.2f%% maxStretch=%5.2f volume=%+6.2f%%%n",
                s.pose, s.badEdgePct, s.collapsedTriPct, s.maxStretch, s.volumeChangePct);
            sumBad += s.badEdgePct;
            sumCol += s.collapsedTriPct;
        }
        System.out.printf("MEAN badEdges=%.3f%% collapsed=%.3f%%%n", sumBad / q.poses.size, sumCol / q.poses.size);
    }
}
