package com.mobsminecraft.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

/**
 * Gear mobs craft and enchant mid-fight: each crafting session upgrades their weakest piece one tier
 * (nothing, leather, chainmail, iron, diamond for armour; stone, iron, diamond for weapons).
 */
final class Gear {
	private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
	private static final Item[][] ARMOR_TIERS = {
		{},
		{Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS},
		{Items.CHAINMAIL_HELMET, Items.CHAINMAIL_CHESTPLATE, Items.CHAINMAIL_LEGGINGS, Items.CHAINMAIL_BOOTS},
		{Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS},
		{Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS},
	};
	private static final int MAX_ARMOR = 4;
	private static final int MAX_WEAPON = 3;

	private Gear() {}

	static boolean canWeapon(Mob mob) {
		ItemStack held = mob.getMainHandItem();
		return !(mob instanceof AbstractSkeleton && held.is(Items.BOW)) && !held.is(Items.CROSSBOW) && !held.is(Items.BOW)
			&& BlockWork.isToolHolder(mob);
	}

	static int armorTier(ItemStack stack) {
		if (stack.isEmpty()) return 0;
		String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
		if (id.startsWith("netherite")) return 5;
		if (id.startsWith("diamond")) return 4;
		if (id.startsWith("iron")) return 3;
		if (id.startsWith("chainmail") || id.startsWith("copper") || id.startsWith("turtle")) return 2;
		return 1;
	}

	static int weaponTier(ItemStack stack) {
		String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
		if (!id.endsWith("_sword") && !id.endsWith("_axe")) return 0;
		if (id.startsWith("netherite")) return 4;
		if (id.startsWith("diamond")) return 3;
		if (id.startsWith("iron")) return 2;
		return 1;
	}

	/** The armour tier worth crafting against this player: what they wear (their best piece), at least chainmail, at most diamond. */
	static int armorGoal(net.minecraft.world.entity.LivingEntity player) {
		int best = 0;
		for (EquipmentSlot slot : ARMOR) {
			best = Math.max(best, armorTier(player.getItemBySlot(slot)));
		}
		return Math.max(2, Math.min(MAX_ARMOR, best));
	}

	/** The weapon tier worth crafting against this player: what they're holding, at least stone, at most diamond. */
	static int weaponGoal(net.minecraft.world.entity.LivingEntity player) {
		return Math.max(1, Math.min(MAX_WEAPON, weaponTier(player.getMainHandItem())));
	}

	/** True if the mob's gear falls short of these tiers somewhere. */
	static boolean hasUpgradeTo(Mob mob, int armor, int weapon) {
		if (!Juggernaut.canWearArmor(mob)) {
			return false;
		}
		for (EquipmentSlot slot : ARMOR) {
			if (armorTier(mob.getItemBySlot(slot)) < armor) {
				return true;
			}
		}
		return canWeapon(mob) && weaponTier(mob.getMainHandItem()) < weapon;
	}

	/**
	 * Crafts straight up to the given tiers: the weapon first if it's behind, otherwise the weakest armour piece
	 * below the goal becomes that tier (iron for an iron-clad player, diamond for a diamond one). Null if nothing's behind.
	 */
	static ItemStack upgradeTo(SmartBrain brain, RandomSource r, int armor, int weapon) {
		Mob mob = brain.mob;
		if (!Juggernaut.canWearArmor(mob)) {
			return null;
		}
		int wt = weaponTier(mob.getMainHandItem());
		if (canWeapon(mob) && wt < weapon && (wt == 0 || r.nextFloat() < 0.35F)) {
			boolean axe = r.nextFloat() < 0.3F;
			Item item = switch (weapon) {
				case 1 -> axe ? Items.STONE_AXE : Items.STONE_SWORD;
				case 2 -> axe ? Items.IRON_AXE : Items.IRON_SWORD;
				default -> axe ? Items.DIAMOND_AXE : Items.DIAMOND_SWORD;
			};
			brain.restoreHand();
			ItemStack stack = new ItemStack(item);
			mob.setItemSlot(EquipmentSlot.MAINHAND, stack);
			mob.setDropChance(EquipmentSlot.MAINHAND, 0.1F);
			return stack;
		}
		EquipmentSlot pick = null;
		int lowest = Integer.MAX_VALUE;
		for (EquipmentSlot slot : ARMOR) {
			int t = armorTier(mob.getItemBySlot(slot));
			if (t < armor && (t < lowest || t == lowest && r.nextBoolean())) {
				lowest = t;
				pick = slot;
			}
		}
		if (pick == null) {
			if (canWeapon(mob) && wt < weapon) {
				return upgradeTo(brain, r, 0, weapon);
			}
			return null;
		}
		int index = java.util.Arrays.asList(ARMOR).indexOf(pick);
		ItemStack stack = new ItemStack(ARMOR_TIERS[armor][index]);
		mob.setItemSlot(pick, stack);
		mob.setDropChance(pick, 0.1F);
		return stack;
	}

