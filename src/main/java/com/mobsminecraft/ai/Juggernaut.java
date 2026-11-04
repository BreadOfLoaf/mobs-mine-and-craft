package com.mobsminecraft.ai;

import com.mobsminecraft.MobsMineAndCraft;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** A rare armoured variant of mobs that can wear armour: full diamond gear and extra health. */
final class Juggernaut {
	static final String ROLLED_TAG = MobsMineAndCraft.MOD_ID + ".juggernaut_rolled";
	static final String KNIGHTS_TAG = MobsMineAndCraft.MOD_ID + ".knights_spawned";
	private static final Identifier HEALTH_BONUS = Identifier.fromNamespaceAndPath(MobsMineAndCraft.MOD_ID, "juggernaut_health");
	private static final Identifier KNOCKBACK_BONUS = Identifier.fromNamespaceAndPath(MobsMineAndCraft.MOD_ID, "juggernaut_knockback");
	private static final float CHANCE = 0.015F;

	private Juggernaut() {}

	static boolean canWearArmor(Mob mob) {
		return mob instanceof Zombie || mob instanceof AbstractSkeleton || mob instanceof AbstractPiglin;
	}

	static boolean isJuggernaut(Mob mob) {
		AttributeInstance health = mob.getAttribute(Attributes.MAX_HEALTH);
		return health != null && health.hasModifier(HEALTH_BONUS);
	}

	/** Rolls once per mob (the roll is remembered in the mob's saved tags). */
	static void roll(Mob mob, RandomSource r) {
		if (!canWearArmor(mob) || mob.entityTags().contains(ROLLED_TAG)) {
			return;
		}
		mob.addTag(ROLLED_TAG);
		if (r.nextFloat() >= CHANCE) {
			return;
		}
		make(mob, r);
	}

	/** Turns the mob into a Juggernaut: enchanted diamond gear, extra health and knockback resistance. */
	static void make(Mob mob, RandomSource r) {
		if (!canWearArmor(mob) || isJuggernaut(mob)) {
			return;
		}
		mob.addTag(ROLLED_TAG);
		equip(mob, r, EquipmentSlot.HEAD, Items.DIAMOND_HELMET);
		equip(mob, r, EquipmentSlot.CHEST, Items.DIAMOND_CHESTPLATE);
		equip(mob, r, EquipmentSlot.LEGS, Items.DIAMOND_LEGGINGS);
		equip(mob, r, EquipmentSlot.FEET, Items.DIAMOND_BOOTS);
		// Skeleton Juggernauts keep a (heavily enchanted) bow; everyone else gets a diamond sword.
		equip(mob, r, EquipmentSlot.MAINHAND, mob instanceof AbstractSkeleton ? Items.BOW : Items.DIAMOND_SWORD);

		AttributeInstance health = mob.getAttribute(Attributes.MAX_HEALTH);
		if (health != null && !health.hasModifier(HEALTH_BONUS)) {
			health.addPermanentModifier(new AttributeModifier(HEALTH_BONUS, 20.0, AttributeModifier.Operation.ADD_VALUE));
		}
		AttributeInstance knockback = mob.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
		if (knockback != null && !knockback.hasModifier(KNOCKBACK_BONUS)) {
			knockback.addPermanentModifier(new AttributeModifier(KNOCKBACK_BONUS, 0.4, AttributeModifier.Operation.ADD_VALUE));
		}
		mob.setHealth(mob.getMaxHealth());
	}

	/** Enchanted gear. It never drops the vanilla way: on death it all drops, worn down (see {@link #dropGear}). */
	private static void equip(Mob mob, RandomSource r, EquipmentSlot slot, net.minecraft.world.item.Item item) {
		ItemStack stack = new ItemStack(item);
		if (mob.level() instanceof net.minecraft.server.level.ServerLevel level) {
			stack = capped(net.minecraft.world.item.enchantment.EnchantmentHelper.enchantItem(r, stack, 15 + r.nextInt(11), level.registryAccess(), java.util.Optional.empty()));
		}
		mob.setItemSlot(slot, stack);
		mob.setDropChance(slot, 0.0F);
	}

	/** Juggernaut gear tops out at level III enchantments. */
	static ItemStack capped(ItemStack stack) {
		net.minecraft.world.item.enchantment.EnchantmentHelper.updateEnchantments(stack, m -> {
			for (var e : java.util.List.copyOf(m.keySet())) {
				if (m.getLevel(e) > 3) {
					m.set(e, 3);
				}
			}
		});
		return stack;
	}

	/** A copy of the item worn down to 15-60% durability. */
	static ItemStack worn(ItemStack stack, RandomSource r) {
		ItemStack drop = stack.copy();
		if (drop.isDamageableItem()) {
			int max = drop.getMaxDamage();
			drop.setDamageValue(Math.max(drop.getDamageValue(), (int) (max * (0.4F + r.nextFloat() * 0.45F))));
		}
		return drop;
	}

	/** Juggernauts mine with an enchanted diamond pickaxe. */
	static ItemStack pickaxe(Mob mob, RandomSource r) {
		ItemStack pick = new ItemStack(Items.DIAMOND_PICKAXE);
		if (mob.level() instanceof net.minecraft.server.level.ServerLevel level) {
			pick = capped(net.minecraft.world.item.enchantment.EnchantmentHelper.enchantItem(r, pick, 15 + r.nextInt(11), level.registryAccess(), java.util.Optional.empty()));
		}
		return pick;
	}

	/** A dead Juggernaut drops every piece of its gear, damaged from the fight. */
	static void dropGear(net.minecraft.server.level.ServerLevel level, Mob mob) {
		if (!isJuggernaut(mob)) {
			return;
		}
		RandomSource r = mob.getRandom();
		for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.MAINHAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
			ItemStack stack = mob.getItemBySlot(slot);
			if (stack.isEmpty() || mob.getDropChances().byEquipment(slot) > 0.0F) {
				continue;
			}
			mob.spawnAtLocation(level, worn(stack, r));
			mob.setItemSlot(slot, ItemStack.EMPTY);
		}
	}
}
