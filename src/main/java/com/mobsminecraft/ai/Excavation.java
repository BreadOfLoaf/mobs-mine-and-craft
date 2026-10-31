package com.mobsminecraft.ai;

import com.mobsminecraft.world.TimedCleanup;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.vehicle.boat.Boat;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Digging behaviour: strip mines with torches, caving parties, breaking into bases through the weakest
 * wall, cave-ins, undermining the player, and crafting ladders, stairs or boats to reach them.
 */
final class Excavation {
	private Excavation() {}

	static boolean isDark(ServerLevel level, BlockPos pos) {
		return level.getBrightness(LightLayer.BLOCK, pos) < 8 && level.getBrightness(LightLayer.SKY, pos) < 8;
	}

	// ------------------------------------------------------------------ strip mines

	/** Idle mobs with a pickaxe dig a straight 1x2 tunnel into a hillside, lighting it and branching to ores. */
	static boolean tickStripMine(SmartBrain brain, ServerLevel level) {
		Mob mob = brain.mob;
		RandomSource r = mob.getRandom();
		BlockPos here = mob.blockPosition();
		if (brain.stripSteps <= 0) {
			if (brain.stripCooldown > 0 || Industry.toolKind(mob.getMainHandItem()) != Industry.PICKAXE || !mob.onGround()) {
				return false;
			}
			brain.stripCooldown = 600 + r.nextInt(1200);
			if (r.nextFloat() > 0.5F) {
				return false;
			}
			Direction dir = null;
			Direction first = Direction.Plane.HORIZONTAL.getRandomDirection(r);
			for (int i = 0; i < 4 && dir == null; i++) {
				Direction d = i == 0 ? first : (first = first.getClockWise());
				BlockPos ahead = here.relative(d);
				if (BlockWork.blocksMovement(level, ahead) && BlockWork.blocksMovement(level, ahead.above())) {
					dir = d;
				}
			}
			if (dir == null) {
				return false;
			}
			brain.stripDir = dir;
			brain.stripSteps = 12 + r.nextInt(10);
			brain.stripLast = here;
			brain.sinceTorch = 0;
			recruitParty(brain, level);
		}
		if (brain.hasDigTarget()) {
			return false;
		}
		if (!here.equals(brain.stripLast)) {
			brain.stripLast = here;
			brain.stripSteps--;
			if (++brain.sinceTorch >= 5 && isDark(level, here)) {
				if (brain.place(level, here.relative(brain.stripDir.getOpposite()), Blocks.TORCH.defaultBlockState(), false)) {
					brain.sinceTorch = 0;
				}
			}
		}
		if (mob.tickCount % 10 == 0) {
			BlockPos ore = BlockWork.findNearestAt(level, here, 3, (p, s) -> BlockWork.isOre(s) && BlockWork.canMine(mob, level, p, s));
			if (ore != null) {
				brain.setDigTarget(ore, true);
				return false;
			}
		}
		Vec3 goal = Vec3.atBottomCenterOf(here.relative(brain.stripDir, 3));
		if (!brain.stepToward(level, goal, brain.stripDir)) {
			brain.stripSteps = 0;
		}
		return true;
	}

	// ------------------------------------------------------------------ caving parties

	/** Up to two nearby mobs of the same kind join a strip mine: a torchbearer and an ore-hunter. */
	private static void recruitParty(SmartBrain leader, ServerLevel level) {
		Mob mob = leader.mob;
		List<? extends Mob> near = level.getEntitiesOfClass(mob.getClass(), mob.getBoundingBox().inflate(10.0), m -> m != mob && m.isAlive());
		int role = 0;
		for (Mob m : near) {
			if (role >= 2) {
				break;
			}
			SmartBrain follower = Industry.brainOf(m);
			if (follower.partyLeader != null || follower.stripSteps > 0) {
				continue;
			}
			follower.partyLeader = leader;
			follower.partyRole = role++;
		}
	}

