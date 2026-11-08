package com.mobsminecraft.test;

import com.mobsminecraft.debug.AiStats;
import java.util.Map;
import java.util.TreeMap;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** /endtest: a player bot on the End's main island against the Boss dragon. */
final class EndTest {
	static EndTest active;
	private final MinecraftServer server;
	private final ServerLevel end;
	private Bot bot;
	private int ticks;
	private final Map<String, Double> damageBy = new TreeMap<>();
	private int hits;
	private int dragonsAtStart = -1;
	private final StringBuilder log = new StringBuilder();

	static void register() {
		ServerTickEvents.END_SERVER_TICK.register(s -> {
			if (active != null) active.tick();
		});
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
			if (active != null && active.bot != null && entity == active.bot.player) {
				active.hits++;
				String who = (source.getEntity() != null ? BuiltInRegistries.ENTITY_TYPE.getKey(source.getEntity().getType()).getPath() : "none")
					+ "/" + (source.getDirectEntity() != null ? BuiltInRegistries.ENTITY_TYPE.getKey(source.getDirectEntity().getType()).getPath() : source.type().msgId());
				active.damageBy.merge(who, (double) amount, Double::sum);
				return false;
			}
			return true;
		});
	}

	EndTest(MinecraftServer server) {
		this.server = server;
		this.end = server.getLevel(Level.END);
	}

	private void cmd(String c) {
		this.server.getCommands().performPrefixedCommand(this.server.createCommandSourceStack().withSuppressedOutput(), c);
	}

	private void note(String fmt, Object... args) {
		String line = String.format(java.util.Locale.ROOT, fmt, args);
		this.log.append(line).append('\n');
		Playtest.LOG.info("[endtest] {}", line);
	}

	void start() {
		AiStats.reset();
		cmd("gamerule mob_griefing true");
		cmd("difficulty normal");
		cmd("execute in minecraft:the_end run forceload add -64 -64 64 64");
		this.end.getChunk(0, 2);
		int y = this.end.getHeight(Heightmap.Types.MOTION_BLOCKING, 0, 40);
		this.bot = Bot.spawn(this.server, this.end, "EndTester", new Vec3(0.5, y, 40.5));
		PlayerBrain.giveKit(this.bot);
		cmd("tick sprint 3000");
	}

	private final Map<String, Integer> phases = new TreeMap<>();
	private int linked;
	private int unlinked;
	private final Map<String, Integer> endermen = new TreeMap<>();

	private void tick() {
		this.ticks++;
		this.bot.tick();
		if (this.ticks == 1500) {
			// Like a stray duplicate: the real dragon gone, an untracked one in its place.
			for (EnderDragon d : this.end.getDragons()) d.discard();
			cmd("execute in minecraft:the_end run summon ender_dragon 0 100 0");
			EnderDragon d = this.end.getDragons().isEmpty() ? null : this.end.getDragons().get(0);
			note("swapped in an untracked dragon: linked=%s", d != null && d.getDragonFight() != null);
		}
		if (this.ticks == 1560) {
			// And a wrong arena centre, so it circles somewhere else (as a stuck dragon would).
			for (EnderDragon d : this.end.getDragons()) d.setFightOrigin(new net.minecraft.core.BlockPos(120, 0, 120));
			note("gave the dragon a wrong arena centre (120, 120)");
			this.phases.clear();
			this.linked = 0;
			this.unlinked = 0;
		}
		if (this.ticks > 320 && this.ticks % 20 == 0) {
			for (EnderDragon d : this.end.getDragons()) {
				this.phases.merge(d.getPhaseManager().getCurrentPhase().getPhase().toString(), 1, Integer::sum);
				if (d.getDragonFight() != null) this.linked++; else this.unlinked++;
			}
			for (Enderman e : this.end.getEntitiesOfClass(Enderman.class, this.bot.player.getBoundingBox().inflate(32))) {
				var b = ((com.mobsminecraft.SmartMobHolder) e).mmc$getBrain();
				String near = e.distanceTo(this.bot.player) < 6 ? "near" : "far";
				if (near.equals("near")) this.endermen.merge("ALL_NEAR", 1, Integer::sum); else this.endermen.merge("ALL_FAR", 1, Integer::sum);
				this.endermen.merge(b.personalityName() + "/" + b.activity() + "/" + near, 1, Integer::sum);
			}
		}
		if (this.ticks == 300) {
			this.dragonsAtStart = this.end.getDragons().size();
			note("dragons after the fight starts: %d", this.dragonsAtStart);
			// A second dragon, as if something had duplicated it.
			cmd("execute in minecraft:the_end run summon ender_dragon 20 90 20");
			note("summoned a second dragon: now %d", this.end.getDragons().size());
			// One crystal down, so the dragon has one to rebuild.
			EndCrystal c = this.end.getEntitiesOfClass(EndCrystal.class, new AABB(-80, 0, -80, 80, 200, 80)).stream().findFirst().orElse(null);
			if (c != null) {
				c.discard();
				note("destroyed one crystal at %s", c.blockPosition());
			}
			com.mobsminecraft.ai.Boss.debugCrystalSoon();
			// Endermen around: some will rebel; three are forced to be End Gifters.
			for (int i = 0; i < 8; i++) {
				double a = i * Math.PI / 4;
				cmd(String.format(java.util.Locale.ROOT, "execute in minecraft:the_end run summon enderman %.1f %d %.1f", Math.cos(a) * 10, this.bot.player.blockPosition().getY(), 40 + Math.sin(a) * 10));
			}
		}
		if (this.ticks == 310) {
			int n = 0;
			for (Enderman e : this.end.getEntitiesOfClass(Enderman.class, this.bot.player.getBoundingBox().inflate(20))) {
				if (n++ < 3) ((com.mobsminecraft.SmartMobHolder) e).mmc$getBrain().debugPersonality("end_gifter");
			}
		}
		if (this.ticks == 500) {
			note("dragons 10 s after the duplicate: %d", this.end.getDragons().size());
		}
		if (this.ticks == 1200) {
			// Bring the dragon down onto the portal, with rebels waiting there.
			EnderDragon d = this.end.getDragons().isEmpty() ? null : this.end.getDragons().get(0);
			if (d != null) {
				d.getPhaseManager().setPhase(net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase.LANDING_APPROACH);
			}
			int top = this.end.getHeight(Heightmap.Types.MOTION_BLOCKING, 4, 4);
			for (int i = 0; i < 4; i++) {
				cmd(String.format(java.util.Locale.ROOT, "execute in minecraft:the_end run summon enderman %d %d %d {Tags:[\"mobsmineandcraft.end_rebel\",\"mobsmineandcraft.end_rebel_rolled\"]}", 4 + i, top, 5));
			}
			note("dragon sent to land on the portal; 4 rebels waiting");
		}
		if (this.ticks == 2700) {
			this.finish();
		}
	}

	private void finish() {
		note("dragons at the end: %d", this.end.getDragons().size());
		note("dragon phases after the swap (samples): %s; linked to the fight %d, not linked %d", this.phases, this.linked, this.unlinked);
		note("endermen within 32 (personality/activity/near<6): %s", this.endermen);
		note("bot took %d hits: %s", this.hits, this.damageBy);
		int rebels = 0, rebelsOnPlayer = 0;
		for (Enderman e : this.end.getEntitiesOfClass(Enderman.class, new AABB(-120, 0, -120, 120, 256, 120))) {
			if (e.entityTags().contains(com.mobsminecraft.ai.Boss.REBEL_TAG)) {
				rebels++;
				if (e.getTarget() == this.bot.player) rebelsOnPlayer++;
			}
		}
		note("rebel endermen alive: %d (targeting the player: %d)", rebels, rebelsOnPlayer);
		long crystals = this.end.getEntitiesOfClass(EndCrystal.class, new AABB(-80, 0, -80, 80, 200, 80)).size();
		note("crystals now: %d", crystals);
		EnderDragon d = this.end.getDragons().isEmpty() ? null : this.end.getDragons().get(0);
		note("dragon health: %s, personality tags: %s", d == null ? "-" : d.getHealth(), d == null ? "-" : d.entityTags());
		Map<String, Integer> stats = AiStats.snapshot();
		Map<String, Integer> mine = new TreeMap<>();
		stats.forEach((k, v) -> { if (k.startsWith("boss_") || k.startsWith("gift") || k.startsWith("personality:end")) mine.put(k, v); });
		note("stats: %s", mine);
		this.bot.remove(this.server);
		cmd("tick sprint stop");
		Playtest.LOG.info("[endtest] DONE");
		active = null;
	}
}
