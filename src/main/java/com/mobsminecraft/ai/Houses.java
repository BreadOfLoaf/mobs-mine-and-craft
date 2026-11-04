package com.mobsminecraft.ai;

import com.mobsminecraft.world.MobBuilt;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.AbstractBedBlock;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * Mob houses with real variety: a material palette, a rectangular footprint, wall height,
 * one of three roof styles, a door, windows, furniture, and optional chimney, path and flowers.
 */
final class Houses {
	private Houses() {}

	/** Materials for one house. {@code stairs}, {@code door} and {@code window} may be null. */
	record Palette(Block wall, Block corner, Block roof, Block stairs, Block floor, Block door, Block window) {}

	enum Roof { FLAT, GABLE, PYRAMID }

	private static Palette wood(Block planks, Block log, Block stairs, Block door) {
		return new Palette(planks, log, planks, stairs, planks, door, Blocks.GLASS_PANE);
	}

	private static final Palette OAK = wood(Blocks.OAK_PLANKS, Blocks.OAK_LOG, Blocks.SPRUCE_STAIRS, Blocks.OAK_DOOR);
	private static final Palette SPRUCE = wood(Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_LOG, Blocks.DARK_OAK_STAIRS, Blocks.SPRUCE_DOOR);
	private static final Palette BIRCH = wood(Blocks.BIRCH_PLANKS, Blocks.BIRCH_LOG, Blocks.OAK_STAIRS, Blocks.BIRCH_DOOR);
	private static final Palette JUNGLE = wood(Blocks.JUNGLE_PLANKS, Blocks.JUNGLE_LOG, Blocks.JUNGLE_STAIRS, Blocks.JUNGLE_DOOR);
	private static final Palette ACACIA = wood(Blocks.ACACIA_PLANKS, Blocks.ACACIA_LOG, Blocks.ACACIA_STAIRS, Blocks.ACACIA_DOOR);
	private static final Palette DARK_OAK = wood(Blocks.DARK_OAK_PLANKS, Blocks.DARK_OAK_LOG, Blocks.SPRUCE_STAIRS, Blocks.DARK_OAK_DOOR);
	private static final Palette CHERRY = wood(Blocks.CHERRY_PLANKS, Blocks.CHERRY_LOG, Blocks.CHERRY_STAIRS, Blocks.CHERRY_DOOR);

	private static final Palette[] ALL = {
		OAK, SPRUCE, BIRCH, JUNGLE, ACACIA, DARK_OAK, CHERRY,
		new Palette(Blocks.COBBLESTONE, Blocks.OAK_LOG, Blocks.STONE_BRICKS, Blocks.STONE_BRICK_STAIRS, Blocks.OAK_PLANKS, Blocks.OAK_DOOR, Blocks.GLASS_PANE),
		new Palette(Blocks.BRICKS, Blocks.STONE_BRICKS, Blocks.DARK_OAK_PLANKS, Blocks.DARK_OAK_STAIRS, Blocks.SPRUCE_PLANKS, Blocks.DARK_OAK_DOOR, Blocks.GLASS_PANE),
		new Palette(Blocks.MUD_BRICKS, Blocks.OAK_LOG, Blocks.MUD_BRICKS, Blocks.MUD_BRICK_STAIRS, Blocks.OAK_PLANKS, Blocks.OAK_DOOR, Blocks.GLASS_PANE),
		new Palette(Blocks.SANDSTONE, Blocks.CUT_SANDSTONE, Blocks.SANDSTONE, Blocks.SANDSTONE_STAIRS, Blocks.BIRCH_PLANKS, Blocks.JUNGLE_DOOR, Blocks.GLASS_PANE),
		new Palette(Blocks.COBBLED_DEEPSLATE, Blocks.POLISHED_DEEPSLATE, Blocks.DEEPSLATE_TILES, Blocks.DEEPSLATE_TILE_STAIRS, Blocks.DARK_OAK_PLANKS, Blocks.DARK_OAK_DOOR, Blocks.GLASS_PANE),
		// A sad little dirt shack: no door, no windows, no floor, flat dirt roof.
		new Palette(Blocks.DIRT, Blocks.DIRT, Blocks.DIRT, null, null, null, null),
	};

	static Palette random(RandomSource r) {
		return ALL[r.nextInt(ALL.length)];
	}

