package com.mobsminecraft.ai;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Prospecting trips: a passive mob gathers a few others, digs a staircase down into the ground, tunnels along
 * mining any ores it uncovers, then everyone walks back up and the leader smelts the ore in a furnace.
 */
final class Prospecting {
	/** One step of the trip: dig this block out, or walk onto it. */
	record Op(BlockPos pos, boolean move) {}

	static final int NONE = 0;
	static final int DIGGING = 1;
	static final int RETURNING = 2;
	static final int SMELTING = 3;

	private Prospecting() {}

	static SmartBrain brainOf(Mob mob) {
		return ((com.mobsminecraft.SmartMobHolder) mob).mmc$getBrain();
	}

	private static boolean busy(SmartBrain b) {
		return b.housePlan != null || b.prospectPhase != NONE || b.prospectLeader != null || b.farmPlan != null || b.cookPos != null;
	}

	/** Returns true while the mob is on a trip (as leader or follower). */
	static boolean tick(SmartBrain brain, ServerLevel level, boolean griefing) {
		if (brain.prospectLeader != null) {
			return follow(brain, level);
		}
		if (brain.prospectPhase == NONE) {
			return tryStart(brain, level, griefing);
		}
		return switch (brain.prospectPhase) {
			case DIGGING -> dig(brain, level);
			case RETURNING -> returning(brain, level);
			default -> smelt(brain, level);
		};
	}

	private static boolean tryStart(SmartBrain brain, ServerLevel level, boolean griefing) {
		Mob mob = brain.mob;
		boolean underground = Outpost.isUnderground(level, mob);
		// Minemen already underground run a branch mine from where they are; everyone else starts from the surface.
		if (brain.prospectCooldown > 0 || !griefing || !mob.onGround() || !BlockWork.canBuild(mob)
			|| (underground && brain.personality != Personality.MINEMAN)) {
			return false;
		}
		RandomSource r = mob.getRandom();
		boolean mineman = brain.personality == Personality.MINEMAN;
		brain.prospectCooldown = mineman ? 200 + r.nextInt(300) : 2400 + r.nextInt(3600);
		if (!mineman && r.nextFloat() > 0.6F && !brain.forceProspect) {
			return false;
		}
		BlockPos start = mob.blockPosition();
		Direction first = Direction.Plane.HORIZONTAL.getRandomDirection(r);
		int wantDepth = underground ? 1 + r.nextInt(2) : 6 + r.nextInt(5);
		int length = underground ? 8 + r.nextInt(7) : 5 + r.nextInt(6);
		List<Op> ops = null;
		Direction dir = first;
		// Any direction will do, and shallower if the ground is too thin (caves, water) for a deep one.
		search:
		for (int depth = wantDepth; depth >= (underground ? 1 : 4); depth -= underground ? 1 : 2) {
			for (int k = 0; k < 4; k++) {
				dir = Direction.from2DDataValue((first.get2DDataValue() + k) % 4);
				List<Op> plan = planTrip(start, dir, depth, length);
				if (fits(mob, level, plan)) {
					ops = plan;
					break search;
				}
			}
		}
		if (ops == null) {
			com.mobsminecraft.debug.AiStats.add("prospect_no_room");
			if (brain.forceProspect) {
				brain.prospectCooldown = 100;
			}
			return false;
		}
		brain.forceProspect = false;
		brain.prospectOps = ops;
		brain.prospectIndex = 0;
		brain.prospectStart = start;
		brain.prospectDir = dir;
		brain.prospectPhase = DIGGING;
		brain.prospectTicks = 0;
		brain.prospectStuck = 0;
		int recruited = 0;
		for (Mob other : level.getEntitiesOfClass(Mob.class, mob.getBoundingBox().inflate(14.0), m -> m != mob && m.isAlive())) {
			SmartBrain ob = brainOf(other);
			boolean sameKind = mineman ? ob.personality == Personality.MINEMAN : ob.isPassive();
			if (recruited < 3 && sameKind && BlockWork.canBuild(other) && !busy(ob) && other.getTarget() == null) {
				ob.prospectLeader = mob;
				recruited++;
			}
		}
		com.mobsminecraft.debug.AiStats.add("prospect_start");
		if (recruited > 0) {
			com.mobsminecraft.debug.AiStats.add("prospect_group");
		}
		return true;
	}

