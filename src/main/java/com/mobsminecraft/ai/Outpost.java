package com.mobsminecraft.ai;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;

/**
 * Hostile mobs underground build mining outposts: a mineshaft-style room dug out of the rock (if they
 * have a pickaxe; otherwise they use open cave space), with a plank floor, log pillars and beams,
 * lanterns, a rail line, a crafting table, a loaded furnace and a supply chest. Size varies each time.
 */
final class Outpost {
	/** Plan marker meaning "dig this block out". */
	static final BlockState DIG = Blocks.CAVE_AIR.defaultBlockState();

	private Outpost() {}

	static boolean isUnderground(ServerLevel level, Mob mob) {
		BlockPos pos = mob.blockPosition();
		return level.getBrightness(LightLayer.SKY, pos) == 0 && pos.getY() < level.getSeaLevel();
	}

	/** Plans an outpost at the mob's position. Returns false if the spot is unsuitable. */
	static boolean plan(SmartBrain brain, ServerLevel level) {
		Mob mob = brain.mob;
		RandomSource r = mob.getRandom();
		BlockPos c = mob.blockPosition();
		int half = 2 + r.nextInt(2);
		int height = 3 + r.nextInt(2);

		// Rock the mob can't dig must be rare, or there's no room for the outpost here.
		int blocked = 0;
		int volume = 0;
		for (int x = -half; x <= half; x++) {
			for (int z = -half; z <= half; z++) {
				for (int y = 0; y < height; y++) {
					BlockPos p = c.offset(x, y, z);
					BlockState s = level.getBlockState(p);
					volume++;
					if (!s.canBeReplaced() && !BlockWork.canMine(mob, level, p, s)) {
						blocked++;
					}
					if (!s.getFluidState().isEmpty()) {
						return false;
					}
				}
			}
		}
		if (blocked > volume / 4) {
			return false;
		}

		List<BlockPos> plan = new ArrayList<>();
		List<BlockState> states = new ArrayList<>();
		// 1. Dig out the room.
		for (int y = height - 1; y >= 0; y--) {
			for (int x = -half; x <= half; x++) {
				for (int z = -half; z <= half; z++) {
					plan.add(c.offset(x, y, z));
					states.add(DIG);
				}
			}
		}
		// 2. Plank floor (replacing the rock underfoot).
		for (int x = -half; x <= half; x++) {
			for (int z = -half; z <= half; z++) {
				plan.add(c.offset(x, -1, z));
				states.add(Blocks.OAK_PLANKS.defaultBlockState());
			}
		}
		// 3. Log pillars in the corners and plank beams along the ceiling edges.
		for (int x = -half; x <= half; x += 2 * half) {
			for (int z = -half; z <= half; z += 2 * half) {
				for (int y = 0; y < height; y++) {
					plan.add(c.offset(x, y, z));
					states.add(Blocks.OAK_LOG.defaultBlockState());
				}
			}
		}
		for (int i = -half + 1; i < half; i++) {
			plan.add(c.offset(i, height - 1, -half));
			states.add(Blocks.OAK_PLANKS.defaultBlockState());
			plan.add(c.offset(i, height - 1, half));
			states.add(Blocks.OAK_PLANKS.defaultBlockState());
			plan.add(c.offset(-half, height - 1, i));
			states.add(Blocks.OAK_PLANKS.defaultBlockState());
			plan.add(c.offset(half, height - 1, i));
			states.add(Blocks.OAK_PLANKS.defaultBlockState());
		}
		// 4. Furnishings: a rail line down the middle, lanterns, crafting table, furnace, supply chest.
		for (int x = -half + 1; x < half; x++) {
			plan.add(c.offset(x, 0, 0));
			states.add(Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, RailShape.EAST_WEST));
		}
		int in = half - 1;
		plan.add(c.offset(in, 0, -in));
		states.add(Blocks.CRAFTING_TABLE.defaultBlockState());
		plan.add(c.offset(-in, 0, -in));
		states.add(Blocks.FURNACE.defaultBlockState().setValue(AbstractFurnaceBlock.FACING, Direction.SOUTH));
		plan.add(c.offset(in, 0, in));
		states.add(Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH));
		plan.add(c.offset(-in, 0, in));
		states.add(Blocks.LANTERN.defaultBlockState());
		plan.add(c.offset(0, 0, -in));
		states.add(Blocks.LANTERN.defaultBlockState());

		brain.housePlan = plan;
		brain.houseStates = states;
		brain.houseIndex = 0;
		brain.houseCenter = c;
		brain.buildInterval = 3;
		brain.buildingOutpost = true;
		return true;
	}
}
