package com.arrl.radiocraft.common.telephone;

import com.arrl.radiocraft.common.telephone.AutopatchControl.Action;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The repeater autopatch's DTMF control: arming on its code, collecting a number, dialling, cancelling, timing out. */
class AutopatchControlTest {

    private static final int TIMEOUT = 200;

    private static AutopatchControl control(String code) {
        AutopatchControl control = new AutopatchControl(TIMEOUT);
        control.setCode(code);
        return control;
    }

    /** Keys {@code keys} one a tick from {@code from}, idle; returns the last key's action. */
    private static Action keys(AutopatchControl control, String keys, long from) {
        Action last = Action.NONE;
        for(int i = 0; i < keys.length(); i++)
            last = control.key(keys.charAt(i), from + i, false);
        return last;
    }

    @Test
    void codeArmsIt() {
        AutopatchControl control = control("A12");
        assertEquals(Action.NONE, control.key('A', 0, false));
        assertEquals(Action.NONE, control.key('1', 1, false));
        assertEquals("A1", control.getPartial());
        assertEquals(Action.ARMED, control.key('2', 2, false));
        assertTrue(control.isArmed());
        assertEquals("", control.getPartial());
    }

    @Test
    void codeFoundInTheMiddleOfOtherKeys() {
        AutopatchControl control = control("123");
        assertEquals(Action.ARMED, keys(control, "9121123", 0), "the longest end that starts the code is kept");
    }

    @Test
    void lowerCaseKeysCount() {
        assertEquals(Action.ARMED, keys(control("ab"), "ab", 0));
    }

    @Test
    void emptyCodeNeverArms() {
        AutopatchControl control = control("");
        assertEquals(Action.NONE, keys(control, "1234*#ABCD", 0));
        assertFalse(control.isArmed());
    }

    @Test
    void starDialsTheNumber() {
        AutopatchControl control = control("1");
        control.key('1', 0, false);
        assertEquals(Action.DIGIT, control.key('6', 1, false));
        assertEquals(Action.DIGIT, control.key('0', 2, false));
        assertEquals(Action.NONE, control.key('A', 3, false), "A-D are no digits");
        assertEquals(Action.DIGIT, control.key('3', 4, false));
        assertEquals("603", control.getNumber());
        assertEquals(Action.DIAL, control.key('*', 5, false));
        assertEquals("603", control.getNumber());
        assertFalse(control.isArmed());
    }

    @Test
    void poundCancels() {
        AutopatchControl control = control("1");
        keys(control, "155", 0);
        assertEquals(Action.CANCEL, control.key('#', 3, false));
        assertFalse(control.isArmed());
        assertEquals("", control.getNumber());
    }

    @Test
    void starWithNoNumberCancels() {
        AutopatchControl control = control("1");
        control.key('1', 0, false);
        assertEquals(Action.CANCEL, control.key('*', 1, false));
        assertFalse(control.isArmed());
    }

    @Test
    void numberHasALimit() {
        AutopatchControl control = control("1");
        control.key('1', 0, false);
        for(int i = 0; i < AutopatchControl.MAX_NUMBER + 3; i++)
            control.key('5', 1 + i, false);
        assertEquals(AutopatchControl.MAX_NUMBER, control.getNumber().length());
    }

    @Test
    void silenceTimesOut() {
        AutopatchControl control = control("1");
        control.key('1', 100, false);
        control.key('2', 150, false);
        assertEquals(Action.NONE, control.tick(150 + TIMEOUT), "not yet");
        assertEquals(Action.TIMEOUT, control.tick(151 + TIMEOUT));
        assertFalse(control.isArmed());
        assertEquals("", control.getNumber());
        assertEquals(Action.NONE, control.tick(400 + TIMEOUT), "only once");
    }

    @Test
    void halfACodeGoesStale() {
        AutopatchControl control = control("12");
        control.key('1', 0, false);
        control.tick(TIMEOUT + 1);
        assertEquals("", control.getPartial());
        assertEquals(Action.NONE, control.key('2', TIMEOUT + 2, false), "the 1 was forgotten");
    }

    @Test
    void duringACallOnlyPoundCounts() {
        AutopatchControl control = control("1");
        assertEquals(Action.NONE, control.key('1', 0, true), "the code does not arm it during a call");
        assertFalse(control.isArmed());
        assertEquals(Action.NONE, control.key('*', 1, true));
        assertEquals(Action.HANG_UP, control.key('#', 2, true));
    }

    @Test
    void codesAreSanitized() {
        assertEquals("12AB", AutopatchControl.sanitizeCode("1*2#ab"));
        assertEquals("12345678", AutopatchControl.sanitizeCode("123456789"));
        assertTrue(AutopatchControl.validCode(""));
        assertTrue(AutopatchControl.validCode("12ab"));
        assertFalse(AutopatchControl.validCode("12*"));
        assertFalse(AutopatchControl.validCode("123456789"));
    }

    @Test
    void newCodeDisarms() {
        AutopatchControl control = control("1");
        control.key('1', 0, false);
        control.setCode("2");
        assertFalse(control.isArmed());
        assertEquals("2", control.getCode());
    }
}