	/** The block a staircase step or tunnel stands on: the next op walks onto it. */
	private static boolean isFloorOp(SmartBrain brain, Op op) {
		int i = brain.prospectOps.indexOf(op);
		return i >= 0 && i + 1 < brain.prospectOps.size() && brain.prospectOps.get(i + 1).move() && brain.prospectOps.get(i + 1).pos().equals(op.pos());
	}

	private static List<Op> planTrip(BlockPos start, Direction dir, int depth, int length) {
		List<Op> ops = new ArrayList<>();
		for (int i = 1; i <= depth; i++) {
			BlockPos s = start.relative(dir, i).below(i);
			ops.add(new Op(s.above(2), false));
			ops.add(new Op(s.above(), false));
			ops.add(new Op(s, false));
			ops.add(new Op(s, true));
		}
		BlockPos bottom = start.relative(dir, depth).below(depth);
		for (int j = 1; j <= length; j++) {
			BlockPos t = bottom.relative(dir, j);
			ops.add(new Op(t.above(), false));
			ops.add(new Op(t, false));
			ops.add(new Op(t, true));
		}
		return ops;
	}

	/** The way down is diggable ground, with nothing that would flood it and no cave under any step. */
	private static boolean fits(Mob mob, ServerLevel level, List<Op> ops) {
		for (Op op : ops) {
			if (op.move()) {
				if (!BlockWork.blocksMovement(level, op.pos().below())) {
					return false;
				}
				continue;
			}
			var st = level.getBlockState(op.pos());
			if (!safe(level, op.pos()) || (!st.canBeReplaced() && !BlockWork.canMine(mob, level, op.pos(), st) && !BlockWork.isOre(st))) {
				return false;
			}
		}
		return true;
	}

	private static boolean safe(ServerLevel level, BlockPos p) {
		if (!level.getFluidState(p).isEmpty()) return false;
		for (Direction d : Direction.values()) {
			if (!level.getFluidState(p.relative(d)).isEmpty()) return false;
		}
		return true;
	}

