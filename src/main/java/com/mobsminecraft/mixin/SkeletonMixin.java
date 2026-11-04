package com.mobsminecraft.mixin;

import com.mobsminecraft.ai.Flank;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Skeletons hold their fire when an ally is standing in the way, and step aside for a clear shot. */
@Mixin(AbstractSkeleton.class)
public abstract class SkeletonMixin {
	@Inject(method = "performRangedAttack", at = @At("HEAD"), cancellable = true)
	private void mmc$holdFire(LivingEntity target, float power, CallbackInfo ci) {
		AbstractSkeleton self = (AbstractSkeleton) (Object) this;
		if (Flank.allyInLine(self, target)) {
			((com.mobsminecraft.SmartMobHolder) self).mmc$getBrain().stepAsideForShot();
			com.mobsminecraft.debug.AiStats.add("held_fire");
			ci.cancel();
		}
	}
}
