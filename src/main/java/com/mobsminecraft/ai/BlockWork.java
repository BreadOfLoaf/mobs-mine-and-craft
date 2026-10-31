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
		return !state.requiresCorrectToolForDrops() || mob.getMainHandItem().isCorrectToolForDrops(state);
	}

	/** Fraction of the block broken per tick, modelled on the player formula but a bit faster. */
	public static float progressPerTick(Mob mob, ServerLevel level, BlockPos pos, BlockState state) {
		float hardness = state.getDestroySpeed(level, pos);
		if (hardness <= 0.0F) {
			return 1.0F;
		}
		ItemStack tool = mob.getMainHandItem();
		float speed = Math.max(1.0F, tool.getDestroySpeed(state));
		boolean correct = !state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state);
		return 1.5F * speed / hardness / (correct ? 30.0F : 100.0F);
	}

	/** Blocks that the mob keeps for its own building stock instead of dropping. */
	public static boolean isBuildingMaterial(BlockState state) {
		return state.is(BlockTags.DIRT)
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
		return findNearestAt(level, center, radius, (pos, state) -> test.test(state));
	}

	/** Like {@link #findNearest} but the test also sees the position. */
	public static BlockPos findNearestAt(ServerLevel level, BlockPos center, int radius, BiPredicate<BlockPos, BlockState> test) {
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int x = -radius; x <= radius; x++) {
			for (int y = -radius; y <= radius; y++) {
				for (int z = -radius; z <= radius; z++) {
					p.set(center.getX() + x, center.getY() + y, center.getZ() + z);
					if (!test.test(p, level.getBlockState(p))) {
						continue;
					}
					double d = x * x + y * y + z * z;
					if (d < bestDist) {
						bestDist = d;
						best = p.immutable();
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
