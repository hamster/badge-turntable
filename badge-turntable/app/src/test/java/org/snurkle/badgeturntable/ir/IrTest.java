package org.snurkle.badgeturntable.ir;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.io.StringReader;
import java.util.List;
import java.util.Map;

public class IrTest {

    @Test
    public void necFrameLayout() {
        int[] p = NecEncoder.nec(0x07, 0x02).pattern();
        assertEquals(2 + 64 + 1, p.length);
        assertEquals(9000, p[0]);
        assertEquals(4500, p[1]);
        // Address 0x07, LSB first: 1,1,1,0,0,0,0,0
        int[] expectSpaces = {1687, 1687, 1687, 562, 562, 562, 562, 562};
        for (int b = 0; b < 8; b++) {
            assertEquals(562, p[2 + 2 * b]);
            assertEquals("bit " + b, expectSpaces[b], p[3 + 2 * b]);
        }
        // Inverted address 0xF8: 0,0,0,1,1,1,1,1
        assertEquals(562, p[3 + 2 * 8]);
        assertEquals(1687, p[3 + 2 * 11]);
        assertEquals(562, p[p.length - 1]);
    }

    @Test
    public void flipperLittleEndian() {
        assertEquals(0x07, FlipperIrParser.littleEndian("07 00 00 00"));
        assertEquals(0xBF40, FlipperIrParser.littleEndian("40 BF 00 00"));
    }

    @Test
    public void parsesFlipperFile() throws Exception {
        String file = "Filetype: IR signals file\nVersion: 1\n#\n"
                + "name: On_pause\ntype: parsed\nprotocol: NEC\naddress: 07 00 00 00\ncommand: 02 00 00 00\n#\n"
                + "name: 90\ntype: raw\nfrequency: 38000\nduty_cycle: 0.330000\ndata: 9024 4512 579 552 579\n#\n"
                + "name: Mystery\ntype: parsed\nprotocol: RC5\naddress: 00 00 00 00\ncommand: 0C 00 00 00\n";
        List<FlipperIrParser.Signal> s = FlipperIrParser.parse(new StringReader(file));
        assertEquals(3, s.size());
        assertEquals(NecEncoder.nec(7, 2), s.get(0).code);
        assertArrayEquals(new int[]{9024, 4512, 579, 552, 579}, s.get(1).code.pattern());
        assertNull(s.get(2).code);
        assertNotNull(s.get(2).unsupported);

        Map<TurntableProfile.Role, IrCode> roles = ButtonNames.map(s);
        assertEquals(s.get(0).code, roles.get(TurntableProfile.Role.START_STOP));
        assertEquals(s.get(1).code, roles.get(TurntableProfile.Role.STEP_90));
    }

    @Test
    public void codeRoundTrip() {
        IrCode c = NecEncoder.necExt(0xBF40, 0xE51A);
        assertEquals(c, IrCode.parse(c.serialize()));
    }
}
