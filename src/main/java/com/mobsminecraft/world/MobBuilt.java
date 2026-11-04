package com.mobsminecraft.world;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Remembers every block a mob placed for a structure, so mobs don't mine their own (or each other's) houses,
 * forts and outposts back down. Saved with the world (one small file per dimension), so it survives restarts.
 */
public final class MobBuilt {
	private static final int MAX_PER_LEVEL = 500_000;
	private static final Map<ServerLevel, LongOpenHashSet> PLACED = new WeakHashMap<>();
	private static final Map<ServerLevel, Boolean> DIRTY = new WeakHashMap<>();

	private MobBuilt() {}

	private static LongOpenHashSet set(ServerLevel level) {
		return PLACED.computeIfAbsent(level, MobBuilt::load);
	}

	public static void mark(ServerLevel level, BlockPos pos) {
		LongOpenHashSet set = set(level);
		if (set.size() < MAX_PER_LEVEL && set.add(pos.asLong())) {
			DIRTY.put(level, true);
		}
	}

	public static void unmark(ServerLevel level, BlockPos pos) {
		if (set(level).remove(pos.asLong())) {
			DIRTY.put(level, true);
		}
	}

	public static boolean contains(ServerLevel level, BlockPos pos) {
		return set(level).contains(pos.asLong());
	}

	private static Path file(ServerLevel level) {
		String dim = level.dimension().identifier().toString().replace(':', '_').replace('/', '_');
		return level.getServer().getWorldPath(LevelResource.ROOT).resolve("data").resolve("mobsmineandcraft_built_" + dim + ".dat");
	}

	private static LongOpenHashSet load(ServerLevel level) {
		LongOpenHashSet set = new LongOpenHashSet();
		Path f = file(level);
		if (Files.isRegularFile(f)) {
			try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(Files.newInputStream(f)))) {
				int n = in.readInt();
				for (int i = 0; i < n; i++) {
					set.add(in.readLong());
				}
			} catch (IOException e) {
				com.mobsminecraft.MobsMineAndCraft.LOGGER.warn("Couldn't read mob-built blocks from {}", f, e);
			}
		}
		return set;
	}

	/** Writes the level's mob-built blocks to disk if they changed. */
	public static void save(ServerLevel level) {
		LongOpenHashSet set = PLACED.get(level);
		if (set == null || !Boolean.TRUE.equals(DIRTY.get(level))) {
			return;
		}
		Path f = file(level);
		try {
			Files.createDirectories(f.getParent());
			Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
			try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(tmp)))) {
				out.writeInt(set.size());
				for (LongIterator it = set.iterator(); it.hasNext();) {
					out.writeLong(it.nextLong());
				}
			}
			Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			DIRTY.put(level, false);
		} catch (IOException e) {
			com.mobsminecraft.MobsMineAndCraft.LOGGER.warn("Couldn't save mob-built blocks to {}", f, e);
		}
	}
}
