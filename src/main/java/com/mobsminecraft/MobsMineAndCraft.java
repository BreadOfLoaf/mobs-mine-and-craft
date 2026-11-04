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

	@Override
	public void onInitialize() {
		ServerTickEvents.END_LEVEL_TICK.register(TimedCleanup::tick);
		// /modsmac nametags true|false: show every mob's personality above its head (for debugging).
		net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
			net.minecraft.commands.Commands.literal("modsmac")
				.requires(net.minecraft.commands.Commands.hasPermission(net.minecraft.commands.Commands.LEVEL_GAMEMASTERS))
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
