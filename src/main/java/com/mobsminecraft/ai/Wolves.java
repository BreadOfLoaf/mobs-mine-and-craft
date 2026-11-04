package com.mobsminecraft.ai;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Wild wolves: always Tacticians. They heal by eating rotten flesh (and meat) they pick up, eat golden apples when
 * badly hurt, and get stronger with gear they find: a sword in the jaws adds its damage, and any chestplate they
 * find becomes wolf armour.
 */
final class Wolves {
	private Wolves() {}

	static boolean isWildWolf(SmartBrain b) {
		return b.mob instanceof Wolf wolf && !wolf.isTame();
	}

	/** Out of a fight and hurt: eat something it's carrying. */
	static boolean heal(SmartBrain b, ServerLevel level) {
		Wolf wolf = (Wolf) b.mob;
		if (b.eating() || wolf.getHealth() / wolf.getMaxHealth() > 0.7F || wolf.tickCount % 20 != 0) {
			return false;
		}
		for (ItemStack s : b.loot) {
			if (s.is(Items.ROTTEN_FLESH) || Recovery.isRawMeat(s) || Recovery.isCookedMeat(s)) {
				ItemStack food = s.copyWithCount(1);
				float heal = Recovery.isCookedMeat(s) ? 8.0F : 4.0F;
				s.shrink(1);
				b.loot.removeIf(ItemStack::isEmpty);
				Eating.start(b, level, food, Eating.EAT_TICKS, () -> wolf.heal(heal));
				com.mobsminecraft.debug.AiStats.add("wolf_eat:" + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(food.getItem()).getPath());
				return true;
			}
		}
		return false;
	}

	/** What a wild wolf will pick up. */
	static boolean wants(SmartBrain b, ItemStack s) {
		Wolf wolf = (Wolf) b.mob;
		if (s.is(Items.ROTTEN_FLESH) || Recovery.isRawMeat(s) || Recovery.isCookedMeat(s) || s.is(Items.GOLDEN_APPLE) || s.is(Items.ENCHANTED_GOLDEN_APPLE)) {
			return true;
		}
		if (Gear.weaponTier(s) > 0) {
			return Gear.weaponTier(s) > Gear.weaponTier(wolf.getMainHandItem());
		}
		if (s.is(Items.WOLF_ARMOR) || isChestplate(s)) {
			return wolf.getItemBySlot(EquipmentSlot.BODY).isEmpty();
		}
		return false;
	}

	private static boolean isChestplate(ItemStack s) {
		return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().endsWith("_chestplate");
	}

	/** Puts gear on the wolf. Returns true if it took the item. */
	static boolean equip(SmartBrain b, ItemStack s) {
		Wolf wolf = (Wolf) b.mob;
		if (Gear.weaponTier(s) > Gear.weaponTier(wolf.getMainHandItem())) {
			wolf.setItemSlot(EquipmentSlot.MAINHAND, s.copyWithCount(1));
			wolf.setDropChance(EquipmentSlot.MAINHAND, 1.0F);
			com.mobsminecraft.debug.AiStats.add("wolf_sword");
			return true;
		}
		if ((s.is(Items.WOLF_ARMOR) || isChestplate(s)) && wolf.getItemBySlot(EquipmentSlot.BODY).isEmpty()) {
			// Any chestplate gets turned into proper wolf armour.
			wolf.setItemSlot(EquipmentSlot.BODY, new ItemStack(Items.WOLF_ARMOR));
			wolf.setDropChance(EquipmentSlot.BODY, 1.0F);
			com.mobsminecraft.debug.AiStats.add("wolf_armor");
			return true;
		}
		return false;
	}
}
