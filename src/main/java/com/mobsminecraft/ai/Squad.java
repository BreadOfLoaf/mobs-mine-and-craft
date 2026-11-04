package com.mobsminecraft.ai;

import com.mobsminecraft.debug.AiStats;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Hostile mobs that know about the same player band together and fight as a team, each doing its part.
 * <ol>
 * <li><b>Gather</b>: instead of trickling in one at a time, they meet up at a rally point (the leader) until most of
 * the squad is there, then go in together. An ambush (the player walking into them, or hitting one) starts it early.</li>
 * <li><b>Attack</b>, by role:
 * <ul>
 * <li>front line (zombies, fighters, heroes, Juggernauts): spread out and hit from several sides at once;</li>
 * <li>flankers (tacticians): come round the sides and back;</li>
 * <li>archers (skeletons): hang back at bow range on the squad's side, behind the front line;</li>
 * <li>bombers (creepers): wait out of the way and only close in once the player is tied up in melee, from behind;</li>
 * <li>support (crafters): stay just behind the front line, crafting gear for and healing the fighters;</li>
 * <li>builders: put up cover for the archers;</li>
 * <li>engineers: set their traps on the far side of the player, where they'll back off to.</li>
 * </ul></li>
 * <li><b>Regroup</b>: a squad that has lost most of its members falls back together, picks up reinforcements, and
 * comes again.</li>
 * </ol>
 * Everything goes by what members honestly know: the player has to have been seen, heard or called out.
 */
public final class Squad {
	enum Role { FRONT, FLANK, RANGED, BOMBER, SUPPORT, BUILDER, ENGINEER }

	enum Phase { GATHER, ATTACK, REGROUP }

	private static final int MAX_SIZE = 12;
	/** Test switch: false turns teamwork off (each mob for itself, as before). */
	public static boolean enabled = true;

	final Player target;
	final List<SmartBrain> members = new ArrayList<>();
	SmartBrain leader;
	Phase phase = Phase.GATHER;
	long phaseStart;
	long lastUpdate = -1;
	Vec3 rally;
	int peak;
	boolean disbanded;

	private Squad(Player target, long now) {
		this.target = target;
		this.phaseStart = now;
	}

	// ================================================================== membership

	/** Can this mob be part of a squad at all? */
	static boolean eligible(SmartBrain b) {
		Mob mob = b.mob;
		if (!(mob instanceof Enemy) || !mob.isAlive() || mob.isPassenger() || b.knightOf != null) return false;
		if (!(BlockWork.canBuild(mob) || mob instanceof Creeper || mob instanceof net.minecraft.world.entity.monster.spider.Spider)) return false;
		Personality p = b.personality;
		// Builders and engineers do their own jobs (houses, traps) rather than squad duty.
		if (p.peaceful() || p == Personality.GRIEFER || p == Personality.BUILDER || p == Personality.ENGINEER) return false;
		// Busy builders and crafters finish what they're doing first.
		return b.housePlan == null && b.helpingWith == null && b.crafterItem == null;
	}

	private static Role roleOf(SmartBrain b, Squad s) {
		Mob mob = b.mob;
		if (mob instanceof Creeper) return Role.BOMBER;
		switch (b.personality) {
			case CRAFTER -> { return Role.SUPPORT; }
			case BUILDER -> { return Role.BUILDER; }
			case ENGINEER -> { return Role.ENGINEER; }
			default -> {}
		}
		if (mob instanceof RangedAttackMob) return Role.RANGED;
		if (b.personality == Personality.TACTICIAN) {
			long flankers = s.members.stream().filter(m -> m.squadRole == Role.FLANK).count();
			long front = s.members.stream().filter(m -> m.squadRole == Role.FRONT).count();
			if (flankers < front) return Role.FLANK;
		}
		return Role.FRONT;
	}

	private void add(SmartBrain b) {
		if (this.members.contains(b) || this.members.size() >= MAX_SIZE) return;
		b.squad = this;
		b.squadRole = roleOf(b, this);
		b.squadSlot = (int) this.members.stream().filter(m -> m.squadRole == b.squadRole).count();
		this.members.add(b);
		this.peak = Math.max(this.peak, this.members.size());
	}

	private static void leave(SmartBrain b) {
		b.squad = null;
		b.squadRole = null;
		b.squadHold = false;
	}

