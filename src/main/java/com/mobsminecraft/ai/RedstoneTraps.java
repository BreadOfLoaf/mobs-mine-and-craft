package com.mobsminecraft.ai;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.vehicle.minecart.MinecartTNT;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.PoweredRailBlock;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.phys.Vec3;

/**
 * Working redstone traps that "engineer" mobs set up during big fights and fire with a button:
 * <ul>
 *   <li>Arrow or fire-charge dispenser: a loaded dispenser aimed at the player with a stone button on top.
 *       The engineer stands behind it and presses the button while the player is in the line of fire.</li>
 *   <li>TNT minecart: a straight track toward the player. A button on a backstop block powers the powered
 *       rail next to it, which launches the cart away from the block; an activator rail near the far end,
 *       powered by a redstone block, lights the TNT as the cart rolls over it.</li>
 * </ul>
 */
final class RedstoneTraps {
	enum Kind { ARROWS, FIRE_CHARGES, TNT_CART }

	private RedstoneTraps() {}

	/** Hostile mobs fighting the same player nearby, this one included. */
	static int waveSize(ServerLevel level, Mob mob, LivingEntity target) {
		return level.getEntitiesOfClass(Mob.class, mob.getBoundingBox().inflate(24.0),
			m -> m instanceof Enemy && m.isAlive() && m.getTarget() == target).size();
	}

	/** Builds, then operates, a trap. Returns true while the engineer is busy with it. */
	static boolean tick(SmartBrain brain, ServerLevel level, LivingEntity target, boolean griefing) {
		Mob mob = brain.mob;
		if (brain.rsPlan != null) {
			return build(brain, level);
		}
		if (brain.rsButton != null) {
			return operate(brain, level, target);
		}
		if (!brain.engineer || !griefing || brain.rsCooldown > 0 || !mob.onGround()) {
			return false;
		}
		double dist = mob.distanceTo(target);
		if (dist < 7.0 || dist > 24.0 || !mob.hasLineOfSight(target) || waveSize(level, mob, target) < 3) {
			return false;
		}
		RandomSource r = mob.getRandom();
		brain.rsCooldown = 600 + r.nextInt(600);
		Vec3 d = target.position().subtract(mob.position());
		Direction dir = Direction.getApproximateNearest(d.x, 0.0, d.z);
		int roll = r.nextInt(100);
		Kind first = roll < 40 ? Kind.ARROWS : roll < 70 ? Kind.FIRE_CHARGES : Kind.TNT_CART;
		if (first == Kind.TNT_CART && planCart(brain, level, dir, r)) {
			return true;
		}
		return planDispenser(brain, level, dir, first == Kind.TNT_CART ? Kind.ARROWS : first);
	}

	// ------------------------------------------------------------------ planning

