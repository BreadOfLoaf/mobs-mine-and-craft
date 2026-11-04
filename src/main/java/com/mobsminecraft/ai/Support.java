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
		brain.supportCooldown = 20;
		if (RedstoneTraps.waveSize(level, mob, target) < 2) {
			return;
		}
		RandomSource r = mob.getRandom();
		List<Mob> allies = level.getEntitiesOfClass(Mob.class, mob.getBoundingBox().inflate(12.0),
			m -> m != mob && m.isAlive() && m instanceof Enemy && mob.hasLineOfSight(m));

		Mob recipient = null;
		Holder<Potion> potion = null;
		for (Mob ally : allies) {
			if (ally.getHealth() / ally.getMaxHealth() < 0.6F) {
				recipient = ally;
				potion = ally.isInvertedHealAndHarm() ? Potions.HARMING : Potions.HEALING;
				break;
			}
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
		double dx = recipient.getX() - mob.getX();
		double dy = recipient.getY() - mob.getY();
		double dz = recipient.getZ() - mob.getZ();
		double horiz = Math.sqrt(dx * dx + dz * dz);
		Projectile.spawnProjectileUsingShoot(thrown, level, stack, dx, dy + horiz * 0.2, dz, 0.75F, 2.0F);
		mob.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
		level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.SPLASH_POTION_THROW, SoundSource.HOSTILE, 1.0F, 0.9F);
		brain.supportCooldown = 50 + r.nextInt(40);
		com.mobsminecraft.debug.AiStats.add("support_potion");
	}
}
