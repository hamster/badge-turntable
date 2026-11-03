package org.snurkle.badgeturntable.ir;

/**
 * Encoders for the common consumer IR protocols, matching Flipper Zero's
 * "parsed" signal types (NEC, NECext, Samsung32). Bits are sent LSB first.
 */
public final class NecEncoder {
    public static final int CARRIER_HZ = 38_000;

    private static final int NEC_HDR_MARK = 9000, NEC_HDR_SPACE = 4500;
    private static final int SAMSUNG_HDR_MARK = 4500, SAMSUNG_HDR_SPACE = 4500;
    private static final int BIT_MARK = 562, ONE_SPACE = 1687, ZERO_SPACE = 562;

    private NecEncoder() {}

    /** NEC: 8-bit address and command, each followed by its inverse. */
    public static IrCode nec(int address, int command) {
        int a = address & 0xFF, c = command & 0xFF;
        return frame(NEC_HDR_MARK, NEC_HDR_SPACE, a | ((~a & 0xFF) << 8), c | ((~c & 0xFF) << 8));
    }

    /** NEC extended: 16-bit address, 16-bit command (as Flipper stores them). */
    public static IrCode necExt(int address, int command) {
        return frame(NEC_HDR_MARK, NEC_HDR_SPACE, address & 0xFFFF, command & 0xFFFF);
    }

    /** Samsung32: 8-bit address sent twice, then command and its inverse. */
    public static IrCode samsung32(int address, int command) {
        int a = address & 0xFF, c = command & 0xFF;
        return frame(SAMSUNG_HDR_MARK, SAMSUNG_HDR_SPACE, a | (a << 8), c | ((~c & 0xFF) << 8));
    }

    private static IrCode frame(int hdrMark, int hdrSpace, int first16, int second16) {
        int[] p = new int[2 + 64 + 1];
        int i = 0;
        p[i++] = hdrMark;
        p[i++] = hdrSpace;
        long bits = (first16 & 0xFFFFL) | ((second16 & 0xFFFFL) << 16);
        for (int b = 0; b < 32; b++) {
            p[i++] = BIT_MARK;
            p[i++] = ((bits >> b) & 1) == 1 ? ONE_SPACE : ZERO_SPACE;
        }
        p[i] = BIT_MARK;  // stop bit
        return new IrCode(CARRIER_HZ, p);
    }
}