	static boolean tickFollower(SmartBrain brain, ServerLevel level) {
		SmartBrain leader = brain.partyLeader;
		if (leader == null) {
			return false;
		}
		Mob mob = brain.mob;
		if (!leader.mob.isAlive() || leader.stripSteps <= 0 || mob.distanceToSqr(leader.mob) > 400.0) {
			brain.partyLeader = null;
			return false;
		}
		if (brain.hasDigTarget()) {
			return false;
		}
		if (brain.partyRole == 0 && --brain.torchTicks <= 0) {
			brain.torchTicks = 60;
			if (isDark(level, mob.blockPosition())) {
				BlockPos spot = BlockWork.findAdjacentSpot(level, mob);
				if (spot != null) {
					brain.place(level, spot, Blocks.TORCH.defaultBlockState(), false);
				}
			}
		}
		if (brain.partyRole == 1 && mob.tickCount % 20 == 0) {
			BlockPos ore = BlockWork.findNearestAt(level, mob.blockPosition(), 4, (p, s) -> BlockWork.isOre(s) && BlockWork.canMine(mob, level, p, s));
			if (ore != null) {
				brain.setDigTarget(ore, true);
				return false;
			}
		}
		if (mob.distanceToSqr(leader.mob) > 9.0 && mob.tickCount % 10 == 0) {
			mob.getNavigation().moveTo(leader.mob, 1.0);
		}
		return true;
	}

	// ------------------------------------------------------------------ breaking into bases

	/**
	 * Chooses which way to dig toward the target: straight on, or sideways along the other axis if that
	 * wall is much weaker (dirt, wood or glass instead of stone, and never obsidian).
	 */
	static Direction breachDirection(SmartBrain brain, ServerLevel level, double dx, double dz) {
		if (brain.breachTicks > 0 && brain.breachDir != null) {
			return brain.breachDir;
		}
		Direction primary = Direction.getApproximateNearest(dx, 0.0, dz);
		Direction secondary = Math.abs(dx) > Math.abs(dz)
			? (dz >= 0 ? Direction.SOUTH : Direction.NORTH)
			: (dx >= 0 ? Direction.EAST : Direction.WEST);
		double primaryCost = wallCost(brain, level, primary);
		double secondaryCost = wallCost(brain, level, secondary);
		Direction chosen = secondaryCost + 20.0 < primaryCost ? secondary : primary;
		brain.breachDir = chosen;
		brain.breachTicks = 40;
		return chosen;
	}

	/** Ticks needed to clear the way one block in that direction (huge if impossible). */
	private static double wallCost(SmartBrain brain, ServerLevel level, Direction dir) {
		Mob mob = brain.mob;
		BlockPos next = mob.blockPosition().relative(dir);
		double cost = 0.0;
		for (int i = 0; i < brain.height(); i++) {
			BlockPos p = next.above(i);
			if (!BlockWork.blocksMovement(level, p)) {
				continue;
			}
			BlockState state = level.getBlockState(p);
			if (!BlockWork.canMine(mob, level, p, state)) {
				return 10000.0;
			}
			cost += 1.0 / Math.max(0.001F, BlockWork.progressPerTick(mob, level, p, state));
		}
		return cost;
	}

	// ------------------------------------------------------------------ cave-ins

	/** Mines out what holds up gravel or sand over the player, or drops some on them. */
	static void caveIn(SmartBrain brain, ServerLevel level, LivingEntity target) {
		Mob mob = brain.mob;
		if (brain.caveCooldown > 0 || !(mob instanceof Enemy) || mob.distanceTo(target) > 7.0F || !mob.hasLineOfSight(target)) {
			return;
		}
		RandomSource r = mob.getRandom();
		brain.caveCooldown = 500 + r.nextInt(400);
		BlockPos head = target.blockPosition();
		for (int h = 2; h <= 5; h++) {
			BlockPos support = head.above(h);
			BlockState state = level.getBlockState(support);
			if (BlockWork.blocksMovement(level, support)
				&& level.getBlockState(support.above()).getBlock() instanceof FallingBlock
				&& BlockWork.canMine(mob, level, support, state)
				&& mob.distanceToSqr(Vec3.atCenterOf(support)) < 25.0) {
				brain.startMining(support, true);
				return;
			}
			if (BlockWork.blocksMovement(level, support)) {
				break;
			}
		}
		if (r.nextFloat() > 0.4F) {
			return;
		}
		for (int h = 1; h <= 4; h++) {
			if (!level.getBlockState(head.above(h)).canBeReplaced()) {
				return;
			}
		}
		BlockState drop = r.nextBoolean() ? Blocks.GRAVEL.defaultBlockState() : Blocks.SAND.defaultBlockState();
		level.setBlock(head.above(4), drop, Block.UPDATE_ALL);
		level.setBlock(head.above(3), drop, Block.UPDATE_ALL);
		level.playSound(null, head.above(3), SoundEvents.GRAVEL_PLACE, SoundSource.HOSTILE, 1.0F, 0.8F);
		mob.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
	}

