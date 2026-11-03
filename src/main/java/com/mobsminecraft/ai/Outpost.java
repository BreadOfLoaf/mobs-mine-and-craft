package com.mobsminecraft.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;

/**
 * Hostile mobs underground build mining outposts: a mineshaft-style room dug out of the rock (if they
 * have a pickaxe; otherwise they use open cave space). Wood, floor, size, supports, lighting, rails and
 * furniture all vary, so no two look the same.
 */
final class Outpost {
	/** Plan marker meaning "dig this block out". */
	static final BlockState DIG = Blocks.CAVE_AIR.defaultBlockState();

	private static final Block[][] WOODS = {
		{Blocks.OAK_PLANKS, Blocks.OAK_LOG, Blocks.OAK_FENCE},
		{Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_LOG, Blocks.SPRUCE_FENCE},
		{Blocks.DARK_OAK_PLANKS, Blocks.DARK_OAK_LOG, Blocks.DARK_OAK_FENCE},
		{Blocks.BIRCH_PLANKS, Blocks.BIRCH_LOG, Blocks.BIRCH_FENCE},
		{Blocks.JUNGLE_PLANKS, Blocks.JUNGLE_LOG, Blocks.JUNGLE_FENCE},
	};
	private static final Block[] FLOORS = {
		null, null, Blocks.COBBLESTONE, Blocks.STONE_BRICKS, Blocks.SMOOTH_STONE, Blocks.COBBLED_DEEPSLATE,
	};

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
		int hx = 2 + r.nextInt(3);
		int hz = 2 + r.nextInt(2);
		if (r.nextBoolean()) {
			int t = hx;
			hx = hz;
			hz = t;
		}
		int height = 3 + r.nextInt(2);
		Block[] wood = WOODS[r.nextInt(WOODS.length)];
		Block floorChoice = FLOORS[r.nextInt(FLOORS.length)];
		Block floor = floorChoice != null ? floorChoice : wood[0];

		// Rock the mob can't dig must be rare, or there's no room for the outpost here.
		int blocked = 0;
		int volume = 0;
		for (int x = -hx; x <= hx; x++) {
			for (int z = -hz; z <= hz; z++) {
				for (int y = 0; y < height; y++) {
					BlockPos p = c.offset(x, y, z);
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
		if (blocked > volume / 4) {
			return false;
		}

		List<BlockPos> plan = new ArrayList<>();
		List<BlockState> states = new ArrayList<>();
		// 1. Dig out the room.
		for (int y = height - 1; y >= 0; y--) {
			for (int x = -hx; x <= hx; x++) {
				for (int z = -hz; z <= hz; z++) {
					plan.add(c.offset(x, y, z));
					states.add(DIG);
				}
			}
		}
		// 2. Floor.
		for (int x = -hx; x <= hx; x++) {
			for (int z = -hz; z <= hz; z++) {
				plan.add(c.offset(x, -1, z));
				states.add(floor.defaultBlockState());
			}
		}
		// 3. Supports: log pillars (corners, plus mid-wall ones on long rooms) and beams or a full ceiling.
		List<int[]> pillars = new ArrayList<>(List.of(new int[] {-hx, -hz}, new int[] {-hx, hz}, new int[] {hx, -hz}, new int[] {hx, hz}));
		if (hx >= 4) {
			pillars.add(new int[] {0, -hz});
			pillars.add(new int[] {0, hz});
		}
		if (hz >= 4) {
			pillars.add(new int[] {-hx, 0});
			pillars.add(new int[] {hx, 0});
		}
		boolean fencePosts = r.nextFloat() < 0.3F;
		for (int[] pl : pillars) {
			for (int y = 0; y < height - 1; y++) {
				plan.add(c.offset(pl[0], y, pl[1]));
				states.add((fencePosts ? wood[2] : wood[1]).defaultBlockState());
			}
			plan.add(c.offset(pl[0], height - 1, pl[1]));
			states.add(wood[1].defaultBlockState());
		}
		boolean fullCeiling = r.nextFloat() < 0.3F;
		for (int x = -hx; x <= hx; x++) {
			for (int z = -hz; z <= hz; z++) {
				boolean edge = Math.abs(x) == hx || Math.abs(z) == hz;
				if (edge || fullCeiling) {
					plan.add(c.offset(x, height - 1, z));
					states.add(wood[0].defaultBlockState());
				}
			}
		}
		// 4. Rails down the long axis (most of the time).
		boolean alongX = hx >= hz;
		boolean rails = r.nextFloat() < 0.7F;
		if (rails) {
			int len = alongX ? hx : hz;
			for (int i = -len + 1; i < len; i++) {
				plan.add(alongX ? c.offset(i, 0, 0) : c.offset(0, 0, i));
				states.add(Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, alongX ? RailShape.EAST_WEST : RailShape.NORTH_SOUTH));
			}
		}
		// 5. Furniture in random spots along the walls.
		List<BlockPos> spots = new ArrayList<>();
		for (int x = -hx + 1; x < hx; x++) {
			for (int z = -hz + 1; z < hz; z++) {
				boolean onRail = rails && (alongX ? z == 0 : x == 0);
				boolean byWall = Math.abs(x) == hx - 1 || Math.abs(z) == hz - 1;
				if (byWall && !onRail && !(x == 0 && z == 0)) {
					spots.add(c.offset(x, 0, z));
				}
			}
		}
		Collections.shuffle(spots, new java.util.Random(r.nextLong()));
		List<BlockState> pieces = new ArrayList<>();
		pieces.add(Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.Plane.HORIZONTAL.getRandomDirection(r)));
		if (r.nextFloat() < 0.8F) pieces.add(Blocks.CRAFTING_TABLE.defaultBlockState());
		if (r.nextFloat() < 0.7F) pieces.add(Blocks.FURNACE.defaultBlockState().setValue(AbstractFurnaceBlock.FACING, Direction.Plane.HORIZONTAL.getRandomDirection(r)));
		if (r.nextFloat() < 0.3F) pieces.add(Blocks.FURNACE.defaultBlockState().setValue(AbstractFurnaceBlock.FACING, Direction.Plane.HORIZONTAL.getRandomDirection(r)));
		if (r.nextFloat() < 0.4F) pieces.add(Blocks.BARREL.defaultBlockState());
		if (r.nextFloat() < 0.25F) pieces.add(Blocks.ANVIL.defaultBlockState().setValue(AnvilBlock.FACING, Direction.Plane.HORIZONTAL.getRandomDirection(r)));
		if (r.nextFloat() < 0.25F) pieces.add(Blocks.SMITHING_TABLE.defaultBlockState());
		if (r.nextFloat() < 0.2F) pieces.add(Blocks.TNT.defaultBlockState());
		if (r.nextFloat() < 0.3F) pieces.add(Blocks.COBWEB.defaultBlockState());
		if (r.nextFloat() < 0.3F) pieces.add(Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.Plane.HORIZONTAL.getRandomDirection(r)));
		Block light = r.nextBoolean() ? Blocks.LANTERN : Blocks.TORCH;
		int lights = 1 + r.nextInt(3);
		for (int i = 0; i < lights; i++) {
			pieces.add(light.defaultBlockState());
		}
		int n = Math.min(pieces.size(), spots.size());
		for (int i = 0; i < n; i++) {
			plan.add(spots.get(i));
			states.add(pieces.get(i));
		}

		brain.housePlan = plan;
		brain.houseStates = states;
		brain.houseIndex = 0;
		brain.houseCenter = c;
		brain.buildInterval = 3;
		brain.buildingOutpost = true;
		return true;
	}
}
