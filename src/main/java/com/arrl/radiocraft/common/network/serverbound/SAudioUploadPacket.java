package com.arrl.radiocraft.common.network.serverbound;

import com.arrl.radiocraft.Radiocraft;
import com.arrl.radiocraft.RadiocraftServerConfig;
import com.arrl.radiocraft.common.data.TapeRecordingSavedData;
import com.arrl.radiocraft.common.init.RadiocraftDataComponent;
import com.arrl.radiocraft.common.init.RadiocraftItems;
import com.arrl.radiocraft.common.items.TapeItem;
import com.arrl.radiocraft.common.moderation.ModerationHooks;
import com.arrl.radiocraft.common.radio.tape.TapeRecordings;
import com.arrl.radiocraft.common.radio.voice.RadioVoiceProcessor;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

/**
 * Packet storing a chunk of an audio file; for uploading music discs as tape recordings. Must be sent sequentially.
 * Each user may only upload one file at a time.
 *
 * @param soundName name of the complete audio (i.e. the music disc name); use "" if unknown; ignored on all packets but the first
 * @param totalSamples total number of samples in the audio; ignored on all packets but the first
 * @param index the starting index for the chunk in the byte[] representing the complete audio
 * @param data a chunk of the audio data
 */
public record SAudioUploadPacket(@NotNull String soundName, int totalSamples, int index, byte[] data) implements CustomPacketPayload {
    public static final int MAX_CHUNK_BYTES = 16 * 1024;  // well under the 32 KiB limit
    private static final int MAX_SAMPLES = 48_000 * 60 * 15;  // cap what a client can make us allocate
    public static final Type<SAudioUploadPacket> TYPE = new Type<>(Radiocraft.id("s_audio_upload"));

    // Remember to update for music discs introduced by new versions:
    //  - Start the dev client
    //  - /rcdebug tapes upload_all_discs
    //  - Wait for discs to upload
    //  - /rcdebug tapes list @p
    //  - Copy entries from the game log
    private static final Map<Integer, String> VANILLA_DISCS = Map.ofEntries(
        Map.entry(1669799951, "C418 - blocks"),
        Map.entry(1703760815, "C418 - far"),
        Map.entry(1581195012, "C418 - mellohi"),
        Map.entry(-883088719, "C418 - strad"),
        Map.entry(1444060434, "C418 - wait"),
        Map.entry(1322926903, "C418 - 11"),
        Map.entry(-717354913, "C418 - cat"),
        Map.entry(569407030, "Lena Raine - Creator (Music Box)"),
        Map.entry(1408534776, "C418 - mall"),
        Map.entry(-1897617754, "Samuel Åberg - 5"),
        Map.entry(1939359184, "Aaron Cherof - Relic"),
        Map.entry(1424760162, "Lena Raine - Creator"),
        Map.entry(49664530, "Lena Raine - otherside"),
        Map.entry(-1584557387, "C418 - ward"),
        Map.entry(136555790, "C418 - stal"),
        Map.entry(848121532, "Lena Raine - Pigstep"),
        Map.entry(1075991832, "C418 - 13"),
        Map.entry(-718082914, "C418 - chirp"),
        Map.entry(1334316210, "Aaron Cherof - Precipice")
    );

    private static final Map<UUID, Assembly> inProgress = new HashMap<>();

    private static final class Assembly {
        final short[] audio;
        String name;
        int audioIndex, nextPacketIndex;
        Assembly(int total, String name) {
            this.name = name;
            audio = new short[total];
        }
    }

