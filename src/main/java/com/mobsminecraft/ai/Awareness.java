package com.mobsminecraft.ai;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * What a mob actually knows about where the player is. No wallhacks: a mob knows the player's position only if it
 * can see them, hears them (footsteps close by, blocks breaking, fighting), or an ally that saw them called it out.
 * After that it remembers the last known position for a while, goes to look, and loses interest if nothing turns up.
 */
public final class Awareness {
	/** A noise a player made: where, when, and how far it carries. */
	private record Noise(Player player, Vec3 pos, long tick, double radius) {}

	private static final Map<ServerLevel, Deque<Noise>> NOISES = new WeakHashMap<>();
	static final int MEMORY_TICKS = 600;

	private Awareness() {}

	/** Records a noise (block broken, hit landed, explosion...) made by a player. */
	public static void noise(Player player, Vec3 pos, double radius) {
		if (!(player.level() instanceof ServerLevel level) || player.isCreative() || player.isSpectator()) {
			return;
		}
		Deque<Noise> q = NOISES.computeIfAbsent(level, l -> new ArrayDeque<>());
		q.addLast(new Noise(player, pos, level.getGameTime(), radius));
		while (q.size() > 256) {
			q.pollFirst();
		}
	}

	/** Footsteps: a walking player can be heard close by; sneaking is nearly silent. Called every few ticks. */
	public static void footsteps(ServerLevel level) {
		for (Player p : level.players()) {
			if (p.isSpectator() || p.isCreative() || !p.onGround()) continue;
			if (p.getKnownMovement().horizontalDistanceSqr() > 1.0E-4) {
				noise(p, p.position(), p.isShiftKeyDown() ? 2.5 : p.isSprinting() ? 12.0 : 7.0);
			}
		}
	}

	/** Updates what this mob knows. Called once a tick from the brain. */
	static void tick(SmartBrain b, ServerLevel level) {
		Mob mob = b.mob;
		long now = level.getGameTime();
		// Sight: the player it's targeting, or any player in plain view nearby.
		if (mob.tickCount % 5 == 0) {
			Player seen = null;
			if (mob.getTarget() instanceof Player t && mob.getSensing().hasLineOfSight(t)) {
				seen = t;
			} else {
				Player p = level.getNearestPlayer(mob.getX(), mob.getY(), mob.getZ(), 24.0, EntitySelector.NO_CREATIVE_OR_SPECTATOR.and(e -> e.isAlive()));
				if (p != null && mob.getSensing().hasLineOfSight(p) && !p.isInvisible()) {
					seen = p;
				}
			}
			if (seen != null) {
				boolean fresh = now - b.lastSeenTick > 200;
				b.knownPlayer = seen;
				b.knownPos = seen.position();
				b.knownTick = now;
				b.lastSeenTick = now;
				if (fresh && mob instanceof Enemy) {
					callOut(b, level, seen);
				}
			}
		}
		// Hearing.
		if (mob.tickCount % 10 == 0) {
			Deque<Noise> q = NOISES.get(level);
			if (q != null) {
				for (Noise n : q) {
					if (now - n.tick() <= 10 && n.player().isAlive() && mob.position().distanceToSqr(n.pos()) <= n.radius() * n.radius()) {
						b.knownPlayer = n.player();
						// You hear roughly where a sound came from, not the exact block.
						b.knownPos = n.pos().add(mob.getRandom().nextGaussian(), 0.0, mob.getRandom().nextGaussian());
						b.knownTick = now;
						com.mobsminecraft.debug.AiStats.add("heard_player");
					}
				}
			}
		}
		if (b.knownPlayer != null && (now - b.knownTick > MEMORY_TICKS || !b.knownPlayer.isAlive())) {
			b.knownPlayer = null;
			b.knownPos = null;
		}
	}

	/** Spotted the player: a shout, and allies close by learn where they are too. */
	private static void callOut(SmartBrain b, ServerLevel level, Player seen) {
		Mob mob = b.mob;
		mob.playAmbientSound();
		int told = 0;
		for (Mob ally : level.getEntitiesOfClass(Mob.class, mob.getBoundingBox().inflate(16.0), m -> m != mob && m.isAlive() && m instanceof Enemy)) {
			SmartBrain ab = ((com.mobsminecraft.SmartMobHolder) ally).mmc$getBrain();
			if (ab.knownPlayer == null || level.getGameTime() - ab.knownTick > 40) {
				ab.knownPlayer = seen;
				ab.knownPos = seen.position();
				ab.knownTick = level.getGameTime();
				told++;
			}
		}
		if (told > 0) {
			com.mobsminecraft.debug.AiStats.add("callout");
		}
	}

	/** True if the mob can see the player right now. */
	static boolean sees(SmartBrain b, Player p) {
		return p != null && b.lastSeenTick >= b.mob.level().getGameTime() - 5 && b.knownPlayer == p;
	}

	/** The player this mob knows about (seen, heard or told), or null. */
	static Player known(SmartBrain b) {
		return b.knownPlayer;
	}

	/** Is the mob's knowledge of where this player is fresh and accurate enough to act on (dig, breach)? */
	static boolean knowsWhere(SmartBrain b, Player p, int maxAge) {
		return p != null && p == b.knownPlayer && b.knownPos != null
			&& b.mob.level().getGameTime() - b.knownTick <= maxAge && b.knownPos.distanceToSqr(p.position()) < 25.0;
	}
}
