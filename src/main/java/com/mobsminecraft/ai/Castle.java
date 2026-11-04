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
 * A small stone fort a Juggernaut builds for itself: walls (7x7 up to 13x13) with crenellations, corner towers,
 * a gate, a red carpet up to a throne, and a crafting table and furnace inside.
 */
final class Castle {
	private Castle() {}

	static void plan(SmartBrain brain, ServerLevel level) {
		BlockPos c = brain.mob.blockPosition();
		RandomSource r = brain.mob.getRandom();
		// Size and material vary from castle to castle.
		int wallHeight = 3 + r.nextInt(3);
		BlockState wall = wallBlock(r);
		// Try the size it wants first, then smaller forts if the ground is too rough.
		for (int radius = 3 + r.nextInt(4); radius >= 3; radius--) {
			if (fits(level, c, radius, wallHeight)) {
				plan(brain, c, r, radius, wallHeight, wall);
				return;
			}
		}
	}

	private static boolean fits(ServerLevel level, BlockPos c, int radius, int wallHeight) {
		int bad = 0;
		for (int x = -radius; x <= radius; x++) {
			for (int z = -radius; z <= radius; z++) {
				if (!isPerimeter(x, z, radius)) {
					continue;
				}
				if (!BlockWork.isFloor(level, c.offset(x, -1, z))) {
					bad++;
				}
				for (int y = 0; y < wallHeight; y++) {
					if (!level.getBlockState(c.offset(x, y, z)).canBeReplaced()) {
						bad++;
					}
				}
			}
		}
		return bad <= 8 * radius * wallHeight / 5;
	}

	private static void plan(SmartBrain brain, BlockPos c, RandomSource r, int radius, int wallHeight, BlockState wall) {
		ServerLevel level = (ServerLevel) brain.mob.level();

		Direction gate = Direction.Plane.HORIZONTAL.getRandomDirection(r);
		Direction back = gate.getOpposite();
		Direction side = gate.getClockWise();
		List<BlockPos> plan = new ArrayList<>();
		List<BlockState> states = new ArrayList<>();

		// Foundations where the ground dips.
		for (int x = -radius; x <= radius; x++) {
			for (int z = -radius; z <= radius; z++) {
				if (isPerimeter(x, z, radius) && !BlockWork.isFloor(level, c.offset(x, -1, z))) {
					plan.add(c.offset(x, -1, z));
					states.add(Blocks.COBBLESTONE.defaultBlockState());
				}
			}
		}
		// Walls, laid course by course so the castle visibly rises.
		for (int y = 0; y < wallHeight; y++) {
			for (int x = -radius; x <= radius; x++) {
				for (int z = -radius; z <= radius; z++) {
					if (!isPerimeter(x, z, radius)) {
						continue;
					}
					boolean gateway = y <= 2 && x == gate.getStepX() * radius && z == gate.getStepZ() * radius;
					if (!gateway) {
						plan.add(c.offset(x, y, z));
						states.add(wall);
					}
				}
			}
		}
		// Crenellations on top, and taller corner towers.
		for (int x = -radius; x <= radius; x++) {
			for (int z = -radius; z <= radius; z++) {
				if (isPerimeter(x, z, radius) && (x + z) % 2 == 0) {
					plan.add(c.offset(x, wallHeight, z));
					states.add(Blocks.STONE_BRICKS.defaultBlockState());
				}
			}
		}
		for (int x = -radius; x <= radius; x += 2 * radius) {
			for (int z = -radius; z <= radius; z += 2 * radius) {
				for (int y = wallHeight + 1; y <= wallHeight + 2; y++) {
					plan.add(c.offset(x, y, z));
					states.add(Blocks.COBBLESTONE.defaultBlockState());
				}
			}
		}
		// Red carpet from the gate to the throne.
		for (int k = radius - 1; k >= -1; k--) {
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
		plan.add(c.relative(side, radius - 1));
		states.add(Blocks.CRAFTING_TABLE.defaultBlockState());
		plan.add(c.relative(side.getOpposite(), radius - 1));
		states.add(Blocks.FURNACE.defaultBlockState().setValue(AbstractFurnaceBlock.FACING, side).setValue(AbstractFurnaceBlock.LIT, true));

		brain.housePlan = plan;
		brain.houseStates = states;
		brain.houseIndex = 0;
		brain.houseCenter = c;
		brain.buildInterval = 2;
	}

	private static boolean isPerimeter(int x, int z, int radius) {
		return Math.abs(x) == radius || Math.abs(z) == radius;
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
