package com.mobsminecraft.ai;

import com.mobsminecraft.debug.AiStats;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ShulkerBullet;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.projectile.arrow.SpectralArrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.entity.projectile.hurtingprojectile.DragonFireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull;
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.WindCharge;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.EndSpikeFeature;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The ender dragon's personality, "Boss". The dragon's own AI never runs the mod's per-mob brain, so this ticks in
 * the End directly:
 * <ul>
 * <li>only one dragon: extra dragons (beyond the one the dragon fight tracks) are removed;</li>
 * <li>it attacks with every kind of projectile there is, one every few seconds;</li>
 * <li>it builds walls of end stone and obsidian near the player;</li>
 * <li>it slowly rebuilds destroyed crystals: fifteen seconds of channelling that any hit interrupts;</li>
 * <li>many Endermen side against it, attacking it instead of the player.</li>
 * </ul>
 */
public final class Boss {
	private Boss() {}

	/** Endermen that side against the dragon carry this tag; ones that rolled and didn't carry the other. */
	public static final String REBEL_TAG = "mobsmineandcraft.end_rebel";
	private static final String ROLLED_TAG = "mobsmineandcraft.end_rebel_rolled";
	private static final int CHANNEL_TICKS = 300;

	private static final class State {
		int attackCooldown = 80;
		int buildCooldown = 200;
		int crystalCooldown = 1200;
		BlockPos channelSpot;
		int channelTicks;
		float channelHealth;
	}

	private static final Map<UUID, State> STATES = new HashMap<>();

	/** Called every tick for the End. */
	public static void tickEnd(ServerLevel level) {
		List<? extends EnderDragon> dragons = level.getDragons();
		if (level.getGameTime() % 100 == 0) {
			if (dragons.size() > 1) {
				onlyOne(level, dragons);
				dragons = level.getDragons();
			}
			STATES.keySet().removeIf(id -> level.getEntity(id) == null);
		}
		for (EnderDragon dragon : dragons) {
			if (dragon.isAlive() && dragon.getPhaseManager().getCurrentPhase().getPhase() != EnderDragonPhase.DYING) {
				tickDragon(level, dragon);
			}
		}
		if (level.getGameTime() % 20 == 0 && !dragons.isEmpty()) {
			rebels(level, dragons.get(0));
		}
	}

	// ------------------------------------------------------------------ one dragon

	/** Keeps the dragon the fight is tracking (or the healthiest) and removes the rest. */
	private static void onlyOne(ServerLevel level, List<? extends EnderDragon> dragons) {
		UUID tracked = level.getDragonFight() != null ? level.getDragonFight().dragonUUID() : null;
		EnderDragon keep = null;
		for (EnderDragon d : dragons) {
			if (d.getUUID().equals(tracked)) keep = d;
		}
		if (keep == null) {
			for (EnderDragon d : dragons) {
				if (keep == null || d.getHealth() > keep.getHealth()) keep = d;
			}
		}
		int removed = 0;
		for (EnderDragon d : new ArrayList<>(dragons)) {
			if (d != keep) {
				d.discard();
				removed++;
			}
		}
		com.mobsminecraft.MobsMineAndCraft.LOGGER.info("[mobsmineandcraft] Removed {} extra ender dragon(s); kept {}", removed, keep.getUUID());
		AiStats.add("boss_extra_dragons_removed", removed);
	}

	// ------------------------------------------------------------------ the dragon

	private static void tickDragon(ServerLevel level, EnderDragon dragon) {
		if (!dragon.entityTags().contains("mobsmineandcraft.personality.BOSS")) {
			Personality.set(dragon, Personality.BOSS);
		}
		State s = STATES.computeIfAbsent(dragon.getUUID(), k -> new State());
		RandomSource r = dragon.getRandom();
		Player player = target(level, dragon);
		crystals(level, dragon, s, r);
		if (player == null) {
			return;
		}
		if (--s.attackCooldown <= 0) {
			s.attackCooldown = 50 + r.nextInt(40);
			if (dragon.distanceTo(player) < 80.0F && hasSight(level, dragon, player)) {
				shoot(level, dragon, player, r);
			}
		}
		if (--s.buildCooldown <= 0) {
			s.buildCooldown = 140 + r.nextInt(80);
			if (level.getGameRules().get(net.minecraft.world.level.gamerules.GameRules.MOB_GRIEFING) && dragon.distanceTo(player) < 100.0F) {
				buildNear(level, player, r);
			}
		}
	}

