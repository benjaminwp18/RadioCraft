package com.arrl.radiocraft.common.blockentities;

import com.arrl.radiocraft.Radiocraft;
import com.arrl.radiocraft.RadiocraftServerConfig;
import com.arrl.radiocraft.common.be_networks.DataWire;
import com.arrl.radiocraft.common.blockentities.radio.RadioBlockEntity;
import com.arrl.radiocraft.common.blocks.RepeaterAutopatchBlock;
import com.arrl.radiocraft.common.init.RadiocraftBlockEntities;
import com.arrl.radiocraft.common.menus.RepeaterAutopatchMenu;
import com.arrl.radiocraft.common.radio.voice.AutopatchTones;
import com.arrl.radiocraft.common.radio.voice.RadioVoiceProcessor;
import com.arrl.radiocraft.common.telephone.AutopatchControl;
import com.arrl.radiocraft.common.telephone.PabxNumbers;
import de.maxhenkel.voicechat.api.ServerLevel;
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

import java.util.List;
import java.util.UUID;

/**
 * The Repeater Autopatch Controller (maintainer, 2026-11-04): a phone on a PABX (touching it or on its data wire) that
 * is also linked to a VHF repeater the same way, so radio users can make and take phone calls through the repeater.
 * <p>
 * Key its activation code on the repeater's input and it arms, with a burst of dial tone; then key a number and '*' to
 * dial it, or '#' to give up ({@link AutopatchControl}; ten seconds with no key gives up too). Ringback goes out while
 * the far phone rings, reorder if the call cannot be made. A call to its extension is answered after a short ring on
 * the air.
 * <p>
 * During a call what the repeater hears on its input goes down the line, and the phone's side goes out on the
 * repeater's output, but only while nothing is coming in: the radio side is simplex, so phone audio that arrives while
 * someone is transmitting is dropped ({@link VHFRepeaterBlockEntity#transmitPatched}). '#' on the radio hangs up, as
 * does the far end hanging up, or nothing heard from the radio side for {@code autopatch_inactivity_seconds}.
 */
public class RepeaterAutopatchBlockEntity extends TelephoneBlockEntity implements DtmfKeyReceiver {

	/** Ticks armed with no key before it gives up: 10 seconds. */
	public static final int ARMED_TIMEOUT_TICKS = 200;

	private final AutopatchControl control = new AutopatchControl(ARMED_TIMEOUT_TICKS);
	/** The extension was set on its screen, so the PABX leaves it alone. */
	private boolean extensionSet = false;
	@Nullable
	private volatile VHFRepeaterBlockEntity repeater = null;
	private State lastState = State.IDLE;
	/** Game time an incoming call is put through, after its ring on the air; -1 for none. */
	private long answerAt = -1L;
	/** It hung up itself ('#', the inactivity timeout), so the call ending is no failure. */
	private boolean endedHere = false;
	/** Game time the radio side was last heard during a call (voice thread writes it). */
	private volatile long lastRadioGameTime = -1L;
	private final RadioVoiceProcessor processor = new RadioVoiceProcessor(); // Under this object's lock.
	/** What the last save on its screen came to: a translation key and its argument (synced, not saved). */
	private String statusKey = "", statusArg = "";
	// The client's copies, for the screen.
	private boolean clientRepeater = false;
	private boolean clientArmed = false;
	private String clientNumber = "";

	public RepeaterAutopatchBlockEntity(BlockPos pos, BlockState state) {
		super(RadiocraftBlockEntities.REPEATER_AUTOPATCH.get(), pos, state);
	}

	// -------------------- SETTINGS --------------------

	public String getCode() {
		return control.getCode();
	}

	/** The extension as its screen shows it; empty until it has one. */
	public String getExtensionText() {
		return isNumbered() ? Integer.toString(getExtension()) : "";
	}

	public String getStatusKey() {
		return statusKey;
	}

	public String getStatusArg() {
		return statusArg;
	}

	/** True while it is linked to a repeater (the client's copy is synced). */
	public boolean hasRepeater() {
		return level != null && level.isClientSide ? clientRepeater : repeater != null;
	}

	/** True while a radio user has armed it and is keying a number (the client's copy is synced). */
	public boolean isArmed() {
		return level != null && level.isClientSide ? clientArmed : control.isArmed();
	}

	/** The number keyed so far while armed (the client's copy is synced). */
	public String getArmedNumber() {
		return level != null && level.isClientSide ? clientNumber : control.getNumber();
	}

