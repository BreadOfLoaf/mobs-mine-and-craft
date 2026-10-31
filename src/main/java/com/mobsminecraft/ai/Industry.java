package com.mobsminecraft.ai;

import com.mobsminecraft.SmartMobHolder;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Mining-and-crafting economy: mobs collect ores, smelt them, craft better tools and armour,
 * repair worn tools at anvils, brew potions, stash loot in chests, duel over crafting tables,
 * and run little workshop camps.
 */
final class Industry {
	private Industry() {}

	/** Work done at a station block. */
	enum Job {
		SMELT(80), COOK(60), UPGRADE(50), ARMOR(50), REPAIR(40), BREW(80), STASH(20),
		LADDERS(30), STAIRS(30), BOAT(30);

		final int ticks;

		Job(int ticks) {
			this.ticks = ticks;
		}

		/** Jobs that help reach the player; they are not cancelled when a fight starts. */
		boolean isCombatCraft() {
			return this == LADDERS || this == STAIRS || this == BOAT;
		}
	}

	// ------------------------------------------------------------------ tools and tiers

	private static final Item[][] TOOLS = {
		{Items.WOODEN_SHOVEL, Items.STONE_SHOVEL, Items.IRON_SHOVEL, Items.DIAMOND_SHOVEL},
		{Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE},
		{Items.WOODEN_AXE, Items.STONE_AXE, Items.IRON_AXE, Items.DIAMOND_AXE},
	};
	static final int SHOVEL = 0;
	static final int PICKAXE = 1;
	static final int AXE = 2;

	/** 0 shovel, 1 pickaxe, 2 axe, or -1 if not one of those tools. */
	static int toolKind(ItemStack stack) {
		for (int kind = 0; kind < TOOLS.length; kind++) {
			for (Item item : TOOLS[kind]) {
				if (stack.is(item)) {
					return kind;
				}
			}
		}
		if (stack.is(Items.GOLDEN_SHOVEL) || stack.is(Items.COPPER_SHOVEL) || stack.is(Items.NETHERITE_SHOVEL)) return SHOVEL;
		if (stack.is(Items.GOLDEN_PICKAXE) || stack.is(Items.COPPER_PICKAXE) || stack.is(Items.NETHERITE_PICKAXE)) return PICKAXE;
		if (stack.is(Items.GOLDEN_AXE) || stack.is(Items.COPPER_AXE) || stack.is(Items.NETHERITE_AXE)) return AXE;
		return -1;
	}

	/** 0 wood/gold, 1 stone/copper, 2 iron, 3 diamond, 4 netherite, or -1 if not a tool. */
	static int toolTier(ItemStack stack) {
		for (Item[] kind : TOOLS) {
			for (int tier = 0; tier < kind.length; tier++) {
				if (stack.is(kind[tier])) {
					return tier;
				}
			}
		}
		if (stack.is(Items.GOLDEN_SHOVEL) || stack.is(Items.GOLDEN_PICKAXE) || stack.is(Items.GOLDEN_AXE)) return 0;
		if (stack.is(Items.COPPER_SHOVEL) || stack.is(Items.COPPER_PICKAXE) || stack.is(Items.COPPER_AXE)) return 1;
		if (stack.is(Items.NETHERITE_SHOVEL) || stack.is(Items.NETHERITE_PICKAXE) || stack.is(Items.NETHERITE_AXE)) return 4;
		return -1;
	}

	static ItemStack makeTool(int kind, int tier) {
		return new ItemStack(TOOLS[kind][Math.max(0, Math.min(3, tier))]);
	}

	static SmartBrain brainOf(Mob mob) {
		return ((SmartMobHolder) mob).mmc$getBrain();
	}

	// ------------------------------------------------------------------ materials

	/** Mobs that carry what they mine instead of leaving it on the ground (most of the time). */
	static boolean collectsOres(SmartBrain brain) {
		return BlockWork.isToolHolder(brain.mob) && brain.mob.getRandom().nextFloat() < 0.7F;
	}

