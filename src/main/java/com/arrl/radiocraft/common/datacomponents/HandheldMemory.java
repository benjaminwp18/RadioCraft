package com.arrl.radiocraft.common.datacomponents;

import com.arrl.radiocraft.common.radio.voice.Ctcss;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * A handheld's memory channels (slots 0 to 9, one per number key) and whether it is in memory mode. In memory mode the
 * radio runs on {@code channel}'s settings; {@code vfo} holds the dial settings it had before, put back on leaving.
 *
 * @param channels   The stored channels, at most one per slot, in slot order.
 * @param memoryMode True while the radio runs on a memory channel instead of its dial (VFO).
 * @param channel    The memory channel in use (memory mode).
 * @param vfo        The VFO settings to go back to; null outside memory mode.
 * @param display    What the main display shows big in memory mode: the channel's name, its frequency or its number.
 */
public record HandheldMemory(List<Channel> channels, boolean memoryMode, int channel, @Nullable Channel vfo, Display display) {

	public static final int SLOTS = 10;
	/** Characters in a channel's name. */
	public static final int NAME_MAX = 7;

	/** What the main display shows big on a memory channel; the other one of name and frequency goes small above. */
	public enum Display {
		NAME, FREQUENCY, CHANNEL;

		public Display next() {
			return values()[(ordinal() + 1) % values().length];
		}

		static Display byName(String name) {
			try {
				return valueOf(name);
			}
			catch(IllegalArgumentException e) {
				return FREQUENCY;
			}
		}
	}

	/** One stored channel: frequency, TX and RX tones, repeater offset and a name (empty for none). */
	public record Channel(int slot, float frequency, float ctcssTone, float rxTone, int offsetHz, String name) {

		public Channel(int slot, float frequency, float ctcssTone, float rxTone, int offsetHz) {
			this(slot, frequency, ctcssTone, rxTone, offsetHz, "");
		}

