package com.arrl.radiocraft.common.be_networks;

import com.arrl.radiocraft.common.blocks.Strand;
import com.arrl.radiocraft.common.blocks.WireBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the data wire is like where it is not loaded (2026-11-04): every data wire strand a walk ({@link DataWire}) has
 * passed, with the blocks it links to, saved with the dimension ({@code data/radiocraft_data_wire.dat}). A walk that
 * reaches an unloaded chunk goes on from here, so two phones a long line apart talk while only their own chunks are
 * loaded. Wire only changes while loaded: a change ({@link DataWire#changed}) has the strands round it looked at again
 * on the next tick, and every walk through loaded wire brings what it passes up to date.
 */
public final class DataWireMemory extends SavedData {

	public static final String SAVE_NAME = "radiocraft_data_wire";
	/** The most strands remembered per dimension (about 80 bytes each); past it, new wire is only walked while loaded. */
	public static final int MAX_STRANDS = 500_000;

	/** A strand: what it links to, and for each link the device it reaches if that is not a strand. */
	record Remembered(WireBlock kind, long[] targets, long[] devices) {
		boolean same(Remembered other) {
			return kind == other.kind && Arrays.equals(targets, other.targets) && Arrays.equals(devices, other.devices);
		}
	}

	private final Map<Long, List<Remembered>> strands = new HashMap<>();
	private int count = 0;

	public static SavedData.Factory<DataWireMemory> factory() {
		return new SavedData.Factory<>(DataWireMemory::new, DataWireMemory::load, null);
	}

	/** The level's memory; null on the client. */
	@Nullable
	public static DataWireMemory of(Level level) {
		return level instanceof ServerLevel server ? server.getDataStorage().computeIfAbsent(factory(), SAVE_NAME) : null;
	}

	/** The remembered strands at {@code pos}, none if it was never walked. */
	List<Remembered> at(BlockPos pos) {
		return strands.getOrDefault(pos.asLong(), List.of());
	}

	@Nullable
	Remembered get(BlockPos pos, WireBlock kind) {
		for(Remembered strand : at(pos))
			if(strand.kind() == kind)
				return strand;
		return null;
	}

	public int size() {
		return count;
	}

	/** Notes the strand of {@code kind} at {@code pos} (loaded) and its links, as {@code getLinks} gives them. */
	void remember(Level level, BlockPos pos, WireBlock kind, Map<BlockPos, Direction> links) {
		long[] targets = new long[links.size()], devices = new long[links.size()];
		int i = 0;
		for(Map.Entry<BlockPos, Direction> link : links.entrySet()) {
			BlockPos target = link.getKey();
			targets[i] = target.asLong();
			devices[i] = level.isLoaded(target) && Strand.at(level, target).isEmpty()
					? WireUtils.linkTarget(level, target, link.getValue()).asLong() : target.asLong();
			i++;
		}
		Remembered fresh = new Remembered(kind, targets, devices);
		List<Remembered> here = strands.get(pos.asLong());
		if(here == null) {
			if(count >= MAX_STRANDS)
				return;
			here = new ArrayList<>(1);
			strands.put(pos.asLong(), here);
		}
		for(int j = 0; j < here.size(); j++)
			if(here.get(j).kind() == kind) {
				if(!here.get(j).same(fresh)) {
					here.set(j, fresh);
					setDirty();
				}
				return;
			}
		here.add(fresh);
		count++;
		setDirty();
	}

	/** Looks again at the loaded block {@code pos}: its data strands as they are now, or forgets it if it has none. */
	void refresh(Level level, BlockPos pos, Set<WireBlock> wires) {
		List<Remembered> old = strands.remove(pos.asLong());
		if(old != null) {
			count -= old.size();
			setDirty();
		}
		for(Strand strand : Strand.at(level, pos))
			if(wires.contains(strand.kind())) {
				var state = strand.state(level);
				if(state != null)
					remember(level, pos, strand.kind(), strand.kind().getLinks(level, pos, state));
			}
	}

	@Override
	public @NotNull CompoundTag save(@NotNull CompoundTag tag, HolderLookup.@NotNull Provider registries) {
		synchronized(DataWire.class) { // Walks note strands under this lock, from any thread.
			return write(tag);
		}
	}

	private CompoundTag write(CompoundTag tag) {
		List<ResourceLocation> kinds = new ArrayList<>();
		ListTag list = new ListTag();
		for(Map.Entry<Long, List<Remembered>> entry : strands.entrySet())
			for(Remembered strand : entry.getValue()) {
				ResourceLocation id = BuiltInRegistries.BLOCK.getKey(strand.kind());
				int kind = kinds.indexOf(id);
				if(kind < 0) {
					kind = kinds.size();
					kinds.add(id);
				}
				CompoundTag one = new CompoundTag();
				one.putLong("pos", entry.getKey());
				one.putInt("kind", kind);
				one.putLongArray("targets", strand.targets());
				one.putLongArray("devices", strand.devices());
				list.add(one);
			}
		ListTag kindList = new ListTag();
		for(ResourceLocation id : kinds)
			kindList.add(net.minecraft.nbt.StringTag.valueOf(id.toString()));
		tag.put("kinds", kindList);
		tag.put("strands", list);
		return tag;
	}

	public static DataWireMemory load(CompoundTag tag, HolderLookup.Provider registries) {
		DataWireMemory memory = new DataWireMemory();
		List<WireBlock> kinds = new ArrayList<>();
		for(Tag id : tag.getList("kinds", Tag.TAG_STRING)) {
			ResourceLocation location = ResourceLocation.tryParse(id.getAsString());
			kinds.add(location != null && BuiltInRegistries.BLOCK.get(location) instanceof WireBlock wire ? wire : null);
		}
		for(Tag entry : tag.getList("strands", Tag.TAG_COMPOUND)) {
			CompoundTag one = (CompoundTag)entry;
			int kind = one.getInt("kind");
			long[] targets = one.getLongArray("targets"), devices = one.getLongArray("devices");
			if(kind < 0 || kind >= kinds.size() || kinds.get(kind) == null || targets.length != devices.length)
				continue; // A wire from a mod no longer there.
			memory.strands.computeIfAbsent(one.getLong("pos"), k -> new ArrayList<>(1)).add(new Remembered(kinds.get(kind), targets, devices));
			memory.count++;
		}
		return memory;
	}
}