	/** Underground houses: stone and deepslate, iron-bar windows. */
	private static final Palette[] CAVE = {
		new Palette(Blocks.COBBLED_DEEPSLATE, Blocks.POLISHED_DEEPSLATE, Blocks.DEEPSLATE_TILES, null, Blocks.DEEPSLATE_TILES, Blocks.DARK_OAK_DOOR, Blocks.IRON_BARS),
		new Palette(Blocks.STONE_BRICKS, Blocks.CHISELED_STONE_BRICKS, Blocks.STONE_BRICKS, null, Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_DOOR, Blocks.IRON_BARS),
		new Palette(Blocks.MOSSY_COBBLESTONE, Blocks.COBBLESTONE, Blocks.COBBLESTONE, null, Blocks.OAK_PLANKS, Blocks.OAK_DOOR, Blocks.IRON_BARS),
		new Palette(Blocks.TUFF_BRICKS, Blocks.POLISHED_TUFF, Blocks.TUFF_BRICKS, null, Blocks.POLISHED_TUFF, Blocks.SPRUCE_DOOR, Blocks.IRON_BARS),
		new Palette(Blocks.POLISHED_ANDESITE, Blocks.STONE_BRICKS, Blocks.SMOOTH_STONE, null, Blocks.DARK_OAK_PLANKS, Blocks.DARK_OAK_DOOR, Blocks.IRON_BARS),
	};

	static Palette randomCave(RandomSource r) {
		return CAVE[r.nextInt(CAVE.length)];
	}