	private static Player target(ServerLevel level, EnderDragon dragon) {
		Player best = null;
		for (Player p : level.players()) {
			if (!p.isAlive() || p.isSpectator() || p.isCreative() || p.distanceTo(dragon) > 150.0F) continue;
			if (best == null || p.distanceToSqr(dragon) < best.distanceToSqr(dragon)) best = p;
		}
		return best;
	}

	private static boolean hasSight(ServerLevel level, EnderDragon dragon, Player p) {
		Vec3 from = dragon.head.position();
		return level.clip(new net.minecraft.world.level.ClipContext(from, p.getEyePosition(), net.minecraft.world.level.ClipContext.Block.COLLIDER,
			net.minecraft.world.level.ClipContext.Fluid.NONE, dragon)).getType() == net.minecraft.world.phys.HitResult.Type.MISS;
	}

	/** One attack, with a projectile picked at random from everything that flies. */
	private static void shoot(ServerLevel level, EnderDragon dragon, Player p, RandomSource r) {
		Vec3 from = dragon.head.position().add(0, 0.5, 0);
		Vec3 aim = p.getEyePosition().subtract(0, 0.4, 0);
		Vec3 dir = aim.subtract(from);
		double dist = dir.length();
		Vec3 n = dir.normalize();
		String kind;
		switch (r.nextInt(10)) {
			case 0 -> {
				kind = "dragon_fireball";
				add(level, new DragonFireball(level, dragon, n), from);
			}
			case 1 -> {
				kind = "ghast_fireball";
				add(level, new LargeFireball(level, dragon, n, 1), from);
			}
			case 2 -> {
				kind = "blaze_fireballs";
				for (int i = 0; i < 3; i++) {
					Vec3 spread = n.add(r.nextGaussian() * 0.04, r.nextGaussian() * 0.04, r.nextGaussian() * 0.04);
					add(level, new SmallFireball(level, dragon, spread.normalize()), from);
				}
			}
			case 3 -> {
				kind = "wither_skull";
				add(level, new WitherSkull(level, dragon, n), from);
			}
			case 4 -> {
				kind = "shulker_bullet";
				add(level, new ShulkerBullet(level, dragon, p, net.minecraft.core.Direction.Axis.Y), from);
			}
			case 5 -> {
				kind = "arrow_volley";
				for (int i = 0; i < 5; i++) {
					AbstractArrow a = r.nextInt(3) == 0 ? new SpectralArrow(level, dragon, new ItemStack(Items.SPECTRAL_ARROW), null)
						: new Arrow(level, dragon, new ItemStack(Items.ARROW), null);
					a.pickup = AbstractArrow.Pickup.DISALLOWED;
					a.setPos(from);
					a.shoot(dir.x, dir.y + dist * 0.12, dir.z, 2.4F, 8.0F);
					level.addFreshEntity(a);
				}
			}
			case 6 -> {
				kind = "splash_potion";
				var potion = switch (r.nextInt(3)) {
					case 0 -> Potions.HARMING;
					case 1 -> Potions.POISON;
					default -> Potions.SLOWNESS;
				};
				ThrownSplashPotion t = new ThrownSplashPotion(level, dragon, PotionContents.createItemStack(Items.SPLASH_POTION, potion));
				t.setPos(from);
				t.shoot(dir.x, dir.y + dist * 0.25, dir.z, 1.4F, 4.0F);
				level.addFreshEntity(t);
			}
			case 7 -> {
				kind = "wind_charge";
				WindCharge w = new WindCharge(level, from.x, from.y, from.z, n);
				w.setOwner(dragon);
				level.addFreshEntity(w);
			}
			case 8 -> {
				kind = "trident";
				ThrownTrident t = new ThrownTrident(level, dragon, new ItemStack(Items.TRIDENT));
				t.pickup = AbstractArrow.Pickup.DISALLOWED;
				t.setPos(from);
				t.shoot(dir.x, dir.y + dist * 0.08, dir.z, 2.5F, 4.0F);
				level.addFreshEntity(t);
			}
			default -> {
				// (Dragon breath already lingers a lot: the tenth slot is another volley of arrows.)
				kind = "arrow_volley";
				for (int i = 0; i < 4; i++) {
					Arrow a = new Arrow(level, dragon, new ItemStack(Items.ARROW), null);
					a.pickup = AbstractArrow.Pickup.DISALLOWED;
					a.setPos(from);
					a.shoot(dir.x, dir.y + dist * 0.12, dir.z, 2.4F, 8.0F);
					level.addFreshEntity(a);
				}
			}
		}
		level.playSound(null, from.x, from.y, from.z, SoundEvents.ENDER_DRAGON_SHOOT, SoundSource.HOSTILE, 4.0F, 0.9F + r.nextFloat() * 0.2F);
		AiStats.add("boss_shot:" + kind);
	}

