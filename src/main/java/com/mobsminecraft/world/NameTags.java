package com.mobsminecraft.world;

/** Debug name tags: when on, every mob shows its personality above its head (/modsmac nametags true|false). */
public final class NameTags {
	private static volatile boolean enabled;

	private NameTags() {}

	public static boolean enabled() {
		return enabled;
	}

	public static void set(boolean on) {
		enabled = on;
	}
}