	/** True if a crafting session would give the mob something better. */
	static boolean hasUpgrade(Mob mob) {
		if (!Juggernaut.canWearArmor(mob)) {
			return false;
		}
		for (EquipmentSlot slot : ARMOR) {
			if (armorTier(mob.getItemBySlot(slot)) < MAX_ARMOR) {
				return true;
			}
		}
		return canWeapon(mob) && weaponTier(mob.getMainHandItem()) < MAX_WEAPON;
	}

	/** True if some armour piece (or the weapon) is below the given tier and could be crafted up. */
	static boolean hasUpgradeBelow(Mob mob, int tier) {
		if (!Juggernaut.canWearArmor(mob)) {
			return false;
		}
		for (EquipmentSlot slot : ARMOR) {
			if (armorTier(mob.getItemBySlot(slot)) < tier) {
				return true;
			}
		}
		return canWeapon(mob) && weaponTier(mob.getMainHandItem()) < Math.min(tier - 1, MAX_WEAPON);
	}

	/** Upgrades the weakest piece by one tier. Returns the new item, or null. */
	static ItemStack upgrade(SmartBrain brain, RandomSource r) {
		Mob mob = brain.mob;
		List<EquipmentSlot> weakest = new ArrayList<>();
		int lowest = Integer.MAX_VALUE;
		for (EquipmentSlot slot : ARMOR) {
			int t = armorTier(mob.getItemBySlot(slot));
			if (t >= MAX_ARMOR) continue;
			if (t < lowest) { lowest = t; weakest.clear(); }
			if (t == lowest) weakest.add(slot);
		}
		boolean weapon = canWeapon(mob) && weaponTier(mob.getMainHandItem()) < MAX_WEAPON;
		// A weapon comes first if the mob has none; after that, weapons and armour take turns.
		if (weapon && (weaponTier(mob.getMainHandItem()) == 0 || weakest.isEmpty() || weaponTier(mob.getMainHandItem()) <= lowest - 1 || r.nextFloat() < 0.3F)) {
			int next = weaponTier(mob.getMainHandItem()) + 1;
			boolean axe = r.nextFloat() < 0.3F;
			Item item = switch (next) {
				case 1 -> axe ? Items.STONE_AXE : Items.STONE_SWORD;
				case 2 -> axe ? Items.IRON_AXE : Items.IRON_SWORD;
				default -> axe ? Items.DIAMOND_AXE : Items.DIAMOND_SWORD;
			};
			brain.restoreHand();
			ItemStack stack = new ItemStack(item);
			mob.setItemSlot(EquipmentSlot.MAINHAND, stack);
			mob.setDropChance(EquipmentSlot.MAINHAND, 0.1F);
			return stack;
		}
		if (weakest.isEmpty()) {
			return null;
		}
		EquipmentSlot slot = weakest.get(r.nextInt(weakest.size()));
		int index = java.util.Arrays.asList(ARMOR).indexOf(slot);
		int next = Math.min(MAX_ARMOR, armorTier(mob.getItemBySlot(slot)) + 1);
		ItemStack stack = new ItemStack(ARMOR_TIERS[next][index]);
		mob.setItemSlot(slot, stack);
		mob.setDropChance(slot, 0.1F);
		return stack;
	}

	/** True if the mob wears or holds anything an enchanting table could improve. */
	static boolean hasEnchantable(Mob mob) {
		for (EquipmentSlot slot : EquipmentSlot.values()) {
			ItemStack s = mob.getItemBySlot(slot);
			if (!s.isEmpty() && !s.isEnchanted() && s.isEnchantable()) {
				return true;
			}
		}
		return false;
	}

	/** Enchants every unenchanted piece of gear the mob has. Returns how many were enchanted. */
	static int enchantAll(ServerLevel level, Mob mob, RandomSource r, int minCost, int maxCost) {
		int n = 0;
		for (EquipmentSlot slot : EquipmentSlot.values()) {
			if (slot == EquipmentSlot.BODY || slot == EquipmentSlot.SADDLE) continue;
			ItemStack s = mob.getItemBySlot(slot);
			if (s.isEmpty() || s.isEnchanted() || !s.isEnchantable()) continue;
			ItemStack copy = EnchantmentHelper.enchantItem(r, s.copy(), minCost + r.nextInt(maxCost - minCost + 1), level.registryAccess(), Optional.empty());
			if (copy.isEnchanted()) {
				float drop = mob.getDropChances().byEquipment(slot);
				mob.setItemSlot(slot, copy);
				mob.setDropChance(slot, drop);
				n++;
			}
		}
		if (n > 0) {
			mob.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT);
		}
		return n;
	}
}
