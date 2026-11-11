package com.star4droid.mobirig.rig;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.badlogic.gdx.utils.JsonWriter;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Raw GLB container: the glTF JSON tree plus the binary chunk. No GL needed, so it works
 * on a background thread, on the phone, on desktop and in headless tests.
 */
public class GlbFile {
    public static final int FLOAT = 5126, UBYTE = 5121, BYTE = 5120, USHORT = 5123, SHORT = 5122, UINT = 5125;

    public final JsonValue json;
    private final byte[] bin;
    private final ByteArrayOutputStream extra = new ByteArrayOutputStream();

    private GlbFile(JsonValue json, byte[] bin) {
        this.json = json;
        this.bin = bin == null ? new byte[0] : bin;
    }

    public static GlbFile parse(byte[] data) {
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (data.length < 20 || b.getInt(0) != 0x46546C67) throw new RigException("Not a GLB file (bad header)");
        if (b.getInt(4) != 2) throw new RigException("Only glTF 2.0 GLB files are supported");
        int pos = 12;
        String jsonText = null;
        byte[] bin = null;
        while (pos + 8 <= data.length) {
            int len = b.getInt(pos), type = b.getInt(pos + 4);
            if (type == 0x4E4F534A) jsonText = new String(data, pos + 8, len, StandardCharsets.UTF_8);
            else if (type == 0x004E4942) {
                bin = new byte[len];
                System.arraycopy(data, pos + 8, bin, 0, len);
            }
            pos += 8 + len;
        }
        if (jsonText == null) throw new RigException("GLB has no JSON chunk");
        JsonValue json = new JsonReader().parse(jsonText);
        JsonValue buffers = json.get("buffers");
        if (buffers != null && buffers.size > 0 && buffers.get(0).has("uri")) {
            throw new RigException("External or embedded-URI buffers are not supported; export as .glb with a binary chunk");
        }
        JsonValue req = json.get("extensionsRequired");
        if (req != null) {
            for (JsonValue e = req.child; e != null; e = e.next) {
                String n = e.asString();
                if (n.contains("draco") || n.contains("meshopt")) {
                    throw new RigException("Compressed meshes (" + n + ") are not supported yet; export without compression");
                }
            }
        }
        return new GlbFile(json, bin);
    }

    // ------------------------------------------------------------------ reading

    public static int numComponents(String type) {
        switch (type) {
            case "SCALAR": return 1;
            case "VEC2": return 2;
            case "VEC3": return 3;
            case "VEC4": return 4;
            case "MAT2": return 4;
            case "MAT3": return 9;
            case "MAT4": return 16;
            default: throw new RigException("Unknown accessor type " + type);
        }
    }

    static int componentSize(int componentType) {
        switch (componentType) {
            case BYTE:
            case UBYTE: return 1;
            case SHORT:
            case USHORT: return 2;
            case UINT:
            case FLOAT: return 4;
            default: throw new RigException("Unknown component type " + componentType);
        }
    }

    public JsonValue accessor(int i) {
        return json.get("accessors").get(i);
    }

    public int count(int accessor) {
        return accessor(accessor).getInt("count");
    }

