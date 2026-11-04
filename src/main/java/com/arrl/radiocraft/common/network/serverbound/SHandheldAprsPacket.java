package com.arrl.radiocraft.common.network.serverbound;

import com.arrl.radiocraft.Radiocraft;
import com.arrl.radiocraft.common.datacomponents.HandheldAprs;
import com.arrl.radiocraft.common.init.RadiocraftDataComponent;
import com.arrl.radiocraft.common.radio.data.TncPacket;
import com.arrl.radiocraft.common.radio.voice.handheld.PlayerRadioManager;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

/**
 * Something done on the APRS page of the handheld in inventory slot {@code index}: switch the beacon or the mute
 * ({@code value} 1 for on), mark message {@code value} read, or send {@code text} to {@code to}.
 */
public record SHandheldAprsPacket(int index, Action action, int value, String to, String text) implements CustomPacketPayload {

	public enum Action { BEACON, MUTE, READ, SEND }

	public static final Type<SHandheldAprsPacket> TYPE = new Type<>(Radiocraft.id("s_handheld_aprs"));

	public static final StreamCodec<ByteBuf, SHandheldAprsPacket> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, SHandheldAprsPacket::index,
			ByteBufCodecs.VAR_INT.map(i -> Action.values()[Math.floorMod(i, Action.values().length)], Action::ordinal), SHandheldAprsPacket::action,
			ByteBufCodecs.VAR_INT, SHandheldAprsPacket::value,
			ByteBufCodecs.stringUtf8(TncPacket.CALLSIGN_MAX * 2), SHandheldAprsPacket::to,
			ByteBufCodecs.stringUtf8(TncPacket.MESSAGE_TEXT_MAX * 2), SHandheldAprsPacket::text,
			SHandheldAprsPacket::new);

	@Override
	public @NotNull Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public void handle(IPayloadContext context) {
		context.enqueueWork(() -> {
			if(index < 0 || index >= context.player().getInventory().getContainerSize())
				return;
			ItemStack stack = context.player().getInventory().getItem(index);
			HandheldAprs aprs = stack.get(RadiocraftDataComponent.HANDHELD_APRS.value());
			if(aprs == null)
				return;
			switch(action) {
				case BEACON -> stack.set(RadiocraftDataComponent.HANDHELD_APRS.value(), aprs.withBeacon(value != 0));
				case MUTE -> stack.set(RadiocraftDataComponent.HANDHELD_APRS.value(), aprs.withMute(value != 0));
				case READ -> stack.set(RadiocraftDataComponent.HANDHELD_APRS.value(), aprs.withMessage(value, message ->
						new HandheldAprs.Message(message.peer(), message.text(), message.number(), message.status(), true, message.time())));
				case SEND -> PlayerRadioManager.get(context.player().getUUID()).ifPresent(radio -> radio.sendAprsMessage(index, to, text));
			}
		});
	}

	public static void send(int index, Action action, int value) {
		PacketDistributor.sendToServer(new SHandheldAprsPacket(index, action, value, "", ""));
	}

	public static void sendMessage(int index, String to, String text) {
		PacketDistributor.sendToServer(new SHandheldAprsPacket(index, Action.SEND, 0, to, text));
	}
}
