package com.mobsminecraft.mixin;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.dimension.end.EnderDragonFight;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets the Boss code relink a dragon the fight lost track of. */
@Mixin(EnderDragonFight.class)
public interface EnderDragonFightAccessor {
	@Accessor("dragonUUID")
	void mmc$setDragonUUID(UUID uuid);

	@Accessor("origin")
	BlockPos mmc$origin();
}
