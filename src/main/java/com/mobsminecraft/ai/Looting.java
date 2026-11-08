package com.mobsminecraft.ai;

import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Mobs go after useful items lying on the ground: better gear, consumables, golden apples, food, ore for minemen.
 * Greedy ones will fight another mob that's about to grab the item first.
 */
final class Looting {
	private Looting() {}

	/** Returns true while the mob is heading for an item. */
	static boolean tick(SmartBrain b, ServerLevel level, LivingEntity target) {
		Mob mob = b.mob;
		if (b.personality == Personality.GIFTER || b.eating() || mob.isPassenger()) {
			return false;
		}
		// Busy fighting: only grab things that are right there.
		double radius = target == null || mob.distanceTo(target) > 12.0F ? 10.0 : 4.0;
		ItemEntity item = b.lootItem;
		if (item != null && (!item.isAlive() || item.distanceTo(mob) > radius + 4.0 || ++b.lootTicks > 200)) {
			b.lootItem = null;
			item = null;
		}
		if (item == null && mob.tickCount % 10 == 0) {
			ItemEntity best = null;
			for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, mob.getBoundingBox().inflate(radius, 3.0, radius),
				e -> e.isAlive() && !Piglins.forPlayers(e) && wants(b, e.getItem()))) {
				if (best == null || e.distanceToSqr(mob) < best.distanceToSqr(mob)) {
					best = e;
				}
			}
			if (best != null) {
				b.lootItem = best;
				b.lootTicks = 0;
				item = best;
				com.mobsminecraft.debug.AiStats.add("loot_seek");
			}
		}
		if (item == null) {
			return false;
		}
		// Greedy: someone else is about to get there first? Fight them for it.
		if (b.greedy && mob instanceof Enemy && b.lootRival == null) {
			final ItemEntity it = item;
			List<Mob> rivals = level.getEntitiesOfClass(Mob.class, item.getBoundingBox().inflate(2.0), m -> m != mob && m.isAlive()
				&& m.distanceToSqr(it) < mob.distanceToSqr(it) && mob.distanceTo(m) < 8.0F);
			if (!rivals.isEmpty()) {
				b.lootRival = rivals.get(0);
				b.lootRivalTicks = 120;
				mob.setTarget(b.lootRival);
				com.mobsminecraft.debug.AiStats.add("loot_fight");
			}
		}
		if (b.lootRival != null) {
			if (--b.lootRivalTicks <= 0 || !b.lootRival.isAlive()) {
				if (mob.getTarget() == b.lootRival) {
					mob.setTarget(null);
				}
				b.lootRival = null;
			} else {
				return false; // let the fight happen
			}
		}
		b.pickupBoostTicks = 20;
		b.walkTo(item.position(), b.walkSpeed() * 1.1);
		return true;
	}

	/** Is this item worth walking over for? */
	static boolean wants(SmartBrain b, ItemStack s) {
		Mob mob = b.mob;
		if (s.isEmpty()) {
			return false;
		}
		if (s.is(Items.GOLDEN_APPLE) || s.is(Items.ENCHANTED_GOLDEN_APPLE) || s.is(Items.TOTEM_OF_UNDYING)) {
			return true;
		}
		if (b.personality == Personality.MINEMAN) {
			return Personalities.isOreItem(s);
		}
		if (Wolves.isWildWolf(b)) {
			return Wolves.wants(b, s);
		}
		if (b.isPassive()) {
			return false;
		}
		if (Recovery.isRawMeat(s) || Recovery.isCookedMeat(s)) {
			return true;
		}
		if (Inventory.consumableFor(s) != null) {
			return true;
		}
		// Gear: only if it's better than what it has.
		if (s.is(Items.SHIELD)) {
			return Shields.canCarry(mob) && mob.getOffhandItem().isEmpty();
		}
		if (s.is(Items.BOW)) {
			return mob instanceof AbstractSkeleton && !mob.getMainHandItem().is(Items.BOW);
		}
		if (!Juggernaut.canWearArmor(mob) && !BlockWork.isToolHolder(mob)) {
			return false;
		}
		EquipmentSlot slot = mob.getEquipmentSlotForItem(s);
		if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR) {
			return Juggernaut.canWearArmor(mob) && Gear.armorTier(s) > Gear.armorTier(mob.getItemBySlot(slot));
		}
		if (Gear.weaponTier(s) > 0) {
			return Gear.canWeapon(mob) && Gear.weaponTier(s) > Gear.weaponTier(mob.getMainHandItem());
		}
		return false;
	}
}
