package com.mobsminecraft.test;

import com.mobsminecraft.ai.NeuralTactics;
import com.mobsminecraft.ai.Personality;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Neuroevolution of the mobs' fighting tactics (see {@link NeuralTactics}). Runs 8 arenas side by side, each with a
 * test bot and a squad of Tactician mobs all using one candidate network. Every generation each candidate fights two
 * episodes (bot fighting back, kiting, or up a pillar); the best are kept and mutated. The score rewards fights that
 * are threatening but fair: damage to the bot (capped, so swarming doesn't pay beyond a point), staying alive, using
 * more than one tactic, and no friendly fire.
 *
 * Commands: {@code /train start <generations> <population>}, {@code /train validate <episodes>}, {@code /train stop}.
 */
final class Trainer {
	static Trainer active;

	enum Kind { FIGHT, KITE, PILLAR }

	private static final int ARENAS = 8;
	private static final int EPISODE_TICKS = 900;
	private static final int SQUAD_ZOMBIES = 2;
	private static final int SQUAD_SKELETONS = 1;

	/** One fight being run in one arena. */
	private static final class Arena {
		final int slot;
		final BlockPos origin;
		Bot bot;
		Kind kind;
		float[] genome;
		int genomeIndex;
		final List<Mob> mobs = new ArrayList<>();
		final int[] tactics = new int[NeuralTactics.OUTPUTS];
		long aliveTicks;
		double damage;
		int friendlyFire;
		int lastHitTick = -100;
		float lastHitAmount;
		int ticks;
		/** The bot's simulated health: it dies (and the fight ends) at 0, and regenerates like a fed player. */
		float hp;
		float minHp;
		int killedAt = -1;
		int aliveAtEnd = -1;

		Arena(int slot) {
			this.slot = slot;
			this.origin = new BlockPos(200000 + slot * 300, 120, 200000);
		}
	}

	/** An evaluation still to run: which genome on which kind of episode. */
	private record Job(int genome, Kind kind) {}

	private final MinecraftServer server;
	private final ServerLevel level;
	private final Random rnd = new Random();
	private final Arena[] arenas = new Arena[ARENAS];
	private final boolean validating;
	private final int generations;
	private final int populationSize;
	private int generation;
	private List<float[]> population = new ArrayList<>();
	private double[] fitness;
	private int[] evals;
	private final List<Job> queue = new ArrayList<>();
	private final List<Arena> running = new ArrayList<>();
	private int batchTicks = -1;
	private final StringBuilder log = new StringBuilder();
	// validation: [0] = baseline (hand-written AI only), [1] = champion
	private final double[][] validation = new double[2][6];
	private final int[] validationRuns = new int[2];
	// validation breakdown by bot style: [genome][kind][score, damage, survival, runs], and which tactics were picked
	private final double[][][] byKind = new double[2][Kind.values().length][5];
	private final long[][] tacticTotals = new long[2][NeuralTactics.OUTPUTS];

	private Trainer(MinecraftServer server, int generations, int population, boolean validating, int validationEpisodes) {
		this.server = server;
		this.level = server.overworld();
		this.generations = generations;
		this.populationSize = population;
		this.validating = validating;
		for (int i = 0; i < ARENAS; i++) {
			this.arenas[i] = new Arena(i);
		}
		if (validating) {
			float[] champion = loadChampion();
			if (champion == null) {
				Playtest.LOG.info("[train] no champion to validate");
				return;
			}
			this.population.add(baseline());
			this.population.add(champion);
			for (int i = 0; i < validationEpisodes; i++) {
				Kind k = Kind.values()[i % Kind.values().length];
				this.queue.add(new Job(0, k));
				this.queue.add(new Job(1, k));
			}
			this.fitness = new double[2];
			this.evals = new int[2];
		} else {
			float[] champion = loadChampion();
			this.population.add(baseline());
			if (champion != null) {
				this.population.add(champion);
			}
			while (this.population.size() < population) {
				this.population.add(NeuralTactics.seed(this.rnd));
			}
			this.startGeneration();
		}
	}

	static void start(MinecraftServer server, int generations, int population) {
		active = new Trainer(server, generations, population, false, 0);
		active.prepare();
	}

	static void validate(MinecraftServer server, int episodes) {
		active = new Trainer(server, 0, 0, true, episodes);
		active.prepare();
	}

	static void stop(MinecraftServer server) {
		if (active != null) {
			active.finishAll();
		}
		active = null;
	}

	private void cmd(String format, Object... args) {
		this.server.getCommands().performPrefixedCommand(this.server.createCommandSourceStack().withSuppressedOutput(), String.format(Locale.ROOT, format, args));
	}

	private void prepare() {
		cmd("gamerule max_block_modifications 5000000");
		cmd("gamerule spawn_mobs false");
		cmd("gamerule advance_time false");
		cmd("gamerule advance_weather false");
		cmd("gamerule mob_griefing true");
		cmd("time set midnight");
		cmd("weather clear");
		cmd("difficulty hard");
		for (Arena a : this.arenas) {
			cmd("forceload add %d %d %d %d", a.origin.getX() - 24, a.origin.getZ() - 24, a.origin.getX() + 24, a.origin.getZ() + 24);
		}
		cmd("tick sprint 100000000");
	}

	/** A network that always says "usual": the hand-written AI. */
	static float[] baseline() {
		float[] w = new float[NeuralTactics.SIZE];
		w[NeuralTactics.SIZE - NeuralTactics.OUTPUTS + NeuralTactics.Tactic.USUAL.ordinal()] = 10.0F;
		return w;
	}

	private void startGeneration() {
		this.fitness = new double[this.population.size()];
		this.evals = new int[this.population.size()];
		this.queue.clear();
		for (int i = 0; i < this.population.size(); i++) {
			for (Kind kind : Kind.values()) {
				this.queue.add(new Job(i, kind));
			}
		}
		java.util.Collections.shuffle(this.queue, this.rnd);
	}

	/** Called every server tick. */
	void tick() {
		if (this.running.isEmpty()) {
			if (this.queue.isEmpty()) {
				if (this.validating) {
					this.reportValidation();
					stop(this.server);
					return;
				}
				this.endGeneration();
				if (this.generation >= this.generations) {
					stop(this.server);
					return;
				}
				this.startGeneration();
			}
			for (int i = 0; i < ARENAS && !this.queue.isEmpty(); i++) {
				Job job = this.queue.remove(0);
				Arena a = this.arenas[i];
				a.genomeIndex = job.genome();
				a.genome = this.population.get(job.genome());
				a.kind = job.kind();
				this.resetArena(a);
				this.running.add(a);
			}
			this.batchTicks = 0;
			return;
		}
		this.batchTicks++;
		for (Arena a : this.running) {
			if (this.batchTicks == 10) {
				this.spawnSquad(a);
			} else if (this.batchTicks > 10) {
				this.tickArena(a);
			}
		}
		if (this.batchTicks >= 10 + EPISODE_TICKS) {
			for (Arena a : this.running) {
				this.score(a);
			}
			this.running.clear();
		}
	}

	private void resetArena(Arena a) {
		int x = a.origin.getX();
		int z = a.origin.getZ();
		cmd("kill @e[type=!player,x=%d,y=100,z=%d,dx=48,dy=80,dz=48]", x - 24, z - 24);
		com.mobsminecraft.world.MobBuilt.unmarkBox(this.level, new BlockPos(x - 24, 100, z - 24), new BlockPos(x + 24, 180, z + 24));
		cmd("fill %d 112 %d %d 118 %d minecraft:stone", x - 22, z - 22, x + 22, z + 22);
		cmd("fill %d 119 %d %d 119 %d minecraft:dirt", x - 22, z - 22, x + 22, z + 22);
		cmd("fill %d 120 %d %d 120 %d minecraft:grass_block", x - 22, z - 22, x + 22, z + 22);
		cmd("fill %d 121 %d %d 150 %d minecraft:air", x - 22, z - 22, x + 22, z + 22);
		Vec3 start = Vec3.atBottomCenterOf(a.origin.above());
		if (a.kind == Kind.PILLAR) {
			cmd("fill %d 121 %d %d 126 %d minecraft:dirt", x, z, x, z);
			start = Vec3.atBottomCenterOf(a.origin.above(7));
		}
		if (a.bot == null) {
			a.bot = Bot.spawn(this.server, this.level, "Trainee" + a.slot, start);
		}
		a.bot.teleport(start);
		a.bot.hold(a.kind == Kind.PILLAR ? ItemStack.EMPTY : new ItemStack(Items.IRON_SWORD));
		a.bot.player.setHealth(a.bot.player.getMaxHealth());
		// Dressed like a typical mid-game player: full iron.
		a.bot.player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
		a.bot.player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
		a.bot.player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
		a.bot.player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
		a.hp = 20;
		a.minHp = 20;
		a.killedAt = -1;
		a.aliveAtEnd = -1;
		a.mobs.clear();
		Arrays.fill(a.tactics, 0);
		a.aliveTicks = 0;
		a.damage = 0;
		a.friendlyFire = 0;
		a.ticks = 0;
		a.lastHitTick = -100;
	}

	private void spawnSquad(Arena a) {
		int n = SQUAD_ZOMBIES + SQUAD_SKELETONS;
		for (int i = 0; i < n; i++) {
			String id = i < SQUAD_ZOMBIES ? "zombie" : "skeleton";
			var type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.withDefaultNamespace(id));
			if (!(type.create(this.level, EntitySpawnReason.COMMAND) instanceof Mob mob)) continue;
			double ang = i * Math.PI * 2 / n;
			mob.snapTo(a.origin.getX() + 0.5 + Math.cos(ang) * 14, 121, a.origin.getZ() + 0.5 + Math.sin(ang) * 14, this.rnd.nextFloat() * 360, 0);
			mob.finalizeSpawn(this.level, this.level.getCurrentDifficultyAt(mob.blockPosition()), EntitySpawnReason.COMMAND, null);
			mob.setPersistenceRequired();
			mob.addTag("trainer");
			// Never a Juggernaut in training (they'd skew the score); always a Tactician.
			mob.addTag("mobsmineandcraft.juggernaut_rolled");
			Personality.set(mob, Personality.TACTICIAN);
			this.level.addFreshEntity(mob);
			((com.mobsminecraft.SmartMobHolder) mob).mmc$getBrain().setTacticsWeights(a.genome);
			a.mobs.add(mob);
		}
	}

	private void tickArena(Arena a) {
		if (a.killedAt >= 0) {
			return; // the player is dead: this fight is over
		}
		a.ticks++;
		if (a.ticks % 40 == 0 && a.hp < 20) {
			a.hp = Math.min(20, a.hp + 1);
		}
		Bot bot = a.bot;
		Vec3 c = Vec3.atBottomCenterOf(a.origin.above());
		switch (a.kind) {
			case FIGHT -> {
				// An aggressive player: goes for the nearest mob and hits at sword cooldown.
				Mob near = null;
				for (Mob m : a.mobs) {
					if (m.isAlive() && (near == null || m.distanceTo(bot.player) < near.distanceTo(bot.player))) near = m;
				}
				if (near != null && near.distanceTo(bot.player) > 2.5F) {
					bot.walkToward(near.position(), 0.2);
				}
				if (a.ticks % 13 == 0) this.swing(a);
			}
			case KITE -> {
				double ang = a.ticks * 0.01;
				bot.walkToward(c.add(Math.cos(ang) * 7, 0, Math.sin(ang) * 7), 0.16);
				if (a.ticks % 15 == 0) this.swing(a);
			}
			case PILLAR -> {}
		}
		bot.tick();
		for (Mob m : a.mobs) {
			if (m.isAlive()) a.aliveTicks++;
		}
		if (a.ticks % 20 == 0) {
			for (Mob m : a.mobs) {
				if (m.isAlive() && m.getTarget() == bot.player) {
					a.tactics[((com.mobsminecraft.SmartMobHolder) m).mmc$getBrain().tacticOrdinal()]++;
				}
			}
		}
	}

	private void swing(Arena a) {
		Mob nearest = null;
		for (Mob m : a.mobs) {
			if (m.isAlive() && m.distanceTo(a.bot.player) < 3.5F && (nearest == null || m.distanceTo(a.bot.player) < nearest.distanceTo(a.bot.player))) {
				nearest = m;
			}
		}
		if (nearest != null) {
			a.bot.attack(nearest);
		}
	}

	/** Damage hook: returns true (and records it) if the entity is one of the training bots. */
	boolean onDamage(LivingEntity entity, DamageSource source, float amount) {
		for (Arena a : this.running) {
			if (a.bot != null && entity == a.bot.player) {
				if (a.killedAt >= 0) {
					return true;
				}
				if (!source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_ARMOR)) {
					// Vanilla armour formula for full iron (15 armour, no toughness).
					float armor = 15.0F;
					float reduced = Math.max(armor * 0.2F, Math.min(20.0F, armor - amount / 2.0F));
					amount = amount * (1.0F - reduced / 25.0F);
				}
				double before = a.damage;
				if (a.ticks - a.lastHitTick < 10) {
					if (amount > a.lastHitAmount) {
						a.damage += amount - a.lastHitAmount;
						a.lastHitAmount = amount;
					}
				} else {
					a.lastHitTick = a.ticks;
					a.lastHitAmount = amount;
					a.damage += amount;
				}
				a.hp -= (float) (a.damage - before);
				a.minHp = Math.min(a.minHp, a.hp);
				if (a.hp <= 0) {
					a.killedAt = a.ticks;
					a.aliveAtEnd = (int) a.mobs.stream().filter(Mob::isAlive).count();
				}
				return true;
			}
			if (entity instanceof Mob m && a.mobs.contains(m) && source.getDirectEntity() instanceof AbstractArrow && source.getEntity() instanceof AbstractSkeleton) {
				a.friendlyFire++;
			}
		}
		return false;
	}

	/** The score for one fight. */
	private double fitnessOf(Arena a, double[] parts) {
		int n = a.mobs.size();
		long alive = a.aliveAtEnd >= 0 ? a.aliveAtEnd : a.mobs.stream().filter(Mob::isAlive).count();
		boolean killed = a.killedAt >= 0;
		// Killing the player is the goal (sooner is better); otherwise, how close they came.
		double lethal = killed ? 1.0 + 0.5 * (1.0 - a.killedAt / (double) EPISODE_TICKS) : 0.8 * (20.0 - Math.max(0, a.minHp)) / 20.0;
		double survive = n == 0 ? 0 : alive / (double) n;
		int total = Arrays.stream(a.tactics).sum();
		double entropy = 0;
		for (int t : a.tactics) {
			if (t > 0) {
				double p = t / (double) total;
				entropy -= p * Math.log(p);
			}
		}
		double variety = total == 0 ? 0 : entropy / Math.log(NeuralTactics.OUTPUTS);
		double ff = Math.min(0.3, a.friendlyFire * 0.05);
		parts[0] += a.damage;
		parts[1] += survive;
		parts[2] += variety;
		parts[3] += a.friendlyFire;
		parts[4] += killed ? 1 : 0;
		parts[5] += killed ? a.killedAt : 0;
		return lethal + 0.5 * survive + 0.1 * variety - ff;
	}

	private void score(Arena a) {
		double[] parts = this.validating ? this.validation[a.genomeIndex] : new double[6];
		double f = this.fitnessOf(a, parts);
		this.fitness[a.genomeIndex] += f;
		this.evals[a.genomeIndex]++;
		if (this.validating) {
			this.validationRuns[a.genomeIndex]++;
			double[] k = this.byKind[a.genomeIndex][a.kind.ordinal()];
			long alive = a.aliveAtEnd >= 0 ? a.aliveAtEnd : a.mobs.stream().filter(Mob::isAlive).count();
			k[4] += a.killedAt >= 0 ? 1 : 0;
			k[0] += f;
			k[1] += a.damage;
			k[2] += a.mobs.isEmpty() ? 0 : alive / (double) a.mobs.size();
			k[3]++;
			for (int t = 0; t < NeuralTactics.OUTPUTS; t++) this.tacticTotals[a.genomeIndex][t] += a.tactics[t];
		}
		for (Mob m : a.mobs) {
			m.discard();
		}
		cmd("kill @e[type=!player,x=%d,y=100,z=%d,dx=48,dy=80,dz=48]", a.origin.getX() - 24, a.origin.getZ() - 24);
	}

	private void endGeneration() {
		int n = this.population.size();
		double[] mean = new double[n];
		Integer[] order = new Integer[n];
		for (int i = 0; i < n; i++) {
			mean[i] = this.evals[i] == 0 ? -1 : this.fitness[i] / this.evals[i];
			order[i] = i;
		}
		Arrays.sort(order, Comparator.comparingDouble(i -> -mean[i]));
		double avg = Arrays.stream(mean).average().orElse(0);
		float[] best = this.population.get(order[0]);
		String line = String.format(Locale.ROOT, "gen %3d  best %.3f  mean %.3f  baseline-in-pop %.3f", this.generation, mean[order[0]], avg, mean[0]);
		Playtest.LOG.info("[train] {}", line);
		this.log.append(line).append('\n');
		this.saveChampion(best, mean[order[0]]);
		this.generation++;
		// Next generation: keep the top quarter (re-scored next time, which weeds out lucky ones), fill the rest
		// with mutated copies and crosses of the top half. The pure hand-written baseline always stays in as a yardstick.
		int elites = Math.max(2, n / 4);
		List<float[]> next = new ArrayList<>();
		next.add(baseline());
		for (int i = 0; i < elites && next.size() < n; i++) {
			if (order[i] != 0) next.add(this.population.get(order[i]));
		}
		double sigma = Math.max(0.05, 0.25 * Math.pow(0.93, this.generation));
		while (next.size() < n) {
			float[] p1 = this.population.get(order[this.rnd.nextInt(Math.max(1, n / 2))]);
			float[] child = p1.clone();
			if (this.rnd.nextFloat() < 0.3F) {
				float[] p2 = this.population.get(order[this.rnd.nextInt(Math.max(1, n / 2))]);
				for (int k = 0; k < child.length; k++) if (this.rnd.nextBoolean()) child[k] = p2[k];
			}
			for (int k = 0; k < child.length; k++) {
				if (this.rnd.nextFloat() < 0.25F) child[k] += (float) (this.rnd.nextGaussian() * sigma);
			}
			next.add(child);
		}
		this.population = next;
	}

	private void reportValidation() {
		StringBuilder sb = new StringBuilder("=== validation: champion network vs hand-written AI ===\n");
		String[] names = {"hand-written", "champion"};
		for (int g = 0; g < 2; g++) {
			int runs = Math.max(1, this.validationRuns[g]);
			sb.append(String.format(Locale.ROOT, "%-13s fights=%d  score %.3f  damage/fight %.1f  survival %.0f%%  tactic variety %.2f  friendly fire/fight %.1f%n",
				names[g], this.validationRuns[g], this.fitness[g] / runs, this.validation[g][0] / runs, 100 * this.validation[g][1] / runs,
				this.validation[g][2] / runs, this.validation[g][3] / runs));
			sb.append(String.format(Locale.ROOT, "%-13s player killed in %.0f%% of fights, avg %.1f s to kill%n", "", 100 * this.validation[g][4] / runs,
				this.validation[g][4] == 0 ? 0 : this.validation[g][5] / this.validation[g][4] / 20.0));
		}
		for (int g = 0; g < 2; g++) {
			for (Kind kind : Kind.values()) {
				double[] k = this.byKind[g][kind.ordinal()];
				double r = Math.max(1, k[3]);
				sb.append(String.format(Locale.ROOT, "  %-13s vs %-6s  score %.3f  player killed %.0f%%  damage %.1f  mobs alive %.0f%%%n", names[g], kind, k[0] / r, 100 * k[4] / r, k[1] / r, 100 * k[2] / r));
			}
		}
		long total = Math.max(1, Arrays.stream(this.tacticTotals[1]).sum());
		sb.append("champion tactic mix:");
		for (NeuralTactics.Tactic t : NeuralTactics.Tactic.values()) {
			sb.append(String.format(Locale.ROOT, " %s %.0f%%", t.name().toLowerCase(), 100.0 * this.tacticTotals[1][t.ordinal()] / total));
		}
		sb.append('\n');
		Playtest.LOG.info("\n{}", sb);
		write("validation.txt", sb.toString());
	}

	private void finishAll() {
		for (Arena a : this.running) {
			for (Mob m : a.mobs) m.discard();
		}
		for (Arena a : this.arenas) {
			if (a.bot != null) a.bot.remove(this.server);
			cmd("forceload remove %d %d %d %d", a.origin.getX() - 24, a.origin.getZ() - 24, a.origin.getX() + 24, a.origin.getZ() + 24);
		}
		cmd("tick sprint stop");
		if (!this.validating) {
			write("log.txt", this.log.toString());
		}
		Playtest.LOG.info("[train] done");
	}

	private void saveChampion(float[] w, double score) {
		StringBuilder sb = new StringBuilder();
		sb.append(String.format(Locale.ROOT, "# neural tactics: %d inputs, %d hidden, %d outputs; generation %d, score %.3f%n",
			NeuralTactics.INPUTS, NeuralTactics.HIDDEN, NeuralTactics.OUTPUTS, this.generation, score));
		for (int i = 0; i < w.length; i++) {
			sb.append(String.format(Locale.ROOT, "%.5f", w[i])).append(i % 10 == 9 ? "\n" : " ");
		}
		write("champion.weights", sb.toString());
		write("log.txt", this.log.toString());
	}

	private float[] loadChampion() {
		Path f = this.server.getServerDirectory().resolve("training").resolve("champion.weights");
		try {
			if (!Files.isRegularFile(f)) return null;
			List<Float> v = new ArrayList<>();
			for (String line : Files.readAllLines(f)) {
				if (line.startsWith("#")) continue;
				for (String p : line.trim().split("\\s+")) if (!p.isEmpty()) v.add(Float.parseFloat(p));
			}
			if (v.size() != NeuralTactics.SIZE) return null;
			float[] w = new float[v.size()];
			for (int i = 0; i < w.length; i++) w[i] = v.get(i);
			return w;
		} catch (IOException e) {
			return null;
		}
	}

	private void write(String name, String text) {
		try {
			Path dir = this.server.getServerDirectory().resolve("training");
			Files.createDirectories(dir);
			Files.writeString(dir.resolve(name), text);
		} catch (IOException e) {
			Playtest.LOG.warn("[train] couldn't write {}", name, e);
		}
	}
}
