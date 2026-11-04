package com.mobsminecraft.ai;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;

/** What mobs build below ground: mining outposts, cave houses and mineshaft corridors. */
final class Underground {
	private static final Block[][] WOODS = {
		{Blocks.OAK_PLANKS, Blocks.OAK_FENCE},
		{Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_FENCE},
		{Blocks.DARK_OAK_PLANKS, Blocks.DARK_OAK_FENCE},
	};

	private Underground() {}

	/** Picks and plans an underground build at the mob's position. Returns false if nothing fits here. */
	static boolean plan(SmartBrain brain, ServerLevel level) {
		Mob mob = brain.mob;
		if (Houses.tooCrowded(level, mob.blockPosition())) {
			return false;
		}
		RandomSource r = mob.getRandom();
		if (brain.isPassive()) {
			return Houses.plan(brain, level, Houses.randomCave(r), true);
		}
		int roll = r.nextInt(100);
		if (roll < 35) {
			return mineshaft(brain, level);
		} else if (roll < 70) {
			return Outpost.plan(brain, level);
		}
		return Houses.plan(brain, level, Houses.randomCave(r), true);
	}

	/**
	 * A 3-wide, 3-tall corridor dug straight into the rock, with plank floor patches over gaps,
	 * fence-and-beam supports every four blocks, a rail line, torches, cobwebs and a supply chest at the end.
	 */
	static boolean mineshaft(SmartBrain brain, ServerLevel level) {
		Mob mob = brain.mob;
		RandomSource r = mob.getRandom();
		BlockPos c = mob.blockPosition();
		Direction dir = Direction.Plane.HORIZONTAL.getRandomDirection(r);
		Direction side = dir.getClockWise();
		int len = 10 + r.nextInt(12);

		int blocked = 0;
		int volume = 0;
		for (int i = 1; i <= len; i++) {
			for (int w = -1; w <= 1; w++) {
				for (int y = 0; y <= 2; y++) {
					BlockPos p = c.relative(dir, i).relative(side, w).above(y);
					BlockState s = level.getBlockState(p);
					volume++;
					if (!s.getFluidState().isEmpty()) {
						return false;
					}
					if (!s.canBeReplaced() && !BlockWork.canMine(mob, level, p, s)) {
						blocked++;
					}
				}
			}
		}
		if (blocked > volume * 0.15) {
			return false;
		}

		Block[] wood = WOODS[r.nextInt(WOODS.length)];
		RailShape railShape = dir.getAxis() == Direction.Axis.X ? RailShape.EAST_WEST : RailShape.NORTH_SOUTH;
		List<BlockPos> plan = new ArrayList<>();
		List<BlockState> states = new ArrayList<>();
		for (int i = 1; i <= len; i++) {
			BlockPos row = c.relative(dir, i);
			for (int y = 2; y >= 0; y--) {
				for (int w = -1; w <= 1; w++) {
					plan.add(row.relative(side, w).above(y));
					states.add(Outpost.DIG);
				}
			}
			for (int w = -1; w <= 1; w++) {
				plan.add(row.relative(side, w).below());
				states.add(wood[0].defaultBlockState());
			}
			if (i % 4 == 0) {
				for (int y = 0; y <= 1; y++) {
					plan.add(row.relative(side, -1).above(y));
					states.add(wood[1].defaultBlockState());
					plan.add(row.relative(side, 1).above(y));
					states.add(wood[1].defaultBlockState());
				}
				for (int w = -1; w <= 1; w++) {
					plan.add(row.relative(side, w).above(2));
					states.add(wood[0].defaultBlockState());
				}
			} else {
				if (i % 4 == 2) {
					plan.add(row.relative(side, r.nextBoolean() ? 1 : -1));
					states.add(Blocks.TORCH.defaultBlockState());
				}
				if (r.nextFloat() < 0.15F) {
					plan.add(row.relative(side, r.nextBoolean() ? 1 : -1).above(2));
					states.add(Blocks.COBWEB.defaultBlockState());
				}
			}
			plan.add(row);
			states.add(Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, railShape));
		}
		BlockPos end = c.relative(dir, len + 1);
		plan.add(end);
		states.add(Outpost.DIG);
		plan.add(end.above());
		states.add(Outpost.DIG);
		plan.add(end);
		states.add(r.nextBoolean()
			? Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, dir.getOpposite())
			: Blocks.BARREL.defaultBlockState());

		brain.housePlan = plan;
		brain.houseStates = states;
		brain.houseIndex = 0;
		brain.houseCenter = c;
		brain.buildInterval = 2;
		brain.buildingOutpost = true;
		return true;
	}
}
