package com.mobsminecraft.ai;

import com.mobsminecraft.MobsMineAndCraft;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Every mob gets a personality that decides which of its abilities it actually uses.
 * The roll is stored in the mob's tags, so a mob keeps its personality across saves.
 */
public enum Personality {
	/** Doesn't craft or build: uses what it was given (or picks up) and charges like a vanilla mob. */
	FIGHTER,
	/** Does everything: fights, crafts, uses utility, sets traps, towers, tunnels. The most common. */
	TACTICIAN,
	/** Loves building: houses, outposts, forts. Cleans up the towers and bridges others leave after fights. */
	BUILDER,
	/** Fights only with traps, especially repeater-clock turrets. Keeps out of melee. */
	ENGINEER,
	/** Supports: crafts shields, armour, tools and consumables and tosses them to allies, throws potions. */
	CRAFTER,
	/** Gathers ore for everyone and stashes it in chests. Avoids the player. Helmet, pickaxe and a torch. */
	MINEMAN,
	/** Rare. Double health, extra golden apples; ignores the player and attacks other mobs out of nowhere, griefing as it goes. */
	IMPOSTER,
	/** When nearly beaten it runs, crafts Juggernaut armour (and a totem) and comes back far stronger. */
	HERO,
	/** Won't fight. Walks up to the player and gives them things, now and then enchanted diamond gear. */
	GIFTER,
	/** Wants chaos: breaks as many blocks as it can, lights TNT whoever is near, builds ugly dirt huts. */
	GRIEFER,
	/** The ender dragon: projectiles of every kind, walls near the player, rebuilds crystals (see Boss). */
	BOSS,
	/** Endermen who help: bring the player golden apples, arrows, diamonds and healing potions. */
	END_GIFTER;

	private static final String TAG_PREFIX = MobsMineAndCraft.MOD_ID + ".personality.";
	private static final Identifier IMPOSTER_HEALTH = Identifier.fromNamespaceAndPath(MobsMineAndCraft.MOD_ID, "imposter_health");

	/** The stored personality, or a fresh roll (then stored). */
	static Personality of(Mob mob, RandomSource r) {
		for (String tag : mob.entityTags()) {
			if (tag.startsWith(TAG_PREFIX)) {
				try {
					return valueOf(tag.substring(TAG_PREFIX.length()));
				} catch (IllegalArgumentException ignored) {
					// unknown name from another version: roll again
				}
			}
		}
		Personality p = roll(mob, r);
		mob.addTag(TAG_PREFIX + p.name());
		return p;
	}

	/** Replaces a mob's personality (used by test hooks). */
	public static void set(Mob mob, Personality p) {
		mob.entityTags().stream().filter(t -> t.startsWith(TAG_PREFIX)).toList().forEach(mob::removeTag);
		mob.addTag(TAG_PREFIX + p.name());
	}

	private static Personality roll(Mob mob, RandomSource r) {
		if (mob instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon) {
			return BOSS;
		}
		if (mob instanceof net.minecraft.world.entity.monster.Enderman && r.nextFloat() < 0.15F) {
			return END_GIFTER;
		}
		if (mob instanceof net.minecraft.world.entity.animal.wolf.Wolf) {
			return TACTICIAN; // wild wolves are all tacticians
		}
		if (!BlockWork.canBuild(mob)) {
			// Flyers, swimmers, slimes...: only personalities that don't need building.
			return pick(r, new Personality[] {TACTICIAN, FIGHTER, GIFTER, IMPOSTER, HERO}, new int[] {70, 20, 4, 3, 3});
		}
		if (mob instanceof Villager) {
			return pick(r, new Personality[] {TACTICIAN, GRIEFER, BUILDER, MINEMAN, GIFTER, CRAFTER, FIGHTER, IMPOSTER}, new int[] {38, 16, 14, 10, 10, 6, 4, 4});
		}
		if (!(mob instanceof Enemy)) {
			return pick(r, new Personality[] {TACTICIAN, BUILDER, MINEMAN, GRIEFER, GIFTER, FIGHTER}, new int[] {60, 10, 10, 8, 8, 4});
		}
		return pick(r, new Personality[] {TACTICIAN, FIGHTER, BUILDER, ENGINEER, CRAFTER, MINEMAN, HERO, GRIEFER, GIFTER, IMPOSTER},
			new int[] {36, 15, 9, 8, 8, 7, 5, 5, 2, 6});
	}

	private static Personality pick(RandomSource r, Personality[] options, int[] weights) {
		int total = 0;
		for (int w : weights) total += w;
		int roll = r.nextInt(total);
		for (int i = 0; i < options.length; i++) {
			roll -= weights[i];
			if (roll < 0) return options[i];
		}
		return options[0];
	}

	/** Mobs that don't want to fight the player at all. */
	boolean peaceful() {
		return this == GIFTER || this == IMPOSTER || this == MINEMAN || this == END_GIFTER;
	}

	/** Mobs that get on with their job (building, crafting) and only fight a player who attacks them. */
	boolean busyWithRole() {
		return this == BUILDER || this == CRAFTER;
	}

	/** Mobs that use the clever chase tricks (bridging, towering, tunnelling, stairs, ladders). */
	boolean usesTricks() {
		return this != FIGHTER && this != GIFTER;
	}

