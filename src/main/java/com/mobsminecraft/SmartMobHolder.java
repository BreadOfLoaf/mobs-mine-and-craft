package com.mobsminecraft;

import com.mobsminecraft.ai.SmartBrain;

/** Implemented on every {@link net.minecraft.world.entity.Mob} by {@link com.mobsminecraft.mixin.MobMixin}. */
public interface SmartMobHolder {
	SmartBrain mmc$getBrain();
}
