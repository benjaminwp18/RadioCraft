package com.arrl.radiocraft.common.blockentities;

import com.arrl.radiocraft.Radiocraft;
import com.arrl.radiocraft.common.be_networks.DataWire;
import com.arrl.radiocraft.common.blocks.PhonePaBlock;
import com.arrl.radiocraft.common.blocks.SpeakerBlock;
import com.arrl.radiocraft.common.blocks.WiredSpeakerBlock;
import com.arrl.radiocraft.common.init.RadiocraftBlockEntities;
import com.arrl.radiocraft.common.menus.PhonePaMenu;
import com.arrl.radiocraft.common.radio.voice.RadioVoiceProcessor;
import com.arrl.radiocraft.common.radio.voice.RadiocraftVoicePlugin;
import com.arrl.radiocraft.common.radio.voice.SpeakerKind;
import com.arrl.radiocraft.common.telephone.PabxNumbers;
import de.maxhenkel.voicechat.api.ServerLevel;
import de.maxhenkel.voicechat.api.audiochannel.LocationalAudioChannel;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A Phone PA (maintainer, 2026-11-02): a phone on a PABX with no handset. Call its extension and it answers at once,
 * and what the caller says comes out of every speaker touching it or on its data wire. It only listens, and All-Call
 * passes it by, or it would take every All-Call for itself. An extension set on its screen stays through the PABX's
 * renumbering; with none set the PABX gives it one like any phone. Set to local calls only, it answers just the phones
 * on its own PABX and those linked to it.
 */
public class PhonePaBlockEntity extends TelephoneBlockEntity {

	/** A speaker it plays through. */
	public record Target(BlockPos pos, SpeakerKind kind) {}

	private boolean localOnly = false;
	/** The extension was set on its screen, so the PABX leaves it alone. */
	private boolean extensionSet = false;
	private volatile List<Target> speakers = List.of();
	private int clientSpeakers = 0;
	/** What the last save on its screen came to: a translation key and its argument (synced, not saved). */
	private String statusKey = "", statusArg = "";
	private final Map<Target, Output> outputs = new HashMap<>(); // Under this object's lock.

	public PhonePaBlockEntity(BlockPos pos, BlockState state) {
		super(RadiocraftBlockEntities.PHONE_PA.get(), pos, state);
	}

	public boolean isLocalOnly() {
		return localOnly;
	}

	public void setLocalOnly(boolean localOnly) {
		this.localOnly = localOnly;
		sync();
	}

	public List<Target> getSpeakers() {
		return speakers;
	}

	/** How many speakers it plays through (the client's copy, synced). */
	public int getSpeakerCount() {
		return level != null && level.isClientSide ? clientSpeakers : speakers.size();
	}

	public String getStatusKey() {
		return statusKey;
	}

	public String getStatusArg() {
		return statusArg;
	}

	/** The extension as its screen shows it; empty until it has one. */
	public String getExtensionText() {
		return isNumbered() ? Integer.toString(getExtension()) : "";
	}

	/**
	 * The screen's Save: an extension (empty hands it back to the PABX to number) and local calls only, or nothing if the
	 * extension will not do. The result shows on the screen. Returns true if saved.
	 */
	public boolean save(String extension, boolean local) {
		int number = extension.isEmpty() ? -1 : PabxNumbers.parseExtension(extension);
		String why = !extension.isEmpty() && number < 0 ? "screen.radiocraft.phone_pa.bad_extension"
				: number >= 0 && !setExtension(number) ? "screen.radiocraft.phone_pa.extension_taken" : null;
		if(why != null) {
			status(why, extension);
			return false;
		}
		if(number < 0 && extensionSet) { // Numbered again next time the PABX looks.
			extensionSet = false;
			PabxBlockEntity pabx = pabx();
			if(pabx != null)
				pabx.forget(this);
		}
		localOnly = local;
		status("screen.radiocraft.phone_pa.saved", "");
		return true;
	}

	private void status(String key, String arg) {
		statusKey = key;
		statusArg = arg;
		sync();
	}

	@Override
	public boolean setExtension(int number) {
		boolean set = super.setExtension(number);
		if(set)
			extensionSet = true;
		return set;
	}

	@Override
	public void assignExtension(int number) {
		if(!extensionSet)
			super.assignExtension(number);
	}

	@Override
	protected boolean ringsOnAllCall() {
		return false;
	}

	@Override
	protected boolean acceptsCallFrom(TelephoneBlockEntity caller) {
		PabxBlockEntity mine = pabx();
		return !localOnly || (mine != null && mine.linked().contains(caller.pabx()));
	}

	@Override
	protected void onRung() {
		answer();
	}

	@Override
	public boolean canTransmitVoice() {
		return false;
	}

	@Override
	public void acceptVoicePacket(ServerLevel level, short[] rawAudio, UUID sourcePlayer, @Nullable Vec3 speakerPos) {}

