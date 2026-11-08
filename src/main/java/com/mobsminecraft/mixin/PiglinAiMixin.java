package com.mobsminecraft.mixin;

import com.mobsminecraft.ai.Piglins;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.piglin.PiglinAi;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Golden armour is bartered like gold ingots; some barters give End gear. */
@Mixin(PiglinAi.class)
public abstract class PiglinAiMixin {
	@Inject(method = "isBarterCurrency", at = @At("RETURN"), cancellable = true)
	private static void mmc$goldenArmor(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValueZ() && Piglins.isGoldenArmor(stack)) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "getBarterResponseItems", at = @At("RETURN"), cancellable = true)
	private static void mmc$endPrep(Piglin piglin, CallbackInfoReturnable<List<ItemStack>> cir) {
		if (piglin.level() instanceof ServerLevel level) {
			cir.setReturnValue(Piglins.endPrep(level, piglin.getRandom(), cir.getReturnValue()));
		}
	}
}
