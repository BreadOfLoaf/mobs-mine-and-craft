package com.mobsminecraft.test;

import com.mobsminecraft.debug.AiStats;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dev-only playtest harness. {@code /playtest <scenario|all> [runs]} builds an arena far from spawn, puts a scripted
 * bot player in it, spawns mobs, fast-forwards, and writes a report of what the mob AI did.
 */
public class Playtest implements ModInitializer {
	static final Logger LOG = LoggerFactory.getLogger("playtest");

	/** One test setup: arena tweaks, mobs to spawn, and how the bot behaves. */
	record Scenario(String name, int ticks, String description, BiConsumer<Run, BlockPos> setup, Map<String, Integer> mobs, double ring, Behaviour bot) {}

	enum Behaviour { STAND, FIGHT, KITE, SPECTATE }

	static final Map<String, Scenario> SCENARIOS = new LinkedHashMap<>();

	static {
		add(new Scenario("open_field", 1800, "Bot stands in an open field at night.", (run, o) -> {},
			mobs("zombie", 5, "skeleton", 3, "creeper", 2, "spider", 2), 20, Behaviour.STAND));
		add(new Scenario("pillar", 1800, "Bot stands on top of an 8-block dirt pillar.", (run, o) -> {
			run.cmd("fill %d 121 %d %d 128 %d minecraft:dirt", o.getX(), o.getZ(), o.getX(), o.getZ());
			run.botStart = Vec3.atBottomCenterOf(o.above(9));
		}, mobs("zombie", 6, "skeleton", 2), 14, Behaviour.STAND));
		add(new Scenario("bunker", 1800, "Bot hides in a sealed cobblestone bunker (3x3 inside, roofed).", (run, o) -> {
			run.cmd("fill %d 121 %d %d 124 %d minecraft:cobblestone hollow", o.getX() - 2, o.getZ() - 2, o.getX() + 2, o.getZ() + 2);
		}, mobs("zombie", 6, "creeper", 2), 14, Behaviour.STAND));
		add(new Scenario("moat", 1800, "Bot stands on a 7x7 island ringed by a 5-wide, 4-deep moat.", (run, o) -> {
			run.cmd("fill %d 117 %d %d 120 %d minecraft:water", o.getX() - 8, o.getZ() - 8, o.getX() + 8, o.getZ() + 8);
			run.cmd("fill %d 117 %d %d 119 %d minecraft:dirt", o.getX() - 3, o.getZ() - 3, o.getX() + 3, o.getZ() + 3);
			run.cmd("fill %d 120 %d %d 120 %d minecraft:grass_block", o.getX() - 3, o.getZ() - 3, o.getX() + 3, o.getZ() + 3);
		}, mobs("zombie", 6, "skeleton", 2), 18, Behaviour.STAND));
		add(new Scenario("wave", 1800, "Bot fights back with an iron sword against a big mixed wave.", (run, o) -> {},
			mobs("zombie", 6, "skeleton", 4, "creeper", 2, "spider", 2, "witch", 1, "husk", 1), 18, Behaviour.FIGHT));
		add(new Scenario("in_house", 1800, "Bot hides in a 7x7 oak house with a door and glass windows.", (run, o) -> {
			int x = o.getX();
			int z = o.getZ();
			run.cmd("fill %d 121 %d %d 124 %d minecraft:oak_planks hollow", x - 3, z - 3, x + 3, z + 3);
			run.cmd("fill %d 122 %d %d 122 %d minecraft:glass_pane", x - 1, z - 3, x + 1, z - 3);
			run.cmd("fill %d 122 %d %d 122 %d minecraft:glass_pane", x - 3, z - 1, x - 3, z + 1);
			run.cmd("setblock %d 121 %d minecraft:oak_door[facing=south,half=lower]", x, z + 3);
			run.cmd("setblock %d 122 %d minecraft:oak_door[facing=south,half=upper]", x, z + 3);
		}, mobs("zombie", 6, "skeleton", 2, "creeper", 2), 16, Behaviour.STAND));
		add(new Scenario("cave", 1800, "Bot fights in a sealed 21x21 cave room deep underground.", (run, o) -> {
			int x = o.getX();
			int z = o.getZ();
			run.cmd("fill %d 121 %d %d 128 %d minecraft:stone", x - 14, z - 14, x + 14, z + 14);
			run.cmd("fill %d 121 %d %d 124 %d minecraft:air", x - 10, z - 10, x + 10, z + 10);
			run.cmd("fill %d 121 %d %d 121 %d minecraft:coal_ore", x - 9, z - 10, x - 7, z - 10);
			run.cmd("fill %d 121 %d %d 121 %d minecraft:iron_ore", x + 7, z + 10, x + 9, z + 10);
		}, mobs("zombie", 5, "skeleton", 3, "spider", 2, "creeper", 1), 8, Behaviour.STAND));
		add(new Scenario("house_build", 3600, "12 builders each start a house in four biome strips (savanna, snowy taiga, desert, jungle).", (run, o) -> {
			String[] biomes = {"savanna", "snowy_taiga", "desert", "jungle"};
			for (int i = 0; i < 4; i++) {
				int x0 = o.getX() - 36 + i * 18;
				run.cmd("fillbiome %d 100 %d %d 170 %d minecraft:%s", x0, o.getZ() - 40, x0 + 17, o.getZ() + 40, biomes[i]);
			}
		},
			mobs("zombie", 4, "skeleton", 2, "villager", 4, "cow", 2), -18, Behaviour.SPECTATE));
		add(new Scenario("passive_life", 3600, "Cows, sheep and villagers in a field with trees; spectator bot. What do they do?", (run, o) -> {
			for (int i = 0; i < 10; i++) {
				int tx = o.getX() - 30 + (i % 5) * 15;
				int tz = o.getZ() + (i < 5 ? -20 : 20);
				run.cmd("place feature minecraft:%s %d 121 %d", i % 3 == 0 ? "birch" : "oak", tx, tz);
			}
			run.cmd("fill %d 121 %d %d 123 %d minecraft:stone", o.getX() + 25, o.getZ() - 5, o.getX() + 28, o.getZ() + 5);
		}, mobs("cow", 6, "sheep", 4, "villager", 4, "pig", 2), 14, Behaviour.SPECTATE));
		add(new Scenario("trap_test", 1200, "Three engineer zombies build redstone traps against a standing bot. Do the traps hit?", (run, o) -> {},
			mobs("zombie", 3), 9, Behaviour.STAND));
		add(new Scenario("shields", 1200, "Bot fights 5 shield-carrying zombies with an iron sword.", (run, o) -> {},
			mobs("zombie", 5), 8, Behaviour.FIGHT));
		add(new Scenario("retreat", 1200, "Bot fights hurt zombies (30% health, carrying meat) alongside two skeletons.", (run, o) -> {},
			mobs("zombie", 5, "skeleton", 2), 8, Behaviour.FIGHT));
		add(new Scenario("tunnel", 1200, "Bot stands inside an obsidian ring; tunneller zombies dig under it.", (run, o) -> {
			run.cmd("fill %d 121 %d %d 123 %d minecraft:obsidian hollow", o.getX() - 2, o.getZ() - 2, o.getX() + 2, o.getZ() + 2);
			run.cmd("fill %d 123 %d %d 123 %d minecraft:obsidian", o.getX() - 2, o.getZ() - 2, o.getX() + 2, o.getZ() + 2);
		}, mobs("zombie", 4), 9, Behaviour.STAND));
		add(new Scenario("stairs", 1200, "Bot on top of a 7-high obsidian pillar; zombies build staircases up.", (run, o) -> {
			run.cmd("fill %d 121 %d %d 127 %d minecraft:obsidian", o.getX(), o.getZ(), o.getX(), o.getZ());
			run.botStart = Vec3.atBottomCenterOf(o.above(8));
		}, mobs("zombie", 4), 10, Behaviour.STAND));
		add(new Scenario("ladders", 1200, "Bot on top of a 7-high obsidian pillar; zombies with ladders climb it.", (run, o) -> {
			run.cmd("fill %d 121 %d %d 127 %d minecraft:obsidian", o.getX(), o.getZ(), o.getX(), o.getZ());
			run.botStart = Vec3.atBottomCenterOf(o.above(8));
		}, mobs("zombie", 4), 10, Behaviour.STAND));
		add(new Scenario("gear", 1500, "Bot in a sealed obsidian box; crafter zombies and skeletons power up with gear and enchantments.", (run, o) -> {
			run.cmd("fill %d 121 %d %d 124 %d minecraft:obsidian hollow", o.getX() - 2, o.getZ() - 2, o.getX() + 2, o.getZ() + 2);
		}, mobs("zombie", 4, "skeleton", 2), 12, Behaviour.STAND));
		add(new Scenario("juggernaut", 1800, "Two Juggernauts (zombie, skeleton) spot the bot: forts first, then fight.", (run, o) -> {},
			mobs("zombie", 1, "skeleton", 1), 12, Behaviour.STAND));
		add(new Scenario("farm", 2400, "Farmer villagers and cows near a pond (and away from it).", (run, o) -> {
			run.cmd("fill %d 120 %d %d 120 %d minecraft:water", o.getX() - 1, o.getZ() - 1, o.getX() + 1, o.getZ());
		}, mobs("villager", 4, "cow", 2), -10, Behaviour.SPECTATE));
		add(new Scenario("prospect", 3000, "Passive mobs go on a prospecting trip into ore-rich ground and smelt what they find.", (run, o) -> {
			java.util.Random rnd = new java.util.Random(o.getX());
			for (int i = 0; i < 260; i++) {
				run.cmd("setblock %d %d %d minecraft:%s", o.getX() - 20 + rnd.nextInt(41), 112 + rnd.nextInt(7), o.getZ() - 20 + rnd.nextInt(41),
					rnd.nextInt(3) == 0 ? "coal_ore" : rnd.nextBoolean() ? "iron_ore" : "copper_ore");
			}
		}, mobs("villager", 2, "cow", 2, "sheep", 1), 3, Behaviour.SPECTATE));
		add(new Scenario("prospect_solo", 1200, "One cow goes prospecting.", (run, o) -> {}, mobs("cow", 1), 3, Behaviour.SPECTATE));
		add(new Scenario("kite", 1200, "Bot walks away steadily for 30 seconds, then stops.", (run, o) -> {},
			mobs("zombie", 5, "spider", 2), 12, Behaviour.KITE));
	}

