package com.mobsminecraft.world;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Remembers every block a mob placed, so mobs don't mine their own (or each other's) houses,
 * bridges and outposts back down for materials. Kept in memory for the session.
 */
public final class MobBuilt {
	private static final int MAX_PER_LEVEL = 500_000;
	private static final Map<ServerLevel, LongOpenHashSet> PLACED = new WeakHashMap<>();

	private MobBuilt() {}

	public static void mark(ServerLevel level, BlockPos pos) {
		LongOpenHashSet set = PLACED.computeIfAbsent(level, l -> new LongOpenHashSet());
		if (set.size() < MAX_PER_LEVEL) {
			set.add(pos.asLong());
		}
	}

	public static boolean contains(ServerLevel level, BlockPos pos) {
		LongOpenHashSet set = PLACED.get(level);
		return set != null && set.contains(pos.asLong());
	}
}