	private static boolean dig(SmartBrain brain, ServerLevel level) {
		Mob mob = brain.mob;
		if (++brain.prospectTicks > 2400 || brain.prospectIndex >= brain.prospectOps.size()) {
			startReturn(brain, brain.prospectTicks > 2400 ? "time" : "done");
			return true;
		}
		Op op = brain.prospectOps.get(brain.prospectIndex);
		if (op.move()) {
			double dx = op.pos().getX() + 0.5 - mob.getX();
			double dz = op.pos().getZ() + 0.5 - mob.getZ();
			if (dx * dx + dz * dz < 0.3 && Math.abs(mob.getY() - op.pos().getY()) < 0.6 && mob.onGround()) {
				brain.prospectIndex++;
				brain.prospectStuck = 0;
				// In the tunnel: look around for ores the digging uncovered.
				if (op.pos().getY() < brain.prospectStart.getY() - 3 || brain.personality == Personality.MINEMAN) {
					scanOres(brain, level, op.pos());
				}
				return true;
			}
			if (++brain.prospectStuck > 30) {
				// Someone is standing on the next step: nudge them out of the way.
				net.minecraft.world.phys.AABB cell = new net.minecraft.world.phys.AABB(op.pos()).expandTowards(0.0, 1.0, 0.0);
				for (Mob other : level.getEntitiesOfClass(Mob.class, cell, m -> m != mob)) {
					Vec3 push = Vec3.atLowerCornerOf(brain.prospectDir.getUnitVec3i()).scale(0.4);
					other.setDeltaMovement(other.getDeltaMovement().add(push.x, 0.1, push.z));
				}
			}
			if (brain.prospectStuck > 120) {
				startReturn(brain, "stuck");
				return true;
			}
			mob.getNavigation().stop();
			mob.getMoveControl().setWantedPosition(op.pos().getX() + 0.5, op.pos().getY(), op.pos().getZ() + 0.5, brain.walkSpeed() * 0.8);
			return true;
		}
		var state = level.getBlockState(op.pos());
		if (state.canBeReplaced() && state.getFluidState().isEmpty()) {
			brain.prospectIndex++;
			brain.prospectStuck = 0;
			return true;
		}
		if (!safe(level, op.pos())) {
			startReturn(brain, "flood");
			return true;
		}
		if (!BlockWork.canMine(mob, level, op.pos(), state)) {
			if (BlockWork.isOre(state)) {
				brain.prospectIndex++; // an ore it can't mine yet (needs iron tools): leave it
				brain.prospectStuck = 0;
				return true;
			}
			startReturn(brain, "unmineable:" + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath());
			return true;
		}
		// Never dig out the floor of a step over a cave.
		if (isFloorOp(brain, op) && !BlockWork.blocksMovement(level, op.pos().below())) {
			startReturn(brain, "cave");
			return true;
		}
		// The step only counts once the block is actually gone (mining can be interrupted).
		if (++brain.prospectStuck > 120) {
			startReturn(brain, "dig_stuck");
			return true;
		}
		brain.startMining(op.pos(), !BlockWork.isOre(state));
		if (BlockWork.isOre(state) && brain.prospectStuck == 1) {
			com.mobsminecraft.debug.AiStats.add("prospect_ore");
		}
		return true;
	}

	/** Queues any ores within two blocks of the tunnel, with the block in between if needed, then returns to the tunnel. */
	private static void scanOres(SmartBrain brain, ServerLevel level, BlockPos at) {
		List<Op> extra = new ArrayList<>();
		for (int y = 0; y <= 1; y++) {
			for (Direction d : Direction.values()) {
				BlockPos near = at.above(y).relative(d);
				BlockPos far = near.relative(d);
				if (BlockWork.isOre(level.getBlockState(near))) {
					extra.add(new Op(near, false));
				} else if (d.getAxis().isHorizontal() && BlockWork.isOre(level.getBlockState(far)) && safe(level, near) && safe(level, far)) {
					extra.add(new Op(near, false));
					extra.add(new Op(far, false));
				}
			}
		}
		if (!extra.isEmpty()) {
			brain.prospectOps.addAll(brain.prospectIndex, extra);
		}
	}

	private static void startReturn(SmartBrain brain, String why) {
		com.mobsminecraft.debug.AiStats.add("prospect_return:" + why);
		startReturn(brain);
	}

	private static void startReturn(SmartBrain brain) {
		brain.prospectPhase = RETURNING;
		brain.prospectTicks = 0;
		brain.pickupBoostTicks = 60;
	}

	private static boolean returning(SmartBrain brain, ServerLevel level) {
		Mob mob = brain.mob;
		Vec3 home = Vec3.atBottomCenterOf(brain.prospectStart);
		if (mob.position().distanceToSqr(home) < 4.0 || ++brain.prospectTicks > 600) {
			brain.prospectPhase = SMELTING;
			brain.prospectTicks = 0;
			brain.prospectFurnace = null;
			return true;
		}
		if (mob.tickCount % 10 == 0) {
			// Walk back up the staircase: retrace the dug steps.
			Vec3 goal = home;
			if (!mob.getNavigation().moveTo(goal.x, goal.y, goal.z, brain.walkSpeed())) {
				BlockPos feet = mob.blockPosition();
				BlockPos up = feet.relative(brain.prospectDir.getOpposite()).above();
				mob.getMoveControl().setWantedPosition(up.getX() + 0.5, up.getY(), up.getZ() + 0.5, brain.walkSpeed());
				mob.getJumpControl().jump();
			}
		}
		return true;
	}

