package com.mobsminecraft.ai;

/** For tests. */
public final class PiglinsAccess {
	private PiglinsAccess() {}

	public static boolean isGoldenArmor(net.minecraft.world.item.ItemStack s) {
		return Piglins.isGoldenArmor(s);
	}
}
