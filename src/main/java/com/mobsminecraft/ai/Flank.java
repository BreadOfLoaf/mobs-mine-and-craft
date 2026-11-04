package com.mobsminecraft.ai;

import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Small-group tactics. When several melee mobs go for the same player, they spread out and come at them from
 * different sides instead of queueing up in a line; and archers hold fire rather than shoot through an ally.
 */
public final class Flank {
	/** Test switches, so playtests can compare with and without these tactics. */
	public static volatile boolean flanking = true;
	public static volatile boolean holdFire = true;

	private Flank() {}

	/** Steers a melee mob toward its own angle of approach. Returns true if it set the movement this tick. */
	static boolean tick(SmartBrain b, ServerLevel level, LivingEntity target) {
		Mob mob = b.mob;
		if (!flanking || mob instanceof RangedAttackMob || mob instanceof Creeper || !b.targetReachable) {
			return false;
		}
		double dist = mob.distanceTo(target);
		// Only on fairly level ground: a player up a pillar or down a hole needs building, not flanking.
		if (dist < 4.0 || dist > 18.0 || Math.abs(target.getY() - mob.getY()) > 2.0) {
			return false;
		}
		if (mob.tickCount % 20 == 0 || b.flankSlot == Integer.MIN_VALUE) {
			List<Mob> pack = level.getEntitiesOfClass(Mob.class, target.getBoundingBox().inflate(18.0), m -> m.isAlive() && m.getTarget() == target
				&& m instanceof Enemy && !(m instanceof RangedAttackMob) && !(m instanceof Creeper));
			if (pack.size() < 3) {
				b.flankSlot = Integer.MIN_VALUE;
				return false;
			}
			pack.sort(java.util.Comparator.comparingInt(Mob::getId));
			int i = pack.indexOf(mob);
			// Slots alternate: straight on, left, right, wide left, wide right...
			b.flankSlot = i == 0 ? 0 : ((i + 1) / 2) * (i % 2 == 0 ? -1 : 1);
		}
		if (b.flankSlot == 0 || b.flankSlot == Integer.MIN_VALUE) {
			return false;
		}
		// Each mob takes a side of the player (relative to where the pack is coming from): front, left, right,
		// behind. It heads for a spot right next to the player on that side; the last few blocks are the normal attack.
		if (mob.tickCount % 20 == 0 || b.flankBase == null) {
			Vec3 sum = Vec3.ZERO;
			int n = 0;
			for (Mob m : level.getEntitiesOfClass(Mob.class, target.getBoundingBox().inflate(18.0), m -> m.isAlive() && m.getTarget() == target && m instanceof Enemy)) {
				sum = sum.add(m.position());
				n++;
			}
			b.flankBase = n == 0 ? mob.position() : sum.scale(1.0 / n);
		}
		Vec3 toPack = b.flankBase.subtract(target.position()).multiply(1.0, 0.0, 1.0);
		if (toPack.lengthSqr() < 1.0E-4) {
			return false;
		}
		double angle = Math.atan2(toPack.z, toPack.x) + Math.max(-2, Math.min(2, b.flankSlot)) * (Math.PI / 2);
		Vec3 goal = target.position().add(Math.cos(angle) * 2.0, 0.0, Math.sin(angle) * 2.0);
		if (mob.position().distanceToSqr(goal) < 9.0) {
			return false;
		}
		b.walkTo(goal, b.walkSpeed());
		if (mob.tickCount % 40 == 0) {
			com.mobsminecraft.debug.AiStats.add("flanking");
		}
		return true;
	}

	/** Is there an ally between this archer and its target? (Then it doesn't shoot.) */
	public static boolean allyInLine(Mob archer, LivingEntity target) {
		if (!holdFire) {
			return false;
		}
		Vec3 from = archer.getEyePosition();
		Vec3 to = target.getBoundingBox().getCenter();
		AABB box = new AABB(from, to).inflate(1.0);
		for (Mob m : archer.level().getEntitiesOfClass(Mob.class, box, m -> m != archer && m != target && m.isAlive() && m instanceof Enemy)) {
			// A generous margin: arrows arc and spread.
			if (m.getBoundingBox().inflate(0.6).clip(from, to).isPresent()) {
				return true;
			}
		}
		return false;
	}
}
