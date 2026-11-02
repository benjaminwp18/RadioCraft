package com.arrl.radiocraft.common.datacomponents;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * What is on a tape reel, and how it looks. All of these string parameters use "" as the non-value, NOT null.
 *
 * @param colorway  0 to {@link #COLORWAYS} - 1: the reel's colour and its label's (see TapeItem)
 * @param track     a server tape it was found with, by its path in the tape folder ("numbers/station_a.ogg"; see TapeLibrary); or empty
 * @param uuid      a UUID for this player recording (not the UUID of the player; see TapeRecordings); or empty
 * @param name      an extra display name, ignored for server tape tracks; or empty
 * @param seconds   how long the recording is, for the tooltip (0 if not known)
 */
public record TapeContents(int colorway, @NotNull String track, @NotNull String uuid, @NotNull String name, int seconds) {

	public static final int REEL_COLOURS = 12;
	public static final int LABEL_COLOURS = 5;
	public static final int COLORWAYS = REEL_COLOURS * LABEL_COLOURS;

	public static final TapeContents BLANK = new TapeContents(0, "", "",  "", 0);

	public static final Codec<TapeContents> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.INT.optionalFieldOf("colorway", 0).forGetter(TapeContents::colorway),
			Codec.STRING.optionalFieldOf("track", "").forGetter(TapeContents::track),
			Codec.STRING.optionalFieldOf("uuid", "").forGetter(TapeContents::uuid),
			Codec.STRING.optionalFieldOf("name", "").forGetter(TapeContents::name),
			Codec.INT.optionalFieldOf("seconds", 0).forGetter(TapeContents::seconds)
	).apply(instance, TapeContents::new));

	public static final StreamCodec<ByteBuf, TapeContents> STREAM_CODEC = ByteBufCodecs.fromCodec(CODEC);

	public boolean isBlank() {
		return track.isEmpty() && uuid.isEmpty();
	}

	public boolean isFound() {
		return !track.isEmpty();
	}

	public boolean isRecorded() {
		return !uuid.isEmpty();
	}

	/** The recording's id, or null if this is not a recorded tape (or the id is broken). */
	public UUID recordingUUID() {
		try {
			return uuid.isEmpty() ? null : UUID.fromString(uuid);
		}
		catch(IllegalArgumentException e) {
			return null;
		}
	}

	public TapeContents withColorway(int value) {
		return new TapeContents(Math.floorMod(value, COLORWAYS), track, uuid, name, seconds);
	}

	public TapeContents withRecording(@NotNull UUID id, @NotNull String name, int length) {
		return new TapeContents(colorway, "", id.toString(), name, length);
	}
}
