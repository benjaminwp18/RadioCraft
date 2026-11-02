package com.arrl.radiocraft.common.data;

import com.arrl.radiocraft.Radiocraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class TapeRecordingSavedData extends SavedData {

    private static final String DATA_SPECIFIER = "tape_recording_data";
    private static final String DATA_NAME = Radiocraft.MOD_ID + "_" + DATA_SPECIFIER;

    // TODO: consider implementing an entity or item capability & moving this to api/capabilities
    // TODO: consider storing audio length
    public record TapeRecordingEntry(
            @NotNull UUID tapeUUID, @NotNull String tapeName,
            @NotNull UUID uploaderUUID, @Nullable String uploaderName,
            int audioHash, int audioLengthSeconds
    ) {
        @Override
        public @NotNull String toString() {
            String uploader = (uploaderName != null ? uploaderName : "") + " (" + uploaderUUID.toString().substring(0, 8) + "...)";
            return "Upload by " + uploader + ": " + tapeName + " (" + tapeUUID.toString().substring(0, 8) + "...), " + audioLengthSeconds + "s";
        }
    }

    private static final HashMap<UUID, TapeRecordingEntry> tapesByTapeUUID = new HashMap<>();
    private static final HashMap<Integer, TapeRecordingEntry> tapesByHash = new HashMap<>();

    public static TapeRecordingSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new Factory<>(TapeRecordingSavedData::new, TapeRecordingSavedData::load), DATA_NAME
        );
    }

    private static TapeRecordingSavedData load(@NotNull CompoundTag nbt, @NotNull HolderLookup.Provider provider) {
        Radiocraft.LOGGER.info("Loading tape recording data");
        CompoundTag callData = nbt.getCompound(DATA_SPECIFIER);
        TapeRecordingSavedData data = new TapeRecordingSavedData();
        TapeRecordingSavedData.tapesByTapeUUID.clear();
        TapeRecordingSavedData.tapesByHash.clear();

        for (String tapeUUIDString : callData.getAllKeys()) {
            try {
                CompoundTag tapeData = callData.getCompound(tapeUUIDString);
                String tapeName = tapeData.getString("tapeName");
                String uploaderUUIDString = null;
                if (tapeData.contains("uploaderUUID")) {
                    uploaderUUIDString = tapeData.getString("uploaderUUID");
                }
                String uploaderName = null;
                if (tapeData.contains("uploaderName")) {
                    uploaderName = tapeData.getString("uploaderName");
                }
                int audioHash = tapeData.getInt("audioHash");
                int audioLengthSeconds = tapeData.getInt("audioLengthSeconds");

                UUID tapeUUID = UUID.fromString(tapeUUIDString);
                UUID uploaderUUID = uploaderUUIDString == null ? null : UUID.fromString(uploaderUUIDString);
                TapeRecordingEntry entry = new TapeRecordingEntry(tapeUUID, tapeName, uploaderUUID, uploaderName, audioHash, audioLengthSeconds);
                TapeRecordingSavedData.tapesByTapeUUID.put(tapeUUID, entry);
                TapeRecordingSavedData.tapesByHash.put(audioHash, entry);
                Radiocraft.LOGGER.info("Loaded tape recording data for UUID: {}, name: {}", tapeUUIDString, tapeName);
            } catch (IllegalArgumentException e) {
                Radiocraft.LOGGER.error("Invalid recording data for UUID: {}", tapeUUIDString, e);
            }
        }
        Radiocraft.LOGGER.info("Loaded {} tape recording entries", TapeRecordingSavedData.tapesByTapeUUID.size());
        return data;
    }

    @Override
    public @NotNull CompoundTag save(@NotNull CompoundTag nbt, @NotNull HolderLookup.Provider provider) {
        Radiocraft.LOGGER.info("Saving tape recording data");
        CompoundTag allTapesData = new CompoundTag();
        for (UUID uuid : tapesByTapeUUID.keySet()) {
            TapeRecordingEntry entry = tapesByTapeUUID.get(uuid);
            CompoundTag tapeData = new CompoundTag();
            tapeData.putString("tapeName", entry.tapeName());
            tapeData.putString("uploaderUUID", entry.uploaderUUID().toString());
            if (entry.uploaderName() != null && !entry.uploaderName().isEmpty()) {
                tapeData.putString("uploaderName", entry.uploaderName());
            }
            tapeData.putInt("audioHash", entry.audioHash());
            tapeData.putInt("audioLengthSeconds", entry.audioLengthSeconds());
            allTapesData.put(uuid.toString(), tapeData);
        }
        nbt.put(DATA_SPECIFIER, allTapesData);
        return nbt;
    }

    public @NotNull Collection<TapeRecordingEntry> viewAllTapes() {
        return Collections.unmodifiableCollection(tapesByTapeUUID.values());
    }

    public @NotNull Optional<TapeRecordingEntry> getByTapeUUID(@NotNull UUID tapeUUID) {
        return Optional.ofNullable(tapesByTapeUUID.get(tapeUUID));
    }

    public @NotNull Optional<TapeRecordingEntry> getByHash(int audioHash) {
        return Optional.ofNullable(tapesByHash.get(audioHash));
    }

    public void putTape(@NotNull TapeRecordingEntry tapeEntry) throws IllegalArgumentException {
        TapeRecordingSavedData.tapesByTapeUUID.put(tapeEntry.tapeUUID, tapeEntry);
        TapeRecordingSavedData.tapesByHash.put(tapeEntry.audioHash, tapeEntry);
        setDirty();
    }

    /**
     * Reset the metadata database. You probably want to TapeRecordings::deleteAll to remove the audio files, too.
     */
    public void clearTapeDatabase() {
        tapesByTapeUUID.clear();
        tapesByHash.clear();
        setDirty();
    }
}
