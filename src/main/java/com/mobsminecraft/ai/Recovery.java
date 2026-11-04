package com.mobsminecraft.ai;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Retreat and heal: a badly hurt hostile mob falls back (toward a potion-throwing supporter if there is one),
 * walls itself off, then eats cooked meat, cooks raw meat on a campfire, or eats a golden apple, and comes back.
 */
final class Recovery {
	static final Item[] RAW_MEAT = {Items.BEEF, Items.PORKCHOP, Items.CHICKEN, Items.MUTTON, Items.RABBIT};
	static final Item[] COOKED_MEAT = {Items.COOKED_BEEF, Items.COOKED_PORKCHOP, Items.COOKED_CHICKEN, Items.COOKED_MUTTON, Items.COOKED_RABBIT};

	private Recovery() {}

	static boolean isRawMeat(ItemStack s) {
		for (Item i : RAW_MEAT) if (s.is(i)) return true;
		return s.is(Items.COD) || s.is(Items.SALMON);
	}

	static boolean isCookedMeat(ItemStack s) {
		for (Item i : COOKED_MEAT) if (s.is(i)) return true;
		return s.is(Items.COOKED_COD) || s.is(Items.COOKED_SALMON);
	}

	static void assign(SmartBrain brain, RandomSource r) {
		if (!(brain.mob instanceof Enemy)) {
			return;
		}
		brain.meatType = r.nextInt(RAW_MEAT.length);
		float f = r.nextFloat();
		if (f < 0.3F) {
			brain.rawMeat = 1 + r.nextInt(3);
		} else if (f < 0.45F) {
			brain.cookedMeat = 1 + r.nextInt(2);
		}
	}

	private static boolean hasApple(SmartBrain brain) {
		for (Consumable.Carried c : brain.carried) {
			if ((c.type == Consumable.GOLDEN_APPLE || c.type == Consumable.ENCHANTED_GOLDEN_APPLE) && c.uses > 0) return true;
		}
		return false;
	}

	private static Mob nearestSupporter(SmartBrain brain, ServerLevel level) {
		Mob best = null;
		for (Mob m : level.getEntitiesOfClass(Mob.class, brain.mob.getBoundingBox().inflate(20.0), m -> m != brain.mob && m.isAlive())) {
			if (((com.mobsminecraft.SmartMobHolder) m).mmc$getBrain().supporter && (best == null || m.distanceToSqr(brain.mob) < best.distanceToSqr(brain.mob))) {
				best = m;
			}
		}
		return best;
	}

	/** Starts a retreat if the mob has a way to heal. */
	static boolean tryStart(SmartBrain brain, ServerLevel level, LivingEntity target) {
		Mob mob = brain.mob;
		if (brain.recoverCooldown > 0 || !(mob instanceof Enemy) || mob.getHealth() / mob.getMaxHealth() >= 0.4F) {
			return false;
		}
		RandomSource r = mob.getRandom();
		brain.recoverCooldown = 40;
		Mob supporter = nearestSupporter(brain, level);
		boolean canHeal = brain.cookedMeat > 0 || brain.rawMeat > 0 || hasApple(brain) || supporter != null;
		if (!canHeal) {
			return false;
		}
		brain.recoverCooldown = 400 + r.nextInt(300);
		Vec3 goal;
		if (supporter != null && supporter.distanceToSqr(target) > mob.distanceToSqr(target)) {
			goal = supporter.position();
			SmartBrain sb = ((com.mobsminecraft.SmartMobHolder) supporter).mmc$getBrain();
			sb.healRequest = mob;
			sb.supportCooldown = Math.min(sb.supportCooldown, 5);
		} else {
			Vec3 away = mob.position().subtract(target.position()).multiply(1.0, 0.0, 1.0);
			if (away.lengthSqr() < 1.0E-4) away = new Vec3(1.0, 0.0, 0.0);
			goal = mob.position().add(away.normalize().scale(10.0));
			if (supporter != null) {
				SmartBrain sb = ((com.mobsminecraft.SmartMobHolder) supporter).mmc$getBrain();
				sb.healRequest = mob;
				sb.supportCooldown = Math.min(sb.supportCooldown, 5);
			}
		}
		brain.recovering = true;
		brain.recoverFrom = target;
		brain.recoverTicks = 0;
		brain.recoverGoal = goal;
		brain.recoverWalled = false;
		brain.recoverFire = null;
		mob.setSprinting(true);
		com.mobsminecraft.debug.AiStats.add("retreat");
		return true;
	}

