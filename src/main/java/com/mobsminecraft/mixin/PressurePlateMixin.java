package com.mobsminecraft.mixin;

import com.mobsminecraft.world.MobMines;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.PressurePlateBlock;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Engineers' mines don't go off under hostile mobs (they know where their own mines are). */
@Mixin(PressurePlateBlock.class)
public abstract class PressurePlateMixin {
	@Inject(method = "getSignalStrength", at = @At("HEAD"), cancellable = true)
	private void mmc$mobMine(Level level, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
		if (!MobMines.isMine(level, pos)) {
			return;
		}
		AABB box = new AABB(pos.getX() + 0.0625, pos.getY(), pos.getZ() + 0.0625, pos.getX() + 0.9375, pos.getY() + 0.25, pos.getZ() + 0.9375);
		boolean trodOn = !level.getEntitiesOfClass(LivingEntity.class, box, e -> !e.isSpectator() && !e.isIgnoringBlockTriggers() && !(e instanceof Enemy)).isEmpty();
		cir.setReturnValue(trodOn ? 15 : 0);
	}
}