	/** Breaks an ore and keeps the drops: iron, diamonds and coal feed progression; the rest is loot. */
	static void collectOre(SmartBrain brain, ServerLevel level, BlockPos pos, BlockState state) {
		List<ItemStack> drops = Block.getDrops(state, level, pos, null, brain.mob, brain.mob.getMainHandItem());
		level.destroyBlock(pos, false, brain.mob);
		for (ItemStack drop : drops) {
			if (drop.is(Items.RAW_IRON)) {
				brain.rawIron += drop.getCount();
			} else if (drop.is(Items.DIAMOND)) {
				brain.diamonds += drop.getCount();
			} else if (drop.is(Items.COAL)) {
				brain.coal += drop.getCount();
			} else {
				brain.loot.add(drop);
			}
		}
		level.playSound(null, pos, SoundEvents.ITEM_PICKUP, SoundSource.HOSTILE, 0.6F, 1.2F);
	}

	/** Tools wear out as mobs mine; they get repaired at an anvil before they break. */
	static void wearTool(SmartBrain brain) {
		ItemStack hand = brain.mob.getMainHandItem();
		if (hand.isDamageableItem()) {
			hand.hurtAndBreak(1, brain.mob, EquipmentSlot.MAINHAND);
		}
	}

	// ------------------------------------------------------------------ choosing a job

	private static final EquipmentSlot[] ARMOR_ORDER = {EquipmentSlot.HEAD, EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST};
	private static final int[] ARMOR_COST = {5, 4, 7, 8};

	static Job pickIdleJob(SmartBrain brain) {
		Mob mob = brain.mob;
		ItemStack hand = mob.getMainHandItem();
		if (hand.isDamageableItem() && hand.getDamageValue() > hand.getMaxDamage() * 0.7F) {
			return Job.REPAIR;
		}
		if (mob.getHealth() / mob.getMaxHealth() < 0.6F && brain.cookCooldown == 0 && !(mob instanceof Animal)) {
			return Job.COOK;
		}
		if (brain.rawIron >= 3) {
			return Job.SMELT;
		}
		if (brain.toolCrafter && canUpgrade(brain)) {
			return Job.UPGRADE;
		}
		if (Juggernaut.canWearArmor(mob) && nextArmorSlot(brain) != null) {
			return Job.ARMOR;
		}
		if (brain.loot.size() >= 3 || brain.coal >= 8) {
			return Job.STASH;
		}
		if (brain.brewer && brain.brewCooldown == 0) {
			return Job.BREW;
		}
		return null;
	}

	/** Wood needs 3 stone mined, stone needs 3 iron ingots, iron needs 3 diamonds. */
	private static boolean canUpgrade(SmartBrain brain) {
		ItemStack hand = brain.mob.getMainHandItem();
		if (toolKind(hand) < 0) {
			return false;
		}
		return switch (toolTier(hand)) {
			case 0 -> brain.stoneMined >= 3;
			case 1 -> brain.ironIngots >= 3;
			case 2 -> brain.diamonds >= 3;
			default -> false;
		};
	}

	/** The next armour slot this mob can afford to fill: helmet, boots, leggings, then chestplate. */
	private static EquipmentSlot nextArmorSlot(SmartBrain brain) {
		for (int i = 0; i < ARMOR_ORDER.length; i++) {
			if (brain.mob.getItemBySlot(ARMOR_ORDER[i]).isEmpty()
				&& (brain.ironIngots >= ARMOR_COST[i] || (brain.diamondTool && brain.diamonds >= ARMOR_COST[i]))) {
				return ARMOR_ORDER[i];
			}
		}
		return null;
	}

	static void startJob(SmartBrain brain, Job job) {
		brain.job = job;
		brain.jobStation = null;
		brain.jobTicks = 0;
		brain.jobWalk = 0;
		brain.jobStarted = false;
	}

	private static void endJob(SmartBrain brain) {
		brain.job = null;
		brain.jobStation = null;
		brain.jobTicks = 0;
		brain.jobWalk = 0;
		brain.jobStarted = false;
	}

	// ------------------------------------------------------------------ doing the job