	// ------------------------------------------------------------------ undermining

	/**
	 * Mobs with a pickaxe or shovel crack the block under the player's feet, then break the two blocks below,
	 * dropping them into a hole. Stepping away during the wind-up dodges it. Returns true while busy.
	 */
	static boolean undermine(SmartBrain brain, ServerLevel level, LivingEntity target) {
		Mob mob = brain.mob;
		if (brain.underminePos == null) {
			int kind = Industry.toolKind(mob.getMainHandItem());
			if (brain.undermineCooldown > 0 || (kind != Industry.PICKAXE && kind != Industry.SHOVEL)
				|| mob.distanceTo(target) > 4.0F || !target.onGround() || !mob.hasLineOfSight(target)) {
				return false;
			}
			BlockPos feet = target.blockPosition();
			BlockPos b1 = feet.below();
			BlockPos b2 = feet.below(2);
			boolean minable = BlockWork.canMine(mob, level, b1, level.getBlockState(b1))
				&& (level.getBlockState(b2).isAir() || BlockWork.canMine(mob, level, b2, level.getBlockState(b2)));
			boolean safe = level.getFluidState(b2).isEmpty() && level.getFluidState(feet.below(3)).isEmpty();
			if (!minable || !safe) {
				return false;
			}
			brain.underminePos = feet;
			brain.undermineTicks = 0;
			brain.undermineCooldown = 300 + mob.getRandom().nextInt(200);
			return true;
		}
		BlockPos below = brain.underminePos.below();
		if (!target.blockPosition().equals(brain.underminePos)) {
			level.destroyBlockProgress(mob.getId(), below, -1);
			brain.underminePos = null;
			return false;
		}
		brain.holdStill();
		mob.getLookControl().setLookAt(Vec3.atCenterOf(below));
		brain.undermineTicks++;
		if (brain.undermineTicks % 4 == 0) {
			mob.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
		}
		level.destroyBlockProgress(mob.getId(), below, Math.min(9, brain.undermineTicks));
		if (brain.undermineTicks >= 12) {
			level.destroyBlockProgress(mob.getId(), below, -1);
			level.destroyBlock(below, false, mob);
			level.destroyBlock(below.below(), false, mob);
			brain.blocks = Math.min(brain.blocks + 2, 128);
			brain.underminePos = null;
		}
		return true;
	}

	// ------------------------------------------------------------------ crafting things to reach the player

	/**
	 * When the player can't be reached: craft ladders for a wall, stairs for a slope, or a boat for water,
	 * then use them. Returns true if it took over movement this tick.
	 */
	static boolean tryClimbAids(SmartBrain brain, ServerLevel level, LivingEntity target, double dx, double dy, double dz, double horiz) {
		Mob mob = brain.mob;
		Direction dir = Direction.getApproximateNearest(dx, 0.0, dz);
		BlockPos feet = mob.blockPosition();
		BlockPos next = feet.relative(dir);

		// Ladders up a wall.
		boolean wall = BlockWork.blocksMovement(level, next) && BlockWork.blocksMovement(level, next.above());
		if (dy > 2.5 && horiz < 4.0 && wall) {
			boolean hasLadder = level.getBlockState(feet).is(Blocks.LADDER);
			if (!hasLadder) {
				if (brain.ladders <= 0) {
					Industry.startJob(brain, Industry.Job.LADDERS);
					return true;
				}
				mob.setPos(feet.getX() + 0.5, mob.getY(), feet.getZ() + 0.5);
				int top = Math.min(Mth.ceil(dy) + 1, 12);
				for (int i = 0; i < top && brain.ladders > 0; i++) {
					BlockPos p = feet.above(i);
					if (!BlockWork.blocksMovement(level, p.relative(dir))) {
						break;
					}
					if (brain.place(level, p, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, dir.getOpposite()), false)) {
						brain.ladders--;
					}
				}
			}
			brain.climbTicks = 60;
			brain.climbToward = next;
			return true;
		}

