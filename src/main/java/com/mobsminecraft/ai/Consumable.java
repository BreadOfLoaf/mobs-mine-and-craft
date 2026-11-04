package com.mobsminecraft.ai;

import com.mobsminecraft.world.TimedCleanup;
import it.unimi.dsi.fastutil.ints.IntList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.WindCharge;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEgg;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownLingeringPotion;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Combat consumables a mob may carry. */
public enum Consumable {
	SNOWBALLS(16),
	EGGS(16),
	WIND_CHARGES(8),
	SPLASH_POTION(3),
	LINGERING_POTION(2),
	DRINK_POTION(2),
	FIRE_CHARGE(6),
	ENDER_PEARL(3),
	CHORUS_FRUIT(3),
	WATER_BUCKET(1000),
	LAVA_BUCKET(3),
	POWDER_SNOW_BUCKET(3),
	MILK_BUCKET(1),
	FLINT_AND_STEEL(32),
	FIREWORK_ROCKET(4),
	TRIDENT(3),
	GOLDEN_APPLE(2),
	ENCHANTED_GOLDEN_APPLE(1);

	/** One stack a mob carries: the type, uses left, and for potions which potion. */
	public static final class Carried {
		final Consumable type;
		int uses;
		Holder<Potion> potion;

		Carried(Consumable type, int uses, Holder<Potion> potion) {
			this.type = type;
			this.uses = uses;
			this.potion = potion;
		}
	}

	private final int uses;

	Consumable(int uses) {
		this.uses = uses;
	}

	boolean isApple() {
		return this == GOLDEN_APPLE || this == ENCHANTED_GOLDEN_APPLE;
	}

	/** Everything a mob can roll as its general-purpose consumable. Apples and totems have their own rolls. */
	private static final Consumable[] POOL = {
		SNOWBALLS, EGGS, WIND_CHARGES, SPLASH_POTION, LINGERING_POTION, DRINK_POTION, FIRE_CHARGE, ENDER_PEARL, CHORUS_FRUIT,
		WATER_BUCKET, LAVA_BUCKET, POWDER_SNOW_BUCKET, MILK_BUCKET, FLINT_AND_STEEL, FIREWORK_ROCKET, TRIDENT
	};

	private static final List<Holder<Potion>> THROWN_HARMFUL = List.of(
		Potions.HARMING, Potions.STRONG_HARMING, Potions.POISON, Potions.STRONG_POISON, Potions.SLOWNESS, Potions.STRONG_SLOWNESS,
		Potions.WEAKNESS, Potions.WEAVING, Potions.OOZING, Potions.INFESTED, Potions.WIND_CHARGED
	);
	private static final List<Holder<Potion>> THROWN_HELPFUL = List.of(Potions.HEALING, Potions.STRONG_HEALING, Potions.REGENERATION);
	private static final List<Holder<Potion>> DRINKS = List.of(
		Potions.HEALING, Potions.STRONG_HEALING, Potions.REGENERATION, Potions.STRENGTH, Potions.STRONG_STRENGTH, Potions.SWIFTNESS,
		Potions.FIRE_RESISTANCE, Potions.INVISIBILITY, Potions.TURTLE_MASTER, Potions.LEAPING
	);

	// ------------------------------------------------------------------ rolling inventory

