package org.snurkle.badgeturntable.ir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads Flipper Zero ".ir" remote files (the format used by Flipper-IRDB).
 *
 * <pre>
 * Filetype: IR signals file
 * Version: 1
 * #
 * name: Power
 * type: parsed
 * protocol: NEC
 * address: 07 00 00 00
 * command: 02 00 00 00
 * #
 * name: Turn_left
 * type: raw
 * frequency: 38000
 * duty_cycle: 0.330000
 * data: 9024 4512 579 552 ...
 * </pre>
 *
 * Signals in protocols this app can't encode are returned with a null code and
 * the reason, so the UI can list them instead of silently dropping them.
 */
public final class FlipperIrParser {

    public static final class Signal {
        public final String name;
        public final IrCode code;        // null if unsupported
        public final String unsupported; // why, when code is null

        Signal(String name, IrCode code, String unsupported) {
            this.name = name;
            this.code = code;
            this.unsupported = unsupported;
        }
    }

    private FlipperIrParser() {}

    public static List<Signal> parse(Reader in) throws IOException {
        List<Signal> out = new ArrayList<>();
        Map<String, String> block = new LinkedHashMap<>();
        BufferedReader r = new BufferedReader(in);
        String line;
        while ((line = r.readLine()) != null) {
            line = line.trim();
            if (line.startsWith("#")) {
                flush(block, out);
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String key = line.substring(0, colon).trim().toLowerCase();
            String value = line.substring(colon + 1).trim();
            if (key.equals("name") && block.containsKey("name")) flush(block, out);
            block.put(key, value);
        }
        flush(block, out);
        return out;
    }

    private static void flush(Map<String, String> block, List<Signal> out) {
        String name = block.get("name");
        if (name != null) {
            try {
                out.add(new Signal(name, toCode(block), null));
            } catch (IllegalArgumentException e) {
                out.add(new Signal(name, null, e.getMessage()));
            }
        }
        block.clear();
    }

    private static IrCode toCode(Map<String, String> b) {
        String type = b.getOrDefault("type", "");
        if (type.equals("raw")) {
            int freq = (int) Math.round(Double.parseDouble(b.getOrDefault("frequency", "38000")));
            String[] parts = b.getOrDefault("data", "").trim().split("\\s+");
            int[] p = new int[parts.length];
            for (int i = 0; i < parts.length; i++) p[i] = Integer.parseInt(parts[i]);
            return new IrCode(freq, p);
        }
        if (!type.equals("parsed")) throw new IllegalArgumentException("unknown type '" + type + "'");
        String protocol = b.getOrDefault("protocol", "");
        int address = littleEndian(b.getOrDefault("address", "00 00 00 00"));
        int command = littleEndian(b.getOrDefault("command", "00 00 00 00"));
        switch (protocol) {
            case "NEC": return NecEncoder.nec(address, command);
            case "NECext": return NecEncoder.necExt(address, command);
            case "Samsung32": return NecEncoder.samsung32(address, command);
            default: throw new IllegalArgumentException("protocol " + protocol + " not supported yet");
        }
    }

    /** Flipper writes values as little-endian hex bytes: "07 00 00 00" = 0x07. */
    static int littleEndian(String hexBytes) {
        String[] parts = hexBytes.trim().split("\\s+");
        int v = 0;
        for (int i = parts.length - 1; i >= 0; i--) v = (v << 8) | Integer.parseInt(parts[i], 16);
        return v;
    }
}