	@Override
	protected void serverTick() {
		super.serverTick();
		if(level == null)
			return;
		if(level.getGameTime() % 20 == 0)
			refreshSpeakers();
		boolean inCall = getState() == State.IN_CALL;
		BlockState state = getBlockState();
		if(state.hasProperty(PhonePaBlock.LIT) && state.getValue(PhonePaBlock.LIT) != inCall)
			level.setBlock(worldPosition, state.setValue(PhonePaBlock.LIT, inCall), Block.UPDATE_CLIENTS);
	}

	/** Every speaker touching it or on its data wire. */
	private void refreshSpeakers() {
		List<Target> found = new ArrayList<>();
		for(BlockPos at : DataWire.linked(level, worldPosition)) {
			if(!level.isLoaded(at))
				continue;
			Block block = level.getBlockState(at).getBlock();
			if(block instanceof SpeakerBlock speaker)
				found.add(new Target(at.immutable(), speaker.getKind()));
			else if(block instanceof WiredSpeakerBlock speaker)
				found.add(new Target(at.immutable(), speaker.getKind()));
		}
		if(found.equals(speakers))
			return;
		speakers = List.copyOf(found);
		synchronized(this) {
			outputs.entrySet().removeIf(entry -> {
				if(found.contains(entry.getKey()))
					return false;
				entry.getValue().close();
				return true;
			});
		}
		sync();
	}

	/** A frame from the caller, out of every speaker at phone quality as that speaker colours it. Voice thread. */
	@Override
	protected synchronized void playIncoming(ServerLevel voiceLevel, short[] pcm, double gain) {
		if(RadiocraftVoicePlugin.API == null)
			return;
		for(Target target : speakers) {
			Output output = outputs.computeIfAbsent(target, t -> new Output(voiceLevel, t));
			output.play(pcm.clone(), target.kind().apply(PHONE_QUALITY), Math.min(1.0D, gain) * target.kind().loudness);
		}
	}

	@Override
	public void setRemoved() {
		super.setRemoved();
		if(level != null && !level.isClientSide)
			synchronized(this) {
				outputs.values().forEach(Output::close);
				outputs.clear();
			}
	}

	/** One speaker's channel where it stands, with its own filters and encoder. */
	private final class Output {
		private final LocationalAudioChannel channel;
		private final OpusEncoder encoder = RadiocraftVoicePlugin.API.createEncoder();
		private final RadioVoiceProcessor processor = new RadioVoiceProcessor();
		private final BlockPos pos;

		Output(ServerLevel voiceLevel, Target target) {
			pos = target.pos();
			channel = RadiocraftVoicePlugin.API.createLocationalAudioChannel(UUID.randomUUID(), voiceLevel,
					RadiocraftVoicePlugin.API.createPosition(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D));
			if(channel != null)
				channel.setDistance(target.kind().distance);
		}

		void play(short[] pcm, RadioVoiceProcessor.Settings settings, double level) {
			if(channel == null)
				return;
			Vec3 at = com.arrl.radiocraft.compat.sable.Sublevels.worldCenter(PhonePaBlockEntity.this.level, pos); // Rides a Sable ship.
			channel.updateLocation(RadiocraftVoicePlugin.API.createPosition(at.x, at.y, at.z));
			channel.send(encoder.encode(processor.process(pcm, settings, level, 0.0D)));
		}

		void close() {
			encoder.close();
		}
	}

	@Override
	protected void saveAdditional(@NotNull CompoundTag tag, HolderLookup.@NotNull Provider registries) {
		super.saveAdditional(tag, registries);
		tag.putBoolean("localOnly", localOnly);
		tag.putBoolean("extensionSet", extensionSet);
	}

	@Override
	protected void loadAdditional(@NotNull CompoundTag tag, HolderLookup.@NotNull Provider registries) {
		super.loadAdditional(tag, registries);
		localOnly = tag.getBoolean("localOnly");
		extensionSet = tag.getBoolean("extensionSet");
		if(tag.contains("speakerCount")) { // The client's copy.
			clientSpeakers = tag.getInt("speakerCount");
			statusKey = tag.getString("statusKey");
			statusArg = tag.getString("statusArg");
		}
	}

	@Override
	public @NotNull CompoundTag getUpdateTag(@NotNull HolderLookup.Provider registries) {
		CompoundTag tag = super.getUpdateTag(registries);
		tag.putInt("speakerCount", speakers.size());
		tag.putString("statusKey", statusKey);
		tag.putString("statusArg", statusArg);
		return tag;
	}

	@Override
	public @NotNull Component getDisplayName() {
		return Component.translatable(Radiocraft.translationKey("container", "phone_pa"));
	}

	@Override
	public @Nullable AbstractContainerMenu createMenu(int id, @NotNull Inventory inventory, @NotNull Player player) {
		if(!statusKey.isEmpty())
			status("", ""); // A fresh screen starts with no message.
		return new PhonePaMenu(id, this);
	}
}
