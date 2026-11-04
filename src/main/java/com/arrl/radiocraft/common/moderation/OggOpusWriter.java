package com.arrl.radiocraft.common.moderation;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A minimal Ogg Opus muxer (RFC 7845) for exports: the Opus packets the log kept go into an .opus file any player
 * opens, without decoding or encoding them. Mono, 48 kHz. Gaps between packets (a pause inside a transmission) are
 * filled with Opus silence frames, so the file keeps the timing. Pure: no game classes.
 */
public final class OggOpusWriter {

	private OggOpusWriter() {}

	/** A 20 ms Opus frame of silence (CELT, mono): what fills gaps. */
	public static final byte[] SILENCE = { (byte)0xF8, (byte)0xFF, (byte)0xFE };
	/** Samples the decoder drops at the start (the encoder's lookahead, 6.5 ms). */
	public static final int PRE_SKIP = 312;
	/** Longest gap filled with silence, ms: longer pauses are cut down to this. */
	public static final long MAX_GAP_MS = 10_000L;
	private static final int MAX_PAGE_DATA = 4096;

	/** An Opus packet and when it was received, epoch ms. */
	public record TimedPacket(long ms, byte[] data) {}

	/**
	 * Writes {@code packets} (in time order) to {@code out} as an Ogg Opus stream with {@code serial}, tagged with
	 * {@code tags} (TITLE, ARTIST, DATE... as Vorbis comments).
	 */
	public static void write(OutputStream out, List<TimedPacket> packets, Map<String, String> tags, int serial) throws IOException {
		PageWriter pages = new PageWriter(out, serial);
		pages.page(List.of(opusHead()), 0L, 0x02);
		pages.page(List.of(opusTags(tags)), 0L, 0x00);

		List<byte[]> laid = new ArrayList<>();
		List<Long> granules = new ArrayList<>();
		long samples = 0L;
		long clockMs = packets.isEmpty() ? 0L : packets.getFirst().ms();
		for(TimedPacket packet : packets) {
			long gap = Math.min(MAX_GAP_MS, packet.ms() - clockMs);
			while(gap >= 20L) { // Silence for a pause, 20 ms at a time.
				samples += 960;
				laid.add(SILENCE);
				granules.add(samples);
				gap -= 20L;
				clockMs += 20L;
			}
			int frameSamples = samples(packet.data());
			samples += frameSamples;
			laid.add(packet.data());
			granules.add(samples);
			clockMs = Math.max(clockMs, packet.ms()) + frameSamples / 48; // Arrival jitter under a frame is not a pause.
		}
		if(laid.isEmpty()) {
			laid.add(SILENCE);
			granules.add(960L);
		}

		List<byte[]> page = new ArrayList<>();
		int segments = 0, bytes = 0;
		for(int i = 0; i < laid.size(); i++) {
			byte[] data = laid.get(i);
			int need = data.length / 255 + 1;
			if(!page.isEmpty() && (segments + need > 255 || bytes + data.length > MAX_PAGE_DATA)) {
				pages.page(page, granules.get(i - 1), 0x00);
				page = new ArrayList<>();
				segments = 0;
				bytes = 0;
			}
			page.add(data);
			segments += need;
			bytes += data.length;
		}
		pages.page(page, granules.getLast(), 0x04);
	}

