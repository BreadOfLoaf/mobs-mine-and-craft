package com.mobsminecraft.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * When the player towers up, a mob builds a staircase up to them: it backs off far enough for the ramp,
 * then lays stairs one step forward and one up at a time, walking up as it goes.
 */
final class Stairs {
	private Stairs() {}

	/** Starts a staircase toward a player standing high above. Returns true if it started. */
	static boolean tryStart(SmartBrain brain, ServerLevel level, LivingEntity target, double dy) {
		Mob mob = brain.mob;
		if (brain.stairCooldown > 0 || dy < 2.5 || dy > 20.0 || !mob.onGround()) {
			return false;
		}
		brain.stairCooldown = 200;
		int height = Mth.ceil(dy);
		Vec3 d = target.position().subtract(mob.position());
		Direction dir = Direction.getApproximateNearest(d.x, 0.0, d.z);
		BlockPos pillar = BlockPos.containing(target.getX(), mob.getY(), target.getZ());
		// The ramp ends next to the player's column, so it starts height+1 blocks back from it.
		brain.stairStart = pillar.relative(dir.getOpposite(), height + 1);
		brain.stairDir = dir;
		brain.stairTopY = Mth.floor(target.getY());
		brain.stairPillar = pillar;
		brain.stairTicks = 60 + height * 40;
		brain.stairPhase = 0;
		brain.stairStepWait = 0;
		com.mobsminecraft.debug.AiStats.add("stairs_start");
		return true;
	}

	/** Returns true while busy with the staircase. */
	static boolean tick(SmartBrain brain, ServerLevel level, LivingEntity target) {
		if (brain.stairTicks <= 0) {
			return false;
		}
		Mob mob = brain.mob;
		if (--brain.stairTicks == 0 || target == null) {
			com.mobsminecraft.debug.AiStats.add("stairs_timeout");
			return end(brain);
		}
		BlockPos feet = mob.blockPosition();
		if (brain.stairPhase == 0) {
			// Walk back to where the ramp starts (or start where it stands if it can't get there).
			Vec3 start = Vec3.atBottomCenterOf(brain.stairStart);
			double hx = start.x - mob.getX();
			double hz = start.z - mob.getZ();
			if (hx * hx + hz * hz > 1.0 && brain.stairStepWait++ < 80) {
				brain.walkTo(new Vec3(start.x, mob.getY(), start.z), brain.walkSpeed());
				return true;
			}
			brain.stairPhase = 1;
			brain.stairStepWait = 0;
			// Re-aim at the player from wherever it ended up.
			Vec3 d = Vec3.atBottomCenterOf(brain.stairPillar).subtract(mob.position());
			brain.stairDir = Direction.getApproximateNearest(d.x, 0.0, d.z);
		}
		if (feet.getY() >= brain.stairTopY) {
			com.mobsminecraft.debug.AiStats.add("stairs_top");
			return end(brain);
		}
		if (brain.stairStepWait > 0 || !mob.onGround()) {
			// Keep climbing onto the step just placed (vanilla chasing would pull it off the ramp).
			brain.stairStepWait--;
			if (brain.stairNext != null) {
				mob.getNavigation().stop();
				mob.getMoveControl().setWantedPosition(brain.stairNext.getX() + 0.5, brain.stairNext.getY() + 1.0, brain.stairNext.getZ() + 0.5, brain.walkSpeed());
			}
			return true;
		}
		Direction dir = brain.stairDir;
		BlockPos next = feet.relative(dir);
		BlockPos pillar = new BlockPos(brain.stairPillar.getX(), next.getY(), brain.stairPillar.getZ());
		if (next.getX() == pillar.getX() && next.getZ() == pillar.getZ()) {
			// Reached the player's column too low: finish by towering the rest of the way.
			brain.startTower(brain.stairTopY - feet.getY());
			com.mobsminecraft.debug.AiStats.add("stairs_short");
			return end(brain);
		}
		int height = brain.height();
		// Clear headroom over the next step (and for the jump over the current one).
		for (BlockPos p : new BlockPos[] {next.above(1), next.above(2), next.above(height), feet.above(height)}) {
			if (BlockWork.blocksMovement(level, p)) {
				if (!BlockWork.canMine(mob, level, p, level.getBlockState(p))) {
					com.mobsminecraft.debug.AiStats.add("stairs_blocked");
					return end(brain);
				}
				brain.startMining(p, true);
				return true;
			}
		}
		if (level.getBlockState(next).canBeReplaced()) {
			if (!brain.place(level, next, stairFor(brain.buildState(), dir), true)) {
				// Something is standing there: step back to the middle of this step and try again.
				mob.getNavigation().stop();
				mob.getMoveControl().setWantedPosition(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, brain.walkSpeed());
				brain.stairStepWait = 4;
				brain.stairNext = null;
				return true;
			}
			com.mobsminecraft.debug.AiStats.add("stairs_step");
		}
		mob.getNavigation().stop();
		mob.getMoveControl().setWantedPosition(next.getX() + 0.5, next.getY() + 1.0, next.getZ() + 0.5, brain.walkSpeed());
		mob.getJumpControl().jump();
		brain.stairNext = next;
		brain.stairStepWait = 8;
		return true;
	}

	/** Stairs made of the mob's building block where such stairs exist (spruce, sandstone, blackstone...). */
	static BlockState stairFor(BlockState block, Direction dir) {
		String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block.getBlock()).getPath();
		Block stairs = Blocks.COBBLESTONE_STAIRS;
		for (String name : new String[] {id.replace("_planks", "_stairs"), id + "_stairs", id.replaceAll("s$", "") + "_stairs",
			id.equals("netherrack") ? "nether_brick_stairs" : "", id.equals("end_stone") ? "end_stone_brick_stairs" : "", id.equals("dirt") ? "mud_brick_stairs" : ""}) {
			var opt = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(net.minecraft.resources.Identifier.withDefaultNamespace(name));
			if (!name.isEmpty() && opt.isPresent() && opt.get() instanceof StairBlock) {
				stairs = opt.get();
				break;
			}
		}
		return stairs.defaultBlockState().setValue(StairBlock.FACING, dir);
	}

	private static boolean end(SmartBrain brain) {
		brain.stairTicks = 0;
		brain.stairNext = null;
		return false;
	}
}