	private static boolean isRawOre(ItemStack s) {
		return s.is(Items.RAW_IRON) || s.is(Items.RAW_COPPER) || s.is(Items.RAW_GOLD);
	}

	private static boolean smelt(SmartBrain brain, ServerLevel level) {
		Mob mob = brain.mob;
		if (brain.personality == Personality.MINEMAN) {
			// Minemen don't smelt: the ore goes in their chest (see Personalities.mineman).
			brain.prospectCooldown = 0;
			finish(brain, level);
			brain.prospectCooldown = 200 + mob.getRandom().nextInt(300);
			return false;
		}
		if (brain.prospectFurnace == null) {
			// Pool the group's ore with the leader.
			for (Mob other : level.getEntitiesOfClass(Mob.class, mob.getBoundingBox().inflate(16.0), m -> m != mob && m.isAlive())) {
				SmartBrain ob = brainOf(other);
				if (ob.prospectLeader == mob) {
					ob.loot.removeIf(s -> isRawOre(s) && Inventory.store(brain, s));
					ob.prospectLeader = null;
				}
			}
			ItemStack ore = ItemStack.EMPTY;
			for (ItemStack s : brain.loot) {
				if (isRawOre(s) && s.getCount() > ore.getCount()) {
					ore = s;
				}
			}
			if (ore.isEmpty()) {
				com.mobsminecraft.debug.AiStats.add("prospect_no_ore");
				return finish(brain, level);
			}
			BlockPos furnace = BlockWork.findNearest(level, mob.blockPosition(), 8, s -> s.getBlock() instanceof AbstractFurnaceBlock);
			var furnaceState = Blocks.FURNACE.defaultBlockState().setValue(AbstractFurnaceBlock.FACING, mob.getDirection().getOpposite());
			if (furnace == null) {
				furnace = brain.placeAdjacent(level, furnaceState);
			}
			if (furnace == null) {
				// No room right beside it: any open spot with a floor within a couple of blocks.
				BlockPos spot = BlockWork.findNearestAt(level, mob.blockPosition(), 2, (p, st) -> st.canBeReplaced() && st.getFluidState().isEmpty()
					&& BlockWork.isFloor(level, p.below()) && !p.equals(mob.blockPosition()) && !p.equals(mob.blockPosition().above()));
				if (spot != null && brain.place(level, spot.immutable(), furnaceState, false)) {
					furnace = spot.immutable();
				}
			}
			if (furnace == null) {
				// Still nowhere (a narrow tunnel): carve the furnace into the wall beside it.
				for (Direction d : Direction.Plane.HORIZONTAL) {
					BlockPos wall = mob.blockPosition().relative(d);
					var ws = level.getBlockState(wall);
					if (BlockWork.isNatural(level, wall, ws) && BlockWork.canMine(mob, level, wall, ws)) {
						level.setBlock(wall, furnaceState.setValue(AbstractFurnaceBlock.FACING, d.getOpposite()), net.minecraft.world.level.block.Block.UPDATE_ALL);
						com.mobsminecraft.world.MobBuilt.mark(level, wall);
						furnace = wall;
						break;
					}
				}
			}
			if (furnace == null || !(level.getBlockEntity(furnace) instanceof AbstractFurnaceBlockEntity fb)) {
				com.mobsminecraft.debug.AiStats.add("prospect_no_furnace");
				return finish(brain, level);
			}
			if (fb.getItem(0).isEmpty() || ItemStack.isSameItem(fb.getItem(0), ore)) {
				ItemStack input = ore.copy();
				input.grow(fb.getItem(0).getCount());
				fb.setItem(0, input);
				brain.loot.remove(ore);
			}
			ItemStack coal = brain.loot.stream().filter(s -> s.is(Items.COAL)).findFirst().orElse(ItemStack.EMPTY);
			if (!coal.isEmpty() && fb.getItem(1).isEmpty()) {
				fb.setItem(1, coal.copy());
				brain.loot.remove(coal);
			} else if (fb.getItem(1).isEmpty()) {
				fb.setItem(1, new ItemStack(Items.OAK_PLANKS, 4));
			}
			brain.prospectFurnace = furnace;
			brain.prospectTicks = 0;
			mob.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
			com.mobsminecraft.debug.AiStats.add("prospect_smelting");
			return true;
		}
		if (!(level.getBlockEntity(brain.prospectFurnace) instanceof AbstractFurnaceBlockEntity fb) || ++brain.prospectTicks > 500) {
			return finish(brain, level);
		}
		Vec3 c = Vec3.atCenterOf(brain.prospectFurnace);
		brain.holdStill();
		mob.getLookControl().setLookAt(c);
		if (mob.tickCount % 20 == 0) {
			level.sendParticles(ParticleTypes.SMOKE, c.x, c.y + 0.7, c.z, 3, 0.2, 0.1, 0.2, 0.01);
		}
		ItemStack result = fb.getItem(2);
		if (!result.isEmpty()) {
			Inventory.store(brain, result.copy());
			fb.setItem(2, ItemStack.EMPTY);
			level.playSound(null, mob.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.NEUTRAL, 0.6F, 1.0F);
			com.mobsminecraft.debug.AiStats.add("prospect_ingot");
			return finish(brain, level);
		}
		return true;
	}

