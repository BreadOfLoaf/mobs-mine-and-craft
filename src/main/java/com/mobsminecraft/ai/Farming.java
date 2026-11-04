package com.mobsminecraft.ai;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import com.mobsminecraft.world.MobBuilt;

/**
 * Passive mobs farm: they find water (or dig a hole and pour some in), till the dirt around it into
 * farmland, and plant wheat, carrots, potatoes or beetroots.
 */
final class Farming {
	private static final Block[] CROPS = {Blocks.WHEAT, Blocks.CARROTS, Blocks.POTATOES, Blocks.BEETROOTS};

	private Farming() {}

	/** Returns true while busy farming. */
	static boolean tick(SmartBrain brain, ServerLevel level, boolean griefing) {
		Mob mob = brain.mob;
		if (brain.farmPlan == null) {
			if (!brain.farmer || brain.farmCooldown > 0 || !griefing || !mob.onGround() || Outpost.isUnderground(level, mob)) {
				return false;
			}
			RandomSource r = mob.getRandom();
			brain.farmCooldown = 1200 + r.nextInt(2400);
			if (!plan(brain, level, r)) {
				return false;
			}
			com.mobsminecraft.debug.AiStats.add("farm_started");
			return true;
		}
		if (brain.farmIndex >= brain.farmPlan.size() || ++brain.farmTicks > 900) {
			if (brain.farmIndex >= brain.farmPlan.size()) {
				com.mobsminecraft.debug.AiStats.add("farm_finished");
			}
			brain.farmPlan = null;
			return false;
		}
		BlockPos p = brain.farmPlan.get(brain.farmIndex);
		Vec3 c = Vec3.atCenterOf(p);
		if (mob.distanceToSqr(c) > 16.0) {
			if (mob.tickCount % 10 == 0) {
				mob.getNavigation().moveTo(c.x, c.y + 0.5, c.z, brain.walkSpeed());
			}
			return true;
		}
		brain.holdStill();
		mob.getLookControl().setLookAt(c);
		if (mob.tickCount % 6 != 0) {
			return true;
		}
		brain.farmIndex++;
		if (p.equals(brain.farmWater)) {
			if (level.getBlockState(p).canBeReplaced() || BlockWork.isNatural(level, p, level.getBlockState(p))) {
				level.setBlock(p, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
				level.playSound(null, p, SoundEvents.BUCKET_EMPTY, SoundSource.NEUTRAL, 1.0F, 1.0F);
				mob.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
			}
			return true;
		}
		BlockState ground = level.getBlockState(p);
		BlockState above = level.getBlockState(p.above());
		if (!(ground.is(BlockTags.DIRT) || ground.is(BlockTags.GRASS_BLOCKS)) || ground.is(Blocks.FARMLAND) || !above.canBeReplaced() || !above.getFluidState().isEmpty()) {
			return true;
		}
		if (!above.isAir()) {
			level.destroyBlock(p.above(), false, mob);
		}
		level.setBlock(p, Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE, FarmlandBlock.MAX_MOISTURE), Block.UPDATE_ALL);
		level.playSound(null, p, SoundEvents.HOE_TILL.value(), SoundSource.NEUTRAL, 1.0F, 1.0F);
		RandomSource r = mob.getRandom();
		CropBlock crop = (CropBlock) brain.farmCrop;
		level.setBlock(p.above(), crop.getStateForAge(r.nextInt(3)), Block.UPDATE_ALL);
		level.playSound(null, p.above(), SoundEvents.CROP_PLANTED, SoundSource.NEUTRAL, 1.0F, 1.0F);
		MobBuilt.mark(level, p);
		MobBuilt.mark(level, p.above());
		mob.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
		com.mobsminecraft.debug.AiStats.add("farmland");
		return true;
	}

	private static boolean plan(SmartBrain brain, ServerLevel level, RandomSource r) {
		Mob mob = brain.mob;
		BlockPos here = mob.blockPosition();
		BlockPos water = BlockWork.findNearestAt(level, here, 8, (p, s) -> s.getFluidState().is(FluidTags.WATER) && s.getFluidState().isSource()
			&& p.getY() >= here.getY() - 2 && p.getY() <= here.getY() && level.getBlockState(p.above()).isAir());
		BlockPos newWater = null;
		if (water == null) {
			// No water around: dig a little hole for some.
			for (int i = 0; i < 10 && water == null; i++) {
				BlockPos p = here.offset(r.nextInt(7) - 3, -1, r.nextInt(7) - 3);
				BlockState s = level.getBlockState(p);
				if ((s.is(BlockTags.DIRT) || s.is(BlockTags.GRASS_BLOCKS)) && level.getBlockState(p.above()).canBeReplaced()
					&& level.getFluidState(p.above()).isEmpty() && !MobBuilt.contains(level, p) && solidAround(level, p)) {
					water = p;
					newWater = p;
				}
			}
		}
		if (water == null) {
			return false;
		}
		int radius = 1 + r.nextInt(3);
		int cap = 10 + r.nextInt(14);
		List<BlockPos> plots = new ArrayList<>();
		for (int x = -radius; x <= radius; x++) {
			for (int z = -radius; z <= radius; z++) {
				BlockPos p = water.offset(x, 0, z);
				if (p.equals(water)) continue;
				BlockState s = level.getBlockState(p);
				if ((s.is(BlockTags.DIRT) || s.is(BlockTags.GRASS_BLOCKS)) && !s.is(Blocks.FARMLAND) && level.getBlockState(p.above()).canBeReplaced()
					&& level.getFluidState(p.above()).isEmpty() && !MobBuilt.contains(level, p)) {
					plots.add(p);
				}
			}
		}
		if (plots.size() < 3) {
			return false;
		}
		final BlockPos w = water;
		plots.sort(Comparator.comparingInt(p -> p.distManhattan(w)));
		List<BlockPos> plan = new ArrayList<>();
		if (newWater != null) {
			plan.add(newWater);
		}
		plan.addAll(plots.subList(0, Math.min(cap, plots.size())));
		brain.farmPlan = plan;
		brain.farmIndex = 0;
		brain.farmTicks = 0;
		brain.farmWater = newWater;
		brain.farmCrop = CROPS[r.nextInt(CROPS.length)];
		return true;
	}

	/** Water poured here stays put: solid ground below and on all four sides. */
	private static boolean solidAround(ServerLevel level, BlockPos p) {
		if (!BlockWork.blocksMovement(level, p.below())) return false;
		for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
			if (!BlockWork.blocksMovement(level, p.relative(d))) return false;
		}
		return true;
	}
}