		public static final Codec<Channel> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Codec.INT.fieldOf("slot").forGetter(Channel::slot),
				Codec.FLOAT.fieldOf("frequency").forGetter(Channel::frequency),
				Codec.FLOAT.optionalFieldOf("ctcssTone", 0.0f).forGetter(Channel::ctcssTone),
				Codec.FLOAT.optionalFieldOf("rxTone", 0.0f).forGetter(Channel::rxTone),
				Codec.INT.optionalFieldOf("offsetHz", 0).forGetter(Channel::offsetHz),
				Codec.STRING.optionalFieldOf("name", "").forGetter(Channel::name)
		).apply(instance, Channel::new));

		public static final StreamCodec<ByteBuf, Channel> STREAM_CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, Channel::slot,
				ByteBufCodecs.FLOAT, Channel::frequency,
				ByteBufCodecs.FLOAT, Channel::ctcssTone,
				ByteBufCodecs.FLOAT, Channel::rxTone,
				ByteBufCodecs.INT, Channel::offsetHz,
				ByteBufCodecs.stringUtf8(NAME_MAX * 4), Channel::name,
				Channel::new);

		/** The settings of {@code state} for {@code slot}. */
		public static Channel of(int slot, HandheldRadioState state) {
			return new Channel(slot, state.freq(), state.ctcssTone(), state.rxTone(), state.offsetHz());
		}

		/** This channel named {@code newName}. */
		public Channel named(String newName) {
			return new Channel(slot, frequency, ctcssTone, rxTone, offsetHz, newName);
		}

		/** This channel with its tones and offset made valid, its name cleaned and its slot set to {@code slot}. */
		Channel sanitized(int slot) {
			return new Channel(slot, frequency, Ctcss.sanitize(ctcssTone), Ctcss.sanitize(rxTone), Ctcss.sanitizeOffset(offsetHz), sanitizeName(name));
		}
	}

	/** Upper case printable ASCII, at most {@link #NAME_MAX} characters. */
	public static String sanitizeName(String name) {
		StringBuilder out = new StringBuilder();
		for(char c : name.toUpperCase(java.util.Locale.ROOT).strip().toCharArray()) {
			if(c >= 32 && c < 127)
				out.append(c);
			if(out.length() >= NAME_MAX)
				break;
		}
		return out.toString().strip();
	}

	public static final HandheldMemory EMPTY = new HandheldMemory(List.of(), false, 0, null, Display.FREQUENCY);

	public static final Codec<HandheldMemory> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Channel.CODEC.listOf().optionalFieldOf("channels", List.of()).forGetter(HandheldMemory::channels),
			Codec.BOOL.optionalFieldOf("memoryMode", false).forGetter(HandheldMemory::memoryMode),
			Codec.INT.optionalFieldOf("channel", 0).forGetter(HandheldMemory::channel),
			Channel.CODEC.optionalFieldOf("vfo").forGetter(memory -> Optional.ofNullable(memory.vfo())),
			Codec.STRING.xmap(Display::byName, Display::name).optionalFieldOf("display", Display.FREQUENCY).forGetter(HandheldMemory::display)
	).apply(instance, (channels, memoryMode, channel, vfo, display) -> new HandheldMemory(channels, memoryMode, channel, vfo.orElse(null), display).sanitized()));

	public static final StreamCodec<ByteBuf, HandheldMemory> STREAM_CODEC = StreamCodec.composite(
			Channel.STREAM_CODEC.apply(ByteBufCodecs.list(SLOTS)), HandheldMemory::channels,
			ByteBufCodecs.BOOL, HandheldMemory::memoryMode,
			ByteBufCodecs.VAR_INT, HandheldMemory::channel,
			ByteBufCodecs.optional(Channel.STREAM_CODEC), memory -> Optional.ofNullable(memory.vfo()),
			ByteBufCodecs.VAR_INT.map(i -> Display.values()[Math.floorMod(i, Display.values().length)], Display::ordinal), HandheldMemory::display,
			(channels, memoryMode, channel, vfo, display) -> new HandheldMemory(channels, memoryMode, channel, vfo.orElse(null), display).sanitized());

	/** The channel stored in {@code slot}, or null if it is unused. */
	@Nullable
	public Channel get(int slot) {
		for(Channel stored : channels)
			if(stored.slot() == slot)
				return stored;
		return null;
	}

	/** This memory with {@code stored} written to its slot (replacing what was there). */
	public HandheldMemory store(Channel stored) {
		List<Channel> out = new ArrayList<>(channels);
		out.removeIf(old -> old.slot() == stored.slot());
		out.add(stored);
		return new HandheldMemory(out, memoryMode, channel, vfo, display).sanitized();
	}

	/** The used slot {@code steps} after (or before, if negative) {@code from}, wrapping round; {@code from} if none is used. */
	public int stepChannel(int from, int steps) {
		if(channels.isEmpty())
			return from;
		List<Integer> used = channels.stream().map(Channel::slot).toList();
		int at = used.indexOf(from);
		if(at < 0) { // Not on a used slot: the first used one past it.
			for(int i = 0; i < used.size(); i++)
				if(used.get(i) > from) {
					at = i - (steps > 0 ? 1 : 0);
					break;
				}
			if(at < 0)
				at = steps > 0 ? used.size() - 1 : 0;
		}
		return used.get(Math.floorMod(at + steps, used.size()));
	}

	/** Into memory mode on {@code slot}, remembering the dial settings {@code dial} to go back to. */
	public HandheldMemory enterMemory(int slot, Channel dial) {
		return new HandheldMemory(channels, true, slot, dial, display);
	}

	/** On channel {@code slot} (memory mode). */
	public HandheldMemory withChannel(int slot) {
		return new HandheldMemory(channels, memoryMode, slot, vfo, display);
	}

	/** Showing {@code shown} big on memory channels. */
	public HandheldMemory withDisplay(Display shown) {
		return new HandheldMemory(channels, memoryMode, channel, vfo, shown);
	}

	/** Back to the dial (VFO). */
	public HandheldMemory leaveMemory() {
		return new HandheldMemory(channels, false, channel, null, display);
	}

	/** Slots inside 0 to 9, one channel per slot, in slot order, valid tones; the channel in use inside 0 to 9. */
	public HandheldMemory sanitized() {
		List<Channel> out = new ArrayList<>();
		for(Channel stored : channels)
			if(stored.slot() >= 0 && stored.slot() < SLOTS && out.stream().noneMatch(other -> other.slot() == stored.slot()))
				out.add(stored.sanitized(stored.slot()));
		out.sort(Comparator.comparingInt(Channel::slot));
		return new HandheldMemory(List.copyOf(out), memoryMode, Math.max(0, Math.min(SLOTS - 1, channel)), vfo == null ? null : vfo.sanitized(0), display);
	}
}
