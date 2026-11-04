package com.mobsminecraft.debug;

import java.util.Map;
import java.util.TreeMap;

/** Counts what the mob AI does (blocks placed, consumables used, traps fired...) so playtests can measure it. */
public final class AiStats {
	private static final Map<String, Integer> COUNTS = new TreeMap<>();

	private AiStats() {}

	public static void add(String key) {
		COUNTS.merge(key, 1, Integer::sum);
	}

	public static Map<String, Integer> snapshot() {
		return new TreeMap<>(COUNTS);
	}

	public static void reset() {
		COUNTS.clear();
	}
}
