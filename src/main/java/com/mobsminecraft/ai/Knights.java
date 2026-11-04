package com.mobsminecraft.ai;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * A Juggernaut never comes alone: two or three Knights spawn with it. They wear iron and chainmail, carry a sword
 * and shield, stay close to their Juggernaut, guard it while it builds its fort, and throw healing potions at it.
 */
final class Knights {
	private Knights() {}

	static void spawnFor(Mob juggernaut, ServerLevel level, RandomSource r) {
		int n = 2 + r.nextInt(2);
		for (int i = 0; i < n; i++) {
			Mob knight = (Mob) ((EntityType<?>) juggernaut.getType()).create(level, EntitySpawnReason.REINFORCEMENT);
			if (knight == null) {
				return;
			}
			double a = r.nextDouble() * Math.PI * 2;
			Vec3 p = juggernaut.position().add(Math.cos(a) * 2.0, 0.0, Math.sin(a) * 2.0);
			knight.snapTo(p.x, juggernaut.getY(), p.z, r.nextFloat() * 360.0F, 0.0F);
			// Never a Juggernaut itself.
			knight.addTag(Juggernaut.ROLLED_TAG);
			gear(knight, r);
			// Knights stay only as long as their Juggernaut would (no piling up of permanent mobs).
			if (juggernaut.isPersistenceRequired()) {
				knight.setPersistenceRequired();
				knight.addTag("playtest");
			}
			level.addFreshEntity(knight);
			SmartBrain kb = ((com.mobsminecraft.SmartMobHolder) knight).mmc$getBrain();
			kb.knightOf = juggernaut;
			com.mobsminecraft.debug.AiStats.add("knight_spawned");
		}
	}

	private static void gear(Mob knight, RandomSource r) {
		set(knight, EquipmentSlot.HEAD, r.nextBoolean() ? Items.IRON_HELMET : Items.CHAINMAIL_HELMET);
		set(knight, EquipmentSlot.CHEST, r.nextBoolean() ? Items.IRON_CHESTPLATE : Items.CHAINMAIL_CHESTPLATE);
		set(knight, EquipmentSlot.LEGS, Items.CHAINMAIL_LEGGINGS);
		set(knight, EquipmentSlot.FEET, Items.IRON_BOOTS);
		set(knight, EquipmentSlot.MAINHAND, r.nextFloat() < 0.75F ? Items.IRON_SWORD : Items.IRON_AXE);
		set(knight, EquipmentSlot.OFFHAND, Items.SHIELD);
	}

	private static void set(Mob mob, EquipmentSlot slot, net.minecraft.world.item.Item item) {
		mob.setItemSlot(slot, new ItemStack(item));
		mob.setDropChance(slot, 0.1F);
	}

	/** Called once when the knight's brain starts: knights are supporters and shield-bearers. */
	static void init(SmartBrain brain) {
		// Knights get a personality like anyone else; they all hold the shield line and guard their lord.
		brain.shieldWall = true;
		brain.houseBuilder = false;
	}

	/** Keeps the knight near its Juggernaut when it has nothing better to do. Returns true while walking back. */
	static boolean stayClose(SmartBrain brain, boolean fighting) {
		Mob lord = brain.knightOf;
		if (lord == null) {
			return false;
		}
		if (!lord.isAlive()) {
			brain.knightOf = null;
			return false;
		}
		double leash = fighting ? 14.0 : 6.0;
		if (brain.mob.distanceTo(lord) > leash) {
			brain.walkTo(lord.position(), brain.walkSpeed() * 1.1);
			return true;
		}
		return false;
	}
}
