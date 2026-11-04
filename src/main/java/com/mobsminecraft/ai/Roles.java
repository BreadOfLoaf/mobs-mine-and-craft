package com.mobsminecraft.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.Vec3;

/**
 * What each personality does with its time when there's no fight on, so roles keep going in an open world instead
 * of waiting for something to happen: minemen go looking for ore, engineers set up ambushes where they last saw a
 * player, crafters stay with a group of allies to supply, builders go and find a site. Also gets any mob out of a
 * hole it has been stuck in.
 */
final class Roles {
	private Roles() {}

	/** Returns true if it took over the mob this tick. */
	static boolean idle(SmartBrain b, ServerLevel level) {
		if (unstick(b, level)) {
			return true;
		}
		return switch (b.personality) {
			case MINEMAN -> mineman(b, level);
			case ENGINEER -> engineer(b, level);
			case CRAFTER -> crafter(b, level);
			case BUILDER -> builder(b, level);
			default -> false;
		};
	}

	// ------------------------------------------------------------------ stuck in a hole

	/** Hasn't moved for 10 seconds and is boxed in: dig or tower its way out. */
	private static boolean unstick(SmartBrain b, ServerLevel level) {
		Mob mob = b.mob;
		if (mob.tickCount % 20 == 0) {
			if (b.stuckCheckPos != null && mob.position().distanceToSqr(b.stuckCheckPos) < 0.25) {
				b.stuckIdleTicks += 20;
			} else {
				b.stuckIdleTicks = 0;
			}
			b.stuckCheckPos = mob.position();
		}
		if (b.stuckIdleTicks < 200 || !mob.onGround()) {
			return false;
		}
		BlockPos feet = mob.blockPosition();
		int walls = 0;
		Direction out = null;
		for (Direction d : Direction.Plane.HORIZONTAL) {
			boolean blocked = BlockWork.blocksMovement(level, feet.relative(d)) || BlockWork.blocksMovement(level, feet.relative(d).above());
			if (blocked) {
				walls++;
				if (out == null || mob.getRandom().nextBoolean()) out = d;
			}
		}
		if (walls < 3) {
			return false;
		}
		b.stuckIdleTicks = 0;
		com.mobsminecraft.debug.AiStats.add("unstuck_from_hole");
		// Prefer digging a step out sideways (and up); tower out if that's not possible.
		BlockPos side = feet.relative(out);
		for (BlockPos p : new BlockPos[] {side.above(), side.above(2)}) {
			if (BlockWork.blocksMovement(level, p) && BlockWork.canMine(mob, level, p, level.getBlockState(p))) {
				b.startMining(p, true);
				return true;
			}
		}
		b.startTower(2);
		return true;
	}

	// ------------------------------------------------------------------ mineman

	/** Mine any ore within reach, even buried a little; otherwise its prospecting trips take it underground. */
	private static boolean mineman(SmartBrain b, ServerLevel level) {
		Mob mob = b.mob;
		if (b.digTarget != null || b.stashPos != null || b.prospectPhase != Prospecting.NONE || mob.tickCount % 40 != 0) {
			return false;
		}
		BlockPos here = mob.blockPosition();
		BlockPos ore = BlockWork.findNearestAt(level, here, 10, BlockWork::isOre, (p, s) -> BlockWork.isOre(s) && BlockWork.canMine(mob, level, p, s)
			&& Math.abs(p.getY() - here.getY()) <= 6);
		if (ore != null) {
			b.setDigTarget(ore, true);
			com.mobsminecraft.debug.AiStats.add("mineman_found_ore");
		}
		return false;
	}

	// ------------------------------------------------------------------ engineer

