package com.mobsminecraft.ai;

import com.mobsminecraft.debug.AiStats;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * How a mob reacts to a player shooting at it. It only goes by what it could honestly notice: a player in plain
 * sight drawing a bow (or holding a loaded crossbow) pointed its way, or an arrow that just hit it.
 * <ul>
 * <li>Workers (builders, crafters, engineers, minemen, gifters) get behind something that blocks the shot; builders
 * throw up a quick wall if there's nothing to hide behind; otherwise they get out of bow range.</li>
 * <li>Melee fighters (fighters, tacticians, heroes) zig-zag as they close in, so they're harder to hit.</li>
 * <li>Imposters don't take it lying down: a player who hurts one becomes its next victim.</li>
 * </ul>
 */
final class UnderFire {
	private static final int MEMORY = 60;

	private UnderFire() {}

	/** Returns true if it took over the mob this tick. */
	static boolean tick(SmartBrain b, ServerLevel level, LivingEntity target) {
		Mob mob = b.mob;
		if (!(mob instanceof Enemy) || !BlockWork.canBuild(mob) && !(mob instanceof net.minecraft.world.entity.monster.spider.Spider)) {
			return false;
		}
		Personality p = b.personality;
		// Imposters: hurt one and you're its next victim.
		if (p == Personality.IMPOSTER && mob.getLastHurtByMob() instanceof Player attacker && !attacker.isCreative() && !attacker.isSpectator()
			&& mob.tickCount - mob.getLastHurtByMobTimestamp() < 100 && (b.provokedBy != attacker || mob.tickCount >= b.provokedUntil)) {
			b.provokedBy = attacker;
			b.provokedUntil = mob.tickCount + 600;
			b.imposterVictim = attacker;
			AiStats.add("imposter_provoked");
			return false;
		}
		Player aiming = aimingAt(level, mob);
		Player shotBy = shotBy(mob);
		Player shooter = aiming != null ? aiming : shotBy;
		if (shooter != null) {
			b.fireFrom = shooter;
			b.underFireUntil = mob.tickCount + MEMORY;
		}
		if (b.fireFrom == null || mob.tickCount > b.underFireUntil || !b.fireFrom.isAlive()) {
			b.fireFrom = null;
			b.coverSpot = null;
			return false;
		}
		Player from = b.fireFrom;
		double dist = mob.distanceTo(from);
		if (dist < 4.5) {
			return false; // close enough to fight (or for the usual keep-away logic)
		}
		boolean worker = p == Personality.BUILDER || p == Personality.CRAFTER || p == Personality.ENGINEER || p == Personality.MINEMAN
			|| p == Personality.GIFTER;
		// An engineer in the middle of setting up or working a trap sticks with it.
		if (p == Personality.ENGINEER && (b.rsPlan != null || b.rsButton != null || b.trapPlan != null)) {
			return false;
		}
		if (worker) {
			b.cancelMining(level);
			return takeCover(b, level, from);
		}
		boolean melee = (p == Personality.FIGHTER || p == Personality.TACTICIAN || p == Personality.HERO) && !(mob instanceof RangedAttackMob);
		if (melee && aiming != null && target == aiming && dist < 32.0 && b.targetReachable && Math.abs(aiming.getY() - mob.getY()) < 2.5) {
			return zigzag(b, aiming);
		}
		return false;
	}

	/** A player in sight with a drawn bow or loaded crossbow pointed at this mob. */
	private static Player aimingAt(ServerLevel level, Mob mob) {
		for (Player pl : level.players()) {
			if (pl.isSpectator() || pl.isCreative() || pl.distanceToSqr(mob) > 40 * 40) continue;
			ItemStack use = pl.getUseItem();
			boolean drawn = pl.isUsingItem() && (use.is(Items.BOW) || use.is(Items.CROSSBOW))
				|| pl.getMainHandItem().is(Items.CROSSBOW) && CrossbowItem.isCharged(pl.getMainHandItem());
			if (!drawn) continue;
			Vec3 to = mob.getBoundingBox().getCenter().subtract(pl.getEyePosition());
			double d = to.length();
			if (d < 1.0E-3) continue;
			// Pointed its way (bows are aimed a little above and ahead of a moving target).
			if (pl.getViewVector(1.0F).dot(to.scale(1.0 / d)) < 0.96) continue;
			if (!mob.hasLineOfSight(pl)) continue;
			return pl;
		}
		return null;
	}

	/** The player whose arrow (or other projectile) hit this mob in the last couple of seconds. */
	private static Player shotBy(Mob mob) {
		DamageSource src = mob.getLastDamageSource();
		if (src != null && src.getDirectEntity() instanceof Projectile && src.getEntity() instanceof Player pl
			&& mob.tickCount - mob.getLastHurtByMobTimestamp() < 40) {
			return pl;
		}
		return null;
	}

