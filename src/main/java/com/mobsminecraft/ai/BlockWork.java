package com.mobsminecraft.ai;

import java.util.function.BiPredicate;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.animal.fish.WaterAnimal;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.monster.cubemob.Slime;
import net.minecraft.world.entity.monster.illager.AbstractIllager;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Stateless helpers for mobs interacting with blocks. */
public final class BlockWork {
	private BlockWork() {}

	/** Ground-walking mobs that can sensibly mine, bridge and tower. */
	public static boolean canBuild(Mob mob) {
		return mob.getNavigation() instanceof GroundPathNavigation
			&& mob.getBbWidth() < 2.0F
			&& !(mob instanceof WaterAnimal)
			&& !(mob instanceof Phantom)
			&& !(mob instanceof Bat)
			&& !(mob instanceof Vex)
			&& !(mob instanceof Slime)
			&& !(mob instanceof Shulker)
			&& !(mob instanceof Ghast);
	}

	/** Mobs whose held item is rendered, so a crafted tool is visible. */
	public static boolean isToolHolder(Mob mob) {
		return mob instanceof Zombie || mob instanceof AbstractPiglin || mob instanceof AbstractIllager;
	}

	public static boolean blocksMovement(ServerLevel level, BlockPos pos) {
		return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
	}

	public static boolean isFloor(ServerLevel level, BlockPos pos) {
		return blocksMovement(level, pos);
	}

	/**
	 * Whether this mob can break the block. Blocks that need a tool (stone, ores, cobblestone...)
	 * can only be mined while holding the right one, e.g. a pickaxe; a sword or bare hands won't do.
	 */
	public static boolean canMine(Mob mob, ServerLevel level, BlockPos pos, BlockState state) {
		if (state.isAir() || state.hasBlockEntity() || !state.getFluidState().isEmpty()) {
			return false;
		}
		float hardness = state.getDestroySpeed(level, pos);
		if (hardness < 0.0F || hardness > 20.0F || state.is(Blocks.BEDROCK)) {
			return false;
		}
		// Never break what mobs built (their houses, bridges, outposts).
		// (Griefers don't care whose it is.)
		if (com.mobsminecraft.world.MobBuilt.contains(level, pos) && !Griefer.grieves(((com.mobsminecraft.SmartMobHolder) mob).mmc$getBrain())) {
			return false;
		}
		return !state.requiresCorrectToolForDrops() || tool(mob, state).isCorrectToolForDrops(state);
	}

