package com.mobsminecraft.ai;

import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.component.SwingAnimation;

/**
 * Support mobs: in a big fight, they throw splash potions at allies. Hurt allies get healing
 * (harming for undead, which heals them); allies fighting the player get strength, swiftness or
 * regeneration.
 */
final class Support {
	private Support() {}

	static void tick(SmartBrain brain, ServerLevel level, LivingEntity target) {
		Mob mob = brain.mob;
		if (!brain.supporter || brain.supportCooldown > 0) {
			return;
		}
		brain.supportCooldown = 15;
		RandomSource r = mob.getRandom();
		List<Mob> allies = level.getEntitiesOfClass(Mob.class, mob.getBoundingBox().inflate(14.0),
			m -> m != mob && m.isAlive() && m instanceof Enemy && mob.hasLineOfSight(m));
		// Supporters help any fight nearby, even one they aren't in themselves.
		if (target == null) {
			for (Mob ally : allies) {
				if (SmartBrain.isValidTarget(ally.getTarget())) {
					target = ally.getTarget();
					break;
				}
			}
			if (target == null) {
				return;
			}
		}

		Mob recipient = null;
		Holder<Potion> potion = null;
		// A knight's Juggernaut comes first, then an ally that fell back to be healed, the supporter itself, anyone hurt.
		Mob asked = brain.healRequest;
		if (brain.knightOf != null && brain.knightOf.isAlive() && brain.knightOf.getHealth() / brain.knightOf.getMaxHealth() < 0.8F
			&& brain.knightOf.distanceTo(mob) < 16.0F) {
			asked = brain.knightOf;
		}
		brain.healRequest = null;
		if (asked != null && asked.isAlive() && asked.distanceTo(mob) < 16.0F && asked.getHealth() < asked.getMaxHealth()) {
			recipient = asked;
		} else if (mob.getHealth() / mob.getMaxHealth() < 0.5F) {
			recipient = mob;
		} else {
			Mob worst = null;
			for (Mob ally : allies) {
				if (ally.getHealth() / ally.getMaxHealth() < 0.7F && (worst == null || ally.getHealth() / ally.getMaxHealth() < worst.getHealth() / worst.getMaxHealth())) {
					worst = ally;
				}
			}
			recipient = worst;
		}
		if (recipient != null) {
			potion = recipient.isInvertedHealAndHarm() ? Potions.HARMING : (recipient.getMaxHealth() > 30.0F ? Potions.STRONG_HEALING : Potions.HEALING);
		}
		if (recipient == null) {
			for (Mob ally : allies) {
				if (ally.getTarget() == target && !ally.hasEffect(MobEffects.STRENGTH) && !ally.hasEffect(MobEffects.SPEED)) {
					recipient = ally;
					int roll = r.nextInt(3);
					potion = roll == 0 ? Potions.STRENGTH : roll == 1 ? Potions.SWIFTNESS
						: ally.isInvertedHealAndHarm() ? Potions.STRENGTH : Potions.REGENERATION;
					break;
				}
			}
		}
		if (recipient == null) {
			return;
		}

		ItemStack stack = PotionContents.createItemStack(Items.SPLASH_POTION, potion);
		ThrownSplashPotion thrown = new ThrownSplashPotion(level, mob, stack);
		if (recipient == mob) {
			Projectile.spawnProjectileUsingShoot(thrown, level, stack, 0.0, -1.0, 0.0, 0.2F, 0.0F);
			level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.SPLASH_POTION_THROW, SoundSource.HOSTILE, 1.0F, 0.9F);
			brain.supportCooldown = 40 + r.nextInt(30);
			com.mobsminecraft.debug.AiStats.add("support_self_heal");
			return;
		}
		// Not every throw is a good one: sometimes the potion lands on the player instead.
		LivingEntity landsOn = recipient;
		if (r.nextFloat() < 0.22F && mob.distanceTo(target) < 12.0F && mob.hasLineOfSight(target)) {
			landsOn = target;
			com.mobsminecraft.debug.AiStats.add("support_misthrow");
		}
		double dx = landsOn.getX() - mob.getX();
		double dy = landsOn.getY() - mob.getY();
		double dz = landsOn.getZ() - mob.getZ();
		double horiz = Math.sqrt(dx * dx + dz * dz);
		Projectile.spawnProjectileUsingShoot(thrown, level, stack, dx, dy + horiz * 0.2, dz, 0.75F, 2.0F);
		mob.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
		level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.SPLASH_POTION_THROW, SoundSource.HOSTILE, 1.0F, 0.9F);
		brain.supportCooldown = 40 + r.nextInt(30);
		com.mobsminecraft.debug.AiStats.add(potion == Potions.HEALING || potion == Potions.STRONG_HEALING || potion == Potions.HARMING ? "support_heal" : "support_buff");
	}
}