		// A boat across water.
		boolean water = level.getFluidState(next).is(FluidTags.WATER) || level.getFluidState(next.below()).is(FluidTags.WATER);
		boolean wide = level.getFluidState(next.relative(dir, 2).below()).is(FluidTags.WATER)
			|| level.getFluidState(next.relative(dir, 2)).is(FluidTags.WATER);
		if (Math.abs(dy) < 3.0 && horiz > 4.0 && water && wide && mob.getBbWidth() < 1.4F) {
			if (brain.boats <= 0) {
				Industry.startJob(brain, Industry.Job.BOAT);
				return true;
			}
			Boat boat = EntityTypes.OAK_BOAT.create(level, EntitySpawnReason.EVENT);
			if (boat != null) {
				BlockPos surface = level.getFluidState(next).is(FluidTags.WATER) ? next : next.below();
				boat.snapTo(surface.getX() + 0.5, surface.getY() + 0.9, surface.getZ() + 0.5, dir.toYRot(), 0.0F);
				level.addFreshEntity(boat);
				mob.startRiding(boat);
				brain.boat = boat;
				brain.boatTicks = 0;
				brain.boats--;
				TimedCleanup.scheduleEntity(level, boat, 1200);
				level.playSound(null, boat.getX(), boat.getY(), boat.getZ(), SoundEvents.WOOD_PLACE, SoundSource.HOSTILE, 1.0F, 1.0F);
				return true;
			}
		}

		// Stairs up a slope.
		boolean open = !BlockWork.blocksMovement(level, next) && !BlockWork.blocksMovement(level, next.above())
			&& !BlockWork.blocksMovement(level, next.above(2));
		if (dy > 1.5 && horiz >= 2.5 && open) {
			if (brain.stairs <= 0) {
				Industry.startJob(brain, Industry.Job.STAIRS);
				return true;
			}
			if (brain.place(level, next, Blocks.COBBLESTONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, dir), false)) {
				brain.stairs--;
			}
			mob.getNavigation().stop();
			mob.getMoveControl().setWantedPosition(next.getX() + 0.5, next.getY() + 1, next.getZ() + 0.5, 1.0);
			return true;
		}
		return false;
	}

	/** Pushes into the ladder wall so the mob climbs. Returns true while climbing. */
	static boolean tickClimb(SmartBrain brain) {
		if (brain.climbTicks <= 0 || brain.climbToward == null) {
			return false;
		}
		brain.climbTicks--;
		Mob mob = brain.mob;
		mob.getNavigation().stop();
		mob.getMoveControl().setWantedPosition(brain.climbToward.getX() + 0.5, mob.getY() + 1.0, brain.climbToward.getZ() + 0.5, 1.0);
		return true;
	}

	/** Paddles the mob's boat toward the target; hops out at the far shore. Returns true while boating. */
	static boolean tickBoat(SmartBrain brain, LivingEntity target) {
		if (brain.boat == null) {
			return false;
		}
		Mob mob = brain.mob;
		Boat boat = brain.boat;
		if (boat.isRemoved() || mob.getVehicle() != boat) {
			brain.boat = null;
			return false;
		}
		brain.boatTicks++;
		boolean arrived = target == null || mob.distanceTo(target) < 3.0F || brain.boatTicks > 400
			|| (boat.horizontalCollision && brain.boatTicks > 20);
		if (arrived) {
			mob.stopRiding();
			brain.boat = null;
			return false;
		}
		Vec3 d = target.position().subtract(boat.position()).multiply(1.0, 0.0, 1.0);
		if (d.lengthSqr() > 1.0E-4) {
			d = d.normalize().scale(0.25);
			boat.setYRot((float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90.0F);
			boat.setDeltaMovement(d.x, boat.getDeltaMovement().y, d.z);
		}
		return true;
	}
}
