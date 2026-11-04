package com.arrl.radiocraft.datagen;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Names for the settings in NeoForge's config screen (the mod list's Config button), made from their keys: without one
 * the screen shows the raw key ({@code antenna_update_delay}). {@code hf_frequency_step} becomes "HF Frequency Step",
 * a band's {@code 10mm_los} "Line of Sight Range", and a section's "( * = Restart game to take effect)" is left off.
 * Their comments stay as the tooltips. No game classes, so it can be unit tested.
 */
public final class ConfigNames {

	/** Words written in capitals, or their own way. */
	private static final Map<String, String> WORDS = Map.ofEntries(
			Map.entry("hf", "HF"), Map.entry("vhf", "VHF"), Map.entry("uhf", "UHF"), Map.entry("cw", "CW"), Map.entry("swr", "SWR"),
			Map.entry("tnc", "TNC"), Map.entry("aprs", "APRS"), Map.entry("pabx", "PABX"), Map.entry("fe", "FE"), Map.entry("rpm", "RPM"),
			Map.entry("snr", "SNR"), Map.entry("mhz", "MHz"), Map.entry("hz", "Hz"), Map.entry("db", "dB"), Map.entry("gb", "GB"),
			Map.entry("los", "LOS"), Map.entry("h19", "H19"), Map.entry("pa", "PA"), Map.entry("qrp", "QRP"), Map.entry("wefax", "WEFAX"),
			Map.entry("id", "ID"), Map.entry("e", "E"), Map.entry("z", "Z"), Map.entry("fax", "Fax"), Map.entry("mic", "Mic"));
	/** Small words, lower case but at the start. */
	private static final Set<String> SMALL = Set.of("a", "an", "and", "at", "by", "for", "in", "of", "on", "or", "per", "the", "to", "with");
	/** A band's settings ({@code <band>m_los} and so on; the section names the band). */
	private static final Map<String, String> BAND = Map.of(
			"los", "Line of Sight Range", "minSkipDay", "Shortest Skip (Day)", "maxSkipDay", "Longest Skip (Day)",
			"minSkipNight", "Shortest Skip (Night)", "maxSkipNight", "Longest Skip (Night)",
			"minFrequency", "Lowest Frequency (Hz)", "maxFrequency", "Highest Frequency (Hz)");

	private ConfigNames() {}

	/** A setting's name from its key. */
	public static String name(String key) {
		key = key.replaceFirst(" (integer|double)$", ""); // A few old keys carry their type.
		int band = key.indexOf("m_");
		if(band > 0 && BAND.containsKey(key.substring(band + 2)))
			return BAND.get(key.substring(band + 2));
		return words(key.replaceAll("([a-z])([A-Z])", "$1_$2").split("_"));
	}

	/** A section's name from its key: "Antenna Options ( * = Restart game to take effect)" as "Antenna Options". */
	public static String section(String key) {
		String name = key.replaceFirst("\\s*\\(\\s*\\*.*\\)\\s*$", "").trim();
		if(name.endsWith(" band settings, also known as VHF"))
			return name.replace(" band settings, also known as VHF", " Band (VHF)");
		if(name.endsWith(" band settings"))
			return name.replace(" band settings", " Band");
		return name;
	}

	private static String words(String[] parts) {
		StringBuilder out = new StringBuilder();
		for(String part : parts) {
			if(part.isEmpty())
				continue;
			String lower = part.toLowerCase(Locale.ROOT);
			String word;
			if(WORDS.containsKey(lower))
				word = WORDS.get(lower);
			else if(!out.isEmpty() && SMALL.contains(lower))
				word = lower;
			else if(Character.isDigit(part.charAt(0)))
				word = lower; // 10m, 256.
			else
				word = Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
			out.append(out.isEmpty() ? "" : " ").append(word);
		}
		return out.toString();
	}
}
