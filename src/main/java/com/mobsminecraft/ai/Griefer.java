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
		return b.personality == Personality.GRIEFER || b.personality == Personality.IMPOSTER;
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

	/** Returns true if it started something this tick. */
	static boolean tick(SmartBrain b, ServerLevel level, LivingEntity target) {
		Mob mob = b.mob;
		RandomSource r = mob.getRandom();
		if (b.griefTntCooldown <= 0 && mob.onGround()) {
			b.griefTntCooldown = 300 + r.nextInt(400);
			// Drop a lit TNT right where it stands (near the player if it's close), then run for it.
			BlockPos p = mob.blockPosition();
			if (level.getBlockState(p).canBeReplaced() && BlockWork.isFloor(level, p.below())) {
				level.setBlock(p, Blocks.TNT.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
				if (net.minecraft.world.level.block.TntBlock.prime(level, p, mob, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.FLINT_AND_STEEL))) {
					level.setBlock(p, Blocks.AIR.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL_IMMEDIATE);
					level.playSound(null, p, net.minecraft.sounds.SoundEvents.FLINTANDSTEEL_USE, net.minecraft.sounds.SoundSource.HOSTILE, 1.0F, 1.0F);
				}
				b.griefFleeFrom = Vec3.atCenterOf(p);
				b.awayGoal = null;
				com.mobsminecraft.debug.AiStats.add("griefer_tnt");
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
			BlockPos p = here.offset(r.nextInt(9) - 4, r.nextInt(4) - 1, r.nextInt(9) - 4);
			if (p.equals(here.below())) continue;
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
