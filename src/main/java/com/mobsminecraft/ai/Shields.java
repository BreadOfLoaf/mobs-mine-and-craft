package com.mobsminecraft.ai;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.illager.Vindicator;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.monster.skeleton.WitherSkeleton;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Melee mobs that carry a shield raise it when the player is about to hit them or is drawing a bow,
 * in short bursts, lowering it to strike back. Vanilla handles the actual blocking (and axes disabling it).
 */
final class Shields {
	private Shields() {}

	static boolean canCarry(Mob mob) {
		ItemStack held = mob.getMainHandItem();
		if (held.is(Items.BOW) || held.is(Items.CROSSBOW) || held.is(Items.TRIDENT)) {
			return false;
		}
		return mob instanceof Zombie || mob instanceof AbstractPiglin || mob instanceof Vindicator || mob instanceof WitherSkeleton;
	}

	static void assign(SmartBrain brain, RandomSource r) {
		Mob mob = brain.mob;
		if (canCarry(mob) && mob.getOffhandItem().isEmpty() && r.nextFloat() < 0.3F) {
			give(brain);
			// Half the shield-bearers act as a moving barricade for the archers behind them.
			brain.shieldWall = r.nextFloat() < 0.5F;
		}
	}

	static void give(SmartBrain brain) {
		brain.mob.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
		brain.mob.setDropChance(EquipmentSlot.OFFHAND, 0.1F);
	}

	static boolean hasShield(Mob mob) {
		return mob.getOffhandItem().is(Items.SHIELD);
	}

	static void tick(SmartBrain brain, ServerLevel level, LivingEntity target) {
		Mob mob = brain.mob;
		boolean blocking = mob.isUsingItem() && mob.getUsedItemHand() == InteractionHand.OFF_HAND;
		if (!hasShield(mob) || target == null) {
			if (blocking) {
				mob.stopUsingItem();
			}
			return;
		}
		double dist = mob.distanceTo(target);
		RandomSource r = mob.getRandom();
		if (blocking) {
			mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
			boolean holdingLine = brain.shieldWall && wall(brain, level, target, dist);
			if (--brain.shieldTicks <= 0 || (dist > 10.0 && !holdingLine)) {
				mob.stopUsingItem();
				brain.shieldRest = holdingLine ? 6 + r.nextInt(8) : 12 + r.nextInt(18);
			}
			return;
		}
		if (brain.shieldRest > 0) {
			brain.shieldRest--;
			return;
		}
		if (brain.shieldWall && wall(brain, level, target, dist)) {
			return;
		}
		boolean aiming = target.isUsingItem() && (target.getUseItem().is(Items.BOW) || target.getUseItem().is(Items.CROSSBOW) || target.getUseItem().is(Items.TRIDENT));
		boolean closeThreat = dist < 4.0 && mob.hasLineOfSight(target);
		boolean justHit = mob.hurtTime > 0;
		if (aiming && dist < 30.0) {
			raise(brain, 25 + r.nextInt(20));
		} else if (closeThreat && (justHit || r.nextFloat() < 0.12F)) {
			raise(brain, 15 + r.nextInt(15));
		}
	}

	/**
	 * Shield wall: stand between the player and the nearest archer (or other ally), shield up, and advance
	 * slowly behind it. Up close it fights normally. Returns true while holding the line.
	 */
	private static boolean wall(SmartBrain brain, ServerLevel level, LivingEntity target, double dist) {
		Mob mob = brain.mob;
		if (dist < 3.0 || dist > 18.0) {
			return false;
		}
		Mob ward = null;
		for (Mob m : level.getEntitiesOfClass(Mob.class, mob.getBoundingBox().inflate(10.0), m -> m != mob && m.isAlive()
			&& m instanceof net.minecraft.world.entity.monster.Enemy && m.getTarget() == target && !hasShield(m))) {
			boolean ranged = m instanceof net.minecraft.world.entity.monster.RangedAttackMob;
			if (ward == null || (ranged && !(ward instanceof net.minecraft.world.entity.monster.RangedAttackMob))
				|| m.distanceToSqr(mob) < ward.distanceToSqr(mob)) {
				ward = m;
			}
		}
		if (ward == null) {
			return false;
		}
		net.minecraft.world.phys.Vec3 toPlayer = target.position().subtract(ward.position());
		if (toPlayer.horizontalDistance() < 4.0) {
			return false;
		}
		net.minecraft.world.phys.Vec3 spot = ward.position().add(toPlayer.multiply(1.0, 0.0, 1.0).normalize().scale(2.5));
		if (mob.position().distanceToSqr(spot) > 1.5) {
			mob.getNavigation().stop();
			mob.getMoveControl().setWantedPosition(spot.x, spot.y, spot.z, 0.8);
		}
		mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
		if (!(mob.isUsingItem() && mob.getUsedItemHand() == InteractionHand.OFF_HAND)) {
			raise(brain, 60 + mob.getRandom().nextInt(40));
			com.mobsminecraft.debug.AiStats.add("shield_wall");
		}
		return true;
	}

	private static void raise(SmartBrain brain, int ticks) {
		brain.mob.startUsingItem(InteractionHand.OFF_HAND);
		brain.shieldTicks = ticks;
		com.mobsminecraft.debug.AiStats.add("shield_up");
	}
}
