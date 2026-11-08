package com.mobsminecraft.ai;

import java.util.List;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.monster.piglin.PiglinAi;
import net.minecraft.world.entity.monster.zombie.ZombifiedPiglin;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Nether manners: piglins and brutes leave a player in golden armour alone, zombified piglins stay neutral until
 * someone hits one of them, golden armour can be bartered like gold ingots, and ender pearls turn up a little more often.
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

	/** Something a piglin threw (a trade): it's for players, so no mob picks it up. */
	public static boolean forPlayers(net.minecraft.world.entity.item.ItemEntity item) {
		return item.getOwner() instanceof AbstractPiglin;
	}

	/** Golden armour pieces count as barter currency, like gold ingots. */
	public static boolean isGoldenArmor(ItemStack stack) {
		return stack.is(Items.GOLDEN_HELMET) || stack.is(Items.GOLDEN_CHESTPLATE) || stack.is(Items.GOLDEN_LEGGINGS) || stack.is(Items.GOLDEN_BOOTS)
			|| stack.is(Items.GOLDEN_HORSE_ARMOR);
	}

	/**
	 * Vanilla trades, with ender pearls a little more likely: vanilla gives one pearl about 2.1% of the time; an extra
	 * 2.5% chance of swapping the trade for one pearl brings that to about 4.6%.
	 */
	public static List<ItemStack> morePearls(RandomSource r, List<ItemStack> vanilla) {
		com.mobsminecraft.debug.AiStats.add("barter_trade");
		List<ItemStack> out = vanilla;
		if (r.nextFloat() < 0.025F && vanilla.stream().noneMatch(st -> st.is(Items.ENDER_PEARL))) {
			com.mobsminecraft.debug.AiStats.add("barter_extra_pearl");
			out = List.of(new ItemStack(Items.ENDER_PEARL));
		}
		com.mobsminecraft.debug.AiStats.add("barter_items_out", out.stream().mapToInt(ItemStack::getCount).sum());
		return out;
	}
}