	private static boolean finish(SmartBrain brain, ServerLevel level) {
		brain.prospectPhase = NONE;
		brain.prospectOps = null;
		brain.prospectFurnace = null;
		brain.prospectCooldown = 6000 + brain.mob.getRandom().nextInt(6000);
		for (Mob other : level.getEntitiesOfClass(Mob.class, brain.mob.getBoundingBox().inflate(24.0), m -> m != brain.mob)) {
			SmartBrain ob = brainOf(other);
			if (ob.prospectLeader == brain.mob) {
				ob.prospectLeader = null;
			}
		}
		com.mobsminecraft.debug.AiStats.add("prospect_done");
		return false;
	}

	/** Followers stay close to the leader and mine ores they pass. */
	private static boolean follow(SmartBrain brain, ServerLevel level) {
		Mob mob = brain.mob;
		Mob leader = brain.prospectLeader;
		SmartBrain lb = leader.isAlive() ? brainOf(leader) : null;
		if (lb == null || lb.prospectPhase == NONE || lb.prospectPhase == SMELTING || mob.distanceTo(leader) > 32.0F) {
			brain.prospectLeader = null;
			return false;
		}
		if (mob.tickCount % 30 == 0) {
			BlockPos ore = BlockWork.findNearestAt(level, mob.blockPosition(), 3, (p, s) -> BlockWork.isOre(s) && BlockWork.isExposed(level, p)
				&& BlockWork.canMine(mob, level, p, s) && safe(level, p));
			if (ore != null && mob.getY() < lb.prospectStart.getY() - 2) {
				brain.startMining(ore, false);
				com.mobsminecraft.debug.AiStats.add("prospect_ore");
				return true;
			}
		}
		// Hang back a few blocks so the leader has room on the narrow stairs.
		double d = mob.distanceTo(leader);
		if (d > 4.5F && mob.tickCount % 10 == 0) {
			mob.getNavigation().moveTo(leader, brain.walkSpeed());
		} else if (d < 2.5F) {
			Vec3 away = mob.position().subtract(leader.position()).multiply(1.0, 0.0, 1.0);
			if (away.lengthSqr() > 1.0E-4) {
				Vec3 goal = mob.position().add(away.normalize().scale(1.5));
				mob.getNavigation().moveTo(goal.x, goal.y, goal.z, brain.walkSpeed());
			}
		} else {
			mob.getNavigation().stop();
		}
		return true;
	}
}
