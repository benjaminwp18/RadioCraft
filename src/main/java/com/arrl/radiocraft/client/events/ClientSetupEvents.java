package com.arrl.radiocraft.client.events;


import com.arrl.radiocraft.Radiocraft;
import com.arrl.radiocraft.client.commands.DebugGiveDiscTapeCommand;
import com.arrl.radiocraft.client.entity.AntennaWireEntityRenderer;
import com.arrl.radiocraft.client.screens.*;
import com.arrl.radiocraft.client.screens.radios.*;
import com.arrl.radiocraft.common.init.RadiocraftEntityTypes;
import com.arrl.radiocraft.common.init.RadiocraftBlockEntities;
import com.arrl.radiocraft.client.render.DeskChargerBlockRenderer;
import com.arrl.radiocraft.client.render.RadioDisplayRenderer;
import com.arrl.radiocraft.client.render.RadioDisplays;
import com.arrl.radiocraft.common.init.RadiocraftMenuTypes;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

@EventBusSubscriber(modid = Radiocraft.MOD_ID, value = Dist.CLIENT)
public class ClientSetupEvents {

	@SubscribeEvent
	public static void registerScreens(RegisterMenuScreensEvent event) {
		event.register(RadiocraftMenuTypes.LARGE_BATTERY.get(), LargeBatteryScreen::new);
		event.register(RadiocraftMenuTypes.SOLAR_PANEL.get(), SolarPanelScreen::new);
		event.register(RadiocraftMenuTypes.CHARGE_CONTROLLER.get(), ChargeControllerScreen::new);
		event.register(RadiocraftMenuTypes.HF_RADIO_10M.get(), HFRadio10mScreen::new);
		event.register(RadiocraftMenuTypes.HF_RADIO_20M.get(), HFRadio20mScreen::new);
		event.register(RadiocraftMenuTypes.HF_RADIO_40M.get(), HFRadio40mScreen::new);
		event.register(RadiocraftMenuTypes.HF_RADIO_80M.get(), HFRadio80mScreen::new);
		event.register(RadiocraftMenuTypes.HF_RADIO_ALL_BAND.get(), HFRadioAllBandScreen::new);
		event.register(RadiocraftMenuTypes.QRP_RADIO_20M.get(), QRPRadio20mScreen::new);
		event.register(RadiocraftMenuTypes.QRP_RADIO_40M.get(), QRPRadio40mScreen::new);
		event.register(RadiocraftMenuTypes.VHF_BASE_STATION.get(), VHFBaseStationScreen::new);
		event.register(RadiocraftMenuTypes.VHF_RECEIVER.get(), VHFReceiverScreen::new);
		event.register(RadiocraftMenuTypes.HF_RECEIVER.get(), HFReceiverScreen::new);
		event.register(RadiocraftMenuTypes.DESK_CHARGER.get(), DeskChargerScreen::new);
		event.register(RadiocraftMenuTypes.RADIO_INTERFACE.get(), RadioInterfaceScreen::new);
		event.register(RadiocraftMenuTypes.DTMF_DECODER.get(), DtmfDecoderScreen::new);
		event.register(RadiocraftMenuTypes.DTMF_ENCODER.get(), DtmfEncoderScreen::new);
		event.register(RadiocraftMenuTypes.TELETYPE.get(), TeletypeScreen::new);
		event.register(RadiocraftMenuTypes.APRS_TERMINAL.get(), AprsTerminalScreen::new);
		event.register(RadiocraftMenuTypes.RADIO_FAX.get(), RadioFaxScreen::new);
		event.register(RadiocraftMenuTypes.DUPLEXER.get(), DuplexerScreen::new);
		event.register(RadiocraftMenuTypes.ANTENNA_TUNER.get(), AntennaTunerScreen::new);
		event.register(RadiocraftMenuTypes.VHF_REPEATER.get(), VHFRepeaterScreen::new);
		event.register(RadiocraftMenuTypes.DIRECTION_FINDER.get(), DirectionFinderScreen::new);
		event.register(RadiocraftMenuTypes.MICROWAVE_RADIO.get(), MicrowaveRadioScreen::new);
		event.register(RadiocraftMenuTypes.TELEPHONE.get(), TelephoneScreen::new);
		event.register(RadiocraftMenuTypes.PABX.get(), com.arrl.radiocraft.client.screens.PabxScreen::new);
		event.register(RadiocraftMenuTypes.PHONE_PA.get(), com.arrl.radiocraft.client.screens.PhonePaScreen::new);
		event.register(RadiocraftMenuTypes.MODERATION_COMPUTER.get(), com.arrl.radiocraft.client.screens.moderation.ModerationComputerScreen::new);
		event.register(RadiocraftMenuTypes.REPEATER_AUTOPATCH.get(), com.arrl.radiocraft.client.screens.RepeaterAutopatchScreen::new);
		event.register(RadiocraftMenuTypes.IONOSPHERIC_PROCESSOR.get(), com.arrl.radiocraft.client.screens.IonosphericProcessorScreen::new);
		event.register(RadiocraftMenuTypes.TAPE_PLAYER.get(), com.arrl.radiocraft.client.screens.TapePlayerScreen::new);
		event.register(RadiocraftMenuTypes.WEFAX_RECEIVER.get(), com.arrl.radiocraft.client.screens.WefaxReceiverScreen::new);
		event.register(RadiocraftMenuTypes.COMMERCIAL_RADIO_MODULE.get(), com.arrl.radiocraft.client.screens.CommercialRadioModuleScreen::new);
	}
	
