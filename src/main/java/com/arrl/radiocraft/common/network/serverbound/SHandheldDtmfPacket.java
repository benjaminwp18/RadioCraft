package com.arrl.radiocraft.common.network.serverbound;

import com.arrl.radiocraft.Radiocraft;
import com.arrl.radiocraft.common.radio.voice.Dtmf;
import com.arrl.radiocraft.common.radio.voice.handheld.PlayerRadioManager;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

/** A key pressed on a handheld's DTMF page: the handheld in inventory slot {@code index} sends its tones. */
public record SHandheldDtmfPacket(int index, int key) implements CustomPacketPayload {

	public static final Type<SHandheldDtmfPacket> TYPE = new Type<>(Radiocraft.id("s_handheld_dtmf"));

	public static final StreamCodec<ByteBuf, SHandheldDtmfPacket> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, SHandheldDtmfPacket::index,
			ByteBufCodecs.VAR_INT, SHandheldDtmfPacket::key,
			SHandheldDtmfPacket::new);

	@Override
	public @NotNull Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public void handle(IPayloadContext context) {
		context.enqueueWork(() -> {
			char c = (char)key;
			if(!Dtmf.isKey(c) || index < 0 || index >= context.player().getInventory().getContainerSize())
				return;
			PlayerRadioManager.get(context.player().getUUID()).ifPresent(radio -> radio.sendDtmf(index, Character.toUpperCase(c)));
		});
	}

	public static void send(int index, char key) {
		PacketDistributor.sendToServer(new SHandheldDtmfPacket(index, key));
	}
}
