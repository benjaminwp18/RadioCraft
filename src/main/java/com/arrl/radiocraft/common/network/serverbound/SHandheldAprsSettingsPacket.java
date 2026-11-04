package com.arrl.radiocraft.common.network.serverbound;

import com.arrl.radiocraft.Radiocraft;
import com.arrl.radiocraft.common.datacomponents.HandheldAprs;
import com.arrl.radiocraft.common.init.RadiocraftDataComponent;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

/**
 * The beacon settings of the APRS module in the handheld in inventory slot {@code index}, after the holder changed one
 * on its BEACON page. Every value is checked here; switching the beacon on sends one right away.
 */
public record SHandheldAprsSettingsPacket(int index, HandheldAprs.Settings settings) implements CustomPacketPayload {

	public static final Type<SHandheldAprsSettingsPacket> TYPE = new Type<>(Radiocraft.id("s_handheld_aprs_settings"));

	public static final StreamCodec<ByteBuf, SHandheldAprsSettingsPacket> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, SHandheldAprsSettingsPacket::index,
			HandheldAprs.Settings.STREAM_CODEC, SHandheldAprsSettingsPacket::settings,
			SHandheldAprsSettingsPacket::new);

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
			HandheldAprs changed = settings.beacon() && !aprs.beacon() ? aprs.withBeacon(true) : aprs;
			stack.set(RadiocraftDataComponent.HANDHELD_APRS.value(), changed.withSettings(settings));
		});
	}

	public static void send(int index, HandheldAprs.Settings settings) {
		PacketDistributor.sendToServer(new SHandheldAprsSettingsPacket(index, settings));
	}
}
