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

	public static void add(String key, int amount) {
		COUNTS.merge(key, amount, Integer::sum);
	}

	public static Map<String, Integer> snapshot() {
		return new TreeMap<>(COUNTS);
	}

	public static void reset() {
		COUNTS.clear();
		brainNanos = 0;
		brainTicks = 0;
		slowest.clear();
	}

	// ------------------------------------------------------------------ cost of the AI (for stress tests)

	/** When false, the smart brain is skipped entirely (vanilla mobs), for baseline measurements. */
	public static volatile boolean aiEnabled = true;
	/** When true, the brain's run time is measured (a couple of nanoTime calls per mob per tick). */
	public static volatile boolean timing;
	private static long brainNanos;
	private static long brainTicks;
	private static final Map<String, Long> slowest = new TreeMap<>();

	public static void addBrainTime(long nanos, String what) {
		brainNanos += nanos;
		brainTicks++;
		slowest.merge(what, nanos, Long::sum);
	}

	public static long brainNanos() {
		return brainNanos;
	}

	public static long brainTicks() {
		return brainTicks;
	}

	/** Total brain time per mob type, in nanoseconds. */
	public static Map<String, Long> brainTimeByType() {
		return new TreeMap<>(slowest);
	}
}