    /** All components of an accessor as floats (normalized integers mapped to [-1,1] / [0,1]). */
    public float[] readFloats(int accessorIndex) {
        JsonValue acc = accessor(accessorIndex);
        if (acc.has("sparse")) throw new RigException("Sparse accessors are not supported yet");
        int count = acc.getInt("count");
        int nc = numComponents(acc.getString("type"));
        int ct = acc.getInt("componentType");
        boolean norm = acc.getBoolean("normalized", false);
        float[] out = new float[count * nc];
        if (!acc.has("bufferView")) return out;
        JsonValue bv = json.get("bufferViews").get(acc.getInt("bufferView"));
        int cs = componentSize(ct);
        int stride = bv.getInt("byteStride", cs * nc);
        int base = bv.getInt("byteOffset", 0) + acc.getInt("byteOffset", 0);
        ByteBuffer b = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < count; i++) {
            int p = base + i * stride;
            for (int c = 0; c < nc; c++) {
                int q = p + c * cs;
                float v;
                switch (ct) {
                    case FLOAT: v = b.getFloat(q); break;
                    case UBYTE: v = b.get(q) & 0xFF; if (norm) v /= 255f; break;
                    case BYTE: v = b.get(q); if (norm) v = Math.max(v / 127f, -1f); break;
                    case USHORT: v = b.getShort(q) & 0xFFFF; if (norm) v /= 65535f; break;
                    case SHORT: v = b.getShort(q); if (norm) v = Math.max(v / 32767f, -1f); break;
                    case UINT: v = (float) (b.getInt(q) & 0xFFFFFFFFL); break;
                    default: throw new RigException("Bad component type");
                }
                out[i * nc + c] = v;
            }
        }
        return out;
    }

    public int[] readInts(int accessorIndex) {
        JsonValue acc = accessor(accessorIndex);
        int count = acc.getInt("count");
        int nc = numComponents(acc.getString("type"));
        int ct = acc.getInt("componentType");
        int[] out = new int[count * nc];
        if (!acc.has("bufferView")) return out;
        JsonValue bv = json.get("bufferViews").get(acc.getInt("bufferView"));
        int cs = componentSize(ct);
        int stride = bv.getInt("byteStride", cs * nc);
        int base = bv.getInt("byteOffset", 0) + acc.getInt("byteOffset", 0);
        ByteBuffer b = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < count; i++) {
            int p = base + i * stride;
            for (int c = 0; c < nc; c++) {
                int q = p + c * cs;
                int v;
                switch (ct) {
                    case UBYTE: v = b.get(q) & 0xFF; break;
                    case USHORT: v = b.getShort(q) & 0xFFFF; break;
                    case UINT: v = b.getInt(q); break;
                    case BYTE: v = b.get(q); break;
                    case SHORT: v = b.getShort(q); break;
                    case FLOAT: v = (int) b.getFloat(q); break;
                    default: throw new RigException("Bad component type");
                }
                out[i * nc + c] = v;
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ writing

    private int binLength() {
        return pad4(bin.length) + extra.size();
    }

    private static int pad4(int n) {
        return (n + 3) & ~3;
    }

    /** Appends raw bytes as a new bufferView. Returns its index. */
    public int addBufferView(byte[] data, int target) {
        while ((pad4(bin.length) + extra.size()) % 4 != 0) extra.write(0);
        int offset = binLength();
        extra.write(data, 0, data.length);
        JsonValue bv = new JsonValue(JsonValue.ValueType.object);
        bv.addChild("buffer", new JsonValue(0));
        bv.addChild("byteOffset", new JsonValue(offset));
        bv.addChild("byteLength", new JsonValue(data.length));
        if (target != 0) bv.addChild("target", new JsonValue(target));
        return append(array("bufferViews"), bv);
    }

    /** Set while writing animation data: those buffer views must not be marked as vertex data. */
    public boolean plainViews;

    public int addFloatAccessor(float[] data, String type, boolean minMax) {
        int nc = numComponents(type);
        ByteBuffer b = ByteBuffer.allocate(data.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : data) b.putFloat(f);
        int bv = addBufferView(b.array(), type.equals("MAT4") || plainViews ? 0 : 34962);
        JsonValue acc = accessorJson(bv, FLOAT, data.length / nc, type);
        if (minMax) {
            float[] mn = new float[nc], mx = new float[nc];
            for (int c = 0; c < nc; c++) {
                mn[c] = Float.MAX_VALUE;
                mx[c] = -Float.MAX_VALUE;
            }
            for (int i = 0; i < data.length; i++) {
                int c = i % nc;
                mn[c] = Math.min(mn[c], data[i]);
                mx[c] = Math.max(mx[c], data[i]);
            }
            acc.addChild("min", floatArray(mn));
            acc.addChild("max", floatArray(mx));
        }
        return append(array("accessors"), acc);
    }

    public int addUByteVec4Accessor(int[] data) {
        byte[] bytes = new byte[data.length];
        for (int i = 0; i < data.length; i++) bytes[i] = (byte) data[i];
        int bv = addBufferView(bytes, 34962);
        return append(array("accessors"), accessorJson(bv, UBYTE, data.length / 4, "VEC4"));
    }

    public int addIndexAccessor(int[] idx, int vertexCount) {
        boolean small = vertexCount < 65535;
        ByteBuffer b = ByteBuffer.allocate(idx.length * (small ? 2 : 4)).order(ByteOrder.LITTLE_ENDIAN);
        for (int i : idx) {
            if (small) b.putShort((short) i);
            else b.putInt(i);
        }
        int bv = addBufferView(b.array(), 34963);
        return append(array("accessors"), accessorJson(bv, small ? USHORT : UINT, idx.length, "SCALAR"));
    }

    private JsonValue accessorJson(int bufferView, int componentType, int count, String type) {
        JsonValue acc = new JsonValue(JsonValue.ValueType.object);
        acc.addChild("bufferView", new JsonValue(bufferView));
        acc.addChild("componentType", new JsonValue(componentType));
        acc.addChild("count", new JsonValue(count));
        acc.addChild("type", new JsonValue(type));
        return acc;
    }

    public JsonValue array(String name) {
        JsonValue a = json.get(name);
        if (a == null) {
            a = new JsonValue(JsonValue.ValueType.array);
            json.addChild(name, a);
        }
        return a;
    }

    public static int append(JsonValue array, JsonValue v) {
        int idx = array.size;
        array.addChild(v);
        return idx;
    }

    public static JsonValue floatArray(float[] v) {
        JsonValue a = new JsonValue(JsonValue.ValueType.array);
        for (float f : v) a.addChild(new JsonValue(f));
        return a;
    }

    public static JsonValue intArray(int[] v) {
        JsonValue a = new JsonValue(JsonValue.ValueType.array);
        for (int f : v) a.addChild(new JsonValue(f));
        return a;
    }

    /** Serialises JSON + original binary + appended data into a GLB. */
    public byte[] write() {
        // buffer length
        JsonValue buffers = array("buffers");
        if (buffers.size == 0) buffers.addChild(new JsonValue(JsonValue.ValueType.object));
        JsonValue buf0 = buffers.get(0);
        buf0.remove("byteLength");
        int total = binLength();
        buf0.addChild("byteLength", new JsonValue(total));

        byte[] jsonBytes = json.toJson(JsonWriter.OutputType.json).getBytes(StandardCharsets.UTF_8);
        int jsonLen = pad4(jsonBytes.length);
        int binLen = pad4(total);
        int length = 12 + 8 + jsonLen + 8 + binLen;
        ByteBuffer out = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(0x46546C67).putInt(2).putInt(length);
        out.putInt(jsonLen).putInt(0x4E4F534A).put(jsonBytes);
        for (int i = jsonBytes.length; i < jsonLen; i++) out.put((byte) ' ');
        out.putInt(binLen).putInt(0x004E4942).put(bin);
        for (int i = bin.length; i < pad4(bin.length); i++) out.put((byte) 0);
        out.put(extra.toByteArray());
        while (out.position() < length) out.put((byte) 0);
        return out.array();
    }
}