	/** Runs the current job: find or place the station, walk to it, work, finish. Returns true while busy. */
	static boolean tickJob(SmartBrain brain, ServerLevel level, boolean griefing, LivingEntity target) {
		if (brain.job == null) {
			return false;
		}
		Mob mob = brain.mob;
		if (target != null && mob.distanceTo(target) < 12.0F && !brain.job.isCombatCraft()) {
			endJob(brain);
			return false;
		}
		if (!brain.jobStarted) {
			brain.jobStarted = true;
			brain.jobStation = findStation(level, mob.blockPosition(), brain.job);
			if (brain.jobStation == null && griefing) {
				brain.jobStation = brain.placeAdjacent(level, stationState(mob, brain.job));
			}
			if (brain.jobStation == null && (brain.job == Job.STASH || brain.job == Job.BREW)) {
				endJob(brain);
				return false;
			}
			onStart(brain, level);
		}

		Vec3 focus = brain.jobStation != null ? Vec3.atCenterOf(brain.jobStation) : mob.getEyePosition();
		if (brain.jobStation != null && mob.distanceToSqr(focus) > 9.0) {
			if (mob.tickCount % 10 == 0) {
				mob.getNavigation().moveTo(focus.x, focus.y, focus.z, 1.0);
			}
			if (++brain.jobWalk > 200) {
				endJob(brain);
			}
			return true;
		}
		brain.holdStill();
		mob.getLookControl().setLookAt(focus);
		brain.jobTicks++;
		if (brain.jobTicks % 8 == 0) {
			workEffects(brain, level, focus);
		}
		if (brain.jobTicks >= brain.job.ticks) {
			finish(brain, level);
			endJob(brain);
		}
		return true;
	}

	private static BlockPos findStation(ServerLevel level, BlockPos near, Job job) {
		return switch (job) {
			case SMELT, COOK -> BlockWork.findNearest(level, near, 6, s -> s.getBlock() instanceof AbstractFurnaceBlock);
			case REPAIR -> BlockWork.findNearest(level, near, 6, s -> s.is(BlockTags.ANVIL));
			case BREW -> BlockWork.findNearest(level, near, 6, s -> s.is(Blocks.BREWING_STAND));
			case STASH -> BlockWork.findNearest(level, near, 8, s -> s.is(Blocks.CHEST));
			default -> BlockWork.findNearest(level, near, 6, s -> s.is(Blocks.CRAFTING_TABLE));
		};
	}

