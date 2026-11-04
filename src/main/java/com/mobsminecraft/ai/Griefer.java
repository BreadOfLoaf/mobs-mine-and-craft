package com.mobsminecraft.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Griefers (and imposters): break as many blocks as they can, man-made ones first (yes, even other mobs' houses),
 * and set TNT going without caring who's standing next to it.
 */
final class Griefer {
	private Griefer() {}

	static boolean grieves(SmartBrain b) {
		return b.personality == Personality.GRIEFER;
	}

	/** Runs from any lit TNT nearby (checked before anything else, even mid-mining). Returns true while fleeing. */
	static boolean dodgeTnt(SmartBrain b, ServerLevel level) {
		Mob mob = b.mob;
		RandomSource r = mob.getRandom();
		// Keep well clear of any lit TNT (its own or anyone's) until it goes off.
		net.minecraft.world.entity.item.PrimedTnt tnt = null;
		for (var t : level.getEntitiesOfClass(net.minecraft.world.entity.item.PrimedTnt.class, mob.getBoundingBox().inflate(8.0))) {
			if (tnt == null || t.distanceToSqr(mob) < tnt.distanceToSqr(mob)) tnt = t;
		}
		if (tnt != null) {
			b.griefFleeFrom = tnt.position();
			if (b.awayGoal == null || b.awayGoal.distanceToSqr(tnt.position()) < 100.0 || mob.position().distanceToSqr(b.awayGoal) < 2.0) {
				Vec3 away = mob.position().subtract(tnt.position()).multiply(1.0, 0.0, 1.0);
				if (away.lengthSqr() < 1.0E-4) away = new Vec3(r.nextDouble() - 0.5, 0.0, r.nextDouble() - 0.5);
				b.awayGoal = tnt.position().add(away.normalize().scale(12.0));
			}
			// Re-asserted every tick: the vanilla attack AI would otherwise steer it straight back.
			b.walkTo(new Vec3(b.awayGoal.x, mob.getY(), b.awayGoal.z), 1.25);
			return true;
		}
		b.griefFleeFrom = null;
		return false;
	}

	/** The mob at the centre of the biggest bunch of mobs within 16 blocks (2 or more), or null. */
	private static LivingEntity crowd(ServerLevel level, Mob self) {
		java.util.List<Mob> near = level.getEntitiesOfClass(Mob.class, self.getBoundingBox().inflate(16.0), m -> m != self && m.isAlive() && m.distanceTo(self) > 5.0F);
		Mob best = null;
		int bestCount = 0;
		for (Mob m : near) {
			int count = 0;
			for (Mob o : near) {
				if (o.distanceToSqr(m) < 16.0) count++;
			}
			if (count > bestCount) {
				bestCount = count;
				best = m;
			}
		}
		return best;
	}

	/** Throws a lit TNT in an arc to land around the target. */
	private static void throwTnt(ServerLevel level, Mob mob, LivingEntity at) {
		net.minecraft.world.entity.item.PrimedTnt tnt = new net.minecraft.world.entity.item.PrimedTnt(level, mob.getX(), mob.getEyeY(), mob.getZ(), mob);
		Vec3 d = at.position().subtract(tnt.position());
		double horiz = Math.sqrt(d.x * d.x + d.z * d.z);
		// Roughly a 1-second flight.
		tnt.setDeltaMovement(d.x / 22.0, Math.max(0.25, d.y / 22.0 + horiz * 0.022 + 0.2), d.z / 22.0);
		tnt.setFuse(50);
		level.addFreshEntity(tnt);
		mob.swing(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT);
		level.playSound(null, mob.blockPosition(), net.minecraft.sounds.SoundEvents.TNT_PRIMED, net.minecraft.sounds.SoundSource.HOSTILE, 1.0F, 1.0F);
	}

	/** Returns true if it started something this tick. */
	static boolean tick(SmartBrain b, ServerLevel level, LivingEntity target) {
		Mob mob = b.mob;
		RandomSource r = mob.getRandom();
		// Lobs lit TNT, at a crowd of mobs if there is one (it doesn't care whose side they're on), else at the player.
		if (b.griefTntCooldown <= 0) {
			b.griefTntCooldown = 60 + r.nextInt(60);
			LivingEntity aim = crowd(level, mob);
			if (aim == null && target != null && mob.distanceTo(target) < 16.0F) {
				aim = target;
			}
			if (aim != null && mob.hasLineOfSight(aim)) {
				throwTnt(level, mob, aim);
				com.mobsminecraft.debug.AiStats.add(aim instanceof net.minecraft.world.entity.player.Player ? "griefer_tnt_player" : "griefer_tnt_mobs");
				return true;
			}
		}
		// Sets mobs on fire with flint and steel.
		if (b.griefFireCooldown <= 0) {
			b.griefFireCooldown = 60 + r.nextInt(80);
			for (Mob m : level.getEntitiesOfClass(Mob.class, mob.getBoundingBox().inflate(5.0), m -> m != mob && m.isAlive() && !m.fireImmune() && !m.isOnFire())) {
				// Sets the mob itself alight (not the ground, so the fire doesn't spread back to the griefer).
				m.igniteForSeconds(6.0F);
				mob.swing(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT);
				level.playSound(null, m.blockPosition(), net.minecraft.sounds.SoundEvents.FLINTANDSTEEL_USE, net.minecraft.sounds.SoundSource.HOSTILE, 1.0F, 1.0F);
				com.mobsminecraft.debug.AiStats.add("griefer_burn");
				return true;
			}
		}
		// Roams about looking for things to wreck (towards the player's area if there is one).
		if (b.digTarget == null && mob.tickCount % 60 == 0 && mob.getNavigation().isDone()) {
			Vec3 c = target != null && mob.distanceTo(target) < 24.0F ? target.position() : mob.position();
			double a = r.nextDouble() * Math.PI * 2;
			mob.getNavigation().moveTo(c.x + Math.cos(a) * (6 + r.nextInt(8)), c.y, c.z + Math.sin(a) * (6 + r.nextInt(8)), 1.1);
		}
		if (b.griefCooldown > 0 || !mob.onGround()) {
			return false;
		}
		b.griefCooldown = 40 + r.nextInt(60);
		BlockPos here = mob.blockPosition();
		BlockPos natural = null;
		for (int i = 0; i < 24; i++) {
			// At or above its own feet only, so it never digs itself into a pit.
			BlockPos p = here.offset(r.nextInt(9) - 4, r.nextInt(3), r.nextInt(9) - 4);
			BlockState s = level.getBlockState(p);
			if (s.isAir() || !BlockWork.canMine(mob, level, p, s) || !BlockWork.isExposed(level, p)) continue;
			if (!BlockWork.isNatural(level, p, s)) {
				// Something somebody built: that's the good stuff.
				b.setDigTargetAny(p);
				com.mobsminecraft.debug.AiStats.add("griefer_break_built");
				return true;
			}
			if (natural == null) natural = p;
		}
		if (natural != null) {
			b.setDigTargetAny(natural);
			com.mobsminecraft.debug.AiStats.add("griefer_break");
			return true;
		}
		return false;
	}
}
