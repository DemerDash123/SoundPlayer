package com.demerdash.soundplayer.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class EqPresetsTest {

    @Test
    public void presetTableIsConsistent() {
        assertEquals(EqPresets.KEYS.length, EqPresets.GAINS.length);
        for (float[] g : EqPresets.GAINS) {
            assertEquals(10, g.length);
            for (float v : g) assertTrue(Math.abs(v) <= EqPresets.MAX_DB);
        }
        assertEquals(0, EqPresets.indexOf("flat"));
        assertEquals(-1, EqPresets.indexOf("custom"));
    }

    @Test
    public void interpolatesInLogFrequency() {
        float[] g = {0, 10, 0, 0, 0, 0, 0, 0, 0, 0};
        assertEquals(0f, EqPresets.gainAt(EqPresets.FREQS, g, 31), 1e-4);
        assertEquals(10f, EqPresets.gainAt(EqPresets.FREQS, g, 62), 1e-4);
        assertEquals(5f, EqPresets.gainAt(EqPresets.FREQS, g, (float) Math.sqrt(31 * 62)), 1e-3);
        assertEquals(0f, EqPresets.gainAt(EqPresets.FREQS, g, 10), 1e-4);
        assertEquals(0f, EqPresets.gainAt(EqPresets.FREQS, g, 30000), 1e-4);
    }

    @Test
    public void cutoffsAreIncreasing() {
        float[] c = EqPresets.cutoffs();
        for (int i = 0; i < c.length; i++) {
            assertTrue(c[i] > EqPresets.FREQS[i]);
            if (i > 0) assertTrue(c[i] > c[i - 1]);
        }
    }

    @Test
    public void serializeRoundTripAndClamp() {
        float[] g = {1.5f, -2f, 0, 0, 0, 0, 0, 0, 0, 30f};
        float[] r = EqPresets.parse(EqPresets.serialize(g), 10);
        assertEquals(1.5f, r[0], 1e-6);
        assertEquals(-2f, r[1], 1e-6);
        assertEquals(EqPresets.MAX_DB, r[9], 1e-6);
        assertEquals(0f, EqPresets.parse("garbage,,", 10)[0], 1e-6);
    }
}
