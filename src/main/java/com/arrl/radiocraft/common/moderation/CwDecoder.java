package com.arrl.radiocraft.common.moderation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads Morse from CW key buffers (20 ticks of key state each, numbered) for the moderation log's text. Best effort,
 * as a person copying would: the shortest key-down is a dot, anything twice that a dash; a gap of three dots ends a
 * letter and of six a word. A whole transmission of dashes only reads as dots (nothing to compare them with). Pure.
 */
public final class CwDecoder {

	private CwDecoder() {}

	/** Ticks of key-up put in for each buffer missing from the numbering (a pause), at most {@link #MAX_MISSING} of them. */
	private static final int MISSING_TICKS = 20;
	private static final int MAX_MISSING = 3;

	private static final Map<String, Character> CODE = new HashMap<>();

	static {
		String[] letters = { ".-", "-...", "-.-.", "-..", ".", "..-.", "--.", "....", "..", ".---", "-.-", ".-..", "--", "-.", "---", ".--.", "--.-", ".-.",
				"...", "-", "..-", "...-", ".--", "-..-", "-.--", "--.." };
		for(int i = 0; i < letters.length; i++)
			CODE.put(letters[i], (char)('A' + i));
		String[] digits = { "-----", ".----", "..---", "...--", "....-", ".....", "-....", "--...", "---..", "----." };
		for(int i = 0; i < digits.length; i++)
			CODE.put(digits[i], (char)('0' + i));
		CODE.put(".-.-.-", '.');
		CODE.put("--..--", ',');
		CODE.put("..--..", '?');
		CODE.put("-..-.", '/');
		CODE.put("-...-", '=');
		CODE.put(".-.-.", '+');
		CODE.put("-....-", '-');
	}

	/** The key state tick by tick of {@code buffers} (id, 20-bit mask), put in order of their numbers. */
	public static boolean[] timeline(List<TransmissionRecord.CwBuffer> buffers) {
		List<TransmissionRecord.CwBuffer> sorted = new ArrayList<>(buffers);
		sorted.sort(Comparator.comparingInt(TransmissionRecord.CwBuffer::id));
		List<Boolean> ticks = new ArrayList<>();
		Integer last = null;
		for(TransmissionRecord.CwBuffer buffer : sorted) {
			if(last != null) {
				if(buffer.id() == last)
					continue; // Sent twice.
				int missing = Math.min(MAX_MISSING, buffer.id() - last - 1);
				for(int i = 0; i < missing * MISSING_TICKS; i++)
					ticks.add(false);
			}
			last = buffer.id();
			for(int i = 0; i < 20; i++)
				ticks.add((buffer.mask() & (1 << i)) != 0);
		}
		boolean[] out = new boolean[ticks.size()];
		for(int i = 0; i < out.length; i++)
			out[i] = ticks.get(i);
		return out;
	}

	/** The text sent in {@code buffers}, letters A-Z and digits, '?' for a code it does not know. */
	public static String decode(List<TransmissionRecord.CwBuffer> buffers) {
		return decode(timeline(buffers));
	}

	/** The text in a tick-by-tick key timeline. */
	public static String decode(boolean[] ticks) {
		List<int[]> runs = new ArrayList<>(); // {down ? 1 : 0, length}
		for(boolean down : ticks) {
			int value = down ? 1 : 0;
			if(!runs.isEmpty() && runs.getLast()[0] == value)
				runs.getLast()[1]++;
			else
				runs.add(new int[] { value, 1 });
		}
		while(!runs.isEmpty() && runs.getFirst()[0] == 0)
			runs.removeFirst();
		while(!runs.isEmpty() && runs.getLast()[0] == 0)
			runs.removeLast();
		if(runs.isEmpty())
			return "";
		int unit = Integer.MAX_VALUE;
		for(int[] run : runs)
			if(run[0] == 1)
				unit = Math.min(unit, run[1]);
		StringBuilder text = new StringBuilder();
		StringBuilder letter = new StringBuilder();
		for(int[] run : runs) {
			if(run[0] == 1)
				letter.append(run[1] >= 2 * unit ? '-' : '.');
			else if(run[1] >= 2 * unit) { // Between letters, or words.
				flush(letter, text);
				if(run[1] >= 5 * unit)
					text.append(' ');
			}
		}
		flush(letter, text);
		return text.toString().trim();
	}

	private static void flush(StringBuilder letter, StringBuilder text) {
		if(letter.isEmpty())
			return;
		Character c = CODE.get(letter.toString());
		text.append(c == null ? '?' : c);
		letter.setLength(0);
	}
}