	private static void add(ServerLevel level, Projectile p, Vec3 at) {
		p.setPos(at);
		level.addFreshEntity(p);
	}

	/** A short wall of end stone (sometimes obsidian) across the ground a few blocks from the player. */
	private static void buildNear(ServerLevel level, Player p, RandomSource r) {
		BlockPos feet = p.blockPosition();
		// Never on the exit portal.
		if (Math.abs(feet.getX()) < 7 && Math.abs(feet.getZ()) < 7) {
			return;
		}
		double a = r.nextDouble() * Math.PI * 2;
		int d = 2 + r.nextInt(3);
		BlockPos c = feet.offset((int) Math.round(Math.cos(a) * d), 0, (int) Math.round(Math.sin(a) * d));
		// Across the line to the player: a wall 3 wide.
		int sx = (int) Math.round(-Math.sin(a));
		int sz = (int) Math.round(Math.cos(a));
		BlockState state = r.nextInt(5) == 0 ? Blocks.OBSIDIAN.defaultBlockState() : Blocks.END_STONE.defaultBlockState();
		int height = 1 + r.nextInt(2);
		int placed = 0;
		for (int w = -1; w <= 1; w++) {
			BlockPos col = c.offset(sx * w, 0, sz * w);
			// Settle onto the ground (or up a step).
			for (int i = 0; i < 4 && level.getBlockState(col.below()).isAir(); i++) col = col.below();
			if (level.getBlockState(col.below()).isAir()) continue;
			for (int y = 0; y < height; y++) {
				BlockPos b = col.above(y);
				if (!level.getBlockState(b).isAir() || !level.getEntities(null, new AABB(b)).isEmpty()) continue;
				level.setBlockAndUpdate(b, state);
				level.sendParticles(ParticleTypes.REVERSE_PORTAL, b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5, 12, 0.3, 0.3, 0.3, 0.05);
				placed++;
			}
		}
		if (placed > 0) {
			level.playSound(null, c, SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 1.0F, 1.6F);
			AiStats.add("boss_built", placed);
		}
	}

	// ------------------------------------------------------------------ crystals

	private static void crystals(ServerLevel level, EnderDragon dragon, State s, RandomSource r) {
		if (s.channelSpot != null) {
			// Any hit breaks its concentration.
			if (dragon.getHealth() < s.channelHealth - 0.01F) {
				s.channelSpot = null;
				s.crystalCooldown = 600;
				level.playSound(null, dragon.blockPosition(), SoundEvents.ENDER_DRAGON_HURT, SoundSource.HOSTILE, 3.0F, 0.7F);
				AiStats.add("boss_crystal_interrupted");
				return;
			}
			s.channelHealth = Math.max(s.channelHealth, dragon.getHealth());
			Vec3 top = Vec3.atCenterOf(s.channelSpot);
			if (s.channelTicks % 4 == 0) {
				Vec3 from = dragon.head.position();
				for (int i = 0; i <= 12; i++) {
					Vec3 p = from.lerp(top, i / 12.0);
					level.sendParticles(ParticleTypes.END_ROD, p.x, p.y, p.z, 1, 0.05, 0.05, 0.05, 0.0);
				}
				level.sendParticles(ParticleTypes.PORTAL, top.x, top.y, top.z, 20, 0.5, 0.5, 0.5, 0.5);
			}
			if (++s.channelTicks >= CHANNEL_TICKS) {
				EndCrystal crystal = new EndCrystal(level, top.x, s.channelSpot.getY(), top.z);
				crystal.setShowBottom(true);
				level.addFreshEntity(crystal);
				level.playSound(null, s.channelSpot, SoundEvents.END_PORTAL_SPAWN, SoundSource.HOSTILE, 2.0F, 1.4F);
				AiStats.add("boss_crystal_rebuilt");
				s.channelSpot = null;
				s.crystalCooldown = 1200 + r.nextInt(600);
			}
			return;
		}
		if (--s.crystalCooldown > 0) {
			return;
		}
		s.crystalCooldown = 200;
		List<BlockPos> missing = new ArrayList<>();
		for (EndSpikeFeature.EndSpike spike : EndSpikeFeature.getSpikesForLevel(level)) {
			BlockPos spot = new BlockPos(spike.getCenterX(), spike.getHeight() + 1, spike.getCenterZ());
			if (!level.isLoaded(spot)) continue;
			if (level.getEntitiesOfClass(EndCrystal.class, new AABB(spot).inflate(2.0)).isEmpty()) missing.add(spot);
		}
		if (missing.isEmpty()) {
			return;
		}
		s.channelSpot = missing.get(r.nextInt(missing.size()));
		s.channelTicks = 0;
		s.channelHealth = dragon.getHealth();
		level.playSound(null, s.channelSpot, SoundEvents.BEACON_ACTIVATE, SoundSource.HOSTILE, 3.0F, 0.6F);
		AiStats.add("boss_crystal_channel");
	}

