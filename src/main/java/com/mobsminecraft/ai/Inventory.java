package com.mobsminecraft.ai;

import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;

/**
 * Every mob has a small inventory: its building-block stock, its consumables, and anything it picks up.
 * Mobs pick up items they walk over, and drop everything they carry when they die.
 */
public final class Inventory {
	private static final int MAX_LOOT_STACKS = 18;

	private Inventory() {}

	// ------------------------------------------------------------------ picking up

	/**
	 * Picks up nearby items. Gear (weapons, tools, armour) is equipped if it's an upgrade; everything
	 * else goes into the inventory, so mobs don't walk around holding coal or dirt.
	 */
	static void tickPickup(SmartBrain brain, ServerLevel level) {
		Mob mob = brain.mob;
		boolean boosted = brain.pickupBoostTicks > 0;
		if (mob.tickCount % (boosted ? 2 : 5) != 0) {
			return;
		}
		double reach = boosted ? 2.5 : 1.0;
		List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, mob.getBoundingBox().inflate(reach, 0.5, reach),
			e -> e.isAlive() && !e.hasPickUpDelay() && !e.getItem().isEmpty());
		for (ItemEntity entity : items) {
			ItemStack stack = entity.getItem();
			if (mob.canPickUpLoot() && mob.wantsToPickUp(level, stack)) {
				continue; // villagers and piglins handle their own pickups
			}
			// Piglins never pocket gold: that's for admiring and bartering.
			if (mob instanceof net.minecraft.world.entity.monster.piglin.AbstractPiglin && (stack.is(net.minecraft.tags.ItemTags.PIGLIN_LOVED) || Piglins.isGoldenArmor(stack))) {
				continue;
			}
			if (Wolves.isWildWolf(brain) && Wolves.equip(brain, stack)) {
				mob.take(entity, 1);
				stack.shrink(1);
				if (stack.isEmpty()) {
					entity.discard();
				}
				continue;
			}
			if (isGear(stack)) {
				ItemStack equipped = mob.equipItemIfPossible(level, stack.copy());
				if (!equipped.isEmpty()) {
					mob.take(entity, equipped.getCount());
					stack.shrink(equipped.getCount());
					if (stack.isEmpty()) {
						entity.discard();
					}
					continue;
				}
			}
			if (!store(brain, stack.copy())) {
				continue;
			}
			mob.take(entity, stack.getCount());
			entity.discard();
			level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.ITEM_PICKUP, SoundSource.HOSTILE, 0.3F, 1.4F);
		}
	}

	private static boolean isGear(ItemStack stack) {
		return stack.has(net.minecraft.core.component.DataComponents.TOOL)
			|| stack.has(net.minecraft.core.component.DataComponents.WEAPON)
			|| stack.has(net.minecraft.core.component.DataComponents.EQUIPPABLE)
			|| stack.is(Items.BOW) || stack.is(Items.CROSSBOW);
	}

	/** Puts an item into the mob's inventory. Returns false if there's no room. */
	static boolean store(SmartBrain brain, ItemStack stack) {
		if (stack.getItem() instanceof BlockItem block && BlockWork.isBuildingMaterial(block.getBlock().defaultBlockState())) {
			if (brain.blocks >= 128) {
				return false;
			}
			brain.blocks = Math.min(128, brain.blocks + stack.getCount());
			return true;
		}
		// Meat goes in the food pouch: hostile mobs eat it to heal, and smelt raw meat when they can.
		if (!brain.isPassive() && Recovery.isRawMeat(stack)) {
			brain.rawMeat += stack.getCount();
			return true;
		}
		if (!brain.isPassive() && Recovery.isCookedMeat(stack)) {
			brain.cookedMeat += stack.getCount();
			return true;
		}
		Consumable type = consumableFor(stack);
		if (type != null) {
			Holder<Potion> potion = potionOf(stack);
			Consumable.addCarried(brain, type, stack.getCount(), potion);
			return true;
		}
		for (ItemStack held : brain.loot) {
			if (ItemStack.isSameItemSameComponents(held, stack) && held.getCount() < held.getMaxStackSize()) {
				int moved = Math.min(stack.getCount(), held.getMaxStackSize() - held.getCount());
				held.grow(moved);
				stack.shrink(moved);
				if (stack.isEmpty()) {
					return true;
				}
			}
		}
		if (brain.loot.size() >= MAX_LOOT_STACKS) {
			return false;
		}
		brain.loot.add(stack);
		return true;
	}

	// ------------------------------------------------------------------ dropping

	/** Drops what the mob picked up (often the player's own items). Its gear drops at vanilla rates. */
	public static void dropAll(SmartBrain brain, ServerLevel level) {
		for (ItemStack stack : brain.loot) {
			brain.mob.spawnAtLocation(level, stack);
		}
		brain.loot.clear();
		// Uneaten golden apples and meat are real items too.
		for (Consumable.Carried c : brain.carried) {
			if (c.type.isApple() && c.uses > 0) {
				brain.mob.spawnAtLocation(level, new ItemStack(c.type == Consumable.GOLDEN_APPLE ? Items.GOLDEN_APPLE : Items.ENCHANTED_GOLDEN_APPLE, c.uses));
			}
		}
		brain.carried.removeIf(c -> c.type.isApple());
		if (brain.rawMeat > 0) {
			brain.mob.spawnAtLocation(level, new ItemStack(Recovery.RAW_MEAT[brain.meatType], Math.min(64, brain.rawMeat)));
		}
		if (brain.cookedMeat > 0) {
			brain.mob.spawnAtLocation(level, new ItemStack(Recovery.COOKED_MEAT[brain.meatType], Math.min(64, brain.cookedMeat)));
		}
		brain.rawMeat = 0;
		brain.cookedMeat = 0;
	}

	// ------------------------------------------------------------------ consumables <-> items

	static Consumable consumableFor(ItemStack stack) {
		if (stack.is(Items.SNOWBALL)) return Consumable.SNOWBALLS;
		if (stack.is(Items.EGG)) return Consumable.EGGS;
		if (stack.is(Items.WIND_CHARGE)) return Consumable.WIND_CHARGES;
		if (stack.is(Items.SPLASH_POTION)) return Consumable.SPLASH_POTION;
		if (stack.is(Items.LINGERING_POTION)) return Consumable.LINGERING_POTION;
		if (stack.is(Items.POTION) && potionOf(stack) != null && !potionOf(stack).equals(Potions.WATER)) return Consumable.DRINK_POTION;
		if (stack.is(Items.FIRE_CHARGE)) return Consumable.FIRE_CHARGE;
		if (stack.is(Items.ENDER_PEARL)) return Consumable.ENDER_PEARL;
		if (stack.is(Items.CHORUS_FRUIT)) return Consumable.CHORUS_FRUIT;
		if (stack.is(Items.WATER_BUCKET)) return Consumable.WATER_BUCKET;
		if (stack.is(Items.LAVA_BUCKET)) return Consumable.LAVA_BUCKET;
		if (stack.is(Items.POWDER_SNOW_BUCKET)) return Consumable.POWDER_SNOW_BUCKET;
		if (stack.is(Items.MILK_BUCKET)) return Consumable.MILK_BUCKET;
		if (stack.is(Items.FLINT_AND_STEEL)) return Consumable.FLINT_AND_STEEL;
		if (stack.is(Items.FIREWORK_ROCKET)) return Consumable.FIREWORK_ROCKET;
		if (stack.is(Items.TRIDENT)) return Consumable.TRIDENT;
		if (stack.is(Items.GOLDEN_APPLE)) return Consumable.GOLDEN_APPLE;
		if (stack.is(Items.ENCHANTED_GOLDEN_APPLE)) return Consumable.ENCHANTED_GOLDEN_APPLE;
		return null;
	}

	private static Holder<Potion> potionOf(ItemStack stack) {
		PotionContents contents = stack.get(net.minecraft.core.component.DataComponents.POTION_CONTENTS);
		return contents != null ? contents.potion().orElse(null) : null;
	}

	/** The real items a carried consumable turns back into. */
	private static List<ItemStack> toItems(Consumable.Carried c) {
		int n = Math.max(1, c.uses);
		return switch (c.type) {
			case SNOWBALLS -> List.of(new ItemStack(Items.SNOWBALL, Math.min(16, n)));
			case EGGS -> List.of(new ItemStack(Items.EGG, Math.min(16, n)));
			case WIND_CHARGES -> List.of(new ItemStack(Items.WIND_CHARGE, Math.min(64, n)));
			case SPLASH_POTION -> potions(Items.SPLASH_POTION, c, n);
			case LINGERING_POTION -> potions(Items.LINGERING_POTION, c, n);
			case DRINK_POTION -> potions(Items.POTION, c, n);
			case FIRE_CHARGE -> List.of(new ItemStack(Items.FIRE_CHARGE, Math.min(64, n)));
			case ENDER_PEARL -> List.of(new ItemStack(Items.ENDER_PEARL, Math.min(16, n)));
			case CHORUS_FRUIT -> List.of(new ItemStack(Items.CHORUS_FRUIT, Math.min(64, n)));
			case WATER_BUCKET -> List.of(new ItemStack(Items.WATER_BUCKET));
			case LAVA_BUCKET -> List.of(new ItemStack(Items.LAVA_BUCKET));
			case POWDER_SNOW_BUCKET -> List.of(new ItemStack(Items.POWDER_SNOW_BUCKET));
			case MILK_BUCKET -> List.of(new ItemStack(Items.MILK_BUCKET));
			case FLINT_AND_STEEL -> List.of(new ItemStack(Items.FLINT_AND_STEEL));
			case FIREWORK_ROCKET -> List.of(new ItemStack(Items.FIREWORK_ROCKET, Math.min(64, n)));
			case TRIDENT -> List.of(new ItemStack(Items.TRIDENT));
			case GOLDEN_APPLE -> List.of(new ItemStack(Items.GOLDEN_APPLE, Math.min(64, n)));
			case ENCHANTED_GOLDEN_APPLE -> List.of(new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, Math.min(64, n)));
		};
	}

	private static List<ItemStack> potions(Item item, Consumable.Carried c, int n) {
		Holder<Potion> potion = c.potion != null ? c.potion : Potions.WATER;
		ItemStack one = PotionContents.createItemStack(item, potion);
		return java.util.Collections.nCopies(Math.min(3, n), one).stream().map(ItemStack::copy).toList();
	}
}
