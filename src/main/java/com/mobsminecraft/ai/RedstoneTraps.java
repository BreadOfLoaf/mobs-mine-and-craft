package com.mobsminecraft.ai;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.vehicle.minecart.MinecartTNT;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.PoweredRailBlock;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.phys.Vec3;

/**
 * Working redstone traps that "engineer" mobs set up during big fights and fire with a button:
 * <ul>
 *   <li>Arrow or fire-charge dispenser: a loaded dispenser aimed at the player with a stone button on top.
 *       The engineer stands behind it and presses the button while the player is in the line of fire.</li>
 *   <li>TNT minecart: a straight track toward the player. A button on a backstop block powers the powered
 *       rail next to it, which launches the cart away from the block; an activator rail near the far end,
 *       powered by a redstone block, lights the TNT as the cart rolls over it.</li>
 * </ul>
 */
final class RedstoneTraps {
	enum Kind { ARROWS, FIRE_CHARGES, TNT_CART, CLOCK }

	private RedstoneTraps() {}

	/** Hostile mobs fighting the same player nearby, this one included. */
	static int waveSize(ServerLevel level, Mob mob, LivingEntity target) {
		return level.getEntitiesOfClass(Mob.class, mob.getBoundingBox().inflate(24.0),
			m -> m instanceof Enemy && m.isAlive() && m.getTarget() == target).size();
	}

	/** Builds, then operates, a trap. Returns true while the engineer is busy with it. */
	static boolean tick(SmartBrain brain, ServerLevel level, LivingEntity target, boolean griefing) {
		Mob mob = brain.mob;
		if (brain.rsPlan != null) {
			return build(brain, level);
		}
		if (brain.rsButton != null) {
			return operate(brain, level, target);
		}
		if (!brain.engineer || !griefing || brain.rsCooldown > 0 || !mob.onGround()) {
			return false;
		}
		double dist = mob.distanceTo(target);
		boolean specialist = brain.personality == Personality.ENGINEER;
		if (dist < 5.0 || dist > 24.0 || !mob.hasLineOfSight(target) || (!specialist && waveSize(level, mob, target) < 2)) {
			return false;
		}
		RandomSource r = mob.getRandom();
		brain.rsCooldown = specialist ? 100 + r.nextInt(100) : 400 + r.nextInt(300);
		Vec3 d = target.position().subtract(mob.position());
		Direction dir = Direction.getApproximateNearest(d.x, 0.0, d.z);
		// Dispensers only reach about 11 blocks; farther away, send a TNT cart down a track instead.
		if (dist > 11.0) {
			return planCart(brain, level, dir, r);
		}
		// Now and then: a self-firing turret on a repeater clock.
		if ((brain.forceClock || r.nextFloat() < (specialist ? 0.5F : 0.12F)) && dist > 6.0 && planClock(brain, level, dir)) {
			brain.forceClock = false;
			return true;
		}
		int roll = r.nextInt(100);
		boolean planned = false;
		Kind first = roll < 45 ? Kind.ARROWS : roll < 80 ? Kind.FIRE_CHARGES : Kind.TNT_CART;
		if (first == Kind.TNT_CART && planCart(brain, level, dir, r)) {
			return true;
		}
		planned = planDispenser(brain, level, dir, first == Kind.TNT_CART ? Kind.ARROWS : first);
		if (!planned && specialist) {
			// No room for that one here: try again in a moment rather than giving up for ages.
			brain.rsCooldown = 40;
		}
		return planned;
	}

	// ------------------------------------------------------------------ planning