	// ------------------------------------------------------------------ Endermen

	/** Rolls Endermen near the dragon into rebels (40%), and sends rebels at it when it comes low. */
	private static void rebels(ServerLevel level, EnderDragon dragon) {
		for (Enderman e : level.getEntitiesOfClass(Enderman.class, dragon.getBoundingBox().inflate(96.0, 128.0, 96.0), Enderman::isAlive)) {
			if (!e.entityTags().contains(ROLLED_TAG)) {
				e.addTag(ROLLED_TAG);
				if (e.getRandom().nextFloat() < 0.4F) {
					e.addTag(REBEL_TAG);
					AiStats.add("boss_rebel_enderman");
				}
			}
			if (!e.entityTags().contains(REBEL_TAG)) continue;
			if (e.getTarget() instanceof Player && !(e.getLastHurtByMob() instanceof Player)) {
				e.setTarget(null);
			}
			double groundGap = dragon.getY() - e.getY();
			double horiz = Math.sqrt(Math.pow(dragon.getX() - e.getX(), 2) + Math.pow(dragon.getZ() - e.getZ(), 2));
			if (horiz < 8.0 && groundGap < 9.0) {
				// Close enough: hit it.
				dragon.hurt(level, dragon.head, level.damageSources().mobAttack(e), 5.0F);
				e.swing(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT);
				AiStats.add("boss_rebel_hit");
			} else if (groundGap < 20.0 && horiz < 64.0 && e.getRandom().nextInt(4) == 0) {
				// Under it: teleport beneath the dragon.
				double x = dragon.getX() + e.getRandom().nextGaussian() * 2.0;
				double z = dragon.getZ() + e.getRandom().nextGaussian() * 2.0;
				int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z));
				if (y > level.getMinY() + 1) {
					level.playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1.0F, 1.0F);
					e.teleportTo(x, y, z);
					level.playSound(null, x, y, z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1.0F, 1.0F);
					AiStats.add("boss_rebel_teleport");
				}
			}
		}
	}

	/** Test hook: start the next crystal rebuild soon. */
	public static void debugCrystalSoon() {
		for (State st : STATES.values()) {
			st.crystalCooldown = Math.min(st.crystalCooldown, 20);
		}
	}

	/**
	 * Endermen are neutral, as in vanilla: they leave a player alone unless that player has stared at one, angered it
	 * or hit it. Rebels (who are busy with the dragon) only fight a player who hits them.
	 */
	public static boolean leavesAlone(Mob mob, Player player) {
		if (!(mob instanceof Enderman e)) {
			return false;
		}
		boolean hit = mob.getLastHurtByMob() == player && mob.tickCount - mob.getLastHurtByMobTimestamp() < 600;
		if (hit) {
			return false;
		}
		if (mob.entityTags().contains(REBEL_TAG)) {
			return true;
		}
		boolean angry = e.isCreepy() || e.hasBeenStaredAt()
			|| e.getPersistentAngerTarget() != null && e.getPersistentAngerTarget().matches(player);
		return !angry;
	}

	/** Gifts from an End Gifter enderman: a golden apple, arrows, diamonds or a healing potion. */
	static ItemStack endGift(RandomSource r) {
		int roll = r.nextInt(100);
		if (roll < 25) return new ItemStack(Items.GOLDEN_APPLE);
		if (roll < 55) return new ItemStack(Items.ARROW, 8 + r.nextInt(9));
		if (roll < 75) return new ItemStack(Items.DIAMOND, 1 + r.nextInt(3));
		return PotionContents.createItemStack(Items.POTION, r.nextBoolean() ? Potions.HEALING : Potions.STRONG_HEALING);
	}
}
