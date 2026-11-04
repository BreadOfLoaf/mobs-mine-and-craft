package com.mobsminecraft.world;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Pressure-plate mines laid by engineers. Hostile mobs know where their side's mines are and step carefully, so only
 * players and other creatures set them off. (Forgotten on restart: old mines then work like ordinary plates.)
 */
public final class MobMines {
	private static final Set<String> MINES = new HashSet<>();

	private MobMines() {}

	private static String key(Level level, BlockPos pos) {
		return level.dimension().identifier() + "@" + pos.asLong();
	}

	public static void add(Level level, BlockPos plate) {
		MINES.add(key(level, plate));
	}

	public static boolean isMine(Level level, BlockPos plate) {
		return !MINES.isEmpty() && MINES.contains(key(level, plate));
	}

	public static void remove(Level level, BlockPos plate) {
		MINES.remove(key(level, plate));
	}
}
