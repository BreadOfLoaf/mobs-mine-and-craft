package com.mobsminecraft.world;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Pressure-plate mines laid by engineers. Hostile mobs know where their side's mines are and step carefully, so only
 * players and other creatures set them off. (Forgotten on restart: old mines then work like ordinary plates.)
 */
public final class MobMines {
	private static final Map<String, Set<Long>> MINES = new HashMap<>();

	private MobMines() {}

	private static Set<Long> of(Level level) {
		return MINES.computeIfAbsent(level.dimension().identifier().toString(), k -> new HashSet<>());
	}

	public static void add(Level level, BlockPos plate) {
		of(level).add(plate.asLong());
	}

	public static boolean isMine(Level level, BlockPos plate) {
		Set<Long> s = MINES.get(level.dimension().identifier().toString());
		return s != null && s.contains(plate.asLong());
	}

	public static void remove(Level level, BlockPos plate) {
		of(level).remove(plate.asLong());
	}

	/** Live mines (the plate still there) within {@code radius} blocks; forgets ones that are gone. */
	public static int countNear(Level level, BlockPos pos, int radius) {
		Set<Long> s = MINES.get(level.dimension().identifier().toString());
		if (s == null) return 0;
		int n = 0;
		var it = s.iterator();
		while (it.hasNext()) {
			BlockPos p = BlockPos.of(it.next());
			if (p.distManhattan(pos) > radius * 3) continue;
			if (!level.isLoaded(p)) continue;
			if (!level.getBlockState(p).is(net.minecraft.world.level.block.Blocks.STONE_PRESSURE_PLATE)) {
				it.remove();
				continue;
			}
			if (p.closerThan(pos, radius)) n++;
		}
		return n;
	}
}