	private void disband(String why) {
		for (SmartBrain m : this.members) leave(m);
		this.members.clear();
		this.disbanded = true;
		AiStats.add("squad_disband:" + why);
	}

	/** Joins a squad hunting the player this mob knows about, or forms one with allies who know too. */
	private static void findOrForm(SmartBrain b, ServerLevel level) {
		Player p = b.knownPlayer;
		long now = level.getGameTime();
		if (p == null || !p.isAlive() || p.isCreative() || p.isSpectator() || now - b.knownTick > 200 || p.distanceTo(b.mob) > 40.0F) return;
		List<SmartBrain> free = new ArrayList<>();
		for (Mob m : level.getEntitiesOfClass(Mob.class, b.mob.getBoundingBox().inflate(24.0), m -> m != b.mob && m.isAlive() && m instanceof Enemy)) {
			SmartBrain o = ((com.mobsminecraft.SmartMobHolder) m).mmc$getBrain();
			if (o.squad != null && !o.squad.disbanded && o.squad.target == p && o.squad.members.size() < MAX_SIZE) {
				o.squad.add(b);
				AiStats.add("squad_joined");
				return;
			}
			if (o.squad == null && eligible(o) && m.distanceTo(b.mob) < 24.0F) free.add(o);
		}
		// Needs to have actually seen the player (not just heard of it) to call the others over.
		if (free.isEmpty() || now - b.lastSeenTick > 100) return;
		Squad s = new Squad(p, now);
		s.add(b);
		for (SmartBrain o : free) {
			s.add(o);
			// The call: allies learn where the player is from the one who saw them.
			if (o.knownPlayer != p || o.knownTick < b.knownTick) {
				o.knownPlayer = p;
				o.knownPos = b.knownPos;
				o.knownTick = b.knownTick;
			}
		}
		s.pickLeader();
		s.rally = s.leader.mob.position();
		AiStats.add("squad_formed");
		AiStats.add("squad_size_" + Math.min(s.members.size(), 9));
	}

	private void pickLeader() {
		SmartBrain best = null;
		int bestRank = -1;
		for (SmartBrain m : this.members) {
			int rank = (Juggernaut.isJuggernaut(m.mob) ? 100 : 0) + switch (m.personality) {
				case HERO -> 40;
				case TACTICIAN -> 30;
				case FIGHTER -> 20;
				default -> 0;
			} + (m.mob instanceof Creeper ? -50 : 0) + (int) m.mob.getMaxHealth();
			if (rank > bestRank) {
				bestRank = rank;
				best = m;
			}
		}
		this.leader = best;
	}

	// ================================================================== the squad as a whole

