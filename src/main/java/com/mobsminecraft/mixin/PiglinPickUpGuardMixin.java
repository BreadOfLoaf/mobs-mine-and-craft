package com.mobsminecraft.mixin;

import com.mobsminecraft.ai.Piglins;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.piglin.Piglin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Piglins don't take back what they (or another piglin) just traded. */
@Mixin(Piglin.class)
public abstract class PiglinPickUpGuardMixin {
	@Inject(method = "pickUpItem", at = @At("HEAD"), cancellable = true)
	private void mmc$leaveTradesAlone(ServerLevel level, ItemEntity item, CallbackInfo ci) {
		if (Piglins.forPlayers(item)) {
			ci.cancel();
		}
	}
}