	@SubscribeEvent
	public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
		event.registerBlockEntityRenderer(com.arrl.radiocraft.common.init.RadiocraftBlockEntities.TAPE_PLAYER.get(), com.arrl.radiocraft.client.render.TapePlayerRenderer::new);
		event.registerBlockEntityRenderer(com.arrl.radiocraft.common.init.RadiocraftBlockEntities.WEATHER_STATION.get(), com.arrl.radiocraft.client.render.WeatherStationRenderer::new);
		if(com.arrl.radiocraft.compat.create.CreateCompat.isActive())
			com.arrl.radiocraft.compat.create.CreateAlternatorClient.registerRenderers(event);
		event.registerEntityRenderer(RadiocraftEntityTypes.ANTENNA_WIRE.get(), AntennaWireEntityRenderer::new);
		// Desk charger geo renderer
		// Cast to the concrete BlockEntityType generic to satisfy method signature
		// Use a lambda with an unchecked raw cast to avoid generic signature issues in the registration helper.
		event.registerBlockEntityRenderer((net.minecraft.world.level.block.entity.BlockEntityType) RadiocraftBlockEntities.DESK_CHARGER.get(), ctx -> new DeskChargerBlockRenderer(ctx));
		event.registerBlockEntityRenderer(RadiocraftBlockEntities.PASSTHROUGH.get(), com.arrl.radiocraft.client.render.PassthroughRenderer::new);
		event.registerBlockEntityRenderer(RadiocraftBlockEntities.TELEPHONE.get(), com.arrl.radiocraft.client.render.TelephoneRenderer::new);
		event.registerBlockEntityRenderer(RadiocraftBlockEntities.MODERATION_COMPUTER.get(), com.arrl.radiocraft.client.render.ModerationComputerRenderer::new);
		event.registerBlockEntityRenderer(RadiocraftBlockEntities.FIELD_PHONE.get(), com.arrl.radiocraft.client.render.FieldPhoneRenderer::new);
		// Satellite dish geo renderer
		event.registerBlockEntityRenderer((net.minecraft.world.level.block.entity.BlockEntityType) RadiocraftBlockEntities.SATELLITE_DISH.get(), ctx -> new com.arrl.radiocraft.client.render.SatelliteDishRenderer(ctx));
		// The frequency, mode and S-meter on each radio's screen; a radio whose model has no DISPLAY draws nothing.
		event.registerBlockEntityRenderer(RadiocraftBlockEntities.AUTO_TUNER.get(), com.arrl.radiocraft.client.render.AutoTunerDisplayRenderer::new);
		event.registerBlockEntityRenderer(RadiocraftBlockEntities.MICROWAVE_HORN.get(), com.arrl.radiocraft.client.render.MicrowaveHornRenderer::new);
		event.registerBlockEntityRenderer(RadiocraftBlockEntities.HF_RADIO_10M.get(), RadioDisplayRenderer::new);
		event.registerBlockEntityRenderer(RadiocraftBlockEntities.HF_RADIO_20M.get(), RadioDisplayRenderer::new);
		event.registerBlockEntityRenderer(RadiocraftBlockEntities.HF_RADIO_40M.get(), RadioDisplayRenderer::new);
		event.registerBlockEntityRenderer(RadiocraftBlockEntities.HF_RADIO_80M.get(), RadioDisplayRenderer::new);
		event.registerBlockEntityRenderer(RadiocraftBlockEntities.HF_RADIO_ALL_BAND.get(), RadioDisplayRenderer::new);
		event.registerBlockEntityRenderer(RadiocraftBlockEntities.HF_RECEIVER.get(), RadioDisplayRenderer::new);
		event.registerBlockEntityRenderer(RadiocraftBlockEntities.VHF_BASE_STATION.get(), RadioDisplayRenderer::new);
		event.registerBlockEntityRenderer(RadiocraftBlockEntities.VHF_RECEIVER.get(), RadioDisplayRenderer::new);
		event.registerBlockEntityRenderer(RadiocraftBlockEntities.QRP_RADIO_20M.get(), RadioDisplayRenderer::new); // No screen; draws the mic cord.
		event.registerBlockEntityRenderer(RadiocraftBlockEntities.QRP_RADIO_40M.get(), RadioDisplayRenderer::new);
	}

	/** Radio screens are read from their models (see {@link RadioDisplays}): read them afresh after a resource reload. */
	@SubscribeEvent
	public static void registerReloadListeners(net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent event) {
		event.registerReloadListener((net.minecraft.server.packs.resources.ResourceManagerReloadListener)manager -> RadioDisplays.clear());
	}

	/** The handheld direction finder's lamp blinks with its beeps (see {@link com.arrl.radiocraft.client.HandheldDfClient}). */
	@SubscribeEvent
	public static void clientSetup(net.neoforged.fml.event.lifecycle.FMLClientSetupEvent event) {
		// The Config button in the mod list: NeoForge's own screen over the client, common and server (in a world) settings.
		net.neoforged.fml.ModList.get().getModContainerById(Radiocraft.MOD_ID).ifPresent(container -> container.registerExtensionPoint(
				net.neoforged.neoforge.client.gui.IConfigScreenFactory.class, net.neoforged.neoforge.client.gui.ConfigurationScreen::new));
		if(com.arrl.radiocraft.compat.create.CreateCompat.isActive())
			event.enqueueWork(com.arrl.radiocraft.compat.create.CreateAlternatorClient::registerVisual);
		if(com.arrl.radiocraft.compat.curios.CuriosCompat.isLoaded()) // The handheld on the belt.
			event.enqueueWork(com.arrl.radiocraft.compat.curios.HandheldBeltRenderer::register);
		event.enqueueWork(() -> net.minecraft.client.renderer.item.ItemProperties.register(com.arrl.radiocraft.common.init.RadiocraftItems.HANDHELD_DIRECTION_FINDER.get(),
				Radiocraft.id("lit"), (stack, level, entity, seed) -> entity == net.minecraft.client.Minecraft.getInstance().player && com.arrl.radiocraft.client.HandheldDfClient.isLit() ? 1.0F : 0.0F));
	}

	/** Tape reels: the reel and its label tinted by the tape's colourway. */
	@SubscribeEvent
	public static void registerItemColours(net.neoforged.neoforge.client.event.RegisterColorHandlersEvent.Item event) {
		event.register((stack, tint) -> tint == 1 ? com.arrl.radiocraft.common.items.TapeItem.reelColour(stack)
				: tint == 2 ? com.arrl.radiocraft.common.items.TapeItem.labelColour(stack) : -1, com.arrl.radiocraft.common.init.RadiocraftItems.TAPE_REEL.get());
	}

	/** The HF manpack on a player's back is drawn from its block model, which is loaded on its own for that. */
	@SubscribeEvent
	public static void registerAdditionalModels(net.neoforged.neoforge.client.event.ModelEvent.RegisterAdditional event) {
		event.register(com.arrl.radiocraft.client.render.ManpackLayer.MODEL);
		event.register(com.arrl.radiocraft.client.render.WeatherStationRenderer.VANE);
		event.register(com.arrl.radiocraft.client.render.WeatherStationRenderer.CUPS);
		event.register(com.arrl.radiocraft.client.render.TapePlayerRenderer.REEL);
		event.register(com.arrl.radiocraft.client.render.HandheldItemRenderer.RX_LED);
	}

	/** The VHF handheld's item models hand their drawing to {@link com.arrl.radiocraft.client.render.HandheldItemRenderer} (its live LCD). */
	@SubscribeEvent
	public static void modifyBakedModels(net.neoforged.neoforge.client.event.ModelEvent.ModifyBakingResult event) {
		com.arrl.radiocraft.client.render.HandheldItemRenderer.wrapModels(event.getModels());
	}

	/** A telephone handset raises its holder's arm to the face (vanilla's goat horn pose), the handset by the ear. */
	@SubscribeEvent
	public static void registerClientExtensions(net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent event) {
		event.registerItem(new net.neoforged.neoforge.client.extensions.common.IClientItemExtensions() {
			@Override
			public net.minecraft.client.model.HumanoidModel.ArmPose getArmPose(net.minecraft.world.entity.LivingEntity entity, net.minecraft.world.InteractionHand hand, net.minecraft.world.item.ItemStack stack) {
				return net.minecraft.client.model.HumanoidModel.ArmPose.TOOT_HORN;
			}
		}, com.arrl.radiocraft.common.init.RadiocraftItems.TELEPHONE_HANDSET.get(), com.arrl.radiocraft.common.init.RadiocraftItems.FIELD_PHONE_HANDSET.get());
		event.registerItem(new net.neoforged.neoforge.client.extensions.common.IClientItemExtensions() { // The handheld's live LCD.
			@Override
			public net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer getCustomRenderer() {
				return com.arrl.radiocraft.client.render.HandheldItemRenderer.get();
			}
		}, com.arrl.radiocraft.common.init.RadiocraftItems.VHF_HANDHELD_VARIANTS.values().toArray(net.minecraft.world.item.Item[]::new));
	}

	@SubscribeEvent
	public static void addLayers(EntityRenderersEvent.AddLayers event) {
		for(net.minecraft.client.resources.PlayerSkin.Model skin : event.getSkins()) {
			if(event.getSkin(skin) instanceof net.minecraft.client.renderer.entity.player.PlayerRenderer renderer) {
				renderer.addLayer(new com.arrl.radiocraft.client.render.ManpackLayer(renderer));
				renderer.addLayer(new com.arrl.radiocraft.client.render.PhoneEarLayer(renderer));
			}
		}
	}

	/** The wire model works its look out from the neighbours, see {@link com.arrl.radiocraft.client.model.WireModel}. */
	@SubscribeEvent
	public static void registerGeometryLoaders(net.neoforged.neoforge.client.event.ModelEvent.RegisterGeometryLoaders event) {
		event.register(com.arrl.radiocraft.client.model.WireModel.LOADER_ID, com.arrl.radiocraft.client.model.WireModel.Loader.INSTANCE);
		event.register(com.arrl.radiocraft.client.model.TowerBeamModel.LOADER_ID, com.arrl.radiocraft.client.model.TowerBeamModel.Loader.INSTANCE);
	}

	@SubscribeEvent
	public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
		event.getDispatcher().register(DebugGiveDiscTapeCommand.makeBuilder());
	}
}