	/** Returns true while retreating or healing. */
	static boolean tick(SmartBrain brain, ServerLevel level, boolean griefing) {
		Mob mob = brain.mob;
		LivingEntity target = brain.recoverFrom;
		brain.recoverTicks++;
		float hp = mob.getHealth() / mob.getMaxHealth();
		if (hp > 0.8F || brain.recoverTicks > 260 || target == null || !target.isAlive() || target.distanceTo(mob) > 40.0F) {
			end(brain, level, hp > 0.8F);
			return false;
		}
		// Stop chasing the player while falling back; it picks the fight up again afterwards.
		if (mob.getTarget() != null) {
			mob.setTarget(null);
		}
		if (brain.recoverTicks < 80 && mob.position().distanceToSqr(brain.recoverGoal) > 6.0) {
			brain.walkTo(brain.recoverGoal, brain.walkSpeed() * 1.35);
			return true;
		}
		mob.setSprinting(false);
		if (!brain.recoverWalled && griefing && mob.onGround() && mob.distanceTo(target) < 16.0F) {
			brain.recoverWalled = true;
			if (brain.forceBunker || mob.getRandom().nextFloat() < 0.45F) {
				// Seal itself in completely: walls all round and a roof.
				brain.forceBunker = false;
				planBunker(brain, mob.blockPosition());
				com.mobsminecraft.debug.AiStats.add("bunker");
			} else {
				brain.planWall(level, target, 1, 1, 2, brain.buildState(), false);
			}
		}
		brain.holdStill();
		// Catch its breath a little while hiding.
		if (mob.tickCount % 30 == 0) {
			mob.heal(1.0F);
		}
		RandomSource r = mob.getRandom();
		if (brain.recoverFire != null) {
			Vec3 c = Vec3.atCenterOf(brain.recoverFire);
			mob.getLookControl().setLookAt(c);
			if (mob.tickCount % 8 == 0) {
				level.sendParticles(ParticleTypes.SMOKE, c.x, c.y + 0.5, c.z, 2, 0.15, 0.1, 0.15, 0.01);
			}
			if (++brain.recoverCookTicks >= 50) {
				brain.recoverFire = null;
				int n = Math.min(brain.rawMeat, 2);
				brain.rawMeat -= n;
				brain.cookedMeat += n;
				level.playSound(null, mob.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.HOSTILE, 0.6F, 1.2F);
				com.mobsminecraft.debug.AiStats.add("cook_meat_fight");
			}
			return true;
		}
		if (brain.recoverTicks % 20 != 0) {
			return true;
		}
		if (brain.cookedMeat > 0) {
			eat(brain, level, new ItemStack(COOKED_MEAT[brain.meatType]), 7.0F, () -> {
				brain.cookedMeat = Math.max(0, brain.cookedMeat - 1);
				com.mobsminecraft.debug.AiStats.add("eat_meat");
			});
		} else if (brain.rawMeat > 0 && griefing) {
			BlockPos fire = brain.placeAdjacent(level, Blocks.CAMPFIRE.defaultBlockState());
			if (fire != null) {
				com.mobsminecraft.world.MobBuilt.unmark(level, fire);
				com.mobsminecraft.world.TimedCleanup.scheduleBlock(level, fire, Blocks.CAMPFIRE, 400);
				brain.recoverFire = fire;
				brain.recoverCookTicks = 0;
			} else {
				// No room for a fire: eat it raw.
				eat(brain, level, new ItemStack(RAW_MEAT[brain.meatType]), 3.0F, () -> {
					brain.rawMeat = Math.max(0, brain.rawMeat - 1);
					com.mobsminecraft.debug.AiStats.add("eat_raw_meat");
				});
			}
		} else if (brain.rawMeat > 0) {
			eat(brain, level, new ItemStack(RAW_MEAT[brain.meatType]), 3.0F, () -> {
				brain.rawMeat = Math.max(0, brain.rawMeat - 1);
				com.mobsminecraft.debug.AiStats.add("eat_raw_meat");
			});
		} else if (hasApple(brain) && brain.consumableCooldown > 5) {
			brain.consumableCooldown = 0;
		}
		return true;
	}

