package com.arrl.radiocraft.common.moderation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Which of the moderation log's files go: those wholly older than the retention window, then the oldest audio until
 * the folder is under its storage cap. Whole files only (an hour of audio, a day of index). Pure: the caller lists the
 * files and deletes what this picks.
 */
public final class Retention {

	private Retention() {}

	/**
	 * A file and the time it covers.
	 *
	 * @param name    its path (relative or absolute, only handed back)
	 * @param startMs the start of what it covers, epoch ms
	 * @param spanMs  how long it covers (an hour, a day)
	 * @param bytes   its size
	 */
	public record FileSpan(String name, long startMs, long spanMs, long bytes) {

		public long endMs() {
			return startMs + spanMs;
		}
	}

	/** The files whose whole span ended at or before {@code cutoffMs}. */
	public static List<FileSpan> expired(List<FileSpan> files, long cutoffMs) {
		List<FileSpan> out = new ArrayList<>();
		for(FileSpan file : files)
			if(file.endMs() <= cutoffMs)
				out.add(file);
		return out;
	}

	/**
	 * Oldest first, the files to delete for {@code files} (and {@code otherBytes} that cannot be deleted) to fit in
	 * {@code capBytes}. A file covering {@code nowMs} (being written) is never picked.
	 */
	public static List<FileSpan> overCap(List<FileSpan> files, long otherBytes, long capBytes, long nowMs) {
		long total = otherBytes;
		for(FileSpan file : files)
			total += file.bytes();
		List<FileSpan> sorted = new ArrayList<>(files);
		sorted.sort(Comparator.comparingLong(FileSpan::startMs));
		List<FileSpan> out = new ArrayList<>();
		for(FileSpan file : sorted) {
			if(total <= capBytes)
				break;
			if(file.startMs() <= nowMs && nowMs < file.endMs())
				continue;
			out.add(file);
			total -= file.bytes();
		}
		return out;
	}
}
