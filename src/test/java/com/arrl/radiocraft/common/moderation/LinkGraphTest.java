package com.arrl.radiocraft.common.moderation;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Who hears whom, counted per transmission per day. */
class LinkGraphTest {

	static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
	static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());
	static final long DAY1 = LocalDate.of(2026, 11, 4).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();

	static TransmissionRecord record(long startMs, UUID talker, List<TransmissionRecord.Hearer> hearers) {
		return new TransmissionRecord(startMs * 1000L, TransmissionKind.PLAYER_VOICE, startMs, startMs + 2000L, 0L, "minecraft:overworld",
				List.of(new TransmissionRecord.Talker(talker, talker.equals(ALICE) ? "Alice" : "Bob", "")), "handheld", BlockPos.ZERO, BlockPos.ZERO,
				146_520_000.0F, "2m", "FM", 0.0F, hearers, 0L, "", List.of(), List.of(), List.of(), 0, null, false);
	}

	static TransmissionRecord.Hearer player(UUID who) {
		return new TransmissionRecord.Hearer(who, "", "handheld", "minecraft:overworld", BlockPos.ZERO, 0.8F, 0L);
	}

	static TransmissionRecord.Hearer radio(int x) {
		return new TransmissionRecord.Hearer(null, "", "vhf_base_station", "minecraft:overworld", new BlockPos(x, 64, 0), 0.5F, 0L);
	}

	@Test
	void countsEachTransmissionOncePerHearerPerDay() {
		List<TransmissionRecord> records = List.of(
				record(DAY1 + 1000, ALICE, List.of(player(BOB), player(BOB), radio(5))), // Bob heard it twice (two handhelds): counted once.
				record(DAY1 + 60_000, ALICE, List.of(player(BOB))),
				record(DAY1 + 86_400_000L + 5, ALICE, List.of(player(BOB))), // The next day.
				record(DAY1 + 120_000, BOB, List.of(player(BOB), player(ALICE)))); // Bob hearing himself is no link.
		List<LinkGraph.Link> links = LinkGraph.count(records, ZoneOffset.UTC);
		LinkGraph.Link aliceToBob = links.stream().filter(l -> l.day().equals(LocalDate.of(2026, 11, 4)) && l.from().id().equals(ALICE.toString())
				&& l.to().id().equals(BOB.toString())).findFirst().orElseThrow();
		assertEquals(2, aliceToBob.count());
		assertEquals(4, links.size(), "alice>bob, alice>radio, bob>alice on day one; alice>bob on day two");
		assertEquals(LocalDate.of(2026, 11, 5), links.getLast().day());
	}
}