	/** True if another mob build (a door, bed, rail, lantern or campfire) is within 14 blocks. */
	static boolean tooCrowded(ServerLevel level, BlockPos pos) {
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int x = -14; x <= 14; x++) {
			for (int z = -14; z <= 14; z++) {
				for (int y = -4; y <= 6; y++) {
					p.set(pos.getX() + x, pos.getY() + y, pos.getZ() + z);
					BlockState s = level.getBlockState(p);
					if ((s.is(net.minecraft.tags.BlockTags.DOORS) || s.is(net.minecraft.tags.BlockTags.BEDS) || s.is(Blocks.RAIL)
						|| s.is(Blocks.LANTERN) || s.is(Blocks.CAMPFIRE)) && MobBuilt.contains(level, p)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/** The house style for the wood a mob chopped, so passive mobs build with what they gathered. */
	static Palette forLog(Block log) {
		if (log == Blocks.SPRUCE_LOG) return SPRUCE;
		if (log == Blocks.BIRCH_LOG) return BIRCH;
		if (log == Blocks.JUNGLE_LOG) return JUNGLE;
		if (log == Blocks.ACACIA_LOG) return ACACIA;
		if (log == Blocks.DARK_OAK_LOG) return DARK_OAK;
		if (log == Blocks.CHERRY_LOG) return CHERRY;
		return OAK;
	}

	/**
	 * Plans a house at the mob's position. Surface houses ({@code cave} false) are only built where the sky
	 * is visible; cave houses are dug out of the rock (if the mob can) with a flat ceiling. Returns false
	 * if the spot is unsuitable.
	 */
	static boolean plan(SmartBrain brain, ServerLevel level, Palette p, boolean cave) {
		Mob mob = brain.mob;
		RandomSource r = mob.getRandom();
		BlockPos c = mob.blockPosition();
		if (cave != Outpost.isUnderground(level, mob) || tooCrowded(level, c)) {
			return false;
		}
		int hx = 2 + r.nextInt(cave ? 2 : 4);
		int hz = 2 + r.nextInt(cave ? 2 : 3);
		if (r.nextBoolean()) {
			int t = hx;
			hx = hz;
			hz = t;
		}
		int wallH = cave ? 3 + r.nextInt(2) : 3 + r.nextInt(3);
		Roof roof = cave || p.stairs() == null ? Roof.FLAT : switch (r.nextInt(20)) {
			case 0, 1, 2, 3 -> Roof.FLAT;
			case 4, 5, 6, 7, 8, 9, 10, 11 -> Roof.GABLE;
			default -> Roof.PYRAMID;
		};

		// The site must be mostly flat and open, and must not overlap anything mobs already built.
		int obstacles = 0;
		for (int x = -hx - 1; x <= hx + 1; x++) {
			for (int z = -hz - 1; z <= hz + 1; z++) {
				for (int y = -1; y <= wallH; y++) {
					if (MobBuilt.contains(level, c.offset(x, y, z))) {
						return false;
					}
				}
				if (Math.abs(x) > hx || Math.abs(z) > hz) {
					continue;
				}
				if (!BlockWork.isFloor(level, c.offset(x, -1, z))) {
					obstacles++;
				}
				for (int y = 0; y < wallH; y++) {
					BlockPos q = c.offset(x, y, z);
					BlockState s = level.getBlockState(q);
					boolean diggable = cave && BlockWork.canMine(mob, level, q, s);
					if (!s.canBeReplaced() && !diggable) {
						obstacles++;
					}
					if (cave && !s.getFluidState().isEmpty()) {
						return false;
					}
				}
			}
		}
		if (obstacles > hx + hz) {
			return false;
		}

		List<BlockPos> plan = new ArrayList<>();
		List<BlockState> states = new ArrayList<>();
		if (cave) {
			// Dig the room out first (top down).
			for (int y = wallH - 1; y >= 0; y--) {
				for (int x = -hx; x <= hx; x++) {
					for (int z = -hz; z <= hz; z++) {
						plan.add(c.offset(x, y, z));
						states.add(Outpost.DIG);
					}
				}
			}
		}
		Direction doorSide = Direction.Plane.HORIZONTAL.getRandomDirection(r);
		int doorX = doorSide.getStepX() * hx;
		int doorZ = doorSide.getStepZ() * hz;
		Block window = p.window() == null ? null
			: r.nextFloat() < 0.25F ? Blocks.STAINED_GLASS_PANE.pick(DyeColor.values()[r.nextInt(DyeColor.values().length)]) : p.window();

		// Floor.
		if (p.floor() != null) {
			for (int x = -hx + 1; x < hx; x++) {
				for (int z = -hz + 1; z < hz; z++) {
					plan.add(c.offset(x, -1, z));
					states.add(p.floor().defaultBlockState());
				}
			}
		}
		// Walls, with a door and windows.
		for (int y = 0; y < wallH; y++) {
			for (int x = -hx; x <= hx; x++) {
				for (int z = -hz; z <= hz; z++) {
					if (Math.abs(x) != hx && Math.abs(z) != hz) {
						continue;
					}
					boolean corner = Math.abs(x) == hx && Math.abs(z) == hz;
					if (x == doorX && z == doorZ && y <= 1) {
						if (y == 0 && p.door() != null) {
							plan.add(c.offset(x, 0, z));
							states.add(p.door().defaultBlockState().setValue(DoorBlock.FACING, doorSide.getOpposite())
								.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
						}
						continue;
					}
					BlockState s = corner ? p.corner().defaultBlockState() : p.wall().defaultBlockState();
					boolean windowRow = y == 1 || (y == 2 && wallH >= 5);
					if (window != null && !corner && windowRow && isWindowSpot(x, z, hx, hz, doorX, doorZ)) {
						s = window.defaultBlockState();
					}
					plan.add(c.offset(x, y, z));
					states.add(s);
				}
			}
		}

		// Roof (planned separately so a chimney can go in first and poke through it).
		List<BlockPos> roofPlan = new ArrayList<>();
		List<BlockState> roofStates = new ArrayList<>();
		int top = switch (roof) {
			case FLAT -> flatRoof(c, hx, hz, wallH, p, roofPlan, roofStates);
			case GABLE -> gableRoof(c, hx, hz, wallH, p, roofPlan, roofStates);
			case PYRAMID -> pyramidRoof(c, hx, hz, wallH, p, roofPlan, roofStates);
		};
		if (!cave && r.nextFloat() < 0.35F && p.stairs() != null) {
			int cx = hx - 1;
			int cz = -(hz - 1);
			for (int y = wallH - 1; y <= top + 1; y++) {
				plan.add(c.offset(cx, y, cz));
				states.add(Blocks.COBBLESTONE.defaultBlockState());
			}
			plan.add(c.offset(cx, top + 2, cz));
			states.add(Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true));
		}
		plan.addAll(roofPlan);
		states.addAll(roofStates);

		furnish(c, hx, hz, doorX, doorZ, doorSide, r, plan, states, p, wallH);

		// Outside: a dirt path from the door and some flowers.
		BlockPos doorPos = c.offset(doorX, 0, doorZ);
		if (!cave && r.nextFloat() < 0.5F) {
			int len = 2 + r.nextInt(4);
			for (int i = 1; i <= len; i++) {
				plan.add(doorPos.relative(doorSide, i).below());
				states.add(Blocks.DIRT_PATH.defaultBlockState());
			}
		}
		if (!cave && r.nextFloat() < 0.4F) {
			Direction side = doorSide.getClockWise();
			Block flower = r.nextBoolean() ? Blocks.POPPY : Blocks.DANDELION;
			plan.add(doorPos.relative(doorSide).relative(side, 2));
			states.add(flower.defaultBlockState());
			plan.add(doorPos.relative(doorSide).relative(side, -2));
			states.add(flower.defaultBlockState());
		}

		brain.housePlan = plan;
		brain.houseStates = states;
		brain.houseIndex = 0;
		brain.houseCenter = c;
		brain.buildInterval = 2;
		return true;
	}

	private static boolean isWindowSpot(int x, int z, int hx, int hz, int doorX, int doorZ) {
		if (Math.abs(z) == hz) {
			return Math.abs(x) < hx && Math.floorMod(x, 2) == 0 && !(z == doorZ && Math.abs(x - doorX) <= 1);
		}
		return Math.abs(z) < hz && Math.floorMod(z, 2) == 0 && !(x == doorX && Math.abs(z - doorZ) <= 1);
	}

	private static int flatRoof(BlockPos c, int hx, int hz, int wallH, Palette p, List<BlockPos> plan, List<BlockState> states) {
		for (int x = -hx; x <= hx; x++) {
			for (int z = -hz; z <= hz; z++) {
				plan.add(c.offset(x, wallH, z));
				states.add(p.roof().defaultBlockState());
			}
		}
		return wallH;
	}

	/** Two sloped sides of stairs meeting at a ridge along the longer axis, with filled gable ends. */
	private static int gableRoof(BlockPos c, int hx, int hz, int wallH, Palette p, List<BlockPos> plan, List<BlockState> states) {
		boolean alongX = hx >= hz;
		int ha = alongX ? hx : hz;
		int hb = alongX ? hz : hx;
		Direction rise = alongX ? Direction.SOUTH : Direction.EAST;
		int top = wallH;
		for (int k = 0; ; k++) {
			int lo = -(hb + 1) + k;
			int hi = (hb + 1) - k;
			int y = wallH + k;
			if (lo > hi) {
				break;
			}
			top = y;
			for (int a = -ha - 1; a <= ha + 1; a++) {
				if (lo == hi) {
					plan.add(at(c, alongX, a, y, lo));
					states.add(p.roof().defaultBlockState());
				} else {
					plan.add(at(c, alongX, a, y, lo));
					states.add(p.stairs().defaultBlockState().setValue(StairBlock.FACING, rise));
					plan.add(at(c, alongX, a, y, hi));
					states.add(p.stairs().defaultBlockState().setValue(StairBlock.FACING, rise.getOpposite()));
				}
			}
			if (lo == hi) {
				break;
			}
			for (int b = lo + 1; b <= hi - 1; b++) {
				plan.add(at(c, alongX, -ha, y, b));
				states.add(p.wall().defaultBlockState());
				plan.add(at(c, alongX, ha, y, b));
				states.add(p.wall().defaultBlockState());
			}
		}
		return top;
	}

	private static BlockPos at(BlockPos c, boolean alongX, int a, int y, int b) {
		return alongX ? c.offset(a, y, b) : c.offset(b, y, a);
	}

	/** Rings of stairs stepping inward on all four sides, capped with full blocks. */
	private static int pyramidRoof(BlockPos c, int hx, int hz, int wallH, Palette p, List<BlockPos> plan, List<BlockState> states) {
		int top = wallH;
		for (int k = 0; ; k++) {
			int ex = hx + 1 - k;
			int ez = hz + 1 - k;
			int y = wallH + k;
			top = y;
			if (ex <= 0 || ez <= 0) {
				for (int x = -Math.max(ex, 0); x <= Math.max(ex, 0); x++) {
					for (int z = -Math.max(ez, 0); z <= Math.max(ez, 0); z++) {
						plan.add(c.offset(x, y, z));
						states.add(p.roof().defaultBlockState());
					}
				}
				break;
			}
			for (int x = -ex; x <= ex; x++) {
				for (int z = -ez; z <= ez; z++) {
					if (Math.abs(x) != ex && Math.abs(z) != ez) {
						continue;
					}
					Direction facing = z == -ez ? Direction.SOUTH : z == ez ? Direction.NORTH : x == -ex ? Direction.EAST : Direction.WEST;
					plan.add(c.offset(x, y, z));
					states.add(p.stairs().defaultBlockState().setValue(StairBlock.FACING, facing));
				}
			}
		}
		return top;
	}

	/** Room themes: each one is a set of furniture that belongs together. */
	enum Theme { BEDROOM, KITCHEN, LIBRARY, WORKSHOP, STORAGE, FARMHOUSE, BREWERY }

	private static List<BlockState> themePieces(Theme theme, Direction face, RandomSource r) {
		List<BlockState> p = new ArrayList<>();
		switch (theme) {
			case BEDROOM -> {
				p.add(Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, face));
				p.add(Blocks.FLOWER_POT.defaultBlockState());
				if (r.nextBoolean()) p.add(Blocks.BOOKSHELF.defaultBlockState());
			}
			case KITCHEN -> {
				p.add(Blocks.FURNACE.defaultBlockState().setValue(AbstractFurnaceBlock.FACING, face));
				p.add(Blocks.SMOKER.defaultBlockState().setValue(AbstractFurnaceBlock.FACING, face));
				p.add(Blocks.BARREL.defaultBlockState());
				p.add(Blocks.CRAFTING_TABLE.defaultBlockState());
				if (r.nextBoolean()) p.add(Blocks.CAULDRON.defaultBlockState());
			}
			case LIBRARY -> {
				int shelves = 2 + r.nextInt(3);
				for (int i = 0; i < shelves; i++) p.add(Blocks.BOOKSHELF.defaultBlockState());
				p.add(Blocks.LECTERN.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, face));
				if (r.nextFloat() < 0.25F) p.add(Blocks.ENCHANTING_TABLE.defaultBlockState());
			}
			case WORKSHOP -> {
				p.add(Blocks.CRAFTING_TABLE.defaultBlockState());
				Block[] tools = {Blocks.SMITHING_TABLE, Blocks.FLETCHING_TABLE, Blocks.STONECUTTER, Blocks.LOOM, Blocks.ANVIL, Blocks.GRINDSTONE};
				int n = 2 + r.nextInt(2);
				for (int i = 0; i < n; i++) {
					Block b = tools[r.nextInt(tools.length)];
					BlockState st = b.defaultBlockState();
					if (b == Blocks.GRINDSTONE) {
						st = st.setValue(net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock.FACE,
							net.minecraft.world.level.block.state.properties.AttachFace.FLOOR);
					}
					p.add(st);
				}
			}
			case STORAGE -> {
				p.add(Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, face));
				p.add(Blocks.BARREL.defaultBlockState());
				p.add(Blocks.BARREL.defaultBlockState());
				if (r.nextBoolean()) p.add(Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, face));
			}
			case FARMHOUSE -> {
				p.add(Blocks.COMPOSTER.defaultBlockState());
				p.add(Blocks.HAY_BLOCK.defaultBlockState());
				if (r.nextBoolean()) p.add(Blocks.HAY_BLOCK.defaultBlockState());
				p.add(Blocks.BARREL.defaultBlockState());
				p.add(Blocks.FLOWER_POT.defaultBlockState());
			}
			case BREWERY -> {
				p.add(Blocks.BREWING_STAND.defaultBlockState());
				p.add(Blocks.CAULDRON.defaultBlockState());
				p.add(Blocks.BARREL.defaultBlockState());
				if (r.nextBoolean()) p.add(Blocks.BOOKSHELF.defaultBlockState());
			}
		}
		return p;
	}

