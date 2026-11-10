package com.star4droid.mobirig.rig;

import com.badlogic.gdx.math.Matrix3;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Quaternion;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.JsonValue;
import com.badlogic.gdx.utils.ObjectMap;

/**
 * Writes the rigged model as a new GLB built on the original file: materials, textures, UVs,
 * vertex colours and morph targets are kept; positions/normals are baked to the oriented rest
 * pose; old skins, animations and helper nodes are removed; a Mixamo-named skeleton is added.
 */
public class GlbExporter {
    public String prefix = "mixamorig:";

    public byte[] export(byte[] source, RigMesh mesh, Skeleton sk, int[] joints, float[] weights) {
        return export(source, mesh, sk, joints, weights, null);
    }

    /** Debug/preview variant: every vertex coloured (RGB per vertex) with one plain material. */
    public byte[] export(byte[] source, RigMesh mesh, Skeleton sk, int[] joints, float[] weights, float[] colors) {
        GlbFile glb = GlbFile.parse(source);
        int plainMaterial = -1;
        if (colors != null) {
            com.badlogic.gdx.utils.JsonValue mat = new JsonReaderHelper().parse(
                "{\"name\":\"weights\",\"pbrMetallicRoughness\":{\"baseColorFactor\":[1,1,1,1],\"metallicFactor\":0,\"roughnessFactor\":0.9},\"doubleSided\":true}");
            plainMaterial = GlbFile.append(glb.array("materials"), mat);
        }
        JsonValue json = glb.json;
        JsonValue srcMeshes = json.get("meshes");

        // ---- meshes: one new mesh per (node, mesh) instance, primitives rewritten
        JsonValue newMeshes = new JsonValue(JsonValue.ValueType.array);
        ObjectMap<String, Integer> meshFor = new ObjectMap<>();
        com.badlogic.gdx.utils.Array<String> meshNodeNames = new com.badlogic.gdx.utils.Array<>();
        com.badlogic.gdx.utils.IntArray meshNodeMesh = new com.badlogic.gdx.utils.IntArray();

        for (RigMesh.Part part : mesh.parts) {
            String key = part.node + ":" + part.mesh;
            Integer nm = meshFor.get(key);
            JsonValue meshJson;
            if (nm == null) {
                JsonValue src = srcMeshes.get(part.mesh);
                meshJson = new JsonValue(JsonValue.ValueType.object);
                if (src.has("name")) meshJson.addChild("name", new JsonValue(src.getString("name")));
                if (src.has("weights")) meshJson.addChild("weights", copy(src.get("weights")));
                if (src.has("extras")) meshJson.addChild("extras", copy(src.get("extras")));
                meshJson.addChild("primitives", new JsonValue(JsonValue.ValueType.array));
                nm = GlbFile.append(newMeshes, meshJson);
                meshFor.put(key, nm);
                meshNodeNames.add(part.name);
                meshNodeMesh.add(nm);
            } else meshJson = newMeshes.get(nm);

            JsonValue srcPrim = srcMeshes.get(part.mesh).get("primitives").get(part.prim);
            JsonValue prim = copy(srcPrim);
            JsonValue attrs = prim.get("attributes");
            int off = part.vertexOffset, vc = part.vertexCount;

            float[] p = new float[vc * 3];
            System.arraycopy(mesh.pos, off * 3, p, 0, vc * 3);
            setAttr(attrs, "POSITION", glb.addFloatAccessor(p, "VEC3", true));
            if (attrs.has("NORMAL")) {
                float[] n = new float[vc * 3];
                System.arraycopy(mesh.nrm, off * 3, n, 0, vc * 3);
                setAttr(attrs, "NORMAL", glb.addFloatAccessor(n, "VEC3", false));
            }
            if (attrs.has("TANGENT")) {
                float[] t = glb.readFloats(attrs.getInt("TANGENT"));
                Vector3 v = new Vector3();
                Matrix3 m = new Matrix3();
                for (int i = 0; i < vc; i++) {
                    System.arraycopy(part.linear, i * 9, m.val, 0, 9);
                    v.set(t[i * 4], t[i * 4 + 1], t[i * 4 + 2]).mul(m).nor();
                    t[i * 4] = v.x;
                    t[i * 4 + 1] = v.y;
                    t[i * 4 + 2] = v.z;
                    if (part.flip) t[i * 4 + 3] = -t[i * 4 + 3];
                }
                setAttr(attrs, "TANGENT", glb.addFloatAccessor(t, "VEC4", false));
            }
            for (String old : new String[]{"JOINTS_0", "WEIGHTS_0", "JOINTS_1", "WEIGHTS_1"}) attrs.remove(old);
            int[] j = new int[vc * 4];
            float[] w = new float[vc * 4];
            for (int i = 0; i < vc * 4; i++) {
                j[i] = joints[off * 4 + i];
                w[i] = weights[off * 4 + i];
            }
            if (colors != null) {
                float[] col = new float[vc * 3];
                System.arraycopy(colors, off * 3, col, 0, vc * 3);
                for (String old : new String[]{"COLOR_0", "TEXCOORD_0", "TEXCOORD_1", "TANGENT"}) attrs.remove(old);
                attrs.addChild("COLOR_0", new JsonValue(glb.addFloatAccessor(col, "VEC3", false)));
                prim.remove("material");
                prim.addChild("material", new JsonValue(plainMaterial));
                prim.remove("targets");
            }
            attrs.addChild("JOINTS_0", new JsonValue(glb.addUByteVec4Accessor(j)));
            attrs.addChild("WEIGHTS_0", new JsonValue(glb.addFloatAccessor(w, "VEC4", false)));

            if (part.flip) {
                int[] idx = part.localIndices.clone();
                for (int k = 0; k + 2 < idx.length; k += 3) {
                    int s = idx[k + 1];
                    idx[k + 1] = idx[k + 2];
                    idx[k + 2] = s;
                }
                prim.remove("indices");
                prim.addChild("indices", new JsonValue(glb.addIndexAccessor(idx, vc)));
            }
            // morph targets: deltas follow the same baked transform
            JsonValue targets = prim.get("targets");
            if (targets != null) {
                Matrix3 m = new Matrix3(), nmx = new Matrix3();
                Vector3 v = new Vector3();
                for (JsonValue tg = targets.child; tg != null; tg = tg.next) {
                    for (String a : new String[]{"POSITION", "NORMAL", "TANGENT"}) {
                        if (!tg.has(a)) continue;
                        float[] d = glb.readFloats(tg.getInt(a));
                        for (int i = 0; i < vc; i++) {
                            System.arraycopy(part.linear, i * 9, m.val, 0, 9);
                            Matrix3 use = m;
                            if (!a.equals("POSITION")) use = nmx.set(m).inv().transpose();
                            v.set(d[i * 3], d[i * 3 + 1], d[i * 3 + 2]).mul(use);
                            d[i * 3] = v.x;
                            d[i * 3 + 1] = v.y;
                            d[i * 3 + 2] = v.z;
                        }
                        setAttr(tg, a, glb.addFloatAccessor(d, "VEC3", a.equals("POSITION")));
                    }
                }
            }
            meshJson.get("primitives").addChild(prim);
        }

        // ---- nodes: flat mesh nodes + skeleton
        JsonValue nodes = new JsonValue(JsonValue.ValueType.array);
        int[] meshNodes = new int[meshNodeNames.size];
        for (int i = 0; i < meshNodeNames.size; i++) {
            JsonValue n = new JsonValue(JsonValue.ValueType.object);
            n.addChild("name", new JsonValue(meshNodeNames.get(i)));
            n.addChild("mesh", new JsonValue(meshNodeMesh.get(i)));
            n.addChild("skin", new JsonValue(0));
            meshNodes[i] = GlbFile.append(nodes, n);
        }
        int base = nodes.size;
        int nj = sk.joints.size;
        float[] ibm = new float[nj * 16];
        Matrix4 wm = new Matrix4();
        Quaternion q = new Quaternion();
        Vector3 t = new Vector3();
        for (int ji = 0; ji < nj; ji++) {
            Skeleton.Joint jt = sk.joints.get(ji);
            JsonValue n = new JsonValue(JsonValue.ValueType.object);
            n.addChild("name", new JsonValue(prefix + jt.name));
            sk.localTranslation(ji, t);
            sk.localRotation(ji, q).nor();
            n.addChild("translation", GlbFile.floatArray(new float[]{t.x, t.y, t.z}));
            n.addChild("rotation", GlbFile.floatArray(new float[]{q.x, q.y, q.z, q.w}));
            com.badlogic.gdx.utils.IntArray ch = new com.badlogic.gdx.utils.IntArray();
            for (int c = 0; c < nj; c++) if (sk.joints.get(c).parent == ji) ch.add(base + c);
            if (ch.size > 0) n.addChild("children", GlbFile.intArray(ch.toArray()));
            nodes.addChild(n);
            sk.worldMatrix(ji, wm).inv();
            System.arraycopy(wm.val, 0, ibm, ji * 16, 16);
        }
        int ibmAcc = glb.addFloatAccessor(ibm, "MAT4", false);
        json.remove("nodes");
        json.addChild("nodes", nodes);
        json.remove("meshes");
        json.addChild("meshes", newMeshes);

        JsonValue skins = new JsonValue(JsonValue.ValueType.array);
        JsonValue skin = new JsonValue(JsonValue.ValueType.object);
        skin.addChild("name", new JsonValue("Armature"));
        skin.addChild("inverseBindMatrices", new JsonValue(ibmAcc));
        int[] jointNodes = new int[nj];
        for (int i = 0; i < nj; i++) jointNodes[i] = base + i;
        skin.addChild("joints", GlbFile.intArray(jointNodes));
        skin.addChild("skeleton", new JsonValue(base));
        skins.addChild(skin);
        json.remove("skins");
        json.addChild("skins", skins);
        json.remove("animations");
        json.remove("cameras");

        JsonValue scenes = new JsonValue(JsonValue.ValueType.array);
        JsonValue scene = new JsonValue(JsonValue.ValueType.object);
        scene.addChild("name", new JsonValue("Scene"));
        int[] roots = new int[meshNodes.length + 1];
        System.arraycopy(meshNodes, 0, roots, 0, meshNodes.length);
        roots[meshNodes.length] = base;
        scene.addChild("nodes", GlbFile.intArray(roots));
        scenes.addChild(scene);
        json.remove("scenes");
        json.addChild("scenes", scenes);
        json.remove("scene");
        json.addChild("scene", new JsonValue(0));
        // lights referenced by removed nodes are harmless; drop the node-level extension use
        JsonValue asset = json.get("asset");
        if (asset != null) {
            asset.remove("generator");
            asset.addChild("generator", new JsonValue("MobiRig"));
        }
        return glb.write();
    }