    public static final StreamCodec<ByteBuf, SAudioUploadPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, SAudioUploadPacket::soundName,
            ByteBufCodecs.VAR_INT, SAudioUploadPacket::totalSamples,
            ByteBufCodecs.VAR_INT, SAudioUploadPacket::index,
            ByteBufCodecs.byteArray(MAX_CHUNK_BYTES), SAudioUploadPacket::data,
            SAudioUploadPacket::new
    );

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * Get the official, hardcoded name for a disc recording with the provided hash. If no official disc exists with
     * that hash, return the provided name or null depending on whether the player has perms to upload unofficial discs.
     * @param player the ServerPlayer attempting the upload
     * @param providedName the String tape name suggested by player
     * @param audioHash the int hash of the uploaded audio
     * @return an empty Optional if the upload is illegal, an Optional containing the tape name to use otherwise
     */
    private @NotNull Optional<String> getNameIfUploadAllowed(@NotNull ServerPlayer player, @NotNull String providedName, int audioHash) {
        Optional<String> officialName = Optional.ofNullable(VANILLA_DISCS.get(audioHash));

        if (officialName.isEmpty()) {
            if (RadiocraftServerConfig.TAPE_UPLOAD_FILTERING.get() == RadiocraftServerConfig.TapeUploadFiltering.FILTER_ALL
                    || (!player.hasPermissions(2)
                    && RadiocraftServerConfig.TAPE_UPLOAD_FILTERING.get() == RadiocraftServerConfig.TapeUploadFiltering.FILTER_NON_OP)) {
                return Optional.empty();
            }
            else {
                return Optional.of(providedName);
            }
        }
        else {
            return officialName;
        }
    }

    public void handle(IPayloadContext context) {
        // TODO: not sure about thread safety with the audio buffer
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            UUID playerUUID = player.getUUID();

            // TODO: update once real clientside stuff is done
//            if (!player.hasPermissions(2)) {
//                IN_PROGRESS.remove(playerUUID);
//                return;
//            }

            // TODO: what if a player starts uploading a 2nd recording while the 1st is still uploading?
            if (index == 0) {
                if (totalSamples <= 0 || MAX_SAMPLES < totalSamples) return;
                inProgress.put(playerUUID, new Assembly(totalSamples, soundName));
            }

            Assembly assembly = inProgress.get(playerUUID);
            if (assembly == null || index != assembly.nextPacketIndex
                    || data.length % 2 != 0  // Must have 2 bytes per short
                    || assembly.audio.length < assembly.audioIndex + data.length / 2) {
                inProgress.remove(playerUUID); // malformed or out of order: drop the transfer
                return;
            }

            ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                    .asShortBuffer().get(assembly.audio, assembly.audioIndex, data.length / 2);
            assembly.audioIndex += data.length / 2;
            assembly.nextPacketIndex++;

            if (assembly.audioIndex == assembly.audio.length) {
                inProgress.remove(playerUUID);
                final MinecraftServer server = player.getServer();

                if (server == null) {
                    Radiocraft.LOGGER.error("Could not access server to save audio file");
                    return;
                }

                short[] audio;
                synchronized (assembly) {
                    audio = java.util.Arrays.copyOf(assembly.audio, assembly.audio.length);
                }
                final int audioHash = Arrays.hashCode(audio);
                int seconds = Math.round(audio.length / (float) RadioVoiceProcessor.SAMPLE_RATE);

                // Get metadata about an existing audio file with the same hash, or make a new metadata entry
                TapeRecordingSavedData savedData = TapeRecordingSavedData.get(server.overworld());
                final var existingEntry = savedData.getByHash(audioHash);
                existingEntry.ifPresent(entry ->
                    player.sendSystemMessage(Component.literal("That audio was already uploaded; will use " + entry)));

                // Always allow duplicates of existing uploads...
                final @Nullable TapeRecordingSavedData.TapeRecordingEntry entry = savedData.getByHash(audioHash).orElseGet(() -> {
                    // ...but filter according to server config if this audio is new
                    Optional<String> officialName = getNameIfUploadAllowed(player, soundName, audioHash);
                    if (officialName.isEmpty()) return null;

                    final var newEntry = new TapeRecordingSavedData.TapeRecordingEntry(
                            UUID.randomUUID(), soundName,
                            playerUUID, player.getName().getString(),
                            audioHash, seconds
                    );
                    TapeRecordings.save(server, newEntry.tapeUUID(), audio);
                    savedData.putTape(newEntry);
                    ModerationHooks.tapeRecorded(player.serverLevel(), player.blockPosition(), newEntry.tapeUUID(), seconds, List.of(playerUUID));
                    player.sendSystemMessage(Component.literal("No duplicate found; saved tape under new UUID: " + newEntry.tapeUUID()));
                    return newEntry;
                });

                if (entry == null) {
                    player.sendSystemMessage(Component.literal("Disc audio was not on the whitelist and you don't have permissions to override it; upload cancelled"));
                }
                else {
                    ItemStack tape = new ItemStack(RadiocraftItems.TAPE_REEL.get());
                    tape.set(RadiocraftDataComponent.TAPE_CONTENTS.get(),
                            TapeItem.contents(tape).withRecording(
                                    entry.tapeUUID(),
                                    entry.tapeName(),
                                    Math.round(audio.length / (float) RadioVoiceProcessor.SAMPLE_RATE)));
                    player.addItem(tape);
                }
            }
        });
    }

    public static void send(@NotNull String soundName, int totalSamples, int index, ByteBuffer chunk) {
        PacketDistributor.sendToServer(new SAudioUploadPacket(soundName, totalSamples, index, chunk.array()));
    }

    public static void clear(UUID player) { inProgress.remove(player); } // TODO: call on logout
}
