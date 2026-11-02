package com.arrl.radiocraft.common.radio.voice;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReceiverMixTest {

    @Test
    void fmGetsNoisierNotQuieter() {
        assertEquals(0.0D, ReceiverMix.fmHiss(0.8D), 1e-9, "strong signal: full quieting");
        assertTrue(ReceiverMix.fmHiss(0.05D) > ReceiverMix.fmHiss(0.2D), "weaker is noisier");
        assertEquals(0.0D, ReceiverMix.fmDropout(0.2D), 1e-9, "a fair signal holds");
        assertTrue(ReceiverMix.fmDropout(0.08D) > 0.0D && ReceiverMix.fmDropout(0.08D) < 1.0D, "a weak one cuts in and out");
        assertEquals(1.0D, ReceiverMix.fmDropout(0.02D), 1e-9, "at the edge: only static");
    }

    @Test
    void fmFadeKeepsTheVoiceLevel() {
        RadioVoiceProcessor processor = new RadioVoiceProcessor();
        short[] voice = new short[960];
        java.util.Arrays.fill(voice, (short)8000);
        assertEquals(8000, processor.fmFade(voice.clone(), 0.9D, 1.0D)[480], "a strong signal passes untouched");
        short[] edge = processor.fmFade(new short[960], 0.01D, 1.0D);
        long energy = 0;
        for(short sample : edge)
            energy += Math.abs(sample);
        assertTrue(energy > 0, "past the dropouts there is static");
    }

    @Test
    void aWhipBesideYouBeatsAFullAntenna20BlocksOff() {
        double whip = 0.5D * 0.997D * ReceiverMix.nearness(1.0D), module = 0.933D * ReceiverMix.nearness(20.0D);
        assertTrue(whip > module, "the manpack beside the radio " + whip + " against the module " + module);
    }

    @Test
    void desenseScalesWithDistanceAndSeparation() {
        assertEquals(0.25D, ReceiverMix.desense(0.0D, 0.0D, 5.0D, 2e6D, 0.75D), 1e-9, "beside it, on frequency");
        assertEquals(1.0D, ReceiverMix.desense(5.0D, 0.0D, 5.0D, 2e6D, 0.75D), 1e-9, "at the range");
        assertEquals(1.0D, ReceiverMix.desense(1.0D, 2e6D, 5.0D, 2e6D, 0.75D), 1e-9, "2 MHz off");
        double near = ReceiverMix.desense(1.0D, 500e3D, 5.0D, 2e6D, 0.75D), far = ReceiverMix.desense(1.0D, 1500e3D, 5.0D, 2e6D, 0.75D);
        assertTrue(near < far && far < 1.0D, near + " then " + far);
        assertEquals(near, ReceiverMix.desense(1.0D, -500e3D, 5.0D, 2e6D, 0.75D), 1e-9, "above or below alike");
    }

    @Test
    void onlyATransmitterRightBesideItOverloads() {
        assertTrue(ReceiverMix.overload(1.0D) > 0.0D && ReceiverMix.overload(1.0D) <= ReceiverMix.OVERLOAD_SPLATTER);
        assertEquals(0.0D, ReceiverMix.overload(4.0D), 1e-9);
        assertEquals(0.0D, ReceiverMix.overload(20.0D), 1e-9);
    }

    @Test
    void ssbStaticDucksUnderAStrongSignal() {
        assertEquals(1.0D, ReceiverMix.noiseDuck(0.0D, 0.3D), 1e-9);
        assertTrue(ReceiverMix.noiseDuck(0.9D, 0.3D) < 0.4D);
    }
}
