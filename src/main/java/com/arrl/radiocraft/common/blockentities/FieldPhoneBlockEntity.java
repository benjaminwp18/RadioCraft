package com.arrl.radiocraft.common.blockentities;

import com.arrl.radiocraft.Radiocraft;
import com.arrl.radiocraft.api.benetworks.BENetworkObject;
import com.arrl.radiocraft.api.benetworks.INetworkObjectProvider;
import com.arrl.radiocraft.api.blockentities.radio.IVoiceTransmitter;
import com.arrl.radiocraft.common.be_networks.DataWire;
import com.arrl.radiocraft.common.be_networks.network_objects.FieldPhoneNetworkObject;
import com.arrl.radiocraft.common.blocks.FieldPhoneBlock;
import com.arrl.radiocraft.common.init.RadiocraftBlockEntities;
import com.arrl.radiocraft.common.init.RadiocraftDataComponent;
import com.arrl.radiocraft.common.init.RadiocraftItems;
import com.arrl.radiocraft.common.items.MicCord;
import com.arrl.radiocraft.common.items.TelephoneHandsetItem;
import com.arrl.radiocraft.common.radio.VoiceTransmitters;
import com.arrl.radiocraft.common.radio.voice.RadioVoiceProcessor;
import com.arrl.radiocraft.common.radio.voice.RadiocraftVoicePlugin;
import de.maxhenkel.voicechat.api.ServerLevel;
import de.maxhenkel.voicechat.api.audiochannel.LocationalAudioChannel;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The TA-43 field phone (2026-11-04): a party line on data wire, with no exchange and no power needed. The field phones
 * touching it or on its wire, however far it runs ({@link #LINE_STRANDS} blocks of wire, and through unloaded chunks:
 * see {@code DataWireMemory}), are one line. Picking up a handset on a quiet line rings every other phone on it with an
 * old magneto bell until someone picks up (or {@link #RING_TICKS} pass); everyone with a handset up hears everyone else.
 * A line takes {@link #LINE_PHONES} phones, or {@link #POWERED_LINE_PHONES} if any of them has power on its power wire,
 * which also makes the line's voices and bells louder. More than that and the line is dead.
 */
public class FieldPhoneBlockEntity extends BlockEntity implements INetworkObjectProvider, IVoiceTransmitter, TelephoneHandsetItem.Owner {

	/** The most phones on a line, without and with power. */
	public static final int LINE_PHONES = 6, POWERED_LINE_PHONES = 12;
	/** How much wire a line runs through: about 16 km, as far as a real TA-43 talks over field wire. */
	public static final int LINE_STRANDS = 16384;
	/** Ticks the other phones ring for. */
	public static final int RING_TICKS = 600;
	/** A cranked magneto: {@link #RING_ON_TICKS} of bell out of every {@link #RING_CYCLE_TICKS}, as the crank turns. */
	public static final int RING_CYCLE_TICKS = 50, RING_ON_TICKS = 34;
	/** A field line's voice: narrower than a landline, and a little rough. */
	public static final RadioVoiceProcessor.Settings LINE_QUALITY = new RadioVoiceProcessor.Settings(300.0D, 3000.0D, 1.4D, true);
	/** Voices on a line with no power, against a powered one's. */
	public static final double UNPOWERED_GAIN = 0.6D;
	public static final float UNPOWERED_RING = 0.5F, POWERED_RING = 0.9F;

	private static final Map<ResourceKey<Level>, Map<BlockPos, FieldPhoneBlockEntity>> LOADED = new ConcurrentHashMap<>();

	// The handset on its cord, as the telephone's.
	@Nullable
	private volatile UUID handsetHolder = null;
	@Nullable
	private UUID handsetId = null;
	@Nullable
	private volatile Vec3 holderEar = null;

	/** The other phones on its line, kept up by its tick (read by the voice thread). */
	private volatile List<FieldPhoneBlockEntity> line = List.of();
	private volatile boolean linePowered = false;
	/** More phones on the line than it takes: no bell and no voice. */
	private volatile boolean overloaded = false;
	/** The phone whose pick-up rings this one, and the ticks it has rung. */
	@Nullable
	private BlockPos ringingFor = null;
	private int ringTicks = 0;

	/** The earpiece at the ear of whoever holds the handset, one channel per phone heard on it. Voice thread only. */
	private final Map<BlockPos, Earpiece> earpieces = new HashMap<>();

	private static final class Earpiece {
		LocationalAudioChannel channel;
		OpusEncoder encoder;
		final RadioVoiceProcessor processor = new RadioVoiceProcessor();
	}

	public FieldPhoneBlockEntity(BlockPos pos, BlockState state) {
		super(RadiocraftBlockEntities.FIELD_PHONE.get(), pos, state);
	}

	public static Collection<FieldPhoneBlockEntity> loaded(Level level) {
		Map<BlockPos, FieldPhoneBlockEntity> map = LOADED.get(level.dimension());
		return map == null ? List.of() : List.copyOf(map.values());
	}

	@Nullable
	private static FieldPhoneBlockEntity at(Level level, BlockPos pos) {
		Map<BlockPos, FieldPhoneBlockEntity> map = LOADED.get(level.dimension());
		FieldPhoneBlockEntity phone = map == null ? null : map.get(pos);
		return phone == null || phone.isRemoved() ? null : phone;
	}

	@Override
	public void onLoad() {
		super.onLoad();
		if(level != null && !level.isClientSide) {
			LOADED.computeIfAbsent(level.dimension(), k -> new ConcurrentHashMap<>()).put(worldPosition, this);
			VoiceTransmitters.addListener(level, this);
			getNetworkObject(level, worldPosition); // Joins a power wire.
		}
	}

	@Override
	public void setRemoved() {
		super.setRemoved();
		if(level != null && !level.isClientSide) {
			Map<BlockPos, FieldPhoneBlockEntity> map = LOADED.get(level.dimension());
			if(map != null)
				map.remove(worldPosition, this);
			VoiceTransmitters.removeListener(level, this);
			synchronized(this) {
				for(Earpiece earpiece : earpieces.values())
					if(earpiece.encoder != null)
						earpiece.encoder.close();
				earpieces.clear();
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
		return new FieldPhoneNetworkObject(level, worldPosition);
	}

	// -------------------- THE LINE --------------------

	/** True while its power wire feeds it. */
	public boolean isPowered() {
		return level != null && getNetworkObject(level, worldPosition) instanceof FieldPhoneNetworkObject object && object.isPowered;
	}

	public boolean isOffHook() {
		return handsetHolder != null;
	}

	public boolean isRinging() {
		return ringingFor != null;
	}

	/** The other phones on its line, as of its last look. */
	public List<FieldPhoneBlockEntity> getLine() {
		return line;
	}

	public boolean isLinePowered() {
		return linePowered;
	}

	public boolean isOverloaded() {
		return overloaded;
	}

	/** How many phones the line takes now: {@link #POWERED_LINE_PHONES} with power on any of them. */
	public int capacity() {
		return linePowered ? POWERED_LINE_PHONES : LINE_PHONES;
	}

	/** Looks along the wire again for the other phones, and whether any has power. */
	public void refreshLine() {
		if(level == null)
			return;
		List<FieldPhoneBlockEntity> found = DataWire.linked(level, worldPosition, FieldPhoneBlockEntity.class, LINE_STRANDS).stream()
				.filter(phone -> phone != this && !phone.isRemoved()).toList();
		boolean powered = isPowered() || found.stream().anyMatch(FieldPhoneBlockEntity::isPowered);
		line = found;
		linePowered = powered;
		overloaded = found.size() + 1 > (powered ? POWERED_LINE_PHONES : LINE_PHONES);
	}

	public static void tick(Level level, BlockPos pos, BlockState state, BlockEntity blockEntity) {
		if(!level.isClientSide && blockEntity instanceof FieldPhoneBlockEntity phone)
			phone.serverTick();
	}

	private void serverTick() {
		if(level == null)
			return;
		tickHandset();
		if((level.getGameTime() + worldPosition.hashCode()) % 20 == 0)
			refreshLine();
		if(ringingFor == null)
			return;
		FieldPhoneBlockEntity caller = at(level, ringingFor);
		boolean answered = line.stream().anyMatch(phone -> phone != caller && phone.isOffHook()) || isOffHook();
		if(caller == null || !caller.isOffHook() || !line.contains(caller) || answered || overloaded || ringTicks >= RING_TICKS) {
			ringingFor = null;
			ringTicks = 0;
			return;
		}
		ring(level, worldPosition, ringTicks++, linePowered);
	}

	/**
	 * The bell: an old magneto ringer, its clapper hammering two gongs in turn once a tick while the far end cranks,
	 * lower and harsher than a desk phone's ({@link TelephoneBlockEntity#ring}).
	 */
	static void ring(Level level, BlockPos pos, int ticks, boolean powered) {
		int phase = ticks % RING_CYCLE_TICKS;
		if(phase >= RING_ON_TICKS)
			return;
		float pitch = phase % 2 == 0 ? 0.84F : 0.94F;
		float volume = (powered ? POWERED_RING : UNPOWERED_RING) * (phase < 3 || phase >= RING_ON_TICKS - 3 ? 0.6F : 1.0F); // The crank coming up to speed.
		level.playSound(null, pos, SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.BLOCKS, volume, pitch);
	}

	/** Starts this phone ringing for {@code caller}'s pick-up. */
	private void ringFor(FieldPhoneBlockEntity caller) {
		if(isOffHook() || ringingFor != null)
			return;
		refreshLine(); // A phone just laid on the line still knows the caller.
		ringingFor = caller.worldPosition.immutable();
		ringTicks = 0;
	}

	// -------------------- HANDSET --------------------

	@Nullable
	@Override
	public UUID getHandsetHolder() {
		return handsetHolder;
	}

	@Nullable
	@Override
	public UUID getHandsetId() {
		return handsetId;
	}

	/**
	 * Right click with an empty hand: the handset goes into the player's hand on its cord. On a quiet line the other
	 * phones ring; if this one was ringing, it is answered.
	 */
	public void pickUp(ServerPlayer player) {
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
		refreshLine();
		boolean quiet = line.stream().noneMatch(FieldPhoneBlockEntity::isOffHook);
		handsetHolder = player.getUUID();
		handsetId = UUID.randomUUID();
		holderEar = player.getEyePosition();
		ItemStack handset = new ItemStack(RadiocraftItems.FIELD_PHONE_HANDSET.get());
		handset.set(RadiocraftDataComponent.MIC_CORD.get(), new MicCord(worldPosition, handsetId));
		player.setItemInHand(InteractionHand.MAIN_HAND, handset);
		level.playSound(null, worldPosition, SoundEvents.ITEM_FRAME_REMOVE_ITEM, SoundSource.BLOCKS, 0.6F, 1.2F);
		setOffHook(true);
		ringingFor = null;
		ringTicks = 0;
		if(overloaded)
			player.displayClientMessage(Component.translatable(Radiocraft.translationKey("message", "field_phone_overloaded"), line.size() + 1, capacity()), true);
		else if(quiet && !line.isEmpty()) {
			for(FieldPhoneBlockEntity phone : line)
				phone.ringFor(this);
		}
		sync();
	}

	/** Right click with the handset: it goes back on its cradle. */
	public void putDown(@Nullable Player player) {
		if(player != null)
			removeHandset(player);
		hangUpHandset();
		if(level != null)
			level.playSound(null, worldPosition, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 0.7F, 0.7F);
	}

	/** The cord snaps back if its holder has gone, walked off with it or put it away; otherwise their ear is noted. */
	private void tickHandset() {
		if(handsetHolder == null || !(level instanceof net.minecraft.server.level.ServerLevel serverLevel))
			return;
		Player player = serverLevel.getPlayerByUUID(handsetHolder);
		boolean inHand = player != null && (TelephoneHandsetItem.isHandsetOf(player.getMainHandItem(), this) || TelephoneHandsetItem.isHandsetOf(player.getOffhandItem(), this));
		if(player == null || !inHand || player.position().distanceTo(worldPosition.getCenter()) > TelephoneBlockEntity.CORD_LENGTH) {
			if(player != null)
				removeHandset(player);
			hangUpHandset();
			serverLevel.playSound(null, worldPosition, SoundEvents.LEASH_KNOT_BREAK, SoundSource.BLOCKS, 0.8F, 1.2F);
			return;
		}
		holderEar = player.getEyePosition();
	}

	private void removeHandset(Player player) {
		Inventory inventory = player.getInventory();
		for(int i = 0; i < inventory.getContainerSize(); i++)
			if(TelephoneHandsetItem.isHandsetOf(inventory.getItem(i), this))
				inventory.setItem(i, ItemStack.EMPTY);
	}

	private void hangUpHandset() {
		handsetHolder = null;
		handsetId = null;
		holderEar = null;
		setOffHook(false);
		sync();
	}

	private void setOffHook(boolean offHook) {
		BlockState blockState = getBlockState();
		if(level != null && blockState.hasProperty(FieldPhoneBlock.OFF_HOOK) && blockState.getValue(FieldPhoneBlock.OFF_HOOK) != offHook)
			level.setBlock(worldPosition, blockState.setValue(FieldPhoneBlock.OFF_HOOK, offHook), Block.UPDATE_ALL);
	}

	private void sync() {
		setChanged();
		if(level != null && !level.isClientSide)
			level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
	}

	// -------------------- VOICE --------------------

	/** True while its handset is up on a working line with another handset up on it. */
	@Override
	public boolean canTransmitVoice() {
		return handsetHolder != null && !overloaded && line.stream().anyMatch(FieldPhoneBlockEntity::isOffHook);
	}

	@Override
	public Vec3 getPos() {
		return com.arrl.radiocraft.compat.sable.Sublevels.worldCenter(level, worldPosition);
	}

	@Override
	public void acceptVoicePacket(ServerLevel level, short[] rawAudio, UUID sourcePlayer) {
		acceptVoicePacket(level, rawAudio, sourcePlayer, null);
	}

	/** Only the handset's holder is heard, and they come out of every other handset up on the line. */
	@Override
	public void acceptVoicePacket(ServerLevel level, short[] rawAudio, UUID sourcePlayer, @Nullable Vec3 speakerPos) {
		UUID holder = handsetHolder;
		if(holder == null || !holder.equals(sourcePlayer) || overloaded)
			return;
		double gain = linePowered ? 1.0D : UNPOWERED_GAIN;
		for(FieldPhoneBlockEntity phone : line)
			if(phone.isOffHook() && !phone.overloaded)
				phone.playIncoming(level, worldPosition, rawAudio.clone(), gain);
	}

	/** Plays a frame from the phone at {@code from} at the ear of whoever holds this handset. Voice thread. */
	private synchronized void playIncoming(ServerLevel voiceLevel, BlockPos from, short[] pcm, double gain) {
		Vec3 ear = holderEar;
		if(RadiocraftVoicePlugin.API == null || ear == null || handsetHolder == null)
			return;
		Earpiece earpiece = earpieces.computeIfAbsent(from, k -> new Earpiece());
		if(earpiece.channel == null) {
			earpiece.channel = RadiocraftVoicePlugin.API.createLocationalAudioChannel(UUID.randomUUID(), voiceLevel, RadiocraftVoicePlugin.API.createPosition(ear.x, ear.y, ear.z));
			if(earpiece.channel == null)
				return;
			earpiece.encoder = RadiocraftVoicePlugin.API.createEncoder();
		}
		earpiece.channel.updateLocation(RadiocraftVoicePlugin.API.createPosition(ear.x, ear.y, ear.z));
		earpiece.channel.setDistance(TelephoneBlockEntity.HANDSET_EARPIECE);
		earpiece.channel.send(earpiece.encoder.encode(earpiece.processor.process(pcm, LINE_QUALITY, gain, 0.0D)));
	}

	// -------------------- SAVE AND SYNC --------------------

	@Override
	protected void saveAdditional(@NotNull CompoundTag tag, HolderLookup.@NotNull Provider registries) {
		super.saveAdditional(tag, registries);
		UUID holder = handsetHolder;
		if(holder != null && handsetId != null) {
			tag.putUUID("handsetHolder", holder);
			tag.putUUID("handsetId", handsetId);
		}
	}

	@Override
	protected void loadAdditional(@NotNull CompoundTag tag, HolderLookup.@NotNull Provider registries) {
		super.loadAdditional(tag, registries);
		handsetHolder = tag.hasUUID("handsetHolder") ? tag.getUUID("handsetHolder") : null;
		handsetId = tag.hasUUID("handsetId") ? tag.getUUID("handsetId") : null;
	}

	@Override
	public @NotNull CompoundTag getUpdateTag(@NotNull HolderLookup.Provider registries) {
		return saveWithoutMetadata(registries);
	}

	@Override
	public ClientboundBlockEntityDataPacket getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
	}
}