	/** Goes back to where it last knew a player was and sets tripwire traps around there. */
	private static boolean engineer(SmartBrain b, ServerLevel level) {
		Mob mob = b.mob;
		if (!(mob instanceof Enemy) || b.trapPlan != null) {
			return false;
		}
		if (b.knownPos == null || level.getGameTime() - b.knownTick > 2400) {
			// Nobody known about: patrol, and lay a tripwire ambush along the way every so often.
			if (b.trapCooldown == 0 && mob.onGround() && mob.getRandom().nextInt(40) == 0) {
				b.engineerAmbush = true;
				return false;
			}
			if (b.siteGoal == null || mob.position().distanceToSqr(b.siteGoal) < 4.0 || ++b.siteTicks > 400) {
				double a = mob.getRandom().nextDouble() * Math.PI * 2;
				b.siteGoal = mob.position().add(Math.cos(a) * 18, 0.0, Math.sin(a) * 18);
				b.siteTicks = 0;
			}
			b.walkTo(b.siteGoal, b.walkSpeed());
			if (mob.tickCount % 100 == 0) {
				com.mobsminecraft.debug.AiStats.add("engineer_patrol");
			}
			return true;
		}
		if (mob.position().distanceToSqr(b.knownPos) > 64.0) {
			b.walkTo(b.knownPos, b.walkSpeed());
			if (mob.tickCount % 100 == 0) {
				com.mobsminecraft.debug.AiStats.add("engineer_to_ambush");
			}
			return true;
		}
		// Close enough: the trap code places one here (engineers are trap builders with a short cooldown).
		b.trapCooldown = Math.min(b.trapCooldown, 0);
		return false;
	}

	// ------------------------------------------------------------------ crafter

	/** Stays with a group of allies so there's always someone to craft for. */
	private static boolean crafter(SmartBrain b, ServerLevel level) {
		Mob mob = b.mob;
		if (b.crafterTicks > 0 || b.crafterItem != null) {
			return false;
		}
		if (mob.tickCount % 40 == 0 || b.escortOf == null || !b.escortOf.isAlive()) {
			b.escortOf = null;
			Mob best = null;
			for (Mob m : level.getEntitiesOfClass(Mob.class, mob.getBoundingBox().inflate(32.0), m -> m != mob && m.isAlive()
				&& (m instanceof Enemy) == (mob instanceof Enemy) && BlockWork.canBuild(m))) {
				SmartBrain mb = ((com.mobsminecraft.SmartMobHolder) m).mmc$getBrain();
				if (mb.personality == Personality.CRAFTER) continue;
				if (best == null || m.distanceToSqr(mob) < best.distanceToSqr(mob)) best = m;
			}
			b.escortOf = best;
		}
		if (b.escortOf != null && mob.distanceTo(b.escortOf) > 6.0F) {
			b.walkTo(b.escortOf.position(), b.walkSpeed());
			if (mob.tickCount % 100 == 0) {
				com.mobsminecraft.debug.AiStats.add("crafter_escort");
			}
			return true;
		}
		return false;
	}

	// ------------------------------------------------------------------ builder

	/** Ready for another build: try here, and if the spot's no good, go and find a better one. */
	private static boolean builder(SmartBrain b, ServerLevel level) {
		Mob mob = b.mob;
		if (b.housePlan != null || b.houseCooldown > 0) {
			return false;
		}
		if (b.siteGoal != null) {
			if (mob.position().distanceToSqr(b.siteGoal) > 4.0 && ++b.siteTicks < 300) {
				b.walkTo(b.siteGoal, b.walkSpeed());
				return true;
			}
			b.siteGoal = null;
		}
		if (!mob.onGround() || mob.tickCount % 100 != 0) {
			return false;
		}
		b.planHouse(level);
		if (b.housePlan != null) {
			return true;
		}
		// No room here (too close to another build, or rough ground): look elsewhere.
		RandomSource r = mob.getRandom();
		double a = r.nextDouble() * Math.PI * 2;
		double d = 16 + r.nextInt(10);
		b.siteGoal = mob.position().add(Math.cos(a) * d, 0.0, Math.sin(a) * d);
		b.siteTicks = 0;
		com.mobsminecraft.debug.AiStats.add("builder_new_site");
		return true;
	}
}
