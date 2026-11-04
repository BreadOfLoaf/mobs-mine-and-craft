package com.mobsminecraft.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Skeletons fighting from cover: a two-high wall between them and the player. They wait behind it, step out to the
 * side, shoot, and duck back in, and they leave cover if the player comes round it.
 */
final class Cover {
	static final int NONE = 0;
	static final int BUILDING = 1;
	static final int HIDING = 2;
	static final int PEEKING = 3;

	private Cover() {}

	/** Returns true while the skeleton is working its cover. */
	static boolean tick(SmartBrain b, ServerLevel level, LivingEntity target, boolean griefing) {
		Mob mob = b.mob;
		if (!(mob instanceof AbstractSkeleton) || !mob.getMainHandItem().is(Items.BOW) || !(mob instanceof RangedAttackMob archer)) {
			return false;
		}
		if (b.coverPhase == NONE) {
			return tryStart(b, level, target, griefing);
		}
		double dist = mob.distanceTo(target);
		if (++b.coverTicks > 600 || dist < 4.0 || dist > 30.0) {
			return end(b, "over");
		}
		BlockPos hide = b.coverHide;
		if (b.coverPhase == BUILDING) {
			b.holdStill();
			if (b.wallPlan == null) {
				b.coverPhase = HIDING;
				b.coverPhaseTicks = 30 + mob.getRandom().nextInt(30);
			}
			return true;
		}
		// The player walked round the wall: cover's no good any more.
		if (b.coverPhase == HIDING && mob.hasLineOfSight(target) && b.coverPhaseTicks < 20 && mob.blockPosition().equals(hide)) {
			return end(b, "flanked");
		}
		RandomSource r = mob.getRandom();
		BlockPos spot = b.coverPhase == HIDING ? hide : b.coverPeek;
		double dx = spot.getX() + 0.5 - mob.getX();
		double dz = spot.getZ() + 0.5 - mob.getZ();
		mob.getNavigation().stop();
		if (dx * dx + dz * dz > 0.1) {
			mob.getMoveControl().setWantedPosition(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 1.0);
		} else {
			b.holdStill();
		}
		mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
		b.coverPhaseTicks--;
		if (b.coverPhase == HIDING) {
			if (mob.isUsingItem()) {
				mob.stopUsingItem();
			}
			if (b.coverPhaseTicks <= 0) {
				b.coverPhase = PEEKING;
				b.coverPhaseTicks = 40;
				b.coverShot = false;
				com.mobsminecraft.debug.AiStats.add("cover_peek");
			}
			return true;
		}
		// Peeking: once out and with a clear shot, aim briefly, fire, duck back.
		boolean out = dx * dx + dz * dz < 0.3;
		if (out && mob.hasLineOfSight(target) && !b.coverShot && b.coverPhaseTicks <= 30) {
			archer.performRangedAttack(target, 1.0F);
			b.coverShot = true;
			b.coverPhaseTicks = Math.min(b.coverPhaseTicks, 6);
			com.mobsminecraft.debug.AiStats.add("cover_shot");
		}
		if (b.coverPhaseTicks <= 0) {
			b.coverPhase = HIDING;
			b.coverPhaseTicks = 25 + r.nextInt(35);
		}
		return true;
	}

	private static boolean tryStart(SmartBrain b, ServerLevel level, LivingEntity target, boolean griefing) {
		Mob mob = b.mob;
		double dist = mob.distanceTo(target);
		if (!griefing || b.wallCooldown > 0 || b.sniperPhase != 0 || dist < 8 || dist > 24 || !mob.onGround() || !mob.hasLineOfSight(target)) {
			return false;
		}
		RandomSource r = mob.getRandom();
		b.wallCooldown = 500 + r.nextInt(400);
		BlockPos feet = mob.blockPosition();
		Vec3 d = target.position().subtract(mob.position());
		Direction facing = Direction.getApproximateNearest(d.x, 0.0, d.z);
		Direction side = r.nextBoolean() ? facing.getClockWise() : facing.getCounterClockWise();
		// Peek out past the end of the wall (3 wide, so 2 to the side).
		BlockPos peek = feet.relative(side, 2);
		if (BlockWork.blocksMovement(level, peek) || BlockWork.blocksMovement(level, peek.above()) || !BlockWork.isFloor(level, peek.below())) {
			side = side.getOpposite();
			peek = feet.relative(side, 2);
			if (BlockWork.blocksMovement(level, peek) || BlockWork.blocksMovement(level, peek.above()) || !BlockWork.isFloor(level, peek.below())) {
				return false;
			}
		}
		b.planWall(level, target, 1, 1, 2, Blocks.COBBLESTONE.defaultBlockState(), false);
		b.coverHide = feet;
		b.coverPeek = peek;
		b.coverPhase = BUILDING;
		b.coverTicks = 0;
		com.mobsminecraft.debug.AiStats.add("cover_start");
		return true;
	}

	private static boolean end(SmartBrain b, String why) {
		b.coverPhase = NONE;
		com.mobsminecraft.debug.AiStats.add("cover_end:" + why);
		return false;
	}
}