	/** Fraction of the block broken per tick, modelled on the player formula but a bit faster. */
	public static float progressPerTick(Mob mob, ServerLevel level, BlockPos pos, BlockState state) {
		float hardness = state.getDestroySpeed(level, pos);
		if (hardness <= 0.0F) {
			return 1.0F;
		}
		ItemStack tool = tool(mob, state);
		float speed = Math.max(1.0F, tool.getDestroySpeed(state));
		boolean correct = !state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state);
		return 1.5F * speed / hardness / (correct ? 30.0F : 100.0F);
	}

	private static final ItemStack PASSIVE_TOOL = new ItemStack(net.minecraft.world.item.Items.STONE_PICKAXE);

	/**
	 * The tool a mob mines with: what it holds, or for empty-handed passive mobs (animals, villagers...)
	 * a stand-in stone pickaxe, so they can quarry cobblestone and basic ores. Monsters need a real pickaxe.
	 */
	static ItemStack tool(Mob mob, BlockState state) {
		ItemStack held = mob.getMainHandItem();
		if (held.isCorrectToolForDrops(state) || held.getDestroySpeed(state) > 1.0F) {
			return held;
		}
		// Zombies and skeletons carry a pickaxe they switch to for stone and ores.
		ItemStack pickaxe = ((com.mobsminecraft.SmartMobHolder) mob).mmc$getBrain().pickaxe;
		if (!pickaxe.isEmpty() && (pickaxe.isCorrectToolForDrops(state) || pickaxe.getDestroySpeed(state) > 1.0F)) {
			return pickaxe;
		}
		if (held.isEmpty() && !(mob instanceof net.minecraft.world.entity.monster.Enemy)) {
			return PASSIVE_TOOL;
		}
		return held;
	}

	/**
	 * Natural terrain mobs may gather: dirt, stone, sand, gravel, and logs that are part of a tree
	 * (leaves nearby). Planks, cobblestone, bricks and anything mobs built are left alone.
	 */
	public static boolean isNatural(ServerLevel level, BlockPos pos, BlockState state) {
		if (com.mobsminecraft.world.MobBuilt.contains(level, pos)) {
			return false;
		}
		if (state.is(BlockTags.LOGS)) {
			return findNearest(level, pos, 2, s -> s.is(BlockTags.LEAVES)) != null;
		}
		return state.is(BlockTags.DIRT) || state.is(BlockTags.GRASS_BLOCKS) || state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.BASE_STONE_NETHER)
			|| state.is(BlockTags.SAND) || state.is(Blocks.GRAVEL);
	}

	/** Blocks that the mob keeps for its own building stock instead of dropping. */
	public static boolean isBuildingMaterial(BlockState state) {
		return state.is(BlockTags.DIRT) || state.is(BlockTags.GRASS_BLOCKS)
			|| state.is(BlockTags.LOGS)
			|| state.is(BlockTags.PLANKS)
			|| state.is(BlockTags.BASE_STONE_OVERWORLD)
			|| state.is(BlockTags.BASE_STONE_NETHER)
			|| state.is(Blocks.COBBLESTONE)
			|| state.is(Blocks.COBBLED_DEEPSLATE)
			|| state.is(Blocks.SAND)
			|| state.is(Blocks.GRAVEL);
	}

	public static boolean isOre(BlockState state) {
		return state.is(BlockTags.ORES);
	}

	/** Nearest block within a cube of the given radius that matches, or null. */
	public static BlockPos findNearest(ServerLevel level, BlockPos center, int radius, Predicate<BlockState> test) {
		return findNearestAt(level, center, radius, test, (pos, state) -> true);
	}

	/** Like {@link #findNearest} but the test also sees the position. */
	public static BlockPos findNearestAt(ServerLevel level, BlockPos center, int radius, BiPredicate<BlockPos, BlockState> test) {
		return findNearestAt(level, center, radius, null, test);
	}

	/**
	 * Nearest matching block in a cube. Works section by section: chunks that aren't loaded are skipped (it never
	 * loads or generates terrain), empty sections are skipped, and when {@code mayMatch} is given, so is any section
	 * whose palette has no block it could match (most sections have no ore at all, so ore searches are nearly free).
	 */
	public static BlockPos findNearestAt(ServerLevel level, BlockPos center, int radius, Predicate<BlockState> mayMatch, BiPredicate<BlockPos, BlockState> test) {
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		int x0 = center.getX() - radius, x1 = center.getX() + radius;
		int y0 = Math.max(level.getMinY(), center.getY() - radius), y1 = Math.min(level.getMaxY(), center.getY() + radius);
		int z0 = center.getZ() - radius, z1 = center.getZ() + radius;
		for (int cx = x0 >> 4; cx <= x1 >> 4; cx++) {
			for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) {
				net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
				if (chunk == null) {
					continue;
				}
				for (int sy = y0 >> 4; sy <= y1 >> 4; sy++) {
					int index = chunk.getSectionIndexFromSectionY(sy);
					if (index < 0 || index >= chunk.getSectionsCount()) continue;
					net.minecraft.world.level.chunk.LevelChunkSection section = chunk.getSection(index);
					if (section.hasOnlyAir() && (mayMatch == null || !mayMatch.test(net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()))) {
						continue;
					}
					if (mayMatch != null && !section.maybeHas(mayMatch)) {
						continue;
					}
					int bx0 = Math.max(x0, cx << 4), bx1 = Math.min(x1, (cx << 4) + 15);
					int by0 = Math.max(y0, sy << 4), by1 = Math.min(y1, (sy << 4) + 15);
					int bz0 = Math.max(z0, cz << 4), bz1 = Math.min(z1, (cz << 4) + 15);
					for (int x = bx0; x <= bx1; x++) {
						for (int y = by0; y <= by1; y++) {
							for (int z = bz0; z <= bz1; z++) {
								BlockState state = section.getBlockState(x & 15, y & 15, z & 15);
								if (mayMatch != null && !mayMatch.test(state)) continue;
								p.set(x, y, z);
								if (!test.test(p, state)) continue;
								double dx = x - center.getX(), dy = y - center.getY(), dz = z - center.getZ();
								double d = dx * dx + dy * dy + dz * dz;
								if (d < bestDist) {
									bestDist = d;
									best = p.immutable();
								}
							}
						}
					}
				}
			}
		}
		return best;
	}

	/** True if any neighbour of the position is open, so a walking mob can reach it. */
	public static boolean isExposed(ServerLevel level, BlockPos pos) {
		for (Direction d : Direction.values()) {
			if (level.getBlockState(pos.relative(d)).isAir()) {
				return true;
			}
		}
		return false;
	}

	/** Finds a free spot next to the mob with solid ground, for placing a utility block. */
	public static BlockPos findAdjacentSpot(ServerLevel level, Mob mob) {
		BlockPos feet = mob.blockPosition();
		Direction[] dirs = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
		int start = mob.getRandom().nextInt(4);
		for (int i = 0; i < 4; i++) {
			BlockPos p = feet.relative(dirs[(start + i) % 4]);
			if (level.getBlockState(p).canBeReplaced() && level.getFluidState(p).isEmpty() && isFloor(level, p.below())) {
				return p;
			}
		}
		return null;
	}
}
