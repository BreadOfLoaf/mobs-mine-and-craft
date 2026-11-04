package com.mobsminecraft.ai;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.item.Items;

/**
 * A small neural network that picks a fighting mob's tactic once a second, trained offline by neuroevolution
 * against the playtest bot (see the test mod's Trainer). 16 inputs (what the mob can sense) -> 10 tanh hidden units
 * -> 8 outputs, one per tactic; the highest output wins. "Usual" hands control to the hand-written AI, so a network
 * that knows nothing behaves exactly like the mod without it.
 */
public final class NeuralTactics {
	public enum Tactic { USUAL, FLANK, RETREAT, CRAFT, COVER, REGROUP, RUSH, ITEM }

	public static final int INPUTS = 16;
	public static final int HIDDEN = 10;
	public static final int OUTPUTS = Tactic.values().length;
	public static final int SIZE = INPUTS * HIDDEN + HIDDEN + HIDDEN * OUTPUTS + OUTPUTS;

	/** The trained network shipped with the mod (null = not trained yet: hand-written AI only). */
	private static float[] shipped = load();

	private NeuralTactics() {}

	public static float[] shipped() {
		return shipped;
	}

	private static float[] load() {
		try (InputStream in = NeuralTactics.class.getResourceAsStream("/data/mobsmineandcraft/tactics.weights")) {
			if (in == null) {
				return null;
			}
			List<Float> w = new ArrayList<>();
			for (String line : new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines().toList()) {
				line = line.trim();
				if (line.isEmpty() || line.startsWith("#")) continue;
				for (String part : line.split("[,\\s]+")) {
					if (!part.isEmpty()) w.add(Float.parseFloat(part));
				}
			}
			if (w.size() != SIZE) {
				com.mobsminecraft.MobsMineAndCraft.LOGGER.warn("tactics.weights has {} numbers, expected {}; neural tactics off", w.size(), SIZE);
				return null;
			}
			float[] a = new float[SIZE];
			for (int i = 0; i < SIZE; i++) a[i] = w.get(i);
			return a;
		} catch (Exception e) {
			com.mobsminecraft.MobsMineAndCraft.LOGGER.warn("Couldn't load neural tactics", e);
			return null;
		}
	}

	/** What the mob senses, scaled to roughly -1..1. Only things it could honestly know. */
	static float[] features(SmartBrain b, ServerLevel level, LivingEntity target) {
		Mob mob = b.mob;
		float[] f = new float[INPUTS];
		double dist = mob.distanceTo(target);
		int alliesFighting = 0;
		int alliesClose = 0;
		for (Mob m : level.getEntitiesOfClass(Mob.class, mob.getBoundingBox().inflate(16.0), m -> m != mob && m.isAlive() && m instanceof Enemy)) {
			if (m.getTarget() == target) alliesFighting++;
			if (m.distanceToSqr(mob) < 16.0) alliesClose++;
		}
		boolean ranged = target.getMainHandItem().is(Items.BOW) || target.getMainHandItem().is(Items.CROSSBOW);
		float armor = 0;
		for (var slot : new net.minecraft.world.entity.EquipmentSlot[] {net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.entity.EquipmentSlot.CHEST,
			net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET}) {
			armor += Gear.armorTier(mob.getItemBySlot(slot));
		}
		boolean canHeal = b.cookedMeat > 0 || b.rawMeat > 0 || b.carried.stream().anyMatch(c -> c.type.isApple() && c.uses > 0);
		f[0] = mob.getHealth() / mob.getMaxHealth();
		f[1] = (float) Math.min(1.0, dist / 24.0);
		f[2] = (float) Math.max(-1.0, Math.min(1.0, (target.getY() - mob.getY()) / 8.0));
		f[3] = mob.hasLineOfSight(target) ? 1 : 0;
		f[4] = b.targetReachable ? 1 : 0;
		f[5] = Math.min(1.0F, alliesFighting / 6.0F);
		f[6] = Math.min(1.0F, alliesClose / 4.0F);
		f[7] = ranged ? 1 : 0;
		f[8] = armor / 16.0F;
		f[9] = mob.tickCount - mob.getLastHurtByMobTimestamp() < 40 ? 1 : 0;
		f[10] = mob instanceof RangedAttackMob ? 1 : 0;
		f[11] = canHeal ? 1 : 0;
		f[12] = b.combatCrafter && b.combatCraftCooldown == 0 ? 1 : 0;
		f[13] = Math.min(1.0F, b.fightTicks / 600.0F);
		f[14] = b.carried.isEmpty() ? 0 : 1;
		f[15] = 1.0F;
		return f;
	}

