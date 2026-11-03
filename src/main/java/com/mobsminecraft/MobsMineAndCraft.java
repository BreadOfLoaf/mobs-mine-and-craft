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

		// Mobs drop everything they carry. An enchanted skeleton bow always drops, sometimes at half durability.
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (!(entity instanceof SmartMobHolder holder) || !(entity.level() instanceof ServerLevel level)) {
				return;
			}
			holder.mmc$getBrain().dropInventory(level);
			if (entity instanceof AbstractSkeleton skeleton && holder.mmc$getBrain().isBowEnchanted()) {
				ItemStack bow = skeleton.getMainHandItem();
				if (bow.is(Items.BOW)) {
					ItemStack drop = bow.copy();
					if (level.getRandom().nextFloat() < 0.4F) {
						drop.setDamageValue(drop.getMaxDamage() / 2);
					}
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
