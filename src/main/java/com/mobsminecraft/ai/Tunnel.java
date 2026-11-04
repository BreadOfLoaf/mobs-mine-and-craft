package com.mobsminecraft.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/**
 * Tunnelling under the player: the mob digs down two blocks, tunnels through the ground toward the
 * player, then breaks the block they're standing on so they drop into a two-deep hole they can't jump out of.
 */
final class Tunnel {
	private Tunnel() {}

	/** Returns true while the mob is busy tunnelling. */
	static boolean tick(SmartBrain brain, ServerLevel level, LivingEntity target, boolean griefing) {
		Mob mob = brain.mob;
		if (brain.tunnelTicks <= 0) {
			return tryStart(brain, level, target, griefing);
		}
		brain.tunnelTicks--;
		if (target.getY() < brain.tunnelSurface - 0.9) {
			com.mobsminecraft.debug.AiStats.add("tunnel_drop");
			return end(brain);
		}
		if (brain.tunnelTicks == 0 || mob.distanceTo(target) > 20.0F || mob.isInWater()) {
			com.mobsminecraft.debug.AiStats.add("tunnel_gave_up");
			return end(brain);
		}
		if (!mob.onGround()) {
			return true;
		}
		BlockPos feet = mob.blockPosition();
		// Phase 1: dig down to tunnel depth.
		if (feet.getY() > brain.tunnelY) {
			BlockPos below = feet.below();
			if (!level.getFluidState(below.below()).isEmpty() || !BlockWork.canMine(mob, level, below, level.getBlockState(below))) {
				return end(brain);
			}
			mob.setPos(feet.getX() + 0.5, mob.getY(), feet.getZ() + 0.5);
			brain.startMining(below, true);
			return true;
		}
		// Phase 2: tunnel toward the spot under the player; when right under them, break their floor.
		BlockPos floor = BlockPos.containing(target.getX(), brain.tunnelSurface - 0.5, target.getZ());
		double dx = floor.getX() + 0.5 - mob.getX();
		double dz = floor.getZ() + 0.5 - mob.getZ();
		double horiz = Math.sqrt(dx * dx + dz * dz);
		if (horiz < 1.6 && Math.abs(floor.getY() - (feet.getY() + 1)) <= 1) {
			BlockPos under = floor.below();
			for (BlockPos p : new BlockPos[] {under, floor}) {
				if (BlockWork.blocksMovement(level, p)) {
					if (!BlockWork.canMine(mob, level, p, level.getBlockState(p))) {
						return end(brain);
					}
					brain.startMining(p, true);
					if (p.equals(floor)) {
						com.mobsminecraft.debug.AiStats.add("tunnel_floor_broken");
					}
					return true;
				}
			}
			if (!brain.tunnelReported) {
				brain.tunnelReported = true;
				com.mobsminecraft.debug.AiStats.add("tunnel_floor_broken");
			}
			// Floor is open: the player falls in (a real player does; wait a moment for that).
			return brain.tunnelTicks % 40 != 0 || end(brain);
		}
		Vec3 goal = new Vec3(floor.getX() + 0.5, brain.tunnelY, floor.getZ() + 0.5);
		if (!brain.stepToward(level, goal)) {
			return end(brain);
		}
		return true;
	}

	private static boolean tryStart(SmartBrain brain, ServerLevel level, LivingEntity target, boolean griefing) {
		Mob mob = brain.mob;
		if (!brain.tunneler || !griefing || brain.tunnelCooldown > 0 || !mob.onGround() || !target.onGround() || mob.isInWater()) {
			return false;
		}
		brain.tunnelCooldown = 40;
		double dist = mob.distanceTo(target);
		double dy = target.getY() - mob.getY();
		if (dist < 3.0 || dist > 12.0 || Math.abs(dy) > 0.8) {
			return false;
		}
		RandomSource r = mob.getRandom();
		float chance = brain.targetReachable ? 0.15F : 0.5F;
		if (r.nextFloat() > chance) {
			return false;
		}
		// Only through ground it can actually dig, with no water or lava around.
		BlockPos feet = mob.blockPosition();
		BlockPos playerFloor = BlockPos.containing(target.getX(), target.getY() - 0.5, target.getZ());
		for (BlockPos p : new BlockPos[] {feet.below(), feet.below(2), playerFloor, playerFloor.below()}) {
			if (!BlockWork.canMine(mob, level, p, level.getBlockState(p))) {
				return false;
			}
			for (Direction d : Direction.values()) {
				if (!level.getFluidState(p.relative(d)).isEmpty()) {
					return false;
				}
			}
		}
		brain.tunnelCooldown = 500 + r.nextInt(400);
		brain.tunnelTicks = 400;
		brain.tunnelY = feet.getY() - 2;
		brain.tunnelSurface = target.getY();
		brain.tunnelReported = false;
		com.mobsminecraft.debug.AiStats.add("tunnel_start");
		return true;
	}

	private static boolean end(SmartBrain brain) {
		brain.tunnelTicks = 0;
		return false;
	}
}
