package com.arrl.radiocraft.common.telephone;

import java.util.Locale;

/**
 * The repeater autopatch's DTMF control (maintainer, 2026-11-04), with no game state: the keys its repeater hears, one
 * at a time, and what each one asks for. Idle, it watches for its activation code (the longest end of what came in that
 * starts the code, as the DTMF decoder does). Once the whole code is in it is armed: digits collect into a number, '*'
 * dials it and '#' cancels, and {@link #timeoutTicks} with no key cancels too. During a call only '#' counts: it hangs
 * up. An empty code never arms.
 */
public final class AutopatchControl {

	/** The longest activation code, keys. */
	public static final int MAX_CODE = 8;
	/** The longest number it collects: a master number and a five digit extension, with room to spare. */
	public static final int MAX_NUMBER = 12;
	/** Keys an activation code may have: digits and A-D (not '*' or '#', which dial and cancel). */
	public static final String CODE_KEYS = "0123456789ABCD";

	public enum Action {
		/** Nothing to do. */
		NONE,
		/** The activation code came in: it is armed and collecting digits. */
		ARMED,
		/** A digit was added to the number. */
		DIGIT,
		/** '*': dial {@link #getNumber()}. It is no longer armed. */
		DIAL,
		/** '#', or {@code *} with no number: armed no longer, nothing dialed. */
		CANCEL,
		/** No key for {@link #timeoutTicks} while armed: armed no longer, nothing dialed. */
		TIMEOUT,
		/** '#' during a call: hang up. */
		HANG_UP
	}

	private final int timeoutTicks;
	private String code = "";
	private String partial = "";
	private boolean armed = false;
	private String number = "";
	private long lastKey = 0L;

	public AutopatchControl(int timeoutTicks) {
		this.timeoutTicks = timeoutTicks;
	}

	/** {@code text} as an activation code: upper case, only {@link #CODE_KEYS}, at most {@link #MAX_CODE} keys. */
	public static String sanitizeCode(String text) {
		StringBuilder out = new StringBuilder();
		if(text != null)
			for(char c : text.toUpperCase(Locale.ROOT).toCharArray())
				if(CODE_KEYS.indexOf(c) >= 0 && out.length() < MAX_CODE)
					out.append(c);
		return out.toString();
	}

	/** True if {@code text} is a code as typed: only {@link #CODE_KEYS} (either case), at most {@link #MAX_CODE}. */
	public static boolean validCode(String text) {
		return text.length() <= MAX_CODE && sanitizeCode(text).length() == text.length();
	}

	/** Sets the activation code ({@link #sanitizeCode}), forgetting any part of the old one and disarming. */
	public void setCode(String code) {
		this.code = sanitizeCode(code);
		reset();
	}

	public String getCode() {
		return code;
	}

	/** Forgets everything heard: not armed, no number, no part of the code. */
	public void reset() {
		partial = "";
		armed = false;
		number = "";
	}

	public boolean isArmed() {
		return armed;
	}

	/** The number collected so far while armed; after {@link Action#DIAL}, the number to dial. */
	public String getNumber() {
		return number;
	}

	/** How much of the activation code has come in so far. */
	public String getPartial() {
		return partial;
	}

	/** A key its repeater heard at game time {@code now}, while a call is up ({@code inCall}) or not. */
	public Action key(char key, long now, boolean inCall) {
		key = Character.toUpperCase(key);
		lastKey = now;
		if(inCall) {
			reset();
			return key == '#' ? Action.HANG_UP : Action.NONE;
		}
		if(armed) {
			if(key == '#') {
				reset();
				return Action.CANCEL;
			}
			if(key == '*') {
				if(number.isEmpty()) {
					reset();
					return Action.CANCEL;
				}
				armed = false;
				partial = "";
				return Action.DIAL;
			}
			if(key >= '0' && key <= '9' && number.length() < MAX_NUMBER) {
				number = number + key;
				return Action.DIGIT;
			}
			return Action.NONE; // A-D mean nothing here.
		}
		if(code.isEmpty())
			return Action.NONE;
		partial = partial + key;
		while(!partial.isEmpty() && !code.startsWith(partial)) // The longest end of what came in that starts the code.
			partial = partial.substring(1);
		if(!partial.equals(code))
			return Action.NONE;
		partial = "";
		armed = true;
		number = "";
		return Action.ARMED;
	}

	/** Once a tick: armed with no key for {@link #timeoutTicks} gives up; a part of the code goes stale the same way. */
	public Action tick(long now) {
		if(now - lastKey <= timeoutTicks)
			return Action.NONE;
		if(armed) {
			reset();
			return Action.TIMEOUT;
		}
		partial = "";
		return Action.NONE;
	}
}
