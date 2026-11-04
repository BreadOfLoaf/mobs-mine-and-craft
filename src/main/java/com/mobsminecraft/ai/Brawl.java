package com.mobsminecraft.ai;

import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.monster.piglin.PiglinBrute;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.component.SwingAnimation;

/**
 * Mob-on-mob fights the vanilla AI doesn't have: villagers stand and fight zombies and other monsters (and join
 * in whatever the iron golem is fighting) instead of running; animals fight back when a monster hurts them
 * or one of their herd; and some piglin brutes pick fights with ordinary piglins as well as the player.
 */
final class Brawl {
	private Brawl() {}

	static void assign(SmartBrain brain, RandomSource r) {
		Mob mob = brain.mob;
		if (mob instanceof Villager) {
			brain.brave = true;
		} else if (mob instanceof Animal) {
			brain.brave = r.nextFloat() < 0.5F;
		} else if (mob instanceof PiglinBrute) {
			brain.brave = true;
		}
	}

	/** Returns true while the mob is busy fighting another mob. */
	static boolean tick(SmartBrain brain, ServerLevel level, LivingEntity playerTarget) {
		Mob mob = brain.mob;
		if (!brain.brave) {
			return false;
		}
		if (mob.tickCount % 10 == 0 || (brain.brawlFoe != null && !valid(brain, brain.brawlFoe))) {
			brain.brawlFoe = pickFoe(brain, level, playerTarget);
		}
		LivingEntity foe = brain.brawlFoe;
		if (foe == null || !valid(brain, foe)) {
			brain.brawlFoe = null;
			return false;
		}
		fight(brain, level, foe);
		return true;
	}

	private static boolean valid(SmartBrain brain, LivingEntity foe) {
		Mob mob = brain.mob;
		if (!foe.isAlive() || foe.distanceTo(mob) > 20.0F) {
			return false;
		}
		// Passive mobs lose their nerve when badly hurt.
		return !(brain.isPassive() && mob.getHealth() / mob.getMaxHealth() < 0.25F);
	}

	private static LivingEntity pickFoe(SmartBrain brain, ServerLevel level, LivingEntity playerTarget) {
		Mob mob = brain.mob;
		if (mob instanceof PiglinBrute) {
			// Brutes go for whichever is closer: the player, or another piglin.
			AbstractPiglin piglin = nearest(level, mob, AbstractPiglin.class, 14.0, p -> !(p instanceof PiglinBrute) && !p.isBaby());
			if (piglin == null) {
				return null;
			}
			if (playerTarget != null && playerTarget.distanceTo(mob) < piglin.distanceTo(mob)) {
				return null;
			}
			return piglin;
		}
		if (brain.isPassive() && mob.getHealth() / mob.getMaxHealth() < 0.25F) {
			return null;
		}
		// Whatever hurt it (if it's a monster), then a monster hurting one of its herd or village.
		LivingEntity hurtBy = mob.getLastHurtByMob();
		if (hurtBy instanceof Enemy && hurtBy.isAlive() && mob.tickCount - mob.getLastHurtByMobTimestamp() < 200) {
			return hurtBy;
		}
		if (mob instanceof Villager) {
			IronGolem golem = nearest(level, mob, IronGolem.class, 16.0, g -> g.getTarget() instanceof Enemy && g.getTarget().isAlive());
			if (golem != null) {
				return golem.getTarget();
			}
			return nearest(level, mob, Mob.class, 10.0, m -> m instanceof Enemy && !(m instanceof Creeper) && m.isAlive() && mob.hasLineOfSight(m));
		}
		// Animals defend each other: a monster attacking (or right next to) their herd.
		List<? extends Mob> herd = level.getEntitiesOfClass(mob.getClass(), mob.getBoundingBox().inflate(8.0), m -> m != mob && m.isAlive());
		for (Mob friend : herd) {
			LivingEntity attacker = friend.getLastHurtByMob();
			if (attacker instanceof Enemy && attacker.isAlive() && friend.tickCount - friend.getLastHurtByMobTimestamp() < 100) {
				return attacker;
			}
		}
		if (herd.size() >= 2) {
			return nearest(level, mob, Mob.class, 5.0, m -> m instanceof Enemy && !(m instanceof Creeper) && m.isAlive() && mob.hasLineOfSight(m));
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	private static <T extends LivingEntity> T nearest(ServerLevel level, Mob mob, Class<T> type, double radius, java.util.function.Predicate<T> test) {
		T best = null;
		for (T e : level.getEntitiesOfClass(type, mob.getBoundingBox().inflate(radius), x -> x != mob && test.test(x))) {
			if (best == null || e.distanceToSqr(mob) < best.distanceToSqr(mob)) {
				best = e;
			}
		}
		return best;
	}

	private static void fight(SmartBrain brain, ServerLevel level, LivingEntity foe) {
		Mob mob = brain.mob;
		mob.getLookControl().setLookAt(foe, 30.0F, 30.0F);
		double reach = mob.getBbWidth() * 0.5 + foe.getBbWidth() * 0.5 + 1.0;
		double dist = mob.distanceTo(foe);
		if (dist > reach) {
			// Steer straight at it (overrides vanilla panicking/fleeing).
			mob.getNavigation().stop();
			mob.getMoveControl().setWantedPosition(foe.getX(), foe.getY(), foe.getZ(), brain.isPassive() ? 1.3 : 1.1);
		}
		if (brain.punchCooldown > 0 || dist > reach + 0.4) {
			return;
		}
		brain.punchCooldown = mob instanceof PiglinBrute ? 20 : 25;
		mob.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
		if (mob instanceof PiglinBrute) {
			mob.doHurtTarget(level, foe);
		} else {
			foe.hurtServer(level, mob.damageSources().mobAttack(mob), damageFor(mob));
			level.playSound(null, mob.blockPosition(), SoundEvents.PLAYER_ATTACK_WEAK, SoundSource.NEUTRAL, 0.6F, 1.0F);
		}
		com.mobsminecraft.debug.AiStats.add("brawl_hit:" + mob.getType().toShortString());
	}

	private static float damageFor(Mob mob) {
		if (mob instanceof Villager) return 2.5F;
		float size = mob.getBbWidth() * mob.getBbHeight();
		return size > 1.5F ? 3.0F : size > 0.8F ? 2.0F : 1.0F;
	}
}