	/** Runs the network: the index of the highest output. */
	public static int decide(float[] w, float[] in) {
		float[] h = new float[HIDDEN];
		int k = 0;
		for (int j = 0; j < HIDDEN; j++) {
			float s = 0;
			for (int i = 0; i < INPUTS; i++) s += w[k++] * in[i];
			h[j] = s;
		}
		for (int j = 0; j < HIDDEN; j++) h[j] = (float) Math.tanh(h[j] + w[k++]);
		int best = 0;
		float bestV = Float.NEGATIVE_INFINITY;
		int outStart = k;
		for (int o = 0; o < OUTPUTS; o++) {
			float s = 0;
			for (int j = 0; j < HIDDEN; j++) s += w[outStart + o * HIDDEN + j] * h[j];
			s += w[outStart + OUTPUTS * HIDDEN + o];
			if (s > bestV) {
				bestV = s;
				best = o;
			}
		}
		return best;
	}

	/** A fresh random network that nearly always says "usual" (so it starts out as the hand-written AI). */
	public static float[] seed(java.util.Random r) {
		float[] w = new float[SIZE];
		for (int i = 0; i < SIZE; i++) w[i] = (float) (r.nextGaussian() * 0.3);
		w[SIZE - OUTPUTS + Tactic.USUAL.ordinal()] = 2.0F;
		return w;
	}

	/**
	 * Picks (once a second) and carries out the mob's tactic. Returns true if the tactic took over this tick;
	 * false hands over to the usual hand-written fighting code.
	 */
	static boolean tick(SmartBrain b, ServerLevel level, LivingEntity target) {
		float[] w = b.tacticsWeights != null ? b.tacticsWeights : shipped;
		if (w == null) {
			return false;
		}
		Mob mob = b.mob;
		b.fightTicks++;
		if (--b.tacticTimer <= 0) {
			b.tacticTimer = 20;
			b.tactic = Tactic.values()[decide(w, features(b, level, target))];
			com.mobsminecraft.debug.AiStats.add("tactic:" + b.tactic.name().toLowerCase());
		}
		switch (b.tactic) {
			case USUAL -> {
				return false;
			}
			case FLANK -> {
				return Flank.tick(b, level, target);
			}
			case RETREAT -> {
				if (mob.getHealth() >= mob.getMaxHealth() * 0.75F) {
					return false; // healthy mobs don't run: fight as usual
				}
				if (!b.recovering && b.recoverCooldown == 0 && mob.getHealth() < mob.getMaxHealth()) {
					if (Recovery.tryStartAny(b, level, target)) {
						return true;
					}
				}
				return Personalities.keepAway(b, target, 10.0, true);
			}
			case CRAFT -> {
				if (b.combatCrafter) {
					return CombatCrafting.tick(b, level, target, level.getGameRules().get(net.minecraft.world.level.gamerules.GameRules.MOB_GRIEFING));
				}
				return false;
			}
			case COVER -> {
				if (b.wallPlan == null && b.coverCooldownNeural <= 0 && mob.onGround()) {
					b.coverCooldownNeural = 100;
					b.planWall(level, target, 2, 1, 2, b.buildState(), false);
					com.mobsminecraft.debug.AiStats.add("neural_cover");
				}
				return false;
			}
			case REGROUP -> {
				int allies = level.getEntitiesOfClass(Mob.class, mob.getBoundingBox().inflate(12.0), m -> m != mob && m.isAlive() && m.getTarget() == target).size();
				if (allies < 2 && mob.distanceTo(target) < 12.0F) {
					return Personalities.keepAway(b, target, 12.0, true);
				}
				return false;
			}
			case RUSH -> {
				// Straight at them: no tricks, the vanilla attack does the rest.
				if (mob.tickCount % 10 == 0) {
					mob.getNavigation().moveTo(target, b.walkSpeed() * 1.1);
				}
				return true;
			}
			case ITEM -> {
				// Only picks the moment: the usual consumable cooldown still applies.
				Consumable.tryUse(b, level, target, level.getGameRules().get(net.minecraft.world.level.gamerules.GameRules.MOB_GRIEFING));
				return false;
			}
		}
		return false;
	}
}
