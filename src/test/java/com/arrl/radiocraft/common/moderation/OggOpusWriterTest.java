package com.arrl.radiocraft.common.moderation;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Ogg Opus export: valid pages (CRC, flags, granules), the two headers, packets intact, pauses kept. */
class OggOpusWriterTest {

	/** One parsed Ogg page. */
	private record Page(int flags, long granule, int sequence, List<byte[]> packets) {}

	private static List<Page> parse(byte[] bytes) {
		List<Page> pages = new ArrayList<>();
		int at = 0;
		while(at < bytes.length) {
			assertEquals("OggS", new String(bytes, at, 4, StandardCharsets.US_ASCII));
			ByteBuffer header = ByteBuffer.wrap(bytes, at, 27).order(ByteOrder.LITTLE_ENDIAN);
			int flags = bytes[at + 5];
			long granule = header.getLong(at + 6);
			int sequence = header.getInt(at + 18);
			int crc = header.getInt(at + 22);
			int segments = bytes[at + 26] & 0xFF;
			int dataStart = at + 27 + segments;
			List<byte[]> packets = new ArrayList<>();
			int length = 0, offset = dataStart;
			for(int i = 0; i < segments; i++) {
				int lace = bytes[at + 27 + i] & 0xFF;
				length += lace;
				if(lace < 255) {
					packets.add(Arrays.copyOfRange(bytes, offset, offset + length));
					offset += length;
					length = 0;
				}
			}
			byte[] page = Arrays.copyOfRange(bytes, at, offset);
			page[22] = page[23] = page[24] = page[25] = 0;
			assertEquals(crc, OggOpusWriter.crc(page), "page " + sequence + " CRC");
			pages.add(new Page(flags, granule, sequence, packets));
			at = offset;
		}
		return pages;
	}

	private static byte[] frame(int marker) {
		byte[] data = new byte[60];
		data[0] = (byte)0xF8; // CELT 20 ms, as Simple Voice Chat sends.
		data[1] = (byte)marker;
		return data;
	}

	@Test
	void headersThenPacketsWithGranules() throws IOException {
		List<OggOpusWriter.TimedPacket> packets = new ArrayList<>();
		for(int i = 0; i < 100; i++)
			packets.add(new OggOpusWriter.TimedPacket(1000L + i * 20L, frame(i)));
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		OggOpusWriter.write(out, packets, Map.of("TITLE", "test"), 42);
		List<Page> pages = parse(out.toByteArray());
		assertEquals(0x02, pages.getFirst().flags(), "first page is BOS");
		assertEquals("OpusHead", new String(pages.get(0).packets().getFirst(), 0, 8, StandardCharsets.US_ASCII));
		assertEquals("OpusTags", new String(pages.get(1).packets().getFirst(), 0, 8, StandardCharsets.US_ASCII));
		assertEquals(0x04, pages.getLast().flags(), "last page is EOS");
		assertEquals(100 * 960L, pages.getLast().granule());
		List<byte[]> audio = new ArrayList<>();
		for(Page page : pages.subList(2, pages.size()))
			audio.addAll(page.packets());
		assertEquals(100, audio.size());
		assertArrayEquals(frame(57), audio.get(57));
		for(int i = 0; i < pages.size(); i++)
			assertEquals(i, pages.get(i).sequence());
	}

	@Test
	void aPauseIsFilledWithSilence() throws IOException {
		List<OggOpusWriter.TimedPacket> packets = List.of(new OggOpusWriter.TimedPacket(0L, frame(1)), new OggOpusWriter.TimedPacket(520L, frame(2)));
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		OggOpusWriter.write(out, packets, Map.of(), 1);
		List<Page> pages = parse(out.toByteArray());
		int silent = 0;
		for(Page page : pages.subList(2, pages.size()))
			for(byte[] packet : page.packets())
				if(Arrays.equals(packet, OggOpusWriter.SILENCE))
					silent++;
		assertEquals(25, silent, "half a second of silence between the two frames");
		assertEquals(27 * 960L, pages.getLast().granule());
	}

	@Test
	void samplesFromTheTocByte() {
		assertEquals(960, OggOpusWriter.samples(new byte[] { (byte)0xF8 })); // CELT FB 20 ms.
		assertEquals(480, OggOpusWriter.samples(new byte[] { (byte)(14 << 3) })); // Hybrid config 14: 10 ms.
		assertEquals(1920, OggOpusWriter.samples(new byte[] { (byte)(1 << 3 | 1) })); // SILK 20 ms, two frames.
		assertEquals(3 * 960, OggOpusWriter.samples(new byte[] { (byte)(0xF8 | 3), 3 })); // Code 3, three frames.
		assertTrue(OggOpusWriter.samples(new byte[0]) > 0);
	}
}
