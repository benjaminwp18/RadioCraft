package com.arrl.radiocraft.common.blockentities;

import com.arrl.radiocraft.Radiocraft;
import com.arrl.radiocraft.RadiocraftServerConfig;
import com.arrl.radiocraft.api.benetworks.BENetworkObject;
import com.arrl.radiocraft.api.benetworks.INetworkObjectProvider;
import com.arrl.radiocraft.common.be_networks.DataWire;
import com.arrl.radiocraft.common.be_networks.network_objects.PabxNetworkObject;
import com.arrl.radiocraft.common.blocks.PabxBlock;
import com.arrl.radiocraft.common.init.RadiocraftBlockEntities;
import com.arrl.radiocraft.common.menus.PabxMenu;
import com.arrl.radiocraft.common.radio.microwave.MicrowaveRoutes;
import com.arrl.radiocraft.common.telephone.PabxNumbers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A phone PABX: every telephone needs one (maintainer, 2026-10-30). The phones it serves (touching it, or on its data
 * wire; a phone joins the nearest PABX) call each other by extension, numbered from {@link #getFirstExtension()}.
 * <p>
 * PABXes linked to each other, by data wire (or touching) or across microwave links, however many steps away, are one
 * phone network of equals (2026-10-31). A number dialed is an extension on the caller's own PABX if it has one;
 * otherwise, if it starts with the {@linkplain #getMaster() master number} of a powered PABX on the network, the rest
 * is an extension there: from 602's phones, "603100" rings extension 100 on PABX 603. Master numbers are all
 * {@link #masterDigits()} long, or empty (only the PABX's own phones can call it). Its {@linkplain #getName() name}
 * shows on a phone keying its master number. It needs power ({@link PabxNetworkObject}); with none, its phones have no
 * line.
 * <p>
 * PABXes on one network with the same master number act as one PABX (2026-11-02, {@link #linked()}): their phones
 * dial each other by bare extension, so an extension may only be on one of them. A number two of them use is dead until
 * one changes, and shows "EXT IN USE" ({@link #getClashes()}). The {@linkplain #getAllCall() All-Call extension} rings
 * every phone on this PABX, and on any linked PABX with the same All-Call extension; the first to answer takes the call.
 */
public class PabxBlockEntity extends BlockEntity implements INetworkObjectProvider, MenuProvider {

	public static final int FIRST_EXTENSION = 100;
	/** Highest extension. */
	public static final int MAX_EXTENSION = 99999;
	/** Most PABXes one network search visits, so a huge web cannot stall a tick. */
	private static final int MAX_NETWORK = 256;

	private String master = "";
	private int firstExtension = FIRST_EXTENSION;
	private String name = "";
	/** The extension that rings all its phones; -1 for none. */
	private int allCall = -1;
	/** The numbers used twice among the PABXes linked to it, kept up by its tick (synced, for the screen and its phones). */
	private Set<Integer> clashes = Set.of();
	/** The phones it has numbered, so a number set by hand later is left alone. */
	private final Set<BlockPos> numbered = new HashSet<>();
	private boolean powered = false;
	private double phoneLoad = 0.0D;
	/** What the last save on its screen came to: a translation key and its argument (synced, not saved). */
	private String statusKey = "", statusArg = "";
	/** The client's copy of {@link #masterDigits()}, for the screen. */
	private int clientMasterDigits = 3;

	public PabxBlockEntity(BlockPos pos, BlockState state) {
		super(RadiocraftBlockEntities.PABX.get(), pos, state);
	}

	@Override
	public void onLoad() {
		super.onLoad();
		if(level != null && !level.isClientSide())
			getNetworkObject(level, worldPosition); // Joins the power wire.
	}

	@Override
	public BENetworkObject createNetworkObject() {
		return new PabxNetworkObject(level, worldPosition);
	}

	// -------------------- SETTINGS --------------------

	/** Digits in a master number (server config {@code pabx_master_digits}). */
	public static int masterDigits() {
		try {
			return RadiocraftServerConfig.PABX_MASTER_DIGITS.get();
		}
		catch(IllegalStateException e) { // Config not loaded yet.
			return 3;
		}
	}

	/** True while the wire feeds it (the client's copy is synced). */
	public boolean isPowered() {
		return powered;
	}

	/** The number other PABXes dial it by; empty for none (it can then only be called from its own phones). */
	public String getMaster() {
		return master;
	}

	public int getFirstExtension() {
		return firstExtension;
	}

	/** The extension that rings all its phones, as dialed; empty for none. */
	public String getAllCall() {
		return allCall < 0 ? "" : Integer.toString(allCall);
	}

	/** The numbers two of its linked phones (or a phone and an All-Call extension) have, so neither can be called. */
	public Set<Integer> getClashes() {
		return clashes;
	}

	/** Its name, shown on a phone keying its master number; empty for none. */
	public String getName() {
		return name;
	}

	/** How many digits a master number must have: the client's synced copy, or the server config. */
	public int getMasterDigits() {
		return level != null && level.isClientSide ? clientMasterDigits : masterDigits();
	}

	/** The last save's result, for the screen: a translation key (empty for none) and its argument. */
	public String getStatusKey() {
		return statusKey;
	}

	public String getStatusArg() {
		return statusArg;
	}

	/**
	 * Sets the master number: empty, or exactly {@link #masterDigits()} digits. PABXes on one network with the same one
	 * are {@linkplain #linked() linked}. Returns null if it was set, else the translation key saying why not.
	 */
	@Nullable
	public String setMaster(String number) {
		String why = masterProblem(number);
		if(why != null)
			return why;
		master = number;
		sync();
		return null;
	}

	/** Why {@code number} cannot be this PABX's master number, as a translation key, or null if it can. */
	@Nullable
	private String masterProblem(String number) {
		return PabxNumbers.validMaster(number, masterDigits()) ? null : "screen.radiocraft.pabx.master_length";
	}

	/** Sets the first extension, numbering every phone again from it if it changed. */
	public boolean setFirstExtension(int first) {
		if(first < 0 || first > MAX_EXTENSION)
			return false;
		if(first != firstExtension) {
			firstExtension = first;
			numbered.clear();
		}
		sync();
		return true;
	}

	/** Sets the name ({@link PabxNumbers#cleanName}: letters, digits, spaces and dashes, upper case, ten at most). */
	public void setName(String name) {
		this.name = PabxNumbers.cleanName(name);
		sync();
	}

	/**
	 * The screen's Save: every setting, or none if the master number or an extension will not do. The result shows on
	 * the screen ({@link #getStatusKey()}). An All-Call extension a phone has already is refused, here or on a PABX the
	 * new master number links to; this PABX's own phones do not count if a new first extension renumbers them. Returns
	 * true if saved.
	 */
	public boolean save(String number, String first, String all, String newName) {
		String why = masterProblem(number);
		int extension = PabxNumbers.parseExtension(first);
		int newAllCall = all.isEmpty() ? -1 : PabxNumbers.parseExtension(all);
		if(why == null && extension < 0)
			why = "screen.radiocraft.pabx.bad_extension";
		if(why == null && newAllCall < 0 && !all.isEmpty())
			why = "screen.radiocraft.pabx.bad_all_call";
		if(why == null && newAllCall >= 0)
			for(PabxBlockEntity pabx : linked(number))
				if(pabx != this || extension == firstExtension)
					for(TelephoneBlockEntity phone : pabx.phones())
						if(phone.isNumbered() && phone.getExtension() == newAllCall)
							why = "screen.radiocraft.pabx.all_call_taken";
		if(why != null) {
			status(why, why.endsWith("master_length") ? Integer.toString(masterDigits()) : all);
			return false;
		}
		master = number;
		allCall = newAllCall;
		setFirstExtension(extension);
		setName(newName);
		status("screen.radiocraft.pabx.saved", "");
		return true;
	}

	/** Sets the line the screen shows under Save (cleared when the screen is opened). */
	public void status(String key, String arg) {
		statusKey = key;
		statusArg = arg;
		sync();
	}

	// -------------------- THE NETWORK --------------------

	/** The PABX a phone at {@code pos} belongs to: the nearest one touching it or on its data wire. */
	@Nullable
	public static PabxBlockEntity of(Level level, BlockPos pos) {
		List<PabxBlockEntity> found = DataWire.linked(level, pos, PabxBlockEntity.class);
		return found.isEmpty() ? null : found.get(0);
	}

	/** Lets go of {@code phone}'s number, so the next numbering gives it a new one. */
	public void forget(TelephoneBlockEntity phone) {
		if(numbered.remove(phone.getBlockPos()))
			setChanged();
	}

	/** The telephones it serves: touching it or on its data wire, and nearer it than any other PABX. */
	public List<TelephoneBlockEntity> phones() {
		List<TelephoneBlockEntity> out = new ArrayList<>();
		if(level == null)
			return out;
		for(TelephoneBlockEntity phone : DataWire.linked(level, worldPosition, TelephoneBlockEntity.class))
			if(of(level, phone.getBlockPos()) == this)
				out.add(phone);
		return out;
	}

	/**
	 * Every PABX on this one's phone network, this one first, then outward: those it is wired to (touching, or on its
	 * data wire) and those across microwave links, and theirs in turn. Each maps to whether the way to it crosses a
	 * microwave route with interference or a marginal signal.
	 */
	public Map<PabxBlockEntity, Boolean> network() {
		Map<PabxBlockEntity, Boolean> out = new LinkedHashMap<>();
		out.put(this, false);
		if(level == null)
			return out;
		ArrayDeque<PabxBlockEntity> queue = new ArrayDeque<>(List.of(this));
		while(!queue.isEmpty() && out.size() < MAX_NETWORK) {
			PabxBlockEntity at = queue.poll();
			boolean lossy = out.get(at);
			for(PabxBlockEntity next : DataWire.linked(level, at.worldPosition, PabxBlockEntity.class))
				if(out.putIfAbsent(next, lossy) == null)
					queue.add(next);
			for(MicrowaveRoutes.Route route : MicrowaveRoutes.from(level, at.worldPosition))
				if(route.station() instanceof PabxBlockEntity next && out.putIfAbsent(next, lossy || route.lossy()) == null)
					queue.add(next);
		}
		return out;
	}

	/** The PABXes that act as one with this one: itself first, then those on its network with its master number. */
	public List<PabxBlockEntity> linked() {
		return linked(master);
	}

	/** {@link #linked()} if its master number were {@code number}. An empty master number links to nothing. */
	private List<PabxBlockEntity> linked(String number) {
		List<PabxBlockEntity> out = new ArrayList<>(List.of(this));
		if(!number.isEmpty())
			for(PabxBlockEntity other : network().keySet())
				if(other != this && other.master.equals(number))
					out.add(other);
		return out;
	}

	/** Every number taken among the PABXes linked to it, which a new phone or All-Call extension cannot have. */
	public Set<Integer> numbersInUse() {
		Set<Integer> out = new HashSet<>();
		for(PabxBlockEntity pabx : linked()) {
			if(pabx.allCall >= 0)
				out.add(pabx.allCall);
			for(TelephoneBlockEntity phone : pabx.phones()) // Phones waiting to be numbered again keep no number.
				if(phone.isNumbered() && (!pabx.powered || pabx.numbered.contains(phone.getBlockPos())))
					out.add(phone.getExtension());
		}
		return out;
	}

	/** What a dialed number reaches: the phones to ring, or none; {@code inUse} if two things have that number. */
	public record Route(List<TelephoneBlockEntity> phones, boolean inUse, boolean allCall) {
		private static final Route NONE = new Route(List.of(), false, false);
		private static final Route IN_USE = new Route(List.of(), true, false);
	}

	/**
	 * What {@code digits} reaches when dialed from a phone on this PABX: a number on it or a PABX linked to it, else a
	 * number on the PABXes on its network whose master number {@code digits} starts with.
	 */
	public Route route(String digits) {
		if(!powered)
			return Route.NONE;
		Route here = routeOn(linked(), digits);
		if(here != Route.NONE)
			return here;
		for(PabxBlockEntity pabx : network().keySet()) {
			String rest = PabxNumbers.extensionAfter(digits, pabx.master);
			if(rest != null) {
				Route there = routeOn(pabx.linked(), rest);
				if(there != Route.NONE)
					return there;
			}
		}
		return Route.NONE;
	}

	/** What {@code digits} reaches on these linked PABXes: those with no power do not answer. */
	private static Route routeOn(List<PabxBlockEntity> linked, String digits) {
		List<TelephoneBlockEntity> found = new ArrayList<>();
		List<TelephoneBlockEntity> allCalled = new ArrayList<>();
		boolean isAllCall = false;
		for(PabxBlockEntity pabx : linked) {
			if(!pabx.powered)
				continue;
			boolean all = pabx.allCall >= 0 && PabxNumbers.isExtension(digits, pabx.allCall);
			isAllCall |= all;
			for(TelephoneBlockEntity phone : pabx.phones()) {
				if(phone.isNumbered() && PabxNumbers.isExtension(digits, phone.getExtension()))
					found.add(phone);
				if(all)
					allCalled.add(phone);
			}
		}
		if(found.size() > 1 || (!found.isEmpty() && isAllCall))
			return Route.IN_USE;
		if(isAllCall)
			return new Route(allCalled, false, true);
		return found.isEmpty() ? Route.NONE : new Route(found, false, false);
	}

	/** The numbers used twice among the powered PABXes linked to it. */
	private Set<Integer> findClashes() {
		Set<Integer> seen = new HashSet<>(), out = new TreeSet<>();
		List<PabxBlockEntity> linked = linked();
		for(PabxBlockEntity pabx : linked)
			if(pabx.powered)
				for(TelephoneBlockEntity phone : pabx.phones())
					if(phone.isNumbered() && !seen.add(phone.getExtension()))
						out.add(phone.getExtension());
		for(PabxBlockEntity pabx : linked)
			if(pabx.powered && seen.contains(pabx.allCall))
				out.add(pabx.allCall);
		return out;
	}

	/** The master numbers and names of the powered PABXes on its network (this one included), nearest first. */
	public Map<String, String> directory() {
		Map<String, String> out = new LinkedHashMap<>();
		for(PabxBlockEntity pabx : network().keySet())
			if(pabx.powered && !pabx.master.isEmpty())
				out.putIfAbsent(pabx.master, pabx.name);
		return out;
	}

	/** True if both have power and are on one network. */
	public static boolean connected(@Nullable PabxBlockEntity a, @Nullable PabxBlockEntity b) {
		return a != null && b != null && a.powered && b.powered && (a == b || a.network().containsKey(b));
	}

	/** True if the way between them crosses a microwave route with interference or a marginal signal. */
	public static boolean lossy(@Nullable PabxBlockEntity a, @Nullable PabxBlockEntity b) {
		return a != null && b != null && a != b && Boolean.TRUE.equals(a.network().get(b));
	}

	// -------------------- TICK --------------------

	public static void tick(Level level, BlockPos pos, BlockState state, BlockEntity blockEntity) {
		if(blockEntity instanceof PabxBlockEntity pabx)
			pabx.serverTick(level, state);
	}

	private void serverTick(Level level, BlockState state) {
		boolean now = getNetworkObject(level, worldPosition) instanceof PabxNetworkObject object && object.isPowered;
		if(now != powered) {
			powered = now;
			sync();
		}
		if(state.getValue(PabxBlock.LIT) != powered)
			level.setBlock(worldPosition, state.setValue(PabxBlock.LIT, powered), Block.UPDATE_CLIENTS);
		if(level.getGameTime() % 20 == 0) {
			if(powered)
				number();
			updatePhoneLoad(level);
			Set<Integer> found = powered ? findClashes() : Set.of();
			if(!found.equals(clashes)) {
				clashes = found;
				sync();
			}
		}
	}

	/** Passes what its phones draw on to the power wire ({@link PabxNetworkObject}). */
	private void updatePhoneLoad(Level level) {
		List<TelephoneBlockEntity> phones = phones();
		int paCalls = 0;
		for(TelephoneBlockEntity phone : phones)
			if(phone instanceof PhonePaBlockEntity && phone.getState() == TelephoneBlockEntity.State.IN_CALL)
				paCalls++;
		phoneLoad = PabxNumbers.phoneLoad(phones.size(), paCalls, RadiocraftServerConfig.PHONE_POWER_USE.get(), RadiocraftServerConfig.PHONE_PA_CALL_MULTIPLIER.get());
		if(getNetworkObject(level, worldPosition) instanceof PabxNetworkObject object)
			object.setPhoneLoad(phoneLoad);
	}

	/** What its phones add to its power draw per tick, kept up by its tick. */
	public double getPhoneLoad() {
		return phoneLoad;
	}

	/** Gives each phone it has not numbered yet the lowest number free here and on linked PABXes, from its first extension. */
	private void number() {
		List<TelephoneBlockEntity> phones = phones();
		Set<Integer> taken = null;
		for(TelephoneBlockEntity phone : phones) {
			if(numbered.contains(phone.getBlockPos()))
				continue;
			if(taken == null)
				taken = numbersInUse();
			int extension = firstExtension;
			while(taken.contains(extension) && extension < MAX_EXTENSION)
				extension++;
			phone.assignExtension(extension);
			taken.add(extension);
			numbered.add(phone.getBlockPos().immutable());
			setChanged();
		}
		// Forget phones that are gone, so their numbers free up.
		Set<BlockPos> present = new HashSet<>();
		for(TelephoneBlockEntity phone : phones)
			present.add(phone.getBlockPos());
		if(numbered.retainAll(present))
			setChanged();
	}

	private void sync() {
		setChanged();
		if(level != null && !level.isClientSide)
			level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
	}

	// -------------------- SAVE AND SYNC --------------------

	@Override
	protected void saveAdditional(@NotNull CompoundTag tag, HolderLookup.@NotNull Provider registries) {
		super.saveAdditional(tag, registries);
		tag.putString("master", master);
		tag.putInt("firstExtension", firstExtension);
		tag.putString("name", name);
		tag.putInt("allCall", allCall);
		ListTag list = new ListTag();
		for(BlockPos pos : numbered)
			list.add(LongTag.valueOf(pos.asLong()));
		tag.put("numbered", list);
	}

	@Override
	protected void loadAdditional(@NotNull CompoundTag tag, HolderLookup.@NotNull Provider registries) {
		super.loadAdditional(tag, registries);
		master = tag.getString("master"); // A "behind" tag from 1.10.0 is ignored: every PABX is a peer now.
		firstExtension = tag.contains("firstExtension") ? tag.getInt("firstExtension") : FIRST_EXTENSION;
		name = PabxNumbers.cleanName(tag.getString("name"));
		allCall = tag.contains("allCall") ? tag.getInt("allCall") : -1;
		numbered.clear();
		for(Tag entry : tag.getList("numbered", Tag.TAG_LONG))
			numbered.add(BlockPos.of(((LongTag)entry).getAsLong()));
		if(tag.contains("powered")) { // The client's copy.
			powered = tag.getBoolean("powered");
			clientMasterDigits = tag.getInt("masterDigits");
			statusKey = tag.getString("statusKey");
			statusArg = tag.getString("statusArg");
			Set<Integer> read = new TreeSet<>();
			for(int number : tag.getIntArray("clashes"))
				read.add(number);
			clashes = read;
		}
	}

	@Override
	public @NotNull CompoundTag getUpdateTag(@NotNull HolderLookup.Provider registries) {
		CompoundTag tag = saveWithoutMetadata(registries);
		tag.putBoolean("powered", powered);
		tag.putInt("masterDigits", masterDigits());
		tag.putString("statusKey", statusKey);
		tag.putString("statusArg", statusArg);
		tag.putIntArray("clashes", clashes.stream().mapToInt(Integer::intValue).toArray());
		return tag;
	}

	@Override
	public ClientboundBlockEntityDataPacket getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
	}

	// -------------------- MENU --------------------

	@Override
	public @NotNull Component getDisplayName() {
		return Component.translatable(Radiocraft.translationKey("container", "pabx"));
	}

	@Override
	public @Nullable AbstractContainerMenu createMenu(int id, @NotNull Inventory inventory, @NotNull Player player) {
		if(!statusKey.isEmpty())
			status("", ""); // A fresh screen starts with no message.
		return new PabxMenu(id, this);
	}
}