	private void update(ServerLevel level) {
		long now = level.getGameTime();
		if (this.lastUpdate == now) return;
		this.lastUpdate = now;
		this.members.removeIf(m -> {
			boolean gone = !m.mob.isAlive() || m.mob.isRemoved() || m.mob.distanceTo(this.target) > 56.0F;
			if (gone) leave(m);
			return gone;
		});
		if (this.members.size() < 2) {
			this.disband("too_few");
			return;
		}
		if (!this.target.isAlive() || this.target.isSpectator() || this.target.isCreative()) {
			this.disband("target_gone");
			return;
		}
		long freshest = Long.MIN_VALUE;
		for (SmartBrain m : this.members) {
			if (m.knownPlayer == this.target) freshest = Math.max(freshest, m.knownTick);
		}
		if (now - freshest > 400) {
			this.disband("lost_track");
			return;
		}
		// Members who know share what they know: a squad is as aware as its best-informed member.
		for (SmartBrain m : this.members) {
			if (m.knownPlayer != this.target || m.knownTick < freshest - 20) {
				SmartBrain informed = null;
				for (SmartBrain o : this.members) if (o.knownPlayer == this.target && o.knownTick == freshest) informed = o;
				if (informed != null) {
					m.knownPlayer = this.target;
					m.knownPos = informed.knownPos;
					m.knownTick = informed.knownTick;
				}
			}
		}
		if (this.leader == null || !this.members.contains(this.leader)) this.pickLeader();
		Vec3 player = this.knownPos();
		switch (this.phase) {
			case GATHER -> {
				if (now % 20 == 0 && this.leader.mob.distanceTo(this.target) > 14.0F) this.rally = this.leader.mob.position();
				int there = 0;
				int seen = 0;
				boolean ambushed = false;
				for (SmartBrain m : this.members) {
					if (m.mob.position().distanceTo(this.rally) < 7.0) there++;
					if (m.mob.distanceTo(this.target) < 22.0F && this.target.hasLineOfSight(m.mob)) seen++;
					if (m.mob.distanceTo(this.target) < 8.0F && m.mob.hasLineOfSight(this.target)
						|| m.mob.getLastHurtByMob() == this.target && m.mob.tickCount - m.mob.getLastHurtByMobTimestamp() < 40
						|| m.fireFrom == this.target && m.mob.tickCount < m.underFireUntil) ambushed = true;
				}
				// Gathering only makes sense far off and out of sight: once the player can see a few of us, or is close, go now.
				if (seen >= 2) ambushed = true;
				for (SmartBrain m : this.members) if (m.mob.distanceTo(this.target) < 12.0F) ambushed = true;
				if (there >= Math.ceil(this.members.size() * 0.75) || now - this.phaseStart > 240 || ambushed) {
					this.phase = Phase.ATTACK;
					this.phaseStart = now;
					this.peak = this.members.size();
					AiStats.add(ambushed ? "squad_attack_ambushed" : "squad_attack");
					AiStats.add("squad_attack_size_" + Math.min(this.members.size(), 9));
					level.playSound(null, this.leader.mob.blockPosition(), SoundEvents.ZOMBIE_AMBIENT, SoundSource.HOSTILE, 1.5F, 0.6F); // the war cry
				}
			}
			case ATTACK -> {
				if (now % 10 == 0) this.assignRing(player);
				// Lost most of the squad and the player's still standing: fall back together.
				if (this.peak >= 3 && this.members.size() <= Math.max(1, this.peak * 2 / 5) && this.target.getHealth() > this.target.getMaxHealth() * 0.4F) {
					this.phase = Phase.REGROUP;
					this.phaseStart = now;
					Vec3 c = this.centroid();
					Vec3 away = c.subtract(player).multiply(1, 0, 1);
					if (away.lengthSqr() < 1.0E-4) away = new Vec3(1, 0, 0);
					this.rally = player.add(away.normalize().scale(26.0));
					AiStats.add("squad_regroup");
				}
			}
			case REGROUP -> {
				int there = 0;
				for (SmartBrain m : this.members) if (m.mob.position().distanceTo(this.rally) < 7.0) there++;
				if (now - this.phaseStart > 300 || there >= 3 && now - this.phaseStart > 100) {
					this.phase = Phase.GATHER;
					this.phaseStart = now;
					AiStats.add("squad_regathered");
				}
			}
		}
	}

	private static final double RING = 7.0;

	/** Spreads the melee members evenly round the player, keeping each one's order by where it already stands. */
	private void assignRing(Vec3 player) {
		List<SmartBrain> melee = new ArrayList<>();
		for (SmartBrain m : this.members) if (m.squadRole == Role.FRONT || m.squadRole == Role.FLANK) melee.add(m);
		if (melee.isEmpty()) return;
		melee.sort(java.util.Comparator.comparingDouble(m -> Math.atan2(m.mob.getZ() - player.z, m.mob.getX() - player.x)));
		int n = melee.size();
		// Start from the first one's bearing so nobody has to cross over.
		double start = Math.atan2(melee.get(0).mob.getZ() - player.z, melee.get(0).mob.getX() - player.x);
		double step = n >= 3 ? Math.PI * 2 / n : Math.PI / 2.5;
		for (int i = 0; i < n; i++) melee.get(i).squadAngle = start + i * step;
	}

	private Vec3 ringSpot(SmartBrain m, Vec3 player, double radius) {
		return player.add(Math.cos(m.squadAngle) * radius, 0, Math.sin(m.squadAngle) * radius);
	}

	private Vec3 knownPos() {
		Vec3 best = null;
		long t = Long.MIN_VALUE;
		for (SmartBrain m : this.members) {
			if (m.knownPlayer == this.target && m.knownPos != null && m.knownTick > t) {
				t = m.knownTick;
				best = m.knownPos;
			}
		}
		return best != null ? best : this.target.position();
	}

