package com.mobsminecraft.world;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Removes temporary things mobs put into the world: water/lava they "scoop back up",
 * cobwebs that decay, and boats endermen leave behind.
 */
public final class TimedCleanup {
	private record BlockTask(BlockPos pos, Block block, long expireAt) {}

	private record EntityTask(Entity entity, long expireAt) {}

	private static final Map<ServerLevel, List<BlockTask>> BLOCKS = new WeakHashMap<>();
	private static final Map<ServerLevel, List<EntityTask>> ENTITIES = new WeakHashMap<>();

	private TimedCleanup() {}

	public static void scheduleBlock(ServerLevel level, BlockPos pos, Block block, int ticks) {
		BLOCKS.computeIfAbsent(level, l -> new ArrayList<>()).add(new BlockTask(pos.immutable(), block, level.getGameTime() + ticks));
	}

	public static void scheduleEntity(ServerLevel level, Entity entity, int ticks) {
		ENTITIES.computeIfAbsent(level, l -> new ArrayList<>()).add(new EntityTask(entity, level.getGameTime() + ticks));
	}

	public static void tick(ServerLevel level) {
		long now = level.getGameTime();

		List<BlockTask> blocks = BLOCKS.get(level);
		if (blocks != null && !blocks.isEmpty()) {
			Iterator<BlockTask> it = blocks.iterator();
			while (it.hasNext()) {
				BlockTask task = it.next();
				if (task.expireAt > now) {
					continue;
				}
				it.remove();
				if (!level.isLoaded(task.pos) || !level.getBlockState(task.pos).is(task.block)) {
					continue;
				}
				level.setBlock(task.pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
				if (task.block == Blocks.WATER) {
					level.playSound(null, task.pos, SoundEvents.BUCKET_FILL, SoundSource.HOSTILE, 1.0F, 1.0F);
				} else if (task.block == Blocks.LAVA) {
					level.playSound(null, task.pos, SoundEvents.BUCKET_FILL_LAVA, SoundSource.HOSTILE, 1.0F, 1.0F);
				}
			}
		}

		List<EntityTask> entities = ENTITIES.get(level);
		if (entities != null && !entities.isEmpty()) {
			List<EntityTask> extended = new ArrayList<>();
			Iterator<EntityTask> it = entities.iterator();
			while (it.hasNext()) {
				EntityTask task = it.next();
				if (task.expireAt > now) {
					continue;
				}
				it.remove();
				if (task.entity.isRemoved()) {
					continue;
				}
				if (task.entity.isVehicle()) {
					// Someone is still trapped in it; check again later.
					extended.add(new EntityTask(task.entity, now + 200));
				} else {
					task.entity.discard();
				}
			}
			entities.addAll(extended);
		}
	}
}
