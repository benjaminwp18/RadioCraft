package com.arrl.radiocraft.common.network.serverbound;

import com.arrl.radiocraft.Radiocraft;
import com.arrl.radiocraft.common.init.RadiocraftDataComponent;
import com.arrl.radiocraft.common.init.RadiocraftItems;
import com.arrl.radiocraft.common.items.TapeItem;
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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public record SAudioUploadPacket(int index, int totalSamples, byte[] data) implements CustomPacketPayload {
    public static final int MAX_CHUNK_BYTES = 16 * 1024;  // well under the 32 KiB limit
    public static final Type<SAudioUploadPacket> TYPE = new Type<>(Radiocraft.id("s_audio_upload"));

    private static final int MAX_SAMPLES = 48_000 * 60 * 15;  // cap what a client can make us allocate
    private static final Map<UUID, Assembly> IN_PROGRESS = new HashMap<>();

    private static final class Assembly {
        final short[] audio;
        int audioIndex, nextPacketIndex;
        Assembly(int total) { audio = new short[total]; }
    }

    public static final StreamCodec<ByteBuf, SAudioUploadPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SAudioUploadPacket::index,
            ByteBufCodecs.VAR_INT, SAudioUploadPacket::totalSamples,
            ByteBufCodecs.byteArray(MAX_CHUNK_BYTES), SAudioUploadPacket::data,
            SAudioUploadPacket::new
    );

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void handle(IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            UUID playerUUID = player.getUUID();

            // TODO: update once real clientside stuff is done
            if (!player.hasPermissions(2)) {
                IN_PROGRESS.remove(playerUUID);
                return;
            }

            if (index == 0) {
                if (totalSamples <= 0 || MAX_SAMPLES < totalSamples) return;
                IN_PROGRESS.put(playerUUID, new Assembly(totalSamples));
            }

            Assembly assembly = IN_PROGRESS.get(playerUUID);
            if (assembly == null || index != assembly.nextPacketIndex
                    || totalSamples != assembly.audio.length
                    || data.length % 2 != 0  // Must have 2 bytes per short
                    || assembly.audio.length < assembly.audioIndex + data.length / 2) {
                IN_PROGRESS.remove(playerUUID); // malformed or out of order: drop the transfer
                return;
            }

            ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                    .asShortBuffer().get(assembly.audio, assembly.audioIndex, data.length / 2);
            assembly.audioIndex += data.length / 2;
            assembly.nextPacketIndex++;

            if (assembly.audioIndex == assembly.audio.length) {
                IN_PROGRESS.remove(playerUUID);
                UUID tapeId = UUID.randomUUID();
                MinecraftServer server = player.getServer();

                short[] audio;
                synchronized (assembly) {
                    audio = java.util.Arrays.copyOf(assembly.audio, assembly.audio.length);
                }
                TapeRecordings.save(server, tapeId, audio);

                // Assumes save() is thread-safe; if not, call it directly on this thread.
                // Util.ioPool().execute(() -> TapeRecordings.save(server, tapeId, assembly.audio));
                player.sendSystemMessage(Component.literal("Saved tape " + tapeId));

                ItemStack tape = new ItemStack(RadiocraftItems.TAPE_REEL.get());
                tape.set(RadiocraftDataComponent.TAPE_CONTENTS.get(),
                        TapeItem.contents(tape).withRecording(
                                tapeId, Math.round(audio.length / (float) RadioVoiceProcessor.SAMPLE_RATE)));
                player.addItem(tape);
            }
        });
    }

    public static void send(int index, int totalSamples, ByteBuffer chunk) {
        PacketDistributor.sendToServer(new SAudioUploadPacket(index, totalSamples, chunk.array()));
    }

    public static void clear(UUID player) { IN_PROGRESS.remove(player); } // TODO: call on logout
}