	private Vec3 centroid() {
		Vec3 c = Vec3.ZERO;
		for (SmartBrain m : this.members) c = c.add(m.mob.position());
		return c.scale(1.0 / this.members.size());
	}

	/** The front-line members' centroid (or the whole squad's). */
	private Vec3 frontCentroid() {
		Vec3 c = Vec3.ZERO;
		int n = 0;
		for (SmartBrain m : this.members) {
			if (m.squadRole == Role.FRONT || m.squadRole == Role.FLANK) {
				c = c.add(m.mob.position());
				n++;
			}
		}
		return n == 0 ? this.centroid() : c.scale(1.0 / n);
	}

	private boolean playerEngaged() {
		for (SmartBrain m : this.members) {
			if ((m.squadRole == Role.FRONT || m.squadRole == Role.FLANK) && m.mob.distanceTo(this.target) < 3.5F) return true;
		}
		return false;
	}

	// ================================================================== each member's part

	/** Runs this mob's part in its squad (joining or forming one first). Returns true if it took over this tick. */
	static boolean tick(SmartBrain b, ServerLevel level) {
		if (!enabled) {
			b.squadHold = false;
			b.squad = null;
			return false;
		}
		if (b.squad == null) {
			b.squadHold = false;
			if (b.mob.tickCount % 20 == 0 && eligible(b)) findOrForm(b, level);
			if (b.squad == null) return false;
		}
		Squad s = b.squad;
		if (s.disbanded) {
			leave(b);
			return false;
		}
		s.update(level);
		if (b.squad == null) return false;
		boolean took = s.act(b, level);
		b.squadHold = took;
		return took;
	}

	private boolean act(SmartBrain b, ServerLevel level) {
		Mob mob = b.mob;
		Player p = this.target;
		double dist = mob.distanceTo(p);
		// Being hit, or the player right on top of us: fight now, whatever the plan was.
		if (dist < 4.0 || mob.getLastHurtByMob() == p && mob.tickCount - mob.getLastHurtByMobTimestamp() < 60) return false;
		Vec3 player = this.knownPos();
		switch (this.phase) {
			case GATHER, REGROUP -> {
				// Archers that can already see the player may as well shoot from where they are.
				if (b.squadRole == Role.RANGED && mob.hasLineOfSight(p) && dist < 18.0 && this.phase == Phase.GATHER) return false;
				if (mob.position().distanceTo(this.rally) > 3.5) {
					b.walkTo(this.rally, b.walkSpeed() * (this.phase == Phase.REGROUP ? 1.3 : 1.05));
				} else {
					b.holdStill();
					mob.getLookControl().setLookAt(player.x, player.y + 1.0, player.z);
				}
				return true;
			}
			case ATTACK -> {
				return this.attack(b, level, player, dist);
			}
		}
		return false;
	}

