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
		// Griefers keep 8 blocks from lit TNT; everyone else just gets out of the blast (5 blocks).
		double keep = grieves(b) ? 8.0 : 5.0;
		for (var t : level.getEntitiesOfClass(net.minecraft.world.entity.item.PrimedTnt.class, mob.getBoundingBox().inflate(keep))) {
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

	/** Something somebody built (a mob-built block, or planks/glass/bricks...) within range, nearest first. */
	private static BlockPos nearestBuild(ServerLevel level, Mob mob, int radius) {
		return BlockWork.findNearestAt(level, mob.blockPosition(), radius, st -> !st.isAir() && (st.is(net.minecraft.tags.BlockTags.PLANKS)
				|| st.is(net.minecraft.tags.BlockTags.WOOL) || st.is(Blocks.GLASS) || st.is(Blocks.GLASS_PANE) || st.is(Blocks.BRICKS)
				|| st.is(Blocks.COBBLESTONE) || st.is(Blocks.STONE_BRICKS) || st.is(net.minecraft.tags.BlockTags.DOORS)),
			(p, st) -> BlockWork.isExposed(level, p) && !BlockWork.isNatural(level, p, st));
	}

	private static void throwTntAt(ServerLevel level, Mob mob, Vec3 at) {
		net.minecraft.world.entity.item.PrimedTnt tnt = new net.minecraft.world.entity.item.PrimedTnt(level, mob.getX(), mob.getEyeY(), mob.getZ(), mob);
		Vec3 d = at.subtract(tnt.position());
		double horiz = Math.sqrt(d.x * d.x + d.z * d.z);
		tnt.setDeltaMovement(d.x / 22.0, Math.max(0.25, d.y / 22.0 + horiz * 0.022 + 0.2), d.z / 22.0);
		tnt.setFuse(50);
		level.addFreshEntity(tnt);
		mob.swing(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT);
		level.playSound(null, mob.blockPosition(), net.minecraft.sounds.SoundEvents.TNT_PRIMED, net.minecraft.sounds.SoundSource.HOSTILE, 1.0F, 1.0F);
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
		// Hunted by the mobs it has been tormenting: scramble about in every direction.
		LivingEntity hunter = null;
		for (Mob m : level.getEntitiesOfClass(Mob.class, mob.getBoundingBox().inflate(10.0), m -> m != mob && m.isAlive() && m.getTarget() == mob)) {
			hunter = m;
			break;
		}
		if (hunter != null) {
			if (b.panicTicks <= 0 || mob.getNavigation().isDone()) {
				b.panicTicks = 12 + r.nextInt(16);
				Vec3 away = mob.position().subtract(hunter.position()).multiply(1.0, 0.0, 1.0);
				double base = away.lengthSqr() < 1.0E-4 ? r.nextDouble() * Math.PI * 2 : Math.atan2(away.z, away.x);
				double a = base + (r.nextDouble() - 0.5) * Math.PI * 1.4;
				double d = 4 + r.nextInt(6);
				mob.getNavigation().moveTo(mob.getX() + Math.cos(a) * d, mob.getY(), mob.getZ() + Math.sin(a) * d, 1.35);
				if (r.nextInt(3) == 0) {
					mob.getJumpControl().jump();
				}
			}
			b.panicTicks--;
			if (mob.tickCount % 40 == 0) {
				com.mobsminecraft.debug.AiStats.add("griefer_hunted");
			}
		}
		// Fire doesn't bother a griefer.
		if (mob.tickCount % 200 == 0) {
			mob.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE, 260, 0, false, false));
		}
		// Lobs lit TNT every few seconds: at a crowd of mobs, at a player it knows about (over walls if need be),
		// at somebody's build, or just somewhere nearby. It doesn't care who's standing there.
		if (b.griefTntCooldown <= 0) {
			b.griefTntCooldown = 80 + r.nextInt(80);
			Vec3 aim = null;
			String why;
			LivingEntity crowd = crowd(level, mob);
			if (crowd != null) {
				aim = crowd.position();
				why = "griefer_tnt_mobs";
			} else if (b.knownPlayer != null && b.knownPlayer.isAlive() && b.knownPlayer.distanceTo(mob) < 20.0F && b.knownPlayer.distanceTo(mob) > 4.0F) {
				aim = b.knownPlayer.position();
				why = "griefer_tnt_player";
			} else {
				BlockPos build = nearestBuild(level, mob, 16);
				if (build != null && Vec3.atCenterOf(build).distanceTo(mob.position()) > 5.0) {
					aim = Vec3.atCenterOf(build);
					why = "griefer_tnt_build";
				} else {
					double a = r.nextDouble() * Math.PI * 2;
					aim = mob.position().add(Math.cos(a) * (7 + r.nextInt(6)), 0.0, Math.sin(a) * (7 + r.nextInt(6)));
					why = "griefer_tnt_random";
				}
			}
			throwTntAt(level, mob, aim);
			com.mobsminecraft.debug.AiStats.add(why);
			return true;
		}
		// Sets mobs on fire, and sets wooden builds burning.
		if (b.griefFireCooldown <= 0) {
			b.griefFireCooldown = 30 + r.nextInt(40);
			for (Mob m : level.getEntitiesOfClass(Mob.class, mob.getBoundingBox().inflate(6.0), m -> m != mob && m.isAlive() && !m.fireImmune() && !m.isOnFire())) {
				m.igniteForSeconds(6.0F);
				mob.swing(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT);
				level.playSound(null, m.blockPosition(), net.minecraft.sounds.SoundEvents.FLINTANDSTEEL_USE, net.minecraft.sounds.SoundSource.HOSTILE, 1.0F, 1.0F);
				com.mobsminecraft.debug.AiStats.add("griefer_burn_mob");
				return true;
			}
			BlockPos wood = BlockWork.findNearestAt(level, mob.blockPosition(), 8, st -> st.ignitedByLava(), (p, st) -> st.ignitedByLava()
				&& level.getBlockState(p.above()).isAir() && !BlockWork.isNatural(level, p, st));
			if (wood != null && Consumable.setFire(level, wood.above())) {
				level.playSound(null, wood, net.minecraft.sounds.SoundEvents.FLINTANDSTEEL_USE, net.minecraft.sounds.SoundSource.HOSTILE, 1.0F, 1.0F);
				com.mobsminecraft.debug.AiStats.add("griefer_burn_build");
				return true;
			}
		}
		// Heads for the nearest build to wreck, or roams about.
		if (b.digTarget == null && mob.tickCount % 40 == 0 && mob.getNavigation().isDone()) {
			BlockPos build = nearestBuild(level, mob, 24);
			if (build != null && Vec3.atCenterOf(build).distanceTo(mob.position()) > 4.0) {
				mob.getNavigation().moveTo(build.getX() + 0.5, build.getY(), build.getZ() + 0.5, 1.15);
			} else {
				double a = r.nextDouble() * Math.PI * 2;
				mob.getNavigation().moveTo(mob.getX() + Math.cos(a) * (8 + r.nextInt(8)), mob.getY(), mob.getZ() + Math.sin(a) * (8 + r.nextInt(8)), 1.15);
			}
		}
		if (b.griefCooldown > 0 || !mob.onGround()) {
			return false;
		}
		b.griefCooldown = 15 + r.nextInt(30);
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
