package com.arrl.radiocraft.common.moderation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The moderation audio file: frames of interleaved transmissions read back by id, with their times. */
class FrameFileTest {

	private static final long BASE = 1_762_000_000_000L;

	private static byte[] file(FrameFile.Frame... frames) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.writeBytes(FrameFile.header(BASE));
		for(FrameFile.Frame frame : frames)
			out.writeBytes(FrameFile.encode(frame, BASE));
		return out.toByteArray();
	}

	@Test
	void framesComeBackByTransmissionWithTheirTimes() throws IOException {
		byte[] bytes = file(new FrameFile.Frame(7L, BASE + 20, 0, new byte[] { 1, 2, 3 }), new FrameFile.Frame(9L, BASE + 25, 1, new byte[] { 4 }),
				new FrameFile.Frame(7L, BASE + 40, 0, new byte[] { 5, 6 }));
		DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
		long base = FrameFile.readHeader(in);
		assertEquals(BASE, base);
		List<FrameFile.Frame> seven = FrameFile.readFrames(in, base, bytes.length - FrameFile.HEADER_BYTES, 7L);
		assertEquals(2, seven.size());
		assertEquals(BASE + 20, seven.get(0).epochMs());
		assertArrayEquals(new byte[] { 5, 6 }, seven.get(1).data());
	}

	@Test
	void aSpanOfTheFileIsReadOnItsOwn(@TempDir Path dir) throws IOException {
		FrameFile.Frame a = new FrameFile.Frame(1L, BASE + 1000, 0, new byte[] { 10, 11 });
		FrameFile.Frame b = new FrameFile.Frame(2L, BASE + 2000, 3, new byte[] { 12 });
		byte[] bytes = file(a, b);
		Path path = dir.resolve("13.rcma");
		Files.write(path, bytes);
		long bStart = FrameFile.HEADER_BYTES + FrameFile.FRAME_OVERHEAD + 2;
		List<FrameFile.Frame> read = FrameFile.read(path, bStart, bytes.length, -1L);
		assertEquals(1, read.size());
		assertEquals(2L, read.getFirst().txId());
		assertEquals(3, read.getFirst().stream());
	}

	@Test
	void aFrameCutShortAtTheEndIsDropped() throws IOException {
		byte[] whole = file(new FrameFile.Frame(1L, BASE, 0, new byte[] { 1, 2, 3, 4 }), new FrameFile.Frame(1L, BASE + 20, 0, new byte[] { 5, 6, 7, 8 }));
		byte[] torn = Arrays.copyOf(whole, whole.length - 2);
		DataInputStream in = new DataInputStream(new ByteArrayInputStream(torn));
		long base = FrameFile.readHeader(in);
		assertEquals(1, FrameFile.readFrames(in, base, Long.MAX_VALUE, -1L).size());
	}

	@Test
	void notAModerationFile() {
		DataInputStream in = new DataInputStream(new ByteArrayInputStream("OggS-whatever-".getBytes()));
		assertThrows(IOException.class, () -> FrameFile.readHeader(in));
	}
}
