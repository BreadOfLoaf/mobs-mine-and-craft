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
		this.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, target.getEyePosition());
		this.player.attack(target);
		this.player.resetAttackStrengthTicker();
	}

	/** Runs the per-tick player logic the network handler would normally drive. */
	void tick() {
		this.player.doTick();
	}
}
