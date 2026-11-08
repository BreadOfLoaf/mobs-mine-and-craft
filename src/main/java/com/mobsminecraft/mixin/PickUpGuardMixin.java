package com.mobsminecraft.mixin;

import com.mobsminecraft.ai.Piglins;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No mob picks up what a piglin traded: that's the player's. */
@Mixin(Mob.class)
public abstract class PickUpGuardMixin {
	@Inject(method = "pickUpItem", at = @At("HEAD"), cancellable = true)
	private void mmc$leaveTradesAlone(ServerLevel level, ItemEntity item, CallbackInfo ci) {
		if (Piglins.forPlayers(item)) {
			ci.cancel();
		}
	}
}