    private static void setAttr(JsonValue obj, String name, int accessor) {
        obj.remove(name);
        obj.addChild(name, new JsonValue(accessor));
    }

    private static class JsonReaderHelper extends com.badlogic.gdx.utils.JsonReader {
    }

    /** Vertex colours for the weights view: one bone as a heat ramp, or all bones mixed (bone = -1). */
    public static float[] weightColors(RigMesh mesh, Skeleton sk, int[] joints, float[] weights, int bone) {
        float[] c = new float[mesh.vertexCount * 3];
        for (int v = 0; v < mesh.vertexCount; v++) {
            float r = 0, g = 0, b = 0;
            if (bone >= 0) {
                float w = 0;
                for (int k = 0; k < 4; k++) if (joints[v * 4 + k] == bone) w += weights[v * 4 + k];
                // blue (0) -> green -> yellow -> red (1)
                if (w <= 0.001f) { r = 0.08f; g = 0.1f; b = 0.35f; }
                else if (w < 0.33f) { float t = w / 0.33f; r = 0; g = t; b = 1 - t; }
                else if (w < 0.66f) { float t = (w - 0.33f) / 0.33f; r = t; g = 1; b = 0; }
                else { float t = (w - 0.66f) / 0.34f; r = 1; g = 1 - t; b = 0; }
            } else {
                for (int k = 0; k < 4; k++) {
                    float w = weights[v * 4 + k];
                    if (w <= 0) continue;
                    float[] bc = boneColor(joints[v * 4 + k]);
                    r += w * bc[0]; g += w * bc[1]; b += w * bc[2];
                }
            }
            c[v * 3] = r; c[v * 3 + 1] = g; c[v * 3 + 2] = b;
        }
        return c;
    }

    private static float[] boneColor(int j) {
        float hue = (j * 0.618034f) % 1f;
        float s = 0.75f, l = 0.55f;
        float q = l < 0.5f ? l * (1 + s) : l + s - l * s, pp = 2 * l - q;
        return new float[]{hue2rgb(pp, q, hue + 1f / 3), hue2rgb(pp, q, hue), hue2rgb(pp, q, hue - 1f / 3)};
    }

    private static float hue2rgb(float p, float q, float t) {
        if (t < 0) t += 1;
        if (t > 1) t -= 1;
        if (t < 1f / 6) return p + (q - p) * 6 * t;
        if (t < 0.5f) return q;
        if (t < 2f / 3) return p + (q - p) * (2f / 3 - t) * 6;
        return p;
    }

    static JsonValue copy(JsonValue v) {
        return new com.badlogic.gdx.utils.JsonReader().parse(v.toJson(com.badlogic.gdx.utils.JsonWriter.OutputType.json));
    }
}
