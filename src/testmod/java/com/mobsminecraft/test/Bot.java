package com.mobsminecraft.test;

import com.mojang.authlib.GameProfile;
import io.netty.channel.ChannelFutureListener;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.UUID;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * A scripted test player. It joins the server like a real survival player (mobs see, target and attack it),
 * but moves only where the playtest script puts it. Damage it takes is recorded and cancelled by the harness.
 */
final class Bot {
	/** A network connection that goes nowhere. */
	private static final class NullConnection extends Connection {
		NullConnection() {
			super(PacketFlow.SERVERBOUND);
		}

		@Override
		public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> protocol, T packetListener) {
		}

		@Override
		public void setupOutboundProtocol(ProtocolInfo<?> protocol) {
		}

		@Override
		public void setListenerForServerboundHandshake(PacketListener packetListener) {
		}

		@Override
		public void send(Packet<?> packet) {
		}

		@Override
		public void send(Packet<?> packet, ChannelFutureListener listener) {
		}

		@Override
		public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {
		}

		@Override
		public void flushChannel() {
		}

		@Override
		public boolean isConnected() {
			return true;
		}

		@Override
		public SocketAddress getRemoteAddress() {
			return new InetSocketAddress("127.0.0.1", 0);
		}

		@Override
		public String getLoggableAddress(boolean logIPs) {
			return "playtest-bot";
		}

		@Override
		public void setReadOnly() {
		}

