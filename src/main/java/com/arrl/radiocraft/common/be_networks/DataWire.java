package com.arrl.radiocraft.common.be_networks;

import com.arrl.radiocraft.common.blocks.Strand;
import com.arrl.radiocraft.common.blocks.WireBlock;
import com.arrl.radiocraft.common.init.RadiocraftBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;
import java.util.function.Predicate;

/**
 * The data wire (maintainer, 2026-10-30): the line between a radio and its accessories, wired speakers, and the stations
 * a microwave radio serves, when they are not side by side. It carries no power and builds no network: the devices a
 * block reaches are found by walking the wire when asked, and kept for {@link #CACHE_TICKS} or until a data wire
 * changes anywhere in the level. Touching still links everything it always did; the wire only adds reach.
 * <p>
 * Where the wire runs through chunks that are not loaded, the walk goes on from what it last saw there
 * ({@link DataWireMemory}, 2026-11-04): only the devices at the ends need to be loaded.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = com.arrl.radiocraft.Radiocraft.MOD_ID)
public final class DataWire {

	/** How long a walk's answer is kept, in ticks (a device placed at the far end is noticed within this). */
	public static final int CACHE_TICKS = 40;
	/** The most strands one walk follows, so a huge web of wire cannot stall a tick. */
	public static final int MAX_STRANDS = 4096;

	private record Entry(long tick, long generation, int limit, List<BlockPos> reached) {}

	private static final Map<Level, Map<BlockPos, Entry>> CACHE = new WeakHashMap<>();
	private static final Map<Level, Long> GENERATION = new WeakHashMap<>();
	/** Where data wire changed this tick: the strands round them are looked at again for the memory next tick. */
	private static final Map<Level, Set<BlockPos>> CHANGED = new WeakHashMap<>();

	private DataWire() {}

	/**
	 * The wire blocks the walk runs through: the data wire of every colour (each joins only its own,
	 * {@link WireBlock#joins}), and the passthrough (which every kind runs through).
	 */
	public static Set<WireBlock> wires() {
		Set<WireBlock> wires = new HashSet<>(List.of(RadiocraftBlocks.DATA_WIRE.get(), RadiocraftBlocks.PASSTHROUGH.get()));
		RadiocraftBlocks.COLOURED_DATA_WIRE.values().forEach(wire -> wires.add(wire.get()));
		return wires;
	}

	/** A data wire at {@code pos} was laid, lifted or changed in {@code level}: every cached walk there is stale. */
	public static synchronized void changed(Level level, BlockPos pos) {
		GENERATION.merge(level, 1L, Long::sum);
		if(!level.isClientSide)
			CHANGED.computeIfAbsent(level, l -> new HashSet<>()).add(pos.immutable());
	}

	/** Brings the memory up to date round the wire that changed last tick, now that its states are stored. */
	@net.neoforged.bus.api.SubscribeEvent
	public static void onLevelTick(net.neoforged.neoforge.event.tick.LevelTickEvent.Post event) {
		Level level = event.getLevel();
		if(level.isClientSide)
			return;
		synchronized(DataWire.class) {
			Set<BlockPos> changed = CHANGED.remove(level);
			DataWireMemory memory = changed == null ? null : DataWireMemory.of(level);
			if(memory == null)
				return;
			Set<WireBlock> wires = wires();
			Set<BlockPos> near = new HashSet<>();
			for(BlockPos pos : changed) // A change moves the links of the strands round it (a climb reaches a block diagonally).
				for(BlockPos at : BlockPos.betweenClosed(pos.offset(-1, -1, -1), pos.offset(1, 1, 1)))
					near.add(at.immutable());
			for(BlockPos at : near)
				if(level.isLoaded(at))
					memory.refresh(level, at, wires);
		}
	}

	/** Every device the data wire touching {@code pos} reaches, not counting {@code pos} itself. */
	public static List<BlockPos> reached(Level level, BlockPos pos) {
		return reached(level, pos, MAX_STRANDS);
	}

	/** {@link #reached(Level, BlockPos)}, following at most {@code limit} strands of wire. */
	public static synchronized List<BlockPos> reached(Level level, BlockPos pos, int limit) {
		long generation = GENERATION.getOrDefault(level, 0L);
		long now = level.getGameTime();
		Map<BlockPos, Entry> cache = CACHE.computeIfAbsent(level, l -> new HashMap<>());
		Entry entry = cache.get(pos);
		if(entry != null && entry.generation() == generation && entry.limit() == limit && now - entry.tick() < CACHE_TICKS && now >= entry.tick())
			return entry.reached();
		List<BlockPos> reached = walk(level, pos, limit, level::isLoaded);
		if(cache.size() > 4096)
			cache.clear(); // Positions of devices long gone pile up otherwise.
		cache.put(pos.immutable(), new Entry(now, generation, limit, reached));
		return reached;
	}

	/**
	 * For GameTests: what {@code pos} reaches if the blocks {@code unloaded} picks were not loaded, going on from the
	 * memory there. Not cached.
	 */
	public static synchronized List<BlockPos> reachedAsIfUnloaded(Level level, BlockPos pos, int limit, Predicate<BlockPos> unloaded) {
		return walk(level, pos, limit, at -> level.isLoaded(at) && !unloaded.test(at));
	}

	/** The blocks {@code pos} is linked to: its six neighbours, then everything its data wire reaches. */
	public static List<BlockPos> linked(Level level, BlockPos pos) {
		return linked(level, pos, MAX_STRANDS);
	}

	/** {@link #linked(Level, BlockPos)}, following at most {@code limit} strands of wire. */
	public static List<BlockPos> linked(Level level, BlockPos pos, int limit) {
		List<BlockPos> out = new ArrayList<>(6);
		for(Direction direction : Direction.values())
			out.add(pos.relative(direction));
		for(BlockPos at : reached(level, pos, limit))
			if(!out.contains(at))
				out.add(at);
		return out;
	}

	/** The loaded block entities of {@code type} linked to {@code pos} ({@link #linked}), nearest first. */
	public static <T> List<T> linked(Level level, BlockPos pos, Class<T> type) {
		return linked(level, pos, type, MAX_STRANDS);
	}

	/** {@link #linked(Level, BlockPos, Class)}, following at most {@code limit} strands of wire. */
	public static <T> List<T> linked(Level level, BlockPos pos, Class<T> type, int limit) {
		List<T> out = new ArrayList<>();
		for(BlockPos at : linked(level, pos, limit))
			if(level.isLoaded(at) && type.isInstance(level.getBlockEntity(at))) {
				T found = type.cast(level.getBlockEntity(at));
				if(!out.contains(found))
					out.add(found);
			}
		return out;
	}

	/** The loaded block entities of {@code type} reached over data wire only (not touching). */
	public static <T> List<T> wired(Level level, BlockPos pos, Class<T> type) {
		List<T> out = new ArrayList<>();
		for(BlockPos at : reached(level, pos)) {
			BlockEntity be = level.isLoaded(at) ? level.getBlockEntity(at) : null;
			if(type.isInstance(be) && !out.contains(type.cast(be)))
				out.add(type.cast(be));
		}
		return out;
	}

	/**
	 * Follows the wire out from {@code start}. Through loaded blocks it reads the strands as they are, and notes them in
	 * the memory; through the rest it goes on from the memory, as far as that knows the wire.
	 */
	private static List<BlockPos> walk(Level level, BlockPos start, int limit, Predicate<BlockPos> loaded) {
		Set<WireBlock> wires = wires();
		DataWireMemory memory = DataWireMemory.of(level);
		Set<Strand> seen = new HashSet<>();
		Set<BlockPos> devices = new LinkedHashSet<>();
		ArrayDeque<Strand> queue = new ArrayDeque<>();
		for(BlockPos wirePos : WireUtils.wireLinksOf(level, start, wires).keySet())
			enter(level, wirePos, null, wires, seen, queue, memory, loaded);
		while(!queue.isEmpty() && seen.size() <= limit) {
			Strand strand = queue.poll();
			WireBlock kind = strand.kind();
			if(kind == null)
				continue;
			if(!loaded.test(strand.pos())) {
				DataWireMemory.Remembered known = memory == null ? null : memory.get(strand.pos(), kind);
				if(known == null)
					continue;
				for(int i = 0; i < known.targets().length; i++) {
					BlockPos target = BlockPos.of(known.targets()[i]);
					if(hasStrands(level, target, memory, loaded))
						enter(level, target, kind, wires, seen, queue, memory, loaded);
					else {
						BlockPos device = BlockPos.of(known.devices()[i]);
						if(!device.equals(start))
							devices.add(device);
					}
				}
				continue;
			}
			BlockState state = strand.state(level);
			if(state == null)
				continue;
			Map<BlockPos, Direction> links = kind.getLinks(level, strand.pos(), state);
			if(memory != null)
				memory.remember(level, strand.pos(), kind, links);
			for(Map.Entry<BlockPos, Direction> link : links.entrySet()) {
				BlockPos target = link.getKey();
				if(hasStrands(level, target, memory, loaded)) {
					enter(level, target, kind, wires, seen, queue, memory, loaded);
					continue;
				}
				BlockPos device = loaded.test(target) ? WireUtils.linkTarget(level, target, link.getValue()).immutable() : target.immutable();
				if(!device.equals(start))
					devices.add(device);
			}
		}
		return List.copyOf(devices);
	}

	/** True if there is wire at {@code pos}: as it is where loaded, as remembered where not. */
	private static boolean hasStrands(Level level, BlockPos pos, DataWireMemory memory, Predicate<BlockPos> loaded) {
		if(loaded.test(pos))
			return !Strand.at(level, pos).isEmpty();
		return memory != null && !memory.at(pos).isEmpty();
	}

	private static void enter(Level level, BlockPos pos, WireBlock from, Set<WireBlock> wires, Set<Strand> seen, ArrayDeque<Strand> queue,
							  DataWireMemory memory, Predicate<BlockPos> loaded) {
		List<WireBlock> kinds = new ArrayList<>();
		if(loaded.test(pos))
			for(Strand strand : Strand.at(level, pos))
				kinds.add(strand.kind());
		else if(memory != null)
			for(DataWireMemory.Remembered strand : memory.at(pos))
				kinds.add(strand.kind());
		for(WireBlock kind : kinds)
			if(kind != null && wires.contains(kind) && (from == null || from.joins(kind)) && seen.add(new Strand(pos, kind)))
				queue.add(new Strand(pos, kind));
	}
}
