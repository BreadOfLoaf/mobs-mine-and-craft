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
	private static final String ROLLED_TAG = MobsMineAndCraft.MOD_ID + ".juggernaut_rolled";
	private static final Identifier HEALTH_BONUS = Identifier.fromNamespaceAndPath(MobsMineAndCraft.MOD_ID, "juggernaut_health");
	private static final Identifier KNOCKBACK_BONUS = Identifier.fromNamespaceAndPath(MobsMineAndCraft.MOD_ID, "juggernaut_knockback");
	private static final float CHANCE = 0.03F;

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
		equip(mob, EquipmentSlot.HEAD, Items.DIAMOND_HELMET);
		equip(mob, EquipmentSlot.CHEST, Items.DIAMOND_CHESTPLATE);
		equip(mob, EquipmentSlot.LEGS, Items.DIAMOND_LEGGINGS);
		equip(mob, EquipmentSlot.FEET, Items.DIAMOND_BOOTS);
		// Skeletons switch to melee automatically when given a sword.
		equip(mob, EquipmentSlot.MAINHAND, Items.DIAMOND_SWORD);

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

	private static void equip(Mob mob, EquipmentSlot slot, net.minecraft.world.item.Item item) {
		mob.setItemSlot(slot, new ItemStack(item));
		mob.setDropChance(slot, 2.0F);
	}
}