	/** The identification header: one channel, 48 kHz, no gain, mapping family 0. */
	static byte[] opusHead() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.writeBytes("OpusHead".getBytes(StandardCharsets.US_ASCII));
		out.write(1); // Version.
		out.write(1); // Channels.
		writeLe(out, PRE_SKIP, 2);
		writeLe(out, 48_000, 4);
		writeLe(out, 0, 2); // Output gain.
		out.write(0); // Mapping family.
		return out.toByteArray();
	}

	/** The comment header: vendor "RadioCraft" and {@code tags} as KEY=value. */
	static byte[] opusTags(Map<String, String> tags) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.writeBytes("OpusTags".getBytes(StandardCharsets.US_ASCII));
		byte[] vendor = "RadioCraft".getBytes(StandardCharsets.UTF_8);
		writeLe(out, vendor.length, 4);
		out.writeBytes(vendor);
		writeLe(out, tags.size(), 4);
		for(Map.Entry<String, String> tag : tags.entrySet()) {
			byte[] comment = (tag.getKey() + "=" + tag.getValue()).getBytes(StandardCharsets.UTF_8);
			writeLe(out, comment.length, 4);
			out.writeBytes(comment);
		}
		return out.toByteArray();
	}

	/**
	 * Samples at 48 kHz an Opus packet decodes to, from its TOC byte (RFC 6716 section 3.1); 960 (20 ms) for an empty or
	 * unreadable one.
	 */
	public static int samples(byte[] packet) {
		if(packet.length == 0)
			return 960;
		int toc = packet[0] & 0xFF;
		int config = toc >> 3;
		int frame;
		if(config < 12)
			frame = new int[] { 480, 960, 1920, 2880 }[config & 3]; // SILK: 10, 20, 40, 60 ms.
		else if(config < 16)
			frame = (config & 1) == 0 ? 480 : 960; // Hybrid: 10, 20 ms.
		else
			frame = new int[] { 120, 240, 480, 960 }[config & 3]; // CELT: 2.5, 5, 10, 20 ms.
		int count = switch(toc & 3) {
			case 0 -> 1;
			case 1, 2 -> 2;
			default -> packet.length > 1 ? packet[1] & 0x3F : 1;
		};
		return Math.max(1, count) * frame;
	}

	private static void writeLe(ByteArrayOutputStream out, long value, int bytes) {
		for(int i = 0; i < bytes; i++)
			out.write((int)(value >>> (8 * i)) & 0xFF);
	}

	/** Writes Ogg pages, numbering them and putting the CRC in. */
	private static final class PageWriter {
		private final OutputStream out;
		private final int serial;
		private int sequence = 0;

		PageWriter(OutputStream out, int serial) {
			this.out = out;
			this.serial = serial;
		}

		/** One page holding {@code packets} whole; {@code flags} 0x02 first page, 0x04 last page. */
		void page(List<byte[]> packets, long granule, int flags) throws IOException {
			ByteArrayOutputStream lacing = new ByteArrayOutputStream();
			ByteArrayOutputStream body = new ByteArrayOutputStream();
			for(byte[] packet : packets) {
				int left = packet.length;
				while(left >= 255) {
					lacing.write(255);
					left -= 255;
				}
				lacing.write(left);
				body.writeBytes(packet);
			}
			ByteArrayOutputStream page = new ByteArrayOutputStream();
			page.writeBytes("OggS".getBytes(StandardCharsets.US_ASCII));
			page.write(0); // Version.
			page.write(flags);
			writeLe(page, granule, 8);
			writeLe(page, serial, 4);
			writeLe(page, sequence++, 4);
			writeLe(page, 0, 4); // CRC, filled in below.
			page.write(lacing.size());
			page.writeBytes(lacing.toByteArray());
			page.writeBytes(body.toByteArray());
			byte[] bytes = page.toByteArray();
			int crc = crc(bytes);
			bytes[22] = (byte)crc;
			bytes[23] = (byte)(crc >>> 8);
			bytes[24] = (byte)(crc >>> 16);
			bytes[25] = (byte)(crc >>> 24);
			out.write(bytes);
		}
	}

	private static final int[] CRC_TABLE = new int[256];

	static {
		for(int i = 0; i < 256; i++) {
			int r = i << 24;
			for(int j = 0; j < 8; j++)
				r = (r & 0x80000000) != 0 ? (r << 1) ^ 0x04C11DB7 : r << 1;
			CRC_TABLE[i] = r;
		}
	}

	/** Ogg's CRC-32: polynomial 0x04C11DB7, not reflected, starting at 0, no final xor. */
	static int crc(byte[] data) {
		int crc = 0;
		for(byte b : data)
			crc = (crc << 8) ^ CRC_TABLE[((crc >>> 24) ^ b) & 0xFF];
		return crc;
	}
}