	/**
	 * The screen's Save: an extension (empty hands it back to the PABX to number) and an activation code (0-9 and A-D, at
	 * most {@link AutopatchControl#MAX_CODE} keys; empty turns dialling out off), or nothing if either will not do. The
	 * result shows on the screen. Returns true if saved.
	 */
	public boolean save(String extension, String code) {
		int number = extension.isEmpty() ? -1 : PabxNumbers.parseExtension(extension);
		String why = !extension.isEmpty() && number < 0 ? "screen.radiocraft.repeater_autopatch.bad_extension"
				: !AutopatchControl.validCode(code) ? "screen.radiocraft.repeater_autopatch.bad_code"
				: number >= 0 && !setExtension(number) ? "screen.radiocraft.repeater_autopatch.extension_taken" : null;
		if(why != null) {
			status(why, why.endsWith("bad_code") ? Integer.toString(AutopatchControl.MAX_CODE) : extension);
			return false;
		}
		if(number < 0 && extensionSet) { // Numbered again next time the PABX looks.
			extensionSet = false;
			PabxBlockEntity pabx = pabx();
			if(pabx != null)
				pabx.forget(this);
		}
		control.setCode(code);
		status("screen.radiocraft.repeater_autopatch.saved", "");
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

	// -------------------- THE REPEATER --------------------

	/** The repeater it works through: the nearest touching it or on its data wire, or null. */
	@Nullable
	public VHFRepeaterBlockEntity getRepeater() {
		return repeater;
	}

	/** Its repeater, if it is there and can put anything out. */
	@Nullable
	private VHFRepeaterBlockEntity working() {
		VHFRepeaterBlockEntity found = repeater;
		return found != null && !found.isRemoved() && found.canPatch() ? found : null;
	}

	private void tones(List<short[]> frames) {
		VHFRepeaterBlockEntity found = working();
		if(found != null)
			found.queueTones(frames);
	}

	@Override
	public void receiveDtmf(RadioBlockEntity radio, char key) {
		if(level == null || radio != repeater)
			return; // Keys from another radio on the same wire.
		long now = level.getGameTime();
		switch(control.key(key, now, getState() != State.IDLE)) {
			case ARMED -> tones(AutopatchTones.dialTone(1.0D));
			case DIAL -> {
				String number = control.getNumber();
				control.reset();
				dial(number);
				if(getState() == State.IDLE) // Not found, busy, no line: the status says which.
					tones(AutopatchTones.reorder());
			}
			case CANCEL -> tones(AutopatchTones.ended());
			case HANG_UP -> hangUp(); // The ended beeps follow from the change of state.
			default -> {}
		}
		sync();
	}

	// -------------------- CALLS --------------------

	/** A call to its extension: a ring on the air, then it is put through. With no repeater to take it, it is turned away. */
	@Override
	protected void onRung() {
		VHFRepeaterBlockEntity found = working();
		if(found == null || level == null) {
			hangUp();
			return;
		}
		control.reset();
		List<short[]> ring = AutopatchTones.ringBurst();
		found.queueTones(ring);
		answerAt = level.getGameTime() + AutopatchTones.ticks(ring) + 5;
	}

	/** All-Call passes it by, or every All-Call would end up on the air. */
	@Override
	protected boolean ringsOnAllCall() {
		return false;
	}

	@Override
	protected void serverTick() {
		super.serverTick();
		if(level == null)
			return;
		long now = level.getGameTime();
		if(now % 20 == 0) {
			List<VHFRepeaterBlockEntity> found = DataWire.linked(level, worldPosition, VHFRepeaterBlockEntity.class);
			VHFRepeaterBlockEntity nearest = found.isEmpty() ? null : found.get(0);
			if(nearest != repeater) {
				repeater = nearest;
				control.reset();
				sync();
			}
		}
		VHFRepeaterBlockEntity linked = repeater;
		if(linked != null && !linked.isRemoved())
			linked.attachPatch(this);
		if(control.tick(now) == AutopatchControl.Action.TIMEOUT) {
			tones(AutopatchTones.ended());
			sync();
		}

		State state = getState();
		if(state != lastState) {
			changed(lastState, state, now);
			lastState = state;
		}
		if(state == State.RINGING && answerAt >= 0L && now >= answerAt) {
			answerAt = -1L;
			answer();
		}
		if(state == State.CALLING && getStateTicks() % AutopatchTones.RINGBACK_CYCLE_TICKS == 0)
			tones(AutopatchTones.ringback());
		int inactivity = RadiocraftServerConfig.AUTOPATCH_INACTIVITY_SECONDS.get() * 20;
		if(state == State.IN_CALL && inactivity > 0 && now - lastRadioGameTime > inactivity)
			hangUp();

		boolean lit = control.isArmed() || getState() != State.IDLE;
		BlockState blockState = getBlockState();
		if(blockState.hasProperty(RepeaterAutopatchBlock.LIT) && blockState.getValue(RepeaterAutopatchBlock.LIT) != lit)
			level.setBlock(worldPosition, blockState.setValue(RepeaterAutopatchBlock.LIT, lit), Block.UPDATE_CLIENTS);
	}

	/** The tones on the air as a call goes from {@code from} to {@code to}. */
	private void changed(State from, State to, long now) {
		VHFRepeaterBlockEntity found = working();
		if(to == State.IN_CALL) {
			control.reset();
			lastRadioGameTime = now;
			if(found != null) {
				found.clearTones(); // The ringback stops.
				found.queueTones(AutopatchTones.connected());
			}
		}
		else if(to == State.CALLING)
			tones(AutopatchTones.ringback()); // Then one a cycle from the tick.
		else if(to == State.IDLE) {
			answerAt = -1L;
			if(found != null && from != State.IDLE) {
				found.clearTones();
				// Unanswered or turned away: reorder. Hung up by either side: the ended beeps.
				found.queueTones(from == State.CALLING && !endedHere ? AutopatchTones.reorder() : AutopatchTones.ended());
			}
		}
		endedHere = false;
	}

	/** The game time the radio side was last heard, for the inactivity timeout. */
	public long getLastRadioGameTime() {
		return lastRadioGameTime;
	}

	@Override
	public void hangUp() {
		endedHere = true;
		super.hangUp();
	}

	// -------------------- VOICE --------------------

	/** It has no microphone: nothing near it goes down the line, only what its repeater hears. */
	@Override
	public boolean canTransmitVoice() {
		return false;
	}

	@Override
	public void acceptVoicePacket(ServerLevel level, short[] rawAudio, UUID sourcePlayer, @Nullable Vec3 speakerPos) {}

	/** A frame the repeater heard on its input: down the line to the far phone during a call. Voice chat thread. */
	public void fromRadio(net.minecraft.server.level.ServerLevel serverLevel, short[] audio) {
		lastRadioGameTime = serverLevel.getGameTime();
		TelephoneBlockEntity other = callPartner();
		if(other != null && com.arrl.radiocraft.common.radio.voice.RadiocraftVoicePlugin.API != null) // The voice chat's own level: its channels want it.
			other.playIncoming(com.arrl.radiocraft.common.radio.voice.RadiocraftVoicePlugin.API.fromServerLevel(serverLevel), audio.clone(), 1.0D);
	}

	/** A frame from the far phone: out on the repeater at phone quality, unless the repeater is hearing someone. Voice thread. */
	@Override
	protected synchronized void playIncoming(ServerLevel voiceLevel, short[] pcm, double gain) {
		VHFRepeaterBlockEntity found = repeater;
		if(found == null || found.isRemoved() || found.isInputActive())
			return; // Half duplex: dropped, not held back.
		found.transmitPatched(voiceLevel, processor.process(pcm, PHONE_QUALITY, Math.min(1.0D, gain), 0.0D));
	}

	// -------------------- SAVE, SYNC, MENU --------------------

	@Override
	protected void saveAdditional(@NotNull CompoundTag tag, HolderLookup.@NotNull Provider registries) {
		super.saveAdditional(tag, registries);
		tag.putString("code", control.getCode());
		tag.putBoolean("extensionSet", extensionSet);
	}

	@Override
	protected void loadAdditional(@NotNull CompoundTag tag, HolderLookup.@NotNull Provider registries) {
		super.loadAdditional(tag, registries);
		control.setCode(tag.getString("code"));
		extensionSet = tag.getBoolean("extensionSet");
		if(tag.contains("hasRepeater")) { // The client's copy.
			clientRepeater = tag.getBoolean("hasRepeater");
			clientArmed = tag.getBoolean("armed");
			clientNumber = tag.getString("armedNumber");
			statusKey = tag.getString("statusKey");
			statusArg = tag.getString("statusArg");
		}
	}

	@Override
	public @NotNull CompoundTag getUpdateTag(@NotNull HolderLookup.Provider registries) {
		CompoundTag tag = super.getUpdateTag(registries);
		tag.putBoolean("hasRepeater", repeater != null);
		tag.putBoolean("armed", control.isArmed());
		tag.putString("armedNumber", control.getNumber());
		tag.putString("statusKey", statusKey);
		tag.putString("statusArg", statusArg);
		return tag;
	}

	@Override
	public @NotNull Component getDisplayName() {
		return Component.translatable(Radiocraft.translationKey("container", "repeater_autopatch"));
	}

	@Override
	public @Nullable AbstractContainerMenu createMenu(int id, @NotNull Inventory inventory, @NotNull Player player) {
		if(!statusKey.isEmpty())
			status("", ""); // A fresh screen starts with no message.
		return new RepeaterAutopatchMenu(id, this);
	}
}