	/** Gets somewhere the shooter can't see, or failing that, builds cover or leaves bow range. */
	private static boolean takeCover(SmartBrain b, ServerLevel level, Player from) {
		Mob mob = b.mob;
		Vec3 eye = from.getEyePosition();
		if (b.coverSpot != null && !hidden(level, eye, b.coverSpot)) {
			b.coverSpot = null;
		}
		if (b.coverSpot == null && mob.tickCount >= b.coverSearchTick) {
			b.coverSearchTick = mob.tickCount + 10;
			b.coverSpot = findCover(b, level, eye);
			if (b.coverSpot != null) {
				AiStats.add("underfire_cover");
			}
		}
		if (b.coverSpot != null) {
			Vec3 spot = Vec3.atBottomCenterOf(b.coverSpot);
			if (mob.position().distanceToSqr(spot) > 0.6) {
				if (mob.tickCount % 5 == 0) {
					mob.getNavigation().moveTo(spot.x, spot.y, spot.z, b.walkSpeed() * 1.35);
				}
			} else {
				b.holdStill();
				mob.getLookControl().setLookAt(from, 30.0F, 30.0F);
			}
			return true;
		}
		// Nowhere to hide: a builder puts a wall up; everyone else gets out of bow range.
		if (b.personality == Personality.BUILDER && b.wallPlan == null && b.blocks >= 6 && mob.onGround()
			&& level.getGameRules().get(net.minecraft.world.level.gamerules.GameRules.MOB_GRIEFING)) {
			b.planWall(level, from, 2, 1, 2, b.buildState(), false);
			AiStats.add("underfire_wall");
			return false;
		}
		if (mob.tickCount % 40 == 0) AiStats.add("underfire_flee");
		return Personalities.keepAway(b, from, 30.0, true);
	}

	/** The nearest standable spot within 9 blocks, reachable, that the shooter has no line of sight to. */
	private static BlockPos findCover(SmartBrain b, ServerLevel level, Vec3 eye) {
		Mob mob = b.mob;
		BlockPos here = mob.blockPosition();
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		for (int ring = 2; ring <= 9; ring += 2) {
			for (int k = 0; k < 12; k++) {
				double a = k * Math.PI / 6 + ring;
				BlockPos c = here.offset((int) Math.round(Math.cos(a) * ring), 0, (int) Math.round(Math.sin(a) * ring));
				BlockPos stand = standable(level, c);
				if (stand == null) continue;
				double d = stand.distSqr(here);
				// Not toward the shooter.
				if (d >= bestD || Vec3.atCenterOf(stand).distanceToSqr(eye) < mob.position().distanceToSqr(eye) - 4.0) continue;
				if (!hidden(level, eye, stand)) continue;
				var path = mob.getNavigation().createPath(stand, 0);
				if (path == null || !path.canReach()) continue;
				best = stand;
				bestD = d;
			}
			if (best != null) return best;
		}
		return best;
	}

	private static BlockPos standable(ServerLevel level, BlockPos c) {
		for (int dy = 2; dy >= -3; dy--) {
			BlockPos f = c.above(dy);
			if (level.getBlockState(f).getCollisionShape(level, f).isEmpty() && level.getBlockState(f.above()).getCollisionShape(level, f.above()).isEmpty()
				&& !level.getBlockState(f.below()).getCollisionShape(level, f.below()).isEmpty() && level.getFluidState(f).isEmpty()) {
				return f;
			}
		}
		return null;
	}

	/** Whether a mob standing at {@code spot} is out of sight of {@code eye} (blocked at both head and body height). */
	static boolean hidden(ServerLevel level, Vec3 eye, BlockPos spot) {
		for (double h : new double[] {0.5, 1.6}) {
			Vec3 to = Vec3.atBottomCenterOf(spot).add(0, h, 0);
			if (level.clip(new ClipContext(eye, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, net.minecraft.world.phys.shapes.CollisionContext.empty()))
				.getType() != HitResult.Type.BLOCK) {
				return false;
			}
		}
		return true;
	}

	/** Closes in on a player who's aiming at it, weaving left and right instead of walking a straight line. */
	private static boolean zigzag(SmartBrain b, Player at) {
		Mob mob = b.mob;
		if (--b.zigTicks <= 0) {
			b.zigTicks = 8 + mob.getRandom().nextInt(8);
			b.zigSide = -b.zigSide;
			AiStats.add("underfire_zigzag");
		}
		if (mob.tickCount % 4 == 0) {
			Vec3 to = at.position().subtract(mob.position()).multiply(1, 0, 1);
			if (to.lengthSqr() < 1.0E-4) return false;
			to = to.normalize();
			Vec3 side = new Vec3(-to.z, 0, to.x).scale(2.5 * b.zigSide);
			Vec3 goal = mob.position().add(to.scale(3.0)).add(side);
			mob.getNavigation().moveTo(goal.x, mob.getY(), goal.z, b.walkSpeed() * 1.15);
		}
		mob.getLookControl().setLookAt(at, 30.0F, 30.0F);
		return true;
	}
}