		@Override
		public void handleDisconnection() {
		}
	}

	final ServerPlayer player;

	private Bot(ServerPlayer player) {
		this.player = player;
	}

	static Bot spawn(MinecraftServer server, ServerLevel level, String name, Vec3 pos) {
		GameProfile profile = new GameProfile(UUID.nameUUIDFromBytes(("bot:" + name).getBytes()), name);
		ClientInformation info = ClientInformation.createDefault();
		ServerPlayer player = new ServerPlayer(server, level, profile, info);
		player.absSnapTo(pos.x, pos.y, pos.z, 0.0F, 0.0F);
		server.getPlayerList().placeNewPlayer(new NullConnection(), player, new CommonListenerCookie(profile, 0, info, false));
		player.setGameMode(GameType.SURVIVAL);
		Bot bot = new Bot(player);
		bot.teleport(pos);
		return bot;
	}

	void remove(MinecraftServer server) {
		server.getPlayerList().remove(this.player);
	}

	void teleport(Vec3 pos) {
		this.player.absSnapTo(pos.x, pos.y, pos.z, this.player.getYRot(), this.player.getXRot());
		this.player.level().getChunkSource().move(this.player);
	}

	/** Moves the bot a step toward a point at walking pace (it doesn't simulate physics). */
	void walkToward(Vec3 goal, double speed) {
		if (this.player.tickCount % 5 == 0) {
			com.mobsminecraft.ai.Awareness.noise(this.player, this.player.position(), 7.0);
		}
		Vec3 here = this.player.position();
		Vec3 d = goal.subtract(here).multiply(1.0, 0.0, 1.0);
		if (d.length() < speed) {
			this.teleport(new Vec3(goal.x, here.y, goal.z));
		} else {
			this.teleport(here.add(d.normalize().scale(speed)));
		}
	}

	void hold(ItemStack stack) {
		this.player.setItemSlot(EquipmentSlot.MAINHAND, stack);
	}

	/** Swings at a mob within reach. */
	void attack(LivingEntity target) {
		com.mobsminecraft.ai.Awareness.noise(this.player, this.player.position(), 16.0);
		this.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, target.getEyePosition());
		this.player.attack(target);
		this.player.resetAttackStrengthTicker();
	}

	/** Runs the per-tick player logic the network handler would normally drive. */
	void tick() {
		this.player.doTick();
	}

	// ================================================================== a player's body
	// Everything below behaves like a real client would: movement with gravity, jumping and collisions; mining that
	// takes as long as it does for a player with that tool; one block placed at a time from the inventory; a bow that
	// has to be drawn; a shield that has to be raised. The server never moves a player on its own, so we do it here.

	static final double REACH = 4.5;
	private static final net.minecraft.world.item.component.SwingAnimation SWING = net.minecraft.world.item.component.SwingAnimation.DEFAULT;

	/**
	 * One tick of movement, the way a player holding the movement keys would move: accelerates toward
	 * {@code wish} (a horizontal direction, or zero to stop), jumps if asked and on the ground, falls, collides.
	 */
	void move(Vec3 wish, boolean sprint, boolean jump, boolean sneak) {
		ServerPlayer p = this.player;
		boolean ground = p.onGround();
		boolean water = p.isInWater() || p.isInLava();
		boolean using = p.isUsingItem();
		double top = sneak ? 0.065 : using ? 0.06 : sprint ? 0.28 : 0.216;
		Vec3 v = p.getDeltaMovement();
		Vec3 want = wish.lengthSqr() > 1.0E-6 ? new Vec3(wish.x, 0.0, wish.z).normalize().scale(top) : Vec3.ZERO;
		double grip = water ? 0.12 : ground ? 0.45 : 0.08;
		double vx = v.x + (want.x - v.x) * grip;
		double vz = v.z + (want.z - v.z) * grip;
		double vy = v.y;
		if (water) {
			vy = (jump ? Math.min(vy + 0.06, 0.15) : vy - 0.02) * 0.85;
		} else if (jump && ground) {
			vy = 0.42;
			if (sprint && want.lengthSqr() > 0) {
				vx += want.x / top * 0.2;
				vz += want.z / top * 0.2;
				p.causeFoodExhaustion(0.2F);
			} else {
				p.causeFoodExhaustion(0.05F);
			}
		}
		p.setSprinting(sprint && !sneak && !using && want.lengthSqr() > 0);
		p.setShiftKeyDown(sneak);
		Vec3 before = p.position();
		p.setDeltaMovement(vx, vy, vz);
		// Sneaking stops you walking off edges, like the real thing.
		Vec3 step = p.getDeltaMovement();
		if (sneak && ground && step.y <= 0) {
			step = this.backOffFromEdge(step);
		}
		double fallBefore = p.fallDistance;
		boolean groundBefore = p.onGround();
		p.move(net.minecraft.world.entity.MoverType.SELF, step);
		// The server only tracks a player's falls from what the client reports; report ours (crits, fall damage).
		if (p.fallDistance == fallBefore && !(groundBefore && p.onGround() && fallBefore == 0)) {
			Vec3 moved = p.position().subtract(before);
			p.doCheckFallDamage(moved.x, moved.y, moved.z, p.onGround());
		}
		Vec3 after = p.getDeltaMovement();
		// Gravity and drag for next tick (vanilla order: move, then gravity).
		double ny = water ? after.y : p.onGround() && after.y < 0 ? 0.0 : (after.y - 0.08) * 0.98;
		double drag = p.onGround() ? 0.55 : 0.91;
		p.setDeltaMovement(after.x * drag, ny, after.z * drag);
		this.level().getChunkSource().move(p);
		if (sprint && p.onGround()) {
			p.causeFoodExhaustion(0.01F * (float) before.distanceTo(p.position()));
		}
		if (p.tickCount % 5 == 0 && !sneak && before.distanceToSqr(p.position()) > 1.0E-3) {
			com.mobsminecraft.ai.Awareness.noise(p, p.position(), sprint ? 12.0 : 7.0);
		}
	}

	private Vec3 backOffFromEdge(Vec3 step) {
		var p = this.player;
		double x = step.x, z = step.z;
		var level = this.level();
		while (x != 0 && level.noCollision(p, p.getBoundingBox().move(x, -1.0, 0))) x = Math.abs(x) < 0.05 ? 0 : x * 0.5;
		while (z != 0 && level.noCollision(p, p.getBoundingBox().move(0, -1.0, z))) z = Math.abs(z) < 0.05 ? 0 : z * 0.5;
		return new Vec3(x, step.y, z);
	}

	/** Pushes the bot back when something hits it (the harness cancels the hit itself). */
	void knockedBack(Vec3 from, double strength) {
		var p = this.player;
		Vec3 d = p.position().subtract(from).multiply(1, 0, 1);
		if (d.lengthSqr() < 1.0E-4) return;
		d = d.normalize().scale(strength);
		Vec3 v = p.getDeltaMovement();
		p.setDeltaMovement(v.x / 2 + d.x, p.onGround() ? Math.min(0.4, v.y / 2 + strength) : v.y, v.z / 2 + d.z);
	}

	ServerLevel level() {
		return (ServerLevel) this.player.level();
	}

	void look(Vec3 at) {
		this.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, at);
		this.player.setYHeadRot(this.player.getYRot());
	}

	// ------------------------------------------------------------------ inventory

	/** Puts the first item matching {@code what} in the main hand (swapping it into the hotbar if needed). */
	boolean select(java.util.function.Predicate<ItemStack> what) {
		var inv = this.player.getInventory();
		if (what.test(inv.getSelectedItem())) return true;
		for (int i = 0; i < 9; i++) {
			if (!inv.getItem(i).isEmpty() && what.test(inv.getItem(i))) {
				this.stopUsing();
				inv.setSelectedSlot(i);
				return true;
			}
		}
		for (int i = 9; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty() && what.test(s)) {
				int slot = 8;
				ItemStack old = inv.getItem(slot);
				inv.setItem(slot, s);
				inv.setItem(i, old);
				this.stopUsing();
				inv.setSelectedSlot(slot);
				return true;
			}
		}
		return false;
	}

	boolean has(java.util.function.Predicate<ItemStack> what) {
		var inv = this.player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (!inv.getItem(i).isEmpty() && what.test(inv.getItem(i))) return true;
		}
		return false;
	}

	// ------------------------------------------------------------------ mining

	private BlockPos miningPos;
	private float miningProgress;

	/**
	 * Keeps mining a block with whatever is best in the inventory for it; returns true once it breaks.
	 * Takes as long as it would for a player (tool, enchantments, on the ground or not, in water or not).
	 */
	boolean mine(BlockPos pos) {
		var p = this.player;
		var level = this.level();
		var state = level.getBlockState(pos);
		if (state.isAir() || state.getDestroySpeed(level, pos) < 0) {
			this.stopMining();
			return state.isAir();
		}
		if (p.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) > REACH + 0.5) {
			this.stopMining();
			return false;
		}
		if (!pos.equals(this.miningPos)) {
			this.stopMining();
			this.miningPos = pos;
			this.miningProgress = 0;
			this.selectToolFor(state);
		}
		this.look(Vec3.atCenterOf(pos));
		this.miningProgress += state.getDestroyProgress(p, level, pos);
		if (p.tickCount % 4 == 0) {
			p.swing(net.minecraft.world.InteractionHand.MAIN_HAND, SWING, true);
			level.playSound(null, pos, state.getSoundType().getHitSound(), net.minecraft.sounds.SoundSource.BLOCKS, 0.25F, 0.5F);
		}
		level.destroyBlockProgress(p.getId(), pos, Math.min(9, (int) (this.miningProgress * 10)));
		if (this.miningProgress >= 1.0F) {
			level.destroyBlockProgress(p.getId(), pos, -1);
			this.miningPos = null;
			this.miningProgress = 0;
			return p.gameMode.destroyBlock(pos);
		}
		return false;
	}

	void stopMining() {
		if (this.miningPos != null) {
			this.level().destroyBlockProgress(this.player.getId(), this.miningPos, -1);
		}
		this.miningPos = null;
		this.miningProgress = 0;
	}

	boolean isMining() {
		return this.miningPos != null;
	}

	private void selectToolFor(net.minecraft.world.level.block.state.BlockState state) {
		var inv = this.player.getInventory();
		int best = -1;
		float bestSpeed = 1.0F;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			float speed = s.isEmpty() ? 1.0F : s.getDestroySpeed(state);
			if (speed > bestSpeed) {
				bestSpeed = speed;
				best = i;
			}
		}
		if (best >= 0) {
			ItemStack tool = inv.getItem(best);
			this.select(s -> s == tool);
		}
	}

	// ------------------------------------------------------------------ building

	private int placeCooldown;

	/**
	 * Places one block from the inventory (one every 4 ticks at most, like right-click held down). It has to be in reach,
	 * free of entities, and against something solid, as for a player. Returns true if it was placed.
	 */
	boolean place(BlockPos pos, java.util.function.Predicate<ItemStack> which) {
		var p = this.player;
		var level = this.level();
		if (--this.placeCooldown > 0) return false;
		if (!level.getBlockState(pos).canBeReplaced() || p.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) > REACH + 0.5) return false;
		boolean against = false;
		for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
			if (!level.getBlockState(pos.relative(d)).canBeReplaced()) {
				against = true;
				break;
			}
		}
		if (!against || !this.select(s -> s.getItem() instanceof net.minecraft.world.item.BlockItem && which.test(s))) return false;
		ItemStack stack = p.getMainHandItem();
		var state = ((net.minecraft.world.item.BlockItem) stack.getItem()).getBlock().defaultBlockState();
		// Solid blocks can't go where someone is standing (torches and the like can).
		if (!state.getCollisionShape(level, pos).isEmpty()
			&& !level.getEntities(null, new net.minecraft.world.phys.AABB(pos)).stream().filter(e -> e.blocksBuilding).toList().isEmpty()) return false;
		this.look(Vec3.atCenterOf(pos));
		if (!level.setBlock(pos, state, net.minecraft.world.level.block.Block.UPDATE_ALL)) return false;
		stack.shrink(1);
		p.swing(net.minecraft.world.InteractionHand.MAIN_HAND, SWING, true);
		level.playSound(null, pos, state.getSoundType().getPlaceSound(), net.minecraft.sounds.SoundSource.BLOCKS, 1.0F, 0.8F);
		this.placeCooldown = 4;
		return true;
	}

	// ------------------------------------------------------------------ fighting

	/** Melee only when the swing is (nearly) recharged; a hit while falling is a critical hit, as in vanilla. */
	boolean swingAt(LivingEntity target) {
		return this.swingAt(target, false);
	}

	/** As above; {@code axe} swings an axe instead of the sword (an axe knocks a raised shield out of use). */
	boolean swingAt(LivingEntity target, boolean axe) {
		var p = this.player;
		if (p.getAttackStrengthScale(0.5F) < 0.95F || !this.inReach(target) || p.isUsingItem() && !p.getUseItem().is(net.minecraft.world.item.Items.SHIELD)) {
			return false;
		}
		this.stopUsing();
		if (!axe || !this.select(s -> s.is(net.minecraft.tags.ItemTags.AXES))) {
			this.select(s -> s.is(net.minecraft.tags.ItemTags.SWORDS));
		}
		this.attack(target);
		return true;
	}

	/** Vanilla melee reach: 3 blocks from the eyes to the nearest point of the target's hitbox. */
	boolean inReach(LivingEntity target) {
		Vec3 eye = this.player.getEyePosition();
		var box = target.getBoundingBox();
		double x = Math.max(box.minX, Math.min(eye.x, box.maxX));
		double y = Math.max(box.minY, Math.min(eye.y, box.maxY));
		double z = Math.max(box.minZ, Math.min(eye.z, box.maxZ));
		return eye.distanceToSqr(x, y, z) <= 9.0;
	}

	/** Raises the shield in the off hand (it blocks after a moment, like the real one). */
	void raiseShield() {
		var p = this.player;
		if (!p.getOffhandItem().is(net.minecraft.world.item.Items.SHIELD)) return;
		if (!p.isUsingItem()) {
			p.startUsingItem(net.minecraft.world.InteractionHand.OFF_HAND);
			this.shieldSince = p.tickCount;
		}
	}

	private int shieldSince;

	/** True when the shield is up and has been for the 5 ticks vanilla needs before it blocks. */
	boolean shielding() {
		var p = this.player;
		return p.isUsingItem() && p.getUseItem().is(net.minecraft.world.item.Items.SHIELD) && p.tickCount - this.shieldSince >= 5;
	}

	void stopUsing() {
		if (this.player.isUsingItem()) this.player.stopUsingItem();
	}

	private int drawTicks = -1;

	/** Starts (or keeps) drawing the bow. Needs a bow and arrows. */
	boolean drawBow() {
		var p = this.player;
		if (!this.has(s -> s.is(net.minecraft.tags.ItemTags.ARROWS))) return false;
		if (this.drawTicks < 0 || !p.isUsingItem() || !p.getUseItem().is(net.minecraft.world.item.Items.BOW)) {
			this.stopUsing();
			if (!this.select(s -> s.is(net.minecraft.world.item.Items.BOW))) return false;
			p.startUsingItem(net.minecraft.world.InteractionHand.MAIN_HAND);
			this.drawTicks = 0;
		}
		this.drawTicks++;
		return true;
	}

	int drawTicks() {
		return this.drawTicks;
	}

	/** Lets the arrow go at {@code target}, aiming ahead of it and above it for the arrow's drop. */
	void loose(LivingEntity target) {
		var p = this.player;
		Vec3 eye = p.getEyePosition();
		Vec3 aim = target.getBoundingBox().getCenter();
		double dist = eye.distanceTo(aim);
		double t = dist / 2.8; // ticks in flight at full draw
		aim = aim.add(target.getDeltaMovement().multiply(t, 0, t)).add(0, 0.025 * t * t, 0);
		this.look(aim);
		p.releaseUsingItem();
		this.drawTicks = -1;
		com.mobsminecraft.ai.Awareness.noise(p, p.position(), 12.0);
	}

	void cancelBow() {
		if (this.drawTicks >= 0) {
			this.stopUsing();
			this.drawTicks = -1;
		}
	}
}
