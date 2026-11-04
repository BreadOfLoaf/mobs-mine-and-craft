package com.mobsminecraft.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Mid-fight crafting: a hostile mob places (or finds) a crafting table and crafts what helps it now:
 * ladders to climb the player's walls, lava or water buckets, or anvils to drop on the player.
 */
final class CombatCrafting {
	enum Craft { LADDERS, LAVA_BUCKET, WATER_BUCKET, ANVILS }

	private static final int CRAFT_TICKS = 40;

	private CombatCrafting() {}

	/** Runs a crafting session, or starts one when it's a good moment. Returns true while busy. */
	static boolean tick(SmartBrain brain, ServerLevel level, LivingEntity target, boolean griefing) {
		Mob mob = brain.mob;
		if (brain.crafting == null) {
			if (!brain.combatCrafter || !griefing || brain.combatCraftCooldown > 0 || !mob.onGround()) {
				return false;
			}
			double dist = mob.distanceTo(target);
			if (dist < 6.0 || dist > 28.0) {
				return false;
			}
			RandomSource r = mob.getRandom();
			brain.combatCraftCooldown = 600 + r.nextInt(600);
			boolean playerAbove = target.getY() - mob.getY() > 2.5;
			if (playerAbove && brain.ladders == 0) {
				brain.crafting = Craft.LADDERS;
			} else {
				int roll = r.nextInt(100);
				brain.crafting = roll < 35 ? Craft.ANVILS : roll < 43 ? Craft.LAVA_BUCKET : roll < 70 ? Craft.WATER_BUCKET : Craft.LADDERS;
			}
			brain.craftTicks = 0;
			brain.craftTable = BlockWork.findNearest(level, mob.blockPosition(), 8, s -> s.is(Blocks.CRAFTING_TABLE));
			if (brain.craftTable == null) {
				brain.craftTable = brain.placeAdjacent(level, Blocks.CRAFTING_TABLE.defaultBlockState());
			}
			return true;
		}

		Vec3 focus = brain.craftTable != null ? Vec3.atCenterOf(brain.craftTable) : mob.getEyePosition();
		if (brain.craftTable != null && mob.distanceToSqr(focus) > 9.0) {
			if (mob.tickCount % 10 == 0) {
				mob.getNavigation().moveTo(focus.x, focus.y, focus.z, brain.walkSpeed());
			}
			if (++brain.craftTicks > 160) {
				brain.crafting = null;
			}
			return true;
		}
		brain.holdStill();
		mob.getLookControl().setLookAt(focus);
		brain.craftTicks++;
		if (brain.craftTicks % 8 == 0) {
			mob.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
			level.playSound(null, mob.blockPosition(), SoundEvents.WOOD_HIT, SoundSource.HOSTILE, 0.8F, 1.0F);
		}
		if (brain.craftTicks >= CRAFT_TICKS) {
			switch (brain.crafting) {
				case LADDERS -> brain.ladders += 16;
				case LAVA_BUCKET -> Consumable.addCarried(brain, Consumable.LAVA_BUCKET, 1, null);
				case WATER_BUCKET -> Consumable.addCarried(brain, Consumable.WATER_BUCKET, 2, null);
				case ANVILS -> brain.anvils += 2;
			}
			level.playSound(null, mob.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.HOSTILE, 0.8F, 1.0F);
			brain.crafting = null;
		}
		return true;
	}

	// ------------------------------------------------------------------ anvils

	/** Places a crafted anvil a few blocks above where the player is heading, so it falls on them. */
	static void dropAnvil(SmartBrain brain, ServerLevel level, LivingEntity target) {
		Mob mob = brain.mob;
		if (brain.anvils <= 0 || brain.anvilCooldown > 0 || mob.distanceTo(target) > 8.0F || !mob.hasLineOfSight(target)) {
			return;
		}
		RandomSource r = mob.getRandom();
		brain.anvilCooldown = 100 + r.nextInt(80);
		Vec3 aim = target.position().add(target.getKnownMovement().multiply(6.0, 0.0, 6.0));
		BlockPos drop = findDropSpot(level, BlockPos.containing(aim));
		if (drop == null) {
			return;
		}
		BlockState anvil = Blocks.ANVIL.defaultBlockState().setValue(AnvilBlock.FACING, Direction.Plane.HORIZONTAL.getRandomDirection(r));
		level.setBlock(drop, anvil, Block.UPDATE_ALL);
		level.playSound(null, drop, SoundEvents.ANVIL_PLACE, SoundSource.HOSTILE, 0.6F, 1.0F);
		mob.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
		brain.anvils--;
	}

	/** Highest spot 3-6 blocks up with open air all the way down to the player's column. */
	private static BlockPos findDropSpot(ServerLevel level, BlockPos col) {
		for (int h = 6; h >= 3; h--) {
			boolean clear = true;
			for (int y = 0; y <= h && clear; y++) {
				clear = level.getBlockState(col.above(y)).canBeReplaced();
			}
			if (clear) {
				return col.above(h);
			}
		}
		return null;
	}

	// ------------------------------------------------------------------ ladders

	/**
	 * When the player is up a wall and the mob has crafted ladders, it puts a ladder column up the wall
	 * and climbs it. Returns true if it took over movement this tick.
	 */
	static boolean tryLadders(SmartBrain brain, ServerLevel level, double dx, double dy, double dz, double horiz) {
		if (brain.ladders <= 0 || dy <= 2.5 || horiz >= 4.0) {
			return false;
		}
		Mob mob = brain.mob;
		Direction dir = Direction.getApproximateNearest(dx, 0.0, dz);
		BlockPos feet = mob.blockPosition();
		BlockPos wall = feet.relative(dir);
		if (!BlockWork.blocksMovement(level, wall) || !BlockWork.blocksMovement(level, wall.above())) {
			return false;
		}
		if (!level.getBlockState(feet).is(Blocks.LADDER)) {
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
		brain.climbToward = wall;
		return true;
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
}