	private static boolean planDispenser(SmartBrain brain, ServerLevel level, Direction dir, Kind kind) {
		BlockPos spot = brain.mob.blockPosition().relative(dir);
		if (!level.getBlockState(spot).canBeReplaced() || !level.getBlockState(spot.above()).canBeReplaced()
			|| !BlockWork.isFloor(level, spot.below())) {
			return false;
		}
		List<BlockPos> plan = new ArrayList<>();
		List<BlockState> states = new ArrayList<>();
		plan.add(spot);
		states.add(Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING, dir));
		plan.add(spot.above());
		states.add(button(dir));
		start(brain, kind, plan, states, spot.above(), dir);
		return true;
	}

	private static boolean planCart(SmartBrain brain, ServerLevel level, Direction dir, RandomSource r) {
		BlockPos s = brain.mob.blockPosition().relative(dir);
		int len = 6 + r.nextInt(4);
		for (int i = 0; i <= len; i++) {
			BlockPos p = s.relative(dir, i);
			if (!level.getBlockState(p).canBeReplaced() || !BlockWork.isFloor(level, p.below())) {
				return false;
			}
		}
		Direction side = dir.getClockWise();
		BlockPos activator = s.relative(dir, len - 1);
		if (!level.getBlockState(activator.relative(side)).canBeReplaced()) {
			return false;
		}
		RailShape straight = dir.getAxis() == Direction.Axis.X ? RailShape.EAST_WEST : RailShape.NORTH_SOUTH;
		List<BlockPos> plan = new ArrayList<>();
		List<BlockState> states = new ArrayList<>();
		// Backstop block the powered rail pushes the cart away from.
		plan.add(s);
		states.add(Blocks.COBBLESTONE.defaultBlockState());
		plan.add(s.relative(dir, 1));
		states.add(Blocks.POWERED_RAIL.defaultBlockState().setValue(PoweredRailBlock.SHAPE, straight));
		for (int i = 2; i <= len; i++) {
			plan.add(s.relative(dir, i));
			if (i == len - 1) {
				states.add(Blocks.ACTIVATOR_RAIL.defaultBlockState().setValue(PoweredRailBlock.SHAPE, straight));
			} else {
				states.add(Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, straight));
			}
		}
		// Keeps the activator rail powered so the cart lights as it passes.
		plan.add(activator.relative(side));
		states.add(Blocks.REDSTONE_BLOCK.defaultBlockState());
		brain.rsCartPos = s.relative(dir, 1);
		// The button goes on top of the backstop last, after the cart is on the track.
		start(brain, Kind.TNT_CART, plan, states, s.above(), dir);
		return true;
	}

	private static BlockState button(Direction facing) {
		return Blocks.STONE_BUTTON.defaultBlockState()
			.setValue(FaceAttachedHorizontalDirectionalBlock.FACE, AttachFace.FLOOR)
			.setValue(FaceAttachedHorizontalDirectionalBlock.FACING, facing);
	}

	private static void start(SmartBrain brain, Kind kind, List<BlockPos> plan, List<BlockState> states, BlockPos button, Direction dir) {
		com.mobsminecraft.debug.AiStats.add("rs_build:" + kind.name().toLowerCase());
		brain.rsKind = kind;
		brain.rsPlan = plan;
		brain.rsStates = states;
		brain.rsIndex = 0;
		brain.rsButtonSpot = button;
		brain.rsDir = dir;
		brain.rsButton = null;
	}

	// ------------------------------------------------------------------ building

	private static boolean build(SmartBrain brain, ServerLevel level) {
		Mob mob = brain.mob;
		brain.holdStill();
		if (mob.tickCount % 4 != 0) {
			return true;
		}
		if (brain.rsIndex < brain.rsPlan.size()) {
			BlockPos p = brain.rsPlan.get(brain.rsIndex);
			BlockState s = brain.rsStates.get(brain.rsIndex);
			brain.rsIndex++;
			mob.getLookControl().setLookAt(Vec3.atCenterOf(p));
			if (brain.place(level, p, s, false) && s.is(Blocks.DISPENSER) && level.getBlockEntity(p) instanceof DispenserBlockEntity dispenser) {
				dispenser.insertItem(brain.rsKind == Kind.FIRE_CHARGES ? new ItemStack(Items.FIRE_CHARGE, 16) : new ItemStack(Items.ARROW, 64));
			}
			return true;
		}
		// Plan done: put the TNT cart on the track, then the button that fires it.
		if (brain.rsKind == Kind.TNT_CART && brain.rsCartPos != null) {
			MinecartTNT cart = EntityTypes.TNT_MINECART.create(level, EntitySpawnReason.EVENT);
			if (cart != null) {
				cart.setPos(brain.rsCartPos.getX() + 0.5, brain.rsCartPos.getY(), brain.rsCartPos.getZ() + 0.5);
				level.addFreshEntity(cart);
			}
			brain.rsCartPos = null;
			brain.rsPresses = 1;
		} else {
			brain.rsPresses = 10;
		}
		if (brain.rsKind != Kind.TNT_CART) {
			brain.rsButton = brain.rsButtonSpot;
		} else if (brain.place(level, brain.rsButtonSpot, button(brain.rsDir), false)) {
			brain.rsButton = brain.rsButtonSpot;
		}
		brain.rsPlan = null;
		brain.rsOperateTicks = 0;
		return true;
	}

	// ------------------------------------------------------------------ operating

	/** Stays by the button and presses it: dispensers whenever the player is in front, the cart once. */
	private static boolean operate(SmartBrain brain, ServerLevel level, LivingEntity target) {
		Mob mob = brain.mob;
		BlockState state = level.getBlockState(brain.rsButton);
		boolean done = brain.rsPresses <= 0 || ++brain.rsOperateTicks > 400 || !(state.getBlock() instanceof ButtonBlock button)
			|| mob.distanceToSqr(Vec3.atCenterOf(brain.rsButton)) > 16.0;
		if (done) {
			brain.rsButton = null;
			return false;
		}
		brain.holdStill();
		mob.getLookControl().setLookAt(Vec3.atCenterOf(brain.rsButton));
		if (state.getValue(ButtonBlock.POWERED) || brain.rsPressCooldown > 0) {
			brain.rsPressCooldown--;
			return true;
		}
		if (brain.rsKind != Kind.TNT_CART && !inLineOfFire(brain, target)) {
			return true;
		}
		((ButtonBlock) state.getBlock()).press(state, level, brain.rsButton, null);
		level.playSound(null, brain.rsButton, SoundEvents.STONE_BUTTON_CLICK_ON, SoundSource.HOSTILE, 0.6F, 0.6F);
		mob.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
		brain.rsPresses--;
		com.mobsminecraft.debug.AiStats.add("rs_press");
		brain.rsPressCooldown = 10;
		return true;
	}

	/** The player is in front of the dispenser, within a block of its firing line. */
	private static boolean inLineOfFire(SmartBrain brain, LivingEntity target) {
		BlockPos dispenser = brain.rsButton.below();
		Direction dir = brain.rsDir;
		double ahead = (target.getX() - (dispenser.getX() + 0.5)) * dir.getStepX() + (target.getZ() - (dispenser.getZ() + 0.5)) * dir.getStepZ();
		double across = Math.abs((target.getX() - (dispenser.getX() + 0.5)) * dir.getStepZ() - (target.getZ() - (dispenser.getZ() + 0.5)) * dir.getStepX());
		return ahead > 1.0 && ahead < 22.0 && across < 1.5 + ahead * 0.35 && Math.abs(target.getY() - dispenser.getY()) < 3.0;
	}
}