	private static void add(Scenario s) {
		SCENARIOS.put(s.name(), s);
	}

	private static Map<String, Integer> mobs(Object... kv) {
		Map<String, Integer> m = new LinkedHashMap<>();
		for (int i = 0; i < kv.length; i += 2) {
			m.put((String) kv[i], (Integer) kv[i + 1]);
		}
		return m;
	}

	private static final Deque<Scenario> QUEUE = new ArrayDeque<>();
	private static final List<Run.Result> RESULTS = new ArrayList<>();
	private static Run current;
	private static int runCounter;

	@Override
	public void onInitialize() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
			Commands.literal("playtest")
				.then(Commands.argument("scenario", StringArgumentType.word())
					.executes(ctx -> queue(StringArgumentType.getString(ctx, "scenario"), 1))
					.then(Commands.argument("runs", IntegerArgumentType.integer(1, 20))
						.executes(ctx -> queue(StringArgumentType.getString(ctx, "scenario"), IntegerArgumentType.getInteger(ctx, "runs")))))));

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (current != null) {
				if (current.tick()) {
					RESULTS.add(current.finish());
					current = null;
					if (QUEUE.isEmpty()) {
						writeSummary(server);
					}
				}
			} else if (!QUEUE.isEmpty()) {
				current = new Run(server, QUEUE.poll(), runCounter++);
				current.start();
			}
		});

		// The bot never actually takes damage: every hit is recorded and cancelled.
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
			if (current != null && source.getEntity() == null && source.getDirectEntity() != null
				&& (source.getDirectEntity() instanceof net.minecraft.world.entity.projectile.arrow.AbstractArrow
					|| source.getDirectEntity() instanceof net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball)) {
				AiStats.add("trap_hit:" + BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath());
			}
			if (current != null && current.bot != null && entity == current.bot.player) {
				current.recordDamage(source, amount);
				return false;
			}
			return true;
		});
	}

	private static int queue(String name, int runs) {
		List<Scenario> picked = new ArrayList<>();
		if (name.equals("all")) {
			picked.addAll(SCENARIOS.values());
		} else if (SCENARIOS.containsKey(name)) {
			picked.add(SCENARIOS.get(name));
		} else {
			LOG.info("[playtest] unknown scenario '{}'. Known: all, {}", name, String.join(", ", SCENARIOS.keySet()));
			return 0;
		}
		RESULTS.clear();
		for (int i = 0; i < runs; i++) {
			QUEUE.addAll(picked);
		}
		LOG.info("[playtest] queued {} run(s)", QUEUE.size());
		return 1;
	}

	private static void writeSummary(MinecraftServer server) {
		Map<String, List<Run.Result>> byScenario = new LinkedHashMap<>();
		for (Run.Result r : RESULTS) {
			byScenario.computeIfAbsent(r.scenario(), k -> new ArrayList<>()).add(r);
		}
		StringBuilder sb = new StringBuilder("=== PLAYTEST SUMMARY ===\n");
		for (Map.Entry<String, List<Run.Result>> e : byScenario.entrySet()) {
			List<Run.Result> rs = e.getValue();
			double hitRate = rs.stream().filter(r -> r.firstHitTick() >= 0).count() / (double) rs.size();
			double avgFirst = rs.stream().filter(r -> r.firstHitTick() >= 0).mapToInt(Run.Result::firstHitTick).average().orElse(-1);
			double avgDamage = rs.stream().mapToDouble(Run.Result::damage).average().orElse(0);
			double avgStuck = rs.stream().mapToDouble(Run.Result::stuckShare).average().orElse(0);
			sb.append(String.format(Locale.ROOT, "%-11s runs=%d  bot hit in %3.0f%% of runs  first hit %5.1fs  damage %6.1f  stuck %3.0f%%%n",
				e.getKey(), rs.size(), hitRate * 100, avgFirst / 20.0, avgDamage, avgStuck * 100));
		}
		LOG.info("\n{}", sb);
		write(server, "summary", sb.toString());
	}

	static void write(MinecraftServer server, String name, String text) {
		try {
			Path dir = server.getServerDirectory().resolve("playtest");
			Files.createDirectories(dir);
			Files.writeString(dir.resolve(name + ".txt"), text);
		} catch (IOException e) {
			LOG.warn("[playtest] couldn't write report", e);
		}
	}

	/** One run of one scenario. */
	static final class Run {
		record Result(String scenario, int firstHitTick, double damage, double stuckShare) {}

		private static final class Tracked {
			final Mob mob;
			final String type;
			double minDist = Double.MAX_VALUE;
			Vec3 lastPos;
			int windows;
			int stuckWindows;
			String lastStuckState;

			Tracked(Mob mob) {
				this.mob = mob;
				this.type = BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).getPath();
				this.lastPos = mob.position();
			}
		}

		final MinecraftServer server;
		final ServerLevel level;
		final Scenario scenario;
		final int index;
		final BlockPos origin;
		Vec3 botStart;
		Bot bot;
		final String runTag = "pt" + Long.toHexString(System.nanoTime());
		private final List<Tracked> tracked = new ArrayList<>();
		private final Map<String, Double> damageBy = new HashMap<>();
		private double damage;
		private int hits;
		private int firstHit = -1;
		private int ticks;
		private int kills;

		Run(MinecraftServer server, Scenario scenario, int index) {
			this.server = server;
			this.level = server.overworld();
			this.scenario = scenario;
			this.index = index;
			this.origin = new BlockPos(3000 + index * 300, 120, 3000);
			this.botStart = Vec3.atBottomCenterOf(this.origin.above());
		}

		void cmd(String format, Object... args) {
			String command = String.format(Locale.ROOT, format, args);
			this.server.getCommands().performPrefixedCommand(this.server.createCommandSourceStack().withSuppressedOutput(), command);
		}

		void start() {
			int x = this.origin.getX();
			int z = this.origin.getZ();
			int east = this.scenario.bot() == Behaviour.KITE ? 80 : 40;
			cmd("gamerule max_block_modifications 5000000");
			cmd("gamerule spawn_mobs false");
			cmd("gamerule advance_time false");
			cmd("gamerule advance_weather false");
			cmd("time set midnight");
			cmd("weather clear");
			cmd("difficulty hard");
			cmd("forceload add %d %d %d %d", x - 40, z - 40, x + east, z + 40);
			// Flat arena: stone base, dirt, grass, and open air above.
			cmd("fill %d 112 %d %d 118 %d minecraft:stone", x - 40, z - 40, x + east, z + 40);
			cmd("fill %d 119 %d %d 119 %d minecraft:dirt", x - 40, z - 40, x + east, z + 40);
			cmd("fill %d 120 %d %d 120 %d minecraft:grass_block", x - 40, z - 40, x + east, z + 40);
			cmd("fill %d 121 %d %d 170 %d minecraft:air", x - 40, z - 40, x + east, z + 40);
			cmd("kill @e[type=!player,x=%d,y=100,z=%d,dx=%d,dy=90,dz=80]", x - 40, z - 40, east + 40);
			this.scenario.setup().accept(this, this.origin);

			if (this.scenario.bot() == Behaviour.SPECTATE) {
				this.botStart = Vec3.atBottomCenterOf(this.origin.offset(0, 1, -38));
			}
			this.bot = Bot.spawn(this.server, this.level, "Tester", this.botStart);
			if (this.scenario.bot() == Behaviour.SPECTATE) {
				this.bot.player.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
			}
			if (this.scenario.bot() == Behaviour.FIGHT) {
				this.bot.hold(new ItemStack(Items.IRON_SWORD));
			}

			AiStats.reset();
			cmd("tick sprint %d", this.scenario.ticks());
		}

		/** Spawns the mobs once the arena's chunks (and their entity storage) have loaded. */
		private void spawnMobs() {
			int x = this.origin.getX();
			int z = this.origin.getZ();
			cmd("kill @e[type=!player,x=%d,y=100,z=%d,dx=%d,dy=90,dz=80]", x - 40, z - 40, (this.scenario.bot() == Behaviour.KITE ? 80 : 40) + 40);
			int n = 0;
			int total = this.scenario.mobs().values().stream().mapToInt(Integer::intValue).sum();
			for (Map.Entry<String, Integer> e : this.scenario.mobs().entrySet()) {
				for (int i = 0; i < e.getValue(); i++) {
					double angle = this.scenario.bot() == Behaviour.KITE
						? Math.PI + (n - total / 2.0) * 0.15
						: n * (Math.PI * 2 / total);
					double mx = x + 0.5 + Math.cos(angle) * this.scenario.ring();
					double mz = z + 0.5 + Math.sin(angle) * this.scenario.ring();
					if (this.scenario.ring() < 0) {
						double spacing = -this.scenario.ring();
						mx = x + 0.5 + (n % 4 - 1.5) * spacing;
						mz = z + 0.5 + (n / 4 - 1) * spacing;
					}
					spawn(e.getKey(), mx, mz);
					n++;
				}
			}
			for (Mob mob : this.level.getEntitiesOfClass(Mob.class, new AABB(this.origin).inflate(45), m -> m.entityTags().contains(this.runTag))) {
				this.tracked.add(new Tracked(mob));
			}
			AiStats.reset();
			LOG.info("[playtest] run {}: {} with {} mobs", this.index, this.scenario.name(), this.tracked.size());
		}

		/** Spawns a mob the way natural spawning does, so it gets its normal gear (skeletons need their bows). */
		private void spawn(String id, double x, double z) {
			net.minecraft.world.entity.EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(net.minecraft.resources.Identifier.withDefaultNamespace(id));
			if (!(type.create(this.level, net.minecraft.world.entity.EntitySpawnReason.COMMAND) instanceof Mob mob)) {
				return;
			}
			mob.snapTo(x, 121, z, this.level.getRandom().nextFloat() * 360.0F, 0.0F);
			mob.finalizeSpawn(this.level, this.level.getCurrentDifficultyAt(mob.blockPosition()), net.minecraft.world.entity.EntitySpawnReason.COMMAND, null);
			mob.setPersistenceRequired();
			mob.addTag("playtest");
			mob.addTag(this.runTag);
			this.level.addFreshEntityWithPassengers(mob);
		}

		/** Advances one tick. Returns true when the run is over. */
		boolean tick() {
			this.ticks++;
			if (this.ticks == 10) {
				this.spawnMobs();
			}
			switch (this.scenario.bot()) {
				case KITE -> {
					if (this.ticks < 600) {
						this.bot.walkToward(this.bot.player.position().add(10, 0, 0), 0.08);
					}
				}
				case FIGHT -> {
					if (this.ticks % 12 == 0) {
						Mob nearest = null;
						for (Tracked t : this.tracked) {
							if (t.mob.isAlive() && t.mob.distanceTo(this.bot.player) < 3.5
								&& (nearest == null || t.mob.distanceTo(this.bot.player) < nearest.distanceTo(this.bot.player))) {
								nearest = t.mob;
							}
						}
						if (nearest != null) {
							if (nearest.isBlocking()) {
								AiStats.add("bot_hit_blocked");
							}
							this.bot.attack(nearest);
							if (!nearest.isAlive()) {
								this.kills++;
							}
						}
					}
				}
				default -> {
				}
			}
			this.bot.tick();
			if (this.ticks == 15 && this.scenario.name().equals("trap_test")) {
				for (Tracked t : this.tracked) {
					((com.mobsminecraft.SmartMobHolder) t.mob).mmc$getBrain().debugMakeEngineer();
				}
			}
			if (this.ticks == 15) {
				String force = switch (this.scenario.name()) {
					case "shields" -> "shield";
					case "tunnel" -> "tunnel";
					case "stairs" -> "stairs";
					case "ladders" -> "ladders";
					case "gear" -> "crafter";
					case "juggernaut" -> "juggernaut";
					case "farm" -> "farmer";
					default -> null;
				};
				for (Tracked t : this.tracked) {
					var brain = ((com.mobsminecraft.SmartMobHolder) t.mob).mmc$getBrain();
					if (force != null) {
						brain.debugForce(force);
					}
					if (this.scenario.name().equals("retreat") && t.type.equals("zombie")) {
						brain.debugForce("hurt");
					}
				}
				if (this.scenario.name().startsWith("prospect") && !this.tracked.isEmpty()) {
					((com.mobsminecraft.SmartMobHolder) this.tracked.get(0).mob).mmc$getBrain().debugForce("prospector");
				}
			}
			if (this.ticks == 30 && this.scenario.name().equals("house_build")) {
				for (Tracked t : this.tracked) {
					((com.mobsminecraft.SmartMobHolder) t.mob).mmc$getBrain().debugStartBuild(this.level);
				}
			}
			if (this.scenario.name().equals("juggernaut") && this.ticks == this.scenario.ticks() - 30) {
				for (Tracked t : this.tracked) {
					if (t.mob.isAlive()) {
						t.mob.hurtServer(this.level, this.level.damageSources().genericKill(), 10000.0F);
					}
				}
			}
			if (this.scenario.name().equals("juggernaut") && this.ticks == this.scenario.ticks() - 5) {
				for (net.minecraft.world.entity.item.ItemEntity item : this.level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new AABB(this.origin).inflate(45))) {
					ItemStack st = item.getItem();
					if (st.isDamageableItem()) {
						AiStats.add(String.format(Locale.ROOT, "drop:%s%s:%d%%", BuiltInRegistries.ITEM.getKey(st.getItem()).getPath(), st.isEnchanted() ? "*" : "",
							100 - st.getDamageValue() * 100 / st.getMaxDamage()));
					}
				}
			}
			if (this.ticks % 10 == 0) {
				for (Tracked t : this.tracked) {
					if (t.mob.isAlive()) {
						t.minDist = Math.min(t.minDist, t.mob.distanceTo(this.bot.player));
					}
				}
			}
			if (this.ticks % 100 == 0) {
				for (Tracked t : this.tracked) {
					if (!t.mob.isAlive()) {
						continue;
					}
					Vec3 pos = t.mob.position();
					boolean chasing = t.mob.getTarget() == this.bot.player;
					boolean shooting = t.mob instanceof net.minecraft.world.entity.monster.RangedAttackMob
						&& t.mob.distanceTo(this.bot.player) < 16.0 && t.mob.hasLineOfSight(this.bot.player);
					if (chasing && !shooting && t.mob.distanceTo(this.bot.player) > 3.0) {
						t.windows++;
						if (pos.distanceTo(t.lastPos) < 1.0) {
							t.stuckWindows++;
							t.lastStuckState = String.format(Locale.ROOT, "dist=%.1f los=%s %s", t.mob.distanceTo(this.bot.player),
								t.mob.hasLineOfSight(this.bot.player), ((com.mobsminecraft.SmartMobHolder) t.mob).mmc$getBrain().debugState());
						}
					}
					t.lastPos = pos;
				}
			}
			return this.ticks >= this.scenario.ticks();
		}

		private int lastHitTick = -100;
		private float lastHitAmount;

		void recordDamage(DamageSource source, float amount) {
			// Vanilla: for 10 ticks after a hit, only damage above the last hit counts (and only the difference).
			if (this.ticks - this.lastHitTick < 10) {
				if (amount <= this.lastHitAmount) {
					return;
				}
				float extra = amount - this.lastHitAmount;
				this.lastHitAmount = amount;
				amount = extra;
			} else {
				this.lastHitTick = this.ticks;
				this.lastHitAmount = amount;
			}
			String who = source.getEntity() != null ? BuiltInRegistries.ENTITY_TYPE.getKey(source.getEntity().getType()).getPath() : "none";
			String key = who + "/" + source.type().msgId();
			this.damageBy.merge(key, (double) amount, Double::sum);
			this.damage += amount;
			this.hits++;
			if (this.firstHit < 0) {
				this.firstHit = this.ticks;
			}
		}

		Result finish() {
			Map<String, Integer> stats = AiStats.snapshot();
			StringBuilder sb = new StringBuilder();
			sb.append(String.format(Locale.ROOT, "=== %s (run %d) ===%n%s%n", this.scenario.name(), this.index, this.scenario.description()));
			sb.append(String.format(Locale.ROOT, "Bot: %d hits, %.1f damage, first hit at %s%n", this.hits, this.damage,
				this.firstHit < 0 ? "never" : String.format(Locale.ROOT, "%.1fs", this.firstHit / 20.0)));
			this.damageBy.entrySet().stream().sorted(Map.Entry.<String, Double>comparingByValue().reversed())
				.forEach(e -> sb.append(String.format(Locale.ROOT, "  %-28s %6.1f%n", e.getKey(), e.getValue())));
			if (this.scenario.bot() == Behaviour.FIGHT) {
				sb.append("Bot kills: ").append(this.kills).append('\n');
			}

			Map<String, List<Tracked>> byType = new LinkedHashMap<>();
			for (Tracked t : this.tracked) {
				byType.computeIfAbsent(t.type, k -> new ArrayList<>()).add(t);
			}
			int windows = 0;
			int stuck = 0;
			sb.append("Mobs (type: alive/total, reached within 2.5 blocks, closest, stuck-while-chasing windows):\n");
			for (Map.Entry<String, List<Tracked>> e : byType.entrySet()) {
				List<Tracked> ts = e.getValue();
				long alive = ts.stream().filter(t -> t.mob.isAlive()).count();
				long reached = ts.stream().filter(t -> t.minDist < 2.5).count();
				double closest = ts.stream().mapToDouble(t -> t.minDist).min().orElse(-1);
				int w = ts.stream().mapToInt(t -> t.windows).sum();
				int s = ts.stream().mapToInt(t -> t.stuckWindows).sum();
				windows += w;
				stuck += s;
				sb.append(String.format(Locale.ROOT, "  %-10s %d/%d alive, %d reached, closest %.1f, stuck %d/%d%n",
					e.getKey(), alive, ts.size(), reached, closest, s, w));
			}
			this.tracked.stream().filter(t -> t.lastStuckState != null).limit(6)
				.forEach(t -> sb.append("  stuck ").append(t.type).append(": ").append(t.lastStuckState).append('\n'));
			if (this.scenario.bot() == Behaviour.SPECTATE) {
				for (Tracked t : this.tracked) {
					double prog = ((com.mobsminecraft.SmartMobHolder) t.mob).mmc$getBrain().buildProgress();
					sb.append(String.format(Locale.ROOT, "  builder %-9s alive=%s building=%s  [%s]%n", t.type, t.mob.isAlive(),
						prog < 0 ? "no" : String.format(Locale.ROOT, "%.0f%%", prog * 100),
						((com.mobsminecraft.SmartMobHolder) t.mob).mmc$getBrain().debugState()));
				}
			}
			if (this.scenario.name().equals("gear") || this.scenario.name().equals("juggernaut") || this.scenario.name().equals("shields")) {
				for (Tracked t : this.tracked) {
					StringBuilder eq = new StringBuilder();
					for (net.minecraft.world.entity.EquipmentSlot slot : net.minecraft.world.entity.EquipmentSlot.values()) {
						ItemStack st = t.mob.getItemBySlot(slot);
						if (!st.isEmpty()) {
							eq.append(BuiltInRegistries.ITEM.getKey(st.getItem()).getPath()).append(st.isEnchanted() ? "*" : "").append(' ');
						}
					}
					sb.append(String.format(Locale.ROOT, "  gear %-9s alive=%s %s%n", t.type, t.mob.isAlive(), eq));
				}
			}
			sb.append("AI actions: ").append(stats.isEmpty() ? "none" : stats.toString()).append('\n');
			String report = sb.toString();
			LOG.info("\n{}", report);
			write(this.server, String.format(Locale.ROOT, "%02d-%s", this.index, this.scenario.name()), report);

			this.bot.remove(this.server);
			for (Tracked t : this.tracked) {
				t.mob.discard();
			}
			int x = this.origin.getX();
			int z = this.origin.getZ();
			cmd("kill @e[type=!player,x=%d,y=100,z=%d,dx=%d,dy=90,dz=80]", x - 40, z - 40, 120);
			cmd("forceload remove %d %d %d %d", x - 40, z - 40, x + 80, z + 40);
			cmd("tick unfreeze");
			return new Result(this.scenario.name(), this.firstHit, this.damage, windows == 0 ? 0 : stuck / (double) windows);
		}
	}
}
