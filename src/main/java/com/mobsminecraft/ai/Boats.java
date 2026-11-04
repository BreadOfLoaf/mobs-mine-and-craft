package com.mobsminecraft.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.vehicle.boat.Boat;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.phys.Vec3;

/**
 * When the player takes to the water, mobs at the shore craft a boat, hop in and row after them,
 * climbing out again once they reach the player or the shore.
 */
final class Boats {
	private Boats() {}

	/** Returns true while the mob is crafting, boarding or rowing a boat. */
	static boolean tick(SmartBrain brain, ServerLevel level, LivingEntity target, boolean griefing) {
		Mob mob = brain.mob;
		if (mob.getVehicle() instanceof Boat boat && brain.boat == boat) {
			return row(brain, level, boat, target);
		}
		if (brain.boat != null && !mob.isPassenger()) {
			brain.boat = null;
		}
		if (target == null || !griefing || brain.boatCooldown > 0 || mob.isPassenger() || !BlockWork.canBuild(mob)) {
			return false;
		}
		boolean targetOnWater = target.isInWater() || target.getVehicle() instanceof Boat;
		double dist = mob.distanceTo(target);
		if (!targetOnWater || dist < 3.0 || dist > 32.0) {
			brain.boatCraftTicks = 0;
			return false;
		}
		BlockPos water = BlockWork.findNearestAt(level, mob.blockPosition(), 3, (p, s) -> s.getFluidState().is(FluidTags.WATER)
			&& s.getFluidState().isSource() && level.getBlockState(p.above()).isAir());
		if (water == null) {
			return false;
		}
		// A quick craft at the water's edge, then into the boat.
		brain.holdStill();
		mob.getLookControl().setLookAt(Vec3.atCenterOf(water));
		if (++brain.boatCraftTicks % 8 == 0) {
			mob.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
			level.playSound(null, mob.blockPosition(), SoundEvents.WOOD_HIT, SoundSource.HOSTILE, 0.8F, 1.0F);
		}
		if (brain.boatCraftTicks < 30) {
			return true;
		}
		brain.boatCraftTicks = 0;
		brain.boatCooldown = 200;
		Boat boat = EntityTypes.OAK_BOAT.create(level, EntitySpawnReason.EVENT);
		if (boat == null) {
			return false;
		}
		boat.snapTo(water.getX() + 0.5, water.getY() + 0.9, water.getZ() + 0.5, mob.getYRot(), 0.0F);
		level.addFreshEntity(boat);
		if (!mob.startRiding(boat)) {
			boat.discard();
			return false;
		}
		brain.boat = boat;
		brain.boatTicks = 0;
		level.playSound(null, mob.blockPosition(), SoundEvents.BOAT_PADDLE_WATER, SoundSource.HOSTILE, 1.0F, 1.0F);
		com.mobsminecraft.world.TimedCleanup.scheduleEntity(level, boat, 2400);
		com.mobsminecraft.debug.AiStats.add("boat_launched");
		return true;
	}

	private static boolean row(SmartBrain brain, ServerLevel level, Boat boat, LivingEntity target) {
		Mob mob = brain.mob;
		brain.boatTicks++;
		boolean targetOnWater = target != null && (target.isInWater() || target.getVehicle() instanceof Boat);
		double dist = target == null ? 99.0 : mob.distanceTo(target);
		// Climb out to fight on land, or when the chase is over.
		if (target == null || brain.boatTicks > 1200 || (!targetOnWater && (dist < 3.0 || boat.horizontalCollision))) {
			mob.stopRiding();
			brain.boat = null;
			brain.boatCooldown = 100;
			com.mobsminecraft.debug.AiStats.add("boat_left");
			return false;
		}
		if (dist < 1.8) {
			// Alongside: let the normal attack happen.
			return false;
		}
		Vec3 d = target.position().subtract(boat.position()).multiply(1.0, 0.0, 1.0);
		if (d.lengthSqr() > 1.0E-4) {
			Vec3 v = d.normalize().scale(0.22);
			float yaw = (float) (Mth.atan2(d.z, d.x) * (180.0 / Math.PI)) - 90.0F;
			boat.setYRot(yaw);
			boat.setDeltaMovement(v.x, boat.getDeltaMovement().y, v.z);
			if (brain.boatTicks % 20 == 0) {
				level.playSound(null, boat.blockPosition(), SoundEvents.BOAT_PADDLE_WATER, SoundSource.HOSTILE, 0.6F, 1.0F);
			}
		}
		mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
		if (brain.boatTicks % 40 == 0) {
			com.mobsminecraft.debug.AiStats.add("boat_rowing");
		}
		return true;
	}
}
