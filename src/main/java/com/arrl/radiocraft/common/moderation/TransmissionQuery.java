package com.arrl.radiocraft.common.moderation;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * A filter on the moderation log's transmissions, for {@link ModerationLog#query}: every field left at its default
 * matches everything. Build one with {@link #all()} and the {@code with...} methods, e.g.
 * {@code TransmissionQuery.all().withPlayer(uuid, PlayerRole.EITHER).withTime(from, to).withPage(0, 50)}.
 *
 * @param player       a player who spoke in it or heard it (see {@code role}); null for anyone
 * @param role         whether {@code player} must be the talker, a hearer, or either
 * @param dimension    dimension id; null for any
 * @param radioPos     the transmitting or a receiving radio at this position; null for any
 * @param minHz        lowest frequency, Hz (inclusive)
 * @param maxHz        highest frequency, Hz (inclusive)
 * @param fromMs       started at or after, epoch ms
 * @param toMs         started before, epoch ms
 * @param kinds        the kinds wanted; empty for all but {@link TransmissionKind#PROXIMITY}, which only a query naming it
 *                     returns (the Proximity tab is separate)
 * @param text         words in its text, talker names or callsigns (any case); empty for any
 * @param flaggedOnly  only flagged ones
 * @param offset       how many matches to skip (paging)
 * @param limit        most matches to return
 */
public record TransmissionQuery(@Nullable UUID player, PlayerRole role, @Nullable String dimension, @Nullable BlockPos radioPos, float minHz, float maxHz,
		long fromMs, long toMs, Set<TransmissionKind> kinds, String text, boolean flaggedOnly, int offset, int limit) {

	/** How a player in a query takes part in a transmission. */
	public enum PlayerRole { TALKER, HEARER, EITHER }

	/** Everything, newest first, 100 at a time. */
	public static TransmissionQuery all() {
		return new TransmissionQuery(null, PlayerRole.EITHER, null, null, 0.0F, Float.MAX_VALUE, 0L, Long.MAX_VALUE, Set.of(), "", false, 0, 100);
	}

	public TransmissionQuery withPlayer(@Nullable UUID value, PlayerRole how) {
		return new TransmissionQuery(value, how, dimension, radioPos, minHz, maxHz, fromMs, toMs, kinds, text, flaggedOnly, offset, limit);
	}

	public TransmissionQuery withRadio(@Nullable String dim, @Nullable BlockPos pos) {
		return new TransmissionQuery(player, role, dim, pos, minHz, maxHz, fromMs, toMs, kinds, text, flaggedOnly, offset, limit);
	}

	public TransmissionQuery withDimension(@Nullable String dim) {
		return new TransmissionQuery(player, role, dim, radioPos, minHz, maxHz, fromMs, toMs, kinds, text, flaggedOnly, offset, limit);
	}

	public TransmissionQuery withFrequency(float lowHz, float highHz) {
		return new TransmissionQuery(player, role, dimension, radioPos, lowHz, highHz, fromMs, toMs, kinds, text, flaggedOnly, offset, limit);
	}

	public TransmissionQuery withTime(long from, long to) {
		return new TransmissionQuery(player, role, dimension, radioPos, minHz, maxHz, from, to, kinds, text, flaggedOnly, offset, limit);
	}

	public TransmissionQuery withKinds(TransmissionKind first, TransmissionKind... rest) {
		return new TransmissionQuery(player, role, dimension, radioPos, minHz, maxHz, fromMs, toMs, Set.copyOf(EnumSet.of(first, rest)), text, flaggedOnly, offset, limit);
	}

	public TransmissionQuery withText(String words) {
		return new TransmissionQuery(player, role, dimension, radioPos, minHz, maxHz, fromMs, toMs, kinds, words, flaggedOnly, offset, limit);
	}

	public TransmissionQuery withFlaggedOnly(boolean value) {
		return new TransmissionQuery(player, role, dimension, radioPos, minHz, maxHz, fromMs, toMs, kinds, text, value, offset, limit);
	}

	public TransmissionQuery withPage(int skip, int count) {
		return new TransmissionQuery(player, role, dimension, radioPos, minHz, maxHz, fromMs, toMs, kinds, text, flaggedOnly, Math.max(0, skip), Math.max(1, count));
	}

	/** True if {@code record} passes every filter (paging aside). */
	public boolean matches(TransmissionRecord record) {
		if(kinds.isEmpty() ? record.kind() == TransmissionKind.PROXIMITY : !kinds.contains(record.kind()))
			return false;
		if(record.startMs() < fromMs || record.startMs() >= toMs)
			return false;
		if(flaggedOnly && !record.flagged())
			return false;
		if(record.kind() != TransmissionKind.PROXIMITY && (record.frequency() < minHz || record.frequency() > maxHz))
			return false;
		if(dimension != null && !dimension.equals(record.dimension()))
			return false;
		if(player != null) {
			boolean talked = record.spokenBy(player), heard = record.heardBy(player);
			boolean ok = switch(role) {
				case TALKER -> talked;
				case HEARER -> heard;
				case EITHER -> talked || heard;
			};
			if(!ok)
				return false;
		}
		if(radioPos != null && !radioPos.equals(record.radioPos())) {
			boolean heardThere = false;
			for(TransmissionRecord.Hearer hearer : record.hearers())
				heardThere |= radioPos.equals(hearer.pos());
			if(!heardThere)
				return false;
		}
		if(!text.isBlank()) {
			String wanted = text.toLowerCase(Locale.ROOT);
			StringBuilder hay = new StringBuilder(record.text()).append(' ').append(record.radioKind());
			for(TransmissionRecord.Talker talker : record.talkers())
				hay.append(' ').append(talker.name()).append(' ').append(talker.callsign());
			for(TransmissionRecord.DataPacket packet : record.packets())
				hay.append(' ').append(packet.summary());
			if(!hay.toString().toLowerCase(Locale.ROOT).contains(wanted))
				return false;
		}
		return true;
	}

	/** A page of results: {@code items} (newest first) and how many matched in all. */
	public record Page(List<TransmissionRecord> items, int total) {}
}
