package com.arrl.radiocraft.common.network.clientbound;

import com.arrl.radiocraft.Radiocraft;
import com.arrl.radiocraft.common.moderation.ComputerRequests;
import io.netty.buffer.ByteBuf;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

/**
 * An answer from the moderation computer's server side to its screen: {@code kind} names it ("live", "page",
 * "detail"...) and {@code data} carries it (see {@link ComputerRequests}). Answers are paged and capped, so one stays
 * far under the packet size limit.
 */
public record CModerationPacket(String kind, CompoundTag data) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<CModerationPacket> TYPE = new CustomPacketPayload.Type<>(Radiocraft.id("c_moderation"));

	public static final StreamCodec<ByteBuf, CModerationPacket> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.STRING_UTF8, CModerationPacket::kind,
			ByteBufCodecs.COMPOUND_TAG, CModerationPacket::data,
			CModerationPacket::new
	);

	@Override
	public @NotNull Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public static void handle(final CModerationPacket packet, final IPayloadContext context) {
		context.enqueueWork(() -> com.arrl.radiocraft.client.screens.moderation.ModerationComputerScreen.receive(packet.kind(), packet.data()));
	}

	public static void send(ServerPlayer player, ComputerRequests.Reply reply) {
		PacketDistributor.sendToPlayer(player, new CModerationPacket(reply.kind(), reply.data()));
	}
}