	private static boolean planDispenser(SmartBrain brain, ServerLevel level, Direction dir, Kind kind) {
		// Line the dispenser up with the player's row or column so its straight shots actually hit.
		BlockPos here = brain.mob.blockPosition();
		LivingEntity target = brain.mob.getTarget();
		BlockPos spot = here.relative(dir);
		if (target != null) {
			BlockPos t = target.blockPosition();
			int dx = t.getX() - here.getX();
			int dz = t.getZ() - here.getZ();
			if (Math.abs(dz) <= 3 && dx != 0) {
				dir = dx > 0 ? Direction.EAST : Direction.WEST;
				spot = new BlockPos(here.getX() + dir.getStepX(), here.getY(), t.getZ());
			} else if (Math.abs(dx) <= 3 && dz != 0) {
				dir = dz > 0 ? Direction.SOUTH : Direction.NORTH;
				spot = new BlockPos(t.getX(), here.getY(), here.getZ() + dir.getStepZ());
			}
		}
		// Raised on a block so it fires from chest height; arrows from the ground drop into the dirt.
		if (!level.getBlockState(spot).canBeReplaced() || !level.getBlockState(spot.above()).canBeReplaced()
			|| !level.getBlockState(spot.above(2)).canBeReplaced() || !BlockWork.isFloor(level, spot.below())) {
			return false;
		}
		List<BlockPos> plan = new ArrayList<>();
		List<BlockState> states = new ArrayList<>();
		plan.add(spot);
		states.add(Blocks.COBBLESTONE.defaultBlockState());
		plan.add(spot.above());
		states.add(Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING, dir));
		plan.add(spot.above(2));
		states.add(button(dir));
		start(brain, kind, plan, states, spot.above(2), dir);
		return true;
	}

	/**
	 * Repeater-clock turret: a dispenser on a cobblestone block, powered from behind through a repeater by a
	 * two-repeater clock loop. A redstone torch kicks the clock off and is taken away again, leaving the loop
	 * pulsing, so the dispenser fires on its own every 0.8 seconds until it runs dry. Laid out (top view, the
	 * player is to the right):
	 * <pre>
	 *   E  A  R0 cobble/dispenser
	 *   RB RA
	 *   D  C  T(torch)
	 * </pre>
	 */
	private static boolean planClock(SmartBrain brain, ServerLevel level, Direction dir) {
		Mob mob = brain.mob;
		BlockPos here = mob.blockPosition();
		LivingEntity target = mob.getTarget();
		if (target == null) {
			return false;
		}
		// Right on the player's row or column, 7 blocks out on this side, aimed straight down the line.
		BlockPos t = target.blockPosition();
		int dx = t.getX() - here.getX();
		int dz = t.getZ() - here.getZ();
		dir = Math.abs(dx) >= Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
		BlockPos spot = new BlockPos(t.getX(), here.getY(), t.getZ()).relative(dir.getOpposite(), 7);
		if (Math.abs(t.getY() - here.getY()) > 1) {
			return false;
		}
		Direction back = dir.getOpposite();
		Direction side = mob.getRandom().nextBoolean() ? dir.getClockWise() : dir.getCounterClockWise();
		BlockPos r0 = spot.relative(back);
		BlockPos a = spot.relative(back, 2);
		BlockPos ra = a.relative(side);
		BlockPos c = a.relative(side, 2);
		BlockPos d = c.relative(back);
		BlockPos rb = a.relative(back).relative(side);
		BlockPos e = a.relative(back);
		BlockPos torch = c.relative(side);
		for (BlockPos p : new BlockPos[] {spot, r0, a, ra, c, d, rb, e, torch}) {
			if (!level.getBlockState(p).canBeReplaced() || !level.getFluidState(p).isEmpty() || !BlockWork.isFloor(level, p.below())
				|| p.equals(here) || !level.getEntitiesOfClass(LivingEntity.class, new net.minecraft.world.phys.AABB(p)).isEmpty()) {
				return false;
			}
		}
		if (!level.getBlockState(spot.above()).canBeReplaced()) {
			return false;
		}
		BlockState wire = Blocks.REDSTONE_WIRE.defaultBlockState();
		List<BlockPos> plan = new ArrayList<>(List.of(spot, spot.above(), r0, a, ra, c, d, rb, e));
		List<BlockState> states = new ArrayList<>(List.of(
			Blocks.COBBLESTONE.defaultBlockState(),
			Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING, dir),
			repeater(back, 1), wire, repeater(side.getOpposite(), 4), wire, wire, repeater(side, 4), wire));
		start(brain, Kind.CLOCK, plan, states, torch, dir);
		return true;
	}

	/** A repeater whose input is on the {@code inputSide} (that's what FACING means for diodes). */
	private static BlockState repeater(Direction inputSide, int delay) {
		return Blocks.REPEATER.defaultBlockState()
			.setValue(net.minecraft.world.level.block.RepeaterBlock.FACING, inputSide)
			.setValue(net.minecraft.world.level.block.RepeaterBlock.DELAY, delay);
	}

	private static void loadTurret(DispenserBlockEntity dispenser) {
		dispenser.insertItem(new ItemStack(Items.ARROW, 32));
		dispenser.insertItem(new ItemStack(Items.FIRE_CHARGE, 12));
		ItemStack slow = net.minecraft.world.item.alchemy.PotionContents.createItemStack(Items.TIPPED_ARROW, net.minecraft.world.item.alchemy.Potions.SLOWNESS);
		slow.setCount(8);
		dispenser.insertItem(slow);
		ItemStack poison = net.minecraft.world.item.alchemy.PotionContents.createItemStack(Items.TIPPED_ARROW, net.minecraft.world.item.alchemy.Potions.POISON);
		poison.setCount(8);
		dispenser.insertItem(poison);
		dispenser.insertItem(new ItemStack(Items.SNOWBALL, 16));
	}

	private static boolean planCart(SmartBrain brain, ServerLevel level, Direction dir, RandomSource r) {
		BlockPos s = brain.mob.blockPosition().relative(dir);
		int len = 6 + r.nextInt(4);
		for (int i = 0; i <= len; i++) {
			BlockPos p = s.relative(dir, i);
			if (!level.getBlockState(p).canBeReplaced() || !BlockWork.isFloor(level, p.below())) {
				return false;
			}
		}
		Direction side = dir.getClockWise();
		BlockPos activator = s.relative(dir, len - 1);
		if (!level.getBlockState(activator.relative(side)).canBeReplaced()) {
			return false;
		}
		RailShape straight = dir.getAxis() == Direction.Axis.X ? RailShape.EAST_WEST : RailShape.NORTH_SOUTH;
		List<BlockPos> plan = new ArrayList<>();
		List<BlockState> states = new ArrayList<>();
		// Backstop block the powered rail pushes the cart away from.
		plan.add(s);
		states.add(Blocks.COBBLESTONE.defaultBlockState());
		plan.add(s.relative(dir, 1));
		states.add(Blocks.POWERED_RAIL.defaultBlockState().setValue(PoweredRailBlock.SHAPE, straight));
		for (int i = 2; i <= len; i++) {
			plan.add(s.relative(dir, i));
			if (i == len - 1) {
				states.add(Blocks.ACTIVATOR_RAIL.defaultBlockState().setValue(PoweredRailBlock.SHAPE, straight));
			} else {
				states.add(Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, straight));
			}
		}
		// Keeps the activator rail powered so the cart lights as it passes.
		plan.add(activator.relative(side));
		states.add(Blocks.REDSTONE_BLOCK.defaultBlockState());
		brain.rsCartPos = s.relative(dir, 1);
		// The button goes on top of the backstop last, after the cart is on the track.
		start(brain, Kind.TNT_CART, plan, states, s.above(), dir);
		return true;
	}

	private static BlockState button(Direction facing) {
		return Blocks.STONE_BUTTON.defaultBlockState()
			.setValue(FaceAttachedHorizontalDirectionalBlock.FACE, AttachFace.FLOOR)
			.setValue(FaceAttachedHorizontalDirectionalBlock.FACING, facing);
	}

	private static void start(SmartBrain brain, Kind kind, List<BlockPos> plan, List<BlockState> states, BlockPos button, Direction dir) {
		com.mobsminecraft.debug.AiStats.add("rs_build:" + kind.name().toLowerCase());
		brain.rsKind = kind;
		brain.rsPlan = plan;
		brain.rsStates = states;
		brain.rsIndex = 0;
		brain.rsButtonSpot = button;
		brain.rsDir = dir;
		brain.rsButton = null;
	}

	// ------------------------------------------------------------------ building

	private static boolean build(SmartBrain brain, ServerLevel level) {
		Mob mob = brain.mob;
		brain.holdStill();
		if (mob.tickCount % 4 != 0) {
			return true;
		}
		if (brain.rsIndex < brain.rsPlan.size()) {
			BlockPos p = brain.rsPlan.get(brain.rsIndex);
			BlockState s = brain.rsStates.get(brain.rsIndex);
			brain.rsIndex++;
			mob.getLookControl().setLookAt(Vec3.atCenterOf(p));
			boolean placed = brain.place(level, p, s, false);
			boolean essential = s.is(Blocks.DISPENSER) || s.is(Blocks.COBBLESTONE) || s.getBlock() instanceof net.minecraft.world.level.block.BaseRailBlock
				|| brain.rsKind == Kind.CLOCK;
			if (!placed && essential && !level.getBlockState(p).is(s.getBlock())) {
				// Something (usually another mob) is in the way: give up and try again shortly, rather than build half a trap.
				com.mobsminecraft.debug.AiStats.add("rs_blocked");
				brain.rsPlan = null;
				brain.rsCooldown = 60;
				return false;
			}
			if (placed && s.is(Blocks.DISPENSER) && brain.rsKind == Kind.CLOCK && level.getBlockEntity(p) instanceof DispenserBlockEntity turret) {
				loadTurret(turret);
			} else if (placed && s.is(Blocks.DISPENSER) && level.getBlockEntity(p) instanceof DispenserBlockEntity dispenser) {
				dispenser.insertItem(brain.rsKind == Kind.FIRE_CHARGES ? new ItemStack(Items.FIRE_CHARGE, 16) : new ItemStack(Items.ARROW, 64));
			}
			return true;
		}
		// Clock turret: a redstone torch starts the loop pulsing and is pulled out again right away.
		if (brain.rsKind == Kind.CLOCK) {
			if (brain.place(level, brain.rsButtonSpot, Blocks.REDSTONE_TORCH.defaultBlockState(), false)) {
				com.mobsminecraft.world.TimedCleanup.scheduleBlock(level, brain.rsButtonSpot, Blocks.REDSTONE_TORCH, 3);
				com.mobsminecraft.debug.AiStats.add("rs_clock_started");
			}
			// The turret winds down after a minute or so: the repeaters are taken back.
			for (int i = 0; i < brain.rsPlan.size(); i++) {
				if (brain.rsStates.get(i).is(Blocks.REPEATER)) {
					com.mobsminecraft.world.TimedCleanup.scheduleBlock(level, brain.rsPlan.get(i), Blocks.REPEATER, 1400);
				}
			}
			brain.rsPlan = null;
			brain.rsButton = null;
			return false;
		}
		// Plan done: put the TNT cart on the track, then the button that fires it.
		if (brain.rsKind == Kind.TNT_CART && brain.rsCartPos != null) {
			MinecartTNT cart = EntityTypes.TNT_MINECART.create(level, EntitySpawnReason.EVENT);
			if (cart != null) {
				cart.setPos(brain.rsCartPos.getX() + 0.5, brain.rsCartPos.getY(), brain.rsCartPos.getZ() + 0.5);
				level.addFreshEntity(cart);
			}
			brain.rsCartPos = null;
			brain.rsPresses = 1;
		} else {
			brain.rsPresses = 10;
		}
		if (brain.rsKind != Kind.TNT_CART) {
			brain.rsButton = brain.rsButtonSpot;
		} else if (brain.place(level, brain.rsButtonSpot, button(brain.rsDir), false)) {
			brain.rsButton = brain.rsButtonSpot;
		}
		brain.rsPlan = null;
		brain.rsOperateTicks = 0;
		return true;
	}

	// ------------------------------------------------------------------ operating

	/** Stays by the button and presses it: dispensers whenever the player is in front, the cart once. */
	private static boolean operate(SmartBrain brain, ServerLevel level, LivingEntity target) {
		Mob mob = brain.mob;
		BlockState state = level.getBlockState(brain.rsButton);
		String why = brain.rsPresses <= 0 ? "used_up" : ++brain.rsOperateTicks > 400 ? "timeout"
			: !(state.getBlock() instanceof ButtonBlock) ? "no_button:" + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath()
			: mob.distanceToSqr(Vec3.atCenterOf(brain.rsButton)) > 16.0 ? "walked_away" : null;
		boolean done = why != null;
		if (done) {
			com.mobsminecraft.debug.AiStats.add("rs_end:" + why);
			brain.rsButton = null;
			return false;
		}
		brain.holdStill();
		mob.getLookControl().setLookAt(Vec3.atCenterOf(brain.rsButton));
		if (state.getValue(ButtonBlock.POWERED) || brain.rsPressCooldown > 0) {
			brain.rsPressCooldown--;
			return true;
		}
		if (brain.rsKind != Kind.TNT_CART && !inLineOfFire(brain, target)) {
			return true;
		}
		// Doesn't send the cart while an ally is close to the player: it would blow up its own side.
		if (brain.rsKind == Kind.TNT_CART && target != null && !level.getEntitiesOfClass(Mob.class, target.getBoundingBox().inflate(6.0),
			m -> m != mob && m.isAlive() && (m instanceof net.minecraft.world.entity.monster.Enemy) == (mob instanceof net.minecraft.world.entity.monster.Enemy)).isEmpty()) {
			if (mob.tickCount % 40 == 0) com.mobsminecraft.debug.AiStats.add("rs_cart_held:ally_near");
			return true;
		}
		((ButtonBlock) state.getBlock()).press(state, level, brain.rsButton, null);
		level.playSound(null, brain.rsButton, SoundEvents.STONE_BUTTON_CLICK_ON, SoundSource.HOSTILE, 0.6F, 0.6F);
		mob.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
		brain.rsPresses--;
		com.mobsminecraft.debug.AiStats.add("rs_press");
		brain.rsPressCooldown = 10;
		return true;
	}

	/** The player is in front of the dispenser, within a block of its firing line. */
	private static boolean inLineOfFire(SmartBrain brain, LivingEntity target) {
		BlockPos dispenser = brain.rsButton.below();
		Direction dir = brain.rsDir;
		double ahead = (target.getX() - (dispenser.getX() + 0.5)) * dir.getStepX() + (target.getZ() - (dispenser.getZ() + 0.5)) * dir.getStepZ();
		double across = Math.abs((target.getX() - (dispenser.getX() + 0.5)) * dir.getStepZ() - (target.getZ() - (dispenser.getZ() + 0.5)) * dir.getStepX());
		return ahead > 1.0 && ahead < 12.0 && across < 1.0 + ahead * 0.1 && Math.abs(target.getY() + 1.0 - dispenser.getY()) < 2.0;
	}
}