	/**
	 * Adjusts the abilities rolled in {@link SmartBrain#init} to fit the personality.
	 * TACTICIAN keeps everything as rolled.
	 */
	static void apply(SmartBrain b, RandomSource r) {
		Mob mob = b.mob;
		Personality p = b.personality;
		boolean enemy = mob instanceof Enemy;
		switch (p) {
			case TACTICIAN -> {
				// Every tactician crafts in a fight (armour, ladders, stairs...).
				b.combatCrafter = enemy && BlockWork.canBuild(mob);
				b.combatCraftCooldown = 40 + r.nextInt(100);
			}
			case BOSS -> {}
			case FIGHTER, GIFTER, END_GIFTER -> {
				noCrafting(b);
				noBuilding(b);
				b.supporter = false;
				b.engineer = false;
				b.trapBuilder = false;
				b.tunneler = false;
				b.ladders = 0;
				b.sniper = false;
			}
			case BUILDER -> {
				noCrafting(b);
				// ...except in a fight: builders knock together ladders, stairs and armour.
				b.combatCrafter = enemy;
				b.combatCraftCooldown = 40 + r.nextInt(100);
				b.houseBuilder = true;
				b.outpostBuilder = enemy;
				b.gardener = true;
				b.engineer = false;
				b.trapBuilder = false;
				b.tunneler = false;
				b.supporter = false;
				b.blocks += 40;
				b.houseCooldown = 200 + r.nextInt(600);
			}
			case ENGINEER -> {
				noCrafting(b);
				noBuilding(b);
				b.engineer = enemy;
				b.trapBuilder = enemy;
				b.tunneler = false;
				b.supporter = false;
				b.rsCooldown = 40 + r.nextInt(100);
				b.trapCooldown = 0;
				if (mob.getOffhandItem().isEmpty()) {
					mob.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.REDSTONE_TORCH));
					mob.setDropChance(EquipmentSlot.OFFHAND, 0.5F);
				}
			}
			case CRAFTER -> {
				noBuilding(b);
				b.combatCrafter = false;
				b.supporter = true;
				b.engineer = false;
				b.trapBuilder = false;
				b.tunneler = false;
				b.sniper = false;
				b.crafterCooldown = 100 + r.nextInt(200);
			}
			case MINEMAN -> {
				noCrafting(b);
				noBuilding(b);
				b.prospectCooldown = 100 + r.nextInt(300);
				b.outpostBuilder = enemy;
				b.supporter = false;
				b.engineer = false;
				b.trapBuilder = false;
				b.tunneler = false;
				b.sniper = false;
				b.oreCooldown = 20;
				// Helmet, pickaxe in hand, torch in the off hand. No other armour.
				for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
					mob.setItemSlot(slot, ItemStack.EMPTY);
				}
				if (mob.getItemBySlot(EquipmentSlot.HEAD).isEmpty()) {
					mob.setItemSlot(EquipmentSlot.HEAD, new ItemStack(r.nextBoolean() ? Items.IRON_HELMET : Items.LEATHER_HELMET));
				}
				if (BlockWork.isToolHolder(mob)) {
					if (b.pickaxe.isEmpty()) {
						b.pickaxe = new ItemStack(r.nextFloat() < 0.6F ? Items.STONE_PICKAXE : Items.IRON_PICKAXE);
					}
					mob.setItemSlot(EquipmentSlot.MAINHAND, b.pickaxe.copy());
				}
				mob.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.TORCH));
				b.torches = 24 + r.nextInt(24);
			}
			case IMPOSTER -> {
				AttributeInstance health = mob.getAttribute(Attributes.MAX_HEALTH);
				if (health != null && !health.hasModifier(IMPOSTER_HEALTH)) {
					health.addPermanentModifier(new AttributeModifier(IMPOSTER_HEALTH, 1.5, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
					mob.setHealth(mob.getMaxHealth());
				}
				Consumable.addCarried(b, Consumable.GOLDEN_APPLE, 3 + r.nextInt(3), null);
				// Starts with no armour but 2.5x health, and grinds its way up to better gear with every "ally" it slays.
				if (!Juggernaut.isJuggernaut(mob)) {
					for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
						mob.setItemSlot(slot, ItemStack.EMPTY);
					}
				}
				b.houseBuilder = BlockWork.canBuild(mob) && r.nextFloat() < 0.3F;
				b.greedy = true;
			}
			case HERO -> {}
			case GRIEFER -> {
				noCrafting(b);
				b.engineer = false;
				b.trapBuilder = false;
				b.supporter = false;
				b.tunneler = enemy;
				b.houseBuilder = BlockWork.canBuild(mob);
				b.houseCooldown = 200 + r.nextInt(600);
				b.griefCooldown = 100 + r.nextInt(200);
				b.griefTntCooldown = 300 + r.nextInt(400);
			}
		}
		if (p == FIGHTER && r.nextFloat() < 0.4F) {
			b.greedy = true;
		}
		com.mobsminecraft.debug.AiStats.add("personality:" + p.name().toLowerCase());
	}

	private static void noCrafting(SmartBrain b) {
		b.combatCrafter = false;
		b.enchanter = false;
		b.toolCrafter = false;
		b.enchantTimer = -1;
		b.fletchTimer = -1;
		b.crafterCooldown = Integer.MAX_VALUE;
	}

	private static void noBuilding(SmartBrain b) {
		b.houseBuilder = false;
		b.outpostBuilder = false;
		b.gardener = false;
		b.farmer = false;
		b.prospectCooldown = Integer.MAX_VALUE;
	}
}
