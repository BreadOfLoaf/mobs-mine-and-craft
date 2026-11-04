package com.mobsminecraft.ai;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.TripWireBlock;
import net.minecraft.world.level.block.TripWireHookBlock;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Tripwire traps: a 3-block tripwire between two hooks, each hook mounted on a loaded dispenser
 * that fires down the wire when it is tripped.
 *
 * <pre>
 *   [D>] [hook] [wire] [wire] [wire] [hook] [<D]
 * </pre>
 */
final class Traps {
	private Traps() {}

	/** Returns true while the mob is busy with its trap. */
	static boolean tick(SmartBrain brain, ServerLevel level) {
		Mob mob = brain.mob;
		if (!brain.trapBuilder || brain.trapCooldown > 0) {
			return false;
		}
		if (brain.trapPlan == null) {
			boolean engineer = brain.personality == Personality.ENGINEER;
			boolean knowsPlayer = brain.knownPlayer != null && level.getGameTime() - brain.knownTick < 2400;
			// Engineers on patrol set ambushes even without knowing where a player is.
			boolean ambush = engineer && brain.engineerAmbush;
			if (mob.tickCount < 200 || !mob.onGround() || (!ambush && (mob.getRandom().nextInt(engineer ? 10 : 80) != 0 || !knowsPlayer))) {
				return false;
			}
			brain.engineerAmbush = false;
			plan(brain, level);
			if (brain.trapPlan == null) {
				return false;
			}
		}
		BlockPos next = brain.trapPlan.get(Math.min(brain.trapIndex, brain.trapPlan.size() - 1));
		Vec3 c = Vec3.atCenterOf(next);
		if (mob.distanceToSqr(c) > 100.0) {
			brain.trapPlan = null;
			return false;
		}
		if (mob.distanceToSqr(c) > 12.0) {
			if (mob.tickCount % 10 == 0) {
				mob.getNavigation().moveTo(c.x, c.y, c.z, brain.walkSpeed());
			}
			return true;
		}
		mob.getNavigation().stop();
		mob.getLookControl().setLookAt(c);
		if (mob.tickCount % 8 != 0) {
			return true;
		}
		BlockPos pos = brain.trapPlan.get(brain.trapIndex);
		BlockState state = brain.trapStates.get(brain.trapIndex);
		brain.trapIndex++;
		if (brain.place(level, pos, state, false) && state.is(Blocks.DISPENSER)
			&& level.getBlockEntity(pos) instanceof DispenserBlockEntity dispenser) {
			load(dispenser, mob.getRandom());
		}
		if (brain.trapIndex >= brain.trapPlan.size()) {
			brain.trapPlan = null;
			com.mobsminecraft.debug.AiStats.add("tripwire_trap");
			// Rest a minute or two, then build another one.
			brain.trapCooldown = brain.personality == Personality.ENGINEER ? 400 + mob.getRandom().nextInt(400) : 1200 + mob.getRandom().nextInt(1200);
		}
		return true;
	}

	private static void plan(SmartBrain brain, ServerLevel level) {
		Mob mob = brain.mob;
		// Try every direction so traps get built even in cramped spots.
		Direction first = Direction.Plane.HORIZONTAL.getRandomDirection(mob.getRandom());
		Direction dir = null;
		List<BlockPos> positions = null;
		int wires = 2 + mob.getRandom().nextInt(4);
		int length = wires + 4;
		for (int turn = 0; turn < 4 && positions == null; turn++) {
			Direction d = turn == 0 ? first : first.getClockWise();
			first = d;
			positions = line(level, mob.blockPosition().relative(d, 2), d, length);
			dir = d;
		}
		if (positions == null) {
			return;
		}

		Direction back = dir.getOpposite();
		BlockState wire = Blocks.TRIPWIRE.defaultBlockState()
			.setValue(dir.getAxis() == Direction.Axis.X ? TripWireBlock.EAST : TripWireBlock.NORTH, true)
			.setValue(dir.getAxis() == Direction.Axis.X ? TripWireBlock.WEST : TripWireBlock.SOUTH, true);

		// Order matters: dispensers first, then hooks on them, then the wire (which attaches the hooks).
		List<BlockPos> plan = new ArrayList<>();
		List<BlockState> states = new ArrayList<>();
		plan.add(positions.get(0));
		states.add(Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING, dir));
		plan.add(positions.get(length - 1));
		states.add(Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING, back));
		plan.add(positions.get(1));
		states.add(Blocks.TRIPWIRE_HOOK.defaultBlockState().setValue(TripWireHookBlock.FACING, dir));
		plan.add(positions.get(length - 2));
		states.add(Blocks.TRIPWIRE_HOOK.defaultBlockState().setValue(TripWireHookBlock.FACING, back));
		for (int i = 2; i <= length - 3; i++) {
			plan.add(positions.get(i));
			states.add(wire);
		}
		brain.trapPlan = plan;
		brain.trapStates = states;
		brain.trapIndex = 0;
	}

	/** {@code length} open floor spots in a row, or null if the line is blocked. */
	private static List<BlockPos> line(ServerLevel level, BlockPos start, Direction dir, int length) {
		List<BlockPos> positions = new ArrayList<>();
		for (int i = 0; i < length; i++) {
			BlockPos p = start.relative(dir, i);
			if (!level.getBlockState(p).canBeReplaced() || !level.getFluidState(p).isEmpty() || !BlockWork.isFloor(level, p.below())) {
				return null;
			}
			positions.add(p);
		}
		return positions;
	}

	private static void load(DispenserBlockEntity dispenser, RandomSource r) {
		float roll = r.nextFloat();
		if (roll < 0.45F) {
			dispenser.insertItem(new ItemStack(Items.ARROW, 32));
		} else if (roll < 0.7F) {
			ItemStack tipped = PotionContents.createItemStack(Items.TIPPED_ARROW, r.nextBoolean() ? Potions.POISON : Potions.SLOWNESS);
			tipped.setCount(16);
			dispenser.insertItem(tipped);
		} else if (roll < 0.9F) {
			dispenser.insertItem(new ItemStack(Items.FIRE_CHARGE, 8));
		} else {
			dispenser.insertItem(new ItemStack(Items.LAVA_BUCKET));
		}
	}
}
