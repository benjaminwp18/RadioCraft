package com.arrl.radiocraft.common.blockentities;

import com.arrl.radiocraft.Radiocraft;
import com.arrl.radiocraft.api.benetworks.BENetworkObject;
import com.arrl.radiocraft.api.benetworks.INetworkObjectProvider;
import com.arrl.radiocraft.api.blockentities.radio.IVoiceTransmitter;
import com.arrl.radiocraft.common.be_networks.network_objects.MicrowaveNetworkObjects;
import com.arrl.radiocraft.common.blockentities.radio.RadioBlockEntity;
import com.arrl.radiocraft.common.init.RadiocraftBlockEntities;
import com.arrl.radiocraft.common.menus.TelephoneMenu;
import com.arrl.radiocraft.common.radio.VoiceTransmitters;
import com.arrl.radiocraft.common.radio.voice.RadioVoiceProcessor;
import com.arrl.radiocraft.common.radio.voice.RadiocraftVoicePlugin;
import com.arrl.radiocraft.common.telephone.PabxNumbers;
import de.maxhenkel.voicechat.api.ServerLevel;
import de.maxhenkel.voicechat.api.audiochannel.LocationalAudioChannel;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A desk telephone on a phone PABX (touching it, or on its data wire): every phone needs one. The PABX numbers its
 * phones; a phone's owner can change its extension to any number no other phone on that PABX has. Dial a number (see
 * {@link PabxBlockEntity#resolve}): an extension on the same PABX, or another PABX's master number and an extension
 * there, on the PABXes wired together or linked by microwave; on PABXes sharing a master number, a bare extension
 * reaches them all. An All-Call extension rings several phones, and the first to answer takes the call. It rings until
 * it is answered (or for 30 seconds).
 * While a call is up, voices near either phone come out of the other at phone quality ({@link #PHONE_QUALITY}):
 * fuller than SSB, and clean, as the link is digital.
 */
public class TelephoneBlockEntity extends BlockEntity implements INetworkObjectProvider, MenuProvider, IVoiceTransmitter {

	/** A landline's voice band (300-3400 Hz), lightly compressed, no noise: fuller than SSB's 300-2700. */
	public static final RadioVoiceProcessor.Settings PHONE_QUALITY = new RadioVoiceProcessor.Settings(300.0D, 3400.0D, 1.2D, true);
	/** Ticks a phone rings before the caller hears no answer. */
	public static final int RING_TICKS = 600;
	/** Blocks from the phone a voice is still picked up, and how far its earpiece is heard. */
	public static final double PICKUP = 4.0D;
	public static final float EARPIECE = 8.0F;
	/** How far the handset's cord reaches, and how far the far end is heard from the ear of whoever holds it. */
	public static final double CORD_LENGTH = 5.0D;
	public static final float HANDSET_EARPIECE = 3.0F;
	/** The ring: {@link #RING_ON_TICKS} of bell, then quiet, every {@link #RING_CYCLE_TICKS} (a North American cadence). */
	public static final int RING_CYCLE_TICKS = 120;
	public static final int RING_ON_TICKS = 40;
	/** On the LCD (flashing) when a number is on two phones, so neither can be called. */
	public static final String EXT_IN_USE = "EXT IN USE";
	/** On the caller's LCD when a Phone PA only takes local calls. */
	public static final String LOCAL_ONLY = "LOCAL CALLS ONLY";

	public enum State { IDLE, CALLING, RINGING, IN_CALL }

	private static final Map<ResourceKey<Level>, Map<BlockPos, TelephoneBlockEntity>> LOADED = new ConcurrentHashMap<>();

	private int extension = -1;
	private State state = State.IDLE;
	@Nullable
	private BlockPos partner = null;
	/** The phones an All-Call has ringing, while this phone waits for one to answer ({@link #partner} is then null). */
	private final Set<BlockPos> ringing = new HashSet<>();
	/** This phone's extension is one two things have on its linked PABXes (synced, for the screen). */
	private boolean extensionInUse = false;
	/** The other end's number as this phone would dial it (synced, for the screen). */
	private String partnerNumber = "";
	/** This phone's number as dialed from another PABX (synced, for the screen); its extension alone without a master number. */
	private String fullNumber = "";
	/** The master numbers and names of the PABXes this phone can call (synced, for the screen's "PABX 603: NAME"). */
	private Map<String, String> directory = Map.of();
	/** The extensions on this phone's own PABX (synced, for the screen: a local extension wins over a master number). */
	private Set<Integer> localExtensions = Set.of();
	private String status = "";
	private int stateTicks = 0;
	/** The client's copy: the game time the state began, for the call timer (stateTicks only counts on the server). */
	private long stateSince = -1L;

	// The other phone while in a call, for the voice thread.
	@Nullable
	private volatile TelephoneBlockEntity partnerPhone = null;
	private volatile boolean lossy = false; // Interference somewhere on the link.
	private LocationalAudioChannel earpiece = null; // Voice thread only.
	private final RadioVoiceProcessor processor = new RadioVoiceProcessor();
	private OpusEncoder encoder = null;

	// The handset on its cord: who has it (synced, for the cord), which pick-up it is, and their ear for the voice thread.
	@Nullable
	private volatile UUID handsetHolder = null;
	@Nullable
	private UUID handsetId = null;
	@Nullable
	private volatile Vec3 holderEar = null;

	public TelephoneBlockEntity(BlockPos pos, BlockState state) {
		this(RadiocraftBlockEntities.TELEPHONE.get(), pos, state);
	}

	protected TelephoneBlockEntity(net.minecraft.world.level.block.entity.BlockEntityType<?> type, BlockPos pos, BlockState state) {
		super(type, pos, state);
	}

	/** False for what an All-Call should not ring. */
	protected boolean ringsOnAllCall() {
		return true;
	}

	/** False if this phone turns away a call from {@code caller}. */
	protected boolean acceptsCallFrom(TelephoneBlockEntity caller) {
		return true;
	}

	/** It has just started ringing. */
	protected void onRung() {}

	public static Collection<TelephoneBlockEntity> loaded(Level level) {
		Map<BlockPos, TelephoneBlockEntity> map = LOADED.get(level.dimension());
		return map == null ? List.of() : List.copyOf(map.values());
	}

	@Override
	public void onLoad() {
		super.onLoad();
		if(level != null && !level.isClientSide) {
			LOADED.computeIfAbsent(level.dimension(), k -> new ConcurrentHashMap<>()).put(worldPosition, this);
			VoiceTransmitters.addListener(level, this);
			getNetworkObject(level, worldPosition);
		}
	}

	@Override
	public void setRemoved() {
		super.setRemoved();
		if(level != null && !level.isClientSide) {
			Map<BlockPos, TelephoneBlockEntity> map = LOADED.get(level.dimension());
			if(map != null)
				map.remove(worldPosition, this);
			VoiceTransmitters.removeListener(level, this);
			if(state != State.IDLE)
				hangUp();
			synchronized(this) {
				if(encoder != null)
					encoder.close();
				encoder = null;
				earpiece = null;
			}
		}
	}

	@Override
	public void onChunkUnloaded() {
		super.onChunkUnloaded();
		setRemoved();
	}

	@Override
	public BENetworkObject createNetworkObject() {
		return new MicrowaveNetworkObjects.StationLink(level, worldPosition);
	}

	// -------------------- REACH --------------------

	/** The PABX this phone is on (the nearest touching it or on its data wire), or null. */
	@Nullable
	public PabxBlockEntity pabx() {
		return level == null ? null : PabxBlockEntity.of(level, worldPosition);
	}

	/** How {@code viewer} dials this phone: its extension on the same PABX, its full number from anywhere else. */
	private String numberFrom(TelephoneBlockEntity viewer) {
		PabxBlockEntity mine = pabx();
		if(mine != null && mine.linked().contains(viewer.pabx()))
			return Integer.toString(extension);
		return mine == null ? Integer.toString(extension) : mine.getMaster() + extension;
	}

	// -------------------- CALLS --------------------

	public static void tick(Level level, BlockPos pos, BlockState state, BlockEntity blockEntity) {
		if(!level.isClientSide && blockEntity instanceof TelephoneBlockEntity phone)
			phone.serverTick();
	}

	protected void serverTick() {
		if(level == null)
			return;
		tickHandset();
		if(level.getGameTime() % 40 == 0) { // Its number and directory follow the PABXes' settings.
			PabxBlockEntity pabx = pabx();
			String number = extension < 0 ? "" : pabx == null ? Integer.toString(extension) : pabx.getMaster() + extension;
			Map<String, String> reachable = pabx == null || !pabx.isPowered() ? Map.of() : pabx.directory();
			Set<Integer> locals = pabx == null ? Set.of() : new TreeSet<>(pabx.numbersInUse());
			boolean inUse = pabx != null && isNumbered() && pabx.getClashes().contains(extension);
			if(!number.equals(fullNumber) || !reachable.equals(directory) || !locals.equals(localExtensions) || inUse != extensionInUse) {
				fullNumber = number;
				extensionInUse = inUse;
				directory = reachable;
				localExtensions = locals;
				sync();
			}
		}
		if(state == State.IDLE)
			return;
		stateTicks++;
		if(state == State.CALLING && !ringing.isEmpty()) {
			tickAllCall();
			return;
		}
		TelephoneBlockEntity other = partner == null ? null : at(level, partner);
		if(other == null || !other.isCalling(worldPosition)) {
			drop("CALL DROPPED");
			return;
		}
		if(state == State.RINGING)
			ring(level, stateTicks);
		if(state == State.CALLING && stateTicks >= RING_TICKS) {
			other.reset("MISSED CALL FROM " + numberFrom(other));
			reset("NO ANSWER");
			return;
		}
		if(stateTicks % 20 == 0) { // Still reachable?
			if(!PabxBlockEntity.connected(pabx(), other.pabx())) {
				if(!other.ringing.remove(worldPosition)) // An All-Call rings on at its other phones.
					other.drop("CALL DROPPED");
				drop("CALL DROPPED");
				return;
			}
			lossy = PabxBlockEntity.lossy(pabx(), other.pabx());
		}
	}

	/** While an All-Call waits: phones that have gone stop counting, and it ends with none left or no answer. */
	private void tickAllCall() {
		ringing.removeIf(pos -> {
			TelephoneBlockEntity phone = at(level, pos);
			return phone == null || !worldPosition.equals(phone.partner);
		});
		if(ringing.isEmpty())
			drop("CALL DROPPED");
		else if(stateTicks >= RING_TICKS) {
			endRinging(phone -> "MISSED CALL FROM " + phone.partnerNumber);
			reset("NO ANSWER");
		}
	}

	/** Stops every phone an All-Call has ringing, each showing {@code why}. */
	private void endRinging(java.util.function.Function<TelephoneBlockEntity, String> why) {
		for(BlockPos pos : ringing) {
			TelephoneBlockEntity phone = at(level, pos);
			if(phone != null && worldPosition.equals(phone.partner))
				phone.reset(why.apply(phone));
		}
		ringing.clear();
	}

	/** True if this phone is calling, or in a call with, the phone at {@code pos}. */
	private boolean isCalling(BlockPos pos) {
		return pos.equals(partner) || ringing.contains(pos);
	}

	@Nullable
	private static TelephoneBlockEntity at(Level level, BlockPos pos) {
		Map<BlockPos, TelephoneBlockEntity> map = LOADED.get(level.dimension());
		TelephoneBlockEntity phone = map == null ? null : map.get(pos);
		return phone == null || phone.isRemoved() ? null : phone;
	}

	/**
	 * The bell, from vanilla's note block bell: for {@link #RING_ON_TICKS} of every {@link #RING_CYCLE_TICKS} the
	 * clapper strikes its two gongs in turn, one a tick, a third apart: the "brrring" of an old desk phone.
	 */
	static void ring(Level level, BlockPos pos, int ticks) {
		int phase = ticks % RING_CYCLE_TICKS;
		if(phase >= RING_ON_TICKS)
			return;
		float pitch = phase % 2 == 0 ? 1.19F : 1.5F; // Two gongs a major third apart (D and F# above the bell's F#).
		float volume = phase < 2 || phase >= RING_ON_TICKS - 2 ? 0.35F : 0.6F; // Softer as the clapper starts and stops.
		level.playSound(null, pos, SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.BLOCKS, volume, pitch);
	}

	private void ring(Level level, int ticks) {
		ring(level, worldPosition, ticks);
	}

	// -------------------- HANDSET --------------------

	/** The player who has the handset up, null while it is on its cradle (synced, for drawing the cord). */
	@Nullable
	public UUID getHandsetHolder() {
		return handsetHolder;
	}

	/** Which pick-up the handset out now is, null while it is on its cradle. */
	@Nullable
	public UUID getHandsetId() {
		return handsetId;
	}

	/**
	 * Sneak-right-click with an empty hand: the handset goes into the player's hand on its cord, and a ringing phone is
	 * answered.
	 */
	public void pickUp(net.minecraft.server.level.ServerPlayer player) {
		if(level == null)
			return;
		if(handsetHolder != null) {
			if(!handsetHolder.equals(player.getUUID()))
				player.displayClientMessage(Component.translatable(Radiocraft.translationKey("message", "handset_in_use")), true);
			return;
		}
		if(!player.getMainHandItem().isEmpty()) {
			player.displayClientMessage(Component.translatable(Radiocraft.translationKey("message", "handset_hand_full")), true);
			return;
		}
		handsetHolder = player.getUUID();
		handsetId = UUID.randomUUID();
		holderEar = player.getEyePosition();
		net.minecraft.world.item.ItemStack handset = new net.minecraft.world.item.ItemStack(com.arrl.radiocraft.common.init.RadiocraftItems.TELEPHONE_HANDSET.get());
		handset.set(com.arrl.radiocraft.common.init.RadiocraftDataComponent.MIC_CORD.get(), new com.arrl.radiocraft.common.items.MicCord(worldPosition, handsetId));
		player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, handset);
		level.playSound(null, worldPosition, SoundEvents.ITEM_FRAME_REMOVE_ITEM, SoundSource.BLOCKS, 0.6F, 1.4F);
		setOffHook(true);
		if(state == State.RINGING)
			answer();
		sync();
	}

	/** Sneak-right-click with the handset: it goes back on its cradle, ending any call. */
	public void putDown(@Nullable Player player) {
		if(player != null)
			removeHandset(player);
		hangUpHandset();
		if(level != null)
			level.playSound(null, worldPosition, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 0.7F, 0.8F);
	}

	/**
	 * While the handset is up: the cord snaps back if its holder has gone, is more than {@link #CORD_LENGTH} blocks
	 * away or no longer holds it in a hand; otherwise their ear is noted for the voice thread.
	 */
	private void tickHandset() {
		if(handsetHolder == null || !(level instanceof net.minecraft.server.level.ServerLevel serverLevel))
			return;
		Player player = serverLevel.getPlayerByUUID(handsetHolder);
		boolean inHand = player != null && (com.arrl.radiocraft.common.items.TelephoneHandsetItem.isHandsetOf(player.getMainHandItem(), this)
				|| com.arrl.radiocraft.common.items.TelephoneHandsetItem.isHandsetOf(player.getOffhandItem(), this));
		if(player == null || !inHand || player.position().distanceTo(worldPosition.getCenter()) > CORD_LENGTH) {
			if(player != null)
				removeHandset(player);
			hangUpHandset();
			serverLevel.playSound(null, worldPosition, SoundEvents.LEASH_KNOT_BREAK, SoundSource.BLOCKS, 0.8F, 1.2F);
			return;
		}
		holderEar = player.getEyePosition();
	}

	/** Takes this phone's handset out of the player's inventory, wherever it is. */
	private void removeHandset(Player player) {
		net.minecraft.world.entity.player.Inventory inventory = player.getInventory();
		for(int i = 0; i < inventory.getContainerSize(); i++)
			if(com.arrl.radiocraft.common.items.TelephoneHandsetItem.isHandsetOf(inventory.getItem(i), this))
				inventory.setItem(i, net.minecraft.world.item.ItemStack.EMPTY);
	}

	/** The handset is back on its cradle: any call ends, and the phone is a speakerphone again. */
	private void hangUpHandset() {
		handsetHolder = null;
		handsetId = null;
		holderEar = null;
		setOffHook(false);
		if(state != State.IDLE)
			hangUp();
		sync();
	}

	private void setOffHook(boolean offHook) {
		BlockState blockState = getBlockState();
		if(level != null && blockState.hasProperty(com.arrl.radiocraft.common.blocks.TelephoneBlock.OFF_HOOK)
				&& blockState.getValue(com.arrl.radiocraft.common.blocks.TelephoneBlock.OFF_HOOK) != offHook)
			level.setBlock(worldPosition, blockState.setValue(com.arrl.radiocraft.common.blocks.TelephoneBlock.OFF_HOOK, offHook), Block.UPDATE_ALL);
	}

	/** Dials {@code number} (digits): rings that phone if it can be reached and is free. */
	public void dial(String number) {
		if(level == null || state != State.IDLE || number.isEmpty())
			return;
		PabxBlockEntity pabx = pabx();
		if(pabx == null)
			status = "NO LINE: NO PABX";
		else if(!pabx.isPowered())
			status = "NO LINE: PABX HAS NO POWER";
		else {
			PabxBlockEntity.Route route = pabx.route(number);
			TelephoneBlockEntity callee = route.phones().size() == 1 && !route.allCall() ? route.phones().get(0) : null;
			if(route.inUse())
				status = EXT_IN_USE;
			else if(route.allCall())
				allCall(route.phones(), number);
			else if(callee == this)
				status = "THAT IS THIS PHONE";
			else if(callee == null)
				status = "NUMBER " + number + " NOT FOUND";
			else if(!callee.acceptsCallFrom(this))
				status = LOCAL_ONLY;
			else if(callee.state != State.IDLE)
				status = "BUSY";
			else {
				state = State.CALLING;
				partner = callee.worldPosition;
				partnerNumber = number;
				stateTicks = 0;
				status = "";
				callee.state = State.RINGING;
				callee.partner = worldPosition;
				callee.partnerNumber = numberFrom(callee);
				callee.stateTicks = 0;
				callee.status = "";
				callee.sync();
				callee.onRung();
			}
		}
		sync();
	}

	/** Rings every free phone of {@code phones} but this one. */
	private void allCall(List<TelephoneBlockEntity> phones, String number) {
		List<TelephoneBlockEntity> others = phones.stream().filter(phone -> phone != this && phone.ringsOnAllCall()).toList();
		List<TelephoneBlockEntity> free = others.stream().filter(phone -> phone.state == State.IDLE).toList();
		if(others.isEmpty())
			status = "NO OTHER PHONES";
		else if(free.isEmpty())
			status = "BUSY";
		else {
			state = State.CALLING;
			partnerNumber = number;
			stateTicks = 0;
			status = "";
			for(TelephoneBlockEntity callee : free) {
				ringing.add(callee.worldPosition.immutable());
				callee.state = State.RINGING;
				callee.partner = worldPosition;
				callee.partnerNumber = numberFrom(callee);
				callee.stateTicks = 0;
				callee.status = "";
				callee.sync();
			}
		}
	}

	/** Picks up a ringing phone: the call is up. On an All-Call the first to do so takes it, and the rest stop. */
	public void answer() {
		if(level == null || state != State.RINGING || partner == null)
			return;
		TelephoneBlockEntity caller = at(level, partner);
		if(caller == null || !caller.isCalling(worldPosition)) {
			drop("CALL DROPPED");
			return;
		}
		if(caller.ringing.remove(worldPosition)) {
			caller.endRinging(phone -> "ANSWERED ELSEWHERE");
			caller.partner = worldPosition;
			caller.partnerNumber = numberFrom(caller);
		}
		state = caller.state = State.IN_CALL;
		stateTicks = caller.stateTicks = 0;
		partnerPhone = caller;
		caller.partnerPhone = this;
		lossy = caller.lossy = PabxBlockEntity.lossy(pabx(), caller.pabx());
		caller.sync();
		sync();
		level.playSound(null, worldPosition, SoundEvents.ITEM_FRAME_REMOVE_ITEM, SoundSource.BLOCKS, 0.6F, 1.4F);
	}

	/** Hangs up, ending the call at both ends. Turning down an All-Call only stops this phone, unless it was the last ringing. */
	public void hangUp() {
		if(level != null)
			endRinging(phone -> "CALL ENDED");
		if(level != null && partner != null) {
			TelephoneBlockEntity other = at(level, partner);
			if(other != null && other.ringing.remove(worldPosition)) {
				if(other.ringing.isEmpty())
					other.reset("CALL REJECTED");
			}
			else if(other != null && worldPosition.equals(other.partner))
				other.reset(state == State.RINGING ? "CALL REJECTED" : "CALL ENDED");
		}
		reset("");
	}

	private void drop(String why) {
		reset(why);
	}

	private void reset(String why) {
		state = State.IDLE;
		partner = null;
		ringing.clear();
		partnerPhone = null;
		partnerNumber = "";
		stateTicks = 0;
		status = why;
		sync();
	}

	/** Sets the extension a PABX gives this phone ({@link PabxBlockEntity}). */
	public void assignExtension(int number) {
		if(number == extension)
			return;
		extension = number;
		sync();
	}

	/** Changes the extension; false without a PABX, or if that number is taken on its PABX or one linked to it. */
	public boolean setExtension(int number) {
		if(number < 0 || number > PabxBlockEntity.MAX_EXTENSION)
			return false;
		PabxBlockEntity pabx = pabx();
		if(pabx == null || (number != extension && pabx.numbersInUse().contains(number)))
			return false;
		extension = number;
		sync();
		return true;
	}

	protected void sync() {
		setChanged();
		if(level != null && !level.isClientSide)
			level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
	}

	// -------------------- VOICE --------------------

	@Override
	public boolean canTransmitVoice() {
		return state == State.IN_CALL && partnerPhone != null;
	}

	@Override
	public Vec3 getPos() {
		return com.arrl.radiocraft.compat.sable.Sublevels.worldCenter(level, worldPosition);
	}

	@Override
	public void acceptVoicePacket(ServerLevel level, short[] rawAudio, UUID sourcePlayer) {
		acceptVoicePacket(level, rawAudio, sourcePlayer, null);
	}

	/**
	 * A voice near this phone in a call: out of the other phone. With the handset up only its holder is heard, at full
	 * level; on the cradle it is a speakerphone, quieter the further the talker stands from it.
	 */
	@Override
	public void acceptVoicePacket(ServerLevel level, short[] rawAudio, UUID sourcePlayer, @Nullable Vec3 speakerPos) {
		TelephoneBlockEntity other = partnerPhone;
		if(other == null || state != State.IN_CALL)
			return;
		double gain = 1.0D;
		UUID holder = handsetHolder;
		if(holder != null) {
			if(!holder.equals(sourcePlayer))
				return;
		}
		else if(speakerPos != null) {
			double distance = speakerPos.distanceTo(getPos());
			if(distance > PICKUP)
				return;
			gain = RadioBlockEntity.deskMicGain(distance);
		}
		if(lossy && java.util.concurrent.ThreadLocalRandom.current().nextFloat() < 0.3F)
			return; // A frame lost to interference on the link.
		other.playIncoming(level, rawAudio.clone(), gain);
	}

	/** Plays a frame from the other end out of this phone's earpiece. Voice thread. */
	protected synchronized void playIncoming(ServerLevel voiceLevel, short[] pcm, double gain) {
		if(RadiocraftVoicePlugin.API == null)
			return;
		if(earpiece == null) {
			earpiece = RadiocraftVoicePlugin.API.createLocationalAudioChannel(UUID.randomUUID(), voiceLevel,
					RadiocraftVoicePlugin.API.createPosition(worldPosition.getX() + 0.5D, worldPosition.getY() + 0.5D, worldPosition.getZ() + 0.5D));
			if(earpiece == null)
				return;
			encoder = RadiocraftVoicePlugin.API.createEncoder();
		}
		Vec3 ear = holderEar;
		if(handsetHolder != null && ear != null) { // At the ear of whoever holds the handset, and only just audible around them.
			earpiece.updateLocation(RadiocraftVoicePlugin.API.createPosition(ear.x, ear.y, ear.z));
			earpiece.setDistance(HANDSET_EARPIECE);
		}
		else {
			Vec3 at = com.arrl.radiocraft.compat.sable.Sublevels.worldCenter(level, worldPosition); // Rides a Sable ship.
			earpiece.updateLocation(RadiocraftVoicePlugin.API.createPosition(at.x, at.y, at.z));
			earpiece.setDistance(EARPIECE);
		}
		earpiece.send(encoder.encode(processor.process(pcm, PHONE_QUALITY, Math.min(1.0D, gain), 0.0D)));
	}

	// -------------------- READING, SAVE, MENU --------------------

	public int getExtension() {
		return Math.max(0, extension);
	}

	/** False until a PABX has given it a number. */
	public boolean isNumbered() {
		return extension >= 0;
	}

	/** True if something else on its linked PABXes has its number, so it cannot be called (client side, synced). */
	public boolean isExtensionInUse() {
		return extensionInUse;
	}

	public State getState() {
		return state;
	}

	public String getPartnerNumber() {
		return partnerNumber;
	}

	/** This phone's number from another PABX (client side, synced). */
	public String getFullNumber() {
		return fullNumber;
	}

	/**
	 * What the screen shows while {@code dialed} is being keyed: "PABX 603: NAME" when it is exactly the master number
	 * of a PABX this phone can call and no extension on its own PABX, else null. Client side, from the synced directory.
	 */
	@Nullable
	public String directoryLabel(String dialed) {
		if(!PabxNumbers.digits(dialed) || dialed.length() > 9 || localExtensions.contains(Integer.parseInt(dialed)))
			return null;
		String name = directory.get(dialed);
		return name == null ? null : PabxNumbers.label(dialed, name);
	}

	public String getStatus() {
		return status;
	}

	public int getStateTicks() {
		if(level != null && level.isClientSide && stateSince >= 0L) // The call timer stuck at 0:00 (2026-10-30).
			return (int)Math.max(0L, level.getGameTime() - stateSince);
		return stateTicks;
	}

	@Override
	protected void saveAdditional(@NotNull CompoundTag tag, HolderLookup.@NotNull Provider registries) {
		super.saveAdditional(tag, registries);
		tag.putInt("extension", extension);
		UUID holder = handsetHolder;
		if(holder != null && handsetId != null) {
			tag.putUUID("handsetHolder", holder);
			tag.putUUID("handsetId", handsetId);
		}
	}

	@Override
	protected void loadAdditional(@NotNull CompoundTag tag, HolderLookup.@NotNull Provider registries) {
		super.loadAdditional(tag, registries);
		extension = tag.contains("extension") ? tag.getInt("extension") : -1;
		handsetHolder = tag.hasUUID("handsetHolder") ? tag.getUUID("handsetHolder") : null;
		handsetId = tag.hasUUID("handsetId") ? tag.getUUID("handsetId") : null;
		if(tag.contains("state")) { // The client's copy.
			try {
				state = State.valueOf(tag.getString("state"));
			}
			catch(IllegalArgumentException e) {
				state = State.IDLE;
			}
			partnerNumber = tag.getString("partnerNumber");
			fullNumber = tag.getString("fullNumber");
			Map<String, String> reachable = new LinkedHashMap<>();
			CompoundTag entries = tag.getCompound("directory");
			for(String key : entries.getAllKeys())
				reachable.put(key, entries.getString(key));
			directory = reachable;
			Set<Integer> locals = new TreeSet<>();
			for(int number : tag.getIntArray("localExtensions"))
				locals.add(number);
			localExtensions = locals;
			extensionInUse = tag.getBoolean("extensionInUse");
			status = tag.getString("status");
			stateSince = tag.getLong("stateSince");
		}
	}

	@Override
	public @NotNull CompoundTag getUpdateTag(@NotNull HolderLookup.Provider registries) {
		CompoundTag tag = saveWithoutMetadata(registries);
		tag.putString("state", state.name());
		tag.putString("partnerNumber", partnerNumber);
		tag.putString("fullNumber", fullNumber);
		CompoundTag entries = new CompoundTag();
		directory.forEach(entries::putString);
		tag.put("directory", entries);
		tag.putIntArray("localExtensions", localExtensions.stream().mapToInt(Integer::intValue).toArray());
		tag.putBoolean("extensionInUse", extensionInUse);
		tag.putString("status", status);
		tag.putLong("stateSince", level == null ? 0L : level.getGameTime() - stateTicks);
		return tag;
	}

	@Override
	public ClientboundBlockEntityDataPacket getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
	}

	@Override
	public @NotNull Component getDisplayName() {
		return Component.translatable(Radiocraft.translationKey("container", "telephone"));
	}

	@Override
	public @Nullable AbstractContainerMenu createMenu(int id, @NotNull Inventory inventory, @NotNull Player player) {
		return new TelephoneMenu(id, this);
	}
}
