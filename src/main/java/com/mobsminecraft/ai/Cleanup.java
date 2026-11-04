package com.mobsminecraft.ai;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * The mess fights leave behind (towers, bridges, cover walls, stairs, bunkers) is remembered, and Builders go
 * round taking it back down when nobody's fighting nearby.
 */
final class Cleanup {
	private static final Map<ServerLevel, LongOpenHashSet> MESS = new WeakHashMap<>();
	private static final int MAX = 200_000;

	private Cleanup() {}

	static void add(ServerLevel level, BlockPos pos) {
		LongOpenHashSet set = MESS.computeIfAbsent(level, l -> new LongOpenHashSet());
		if (set.size() < MAX) {
			set.add(pos.asLong());
		}
	}

	private static boolean isMess(ServerLevel level, BlockPos pos) {
		LongOpenHashSet set = MESS.get(level);
		return set != null && set.contains(pos.asLong());
	}

	private static void remove(ServerLevel level, BlockPos pos) {
		LongOpenHashSet set = MESS.get(level);
		if (set != null) {
			set.remove(pos.asLong());
		}
	}

	/** Builder, idle: find leftover fight blocks nearby and mine them. Returns true if it started on one. */
	static boolean tick(SmartBrain b, ServerLevel level) {
		Mob mob = b.mob;
		if (b.cleanupCooldown > 0 || !mob.onGround() || MESS.get(level) == null || MESS.get(level).isEmpty()) {
			return false;
		}
		b.cleanupCooldown = 30;
		// Not in the middle of a fight.
		if (mob.getTarget() != null) {
			return false;
		}
		BlockPos here = mob.blockPosition();
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
		for (int dx = -10; dx <= 10; dx++) {
			for (int dy = -4; dy <= 10; dy++) {
				for (int dz = -10; dz <= 10; dz++) {
					q.set(here.getX() + dx, here.getY() + dy, here.getZ() + dz);
					if (!isMess(level, q)) continue;
					BlockState s = level.getBlockState(q);
					if (s.isAir() || !BlockWork.canMine(mob, level, q, s)) {
						remove(level, q.immutable());
						continue;
					}
					// Never pull a block out from under somebody (a sniper on its tower, a mob on a bridge).
					if (!level.getEntitiesOfClass(Mob.class, new AABB(q).expandTowards(0.0, 3.0, 0.0), m -> m != mob).isEmpty()) continue;
					// Top-down, so towers come down from the top.
					double d = dx * dx + dz * dz - dy * 4.0;
					if (d < bestDist) {
						bestDist = d;
						best = q.immutable();
					}
				}
			}
		}
		if (best == null) {
			return false;
		}
		remove(level, best);
		b.setDigTargetAny(best);
		b.cleanupTarget = best;
		com.mobsminecraft.debug.AiStats.add("cleanup");
		return true;
	}
}
