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

	/** Fills a chest with some mining loot (outpost supply chests). */
	static void fillSupplyChest(ServerLevel level, BlockPos pos, RandomSource r) {
		if (!(level.getBlockEntity(pos) instanceof Container chest)) {
			return;
		}
		HopperBlockEntity.addItem(null, chest, new ItemStack(Items.COAL, 4 + r.nextInt(9)), null);
		HopperBlockEntity.addItem(null, chest, new ItemStack(Items.TORCH, 8 + r.nextInt(9)), null);
		HopperBlockEntity.addItem(null, chest, new ItemStack(Items.RAW_IRON, 1 + r.nextInt(5)), null);
		HopperBlockEntity.addItem(null, chest, new ItemStack(Items.BREAD, 1 + r.nextInt(3)), null);
		if (r.nextFloat() < 0.15F) {
			HopperBlockEntity.addItem(null, chest, new ItemStack(Items.DIAMOND, 1), null);
		}
	}

	private static final Item[] HOUSEHOLD = {
		Items.BREAD, Items.WHEAT, Items.CARROT, Items.POTATO, Items.APPLE, Items.WHEAT_SEEDS, Items.COAL, Items.TORCH,
		Items.STRING, Items.BONE, Items.LEATHER, Items.BOOK, Items.PAPER, Items.STICK, Items.FEATHER, Items.COOKED_BEEF,
		Items.IRON_INGOT, Items.FLINT, Items.SUGAR_CANE, Items.EGG,
	};

	/** Fills a house chest or barrel with a few random household items. */
	static void fillHouseChest(ServerLevel level, BlockPos pos, RandomSource r) {
		if (!(level.getBlockEntity(pos) instanceof Container chest)) {
			return;
		}
		int stacks = 2 + r.nextInt(4);
		for (int i = 0; i < stacks; i++) {
			Item item = HOUSEHOLD[r.nextInt(HOUSEHOLD.length)];
			HopperBlockEntity.addItem(null, chest, new ItemStack(item, 1 + r.nextInt(Math.min(8, item.getDefaultMaxStackSize()))), null);
		}
		if (r.nextFloat() < 0.08F) {
			HopperBlockEntity.addItem(null, chest, new ItemStack(Items.EMERALD, 1 + r.nextInt(3)), null);
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
			brain.cookCooldown = hostile ? 300 + r.nextInt(600) : 1200 + r.nextInt(1800);
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
				mob.heal(4.0F);
				level.playSound(null, mob.blockPosition(), SoundEvents.GENERIC_EAT.value(), SoundSource.NEUTRAL, 0.8F, 1.0F);
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
