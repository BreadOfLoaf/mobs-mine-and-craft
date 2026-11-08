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

	enum Behaviour { STAND, FIGHT, KITE, SPECTATE, PLAYER, CIRCLE }

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
		add(new Scenario("house_build", 4800, "12 builders each start a house in four biome strips (savanna, snowy taiga, desert, jungle).", (run, o) -> {
			String[] biomes = {"savanna", "snowy_taiga", "desert", "jungle"};
			for (int i = 0; i < 4; i++) {
				int x0 = o.getX() - 36 + i * 18;
				run.cmd("fillbiome %d 100 %d %d 170 %d minecraft:%s", x0, o.getZ() - 40, x0 + 17, o.getZ() + 40, biomes[i]);
			}
		},
			mobs("zombie", 4, "skeleton", 2, "villager", 4, "cow", 2), -18, Behaviour.SPECTATE));
		add(new Scenario("house_build_hostile", 4800, "8 zombie and skeleton builders each start a house (no villagers to fight).", (run, o) -> {},
			mobs("zombie", 5, "skeleton", 3), -18, Behaviour.SPECTATE));
		add(new Scenario("house_build_villagers", 4800, "8 villagers and cows each start a house (no monsters around).", (run, o) -> {},
			mobs("villager", 6, "cow", 2), -18, Behaviour.SPECTATE));
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
		add(new Scenario("villagers", 1200, "Villagers and an iron golem against zombies; spectator bot.", (run, o) -> {},
			mobs("villager", 5, "iron_golem", 1, "zombie", 4), 6, Behaviour.SPECTATE));
		add(new Scenario("herd", 1200, "A herd of cows and pigs next to a few monsters; spectator bot.", (run, o) -> {},
			mobs("cow", 5, "pig", 3, "zombie", 2, "skeleton", 1), 4, Behaviour.SPECTATE));
		add(new Scenario("brutes", 1200, "Piglin brutes among piglins (immune to zombifying); spectator bot.", (run, o) -> {},
			mobs("piglin", 5, "piglin_brute", 2), 5, Behaviour.SPECTATE));
		add(new Scenario("boats", 1200, "Bot floats in the middle of a lake; zombies on the shore.", (run, o) -> {
			run.cmd("fill %d 117 %d %d 120 %d minecraft:water", o.getX() - 7, o.getZ() - 7, o.getX() + 7, o.getZ() + 7);
			run.botStart = new Vec3(o.getX() + 0.5, 120.2, o.getZ() + 0.5);
		}, mobs("zombie", 5), 10, Behaviour.STAND));
		add(new Scenario("turret", 1200, "Engineer zombies, forced to build repeater-clock turrets.", (run, o) -> {},
			mobs("zombie", 3), 9, Behaviour.STAND));
		add(new Scenario("bunker", 900, "Hurt zombies seal themselves into bunkers to heal.", (run, o) -> {},
			mobs("zombie", 4), 7, Behaviour.FIGHT));
		add(new Scenario("sniper", 1500, "Sniper skeletons build towers; do they stay on top?", (run, o) -> {},
			mobs("skeleton", 4), 15, Behaviour.STAND));
		add(new Scenario("personas", 1800, "Bot fights one zombie of each personality plus two skeletons.", (run, o) -> {},
			mobs("zombie", 10, "skeleton", 2), 9, Behaviour.FIGHT));
		add(new Scenario("loot", 900, "Gear, apples and pearls on the ground among zombies (half greedy); spectator bot.", (run, o) -> {},
			mobs("zombie", 6), 6, Behaviour.SPECTATE));
		add(new Scenario("cleanup", 1500, "Builder zombies tidy up blocks of fight mess; spectator bot far away.", (run, o) -> {},
			mobs("zombie", 4), 6, Behaviour.SPECTATE));
		add(new Scenario("cover", 1200, "Skeletons fight from behind cover walls against a standing bot.", (run, o) -> {},
			mobs("skeleton", 4), 14, Behaviour.STAND));
		add(new Scenario("hero", 1500, "Hero zombies against a sword-swinging bot: do they flee and come back as Juggernauts?", (run, o) -> {},
			mobs("zombie", 3), 8, Behaviour.FIGHT));
		add(new Scenario("survival", 12000, "A bot plays survival in an ordinary generated world: natural spawns, day and night, mining, building, fighting back.",
			(run, o) -> {}, mobs(), 0, Behaviour.PLAYER));
		add(new Scenario("gifters", 1200, "Zombie, skeleton and creeper gifters around a standing bot: they should never hurt it.", (run, o) -> {},
			mobs("zombie", 3, "skeleton", 2, "creeper", 1), 8, Behaviour.STAND));
		add(new Scenario("roles", 1500, "Builders, crafters, engineers and minemen near a standing bot: they should do their jobs, not charge.", (run, o) -> {},
			mobs("zombie", 8), 10, Behaviour.STAND));
		add(new Scenario("griefers", 1200, "Griefer zombies in a field with a few player-built huts: TNT, then run.", (run, o) -> {
			for (int i = 0; i < 3; i++) {
				run.cmd("fill %d 121 %d %d 123 %d minecraft:oak_planks hollow", o.getX() - 12 + i * 10, o.getZ() + 6, o.getX() - 9 + i * 10, o.getZ() + 9);
			}
		}, mobs("zombie", 4), 8, Behaviour.STAND));
		add(new Scenario("imposters", 1800, "Two imposters among ordinary zombies and skeletons, next to a standing bot.", (run, o) -> {},
			mobs("zombie", 6, "skeleton", 2), 6, Behaviour.STAND));
		add(new Scenario("biome_blocks", 1200, "Bot on a pillar; the west half of the arena is desert, the east half taiga. What do mobs build with?", (run, o) -> {
			run.cmd("fillbiome %d 100 %d %d 170 %d minecraft:desert", o.getX() - 40, o.getZ() - 40, o.getX(), o.getZ() + 40);
			run.cmd("fillbiome %d 100 %d %d 170 %d minecraft:taiga", o.getX() + 1, o.getZ() - 40, o.getX() + 40, o.getZ() + 40);
			run.cmd("fill %d 121 %d %d 128 %d minecraft:obsidian", o.getX(), o.getZ(), o.getX(), o.getZ());
			run.botStart = Vec3.atBottomCenterOf(o.above(9));
		}, mobs("zombie", 8, "skeleton", 2), 12, Behaviour.STAND));
		// --- honesty and tactics -------------------------------------------------------------------------
		add(new Scenario("stealth_silent", 1200, "Bot sits quietly in a sealed stone room the mobs never saw it enter.", (run, o) -> {
			run.cmd("fill %d 121 %d %d 124 %d minecraft:stone hollow", o.getX() - 2, o.getZ() - 2, o.getX() + 2, o.getZ() + 2);
		}, mobs("zombie", 6, "skeleton", 2), 14, Behaviour.STAND));
		add(new Scenario("stealth_noisy", 1200, "Same sealed room, but the bot is mining away inside it (noisy).", (run, o) -> {
			run.cmd("fill %d 121 %d %d 124 %d minecraft:stone hollow", o.getX() - 2, o.getZ() - 2, o.getX() + 2, o.getZ() + 2);
		}, mobs("zombie", 6, "skeleton", 2), 14, Behaviour.STAND));
		add(new Scenario("seen_then_hide", 1200, "Bot stands in the open for 5 seconds, then ducks into a sealed room 3 blocks away.", (run, o) -> {
			run.cmd("fill %d 121 %d %d 124 %d minecraft:stone hollow", o.getX() + 1, o.getZ() - 2, o.getX() + 5, o.getZ() + 2);
			run.botStart = Vec3.atBottomCenterOf(o.above().west(2));
		}, mobs("zombie", 6, "skeleton", 2), 14, Behaviour.STAND));
		add(new Scenario("wave_classic", 1500, "The big mixed wave with the new tactics switched OFF (no flanking, skeletons fire through allies).", (run, o) -> {},
			mobs("zombie", 8, "skeleton", 4, "husk", 2), 16, Behaviour.FIGHT));
		add(new Scenario("wave_smart", 1500, "The same wave with flanking and hold-fire ON.", (run, o) -> {},
			mobs("zombie", 8, "skeleton", 4, "husk", 2), 16, Behaviour.FIGHT));
		add(new Scenario("anvil_warn", 900, "Zombies carrying anvils near a standing bot: every drop should be warned first.", (run, o) -> {},
			mobs("zombie", 3), 6, Behaviour.STAND));
		add(new Scenario("crowd", 1200, "Limit test: 120 mobs of all kinds around a fighting bot. How much does the AI cost per tick?", (run, o) -> {},
			mobs("zombie", 40, "skeleton", 20, "creeper", 10, "spider", 10, "villager", 15, "cow", 15, "pig", 10), 20, Behaviour.FIGHT));
		add(new Scenario("crowd_vanilla", 1200, "The same 120 mobs with the mod's AI switched off (baseline).", (run, o) -> {},
			mobs("zombie", 40, "skeleton", 20, "creeper", 10, "spider", 10, "villager", 15, "cow", 15, "pig", 10), 20, Behaviour.FIGHT));
		add(new Scenario("craft_fight", 1800, "A natural mix of zombies and skeletons (plus two crafters) fighting a standing bot. How much crafting?", (run, o) -> {},
			mobs("zombie", 10, "skeleton", 3), 14, Behaviour.STAND));
		add(new Scenario("craft_idle", 1800, "The same mob mix with no player around (spectator far away). Do they craft when idle?", (run, o) -> {},
			mobs("zombie", 10, "skeleton", 3), 10, Behaviour.SPECTATE));
		add(new Scenario("role_check", 2400, "Two zombies of every personality around a sword-swinging bot: does each one do its job?", (run, o) -> {},
			mobs("zombie", 20), 12, Behaviour.FIGHT));
		add(new Scenario("role_world", 9600, "Survival world: plus 2 zombies of every personality spawned near the bot. What does each one spend its time doing?",
			(run, o) -> {}, mobs(), 0, Behaviour.PLAYER));
		// One arena per personality against the player bot: ore underground and two player huts, so workers have work.
		for (String persona : new String[] {"fighter", "tactician", "builder", "engineer", "crafter", "mineman", "imposter", "hero", "gifter", "griefer"}) {
			add(new Scenario("vs_" + persona, 2400, "Four zombies and two skeletons, all " + persona + "s, around the player bot (ore underground, two player huts).",
				(run, o) -> {
					java.util.Random rnd = new java.util.Random(o.getX());
					for (int i = 0; i < 160; i++) {
						run.cmd("setblock %d %d %d minecraft:%s", o.getX() - 25 + rnd.nextInt(51), 113 + rnd.nextInt(6), o.getZ() - 25 + rnd.nextInt(51), rnd.nextBoolean() ? "iron_ore" : "coal_ore");
					}
					run.cmd("fill %d 121 %d %d 123 %d minecraft:oak_planks hollow", o.getX() + 8, o.getZ() + 8, o.getX() + 11, o.getZ() + 11);
					run.cmd("fill %d 121 %d %d 123 %d minecraft:cobblestone hollow", o.getX() - 11, o.getZ() + 8, o.getX() - 8, o.getZ() + 11);
				}, mobs("zombie", 4, "skeleton", 2), 14, Behaviour.FIGHT));
		}
		for (String mode : new String[] {"on", "off"}) {
			add(new Scenario("squad_" + mode, 2400, "A mixed band (tacticians, fighters, a hero, a crafter, a builder, an engineer, skeletons, a creeper) vs the player bot; teamwork "
				+ mode + ".", (run, o) -> {}, mobs("zombie", 8, "skeleton", 3, "creeper", 1), 24, Behaviour.FIGHT));
		}
		add(new Scenario("real_day", 12000, "An ordinary world from mid-morning into the night, natural spawns only, the player bot playing survival.",
			(run, o) -> {}, mobs(), 0, Behaviour.PLAYER));
		for (String tier : new String[] {"iron", "diamond"}) {
			add(new Scenario("fight_craft_" + tier, 2400, "Tacticians and builders vs the player bot in full " + tier
				+ ": do they craft gear to match, ladders and stairs, and build to their advantage?", (run, o) -> {},
				mobs("zombie", 6, "skeleton", 2), 16, Behaviour.FIGHT));
		}
		// Nether manners (piglins are immune to zombifying here, since this is the Overworld).
		add(new Scenario("piglins_gold", 1200, "Four piglins and two brutes next to a player in full golden armour: nobody should attack.",
			(run, o) -> {}, mobs("piglin", 4, "piglin_brute", 2), 5, Behaviour.STAND));
		add(new Scenario("piglins_plain", 1200, "The same piglins and brutes next to a player without gold (control: they attack).",
			(run, o) -> {}, mobs("piglin", 4, "piglin_brute", 2), 5, Behaviour.STAND));
		for (String g : new String[] {"gold", "plain", "plain_vanilla"}) {
			add(new Scenario("piglin_only_" + g, 1200, "Four piglins (no brutes) next to a player, " + g + ".", (run, o) -> {}, mobs("piglin", 4), 5, Behaviour.STAND));
			add(new Scenario("brute_only_" + g, 1200, "Two piglin brutes (no piglins) next to a player, " + g + ".", (run, o) -> {}, mobs("piglin_brute", 2), 5, Behaviour.STAND));
		}
		add(new Scenario("zombified_neutral", 1200, "Five zombified piglins around a player who leaves them alone: they stay neutral.",
			(run, o) -> {}, mobs("zombified_piglin", 5), 5, Behaviour.STAND));
		add(new Scenario("zombified_provoked", 1200, "Five zombified piglins around a player who fights: once hit, the group turns on them.",
			(run, o) -> {}, mobs("zombified_piglin", 5), 5, Behaviour.FIGHT));
		add(new Scenario("piglin_barter", 1200, "Golden armour and gold ingots thrown to four piglins: do they trade (armour included) instead of keeping it?",
			(run, o) -> {}, mobs("piglin", 4), 4, Behaviour.SPECTATE));
		add(new Scenario("bot_skills", 1800, "The player bot against a mixed group: does it time its swings, crit, block, shoot skeletons and creepers, eat, pillar?",
			(run, o) -> {}, mobs("zombie", 5, "skeleton", 3, "creeper", 2, "spider", 1), 18, Behaviour.FIGHT));
		add(new Scenario("wolves", 1200, "A sword-swinging bot provokes a wolf pack; a sword, chestplate and rotten flesh lie nearby.", (run, o) -> {},
			mobs("wolf", 4), 3, Behaviour.FIGHT));
		add(new Scenario("role_world_calm", 9600, "Survival world plus 2 zombies of each working personality (no imposters or griefers to kill them off).",
			(run, o) -> {}, mobs(), 0, Behaviour.PLAYER));
		add(new Scenario("roles_near", 2400, "A bot walks circles near 2 engineers, minemen, builders, crafters and griefers plus 4 tacticians; ore in the ground, player huts around.",
			(run, o) -> {
				java.util.Random rnd = new java.util.Random(o.getX());
				for (int i = 0; i < 200; i++) {
					run.cmd("setblock %d %d %d minecraft:%s", o.getX() - 25 + rnd.nextInt(51), 113 + rnd.nextInt(6), o.getZ() - 25 + rnd.nextInt(51), rnd.nextBoolean() ? "iron_ore" : "coal_ore");
				}
				for (int i = 0; i < 3; i++) {
					run.cmd("fill %d 121 %d %d 123 %d minecraft:oak_planks hollow", o.getX() - 14 + i * 12, o.getZ() + 14, o.getX() - 10 + i * 12, o.getZ() + 18);
				}
			}, mobs("zombie", 12, "skeleton", 2), 16, Behaviour.CIRCLE));
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
		EndTest.register();
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
			Commands.literal("endtest").executes(ctx -> {
				EndTest.active = new EndTest(ctx.getSource().getServer());
				EndTest.active.start();
				return 1;
			})));
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
			Commands.literal("train")
				.then(Commands.literal("start").then(Commands.argument("generations", IntegerArgumentType.integer(1, 200))
					.then(Commands.argument("population", IntegerArgumentType.integer(4, 64)).executes(ctx -> {
						Trainer.start(ctx.getSource().getServer(), IntegerArgumentType.getInteger(ctx, "generations"), IntegerArgumentType.getInteger(ctx, "population"));
						return 1;
					}))))
				.then(Commands.literal("validate").then(Commands.argument("episodes", IntegerArgumentType.integer(1, 200)).executes(ctx -> {
					Trainer.validate(ctx.getSource().getServer(), IntegerArgumentType.getInteger(ctx, "episodes"));
					return 1;
				})))
				.then(Commands.literal("stop").executes(ctx -> {
					Trainer.stop(ctx.getSource().getServer());
					return 1;
				}))));

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (Trainer.active != null) {
				Trainer.active.tick();
				return;
			}
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

		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (current != null && current.brain != null && current.bot != null && source.getEntity() == current.bot.player) {
				current.brain.kills++;
				AiStats.add("bot_kill");
			}
			if (current != null && entity instanceof Mob m && m.entityTags().contains(current.runTag)) {
				AiStats.add("death:" + ((com.mobsminecraft.SmartMobHolder) m).mmc$getBrain().personalityName() + ":" + source.type().msgId()
					+ (source.getEntity() != null ? ":" + BuiltInRegistries.ENTITY_TYPE.getKey(source.getEntity().getType()).getPath()
						: source.getDirectEntity() != null ? ":by_" + BuiltInRegistries.ENTITY_TYPE.getKey(source.getDirectEntity().getType()).getPath() : ""));
			}
		});
		// The bot never actually takes damage: every hit is recorded and cancelled.
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
			if (Trainer.active != null && Trainer.active.onDamage(entity, source, amount)) {
				return false;
			}
			if (current != null && current.brain != null && current.bot != null && entity != current.bot.player && source.getEntity() == current.bot.player
				&& source.getDirectEntity() instanceof net.minecraft.world.entity.projectile.arrow.AbstractArrow) {
				current.brain.arrowHits++;
			}
			if (current != null && source.getEntity() == null && source.getDirectEntity() != null
				&& (source.getDirectEntity() instanceof net.minecraft.world.entity.projectile.arrow.AbstractArrow
					|| source.getDirectEntity() instanceof net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball)) {
				AiStats.add("trap_hit:" + BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath());
			}
			if (current != null && entity instanceof Mob victim && victim.entityTags().contains(current.runTag)
				&& source.getDirectEntity() instanceof net.minecraft.world.entity.projectile.arrow.AbstractArrow
				&& source.getEntity() instanceof net.minecraft.world.entity.monster.skeleton.AbstractSkeleton) {
				AiStats.add("friendly_fire_arrow");
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
			this.origin = scenario.bot() == Behaviour.PLAYER
				? new BlockPos(-40000 + index * 3000 + (int) (System.nanoTime() % 1000), 64, 40000)
				: new BlockPos(3000 + index * 300, 120, 3000);
			this.botStart = Vec3.atBottomCenterOf(this.origin.above());
		}

		void cmd(String format, Object... args) {
			String command = String.format(Locale.ROOT, format, args);
			this.server.getCommands().performPrefixedCommand(this.server.createCommandSourceStack().withSuppressedOutput(), command);
		}

		void start() {
			if (this.scenario.bot() == Behaviour.PLAYER) {
				this.startSurvival();
				return;
			}
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
			com.mobsminecraft.world.MobBuilt.unmarkBox(this.level, new BlockPos(x - 40, 100, z - 40), new BlockPos(x + east, 190, z + 40));
			this.scenario.setup().accept(this, this.origin);

			if (this.scenario.bot() == Behaviour.SPECTATE) {
				this.botStart = Vec3.atBottomCenterOf(this.origin.offset(0, 1, -38));
			}
			this.bot = Bot.spawn(this.server, this.level, "Tester", this.botStart);
			if (this.scenario.bot() == Behaviour.SPECTATE) {
				this.bot.player.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
			}
			if (this.scenario.bot() == Behaviour.FIGHT) {
				PlayerBrain.giveKit(this.bot);
				if (this.scenario.name().endsWith("_diamond")) {
					var pl = this.bot.player;
					pl.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
					pl.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
					pl.setItemSlot(net.minecraft.world.entity.EquipmentSlot.LEGS, new ItemStack(Items.DIAMOND_LEGGINGS));
					pl.setItemSlot(net.minecraft.world.entity.EquipmentSlot.FEET, new ItemStack(Items.DIAMOND_BOOTS));
					pl.getInventory().setItem(0, new ItemStack(Items.DIAMOND_SWORD));
				}
				this.brain = new PlayerBrain(this.bot, this.level, PlayerBrain.Mode.ARENA, this.botStart);
			}

			AiStats.reset();
			com.mobsminecraft.ai.Flank.flanking = !this.scenario.name().equals("wave_classic");
			com.mobsminecraft.ai.Squad.enabled = !this.scenario.name().equals("squad_off");
			com.mobsminecraft.MobsMineAndCraft.DEBUG_TRADES = this.scenario.name().equals("piglin_barter");
			AiStats.aiEnabled = !this.scenario.name().equals("crowd_vanilla") && !this.scenario.name().endsWith("_vanilla");
			AiStats.timing = this.scenario.name().startsWith("crowd") || this.scenario.bot() == Behaviour.PLAYER;
			this.tickNanos = 0;
			com.mobsminecraft.ai.Flank.holdFire = !this.scenario.name().equals("wave_classic");
			cmd("tick sprint %d", this.scenario.ticks());
		}

		/** An ordinary generated world: no arena, natural mob spawning, time passing, starting at dusk. */
		/** Plays the bot like a person (FIGHT and PLAYER scenarios). */
		PlayerBrain brain;
		private final Map<java.util.UUID, String> census = new LinkedHashMap<>();
		private final Map<String, Map<String, Integer>> activityByPersona = new java.util.TreeMap<>();
		private int buildersBusyMax;

		private void startSurvival() {
			com.mobsminecraft.ai.Squad.enabled = true;
			cmd("gamerule max_block_modifications 5000000");
			cmd("gamerule spawn_mobs true");
			cmd("gamerule advance_time true");
			cmd("gamerule advance_weather false");
			cmd("gamerule mob_griefing true");
			cmd("time set %d", this.scenario.name().equals("real_day") ? 2000 : 12500);
			cmd("weather clear");
			cmd("difficulty normal");
			cmd("modsmac nametags true");
			// Somewhere on dry land: spiral out from the origin until the surface isn't water.
			int bx = this.origin.getX(), bz = this.origin.getZ(), y = 64;
			search:
			for (int ring = 0; ring < 12; ring++) {
				for (int k = 0; k < Math.max(1, ring * 8); k++) {
					double a = k * Math.PI * 2 / Math.max(1, ring * 8);
					int x = this.origin.getX() + (int) (Math.cos(a) * ring * 48);
					int z = this.origin.getZ() + (int) (Math.sin(a) * ring * 48);
					this.level.getChunk(x >> 4, z >> 4);
					int h = this.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
					BlockPos ground = new BlockPos(x, h - 1, z);
					if (this.level.getFluidState(ground).isEmpty() && this.level.getFluidState(ground.above()).isEmpty() && h > 62) {
						bx = x; bz = z; y = h;
						break search;
					}
				}
			}
			this.botStart = new Vec3(bx + 0.5, y, bz + 0.5);
			this.bot = Bot.spawn(this.server, this.level, "Tester", this.botStart);
			PlayerBrain.giveKit(this.bot);
			this.brain = new PlayerBrain(this.bot, this.level, PlayerBrain.Mode.SURVIVAL, this.botStart);
			if (this.scenario.name().startsWith("role_world")) {
				String[] personas = this.scenario.name().equals("role_world_calm")
					? new String[] {"fighter", "tactician", "builder", "engineer", "crafter", "mineman", "hero", "gifter"}
					: new String[] {"fighter", "tactician", "builder", "engineer", "crafter", "mineman", "imposter", "hero", "gifter", "griefer"};
				for (int i = 0; i < personas.length * 2; i++) {
					double a = i * Math.PI * 2 / (personas.length * 2);
					int mx = (int) (this.botStart.x + Math.cos(a) * 14), mz = (int) (this.botStart.z + Math.sin(a) * 14);
					int my = this.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, mx, mz);
					cmd("execute positioned %d %d %d run modsmac spawn minecraft:zombie %s", mx, my, mz, personas[i / 2]);
				}
			}
			AiStats.reset();
			cmd("tick sprint %d", this.scenario.ticks());
			LOG.info("[playtest] run {}: survival at {}", this.index, this.botStart);
		}

		/** Every mob seen near the bot, with its personality; and how many are building at once. */
		private void takeCensus() {
			int busy = 0;
			for (Mob m : this.level.getEntitiesOfClass(Mob.class, this.bot.player.getBoundingBox().inflate(64.0), Mob::isAlive)) {
				var brain = ((com.mobsminecraft.SmartMobHolder) m).mmc$getBrain();
				this.census.putIfAbsent(m.getUUID(), BuiltInRegistries.ENTITY_TYPE.getKey(m.getType()).getPath() + "/" + brain.personalityName());
				if (m instanceof net.minecraft.world.entity.monster.Enemy || m instanceof net.minecraft.world.entity.npc.villager.Villager || m instanceof net.minecraft.world.entity.animal.wolf.Wolf) {
					this.activityByPersona.computeIfAbsent(brain.personalityName(), k -> new java.util.TreeMap<>()).merge(brain.activity(), 1, Integer::sum);
				} else if (brain.isInitialized()) {
					this.activityByPersona.computeIfAbsent("passive_" + brain.personalityName(), k -> new java.util.TreeMap<>()).merge(brain.activity(), 1, Integer::sum);
				}
				if (brain.buildProgress() >= 0) busy++;
			}
			this.buildersBusyMax = Math.max(this.buildersBusyMax, busy);
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
					double ring = this.scenario.ring();
					if (this.scenario.name().startsWith("squad_")) {
						// Scattered, like mobs in the world: some close, some further off, so they'd arrive one by one.
						angle = n * 2.39996;
						ring = 10 + (n % 3) * 12;
					}
					double mx = x + 0.5 + Math.cos(angle) * ring;
					double mz = z + 0.5 + Math.sin(angle) * ring;
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
			if (mob instanceof net.minecraft.world.entity.monster.piglin.AbstractPiglin piglin) {
				piglin.setImmuneToZombification(true);
			}
			mob.addTag("playtest");
			mob.addTag(this.runTag);
			this.level.addFreshEntityWithPassengers(mob);
		}

		/** Advances one tick. Returns true when the run is over. */
		private long tickNanos;
		private long maxTickNanos;

		boolean tick() {
			this.ticks++;
			if (this.ticks > 20) {
				long last = this.server.getTickTimesNanos()[(this.server.getTickCount() + 99) % 100];
				this.tickNanos += last;
				this.maxTickNanos = Math.max(this.maxTickNanos, last);
			}
			if (this.ticks == 10) {
				this.spawnMobs();
			}
			switch (this.scenario.bot()) {
				case KITE -> {
					if (this.ticks < 600) {
						this.bot.walkToward(this.bot.player.position().add(10, 0, 0), 0.08);
					}
				}
				case CIRCLE -> {
					// Walks a circle around the arena centre like a player going about their business; swings at anything close.
					double a = this.ticks * 0.004;
					Vec3 c = Vec3.atBottomCenterOf(this.origin.above());
					this.bot.walkToward(c.add(Math.cos(a) * 9, 0, Math.sin(a) * 9), 0.15);
					if (this.ticks % 15 == 0) {
						for (Tracked t : this.tracked) {
							if (t.mob.isAlive() && t.mob.distanceTo(this.bot.player) < 3.0) {
								this.bot.attack(t.mob);
								break;
							}
						}
					}
					if (this.ticks % 100 == 0) {
						this.takeCensus();
					}
				}
				case PLAYER -> {
					this.brain.tick();
					if (this.ticks % 200 == 0) {
						this.takeCensus();
					}
				}
				case FIGHT -> {
					this.brain.tick();
					if (this.ticks % 100 == 0) {
						this.takeCensus();
					}
				}
				default -> {
				}
			}
			this.bot.tick();
			if (this.scenario.name().equals("stealth_noisy") && this.ticks % 40 == 0) {
				// Mining inside the room: loud.
				com.mobsminecraft.ai.Awareness.noise(this.bot.player, this.bot.player.position(), 16.0);
			}
			if ((this.scenario.name().equals("piglins_gold") || this.scenario.name().endsWith("_only_gold")) && this.ticks == 1) {
				var pl = this.bot.player;
				pl.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new ItemStack(Items.GOLDEN_HELMET));
				pl.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, new ItemStack(Items.GOLDEN_CHESTPLATE));
				pl.setItemSlot(net.minecraft.world.entity.EquipmentSlot.LEGS, new ItemStack(Items.GOLDEN_LEGGINGS));
				pl.setItemSlot(net.minecraft.world.entity.EquipmentSlot.FEET, new ItemStack(Items.GOLDEN_BOOTS));
			}
			if (this.scenario.name().equals("piglin_barter") && (this.ticks == 40 || this.ticks == 200 || this.ticks == 360 || this.ticks == 520)) {
				// Each round: a piece of golden armour for one piglin, gold ingots for the others.
				int round = this.ticks / 160;
				for (Tracked t : this.tracked) {
					if (!t.mob.isAlive()) continue;
					boolean armour = this.tracked.indexOf(t) == round % this.tracked.size();
					ItemStack gift = armour ? new ItemStack(new net.minecraft.world.item.Item[] {Items.GOLDEN_HELMET, Items.GOLDEN_CHESTPLATE, Items.GOLDEN_LEGGINGS, Items.GOLDEN_BOOTS}[round % 4])
						: new ItemStack(Items.GOLD_INGOT);
					net.minecraft.world.entity.item.ItemEntity e = new net.minecraft.world.entity.item.ItemEntity(this.level, t.mob.getX(), t.mob.getY() + 0.5, t.mob.getZ(), gift);
					this.level.addFreshEntity(e);
					AiStats.add(armour ? "given:golden_armour" : "given:gold_ingot");
				}
			}
			if (this.scenario.name().equals("piglin_barter") && (this.ticks == 30 || this.ticks == 1150)) {
				// Golden armour pieces worn (they spawn with some) and carried, before the gifts and at the end.
				int worn = 0;
				for (Tracked t : this.tracked) {
					if (!t.mob.isAlive()) continue;
					for (net.minecraft.world.entity.EquipmentSlot slot : new net.minecraft.world.entity.EquipmentSlot[] {net.minecraft.world.entity.EquipmentSlot.HEAD,
						net.minecraft.world.entity.EquipmentSlot.CHEST, net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET}) {
						if (com.mobsminecraft.ai.PiglinsAccess.isGoldenArmor(t.mob.getItemBySlot(slot))) worn++;
					}
					if (com.mobsminecraft.ai.PiglinsAccess.isGoldenArmor(t.mob.getOffhandItem())) AiStats.add("golden_armour_still_being_admired");
					if (t.mob instanceof net.minecraft.world.entity.monster.piglin.Piglin pg) {
						for (int i = 0; i < pg.getInventory().getContainerSize(); i++) if (com.mobsminecraft.ai.PiglinsAccess.isGoldenArmor(pg.getInventory().getItem(i))) worn++;
					}
				}
				AiStats.add(this.ticks == 30 ? "golden_armour_on_piglins_before" : "golden_armour_on_piglins_after", worn);
				int babies = 0;
				int babyWorn = 0;
				for (Tracked t : this.tracked) {
					if (!t.mob.isAlive() || !t.mob.isBaby()) continue;
					babies++;
					for (net.minecraft.world.entity.EquipmentSlot slot : new net.minecraft.world.entity.EquipmentSlot[] {net.minecraft.world.entity.EquipmentSlot.HEAD,
						net.minecraft.world.entity.EquipmentSlot.CHEST, net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET,
						net.minecraft.world.entity.EquipmentSlot.MAINHAND}) {
						if (com.mobsminecraft.ai.PiglinsAccess.isGoldenArmor(t.mob.getItemBySlot(slot))) babyWorn++;
					}
				}
				AiStats.add(this.ticks == 30 ? "babies" : "babies_end", babies);
				AiStats.add(this.ticks == 30 ? "golden_armour_on_babies_before" : "golden_armour_on_babies_after", babyWorn);
			}
			if (this.scenario.name().equals("piglin_barter") && this.ticks == 1150) {
				for (net.minecraft.world.entity.item.ItemEntity e : this.level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new AABB(this.origin).inflate(30))) {
					AiStats.add("on_ground:" + BuiltInRegistries.ITEM.getKey(e.getItem().getItem()).getPath(), e.getItem().getCount());
					if (e.getOwner() instanceof net.minecraft.world.entity.monster.piglin.AbstractPiglin) {
						AiStats.add("trade_stacks_on_ground");
						AiStats.add("trade_items_on_ground", e.getItem().getCount());
					}
				}
				for (net.minecraft.world.entity.item.ItemEntity e : this.level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new AABB(this.origin).inflate(100),
					e -> e.getOwner() instanceof net.minecraft.world.entity.monster.piglin.AbstractPiglin)) {
					AiStats.add("trade_items_within_100", e.getItem().getCount());
					if (!this.level.getBlockState(e.blockPosition()).isAir()) AiStats.add("trade_items_inside_blocks", e.getItem().getCount());
				}
			}
			if (this.scenario.name().equals("seen_then_hide") && this.ticks == 110) {
				this.bot.teleport(Vec3.atBottomCenterOf(this.origin.above().east(3)));
			}
			// Surround: in how many of the four quarters around the bot are there mobs within 4 blocks?
			if (this.ticks % 20 == 0 && this.bot.player.isAlive() && this.scenario.bot() != Behaviour.SPECTATE) {
				boolean[] q = new boolean[4];
				for (Tracked t : this.tracked) {
					if (t.mob.isAlive() && t.mob.distanceTo(this.bot.player) < 4.0) {
						double a = Math.atan2(t.mob.getZ() - this.bot.player.getZ(), t.mob.getX() - this.bot.player.getX());
						q[(int) ((a + Math.PI) / (Math.PI / 2)) & 3] = true;
					}
				}
				int n = (q[0] ? 1 : 0) + (q[1] ? 1 : 0) + (q[2] ? 1 : 0) + (q[3] ? 1 : 0);
				if (n > 0) AiStats.add("surround_sides_" + n);
			}
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
					case "turret" -> "clock";
					case "bunker" -> "bunker";
					case "sniper" -> "sniper";
					default -> null;
				};
				for (Tracked t : this.tracked) {
					var brain = ((com.mobsminecraft.SmartMobHolder) t.mob).mmc$getBrain();
					if (force != null) {
						brain.debugForce(force);
					}
					if (t.mob instanceof net.minecraft.world.entity.monster.piglin.AbstractPiglin piglin) {
						piglin.setImmuneToZombification(true);
					}
					if (this.scenario.name().equals("retreat") && t.type.equals("zombie")) {
						brain.debugForce("hurt");
					}
				}
				String[] personas = {"fighter", "tactician", "builder", "engineer", "crafter", "mineman", "imposter", "hero", "gifter", "griefer"};
				int pi = 0;
				for (Tracked t : this.tracked) {
					var brain = ((com.mobsminecraft.SmartMobHolder) t.mob).mmc$getBrain();
					switch (this.scenario.name()) {
						case "personas" -> brain.debugPersonality(t.type.equals("zombie") && pi < personas.length ? personas[pi++] : "tactician");
						case "loot" -> { brain.debugPersonality("fighter"); if (pi++ % 2 == 0) brain.debugForce("greedy"); }
						case "cleanup" -> { brain.debugPersonality("builder"); if (pi++ == 0) brain.debugForce("mess"); }
						case "hero" -> brain.debugPersonality("hero");
						case "house_build", "house_build_hostile", "house_build_villagers" -> brain.debugPersonality("builder");
						case "craft_fight", "craft_idle" -> { if (pi++ < 2) brain.debugPersonality("crafter"); }
						case "role_check" -> brain.debugPersonality(personas[pi++ / 2 % personas.length]);
						case String n when n.startsWith("vs_") -> brain.debugPersonality(n.substring(3));
						case "fight_craft_iron", "fight_craft_diamond" -> brain.debugPersonality(t.type.equals("zombie") && pi++ % 3 == 2 ? "builder" : "tactician");
						case "squad_on", "squad_off" -> brain.debugPersonality(t.type.equals("zombie")
							? new String[] {"tactician", "tactician", "fighter", "fighter", "hero", "crafter", "builder", "engineer"}[pi++ % 8] : "tactician");
						case "roles_near" -> brain.debugPersonality(t.type.equals("skeleton") ? "tactician"
							: new String[] {"engineer", "engineer", "mineman", "mineman", "builder", "builder", "crafter", "crafter", "griefer", "griefer", "tactician", "tactician"}[pi++]);
						case "anvil_warn" -> { brain.debugPersonality("tactician"); brain.debugForce("anvils"); }
						case "gifters" -> brain.debugPersonality("gifter");
						case "roles" -> brain.debugPersonality(new String[] {"builder", "crafter", "engineer", "mineman"}[pi++ % 4]);
						case "griefers" -> brain.debugPersonality("griefer");
						case "imposters" -> brain.debugPersonality(pi++ < 2 ? "imposter" : "tactician");
						case "cover" -> { brain.debugPersonality("tactician"); brain.debugForce("nosniper"); }
						default -> {}
					}
				}
				if (this.scenario.name().equals("wolves")) {
					for (String it : new String[] {"iron_sword", "iron_chestplate", "rotten_flesh", "rotten_flesh"}) {
						cmd("summon item %d 121 %d {Item:{id:\"minecraft:%s\",count:2},PickupDelay:10s}", this.origin.getX() + 2, this.origin.getZ() - 2, it);
					}
					for (Tracked t : this.tracked) {
						t.mob.hurt(this.level.damageSources().playerAttack(this.bot.player), 1.0F);
					}
				}
				if (this.scenario.name().equals("loot")) {
					String[] items = {"iron_sword", "iron_chestplate", "golden_apple", "ender_pearl", "diamond_helmet", "shield", "cooked_beef", "iron_boots"};
					for (int i = 0; i < items.length; i++) {
						cmd("summon item %d 121 %d {Item:{id:\"minecraft:%s\",count:1},PickupDelay:20s}", this.origin.getX() - 4 + i, this.origin.getZ() + (i % 2 == 0 ? 2 : -2), items[i]);
					}
				}
				if (this.scenario.name().equals("cleanup") && this.tracked.size() > 1) {
					((com.mobsminecraft.SmartMobHolder) this.tracked.get(1).mob).mmc$getBrain().debugForce("mess");
				}
				if (this.scenario.name().startsWith("prospect") && !this.tracked.isEmpty()) {
					((com.mobsminecraft.SmartMobHolder) this.tracked.get(0).mob).mmc$getBrain().debugForce("prospector");
				}
			}
			if (this.ticks == 30 && this.scenario.name().startsWith("house_build")) {
				// Half of them start a build; the other half are free to be their partners.
				int n = 0;
				for (Tracked t : this.tracked) {
					if (n++ % 2 == 0) {
						((com.mobsminecraft.SmartMobHolder) t.mob).mmc$getBrain().debugStartBuild(this.level);
					}
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
			if (this.brain != null) {
				this.brain.onHurt(source, amount);
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
			if (this.brain != null) {
				sb.append(this.brain.summary());
			}
			if (this.scenario.bot() == Behaviour.FIGHT) {
				sb.append("Bot kills: ").append(this.brain != null ? this.brain.kills : this.kills).append('\n');
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
			if (this.scenario.bot() == Behaviour.SPECTATE && this.scenario.name().startsWith("house_build")) {
				for (Tracked t : this.tracked) {
					double prog = ((com.mobsminecraft.SmartMobHolder) t.mob).mmc$getBrain().buildProgress();
					sb.append(String.format(Locale.ROOT, "  builder %-9s alive=%s building=%s  [%s]%n", t.type, t.mob.isAlive(),
						prog < 0 ? "no" : String.format(Locale.ROOT, "%.0f%%", prog * 100),
						((com.mobsminecraft.SmartMobHolder) t.mob).mmc$getBrain().debugState()));
				}
			}
			if (this.scenario.name().equals("turret")) {
				BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
				for (int dx = -30; dx <= 30; dx++) for (int dz = -30; dz <= 30; dz++) for (int dy = 0; dy <= 4; dy++) {
					q.set(this.origin.getX() + dx, 120 + dy, this.origin.getZ() + dz);
					if (this.level.getBlockEntity(q) instanceof net.minecraft.world.level.block.entity.DispenserBlockEntity d) {
						int n = 0;
						for (int i = 0; i < d.getContainerSize(); i++) n += d.getItem(i).getCount();
						var st = this.level.getBlockState(q);
						AiStats.add(String.format(Locale.ROOT, "dispenser_ammo_%d_facing_%s_bot_%.0f,%.0f", n, st.getValue(net.minecraft.world.level.block.DispenserBlock.FACING),
							this.bot.player.getX() - q.getX() - 0.5, this.bot.player.getZ() - q.getZ() - 0.5));
					}
				}
			}
			if (this.scenario.name().equals("sniper")) {
				for (Tracked t : this.tracked) {
					AiStats.add(String.format(Locale.ROOT, "end_%s_y%d", ((com.mobsminecraft.SmartMobHolder) t.mob).mmc$getBrain().debugPerched() ? "perched" : "ground",
						(int) t.mob.getY() - 121));
				}
			}
			if (this.scenario.name().equals("juggernaut")) {
				for (Mob m : this.level.getEntitiesOfClass(Mob.class, new AABB(this.origin).inflate(45), m -> m.isAlive())) {
					for (net.minecraft.world.entity.EquipmentSlot slot : net.minecraft.world.entity.EquipmentSlot.values()) {
						var ench = m.getItemBySlot(slot).getEnchantments();
						for (var e : ench.keySet()) {
							AiStats.add("ench_level_" + ench.getLevel(e));
						}
					}
				}
			}
			if (this.scenario.name().equals("juggernaut")) {
				int gold = 0;
				BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
				for (int dx = -40; dx <= 40; dx++) for (int dz = -40; dz <= 40; dz++) for (int dy = -1; dy <= 4; dy++) {
					q.set(this.origin.getX() + dx, 121 + dy, this.origin.getZ() + dz);
					if (this.level.getBlockState(q).is(net.minecraft.world.level.block.Blocks.GOLD_BLOCK)) gold++;
				}
				sb.append("  gold blocks in forts: ").append(gold).append('\n');
			}
			if (this.scenario.name().equals("personas") || this.scenario.name().equals("loot") || this.scenario.name().equals("griefers")
				|| this.scenario.name().equals("imposters") || this.scenario.name().equals("roles") || this.scenario.name().equals("gifters")) {
				for (Tracked t : this.tracked) {
					StringBuilder eq = new StringBuilder();
					for (net.minecraft.world.entity.EquipmentSlot slot : net.minecraft.world.entity.EquipmentSlot.values()) {
						ItemStack st = t.mob.getItemBySlot(slot);
						if (!st.isEmpty()) eq.append(BuiltInRegistries.ITEM.getKey(st.getItem()).getPath()).append(' ');
					}
					var brain = ((com.mobsminecraft.SmartMobHolder) t.mob).mmc$getBrain();
					sb.append(String.format(Locale.ROOT, "  %-9s %-10s alive=%s hp=%.0f/%.0f %s%n", t.type, brain.personalityName(), t.mob.isAlive(), t.mob.getHealth(), t.mob.getMaxHealth(), eq));
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
			if (this.scenario.bot() == Behaviour.CIRCLE || this.scenario.bot() == Behaviour.FIGHT) {
				sb.append("What each personality was doing (samples every 5 s):\n");
				for (Map.Entry<String, Map<String, Integer>> e : this.activityByPersona.entrySet()) {
					int total = e.getValue().values().stream().mapToInt(Integer::intValue).sum();
					StringBuilder line = new StringBuilder();
					e.getValue().entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
						.forEach(x -> line.append(String.format(Locale.ROOT, " %s %.0f%%", x.getKey(), 100.0 * x.getValue() / total)));
					sb.append(String.format(Locale.ROOT, "  %-10s (%3d samples):%s%n", e.getKey(), total, line));
				}
			}
			if (this.scenario.bot() == Behaviour.PLAYER) {
				sb.append(String.format(Locale.ROOT, "Bot ended at %s (game time %d)%n", this.bot.player.blockPosition(), this.level.getGameTime() % 24000));
				Map<String, Integer> byPersona = new java.util.TreeMap<>();
				Map<String, Integer> seenTypes = new java.util.TreeMap<>();
				for (String v : this.census.values()) {
					byPersona.merge(v.substring(v.indexOf('/') + 1), 1, Integer::sum);
					seenTypes.merge(v.substring(0, v.indexOf('/')), 1, Integer::sum);
				}
				sb.append("Mobs seen within 64 blocks: ").append(this.census.size()).append(" by type ").append(seenTypes).append('\n');
				sb.append("  by personality ").append(byPersona).append('\n');
				sb.append("  most mobs building at once: ").append(this.buildersBusyMax).append('\n');
				Map<String, Integer> pairs = new java.util.TreeMap<>();
				for (String v : this.census.values()) pairs.merge(v, 1, Integer::sum);
				sb.append("  type/personality ").append(pairs).append('\n');
				sb.append("What each personality was doing (samples every 5 s, hostile mobs, villagers and wolves):\n");
				for (Map.Entry<String, Map<String, Integer>> e : this.activityByPersona.entrySet()) {
					int total = e.getValue().values().stream().mapToInt(Integer::intValue).sum();
					StringBuilder line = new StringBuilder();
					e.getValue().entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
						.forEach(a -> line.append(String.format(Locale.ROOT, " %s %.0f%%", a.getKey(), 100.0 * a.getValue() / total)));
					sb.append(String.format(Locale.ROOT, "  %-10s (%3d samples):%s%n", e.getKey(), total, line));
				}
			}
			if (AiStats.timing || this.scenario.name().startsWith("crowd")) {
				int n = Math.max(1, this.ticks - 20);
				sb.append(String.format(Locale.ROOT, "Server tick: avg %.2f ms, worst %.1f ms. Smart brain: %.2f ms per tick in total (%.1f us per mob-tick).%n",
					this.tickNanos / 1e6 / n, this.maxTickNanos / 1e6, AiStats.brainNanos() / 1e6 / n,
					AiStats.brainTicks() == 0 ? 0.0 : AiStats.brainNanos() / 1e3 / AiStats.brainTicks()));
				StringBuilder brainByType = new StringBuilder("  brain time by mob type (ms per tick):");
				AiStats.brainTimeByType().entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(8)
					.forEach(e -> brainByType.append(String.format(Locale.ROOT, " %s=%.2f", e.getKey(), e.getValue() / 1e6 / n)));
				sb.append(brainByType).append('\n');
			}
			if (this.scenario.name().equals("role_check")) {
				Map<String, Integer> st = AiStats.snapshot();
				java.util.function.Function<String, Integer> sum = prefix -> st.entrySet().stream().filter(e -> e.getKey().startsWith(prefix)).mapToInt(Map.Entry::getValue).sum();
				sb.append(String.format(Locale.ROOT, "Roles: tactician crafts=%d falls_back=%d towers=%d | builder builds=%d cleanups=%d | engineer traps=%d orbits=%d | "
						+ "crafter gave=%d | mineman torches=%d stashes=%d | imposter betrayals=%d kills=%d | hero transformed=%d | gifter gifts=%d | griefer tnt=%d breaks=%d%n",
					sum.apply("craft:"), sum.apply("craft_fall_back"), sum.apply("tower"), sum.apply("build_started"), sum.apply("cleanup"),
					sum.apply("rs_build:"), sum.apply("engineer_orbit"), sum.apply("crafter_gave"), sum.apply("mineman_torch"), sum.apply("mineman_stash"),
					sum.apply("imposter_betrayal"), sum.apply("imposter_kill"), sum.apply("hero_transformed"), sum.apply("gift"), sum.apply("griefer_tnt"), sum.apply("griefer_break")));
			}
			sb.append("AI actions: ").append(AiStats.snapshot().isEmpty() ? "none" : AiStats.snapshot().toString()).append('\n');
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
			AiStats.aiEnabled = true;
			AiStats.timing = false;
			if (this.scenario.bot() == Behaviour.PLAYER) {
				cmd("modsmac nametags false");
				cmd("gamerule spawn_mobs false");
				cmd("gamerule advance_time false");
			}
			return new Result(this.scenario.name(), this.firstHit, this.damage, windows == 0 ? 0 : stuck / (double) windows);
		}
	}
}
