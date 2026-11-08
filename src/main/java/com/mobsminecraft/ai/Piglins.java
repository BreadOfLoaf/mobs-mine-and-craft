package com.mobsminecraft.ai;

import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.monster.piglin.PiglinAi;
import net.minecraft.world.entity.monster.zombie.ZombifiedPiglin;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;

/**
 * Nether manners: piglins and brutes leave a player in golden armour alone, zombified piglins stay neutral until
 * someone hits one of them, golden armour can be bartered like gold ingots, and some barters hand out gear for the End.
 */
public final class Piglins {
	private Piglins() {}

	/** How long (ticks) a hit keeps a piglin or zombified piglin angry with that player. */
	private static final int GRUDGE = 600;

	/** True if this mob should leave this player alone (and not go after them at all). */
	public static boolean leavesAlone(Mob mob, Player player) {
		if (hurtBy(mob, player)) {
			return false;
		}
		if (mob instanceof AbstractPiglin piglin) {
			if (angryAt(piglin, player)) {
				return false;
			}
			return PiglinAi.isWearingSafeArmor(player);
		}
		if (mob instanceof ZombifiedPiglin zp) {
			if (zp.getPersistentAngerTarget() != null && zp.getPersistentAngerTarget().matches(player)) {
				return false;
			}
			// Neutral, like vanilla: angry only once the player has hit one of the group.
			for (ZombifiedPiglin other : mob.level().getEntitiesOfClass(ZombifiedPiglin.class, mob.getBoundingBox().inflate(20.0))) {
				if (hurtBy(other, player)) {
					return false;
				}
			}
			return true;
		}
		return false;
	}

	private static boolean hurtBy(Mob mob, Player player) {
		return mob.getLastHurtByMob() == player && mob.tickCount - mob.getLastHurtByMobTimestamp() < GRUDGE;
	}

	/** Vanilla piglin anger (hit them, opened their chest, mined their gold...). */
	public static boolean angryAt(AbstractPiglin piglin, LivingEntity who) {
		return piglin.getBrain().getMemory(MemoryModuleType.ANGRY_AT).map(u -> u.equals(who.getUUID())).orElse(false);
	}

	/** Golden armour pieces count as barter currency, like gold ingots. */
	public static boolean isGoldenArmor(ItemStack stack) {
		return stack.is(Items.GOLDEN_HELMET) || stack.is(Items.GOLDEN_CHESTPLATE) || stack.is(Items.GOLDEN_LEGGINGS) || stack.is(Items.GOLDEN_BOOTS)
			|| stack.is(Items.GOLDEN_HORSE_ARMOR);
	}

	/** Now and then a barter turns up something for the End instead (about 15% of trades). */
	public static List<ItemStack> endPrep(ServerLevel level, RandomSource r, List<ItemStack> vanilla) {
		if (r.nextFloat() >= 0.15F) {
			return vanilla;
		}
		int roll = r.nextInt(100);
		ItemStack s;
		if (roll < 20) {
			s = new ItemStack(Items.BLAZE_ROD, 1 + r.nextInt(2));
		} else if (roll < 35) {
			s = PotionContents.createItemStack(Items.POTION, Potions.SLOW_FALLING);
		} else if (roll < 50) {
			s = new ItemStack(Items.GOLDEN_CARROT, 3 + r.nextInt(4));
		} else if (roll < 64) {
			s = new ItemStack(Items.ARROW, 8 + r.nextInt(9));
		} else if (roll < 74) {
			s = new ItemStack(Items.SPECTRAL_ARROW, 4 + r.nextInt(5));
		} else if (roll < 86) {
			s = new ItemStack(Items.CARVED_PUMPKIN);
		} else if (roll < 96) {
			s = new ItemStack(Items.BED.pick(net.minecraft.world.item.DyeColor.WHITE));
		} else {
			s = new ItemStack(Items.ENDER_EYE);
		}
		com.mobsminecraft.debug.AiStats.add("barter_end:" + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).getPath());
		return List.of(s);
	}

	static Item[] endPrepItems() {
		return new Item[] {Items.BLAZE_ROD, Items.POTION, Items.GOLDEN_CARROT, Items.ARROW, Items.SPECTRAL_ARROW, Items.CARVED_PUMPKIN, Items.BED.pick(net.minecraft.world.item.DyeColor.WHITE), Items.ENDER_EYE};
	}
}
