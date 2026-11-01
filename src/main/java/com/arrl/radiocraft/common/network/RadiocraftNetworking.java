package com.arrl.radiocraft.common.network;

import com.arrl.radiocraft.common.network.clientbound.*;
import com.arrl.radiocraft.common.network.serverbound.*;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public class RadiocraftNetworking {

    // For consistency, this should probably be its own class in init, but since there doesn't seem to be an available differedRegistry for this anyways, I'll forgo the clutter
    public static void register(PayloadRegistrar registrar) {
        registrar.playToClient(CWireEndPosUpdatePacket.TYPE, CWireEndPosUpdatePacket.STREAM_CODEC, CWireEndPosUpdatePacket::handle);
        registrar.playToClient(CNoisePacket.TYPE, CNoisePacket.STREAM_CODEC, CNoisePacket::handle);
        registrar.playToClient(CCallsignPromptPacket.TYPE, CCallsignPromptPacket.STREAM_CODEC, CCallsignPromptPacket::handle);
        registrar.playToClient(CCWBufferPacket.TYPE, CCWBufferPacket.STREAM_CODEC, CCWBufferPacket::handle);
        registrar.playToClient(CAuroraPacket.TYPE, CAuroraPacket.STREAM_CODEC, CAuroraPacket::handle);
        registrar.playToClient(CInterferencePacket.TYPE, CInterferencePacket.STREAM_CODEC, CInterferencePacket::handle);
        registrar.playToClient(CManpackPacket.TYPE, CManpackPacket.STREAM_CODEC, CManpackPacket::handle);
        registrar.playToClient(CManpackCWPacket.TYPE, CManpackCWPacket.STREAM_CODEC, CManpackCWPacket::handle);
        registrar.playToClient(CHandheldDfPacket.TYPE, CHandheldDfPacket.STREAM_CODEC, CHandheldDfPacket::handle);
        registrar.playToClient(CModerationPacket.TYPE, CModerationPacket.STREAM_CODEC, CModerationPacket::handle);

        registrar.playToServer(SHandheldRadioUpdatePacket.TYPE, SHandheldRadioUpdatePacket.STREAM_CODEC, SHandheldRadioUpdatePacket::handle);
        registrar.playToServer(SBlockRadioControlPacket.TYPE, SBlockRadioControlPacket.STREAM_CODEC, SBlockRadioControlPacket::handle);
        registrar.playToServer(SPlayerClickHoldUpdate.TYPE, SPlayerClickHoldUpdate.STREAM_CODEC, SPlayerClickHoldUpdate::handle);
        registrar.playToServer(SWornPttPacket.TYPE, SWornPttPacket.STREAM_CODEC, SWornPttPacket::handle);
        registrar.playToServer(SCWBufferPacket.TYPE, SCWBufferPacket.STREAM_CODEC, SCWBufferPacket::handle);
        registrar.playToServer(SClaimCallsignPacket.TYPE, SClaimCallsignPacket.STREAM_CODEC, SClaimCallsignPacket::handle);
        registrar.playToServer(SRepeaterSettingsPacket.TYPE, SRepeaterSettingsPacket.STREAM_CODEC, SRepeaterSettingsPacket::handle);
        registrar.playToServer(SStationPacket.TYPE, SStationPacket.STREAM_CODEC, SStationPacket::handle);
        registrar.playToServer(SManpackPacket.TYPE, SManpackPacket.STREAM_CODEC, SManpackPacket::handle);
        registrar.playToServer(SManpackCWPacket.TYPE, SManpackCWPacket.STREAM_CODEC, SManpackCWPacket::handle);
        registrar.playToServer(SHandheldDfFrequencyPacket.TYPE, SHandheldDfFrequencyPacket.STREAM_CODEC, SHandheldDfFrequencyPacket::handle);
        registrar.playToServer(SAntennaTunerDialPacket.TYPE, SAntennaTunerDialPacket.STREAM_CODEC, SAntennaTunerDialPacket::handle);
        registrar.playToServer(SDuplexerScrewPacket.TYPE, SDuplexerScrewPacket.STREAM_CODEC, SDuplexerScrewPacket::handle);
        registrar.playToServer(SChargeControllerTogglePacket.TYPE, SChargeControllerTogglePacket.STREAM_CODEC, SChargeControllerTogglePacket::handle);
        registrar.playToServer(SHandheldDtmfPacket.TYPE, SHandheldDtmfPacket.STREAM_CODEC, SHandheldDtmfPacket::handle);
        registrar.playToServer(SHandheldMemoryPacket.TYPE, SHandheldMemoryPacket.STREAM_CODEC, SHandheldMemoryPacket::handle);
        registrar.playToServer(SHandheldAprsPacket.TYPE, SHandheldAprsPacket.STREAM_CODEC, SHandheldAprsPacket::handle);
        registrar.playToServer(SHandheldAprsSettingsPacket.TYPE, SHandheldAprsSettingsPacket.STREAM_CODEC, SHandheldAprsSettingsPacket::handle);
        registrar.playToServer(SRadioScrollTunePacket.TYPE, SRadioScrollTunePacket.STREAM_CODEC, SRadioScrollTunePacket::handle);
        registrar.playToServer(SAudioUploadPacket.TYPE, SAudioUploadPacket.STREAM_CODEC, SAudioUploadPacket::handle);  // TODO: consider handling on network thread (https://docs.neoforged.net/docs/networking/payload/)
    }
}
