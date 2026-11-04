package com.arrl.radiocraft.common.moderation;

import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An index line written and read back, and the query filters over records. */
class TransmissionRecordTest {

	private static TransmissionRecord full() {
		return new TransmissionRecord(1_762_000_000_123_004L, TransmissionKind.DATA, 1_762_000_000_123L, 1_762_000_002_000L, 5000L, "minecraft:overworld",
				List.of(new TransmissionRecord.Talker(LinkGraphTest.ALICE, "Alice", "K1ABC")), "vhf_base_station", new BlockPos(1, 64, -3), new BlockPos(1, 66, -5),
				144_390_000.0F, "2m", "DATA", 100.0F, List.of(LinkGraphTest.player(LinkGraphTest.BOB), LinkGraphTest.radio(9)), 77L, "MESSAGE K1ABC>ALL: hi",
				List.of(new TransmissionRecord.DataPacket(1_762_000_000_500L, "MESSAGE", "{id:1L,kind:\"MESSAGE\"}", "MESSAGE K1ABC>ALL: hi")),
				List.of(new TransmissionRecord.CwBuffer(3, 0b1011)), List.of(new TransmissionRecord.AudioSpan("audio/2025-11-01/12.rcma", 13L, 400L)), 12,
				UUID.nameUUIDFromBytes("tape".getBytes()), true);
	}

	@Test
	void indexLineRoundTrips() {
		TransmissionRecord record = full();
		String line = record.toJson().toString();
		assertFalse(line.contains("\n"), "one line");
		TransmissionRecord back = TransmissionRecord.fromJson(JsonParser.parseString(line).getAsJsonObject());
		assertEquals(record, back);
	}

	@Test
	void queriesFilter() {
		TransmissionRecord record = full();
		assertTrue(TransmissionQuery.all().matches(record));
		assertTrue(TransmissionQuery.all().withPlayer(LinkGraphTest.BOB, TransmissionQuery.PlayerRole.HEARER).matches(record));
		assertFalse(TransmissionQuery.all().withPlayer(LinkGraphTest.BOB, TransmissionQuery.PlayerRole.TALKER).matches(record));
		assertTrue(TransmissionQuery.all().withRadio(null, new BlockPos(9, 64, 0)).matches(record), "a radio that heard it");
		assertFalse(TransmissionQuery.all().withFrequency(146_000_000.0F, 148_000_000.0F).matches(record));
		assertTrue(TransmissionQuery.all().withText("k1abc").matches(record));
		assertFalse(TransmissionQuery.all().withKinds(TransmissionKind.CW).matches(record));
		assertFalse(TransmissionQuery.all().withTime(0L, record.startMs()).matches(record));
		assertFalse(TransmissionQuery.all().matches(new TransmissionRecord(1L, TransmissionKind.PROXIMITY, 0L, 0L, 0L, "", List.of(), "proximity", null, null, 0.0F, "", "", 0.0F,
				List.of(), 0L, "", List.of(), List.of(), List.of(), 0, null, false)), "proximity only when asked for");
	}
}
