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
	enum Craft { LADDERS, LAVA_BUCKET, WATER_BUCKET, ANVILS, GEAR, ENCHANT }

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
			brain.combatCraftCooldown = 250 + r.nextInt(350);
			boolean playerAbove = target.getY() - mob.getY() > 2.5;
			if (playerAbove && brain.ladders == 0) {
				brain.crafting = Craft.LADDERS;
			} else if (brain.enchanter && !brain.gearEnchanted && Gear.hasEnchantable(mob) && r.nextFloat() < 0.5F) {
				brain.crafting = Craft.ENCHANT;
			} else if (Gear.hasUpgrade(mob) && r.nextFloat() < 0.65F) {
				// Powering up: each session upgrades one piece of armour or the weapon.
				brain.crafting = Craft.GEAR;
			} else {
				int roll = r.nextInt(100);
				brain.crafting = roll < 40 ? Craft.ANVILS : roll < 46 ? Craft.LAVA_BUCKET : roll < 64 ? Craft.WATER_BUCKET : Craft.LADDERS;
			}
			brain.craftTicks = 0;
			if (brain.crafting == Craft.ENCHANT) {
				brain.craftTable = BlockWork.findNearest(level, mob.blockPosition(), 8, s -> s.is(Blocks.ENCHANTING_TABLE));
				if (brain.craftTable == null) {
					brain.craftTable = brain.placeAdjacent(level, Blocks.ENCHANTING_TABLE.defaultBlockState());
					placeBookshelves(brain, level, brain.craftTable, r);
				}
			} else {
				brain.craftTable = BlockWork.findNearest(level, mob.blockPosition(), 8, s -> s.is(Blocks.CRAFTING_TABLE));
				if (brain.craftTable == null) {
					brain.craftTable = brain.placeAdjacent(level, Blocks.CRAFTING_TABLE.defaultBlockState());
				}
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
		if (brain.crafting == Craft.ENCHANT) {
			if (brain.craftTicks % 10 == 0) {
				level.sendParticles(net.minecraft.core.particles.ParticleTypes.ENCHANT, focus.x, focus.y + 0.8, focus.z, 20, 0.6, 0.4, 0.6, 0.8);
			}
		} else if (brain.craftTicks % 8 == 0) {
			mob.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
			level.playSound(null, mob.blockPosition(), SoundEvents.WOOD_HIT, SoundSource.HOSTILE, 0.8F, 1.0F);
		}
		if (brain.craftTicks >= (brain.crafting == Craft.ENCHANT ? 60 : CRAFT_TICKS)) {
			switch (brain.crafting) {
				case LADDERS -> brain.ladders += 16;
				case LAVA_BUCKET -> Consumable.addCarried(brain, Consumable.LAVA_BUCKET, 1, null);
				case WATER_BUCKET -> Consumable.addCarried(brain, Consumable.WATER_BUCKET, 2, null);
				case ANVILS -> brain.anvils += 3;
				case GEAR -> {
					net.minecraft.world.item.ItemStack made = Gear.upgrade(brain, mob.getRandom());
					if (made != null) {
						level.playSound(null, mob.blockPosition(), SoundEvents.ARMOR_EQUIP_IRON.value(), SoundSource.HOSTILE, 1.0F, 1.0F);
						com.mobsminecraft.debug.AiStats.add("gear:" + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(made.getItem()).getPath());
					}
				}
				case ENCHANT -> {
					int n = Gear.enchantAll(level, mob, mob.getRandom(), 10, 30);
					brain.gearEnchanted = true;
					if (mob instanceof net.minecraft.world.entity.monster.skeleton.AbstractSkeleton) {
						brain.bowEnchanted = true;
					}
					level.playSound(null, mob.blockPosition(), SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.HOSTILE, 1.0F, 1.0F);
					com.mobsminecraft.debug.AiStats.add("enchanted_pieces", n);
				}
			}
			level.playSound(null, mob.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.HOSTILE, 0.8F, 1.0F);
			com.mobsminecraft.debug.AiStats.add("craft:" + brain.crafting.name().toLowerCase());
			brain.crafting = null;
		}
		return true;
	}

	/** A few bookshelves around a freshly placed enchanting table. */
	private static void placeBookshelves(SmartBrain brain, ServerLevel level, BlockPos table, RandomSource r) {
		if (table == null) {
			return;
		}
		int wanted = 2 + r.nextInt(3);
		for (int dx = -2; dx <= 2 && wanted > 0; dx++) {
			for (int dz = -2; dz <= 2 && wanted > 0; dz++) {
				if ((Math.abs(dx) == 2 || Math.abs(dz) == 2) && r.nextFloat() < 0.35F) {
					BlockPos p = table.offset(dx, 0, dz);
					if (BlockWork.isFloor(level, p.below()) && !p.closerThan(brain.mob.blockPosition(), 1.5) && brain.place(level, p, Blocks.BOOKSHELF.defaultBlockState(), false)) {
						wanted--;
					}
				}
			}
		}
	}

	// ------------------------------------------------------------------ anvils

	/**
	 * Drops crafted anvils straight down onto the player: from the highest open spot above their head
	 * (up to 10 blocks, or just under a ceiling, so it works indoors too). Sometimes a volley of three.
	 */
	static void dropAnvil(SmartBrain brain, ServerLevel level, LivingEntity target) {
		Mob mob = brain.mob;
		double dist = mob.distanceTo(target);
		if (brain.anvils <= 0 || brain.anvilCooldown > 0 || dist > 10.0 || (!mob.hasLineOfSight(target) && dist > 8.0)) {
			return;
		}
		RandomSource r = mob.getRandom();
		brain.anvilCooldown = 80 + r.nextInt(60);
		BlockPos head = BlockPos.containing(target.position().add(target.getKnownMovement().multiply(2.0, 0.0, 2.0)));
		int dropped = 0;
		if (dropOver(level, head, r)) {
			dropped++;
		}
		if (brain.anvils >= 3 && r.nextFloat() < 0.35F) {
			for (int i = 0; i < 2; i++) {
				if (dropOver(level, head.relative(Direction.Plane.HORIZONTAL.getRandomDirection(r)), r)) {
					dropped++;
				}
			}
		}
		if (dropped > 0) {
			mob.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
			brain.anvils -= dropped;
			for (int i = 0; i < dropped; i++) {
				com.mobsminecraft.debug.AiStats.add("anvil_drop");
			}
		}
	}

	private static boolean dropOver(ServerLevel level, BlockPos column, RandomSource r) {
		BlockPos drop = findDropSpot(level, column);
		if (drop == null) {
			return false;
		}
		BlockState anvil = Blocks.ANVIL.defaultBlockState().setValue(AnvilBlock.FACING, Direction.Plane.HORIZONTAL.getRandomDirection(r));
		level.setBlock(drop, anvil, Block.UPDATE_ALL);
		level.playSound(null, drop, SoundEvents.ANVIL_PLACE, SoundSource.HOSTILE, 0.6F, 1.0F);
		return true;
	}

	/** Highest open spot (3-10 blocks up) above the player's column, stopping under the first ceiling. */
	private static BlockPos findDropSpot(ServerLevel level, BlockPos col) {
		BlockPos best = null;
		for (int h = 0; h <= 10; h++) {
			if (!level.getBlockState(col.above(h)).canBeReplaced()) {
				break;
			}
			if (h >= 3) {
				best = col.above(h);
			}
		}
		return best;
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
		com.mobsminecraft.debug.AiStats.add("ladder_climb");
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
