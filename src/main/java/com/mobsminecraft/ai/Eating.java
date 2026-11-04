package com.mobsminecraft.ai;

import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Eating and drinking take time, like they do for a player: the mob holds the food up, stands still and chews for
 * about 1.6 seconds before it has any effect. Hitting it in the meantime interrupts the meal (the food isn't used up).
 */
final class Eating {
	static final int EAT_TICKS = 32;

	private Eating() {}

	/** Starts eating (or drinking). {@code finish} applies the effect once the mob is done. Returns false if busy. */
	static boolean start(SmartBrain b, ServerLevel level, ItemStack food, int ticks, Runnable finish) {
		if (b.eating()) {
			return false;
		}
		Mob mob = b.mob;
		b.eatFood = food.copyWithCount(1);
		b.eatTicks = 0;
		b.eatDuration = ticks;
		b.eatFinish = finish;
		b.eatHealth = mob.getHealth();
		// Show the food in the off hand while eating.
		b.eatStashedOffhand = mob.getOffhandItem().copy();
		b.eatOffhandDrop = mob.getDropChances().byEquipment(EquipmentSlot.OFFHAND);
		mob.setItemSlot(EquipmentSlot.OFFHAND, b.eatFood.copy());
		mob.setDropChance(EquipmentSlot.OFFHAND, 0.0F);
		if (mob.isUsingItem()) {
			mob.stopUsingItem();
		}
		return true;
	}

	/** Returns true while the mob is eating. */
	static boolean tick(SmartBrain b, ServerLevel level) {
		if (!b.eating()) {
			return false;
		}
		Mob mob = b.mob;
		// Taking a hit spoils the meal.
		if (mob.getHealth() < b.eatHealth - 0.01F || mob.hurtTime > 0 && b.eatTicks > 0 && mob.hurtTime == 9) {
			com.mobsminecraft.debug.AiStats.add("eat_interrupted");
			stop(b);
			return false;
		}
		b.eatHealth = Math.max(b.eatHealth, mob.getHealth());
		b.holdStill();
		mob.setXRot(25.0F);
		b.eatTicks++;
		if (b.eatTicks % 4 == 0) {
			boolean drink = b.eatFood.is(Items.POTION) || b.eatFood.is(Items.MILK_BUCKET);
			level.playSound(null, mob.blockPosition(), drink ? SoundEvents.GENERIC_DRINK.value() : SoundEvents.GENERIC_EAT.value(), SoundSource.HOSTILE,
				0.5F + 0.5F * mob.getRandom().nextInt(2), (mob.getRandom().nextFloat() - mob.getRandom().nextFloat()) * 0.2F + 1.0F);
			if (!drink) {
				level.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, b.eatFood.getItem()),
					mob.getX() + mob.getLookAngle().x * 0.4, mob.getEyeY() - 0.15, mob.getZ() + mob.getLookAngle().z * 0.4, 5, 0.1, 0.05, 0.1, 0.05);
			}
		}
		if (b.eatTicks >= b.eatDuration) {
			Runnable finish = b.eatFinish;
			stop(b);
			level.playSound(null, mob.blockPosition(), SoundEvents.PLAYER_BURP, SoundSource.HOSTILE, 0.5F, 0.9F + mob.getRandom().nextFloat() * 0.1F);
			if (finish != null) {
				finish.run();
			}
			com.mobsminecraft.debug.AiStats.add("ate");
		}
		return true;
	}

	static void stop(SmartBrain b) {
		Mob mob = b.mob;
		if (b.eatStashedOffhand != null) {
			mob.setItemSlot(EquipmentSlot.OFFHAND, b.eatStashedOffhand);
			mob.setDropChance(EquipmentSlot.OFFHAND, b.eatOffhandDrop);
		}
		b.eatStashedOffhand = null;
		b.eatFood = null;
		b.eatFinish = null;
		b.eatTicks = 0;
	}
}
