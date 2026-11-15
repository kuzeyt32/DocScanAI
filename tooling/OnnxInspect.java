import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * DocScanAI SUBTASK H7 - JDK-only ONNX protobuf inspector.
 *
 * Why this exists: the UVDoc grid re-host ships an external-data sidecar
 * (`UVDoc_grid.onnx` + `UVDoc_grid.onnx.data`). We must confirm the true tensor contract
 * (input shape/dtype, output shape/dtype) and the external-data reference BEFORE pinning it in
 * the registry. No Python, no onnxruntime, no new Gradle dependency: this parses the protobuf
 * wire format directly with the pinned JDK 21.
 *
 * Usage:
 *   java tooling\OnnxInspect.java <model.onnx> [more.onnx ...]
 *
 * It prints, per model:
 *   graph name
 *   inputs  : name, elem_type, shape (dim_value / dim_param)
 *   outputs : name, elem_type, shape
 *   initializers: name, dims, and EXTERNAL data location when data_location == 1
 */

public final class OnnxInspect {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: OnnxInspect <model.onnx> [more.onnx ...]");
            System.exit(2);
        }
        for (String arg : args) {
            Path p = Paths.get(arg);
            System.out.println("==================================================================");
            System.out.println("MODEL " + p.toAbsolutePath());
            System.out.println("bytes " + Files.size(p));
            byte[] data = Files.readAllBytes(p);
            Model m = parseModel(data);
            System.out.println("ir_version=" + m.irVersion + " producer=" + m.producer);
            if (m.graph == null) {
                System.out.println("ERROR: no graph field");
                continue;
            }
            System.out.println("graph.name=" + m.graph.name);
            System.out.println("inputs (" + m.graph.inputs.size() + "):");
            for (ValueInfo v : m.graph.inputs) {
                System.out.println("  " + v);
            }
            System.out.println("outputs (" + m.graph.outputs.size() + "):");
            for (ValueInfo v : m.graph.outputs) {
                System.out.println("  " + v);
            }
            System.out.println("initializers (" + m.graph.initializers.size() + "):");
            for (Tensor t : m.graph.initializers) {
                if (t.external) {
                    System.out.println("  EXTERNAL " + t.name + " dims=" + t.dims +
                            " location=" + t.externalLocation +
                            " offset=" + t.externalOffset +
                            " length=" + t.externalLength);
                } else {
                    System.out.println("  embedded " + t.name + " dims=" + t.dims);
                }
            }
        }
    }

    // --- minimal protobuf ----------------------------------------------------------------

    private static final class Reader {
        final byte[] b;
        int pos;
        final int end;

        Reader(byte[] b, int start, int end) {
            this.b = b;
            this.pos = start;
            this.end = end;
        }

        boolean hasMore() { return pos < end; }

        int readVarint() {
            long v = readVarint64();
            return (int) v;
        }

        long readVarint64() {
            long result = 0;
            int shift = 0;
            while (shift < 64) {
                int x = b[pos++] & 0xFF;
                result |= (long) (x & 0x7F) << shift;
                if ((x & 0x80) == 0) return result;
                shift += 7;
            }
            throw new IllegalArgumentException("malformed varint at " + pos);
        }

        int readTag() { return readVarint(); }

        byte[] readBytes() {
            int len = readVarint();
            if (len < 0 || pos + len > end) throw new IllegalArgumentException("bad length " + len);
            byte[] out = new byte[len];
            System.arraycopy(b, pos, out, 0, len);
            pos += len;
            return out;
        }

        void skip(int wire) {
            switch (wire) {
                case 0: readVarint64(); break;
                case 1: pos += 8; break;
                case 2: { int len = readVarint(); pos += len; break; }
                case 5: pos += 4; break;
                default: throw new IllegalArgumentException("unsupported wire type " + wire);
            }
        }
    }

    private static final class Model {
        long irVersion = -1;
        String producer = "";
        Graph graph;
    }

    private static final class Graph {
        String name = "";
        final List<ValueInfo> inputs = new ArrayList<>();
        final List<ValueInfo> outputs = new ArrayList<>();
        final List<Tensor> initializers = new ArrayList<>();
    }

    private static final class ValueInfo {
        String name = "";
        int elemType = -1;
        final List<String> dims = new ArrayList<>();

        @Override public String toString() {
            return name + " elem_type=" + elemTypeName(elemType) + "(" + elemType + ") shape=" + dims;
        }
    }

    private static final class Tensor {
        String name = "";
        final List<Long> dims = new ArrayList<>();
        boolean external = false;
        String externalLocation = "";
        String externalOffset = "";
        String externalLength = "";
    }

    private static Model parseModel(byte[] data) {
        Model model = new Model();
        Reader r = new Reader(data, 0, data.length);
        while (r.hasMore()) {
            int tag = r.readTag();
            int field = tag >>> 3;
            int wire = tag & 7;
            if (field == 7 && wire == 2) {
                model.graph = parseGraph(r.readBytes());
            } else if (field == 1 && wire == 0) {
                model.irVersion = r.readVarint64();
            } else if (field == 2 && wire == 2) {
                model.producer = new String(r.readBytes(), StandardCharsets.UTF_8);
            } else {
                r.skip(wire);
            }
        }
        return model;
    }

    private static Graph parseGraph(byte[] data) {
        Graph g = new Graph();
        Reader r = new Reader(data, 0, data.length);
        while (r.hasMore()) {
            int tag = r.readTag();
            int field = tag >>> 3;
            int wire = tag & 7;
            if (field == 2 && wire == 2) {
                g.name = new String(r.readBytes(), StandardCharsets.UTF_8);
            } else if (field == 11 && wire == 2) {
                g.inputs.add(parseValueInfo(r.readBytes()));
            } else if (field == 12 && wire == 2) {
                g.outputs.add(parseValueInfo(r.readBytes()));
            } else if (field == 5 && wire == 2) {
                g.initializers.add(parseTensor(r.readBytes()));
            } else {
                r.skip(wire);
            }
        }
        return g;
    }

    private static ValueInfo parseValueInfo(byte[] data) {
        ValueInfo v = new ValueInfo();
        Reader r = new Reader(data, 0, data.length);
        while (r.hasMore()) {
            int tag = r.readTag();
            int field = tag >>> 3;
            int wire = tag & 7;
            if (field == 1 && wire == 2) {
                v.name = new String(r.readBytes(), StandardCharsets.UTF_8);
            } else if (field == 2 && wire == 2) {
                parseType(r.readBytes(), v);
            } else {
                r.skip(wire);
            }
        }
        return v;
    }

    private static void parseType(byte[] data, ValueInfo v) {
        Reader r = new Reader(data, 0, data.length);
        while (r.hasMore()) {
            int tag = r.readTag();
            int field = tag >>> 3;
            int wire = tag & 7;
            if (field == 1 && wire == 2) {
                parseTensorType(r.readBytes(), v);
            } else {
                r.skip(wire);
            }
        }
    }

    private static void parseTensorType(byte[] data, ValueInfo v) {
        Reader r = new Reader(data, 0, data.length);
        while (r.hasMore()) {
            int tag = r.readTag();
            int field = tag >>> 3;
            int wire = tag & 7;
            if (field == 1 && wire == 0) {
                v.elemType = r.readVarint();
            } else if (field == 2 && wire == 2) {
                parseShape(r.readBytes(), v);
            } else {
                r.skip(wire);
            }
        }
    }

    private static void parseShape(byte[] data, ValueInfo v) {
        Reader r = new Reader(data, 0, data.length);
        while (r.hasMore()) {
            int tag = r.readTag();
            int field = tag >>> 3;
            int wire = tag & 7;
            if (field == 1 && wire == 2) {
                parseDim(r.readBytes(), v);
            } else {
                r.skip(wire);
            }
        }
    }

    private static void parseDim(byte[] data, ValueInfo v) {
        Reader r = new Reader(data, 0, data.length);
        String dim = "?";
        while (r.hasMore()) {
            int tag = r.readTag();
            int field = tag >>> 3;
            int wire = tag & 7;
            if (field == 1 && wire == 0) {
                dim = Long.toString(r.readVarint64());
            } else if (field == 2 && wire == 2) {
                dim = new String(r.readBytes(), StandardCharsets.UTF_8);
            } else {
                r.skip(wire);
            }
        }
        v.dims.add(dim);
    }

    private static Tensor parseTensor(byte[] data) {
        Tensor t = new Tensor();
        Reader r = new Reader(data, 0, data.length);
        while (r.hasMore()) {
            int tag = r.readTag();
            int field = tag >>> 3;
            int wire = tag & 7;
            if (field == 1 && wire == 0) {
                t.dims.add(r.readVarint64());
            } else if (field == 1 && wire == 2) {
                byte[] packedBytes = r.readBytes();
                Reader packed = new Reader(packedBytes, 0, packedBytes.length);
                while (packed.hasMore()) t.dims.add(packed.readVarint64());
            } else if (field == 8 && wire == 2) {
                t.name = new String(r.readBytes(), StandardCharsets.UTF_8);
            } else if (field == 14 && wire == 0) {
                t.external = r.readVarint() != 0;
            } else if (field == 13 && wire == 2) {
                parseExternalEntry(r.readBytes(), t);
            } else {
                r.skip(wire);
            }
        }
        return t;
    }

    private static void parseExternalEntry(byte[] data, Tensor t) {
        Reader r = new Reader(data, 0, data.length);
        String key = "";
        String value = "";
        while (r.hasMore()) {
            int tag = r.readTag();
            int field = tag >>> 3;
            int wire = tag & 7;
            if (field == 1 && wire == 2) {
                key = new String(r.readBytes(), StandardCharsets.UTF_8);
            } else if (field == 2 && wire == 2) {
                value = new String(r.readBytes(), StandardCharsets.UTF_8);
            } else {
                r.skip(wire);
            }
        }
        if ("location".equals(key)) t.externalLocation = value;
        else if ("offset".equals(key)) t.externalOffset = value;
        else if ("length".equals(key)) t.externalLength = value;
    }

    private static String elemTypeName(int type) {
        switch (type) {
            case 1: return "FLOAT";
            case 2: return "UINT8";
            case 3: return "INT8";
            case 4: return "UINT16";
            case 5: return "INT16";
            case 6: return "INT32";
            case 7: return "INT64";
            case 9: return "BOOL";
            case 10: return "FLOAT16";
            case 11: return "DOUBLE";
            default: return "TYPE" + type;
        }
    }
}
