package com.mobsminecraft;

import com.mobsminecraft.world.TimedCleanup;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MobsMineAndCraft implements ModInitializer {
	public static final String MOD_ID = "mobsmineandcraft";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	private static final String ARROW_TAG = MOD_ID + ".enchanted_arrow";

	/** Test switch: log who removes piglin trades (set by the playtest harness). */
	public static boolean DEBUG_TRADES = false;
	private static int TRADE_TRACES;

	@Override
	public void onInitialize() {
		// The ender dragon's Boss personality, rebel Endermen, and only ever one dragon.
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_LEVEL_TICK.register(level -> {
			if (level.dimension() == net.minecraft.world.level.Level.END) {
				com.mobsminecraft.ai.Boss.tickEnd(level);
			}
		});
		// Piglin trades are for the player: nothing (fire, lava, explosions, cactus) destroys them while they lie there.
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity instanceof net.minecraft.world.entity.item.ItemEntity item && com.mobsminecraft.ai.Piglins.forPlayers(item)) {
				item.setPermanentlyInvulnerable(true);
			}
		});
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
			if (DEBUG_TRADES && entity instanceof net.minecraft.world.entity.item.ItemEntity item && com.mobsminecraft.ai.Piglins.forPlayers(item)
				&& entity.getRemovalReason() != null && entity.getRemovalReason().shouldDestroy()) {
				com.mobsminecraft.debug.AiStats.add("trade_removed:" + entity.getRemovalReason(), item.getItem().getCount());
				if (TRADE_TRACES++ < 12) {
					LOGGER.info("[tradedebug] {} x{} removed ({})", item.getItem().getItem(), item.getItem().getCount(), entity.getRemovalReason(), new Throwable("removed here"));
				}
			}
		});
		ServerTickEvents.END_LEVEL_TICK.register(TimedCleanup::tick);
		// More wolf packs in forests, taigas and savannas.
		net.fabricmc.fabric.api.biome.v1.BiomeModifications.addSpawn(
			net.fabricmc.fabric.api.biome.v1.BiomeSelectors.tag(net.minecraft.tags.BiomeTags.IS_FOREST)
				.or(net.fabricmc.fabric.api.biome.v1.BiomeSelectors.tag(net.minecraft.tags.BiomeTags.IS_TAIGA))
				.or(net.fabricmc.fabric.api.biome.v1.BiomeSelectors.tag(net.minecraft.tags.BiomeTags.IS_SAVANNA)),
			net.minecraft.world.entity.MobCategory.CREATURE, net.minecraft.world.entity.EntityTypes.WOLF, 10, 2, 4);
		// What mobs can hear: footsteps nearby, blocks being broken, fighting.
		ServerTickEvents.END_LEVEL_TICK.register(level -> {
			if (level.getGameTime() % 5 == 0) {
				com.mobsminecraft.ai.Awareness.footsteps(level);
			}
		});
		net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
			com.mobsminecraft.ai.Awareness.noise(player, net.minecraft.world.phys.Vec3.atCenterOf(pos), 16.0);
			// A block the player broke is no longer a mob's to protect.
			if (world instanceof ServerLevel sl) {
				com.mobsminecraft.world.MobBuilt.unmark(sl, pos);
			}
		});
		net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
			com.mobsminecraft.ai.Awareness.noise(player, player.position(), 16.0);
			return net.minecraft.world.InteractionResult.PASS;
		});
		// /modsmac nametags true|false: show every mob's personality above its head (for debugging).
		net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
			net.minecraft.commands.Commands.literal("modsmac")
				.requires(net.minecraft.commands.Commands.hasPermission(net.minecraft.commands.Commands.LEVEL_GAMEMASTERS))
				// /modsmac spawn <mob> <personality>: spawns a mob (with its usual gear) that has that personality.
				.then(net.minecraft.commands.Commands.literal("spawn")
					.then(net.minecraft.commands.Commands.argument("entity", net.minecraft.commands.arguments.ResourceArgument.resource(registryAccess, net.minecraft.core.registries.Registries.ENTITY_TYPE))
						.suggests(net.minecraft.commands.synchronization.SuggestionProviders.cast(net.minecraft.commands.synchronization.SuggestionProviders.SUMMONABLE_ENTITIES))
						.then(net.minecraft.commands.Commands.argument("personality", com.mojang.brigadier.arguments.StringArgumentType.word())
							.suggests((ctx, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
								java.util.Arrays.stream(com.mobsminecraft.ai.Personality.values()).map(p -> p.name().toLowerCase()), builder))
							.executes(ctx -> spawnWithPersonality(ctx.getSource(),
								net.minecraft.commands.arguments.ResourceArgument.getSummonableEntityType(ctx, "entity").value(),
								com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "personality"))))))
				.then(net.minecraft.commands.Commands.literal("nametags")
					.executes(ctx -> {
						ctx.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal(
							"Personality name tags are " + (com.mobsminecraft.world.NameTags.enabled() ? "on" : "off")), false);
						return 1;
					})
					.then(net.minecraft.commands.Commands.argument("enabled", com.mojang.brigadier.arguments.BoolArgumentType.bool())
						.executes(ctx -> {
							boolean on = com.mojang.brigadier.arguments.BoolArgumentType.getBool(ctx, "enabled");
							com.mobsminecraft.world.NameTags.set(on);
							ctx.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal(
								"Personality name tags " + (on ? "on" : "off")), true);
							return 1;
						})))));
		// Mob-built blocks are saved with the world: every few minutes and when the server stops.
		ServerTickEvents.END_LEVEL_TICK.register(level -> {
			if (level.getGameTime() % 6000 == 0) {
				com.mobsminecraft.world.MobBuilt.save(level);
			}
		});
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			for (ServerLevel level : server.getAllLevels()) {
				com.mobsminecraft.world.MobBuilt.save(level);
			}
		});

		// Skeletons shoot "enchanted" arrows most of the time.
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity instanceof AbstractArrow arrow
				&& arrow.getOwner() instanceof AbstractSkeleton skeleton
				&& !arrow.entityTags().contains(ARROW_TAG)) {
				arrow.addTag(ARROW_TAG);
				float chance = ((SmartMobHolder) skeleton).mmc$getBrain().isFletched() ? 0.9F : 0.7F;
				enchantArrow(arrow, level.getRandom(), chance);
			}
		});

		// A mob that dies with its pickaxe out gets its usual item back first, so normal drops still happen.
		ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
			if (entity instanceof SmartMobHolder holder) {
				holder.mmc$getBrain().restoreHand();
			}
			return true;
		});

		// Mobs drop what they picked up. Skeletons with an enchanted bow sometimes drop it at half durability.
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (!(entity instanceof SmartMobHolder holder) || !(entity.level() instanceof ServerLevel level)) {
				return;
			}
			holder.mmc$getBrain().dropInventory(level);
			if (entity instanceof AbstractSkeleton skeleton && holder.mmc$getBrain().isBowEnchanted()) {
				ItemStack bow = skeleton.getMainHandItem();
				float roll = level.getRandom().nextFloat();
				if (bow.is(Items.BOW) && roll < 0.4F) {
					ItemStack drop = bow.copy();
					// Usually worn to half durability; now and then good as new.
					drop.setDamageValue(roll < 0.07F ? 0 : drop.getMaxDamage() / 2);
					skeleton.spawnAtLocation(level, drop);
				}
			}
		});

		LOGGER.info("Mobs can now mine, craft and build. Good luck.");
	}

	private static int spawnWithPersonality(net.minecraft.commands.CommandSourceStack source, net.minecraft.world.entity.EntityType<?> type, String name) {
		com.mobsminecraft.ai.Personality personality;
		try {
			personality = com.mobsminecraft.ai.Personality.valueOf(name.toUpperCase(java.util.Locale.ROOT));
		} catch (IllegalArgumentException e) {
			source.sendFailure(net.minecraft.network.chat.Component.literal("Unknown personality '" + name + "'. Try: "
				+ java.util.Arrays.stream(com.mobsminecraft.ai.Personality.values()).map(p -> p.name().toLowerCase()).toList()));
			return 0;
		}
		ServerLevel level = source.getLevel();
		if (!(type.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND) instanceof net.minecraft.world.entity.Mob mob)) {
			source.sendFailure(net.minecraft.network.chat.Component.literal("That isn't a mob."));
			return 0;
		}
		net.minecraft.world.phys.Vec3 pos = source.getPosition();
		mob.snapTo(pos.x, pos.y, pos.z, source.getRotation().y, 0.0F);
		mob.finalizeSpawn(level, level.getCurrentDifficultyAt(mob.blockPosition()), net.minecraft.world.entity.EntitySpawnReason.COMMAND, null);
		// The personality is read from the mob's tags when its brain starts up on its first tick.
		com.mobsminecraft.ai.Personality.set(mob, personality);
		level.addFreshEntityWithPassengers(mob);
		source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("Spawned ").append(mob.getType().getDescription())
			.append(" (" + name.toLowerCase(java.util.Locale.ROOT) + ")"), true);
		return 1;
	}

	private static void enchantArrow(AbstractArrow arrow, RandomSource r, float chance) {
		if (r.nextFloat() >= chance) {
			return;
		}
		arrow.setCritArrow(true);
		// Any kind of tipped arrow, even helpful ones (a lucky player gets healed or sped up).
		if (arrow instanceof Arrow tipped && r.nextFloat() < 0.45F && arrow.level() instanceof ServerLevel sl) {
			var potions = sl.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.POTION).listElements().toList();
			var potion = potions.get(r.nextInt(potions.size()));
			for (MobEffectInstance e : new net.minecraft.world.item.alchemy.PotionContents(potion).getAllEffects()) {
				tipped.addEffect(new MobEffectInstance(e.getEffect(), Math.max(1, e.getDuration() / 8), e.getAmplifier()));
			}
			return;
		}
		switch (r.nextInt(5)) {
			case 0 -> arrow.igniteForSeconds(100.0F);
			case 1 -> addEffect(arrow, new MobEffectInstance(MobEffects.SLOWNESS, 100, 1));
			case 2 -> addEffect(arrow, new MobEffectInstance(MobEffects.POISON, 100, 0));
			case 3 -> addEffect(arrow, new MobEffectInstance(MobEffects.WEAKNESS, 200, 0));
			default -> addEffect(arrow, new MobEffectInstance(MobEffects.INSTANT_DAMAGE, 1, 0));
		}
	}

	private static void addEffect(AbstractArrow arrow, MobEffectInstance effect) {
		if (arrow instanceof Arrow tipped) {
			tipped.addEffect(effect);
		} else {
			arrow.igniteForSeconds(100.0F);
		}
	}
}
