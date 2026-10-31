package com.mobsminecraft.ai;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A small stone fort a Juggernaut builds for itself: 9x9 walls with crenellations, corner towers,
 * a gate, a red carpet up to a throne, and a crafting table and furnace inside.
 */
final class Castle {
	private static final int R = 4;
	private static final int WALL_HEIGHT = 4;

	private Castle() {}

	static void plan(SmartBrain brain, ServerLevel level) {
		BlockPos c = brain.mob.blockPosition();
		RandomSource r = brain.mob.getRandom();

		int bad = 0;
		for (int x = -R; x <= R; x++) {
			for (int z = -R; z <= R; z++) {
				if (!isPerimeter(x, z)) {
					continue;
				}
				if (!BlockWork.isFloor(level, c.offset(x, -1, z))) {
					bad++;
				}
				for (int y = 0; y < WALL_HEIGHT; y++) {
					if (!level.getBlockState(c.offset(x, y, z)).canBeReplaced()) {
						bad++;
					}
				}
			}
		}
		if (bad > 20) {
			return;
		}

		Direction gate = Direction.Plane.HORIZONTAL.getRandomDirection(r);
		Direction back = gate.getOpposite();
		Direction side = gate.getClockWise();
		List<BlockPos> plan = new ArrayList<>();
		List<BlockState> states = new ArrayList<>();

		// Foundations where the ground dips.
		for (int x = -R; x <= R; x++) {
			for (int z = -R; z <= R; z++) {
				if (isPerimeter(x, z) && !BlockWork.isFloor(level, c.offset(x, -1, z))) {
					plan.add(c.offset(x, -1, z));
					states.add(Blocks.COBBLESTONE.defaultBlockState());
				}
			}
		}
		// Walls, laid course by course so the castle visibly rises.
		for (int y = 0; y < WALL_HEIGHT; y++) {
			for (int x = -R; x <= R; x++) {
				for (int z = -R; z <= R; z++) {
					if (!isPerimeter(x, z)) {
						continue;
					}
					boolean gateway = y <= 2 && x == gate.getStepX() * R && z == gate.getStepZ() * R;
					if (!gateway) {
						plan.add(c.offset(x, y, z));
						states.add(wallBlock(r));
					}
				}
			}
		}
		// Crenellations on top, and taller corner towers.
		for (int x = -R; x <= R; x++) {
			for (int z = -R; z <= R; z++) {
				if (isPerimeter(x, z) && (x + z) % 2 == 0) {
					plan.add(c.offset(x, WALL_HEIGHT, z));
					states.add(Blocks.STONE_BRICKS.defaultBlockState());
				}
			}
		}
		for (int x = -R; x <= R; x += 2 * R) {
			for (int z = -R; z <= R; z += 2 * R) {
				for (int y = WALL_HEIGHT + 1; y <= WALL_HEIGHT + 2; y++) {
					plan.add(c.offset(x, y, z));
					states.add(Blocks.COBBLESTONE.defaultBlockState());
				}
			}
		}
		// Red carpet from the gate to the throne.
		for (int k = R - 1; k >= -1; k--) {
			plan.add(c.relative(gate, k));
			states.add(Blocks.CARPET.pick(DyeColor.RED).defaultBlockState());
		}
		BlockPos throne = c.relative(back, 2);
		plan.add(throne);
		states.add(Blocks.STONE_BRICK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, back));
		plan.add(throne.relative(side));
		states.add(Blocks.TORCH.defaultBlockState());
		plan.add(throne.relative(side.getOpposite()));
		states.add(Blocks.TORCH.defaultBlockState());
		plan.add(c.relative(side, R - 1));
		states.add(Blocks.CRAFTING_TABLE.defaultBlockState());
		plan.add(c.relative(side.getOpposite(), R - 1));
		states.add(Blocks.FURNACE.defaultBlockState().setValue(AbstractFurnaceBlock.FACING, side).setValue(AbstractFurnaceBlock.LIT, true));

		brain.housePlan = plan;
		brain.houseStates = states;
		brain.houseIndex = 0;
		brain.houseCenter = c;
		brain.buildInterval = 3;
	}

	private static boolean isPerimeter(int x, int z) {
		return Math.abs(x) == R || Math.abs(z) == R;
	}

	private static BlockState wallBlock(RandomSource r) {
		float f = r.nextFloat();
		if (f < 0.6F) {
			return Blocks.STONE_BRICKS.defaultBlockState();
		} else if (f < 0.85F) {
			return Blocks.COBBLESTONE.defaultBlockState();
		}
		return Blocks.MOSSY_STONE_BRICKS.defaultBlockState();
	}
}