	private boolean attack(SmartBrain b, ServerLevel level, Vec3 player, double dist) {
		Mob mob = b.mob;
		Player p = this.target;
		Vec3 fromSquad = this.centroid().subtract(player).multiply(1, 0, 1);
		if (fromSquad.lengthSqr() < 1.0E-4) fromSquad = mob.position().subtract(player).multiply(1, 0, 1);
		if (fromSquad.lengthSqr() < 1.0E-4) fromSquad = new Vec3(1, 0, 0);
		double base = Math.atan2(fromSquad.z, fromSquad.x);
		switch (b.squadRole) {
			case FRONT, FLANK -> {
				if (dist < 4.5) return false;
				// Each comes in from its own side of the player, so they hit from all round at once. No waiting about:
				// far out it heads for its side of the ring, close in it goes straight for the player.
				Vec3 side = this.ringSpot(b, player, RING);
				if (dist < 11.0 || mob.position().distanceTo(side) < 3.0) return false;
				Vec3 to = side.subtract(mob.position());
				Vec3 mid = mob.position().add(to.scale(0.5));
				// Round, not through: via a point further out if the straight line passes near the player.
				Vec3 goal = mid.distanceTo(player) < RING - 1.0 ? player.add(mid.subtract(player).multiply(1, 0, 1).normalize().scale(RING + 2.0)) : side;
				b.walkTo(goal, b.walkSpeed() * 1.2);
				if (mob.tickCount % 40 == 0) AiStats.add("squad_encircling");
				return true;
			}
			case RANGED -> {
				// Their own tricks (cover, sniper perches, holding fire) do the work up close; the squad just brings
				// them in on its side of the player.
				if (dist > 20.0) {
					double a = base + (b.squadSlot % 2 == 0 ? 0.35 : -0.35) * (1 + b.squadSlot / 2);
					b.walkTo(player.add(Math.cos(a) * 12.0, 0, Math.sin(a) * 12.0), b.walkSpeed());
					return true;
				}
				return false; // in position: let it shoot
			}
			case BOMBER -> {
				// Never stands about (it'd just get shot): swings round behind the player while the fighters keep them
				// busy, then goes in from their back.
				if (this.playerEngaged() || dist < 6.0) return false;
				Vec3 back = player.add(Math.cos(base + Math.PI) * 5.0, 0, Math.sin(base + Math.PI) * 5.0);
				if (mob.position().distanceTo(back) < 2.5) return false;
				Vec3 to = back.subtract(mob.position());
				Vec3 mid = mob.position().add(to.scale(0.5));
				Vec3 goal = mid.distanceTo(player) < 6.0 ? player.add(mid.subtract(player).multiply(1, 0, 1).normalize().scale(8.0)) : back;
				b.walkTo(goal, b.walkSpeed() * 1.2);
				if (mob.tickCount % 40 == 0) AiStats.add("squad_bomber_flanking");
				return true;
			}
			case SUPPORT -> {
				// Just behind the fighters, so it can craft for them and heal them.
				Vec3 front = this.frontCentroid();
				Vec3 back = front.subtract(player).multiply(1, 0, 1);
				back = back.lengthSqr() < 1.0E-4 ? fromSquad.normalize() : back.normalize();
				Vec3 spot = front.add(back.scale(5.0));
				if (mob.position().distanceTo(spot) > 8.0 && dist < 30.0) {
					b.walkTo(spot, b.walkSpeed() * 1.1);
					return true;
				}
				return false;
			}
			case BUILDER -> {
				// Cover for an archer that doesn't have any.
				if (b.squadCoverCooldown <= 0) {
					for (SmartBrain m : this.members) {
						if (m.squadRole != Role.RANGED || m.wallPlan != null || m.mob.distanceTo(p) > 20.0F) continue;
						if (mob.distanceTo(m.mob) > 3.0F) {
							b.walkTo(m.mob.position(), b.walkSpeed() * 1.2);
							return true;
						}
						if (mob.onGround() && b.wallPlan == null && b.blocks >= 4
							&& level.getGameRules().get(net.minecraft.world.level.gamerules.GameRules.MOB_GRIEFING)) {
							b.planWall(level, p, 2, 1, 2, b.buildState(), false);
							b.squadCoverCooldown = 300;
							AiStats.add("squad_archer_cover");
						}
						return false;
					}
				}
				// Otherwise behind the front line like support (and ready to bridge or tower if the player hides up high).
				Vec3 front = this.frontCentroid();
				Vec3 spot = front.add(front.subtract(player).multiply(1, 0, 1).normalize().scale(4.0));
				if (mob.position().distanceTo(spot) > 3.5 && dist > 8.0) {
					b.walkTo(spot, b.walkSpeed());
					return true;
				}
				return false;
			}
			case ENGINEER -> {
				// Round to the far side of the player: that's where they'll back off to, into the traps.
				if (b.rsPlan != null || b.rsButton != null || b.trapPlan != null) return false;
				Vec3 behind = player.add(Math.cos(base + Math.PI) * 8.0, 0, Math.sin(base + Math.PI) * 8.0);
				if (mob.position().distanceTo(behind) > 4.0) {
					// Swing wide rather than walking through the player.
					double side = (b.squadSlot % 2 == 0 ? 1 : -1) * Math.PI / 2;
					Vec3 via = player.add(Math.cos(base + side) * 11.0, 0, Math.sin(base + side) * 11.0);
					b.walkTo(mob.position().distanceTo(via) > 4.0 && mob.position().distanceTo(behind) > 9.0 ? via : behind, b.walkSpeed() * 1.1);
					if (mob.tickCount % 40 == 0) AiStats.add("squad_engineer_circling");
					return true;
				}
				return false;
			}
		}
		return false;
	}
}
