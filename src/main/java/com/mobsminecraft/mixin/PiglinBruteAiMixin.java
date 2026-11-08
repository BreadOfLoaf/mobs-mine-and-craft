package com.mobsminecraft.mixin;

import com.mobsminecraft.ai.Piglins;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.monster.piglin.PiglinBruteAi;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Brutes leave a player in golden armour alone too (unless that player has angered them). */
@Mixin(PiglinBruteAi.class)
public abstract class PiglinBruteAiMixin {
	@Inject(method = "findNearestValidAttackTarget", at = @At("RETURN"), cancellable = true)
	private static void mmc$goldIsSafe(ServerLevel level, AbstractPiglin brute, CallbackInfoReturnable<Optional<? extends LivingEntity>> cir) {
		Optional<? extends LivingEntity> t = cir.getReturnValue();
		if (t.isPresent() && t.get() instanceof Player player && Piglins.leavesAlone(brute, player)) {
			cir.setReturnValue(Optional.empty());
		}
	}
}