	private static BlockState stationState(Mob mob, Job job) {
		Direction facing = mob.getDirection().getOpposite();
		return switch (job) {
			case SMELT, COOK -> Blocks.FURNACE.defaultBlockState().setValue(AbstractFurnaceBlock.FACING, facing);
			case REPAIR -> Blocks.ANVIL.defaultBlockState().setValue(AnvilBlock.FACING, facing.getClockWise());
			case BREW -> Blocks.BREWING_STAND.defaultBlockState();
			case STASH -> Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing);
			default -> Blocks.CRAFTING_TABLE.defaultBlockState();
		};
	}

	/** Loads real items into furnaces and brewing stands so the player can see (and loot) the work. */
	private static void onStart(SmartBrain brain, ServerLevel level) {
		if (brain.jobStation == null) {
			return;
		}
		if ((brain.job == Job.SMELT || brain.job == Job.COOK) && level.getBlockEntity(brain.jobStation) instanceof AbstractFurnaceBlockEntity furnace) {
			if (furnace.getItem(0).isEmpty()) {
				furnace.setItem(0, brain.job == Job.SMELT ? new ItemStack(Items.RAW_IRON, Math.min(64, brain.rawIron)) : new ItemStack(Items.BEEF, 1));
			}
			if (furnace.getItem(1).isEmpty()) {
				boolean useCoal = brain.coal > 0;
				furnace.setItem(1, useCoal ? new ItemStack(Items.COAL, 1) : new ItemStack(Items.OAK_PLANKS, 2));
				if (useCoal) {
					brain.coal--;
				}
			}
		} else if (brain.job == Job.BREW && level.getBlockEntity(brain.jobStation) instanceof BrewingStandBlockEntity stand) {
			for (int slot = 0; slot < 3; slot++) {
				if (stand.getItem(slot).isEmpty()) {
					stand.setItem(slot, PotionContents.createItemStack(Items.POTION, Potions.WATER));
				}
			}
			if (stand.getItem(4).isEmpty()) {
				stand.setItem(4, new ItemStack(Items.BLAZE_POWDER));
			}
		}
	}

	private static void workEffects(SmartBrain brain, ServerLevel level, Vec3 at) {
		Mob mob = brain.mob;
		mob.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
		SoundEvent sound = switch (brain.job) {
			case SMELT, COOK -> SoundEvents.FURNACE_FIRE_CRACKLE;
			case REPAIR -> SoundEvents.ANVIL_USE;
			case BREW -> SoundEvents.BREWING_STAND_BREW;
			case STASH -> SoundEvents.CHEST_OPEN;
			default -> SoundEvents.WOOD_HIT;
		};
		level.playSound(null, at.x, at.y, at.z, sound, SoundSource.HOSTILE, 0.6F, 1.0F);
		switch (brain.job) {
			case SMELT, COOK -> level.sendParticles(ParticleTypes.SMOKE, at.x, at.y + 0.7, at.z, 4, 0.2, 0.1, 0.2, 0.01);
			case REPAIR -> level.sendParticles(ParticleTypes.CRIT, at.x, at.y + 0.6, at.z, 8, 0.3, 0.1, 0.3, 0.2);
			case BREW -> level.sendParticles(ParticleTypes.WITCH, at.x, at.y + 0.8, at.z, 6, 0.2, 0.2, 0.2, 0.05);
			default -> {
			}
		}
	}

	private static void finish(SmartBrain brain, ServerLevel level) {
		Mob mob = brain.mob;
		RandomSource r = mob.getRandom();
		switch (brain.job) {
			case SMELT -> {
				brain.ironIngots += brain.rawIron;
				brain.rawIron = 0;
				level.playSound(null, mob.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.HOSTILE, 0.8F, 1.0F);
			}
			case COOK -> {
				mob.heal(8.0F);
				brain.cookCooldown = 600;
				level.playSound(null, mob.blockPosition(), SoundEvents.GENERIC_EAT.value(), SoundSource.HOSTILE, 1.0F, 1.0F);
			}
			case UPGRADE -> {
				ItemStack hand = mob.getMainHandItem();
				int kind = toolKind(hand);
				int tier = toolTier(hand);
				if (kind < 0 || !canUpgrade(brain)) {
					return;
				}
				switch (tier) {
					case 0 -> brain.stoneMined -= 3;
					case 1 -> brain.ironIngots -= 3;
					default -> brain.diamonds -= 3;
				}
				int newTier = tier + 1 + (craftingDuel(brain, level) ? 1 : 0);
				equipTool(brain, level, makeTool(kind, newTier));
			}
			case ARMOR -> {
				EquipmentSlot slot = nextArmorSlot(brain);
				if (slot == null) {
					return;
				}
				int cost = ARMOR_COST[java.util.Arrays.asList(ARMOR_ORDER).indexOf(slot)];
				boolean diamond = brain.ironIngots < cost;
				if (diamond) {
					brain.diamonds -= cost;
				} else {
					brain.ironIngots -= cost;
				}
				mob.setItemSlot(slot, new ItemStack(armorItem(slot, diamond)));
				mob.setDropChance(slot, 0.1F);
				level.playSound(null, mob.blockPosition(), SoundEvents.ARMOR_EQUIP_IRON.value(), SoundSource.HOSTILE, 1.0F, 1.0F);
			}
			case REPAIR -> {
				ItemStack repaired = mob.getMainHandItem().copy();
				repaired.setDamageValue(0);
				mob.setItemSlot(EquipmentSlot.MAINHAND, repaired);
				level.playSound(null, mob.blockPosition(), SoundEvents.ANVIL_USE, SoundSource.HOSTILE, 1.0F, 1.2F);
			}
			case BREW -> {
				Consumable.give(brain, Consumable.SPLASH_POTION, r);
				brain.brewCooldown = 2400 + r.nextInt(2400);
				if (brain.jobStation != null && level.getBlockEntity(brain.jobStation) instanceof BrewingStandBlockEntity stand) {
					for (int slot = 0; slot < 3; slot++) {
						stand.setItem(slot, ItemStack.EMPTY);
					}
				}
				level.playSound(null, mob.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.HOSTILE, 0.8F, 1.0F);
			}
			case STASH -> {
				if (brain.jobStation != null && level.getBlockEntity(brain.jobStation) instanceof Container chest) {
					for (ItemStack stack : brain.loot) {
						HopperBlockEntity.addItem(null, chest, stack, null);
					}
					brain.loot.clear();
					if (brain.coal > 4) {
						HopperBlockEntity.addItem(null, chest, new ItemStack(Items.COAL, brain.coal - 4), null);
						brain.coal = 4;
					}
					level.playSound(null, brain.jobStation, SoundEvents.CHEST_CLOSE, SoundSource.HOSTILE, 0.8F, 1.0F);
				}
			}
			case LADDERS -> brain.ladders += 16;
			case STAIRS -> brain.stairs += 12;
			case BOAT -> brain.boats += 1;
		}
	}

	private static Item armorItem(EquipmentSlot slot, boolean diamond) {
		return switch (slot) {
			case HEAD -> diamond ? Items.DIAMOND_HELMET : Items.IRON_HELMET;
			case FEET -> diamond ? Items.DIAMOND_BOOTS : Items.IRON_BOOTS;
			case LEGS -> diamond ? Items.DIAMOND_LEGGINGS : Items.IRON_LEGGINGS;
			default -> diamond ? Items.DIAMOND_CHESTPLATE : Items.IRON_CHESTPLATE;
		};
	}

	static void equipTool(SmartBrain brain, ServerLevel level, ItemStack tool) {
		brain.mob.setItemSlot(EquipmentSlot.MAINHAND, tool);
		brain.mob.setDropChance(EquipmentSlot.MAINHAND, 0.15F);
		if (toolTier(tool) >= 3) {
			brain.diamondTool = true;
		}
		level.playSound(null, brain.mob.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.HOSTILE, 0.8F, 1.0F);
	}

	// ------------------------------------------------------------------ crafting duels

	/**
	 * If another mob is crafting at the same table, the one who finishes first wins: the winner's tool is
	 * one tier better, the loser walks off with a wooden shovel and punches the winner. Returns true on a win.
	 */
	static boolean craftingDuel(SmartBrain winner, ServerLevel level) {
		Mob mob = winner.mob;
		List<Mob> rivals = level.getEntitiesOfClass(Mob.class, mob.getBoundingBox().inflate(4.0), m -> m != mob && m.isAlive());
		for (Mob rival : rivals) {
			SmartBrain other = brainOf(rival);
			boolean crafting = other.isCraftingTool() || other.job == Job.UPGRADE;
			if (!crafting) {
				continue;
			}
			other.stopCrafting();
			if (other.job == Job.UPGRADE) {
				endJob(other);
			}
			if (rival.getMainHandItem().isEmpty()) {
				rival.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.WOODEN_SHOVEL));
			}
			rival.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
			mob.hurtServer(level, rival.damageSources().mobAttack(rival), 1.0F);
			level.sendParticles(ParticleTypes.ANGRY_VILLAGER, rival.getX(), rival.getEyeY() + 0.4, rival.getZ(), 3, 0.3, 0.2, 0.3, 0.0);
			level.sendParticles(ParticleTypes.HAPPY_VILLAGER, mob.getX(), mob.getEyeY() + 0.4, mob.getZ(), 6, 0.3, 0.2, 0.3, 0.0);
			return true;
		}
		return false;
	}

	// ------------------------------------------------------------------ workshop camps

	private static final Block[] UTILITIES = {
		Blocks.FURNACE, Blocks.BARREL, Blocks.SMITHING_TABLE, Blocks.FLETCHING_TABLE, Blocks.STONECUTTER, Blocks.LOOM
	};

	/**
	 * A group of 3+ same-type mobs around a crafting table splits into roles: a crafter at the table,
	 * a lumberjack and a quarrier. Gatherers haul their blocks to a chest; the crafter stocks it with
	 * products and the camp grows a new utility block every so often. Returns true while busy.
	 */
	static boolean tickWorkshop(SmartBrain brain, ServerLevel level) {
		Mob mob = brain.mob;
		if (!(mob instanceof Enemy || mob instanceof Villager)) {
			return false;
		}
		if (--brain.workshopCheck <= 0) {
			brain.workshopCheck = 200;
			List<? extends Mob> group = level.getEntitiesOfClass(mob.getClass(), mob.getBoundingBox().inflate(10.0));
			brain.workshopTable = group.size() >= 3 ? BlockWork.findNearest(level, mob.blockPosition(), 8, s -> s.is(Blocks.CRAFTING_TABLE)) : null;
		}
		BlockPos table = brain.workshopTable;
		if (table == null || !level.getBlockState(table).is(Blocks.CRAFTING_TABLE)) {
			brain.workshopTable = null;
			return false;
		}
		BlockPos chest = BlockWork.findNearest(level, table, 3, s -> s.is(Blocks.CHEST));
		int role = Math.floorMod(mob.getId(), 3);

		if (role == 0) {
			if (walkTo(brain, table)) {
				return true;
			}
			brain.holdStill();
			mob.getLookControl().setLookAt(Vec3.atCenterOf(table));
			if (chest == null) {
				placeNear(brain, level, table, Blocks.CHEST.defaultBlockState());
				return true;
			}
			if (mob.tickCount % 10 == 0) {
				mob.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
				level.playSound(null, table, SoundEvents.WOOD_HIT, SoundSource.HOSTILE, 0.5F, 1.0F);
			}
			if (++brain.workshopTicks % 400 == 0) {
				deposit(level, chest, product(mob.getRandom()));
				growCamp(brain, level, table);
			}
			return true;
		}

		if (brain.blocks >= 40 && chest != null) {
			if (walkTo(brain, chest)) {
				return true;
			}
			deposit(level, chest, role == 1 ? new ItemStack(Items.OAK_LOG, 4) : new ItemStack(Items.COBBLESTONE, 8));
			brain.blocks -= 16;
			mob.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
			return true;
		}
		if (!brain.hasDigTarget() && mob.tickCount % 40 == 0) {
			BlockPos here = mob.blockPosition();
			BlockPos pick = role == 1
				? BlockWork.findNearestAt(level, here, 12, (p, s) -> s.is(BlockTags.LOGS) && BlockWork.isExposed(level, p) && BlockWork.canMine(mob, level, p, s))
				: BlockWork.findNearestAt(level, here, 6, (p, s) -> (s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.DIRT))
					&& BlockWork.isExposed(level, p) && !p.equals(here.below()) && BlockWork.canMine(mob, level, p, s));
			if (pick != null) {
				brain.setDigTarget(pick, false);
			}
		}
		return false;
	}

	private static boolean walkTo(SmartBrain brain, BlockPos pos) {
		Vec3 c = Vec3.atCenterOf(pos);
		if (brain.mob.distanceToSqr(c) <= 9.0) {
			return false;
		}
		if (brain.mob.tickCount % 10 == 0) {
			brain.mob.getNavigation().moveTo(c.x, c.y, c.z, 1.0);
		}
		return true;
	}

	private static void placeNear(SmartBrain brain, ServerLevel level, BlockPos center, BlockState state) {
		RandomSource r = brain.mob.getRandom();
		for (int i = 0; i < 10; i++) {
			BlockPos p = center.offset(r.nextInt(5) - 2, 0, r.nextInt(5) - 2);
			if (!p.equals(center) && BlockWork.isFloor(level, p.below()) && brain.place(level, p, state, false)) {
				return;
			}
		}
	}

	private static void growCamp(SmartBrain brain, ServerLevel level, BlockPos table) {
		int existing = 0;
		for (Block utility : UTILITIES) {
			if (BlockWork.findNearest(level, table, 4, s -> s.is(utility)) != null) {
				existing++;
			}
		}
		if (existing >= UTILITIES.length) {
			return;
		}
		Block next = UTILITIES[brain.mob.getRandom().nextInt(UTILITIES.length)];
		if (BlockWork.findNearest(level, table, 4, s -> s.is(next)) == null) {
			placeNear(brain, level, table, next.defaultBlockState());
		}
	}

	private static ItemStack product(RandomSource r) {
		return switch (r.nextInt(4)) {
			case 0 -> new ItemStack(Items.STICK, 8);
			case 1 -> new ItemStack(Items.TORCH, 4);
			case 2 -> new ItemStack(Items.OAK_PLANKS, 8);
			default -> new ItemStack(Items.ARROW, 8);
		};
	}

	private static void deposit(ServerLevel level, BlockPos chest, ItemStack stack) {
		if (level.getBlockEntity(chest) instanceof Container container) {
			HopperBlockEntity.addItem(null, container, stack, null);
		}
	}
}
