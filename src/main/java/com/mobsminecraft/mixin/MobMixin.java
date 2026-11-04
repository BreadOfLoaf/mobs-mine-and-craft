package com.mobsminecraft.mixin;

import com.mobsminecraft.SmartMobHolder;
import com.mobsminecraft.ai.SmartBrain;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mob.class)
public abstract class MobMixin implements SmartMobHolder {
	@Unique
	private SmartBrain mmc$brain;

	@Override
	public SmartBrain mmc$getBrain() {
		if (this.mmc$brain == null) {
			this.mmc$brain = new SmartBrain((Mob) (Object) this);
		}
		return this.mmc$brain;
	}

	/** Gifters, imposters, minemen, engineers (and builders/crafters unless attacked) never lock on to the player. */
	@Inject(method = "setTarget", at = @At("HEAD"), cancellable = true)
	private void mmc$refuseTarget(net.minecraft.world.entity.LivingEntity target, CallbackInfo ci) {
		if (this.mmc$brain != null && this.mmc$brain.refusesTarget(target)) {
			ci.cancel();
		}
	}

	/** Mobs in the middle of building something don't despawn, so houses get finished. */
	@Inject(method = "checkDespawn", at = @At("HEAD"), cancellable = true)
	private void mmc$noDespawnWhileBuilding(CallbackInfo ci) {
		if (this.mmc$brain != null && this.mmc$brain.isBuilding()) {
			ci.cancel();
		}
	}

	/**
	 * Runs after goals and navigation have ticked but before the move/look/jump controls apply,
	 * so the smart brain can override where the mob walks this tick.
	 */
	@Inject(
		method = "serverAiStep",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/entity/Mob;customServerAiStep(Lnet/minecraft/server/level/ServerLevel;)V",
			shift = At.Shift.AFTER
		)
	)
	private void mmc$tickSmartBrain(CallbackInfo ci) {
		Mob self = (Mob) (Object) this;
		if (!self.isAlive() || !com.mobsminecraft.debug.AiStats.aiEnabled) {
			return;
		}
		if (com.mobsminecraft.debug.AiStats.timing) {
			long t = System.nanoTime();
			this.mmc$getBrain().tick();
			com.mobsminecraft.debug.AiStats.addBrainTime(System.nanoTime() - t, self.getType().toShortString());
		} else {
			this.mmc$getBrain().tick();
		}
	}
}
