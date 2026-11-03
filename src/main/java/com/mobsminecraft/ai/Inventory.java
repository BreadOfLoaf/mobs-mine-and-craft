package com.mobsminecraft.ai;

import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.ItemEntity;
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

	/** Picks up nearby items. Equipment the mob wants is left to vanilla, which equips it. */
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
				continue;
			}
			if (!store(brain, stack.copy())) {
				continue;
			}
			mob.take(entity, stack.getCount());
			entity.discard();
			level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.ITEM_PICKUP, SoundSource.HOSTILE, 0.3F, 1.4F);
		}
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

	/** Drops the whole inventory: loot, consumables, and building blocks. Equipment drops through vanilla. */
	public static void dropAll(SmartBrain brain, ServerLevel level) {
		Mob mob = brain.mob;
		for (ItemStack stack : brain.loot) {
			mob.spawnAtLocation(level, stack);
		}
		brain.loot.clear();
		for (Consumable.Carried c : brain.carried) {
			for (ItemStack stack : toItems(c)) {
				mob.spawnAtLocation(level, stack);
			}
		}
		brain.carried.clear();
		Item blockItem = brain.primaryBlock().asItem();
		int remaining = brain.blocks;
		while (remaining > 0) {
			int n = Math.min(64, remaining);
			mob.spawnAtLocation(level, new ItemStack(blockItem, n));
			remaining -= n;
		}
		brain.blocks = 0;
	}

	// ------------------------------------------------------------------ consumables <-> items

	private static Consumable consumableFor(ItemStack stack) {
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
