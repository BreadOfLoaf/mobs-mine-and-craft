package com.mobsminecraft.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.phys.Vec3;

/** Furnaces mobs place always get something to cook plus fuel; passive mobs cook meat in them. */
final class Furnaces {
	private static final Item[] MEAT = {Items.BEEF, Items.PORKCHOP, Items.CHICKEN, Items.MUTTON, Items.RABBIT, Items.COD, Items.SALMON};
	private static final Item[] OTHER = {Items.RAW_IRON, Items.RAW_COPPER, Items.RAW_GOLD, Items.POTATO, Items.KELP, Items.SAND, Items.COBBLESTONE};
	private static final int SLOT_INPUT = 0;
	private static final int SLOT_FUEL = 1;
	private static final int SLOT_RESULT = 2;

	private Furnaces() {}

	/** Loads a freshly placed furnace with something cookable and fuel, so it lights up and works. */
	static void load(ServerLevel level, BlockPos pos, RandomSource r) {
		Item input = r.nextFloat() < 0.6F ? MEAT[r.nextInt(MEAT.length)] : OTHER[r.nextInt(OTHER.length)];
		load(level, pos, new ItemStack(input, 1 + r.nextInt(4)), r);
	}

	static void load(ServerLevel level, BlockPos pos, ItemStack input, RandomSource r) {
		if (!(level.getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity furnace)) {
			return;
		}
		if (furnace.getItem(SLOT_INPUT).isEmpty()) {
			furnace.setItem(SLOT_INPUT, input);
		}
		if (furnace.getItem(SLOT_FUEL).isEmpty()) {
			furnace.setItem(SLOT_FUEL, r.nextBoolean() ? new ItemStack(Items.COAL, 1 + r.nextInt(3)) : new ItemStack(Items.OAK_PLANKS, 2 + r.nextInt(4)));
		}
	}

	/** One possible stack in a chest: the item, how many (min-max), and how likely it is. */
	private record Loot(Item item, int min, int max, float weight) {}

	private static Loot loot(Item item, int min, int max, float weight) {
		return new Loot(item, min, max, weight);
	}

	/** Mining outposts and mineshafts: what a miner keeps around. */
	private static final Loot[] SUPPLY = {
		loot(Items.COAL, 3, 14, 10), loot(Items.TORCH, 4, 16, 9), loot(Items.COBBLESTONE, 8, 32, 8), loot(Items.STICK, 2, 9, 7),
		loot(Items.RAW_IRON, 1, 6, 6), loot(Items.RAW_COPPER, 2, 9, 5), loot(Items.OAK_PLANKS, 4, 16, 6), loot(Items.BREAD, 1, 4, 5),
		loot(Items.FLINT, 1, 4, 4), loot(Items.RAIL, 2, 8, 4), loot(Items.IRON_NUGGET, 2, 8, 3), loot(Items.STONE_PICKAXE, 1, 1, 3),
		loot(Items.WOODEN_PICKAXE, 1, 1, 2), loot(Items.LADDER, 2, 8, 3), loot(Items.GRAVEL, 4, 12, 3), loot(Items.IRON_INGOT, 1, 3, 2),
		loot(Items.REDSTONE, 2, 8, 2), loot(Items.LAPIS_LAZULI, 1, 5, 1.5F), loot(Items.RAW_GOLD, 1, 3, 1), loot(Items.DIAMOND, 1, 1, 0.3F),
	};

	/** Houses: food, farming, crafting odds and ends, the odd bit of treasure. */
	private static final Loot[] HOUSEHOLD = {
		loot(Items.STICK, 2, 10, 9), loot(Items.OAK_PLANKS, 3, 12, 7), loot(Items.OAK_LOG, 1, 6, 6), loot(Items.WHEAT_SEEDS, 2, 12, 8),
		loot(Items.WHEAT, 2, 9, 6), loot(Items.BREAD, 1, 4, 6), loot(Items.APPLE, 1, 4, 6), loot(Items.CARROT, 1, 6, 4), loot(Items.POTATO, 1, 6, 4),
		loot(Items.COAL, 1, 7, 6), loot(Items.TORCH, 2, 8, 5), loot(Items.STRING, 1, 5, 4), loot(Items.FEATHER, 1, 5, 3), loot(Items.LEATHER, 1, 4, 3),
		loot(Items.BONE, 1, 4, 3), loot(Items.FLINT, 1, 3, 3), loot(Items.COBBLESTONE, 4, 16, 4), loot(Items.WOODEN_AXE, 1, 1, 2),
		loot(Items.WOODEN_HOE, 1, 1, 2), loot(Items.STONE_SHOVEL, 1, 1, 2), loot(Items.BOWL, 1, 3, 2), loot(Items.PAPER, 1, 4, 2),
		loot(Items.BOOK, 1, 2, 1.5F), loot(Items.COOKED_BEEF, 1, 3, 2), loot(Items.COOKED_PORKCHOP, 1, 3, 2), loot(Items.EGG, 1, 4, 2),
		loot(Items.SUGAR_CANE, 1, 5, 2), loot(Items.IRON_NUGGET, 1, 6, 1.5F), loot(Items.IRON_INGOT, 1, 2, 1),
		loot(Items.BUCKET, 1, 1, 1), loot(Items.EMERALD, 1, 2, 0.5F),
	};

	private static Loot pick(Loot[] table, RandomSource r) {
		float total = 0;
		for (Loot l : table) total += l.weight();
		float roll = r.nextFloat() * total;
		for (Loot l : table) {
			roll -= l.weight();
			if (roll <= 0) return l;
		}
		return table[0];
	}