	static void assign(SmartBrain brain, RandomSource r) {
		Mob mob = brain.mob;
		boolean passive = !(mob instanceof Enemy);
		if (mob instanceof Villager) {
			if (r.nextFloat() < 0.85F) {
				give(brain, FLINT_AND_STEEL, r);
			}
		}
		if (r.nextFloat() < (passive ? 0.75F : 0.6F)) {
			give(brain, fromPool(r), r);
		}
		if (r.nextFloat() < (passive ? 0.45F : 0.35F)) {
			give(brain, fromPool(r), r);
		}
		if (r.nextFloat() < 0.15F) {
			give(brain, ENDER_PEARL, r);
		}
		// Golden apples are real: eaten ones are gone, and any left over drop when the mob dies.
		float apple = r.nextFloat();
		if (apple < 0.02F) {
			addCarried(brain, ENCHANTED_GOLDEN_APPLE, 1, null);
		} else if (apple < (passive ? 0.08F : 0.18F)) {
			addCarried(brain, GOLDEN_APPLE, 1 + (r.nextFloat() < 0.3F ? 1 : 0), null);
		}
		// Totems work for any mob holding one; vanilla handles the resurrection.
		if (r.nextFloat() < 0.03F && mob.getItemBySlot(EquipmentSlot.OFFHAND).isEmpty()) {
			mob.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.TOTEM_OF_UNDYING));
		}
		brain.consumableCooldown = 30;
	}

	/** Adds picked-up consumables (counted in uses) to what the mob carries. */
	static void addCarried(SmartBrain brain, Consumable type, int uses, Holder<Potion> potion) {
		for (Carried c : brain.carried) {
			if (c.type == type && (potion == null || potion.equals(c.potion))) {
				c.uses += uses;
				return;
			}
		}
		brain.carried.add(new Carried(type, uses, potion));
	}

	private static void give(SmartBrain brain, Consumable type, RandomSource r) {
		for (Carried c : brain.carried) {
			if (c.type == type) {
				c.uses += type.uses;
				return;
			}
		}
		Holder<Potion> potion = switch (type) {
			case SPLASH_POTION -> r.nextFloat() < 0.7F ? pick(THROWN_HARMFUL, r) : pick(THROWN_HELPFUL, r);
			case LINGERING_POTION -> pick(THROWN_HARMFUL, r);
			case DRINK_POTION -> pick(DRINKS, r);
			default -> null;
		};
		brain.carried.add(new Carried(type, type.uses, potion));
	}

	/** A random consumable. Lava buckets are rare: about 1 in 150 rolls. */
	private static Consumable fromPool(RandomSource r) {
		Consumable c = POOL[r.nextInt(POOL.length)];
		while (c == LAVA_BUCKET && r.nextFloat() < 0.9F) {
			c = POOL[r.nextInt(POOL.length)];
		}
		return c;
	}

	private static <T> T pick(List<T> list, RandomSource r) {
		return list.get(r.nextInt(list.size()));
	}

	// ------------------------------------------------------------------ using

	static void tryUse(SmartBrain brain, ServerLevel level, LivingEntity target, boolean griefing) {
		if (brain.carried.isEmpty() || brain.consumableCooldown > 0) {
			return;
		}
		Mob mob = brain.mob;
		RandomSource r = mob.getRandom();
		List<Carried> order = new ArrayList<>(brain.carried);
		Collections.shuffle(order, new java.util.Random(r.nextLong()));
		for (Carried c : order) {
			int cooldown = use(brain, c, level, mob, target, griefing);
			if (cooldown > 0) {
				brain.consumableCooldown = cooldown * 2 + r.nextInt(20);
				com.mobsminecraft.debug.AiStats.add("use:" + c.type.name().toLowerCase());
				// Consumables never run out, except golden apples, which are eaten for real.
				if (c.type.isApple() && --c.uses <= 0) {
					brain.carried.remove(c);
				}
				mob.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
				return;
			}
		}
		// Nothing was worth using right now; look again shortly.
		brain.consumableCooldown = 5;
	}

	/** Returns the cooldown in ticks if the consumable was used, or 0 if it was not a good moment. */
	private static int use(SmartBrain brain, Carried c, ServerLevel level, Mob mob, LivingEntity target, boolean griefing) {
		double dist = mob.distanceTo(target);
		boolean sight = mob.hasLineOfSight(target);
		float hp = mob.getHealth() / mob.getMaxHealth();
		return switch (c.type) {
			case SNOWBALLS -> dist < 16 && sight ? throwItem(level, mob, target, new ItemStack(Items.SNOWBALL), SoundEvents.SNOWBALL_THROW, 10) : 0;
			case EGGS -> dist < 16 && sight ? throwItem(level, mob, target, new ItemStack(Items.EGG), SoundEvents.EGG_THROW, 10) : 0;
			case WIND_CHARGES -> dist < 14 && sight ? shootWindCharge(level, mob, target) : 0;
			case SPLASH_POTION -> useSplashPotion(c, level, mob, target, dist, sight, hp, false);
			case LINGERING_POTION -> useSplashPotion(c, level, mob, target, dist, sight, hp, true);
			case DRINK_POTION -> drinkPotion(c, level, mob, hp);
			case FIRE_CHARGE -> dist < 20 && sight ? shootFireball(level, mob, target) : 0;
			case ENDER_PEARL -> usePearl(brain, level, mob, target, dist, sight, hp);
			case CHORUS_FRUIT -> hp < 0.35F ? consume(level, mob, new ItemStack(Items.CHORUS_FRUIT), 60) : 0;
			case WATER_BUCKET -> useWater(level, mob, target, dist, sight);
			case LAVA_BUCKET -> griefing && dist < 5 && sight ? placeTemporary(level, mob, target.blockPosition(), Blocks.LAVA, SoundEvents.BUCKET_EMPTY_LAVA, 80, 140) : 0;
			case POWDER_SNOW_BUCKET -> griefing && dist < 5 && sight ? placeTemporary(level, mob, target.blockPosition(), Blocks.POWDER_SNOW, SoundEvents.BUCKET_EMPTY_POWDER_SNOW, 100, 120) : 0;
			case MILK_BUCKET -> hasHarmfulEffect(mob) ? consume(level, mob, new ItemStack(Items.MILK_BUCKET), 40) : 0;
			case FLINT_AND_STEEL -> griefing && dist < (mob instanceof Villager ? 7 : 5) && sight ? useFlintAndSteel(level, mob, target) : 0;
			case FIREWORK_ROCKET -> dist > 3 && dist < 18 && sight ? shootFirework(level, mob, target) : 0;
			case TRIDENT -> dist > 3 && dist < 20 && sight ? throwTrident(level, mob, target) : 0;
			case GOLDEN_APPLE -> hp < 0.45F && c.uses > 0 ? eatApple(level, mob, new ItemStack(Items.GOLDEN_APPLE)) : 0;
			case ENCHANTED_GOLDEN_APPLE -> hp < 0.35F && c.uses > 0 ? eatApple(level, mob, new ItemStack(Items.ENCHANTED_GOLDEN_APPLE)) : 0;
		};
	}

	/** Eats or drinks the item, which applies its normal effects (healing, teleport, clearing effects...). */
	private static int consume(ServerLevel level, Mob mob, ItemStack stack, int cooldown) {
		stack.finishUsingItem(level, mob);
		return cooldown;
	}

	private static int eatApple(ServerLevel level, Mob mob, ItemStack apple) {
		level.playSound(null, mob.blockPosition(), SoundEvents.GENERIC_EAT.value(), SoundSource.HOSTILE, 1.0F, 1.0F);
		level.playSound(null, mob.blockPosition(), SoundEvents.PLAYER_BURP, SoundSource.HOSTILE, 0.5F, 1.0F);
		level.sendParticles(new net.minecraft.core.particles.ItemParticleOption(net.minecraft.core.particles.ParticleTypes.ITEM, apple.getItem()),
			mob.getX(), mob.getEyeY() - 0.2, mob.getZ(), 10, 0.2, 0.1, 0.2, 0.05);
		return consume(level, mob, apple, 60);
	}

	private static boolean hasHarmfulEffect(Mob mob) {
		for (MobEffectInstance effect : mob.getActiveEffects()) {
			if (effect.getEffect().value().getCategory() == MobEffectCategory.HARMFUL) {
				return true;
			}
		}
		return false;
	}

	private static Vec3 aimAt(Mob mob, LivingEntity target, double fromY, double arc) {
		double dx = target.getX() - mob.getX();
		double dy = target.getEyeY() - 1.1 - fromY;
		double dz = target.getZ() - mob.getZ();
		double horiz = Math.sqrt(dx * dx + dz * dz);
		return new Vec3(dx, dy + horiz * arc, dz);
	}

	private static int throwItem(ServerLevel level, Mob mob, LivingEntity target, ItemStack stack, net.minecraft.sounds.SoundEvent sound, int cooldown) {
		Projectile p = stack.is(Items.EGG) ? new ThrownEgg(level, mob, stack) : new Snowball(level, mob, stack);
		Vec3 aim = aimAt(mob, target, p.getY(), 0.2);
		Projectile.spawnProjectileUsingShoot(p, level, stack, aim.x, aim.y, aim.z, 1.6F, 4.0F);
		level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), sound, SoundSource.HOSTILE, 0.8F, 0.8F);
		return cooldown;
	}

	private static int shootWindCharge(ServerLevel level, Mob mob, LivingEntity target) {
		Vec3 dir = new Vec3(target.getX() - mob.getX(), target.getY(0.5) - mob.getEyeY(), target.getZ() - mob.getZ()).normalize();
		WindCharge charge = new WindCharge(level, mob.getX(), mob.getEyeY(), mob.getZ(), dir);
		charge.setOwner(mob);
		level.addFreshEntity(charge);
		level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.WIND_CHARGE_THROW, SoundSource.HOSTILE, 0.8F, 0.8F);
		return 30;
	}

	private static int useSplashPotion(Carried c, ServerLevel level, Mob mob, LivingEntity target, double dist, boolean sight, float hp, boolean lingering) {
		boolean helpful = THROWN_HELPFUL.contains(c.potion);
		ItemStack stack = PotionContents.createItemStack(lingering ? Items.LINGERING_POTION : Items.SPLASH_POTION, c.potion);
		Projectile thrown = lingering ? new ThrownLingeringPotion(level, mob, stack) : new ThrownSplashPotion(level, mob, stack);
		if (helpful) {
			if (hp > 0.5F) {
				return 0;
			}
			// Splash it at its own feet.
			Projectile.spawnProjectileUsingShoot(thrown, level, stack, 0.0, -1.0, 0.0, 0.2F, 0.0F);
		} else {
			if (dist > 10 || !sight) {
				return 0;
			}
			Vec3 aim = aimAt(mob, target, mob.getY(), 0.2).add(target.getDeltaMovement().x, 0.0, target.getDeltaMovement().z);
			Projectile.spawnProjectileUsingShoot(thrown, level, stack, aim.x, aim.y, aim.z, dist <= 2.0 ? 0.45F : 0.75F, 6.0F);
		}
		level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.SPLASH_POTION_THROW, SoundSource.HOSTILE, 1.0F, 0.8F);
		return 60;
	}

	private static int drinkPotion(Carried c, ServerLevel level, Mob mob, float hp) {
		PotionContents contents = new PotionContents(c.potion);
		boolean worthIt = false;
		boolean healing = false;
		for (MobEffectInstance effect : contents.getAllEffects()) {
			Holder<MobEffect> type = effect.getEffect();
			if (type.equals(MobEffects.INSTANT_HEALTH) || type.equals(MobEffects.REGENERATION)) {
				healing = true;
			} else if (type.equals(MobEffects.FIRE_RESISTANCE)) {
				worthIt |= mob.isOnFire() && !mob.hasEffect(type);
			} else {
				worthIt |= !mob.hasEffect(type);
			}
		}
		if (healing ? hp > 0.5F : !worthIt) {
			return 0;
		}
		return consume(level, mob, PotionContents.createItemStack(Items.POTION, c.potion), 60);
	}

	private static int shootFireball(ServerLevel level, Mob mob, LivingEntity target) {
		Vec3 dir = new Vec3(target.getX() - mob.getX(), target.getY(0.5) - mob.getY(0.5), target.getZ() - mob.getZ()).normalize();
		SmallFireball fireball = new SmallFireball(level, mob, dir);
		fireball.setPos(fireball.getX(), mob.getY(0.5) + 0.5, fireball.getZ());
		level.addFreshEntity(fireball);
		level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.FIRECHARGE_USE, SoundSource.HOSTILE, 1.0F, 1.0F);
		return 35;
	}

	private static int usePearl(SmartBrain brain, ServerLevel level, Mob mob, LivingEntity target, double dist, boolean sight, float hp) {
		if (brain.sniperPhase == 3) {
			return 0; // a sniper stays on its perch
		}
		Vec3 aim;
		if (hp < 0.35F) {
			// Escape: throw away from the attacker.
			Vec3 away = mob.position().subtract(target.position()).multiply(1.0, 0.0, 1.0);
			if (away.lengthSqr() < 1.0E-4) {
				return 0;
			}
			aim = away.normalize().scale(12.0).add(0.0, 4.0, 0.0);
		} else if (sight && (dist > 14 || (dist > 8 && !brain.targetReachable))) {
			// Initiate: land right next to the target.
			aim = target.position().subtract(mob.position()).add(0.0, dist * 0.15, 0.0);
		} else {
			return 0;
		}
		ItemStack stack = new ItemStack(Items.ENDER_PEARL);
		ThrownEnderpearl pearl = new ThrownEnderpearl(level, mob, stack);
		Projectile.spawnProjectileUsingShoot(pearl, level, stack, aim.x, aim.y, aim.z, 1.5F, 1.0F);
		level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.ENDER_PEARL_THROW, SoundSource.HOSTILE, 0.5F, 0.4F);
		return 80;
	}

	private static int useWater(ServerLevel level, Mob mob, LivingEntity target, double dist, boolean sight) {
		if (mob.isOnFire()) {
			return placeTemporary(level, mob, mob.blockPosition(), Blocks.WATER, SoundEvents.BUCKET_EMPTY, 40, 60);
		}
		if (dist < 5 && sight) {
			return placeTemporary(level, mob, target.blockPosition(), Blocks.WATER, SoundEvents.BUCKET_EMPTY, 60, 100);
		}
		return 0;
	}

	/** Places a block from a bucket; it gets scooped back up after {@code lifetime} ticks. */
	private static int placeTemporary(ServerLevel level, Mob mob, BlockPos pos, Block block, net.minecraft.sounds.SoundEvent sound, int lifetime, int cooldown) {
		BlockState current = level.getBlockState(pos);
		if (!current.canBeReplaced() || !current.getFluidState().isEmpty()) {
			return 0;
		}
		if (!level.setBlock(pos, block.defaultBlockState(), Block.UPDATE_ALL)) {
			return 0;
		}
		level.playSound(null, pos, sound, SoundSource.HOSTILE, 1.0F, 1.0F);
		TimedCleanup.scheduleBlock(level, pos, block, lifetime);
		return cooldown;
	}

	private static int useFlintAndSteel(ServerLevel level, Mob mob, LivingEntity target) {
		RandomSource r = mob.getRandom();
		BlockPos base = target.blockPosition();
		int wanted = 1 + r.nextInt(3);
		int placed = 0;
		for (int i = 0; i < 10 && placed < wanted; i++) {
			BlockPos p = i == 0 ? base : base.offset(r.nextInt(3) - 1, 0, r.nextInt(3) - 1);
			if (setFire(level, p)) {
				placed++;
			}
		}
		if (placed == 0) {
			return 0;
		}
		level.playSound(null, base, SoundEvents.FLINTANDSTEEL_USE, SoundSource.HOSTILE, 1.0F, r.nextFloat() * 0.4F + 0.8F);
		return mob instanceof Villager ? 25 : 45;
	}

	static boolean setFire(ServerLevel level, BlockPos pos) {
		if (!level.getBlockState(pos).isAir()) {
			return false;
		}
		BlockState fire = BaseFireBlock.getState(level, pos);
		if (!fire.canSurvive(level, pos) || !BaseFireBlock.canBePlacedAt(level, pos, Direction.NORTH)) {
			return false;
		}
		return level.setBlock(pos, fire, Block.UPDATE_ALL_IMMEDIATE);
	}

	private static int shootFirework(ServerLevel level, Mob mob, LivingEntity target) {
		RandomSource r = mob.getRandom();
		ItemStack stack = new ItemStack(Items.FIREWORK_ROCKET);
		FireworkExplosion boom = new FireworkExplosion(FireworkExplosion.Shape.LARGE_BALL, IntList.of(0xFF3300 + r.nextInt(0xFF)), IntList.of(), true, false);
		stack.set(DataComponents.FIREWORKS, new Fireworks(1, List.of(boom, boom)));
		FireworkRocketEntity rocket = new FireworkRocketEntity(level, stack, mob, mob.getX(), mob.getEyeY() - 0.15, mob.getZ(), true);
		Vec3 aim = aimAt(mob, target, mob.getEyeY(), 0.0);
		Projectile.spawnProjectileUsingShoot(rocket, level, stack, aim.x, aim.y, aim.z, 1.6F, 1.0F);
		level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.HOSTILE, 1.0F, 1.0F);
		return 60;
	}

	private static int throwTrident(ServerLevel level, Mob mob, LivingEntity target) {
		ItemStack stack = new ItemStack(Items.TRIDENT);
		ThrownTrident trident = new ThrownTrident(level, mob, stack);
		trident.pickup = AbstractArrow.Pickup.CREATIVE_ONLY;
		Vec3 aim = aimAt(mob, target, trident.getY(), 0.2);
		Projectile.spawnProjectileUsingShoot(trident, level, stack, aim.x, aim.y, aim.z, 1.6F, 4.0F);
		level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.TRIDENT_THROW.value(), SoundSource.HOSTILE, 1.0F, 1.0F);
		return 50;
	}
}
