package com.mobsminecraft.test;

import com.mobsminecraft.debug.AiStats;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Drives the test bot like a survival player in a normal generated world: it wanders the terrain on foot
 * (climbing one block at a time, falling down drops, digging through walls it can't climb), mines stone and ores
 * it passes, builds little cobblestone walls and huts now and then, and fights back against monsters that get close.
 */
final class SurvivalBot {
	private final Bot bot;
	private final ServerLevel level;
	private final RandomSource r;
	private Vec3 waypoint;
	private int waypointTicks;
	private int mineCooldown = 40;
	private int buildCooldown = 400;
	private int attackCooldown;
	int blocksMined;
	int blocksPlaced;
	int attacks;

	SurvivalBot(Bot bot, ServerLevel level) {
		this.bot = bot;
		this.level = level;
		this.r = level.getRandom();
	}

	void tick() {
		var p = this.bot.player;
		// Fight back against monsters right next to us.
		if (--this.attackCooldown <= 0) {
			Mob nearest = null;
			for (Mob m : this.level.getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(3.2), m -> m.isAlive() && m instanceof Enemy)) {
				if (nearest == null || m.distanceToSqr(p) < nearest.distanceToSqr(p)) nearest = m;
			}
			if (nearest != null) {
				this.bot.attack(nearest);
				this.attacks++;
				this.attackCooldown = 12;
				return;
			}
		}
		if (--this.mineCooldown <= 0) {
			this.mineCooldown = 60 + this.r.nextInt(80);
			this.mineSomething();
		}
		if (--this.buildCooldown <= 0) {
			this.buildCooldown = 900 + this.r.nextInt(900);
			this.buildSomething();
		}
		this.walk();
	}

	private void walk() {
		var p = this.bot.player;
		if (this.waypoint == null || ++this.waypointTicks > 400 || p.position().distanceToSqr(this.waypoint) < 2.0) {
			double a = this.r.nextDouble() * Math.PI * 2;
			double d = 8 + this.r.nextInt(20);
			this.waypoint = p.position().add(Math.cos(a) * d, 0, Math.sin(a) * d);
			this.waypointTicks = 0;
		}
		Vec3 here = p.position();
		Vec3 dir = this.waypoint.subtract(here).multiply(1, 0, 1);
		if (dir.lengthSqr() < 1.0E-4) return;
		Vec3 next = here.add(dir.normalize().scale(0.18));
		BlockPos feet = BlockPos.containing(next);
		// Find somewhere to stand at the next spot: one step up, level, or falling down a drop.
		for (int dy = 1; dy >= -6; dy--) {
			BlockPos f = feet.above(dy);
			if (open(f) && open(f.above()) && solid(f.below())) {
				if (dy == 1 && !open(BlockPos.containing(here).above(2))) break; // no headroom to step up
				this.bot.teleport(new Vec3(next.x, f.getY(), next.z));
				if (p.tickCount % 5 == 0) {
					com.mobsminecraft.ai.Awareness.noise(p, p.position(), 7.0);
				}
				return;
			}
		}
		// A wall (or water): dig through it like a player would, or turn around.
		if (this.r.nextFloat() < 0.6F) {
			for (BlockPos b : new BlockPos[] {feet, feet.above()}) {
				if (!open(b) && minable(this.level.getBlockState(b))) {
					this.level.destroyBlock(b, true, p);
					this.blocksMined++;
				}
			}
		} else {
			this.waypoint = null;
		}
	}

	private boolean open(BlockPos b) {
		BlockState s = this.level.getBlockState(b);
		return s.getCollisionShape(this.level, b).isEmpty() && s.getFluidState().isEmpty();
	}

	private boolean solid(BlockPos b) {
		return !this.level.getBlockState(b).getCollisionShape(this.level, b).isEmpty();
	}

	private static boolean minable(BlockState s) {
		return s.is(BlockTags.MINEABLE_WITH_PICKAXE) || s.is(BlockTags.MINEABLE_WITH_SHOVEL) || s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES);
	}

	/** Mines an ore it can see nearby, otherwise a bit of stone or dirt (sometimes digging down a step). */
	private void mineSomething() {
		var p = this.bot.player;
		BlockPos at = p.blockPosition();
		BlockPos ore = null;
		BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
		for (int dx = -4; dx <= 4 && ore == null; dx++)
			for (int dy = -2; dy <= 3 && ore == null; dy++)
				for (int dz = -4; dz <= 4 && ore == null; dz++) {
					q.set(at.getX() + dx, at.getY() + dy, at.getZ() + dz);
					if (this.level.getBlockState(q).is(BlockTags.ORES)) ore = q.immutable();
				}
		if (ore != null) {
			this.level.destroyBlock(ore, true, p);
			com.mobsminecraft.ai.Awareness.noise(p, Vec3.atCenterOf(ore), 16.0);
			this.blocksMined++;
			AiStats.add("bot_mined_ore");
			return;
		}
		Direction d = Direction.Plane.HORIZONTAL.getRandomDirection(this.r);
		// Only now and then dig down, and never toward water or below the caves.
		boolean down = this.r.nextInt(10) == 0 && at.getY() > 50 && this.level.getFluidState(at.below(2)).isEmpty();
		BlockPos b = down ? at.below() : at.relative(d);
		if (!this.level.getFluidState(b).isEmpty() || !this.level.getFluidState(b.above()).isEmpty()) return;
		if (!open(b) && minable(this.level.getBlockState(b)) && !this.level.getBlockState(b).is(Blocks.BEDROCK)) {
			this.level.destroyBlock(b, true, p);
			com.mobsminecraft.ai.Awareness.noise(p, Vec3.atCenterOf(b), 16.0);
			this.blocksMined++;
		}
	}

	/** A little cobblestone hut or wall nearby, like a player putting up a base. */
	private void buildSomething() {
		var p = this.bot.player;
		BlockPos c = p.blockPosition().relative(Direction.Plane.HORIZONTAL.getRandomDirection(this.r), 3);
		int placed = 0;
		for (int dx = -1; dx <= 1; dx++)
			for (int dz = -1; dz <= 1; dz++)
				for (int dy = 0; dy < 2; dy++) {
					if (dx == 0 && dz == 0) continue;
					BlockPos b = c.offset(dx, dy, dz);
					if (open(b) && (dy > 0 || solid(b.below())) && !p.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(b))) {
						this.level.setBlockAndUpdate(b, (dx + dz) % 2 == 0 ? Blocks.COBBLESTONE.defaultBlockState() : Blocks.OAK_PLANKS.defaultBlockState());
						placed++;
					}
				}
		if (open(c.above(2)) && placed > 0) {
			this.level.setBlockAndUpdate(c, Blocks.TORCH.defaultBlockState());
		}
		this.blocksPlaced += placed;
		AiStats.add("bot_built", placed);
	}
}
