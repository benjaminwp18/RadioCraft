package com.arrl.radiocraft.common.moderation;

import java.util.Locale;

/**
 * What a logged transmission was, and so what the moderation log keeps of it (maintainer's design, 2026-11-04):
 * player voice as the Opus packets that arrived, data as data, and notes only for what is already stored elsewhere or
 * is the same every time.
 */
public enum TransmissionKind {
	/** A player talking into a radio (handheld, block radio or hand mic, manpack). Audio: the Opus packets as received. */
	PLAYER_VOICE(Store.AUDIO),
	/** Plain Simple Voice Chat, no radio (moderation_record_proximity). Audio: the Opus packets as received. */
	PROXIMITY(Store.AUDIO),
	/** A phone caller on the air through a repeater autopatch. Audio: encoded by the writer thread. */
	AUTOPATCH(Store.AUDIO),
	/** Any other sound a block put on the air that has no Opus form. Audio: encoded by the writer thread. */
	BLOCK_AUDIO(Store.AUDIO),
	/** A player's tape played over the air. A pointer to the tape's recording, copied only if it changes. */
	TAPE(Store.POINTER),
	/** A server tape (from the tape folder) played over the air. Note: which tape, where, when. */
	SERVER_TAPE(Store.NOTE),
	/** A tape recorded on a tape player. Note: who spoke onto it, where, when, the recording's id. */
	TAPE_RECORDING(Store.NOTE),
	/** A commercial radio module's program, kept only when someone heard it. Note: what played, where, when. */
	COMMERCIAL(Store.NOTE),
	/** A repeater passing on a transmission already logged at its source. Note, linked to the source's id. */
	REPEATED(Store.NOTE),
	/** A parrot controller echoing what its radio heard. Note: the original was logged at its source. */
	PARROT(Store.NOTE),
	/** DTMF keys. Note: the keys, when known. */
	DTMF(Store.NOTE),
	/** Morse: the key timings and the decoded text. */
	CW(Store.DATA),
	/** RTTY, APRS, packet, fax: the packets' saved records. */
	DATA(Store.DATA);

	/** How a kind is kept. */
	public enum Store {
		/** Opus audio in the hourly files (unless moderation_metadata_only). */
		AUDIO,
		/** A pointer to audio stored elsewhere (a tape recording). */
		POINTER,
		/** Data records kept in the index line (CW timings, packets). */
		DATA,
		/** Only the metadata and a line of text. */
		NOTE
	}

	private final Store store;

	TransmissionKind(Store store) {
		this.store = store;
	}

	public Store store() {
		return store;
	}

	/**
	 * True for the world's own stations rather than players: commercial radio modules, server tapes, repeaters passing
	 * a transmission on and parrot echoes. The Live tab hides them unless asked.
	 */
	public boolean isSystem() {
		return this == COMMERCIAL || this == SERVER_TAPE || this == REPEATED || this == PARROT;
	}

	/** True if audio frames for this kind go into the hourly files. */
	public boolean storesAudio() {
		return store == Store.AUDIO;
	}

	/** Lower case name, as written to the index. */
	public String id() {
		return name().toLowerCase(Locale.ROOT);
	}

	/** The kind named {@code id} (any case), or {@link #BLOCK_AUDIO} for an unknown one. */
	public static TransmissionKind byId(String id) {
		try {
			return valueOf(id.toUpperCase(Locale.ROOT));
		}
		catch(IllegalArgumentException e) {
			return BLOCK_AUDIO;
		}
	}
}
