package com.arrl.radiocraft.common.moderation;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.Channels;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * The moderation log's audio file format: a short header, then length-prefixed Opus frames from any number of
 * transmissions, interleaved as they arrived, each with its time so playback can reproduce the timing. One file holds
 * an hour (audio/yyyy-MM-dd/HH.rcma); a flagged transmission's copy in the kept folder is one file of its own.
 * <pre>
 * header: "RCMA" | u8 version (1) | i64 base time, epoch ms
 * frame:  i64 transmission id | i32 ms after the base time | u8 stream (the talker's index) | u16 length | Opus bytes
 * </pre>
 * Big-endian, as {@link java.io.DataOutputStream} writes it. Pure: no game classes.
 */
public final class FrameFile {

	private FrameFile() {}

	public static final byte[] MAGIC = { 'R', 'C', 'M', 'A' };
	public static final int VERSION = 1;
	public static final int HEADER_BYTES = 4 + 1 + 8;
	/** Bytes a frame costs on top of its Opus data. */
	public static final int FRAME_OVERHEAD = 8 + 4 + 1 + 2;
	/** Largest Opus frame kept (a 20 ms voice frame is well under 200 bytes). */
	public static final int MAX_DATA = 0xFFFF;

	/**
	 * One stored frame.
	 *
	 * @param txId    the transmission it belongs to
	 * @param epochMs when it arrived
	 * @param stream  which talker of the transmission (0 for the first)
	 * @param data    the Opus packet
	 */
	public record Frame(long txId, long epochMs, int stream, byte[] data) {}

	/** The header for a file whose frame times count from {@code baseMs}. */
	public static byte[] header(long baseMs) {
		ByteBuffer buffer = ByteBuffer.allocate(HEADER_BYTES);
		buffer.put(MAGIC).put((byte)VERSION).putLong(baseMs);
		return buffer.array();
	}

	/** {@code frame} as stored in a file whose header says {@code baseMs}. Data past {@link #MAX_DATA} is cut. */
	public static byte[] encode(Frame frame, long baseMs) {
		int length = Math.min(MAX_DATA, frame.data().length);
		ByteBuffer buffer = ByteBuffer.allocate(FRAME_OVERHEAD + length);
		buffer.putLong(frame.txId()).putInt((int)(frame.epochMs() - baseMs)).put((byte)frame.stream()).putShort((short)length).put(frame.data(), 0, length);
		return buffer.array();
	}

	/** Reads a header; returns its base time. Throws if it is not one. */
	public static long readHeader(DataInputStream in) throws IOException {
		byte[] magic = new byte[MAGIC.length];
		in.readFully(magic);
		for(int i = 0; i < MAGIC.length; i++)
			if(magic[i] != MAGIC[i])
				throw new IOException("Not a moderation audio file");
		int version = in.readUnsignedByte();
		if(version != VERSION)
			throw new IOException("Unknown moderation audio version " + version);
		return in.readLong();
	}

	/**
	 * The frames read from {@code in} (positioned at a frame) until {@code bytes} are used up or the stream ends, keeping
	 * those of transmission {@code txId} (all of them for a negative id). A frame cut short at the end (the server
	 * stopped mid-write) is dropped.
	 */
	public static List<Frame> readFrames(DataInputStream in, long baseMs, long bytes, long txId) throws IOException {
		List<Frame> out = new ArrayList<>();
		long read = 0L;
		while(read + FRAME_OVERHEAD <= bytes) {
			long id;
			int offset, stream, length;
			try {
				id = in.readLong();
				offset = in.readInt();
				stream = in.readUnsignedByte();
				length = in.readUnsignedShort();
			}
			catch(EOFException e) {
				break;
			}
			if(read + FRAME_OVERHEAD + length > bytes)
				break;
			byte[] data = new byte[length];
			try {
				in.readFully(data);
			}
			catch(EOFException e) {
				break;
			}
			read += FRAME_OVERHEAD + length;
			if(txId < 0L || id == txId)
				out.add(new Frame(id, baseMs + offset, stream, data));
		}
		return out;
	}

	/** The frames of {@code txId} between bytes {@code start} and {@code end} of {@code file}. */
	public static List<Frame> read(Path file, long start, long end, long txId) throws IOException {
		try(FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
			DataInputStream header = new DataInputStream(Channels.newInputStream(channel));
			long baseMs = readHeader(header);
			channel.position(Math.max(HEADER_BYTES, start));
			InputStream body = new java.io.BufferedInputStream(Channels.newInputStream(channel));
			long stop = Math.min(end, channel.size());
			return readFrames(new DataInputStream(body), baseMs, stop - Math.max(HEADER_BYTES, start), txId);
		}
	}
}