	/** Eats like a player does: takes 1.6 seconds, and the food only counts if it isn't interrupted. */
	static void eat(SmartBrain brain, ServerLevel level, ItemStack food, float heal, Runnable consumed) {
		Mob mob = brain.mob;
		Eating.start(brain, level, food, Eating.EAT_TICKS, () -> {
			mob.heal(heal);
			consumed.run();
		});
		level.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, food.getItem()), mob.getX(), mob.getEyeY() - 0.2, mob.getZ(), 8, 0.2, 0.1, 0.2, 0.05);
	}

	private static void planBunker(SmartBrain brain, BlockPos feet) {
		Mob mob = brain.mob;
		mob.setPos(feet.getX() + 0.5, mob.getY(), feet.getZ() + 0.5);
		List<BlockPos> plan = new java.util.ArrayList<>();
		int h = brain.height();
		for (int y = 0; y < h; y++) {
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					if (dx != 0 || dz != 0) {
						plan.add(feet.offset(dx, y, dz));
					}
				}
			}
		}
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				plan.add(feet.offset(dx, h, dz));
			}
		}
		brain.wallPlan = plan;
		brain.wallState = brain.buildState();
		brain.wallIndex = 0;
		brain.wallInterval = 1;
		brain.bunker = new java.util.ArrayList<>();
	}

	/** Bursts out of its bunker toward the player. */
	private static void breakOut(SmartBrain brain, ServerLevel level) {
		if (brain.bunker == null) {
			return;
		}
		Mob mob = brain.mob;
		BlockPos feet = mob.blockPosition();
		LivingEntity from = brain.recoverFrom;
		net.minecraft.core.Direction d = from == null ? mob.getDirection()
			: net.minecraft.core.Direction.getApproximateNearest(from.getX() - mob.getX(), 0.0, from.getZ() - mob.getZ());
		for (int y = 0; y < brain.height(); y++) {
			BlockPos p = feet.relative(d).above(y);
			if (brain.bunker.contains(p)) {
				level.destroyBlock(p, false, mob);
			}
		}
		brain.bunker = null;
		brain.wallPlan = null;
		brain.wallInterval = 4;
		com.mobsminecraft.debug.AiStats.add("bunker_break_out");
	}

	private static void end(SmartBrain brain, ServerLevel level, boolean healed) {
		breakOut(brain, level);
		brain.recovering = false;
		if (brain.recoverFrom != null && brain.recoverFrom.isAlive() && SmartBrain.isValidTarget(brain.recoverFrom)) {
			brain.mob.setTarget(brain.recoverFrom);
		}
		brain.recoverFrom = null;
		brain.recoverFire = null;
		brain.mob.setSprinting(false);
		com.mobsminecraft.debug.AiStats.add(healed ? "retreat_healed" : "retreat_ended");
	}

	/** Out of combat, a hurt mob eats meat it carries. */
	static void snack(SmartBrain brain, ServerLevel level) {
		Mob mob = brain.mob;
		if (mob.tickCount % 40 != 0 || mob.getHealth() / mob.getMaxHealth() > 0.6F || brain.cookedMeat <= 0 || brain.eating()) {
			return;
		}
		eat(brain, level, new ItemStack(COOKED_MEAT[brain.meatType]), 7.0F, () -> {
			brain.cookedMeat = Math.max(0, brain.cookedMeat - 1);
			com.mobsminecraft.debug.AiStats.add("eat_meat");
		});
	}
}
