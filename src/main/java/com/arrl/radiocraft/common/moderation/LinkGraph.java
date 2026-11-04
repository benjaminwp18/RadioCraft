package com.arrl.radiocraft.common.moderation;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Who hears whom: from each logged transmission, a link from its talker (or, when nobody spoke, the radio that sent it)
 * to each thing that heard it (the listening player, or the radio when nobody was near it), counted once per
 * transmission per day. Pure.
 */
public final class LinkGraph {

	private LinkGraph() {}

	/**
	 * One end of a link.
	 *
	 * @param kind  "player" or "radio"
	 * @param id    a player's UUID, or a radio's "dimension x,y,z"
	 * @param label a player's name, or the radio's kind and position
	 */
	public record Node(String kind, String id, String label) {

		public static Node player(UUID uuid, String name) {
			return new Node("player", uuid.toString(), name.isEmpty() ? uuid.toString().substring(0, 8) : name);
		}

		public static Node radio(String dimension, @Nullable BlockPos pos, String radioKind) {
			String where = pos == null ? "?" : pos.getX() + "," + pos.getY() + "," + pos.getZ();
			return new Node("radio", dimension + " " + where, radioKind + " " + where);
		}

		public boolean isPlayer() {
			return "player".equals(kind);
		}
	}

	/** {@code count} transmissions from {@code from} heard by {@code to} on {@code day}. */
	public record Link(LocalDate day, Node from, Node to, int count) {}

	/** The node a transmission comes from: its first talker, else its radio. */
	public static Node source(TransmissionRecord record) {
		TransmissionRecord.Talker talker = record.talker();
		return talker != null ? Node.player(talker.uuid(), talker.name()) : Node.radio(record.dimension(), record.radioPos(), record.radioKind());
	}

	/** The node a hearer stands for: its player, else its radio. */
	public static Node hearer(TransmissionRecord.Hearer hearer) {
		return hearer.player() != null ? Node.player(hearer.player(), hearer.playerName()) : Node.radio(hearer.dimension(), hearer.pos(), hearer.radioKind());
	}

	/** Links counted per day (in {@code zone}) over {@code records}, busiest first within each day, days in order. */
	public static List<Link> count(Iterable<TransmissionRecord> records, ZoneId zone) {
		record Key(LocalDate day, Node from, Node to) {}
		Map<Key, Integer> counts = new LinkedHashMap<>();
		for(TransmissionRecord record : records) {
			LocalDate day = Instant.ofEpochMilli(record.startMs()).atZone(zone).toLocalDate();
			Node from = source(record);
			Set<Node> heard = new LinkedHashSet<>();
			for(TransmissionRecord.Hearer hearer : record.hearers()) {
				Node to = hearer(hearer);
				if(!to.id().equals(from.id()))
					heard.add(to);
			}
			for(Node to : heard)
				counts.merge(new Key(day, from, to), 1, Integer::sum);
		}
		List<Link> out = new ArrayList<>();
		for(Map.Entry<Key, Integer> entry : counts.entrySet())
			out.add(new Link(entry.getKey().day(), entry.getKey().from(), entry.getKey().to(), entry.getValue()));
		out.sort(Comparator.comparing(Link::day).thenComparing(Comparator.comparingInt(Link::count).reversed()));
		return out;
	}
}