	/**
	 * Fills the house: a main room theme (sometimes a second one), a light source of the house's own style,
	 * a bed, and sometimes a rug. Bigger houses may get a dividing wall with a door. Nothing blocks the door.
	 */
	private static void furnish(BlockPos c, int hx, int hz, int doorX, int doorZ, Direction doorSide, RandomSource r,
		List<BlockPos> plan, List<BlockState> states, Palette p, int wallH) {
		BlockPos inside = c.offset(doorX, 0, doorZ).relative(doorSide.getOpposite());

		// A dividing wall across the long side of big houses, with a doorway in the middle.
		boolean alongX = hx >= hz;
		int longHalf = alongX ? hx : hz;
		boolean divider = longHalf >= 4 && r.nextFloat() < 0.5F && (alongX ? doorX != 0 : doorZ != 0);
		if (divider) {
			int shortHalf = alongX ? hz : hx;
			for (int y = 0; y < wallH; y++) {
				for (int b = -shortHalf + 1; b < shortHalf; b++) {
					if (b == 0 && y <= 1) {
						if (y == 0 && p.door() != null) {
							plan.add(alongX ? c.offset(0, 0, b) : c.offset(b, 0, 0));
							Direction facing = alongX ? Direction.EAST : Direction.SOUTH;
							states.add(p.door().defaultBlockState().setValue(DoorBlock.FACING, facing).setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
						}
						continue;
					}
					plan.add(alongX ? c.offset(0, y, b) : c.offset(b, y, 0));
					states.add(p.wall().defaultBlockState());
				}
			}
		}

		List<BlockPos> spots = new ArrayList<>();
		for (int x = -hx + 1; x < hx; x++) {
			for (int z = -hz + 1; z < hz; z++) {
				boolean byWall = Math.abs(x) == hx - 1 || Math.abs(z) == hz - 1;
				boolean onDivider = divider && (alongX ? x == 0 || (x == 1 || x == -1) && z == 0 : z == 0 || (z == 1 || z == -1) && x == 0);
				BlockPos q = c.offset(x, 0, z);
				if (byWall && !onDivider && q.distManhattan(inside) > 1 && !q.equals(c)) {
					spots.add(q);
				}
			}
		}
		Collections.shuffle(spots, new java.util.Random(r.nextLong()));

		Theme[] themes = Theme.values();
		Theme main = themes[r.nextInt(themes.length)];
		List<BlockState> pieces = new ArrayList<>(themePieces(main, doorSide, r));
		if (r.nextFloat() < 0.5F || divider) {
			Theme second = themes[r.nextInt(themes.length)];
			if (second != main) {
				pieces.addAll(themePieces(second, doorSide, r));
			}
		}
		// Lighting in the house's own style.
		int lightStyle = r.nextInt(4);
		int lights = 1 + r.nextInt(2) + (divider ? 1 : 0);
		for (int i = 0; i < lights; i++) {
			pieces.add(switch (lightStyle) {
				case 0 -> Blocks.LANTERN.defaultBlockState();
				case 1 -> Blocks.TORCH.defaultBlockState();
				case 2 -> Blocks.CANDLE.defaultBlockState().setValue(net.minecraft.world.level.block.CandleBlock.LIT, true)
					.setValue(net.minecraft.world.level.block.CandleBlock.CANDLES, 1 + r.nextInt(4));
				default -> Blocks.SOUL_LANTERN.defaultBlockState();
			});
		}
		int n = Math.min(pieces.size(), spots.size());
		for (int i = 0; i < n; i++) {
			plan.add(spots.get(i));
			states.add(pieces.get(i));
		}

		// A rug in the middle of the room.
		if (r.nextFloat() < 0.5F && hx >= 3 && hz >= 3) {
			Block carpet = Blocks.CARPET.pick(DyeColor.values()[r.nextInt(DyeColor.values().length)]);
			for (int x = -1; x <= 1; x++) {
				for (int z = -1; z <= 1; z++) {
					BlockPos q = c.offset(x, 0, z);
					if (!spots.subList(0, n).contains(q) && !(divider && (alongX ? x == 0 : z == 0))) {
						plan.add(q);
						states.add(carpet.defaultBlockState());
					}
				}
			}
		}

		// A bed in a free spot whose neighbour (for the head) is also inside.
		if (main == Theme.BEDROOM || r.nextFloat() < 0.6F) {
			for (int i = n; i < spots.size(); i++) {
				BlockPos foot = spots.get(i);
				for (Direction d : Direction.Plane.HORIZONTAL) {
					BlockPos head = foot.relative(d);
					boolean inside2 = Math.abs(head.getX() - c.getX()) < hx && Math.abs(head.getZ() - c.getZ()) < hz;
					boolean blocked = spots.subList(0, n).contains(head) || head.distManhattan(inside) <= 1 || head.equals(c)
						|| (divider && (alongX ? head.getX() == c.getX() : head.getZ() == c.getZ()));
					if (inside2 && !blocked) {
						Block bed = Blocks.BED.pick(DyeColor.values()[r.nextInt(DyeColor.values().length)]);
						plan.add(foot);
						states.add(bed.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, d).setValue(AbstractBedBlock.PART, BedPart.FOOT));
						return;
					}
				}
			}
		}
	}
}
