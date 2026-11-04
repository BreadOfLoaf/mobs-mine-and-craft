package com.mobsminecraft.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Griefers (and imposters): break as many blocks as they can, man-made ones first (yes, even other mobs' houses),
 * and set TNT going without caring who's standing next to it.
 */
final class Griefer {
	private Griefer() {}

	static boolean grieves(SmartBrain b) {
		return b.personality == Personality.GRIEFER || b.personality == Personality.IMPOSTER;
	}

	/** Returns true if it started something this tick. */
	static boolean tick(SmartBrain b, ServerLevel level, LivingEntity target) {
		Mob mob = b.mob;
		RandomSource r = mob.getRandom();
		if (b.griefTntCooldown <= 0 && mob.onGround()) {
			b.griefTntCooldown = 500 + r.nextInt(600);
			// Next to the player if there is one, otherwise wherever it happens to be.
			BlockPos base = target != null && mob.distanceTo(target) < 12.0F ? target.blockPosition() : mob.blockPosition().offset(r.nextInt(5) - 2, 0, r.nextInt(5) - 2);
			for (int i = 0; i < 6; i++) {
				BlockPos p = base.offset(r.nextInt(3) - 1, 0, r.nextInt(3) - 1);
				if (BlockWork.isFloor(level, p.below()) && b.place(level, p, Blocks.TNT.defaultBlockState(), true)) {
					b.unlitTnt.add(p);
					b.tntLightTicks = 10 + r.nextInt(20);
					com.mobsminecraft.debug.AiStats.add("griefer_tnt");
					return true;
				}
			}
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
