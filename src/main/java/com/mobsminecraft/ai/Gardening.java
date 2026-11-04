package com.mobsminecraft.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.BoneMealItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.phys.Vec3;

/** Some mobs plant saplings and bone meal them, and occasionally grow a tree right next to the player. */
final class Gardening {
	private static final Block[] SAPLINGS = {
		Blocks.OAK_SAPLING, Blocks.BIRCH_SAPLING, Blocks.SPRUCE_SAPLING, Blocks.CHERRY_SAPLING, Blocks.ACACIA_SAPLING, Blocks.JUNGLE_SAPLING
	};

	private Gardening() {}

	/** Idle: plant a sapling nearby and start a bone meal session. */
	static boolean tryPlant(SmartBrain brain, ServerLevel level) {
		Mob mob = brain.mob;
		if (brain.gardenCooldown > 0 || !mob.onGround()) {
			return false;
		}
		RandomSource r = mob.getRandom();
		brain.gardenCooldown = 600 + r.nextInt(1200);
		if (r.nextFloat() > 0.5F) {
			return false;
		}
		BlockPos spot = findSoil(level, mob.blockPosition(), 2, r);
		if (spot == null || spot.equals(mob.blockPosition())) {
			return false;
		}
		if (!brain.place(level, spot, SAPLINGS[r.nextInt(SAPLINGS.length)].defaultBlockState(), false)) {
			return false;
		}
		brain.gardenPos = spot;
		brain.gardenTicks = 0;
		return true;
	}

	/** Bone meals the planted sapling until it grows, then fertilises the grass for flowers. */
	static void tick(SmartBrain brain, ServerLevel level) {
		Mob mob = brain.mob;
		brain.holdStill();
		mob.getLookControl().setLookAt(Vec3.atCenterOf(brain.gardenPos));
		if (++brain.gardenTicks % 10 != 0) {
			return;
		}
		boolean stillSapling = level.getBlockState(brain.gardenPos).is(BlockTags.SAPLINGS);
		if (!stillSapling || brain.gardenTicks > 120) {
			if (!stillSapling) {
				// Celebrate with some flowers.
				boneMeal(mob, level, mob.blockPosition().below());
			}
			brain.gardenPos = null;
			return;
		}
		boneMeal(mob, level, brain.gardenPos);
	}

	/** Combat: plant a sapling next to the player and grow it instantly around them. */
	static boolean tryTreeTrap(SmartBrain brain, ServerLevel level, LivingEntity target) {
		Mob mob = brain.mob;
		if (brain.treeTrapCooldown > 0 || mob.distanceTo(target) > 5.0F || !mob.hasLineOfSight(target)) {
			return false;
		}
		RandomSource r = mob.getRandom();
		brain.treeTrapCooldown = 600 + r.nextInt(600);
		BlockPos spot = findSoil(level, target.blockPosition(), 1, r);
		if (spot == null || spot.equals(target.blockPosition())
			|| !brain.place(level, spot, SAPLINGS[r.nextInt(SAPLINGS.length)].defaultBlockState(), false)) {
			return false;
		}
		for (int i = 0; i < 15 && level.getBlockState(spot).is(BlockTags.SAPLINGS); i++) {
			BoneMealItem.growCrop(new ItemStack(Items.BONE_MEAL), level, spot);
		}
		level.levelEvent(LevelEvent.PARTICLES_AND_SOUND_PLANT_GROWTH, spot, 15);
		mob.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
		return true;
	}

	private static void boneMeal(Mob mob, ServerLevel level, BlockPos pos) {
		mob.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
		BoneMealItem.growCrop(new ItemStack(Items.BONE_MEAL), level, pos);
		level.levelEvent(LevelEvent.PARTICLES_AND_SOUND_PLANT_GROWTH, pos, 15);
	}

	/** A random open spot with dirt or grass underneath, within the given radius. */
	private static BlockPos findSoil(ServerLevel level, BlockPos center, int radius, RandomSource r) {
		for (int i = 0; i < 12; i++) {
			BlockPos p = center.offset(r.nextInt(radius * 2 + 1) - radius, 0, r.nextInt(radius * 2 + 1) - radius);
			if (level.getBlockState(p).canBeReplaced() && level.getFluidState(p).isEmpty() && (level.getBlockState(p.below()).is(BlockTags.DIRT) || level.getBlockState(p.below()).is(BlockTags.GRASS_BLOCKS))) {
				return p;
			}
		}
		return null;
	}
}