	/** Puts a few stacks from the table into random slots, with worn-down tools. */
	private static void fill(Container chest, Loot[] table, int stacks, RandomSource r) {
		for (int i = 0; i < stacks; i++) {
			Loot l = pick(table, r);
			ItemStack stack = new ItemStack(l.item(), l.min() + r.nextInt(l.max() - l.min() + 1));
			if (stack.isDamageableItem()) {
				stack.setDamageValue(r.nextInt(Math.max(1, stack.getMaxDamage() * 3 / 4)));
			}
			int slot = r.nextInt(chest.getContainerSize());
			for (int tries = 0; tries < 10 && !chest.getItem(slot).isEmpty(); tries++) {
				slot = r.nextInt(chest.getContainerSize());
			}
			if (chest.getItem(slot).isEmpty()) {
				chest.setItem(slot, stack);
			}
		}
	}

	/** Fills a chest with mining supplies (outpost and mineshaft chests). */
	static void fillSupplyChest(ServerLevel level, BlockPos pos, RandomSource r) {
		if (level.getBlockEntity(pos) instanceof Container chest) {
			fill(chest, SUPPLY, 3 + r.nextInt(5), r);
		}
	}

	/** Fills a house chest or barrel with a mix of household things. */
	static void fillHouseChest(ServerLevel level, BlockPos pos, RandomSource r) {
		if (level.getBlockEntity(pos) instanceof Container chest) {
			fill(chest, HOUSEHOLD, 3 + r.nextInt(6), r);
		}
	}

	/**
	 * Passive mobs (and villagers) now and then place or find a furnace, put raw meat in it,
	 * wait for it to cook, then take the cooked food (healing a little if hurt). Returns true while busy.
	 */
	static boolean tickCooking(SmartBrain brain, ServerLevel level, boolean griefing) {
		Mob mob = brain.mob;
		boolean hostile = !brain.isPassive();
		if (brain.cookPos == null) {
			if (brain.cookCooldown > 0 || !griefing || !mob.onGround() || (hostile && brain.rawMeat <= 0)) {
				return false;
			}
			RandomSource r = mob.getRandom();
			brain.cookCooldown = hostile ? 1500 + r.nextInt(1500) : 1200 + r.nextInt(1800);
			// Monsters only cook in peace and quiet, with no player around to interrupt.
			if (hostile && level.getNearestPlayer(mob, 24.0) != null) {
				return false;
			}
			BlockPos furnace = BlockWork.findNearest(level, mob.blockPosition(), 12, s -> s.getBlock() instanceof AbstractFurnaceBlock);
			if (furnace == null) {
				furnace = brain.placeAdjacent(level, Blocks.FURNACE.defaultBlockState()
					.setValue(AbstractFurnaceBlock.FACING, mob.getDirection().getOpposite()));
			}
			if (furnace == null) {
				return false;
			}
			if (hostile) {
				// Hostile mobs smelt the raw meat they carry, to heal with later.
				if (level.getBlockEntity(furnace) instanceof AbstractFurnaceBlockEntity fb
					&& (fb.getItem(SLOT_INPUT).isEmpty() || com.mobsminecraft.world.MobBuilt.contains(level, furnace))) {
					fb.setItem(SLOT_INPUT, new ItemStack(Recovery.RAW_MEAT[brain.meatType], brain.rawMeat));
					brain.rawMeat = 0;
				}
				load(level, furnace, ItemStack.EMPTY, r);
				com.mobsminecraft.debug.AiStats.add("smelt_meat_start");
			} else {
				load(level, furnace, new ItemStack(MEAT[r.nextInt(MEAT.length)]), r);
			}
			brain.cookPos = furnace;
			brain.cookTicks = 0;
			return true;
		}

		if (!(level.getBlockEntity(brain.cookPos) instanceof AbstractFurnaceBlockEntity furnace)) {
			brain.cookPos = null;
			return false;
		}
		Vec3 c = Vec3.atCenterOf(brain.cookPos);
		if (mob.distanceToSqr(c) > 9.0) {
			if (mob.tickCount % 10 == 0) {
				mob.getNavigation().moveTo(c.x, c.y, c.z, brain.walkSpeed());
			}
		} else {
			brain.holdStill();
			mob.getLookControl().setLookAt(c);
			if (mob.tickCount % 20 == 0) {
				level.sendParticles(ParticleTypes.SMOKE, c.x, c.y + 0.7, c.z, 3, 0.2, 0.1, 0.2, 0.01);
			}
		}
		ItemStack result = furnace.getItem(SLOT_RESULT);
		if (!result.isEmpty()) {
			furnace.setItem(SLOT_RESULT, ItemStack.EMPTY);
			mob.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
			level.playSound(null, mob.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.NEUTRAL, 0.6F, 1.0F);
			if (hostile && !Recovery.isCookedMeat(result)) {
				Inventory.store(brain, result);
				return true;
			}
			if (hostile) {
				brain.cookedMeat += result.getCount();
				// Take back whatever is still raw.
				ItemStack rest = furnace.getItem(SLOT_INPUT);
				if (Recovery.isRawMeat(rest)) {
					brain.rawMeat += rest.getCount();
					furnace.setItem(SLOT_INPUT, ItemStack.EMPTY);
				}
				com.mobsminecraft.debug.AiStats.add("smelt_meat_done");
				brain.cookPos = null;
				return false;
			}
			if (mob.getHealth() < mob.getMaxHealth()) {
				Eating.start(brain, level, result, Eating.EAT_TICKS, () -> mob.heal(4.0F));
				result.shrink(1);
			}
			if (!result.isEmpty()) {
				Inventory.store(brain, result);
			}
			com.mobsminecraft.debug.AiStats.add("cooked_food");
			brain.cookPos = null;
			return false;
		}
		if (++brain.cookTicks > 400) {
			brain.cookPos = null;
			return false;
		}
		return true;
	}
}
