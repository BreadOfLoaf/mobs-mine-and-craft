package com.mobsminecraft.ai;

import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;

/** The behaviour each personality adds on top of the shared abilities (see {@link Personality}). */
final class Personalities {
	private Personalities() {}

	/** Runs the personality's own behaviour. Returns true if it took over the mob this tick. */
	static boolean tick(SmartBrain b, ServerLevel level, LivingEntity target, boolean griefing) {
		return switch (b.personality) {
			case GIFTER -> gifter(b, level);
			case MINEMAN -> mineman(b, level, griefing);
			case IMPOSTER -> imposter(b, level);
			case HERO -> hero(b, level, target, griefing);
			case ENGINEER -> engineer(b, level, target, griefing);
			case CRAFTER -> crafter(b, level, target, griefing);
			case BUILDER -> builderFight(b, level, griefing);
			default -> false;
		};
	}

	/** Won't target the player (gifters, imposters, minemen). Called right after vanilla targeting. */
	static void dropPlayerTarget(SmartBrain b) {
		if (b.personality.peaceful() && b.mob.getTarget() instanceof Player) {
			b.mob.setTarget(null);
		}
	}

	// ------------------------------------------------------------------ keeping distance

	/** Backs away from a player who is closer than {@code range}. Returns true while backing off. */
	static boolean keepAway(SmartBrain b, LivingEntity target, double range, boolean free) {
		Mob mob = b.mob;
		if (!free || target == null || mob.distanceTo(target) >= range) {
			return false;
		}
		// Pick a spot away from them once and walk there at a normal pace (no jittery re-aiming every tick).
		if (b.awayGoal == null || --b.awayTicks <= 0 || mob.position().distanceToSqr(b.awayGoal) < 2.0
			|| b.awayGoal.distanceToSqr(target.position()) < range * range) {
			Vec3 away = mob.position().subtract(target.position()).multiply(1.0, 0.0, 1.0);
			if (away.lengthSqr() < 1.0E-4) {
				away = new Vec3(1.0, 0.0, 0.0);
			}
			b.awayGoal = mob.position().add(away.normalize().scale(range + 3.0));
			b.awayTicks = 60;
		}
		b.walkTo(b.awayGoal, b.walkSpeed());
		return true;
	}

	/** The player this mob knows about (saw, heard or was told of recently) if within range; no wallhacks. */
	private static Player nearestPlayer(ServerLevel level, Mob mob, double radius) {
		SmartBrain b = ((com.mobsminecraft.SmartMobHolder) mob).mmc$getBrain();
		Player p = b.knownPlayer;
		if (p == null || !p.isAlive() || p.isSpectator() || p.isCreative() || level.getGameTime() - b.knownTick > 100 || p.distanceTo(mob) > radius) {
			return null;
		}
		return p;
	}

	/** Throws an item in an arc to land at someone's feet. */
	static void toss(ServerLevel level, Mob mob, LivingEntity to, ItemStack stack) {
		ItemEntity item = new ItemEntity(level, mob.getX(), mob.getEyeY() - 0.3, mob.getZ(), stack);
		Vec3 d = to.position().subtract(item.position());
		double horiz = Math.sqrt(d.x * d.x + d.z * d.z);
		item.setDeltaMovement(d.x * 0.1, d.y * 0.1 + Math.sqrt(horiz) * 0.08 + 0.15, d.z * 0.1);
		item.setPickUpDelay(10);
		level.addFreshEntity(item);
		mob.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
		level.playSound(null, mob.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.NEUTRAL, 0.6F, 0.6F);
	}

	// ------------------------------------------------------------------ gifter

	/** Walks up to the nearest player and gives them something every so often. Never fights. */
	private static boolean gifter(SmartBrain b, ServerLevel level) {
		Mob mob = b.mob;
		// Only seeks the player out when it has a gift ready; in between it just wanders about like any mob.
		if (b.giftsLeft <= 0 || b.giftCooldown > 0) {
			return false;
		}
		Player player = nearestPlayer(level, mob, 24.0);
		if (player == null) {
			return false;
		}
		double dist = mob.distanceTo(player);
		mob.getLookControl().setLookAt(player, 30.0F, 30.0F);
		if (dist > 3.0) {
			b.walkTo(player.position(), b.walkSpeed());
			return true;
		}
		b.holdStill();
		if (mob.tickCount % 20 == 0) {
			level.sendParticles(ParticleTypes.HEART, mob.getX(), mob.getEyeY() + 0.5, mob.getZ(), 1, 0.2, 0.1, 0.2, 0.0);
		}
		RandomSource r = mob.getRandom();
		toss(level, mob, player, gift(level, r));
		level.sendParticles(ParticleTypes.HAPPY_VILLAGER, mob.getX(), mob.getEyeY(), mob.getZ(), 8, 0.4, 0.3, 0.4, 0.0);
		b.giftsLeft--;
		b.giftCooldown = 1200 + r.nextInt(1800);
		com.mobsminecraft.debug.AiStats.add("gift");
		return true;
	}

	private static ItemStack gift(ServerLevel level, RandomSource r) {
		float roll = r.nextFloat();
		if (roll < 0.04F) {
			// The jackpot: an enchanted diamond piece.
			Item[] diamond = {Items.DIAMOND_SWORD, Items.DIAMOND_PICKAXE, Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS};
			ItemStack s = EnchantmentHelper.enchantItem(r, new ItemStack(diamond[r.nextInt(diamond.length)]), 20 + r.nextInt(11), level.registryAccess(), Optional.empty());
			com.mobsminecraft.debug.AiStats.add("gift_diamond");
			return s;
		}
		if (roll < 0.25F) {
			Item[] gear = {Items.IRON_SWORD, Items.STONE_SWORD, Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS,
				Items.CHAINMAIL_CHESTPLATE, Items.SHIELD, Items.BOW, Items.IRON_PICKAXE, Items.IRON_AXE};
			return new ItemStack(gear[r.nextInt(gear.length)]);
		}
		Item[] stuff = {Items.BREAD, Items.COOKED_BEEF, Items.GOLDEN_APPLE, Items.ARROW, Items.ENDER_PEARL, Items.TORCH, Items.COAL,
			Items.IRON_INGOT, Items.APPLE, Items.COOKED_PORKCHOP, Items.WATER_BUCKET, Items.SNOWBALL, Items.OAK_LOG};
		Item item = stuff[r.nextInt(stuff.length)];
		return new ItemStack(item, Math.min(item.getDefaultMaxStackSize(), item == Items.GOLDEN_APPLE ? 1 : 1 + r.nextInt(8)));
	}

	// ------------------------------------------------------------------ mineman

	/** Keeps away from players, mines ore, lights the way with torches and stashes the ore in a chest. */
	private static boolean mineman(SmartBrain b, ServerLevel level, boolean griefing) {
		Mob mob = b.mob;
		LivingEntity hitBy = mob.getLastHurtByMob();
		if (hitBy instanceof Player attacker && mob.tickCount - mob.getLastHurtByMobTimestamp() < 60 && keepAway(b, attacker, 8.0, true)) {
			return true;
		}
		if (!griefing) {
			return false;
		}
		// (No torches: minemen work in the dark, which leaves plenty of room for monsters to spawn.)
		// Enough ore: stash it in the nearest mob chest (or put one down).
		int ore = 0;
		for (ItemStack s : b.loot) {
			if (isOreItem(s)) ore += s.getCount();
		}
		if (ore >= 6 || (b.stashPos != null)) {
			return stash(b, level);
		}
		// Keen eye for ore: always looking (see the shortened ore cooldown in SmartBrain).
		b.oreCooldown = Math.min(b.oreCooldown, 20);
		return false;
	}

	static boolean isOreItem(ItemStack s) {
		return s.is(Items.RAW_IRON) || s.is(Items.RAW_COPPER) || s.is(Items.RAW_GOLD) || s.is(Items.COAL) || s.is(Items.DIAMOND)
			|| s.is(Items.EMERALD) || s.is(Items.LAPIS_LAZULI) || s.is(Items.REDSTONE) || s.is(Items.IRON_INGOT) || s.is(Items.GOLD_INGOT)
			|| s.is(Items.COPPER_INGOT);
	}

	private static boolean stash(SmartBrain b, ServerLevel level) {
		Mob mob = b.mob;
		if (b.stashPos == null) {
			b.stashPos = BlockWork.findNearestAt(level, mob.blockPosition(), 16, (p, s) -> s.getBlock() instanceof ChestBlock
				&& com.mobsminecraft.world.MobBuilt.contains(level, p));
			if (b.stashPos == null) {
				b.stashPos = b.placeAdjacent(level, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, mob.getDirection().getOpposite()));
			}
			if (b.stashPos == null) {
				return false;
			}
			b.stashTicks = 0;
		}
		Vec3 c = Vec3.atCenterOf(b.stashPos);
		if (++b.stashTicks > 300 || !(level.getBlockEntity(b.stashPos) instanceof Container chest)) {
			b.stashPos = null;
			return false;
		}
		if (mob.distanceToSqr(c) > 6.0) {
			b.walkTo(c, b.walkSpeed());
			return true;
		}
		b.holdStill();
		mob.getLookControl().setLookAt(c);
		int moved = 0;
		for (ItemStack s : List.copyOf(b.loot)) {
			if (!isOreItem(s)) continue;
			ItemStack rest = net.minecraft.world.level.block.entity.HopperBlockEntity.addItem(null, chest, s.copy(), null);
			if (rest.getCount() < s.getCount()) {
				moved += s.getCount() - rest.getCount();
				b.loot.remove(s);
				if (!rest.isEmpty()) b.loot.add(rest);
			}
		}
		level.playSound(null, b.stashPos, SoundEvents.CHEST_OPEN, SoundSource.NEUTRAL, 0.5F, 1.0F);
		mob.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
		com.mobsminecraft.debug.AiStats.add("mineman_stash", Math.max(1, moved));
		b.stashPos = null;
		return true;
	}

	// ------------------------------------------------------------------ imposter

	/** Picks fights with other mobs out of nowhere (never the player) and heals itself often. */
	private static boolean imposter(SmartBrain b, ServerLevel level) {
		Mob mob = b.mob;
		RandomSource r = mob.getRandom();
		// Loves staying alive: eats golden apples early.
		if (mob.getHealth() / mob.getMaxHealth() < 0.6F && !b.eating()) {
			for (Consumable.Carried c : b.carried) {
				if (c.type == Consumable.GOLDEN_APPLE && c.uses > 0) {
					Eating.start(b, level, new ItemStack(Items.GOLDEN_APPLE), 32, () -> {
						new ItemStack(Items.GOLDEN_APPLE).finishUsingItem(level, mob);
						if (--c.uses <= 0) b.carried.remove(c);
					});
					com.mobsminecraft.debug.AiStats.add("imposter_heal");
					return true;
				}
			}
		}
		LivingEntity victim = b.imposterVictim;
		if (victim != null && !victim.isAlive() && victim.getLastHurtByMob() == mob) {
			slain(b, level, victim);
		}
		boolean grudge = victim != null && victim == b.provokedBy && mob.tickCount < b.provokedUntil;
		if (victim != null && (!victim.isAlive() || victim.distanceTo(mob) > (grudge ? 48.0F : 24.0F))) {
			b.imposterVictim = null;
			victim = null;
		}
		// Between fights: hand the player something it stole.
		if (victim == null && !b.stolen.isEmpty() && b.giftCooldown <= 0) {
			Player player = nearestPlayer(level, mob, 16.0);
			if (player != null) {
				if (mob.distanceTo(player) > 3.0F) {
					b.walkTo(player.position(), b.walkSpeed());
					return true;
				}
				toss(level, mob, player, b.stolen.remove(0));
				b.giftCooldown = 600 + r.nextInt(600);
				com.mobsminecraft.debug.AiStats.add("imposter_gift");
				return true;
			}
		}
		if (victim == null && b.imposterCooldown <= 0) {
			List<Mob> near = level.getEntitiesOfClass(Mob.class, mob.getBoundingBox().inflate(14.0), m -> m != mob && m.isAlive()
				&& !(m instanceof net.minecraft.world.entity.animal.golem.IronGolem) && mob.hasLineOfSight(m));
			if (!near.isEmpty()) {
				victim = near.get(r.nextInt(near.size()));
				b.imposterVictim = victim;
				b.imposterCooldown = 200 + r.nextInt(400);
				com.mobsminecraft.debug.AiStats.add("imposter_betrayal");
			}
		}
		if (victim != null) {
			if (mob.getTarget() != victim) {
				mob.setTarget(victim);
			}
			if (imposterUtility(b, level, victim)) {
				return true;
			}
			// Mobs without a vanilla attack (or that won't chase this kind of target) get a hand.
			if (!(mob instanceof net.minecraft.world.entity.monster.RangedAttackMob)) {
				double reach = mob.getBbWidth() * 0.5 + victim.getBbWidth() * 0.5 + 1.0;
				if (mob.distanceTo(victim) > reach) {
					mob.getNavigation().moveTo(victim, b.walkSpeed() * 1.1);
				} else if (b.punchCooldown == 0) {
					b.punchCooldown = 20;
					mob.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
					if (mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE) != null) {
						mob.doHurtTarget(level, victim);
					} else {
						victim.hurtServer(level, mob.damageSources().mobAttack(mob), 3.0F);
					}
					com.mobsminecraft.debug.AiStats.add("imposter_hit");
				}
			}
			return true;
		}
		return false;
	}

	/** An imposter killed an "ally": it pockets their best gear and upgrades its own armour a step. */
	private static final net.minecraft.resources.Identifier IMPOSTER_GROWTH = net.minecraft.resources.Identifier.fromNamespaceAndPath(
		com.mobsminecraft.MobsMineAndCraft.MOD_ID, "imposter_growth");

	/**
	 * The imposter's tricks: pearl in on a victim that's getting away (or pearl out when badly hurt), a Slowness
	 * potion so the victim can't run, and Swiftness for itself. Returns true if it used something this tick.
	 */
	private static boolean imposterUtility(SmartBrain b, ServerLevel level, LivingEntity victim) {
		Mob mob = b.mob;
		RandomSource r = mob.getRandom();
		if (b.imposterUtilityCooldown > 0) {
			return false;
		}
		double dist = mob.distanceTo(victim);
		float hp = mob.getHealth() / mob.getMaxHealth();
		// Speed for itself.
		if (!mob.hasEffect(MobEffects.SPEED) && dist < 20.0) {
			b.imposterUtilityCooldown = 40;
			Eating.start(b, level, net.minecraft.world.item.alchemy.PotionContents.createItemStack(Items.POTION, net.minecraft.world.item.alchemy.Potions.SWIFTNESS),
				Eating.EAT_TICKS, () -> mob.addEffect(new MobEffectInstance(MobEffects.SPEED, 20 * 60, 0)));
			com.mobsminecraft.debug.AiStats.add("imposter_drink_speed");
			return true;
		}
		// Slow the victim so it can't run away.
		if (!victim.hasEffect(MobEffects.SLOWNESS) && dist > 3.0 && dist < 10.0 && mob.hasLineOfSight(victim)) {
			b.imposterUtilityCooldown = 100 + r.nextInt(60);
			ItemStack stack = net.minecraft.world.item.alchemy.PotionContents.createItemStack(Items.SPLASH_POTION, net.minecraft.world.item.alchemy.Potions.SLOWNESS);
			var thrown = new net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion(level, mob, stack);
			Vec3 d = victim.position().subtract(mob.position());
			net.minecraft.world.entity.projectile.Projectile.spawnProjectileUsingShoot(thrown, level, stack, d.x, d.y + Math.sqrt(d.x * d.x + d.z * d.z) * 0.2, d.z, 0.75F, 2.0F);
			mob.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
			level.playSound(null, mob.blockPosition(), SoundEvents.SPLASH_POTION_THROW, SoundSource.HOSTILE, 1.0F, 0.8F);
			com.mobsminecraft.debug.AiStats.add("imposter_slowness");
			return true;
		}
		// Ender pearl: close in on a victim that's far off, or get out when badly hurt.
		if ((dist > 12.0 && dist < 28.0 && mob.hasLineOfSight(victim)) || hp < 0.3F) {
			b.imposterUtilityCooldown = 200 + r.nextInt(100);
			Vec3 aim = hp < 0.3F ? mob.position().subtract(victim.position()).multiply(1.0, 0.0, 1.0).normalize().scale(12.0).add(0.0, 4.0, 0.0)
				: victim.position().subtract(mob.position()).add(0.0, dist * 0.15, 0.0);
			ItemStack pearl = new ItemStack(Items.ENDER_PEARL);
			var thrown = new net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl(level, mob, pearl);
			net.minecraft.world.entity.projectile.Projectile.spawnProjectileUsingShoot(thrown, level, pearl, aim.x, aim.y, aim.z, 1.5F, 1.0F);
			level.playSound(null, mob.blockPosition(), SoundEvents.ENDER_PEARL_THROW, SoundSource.HOSTILE, 0.5F, 0.4F);
			com.mobsminecraft.debug.AiStats.add(hp < 0.3F ? "imposter_pearl_escape" : "imposter_pearl_chase");
			return true;
		}
		return false;
	}

	private static void slain(SmartBrain b, ServerLevel level, LivingEntity victim) {
		Mob mob = b.mob;
		b.imposterKills++;
		com.mobsminecraft.debug.AiStats.add("imposter_kill");
		// Each kill makes it tougher: +2 max health (up to +20) and a little healing.
		var health = mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
		if (health != null && b.imposterKills <= 10) {
			health.removeModifier(IMPOSTER_GROWTH);
			health.addPermanentModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(IMPOSTER_GROWTH, 2.0 * b.imposterKills,
				net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE));
		}
		mob.heal(4.0F);
		for (EquipmentSlot slot : victim instanceof Player ? new EquipmentSlot[0] : EquipmentSlot.values()) {
			ItemStack st = victim.getItemBySlot(slot);
			if (!st.isEmpty() && st.isDamageableItem() && b.stolen.size() < 6) {
				b.stolen.add(st.copy());
				victim.setItemSlot(slot, ItemStack.EMPTY);
			}
		}
		if (b.stolen.isEmpty() && b.stolen.size() < 6 && mob.getRandom().nextFloat() < 0.5F) {
			// Nothing worth taking: it still "finds" something to give away later.
			Item[] loot = {Items.IRON_INGOT, Items.GOLDEN_CARROT, Items.ARROW, Items.BREAD};
			b.stolen.add(new ItemStack(loot[mob.getRandom().nextInt(loot.length)], 1 + mob.getRandom().nextInt(4)));
		}
		if (Juggernaut.canWearArmor(mob)) {
			ItemStack up = Gear.upgrade(b, mob.getRandom());
			if (up == null && b.imposterKills % 3 == 0) {
				// Fully diamond: enchant it instead.
				Gear.enchantAll(level, mob, mob.getRandom(), 10, 25);
			}
			if (up != null) {
				com.mobsminecraft.debug.AiStats.add("imposter_upgrade:" + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(up.getItem()).getPath());
			}
			level.playSound(null, mob.blockPosition(), SoundEvents.ARMOR_EQUIP_DIAMOND.value(), SoundSource.HOSTILE, 0.8F, 1.0F);
		}
	}

	// ------------------------------------------------------------------ engineer

	/**
	 * Engineers never charge in. They circle the player at trap range and keep setting things up: dispensers,
	 * repeater-clock turrets, TNT carts. Getting too close makes them back off.
	 */
	private static boolean engineer(SmartBrain b, ServerLevel level, LivingEntity target, boolean griefing) {
		Mob mob = b.mob;
		if (target == null || !(mob instanceof Enemy)) {
			return false;
		}
		if (RedstoneTraps.tick(b, level, target, griefing)) {
			return true;
		}
		double dist = mob.distanceTo(target);
		if (dist < 5.0) {
			return keepAway(b, target, 9.0, true);
		}
		if (dist > 24.0) {
			b.walkTo(target.position(), b.walkSpeed());
			return true;
		}
		// Drops a landmine behind itself now and then: a TNT block set into the ground under a stone pressure plate.
		if (b.mineCooldown <= 0 && griefing && mob.onGround()) {
			b.mineCooldown = 60 + mob.getRandom().nextInt(60);
			if (layMine(b, level, target)) {
				return true;
			}
		}
		// Orbit: a point a little way round the circle at about 10 blocks.
		if (mob.getRandom().nextInt(200) == 0) {
			b.orbitDir = -b.orbitDir;
		}
		double a = Math.atan2(mob.getZ() - target.getZ(), mob.getX() - target.getX()) + 0.45 * b.orbitDir;
		double radius = 10.0;
		Vec3 goal = new Vec3(target.getX() + Math.cos(a) * radius, mob.getY(), target.getZ() + Math.sin(a) * radius);
		if (mob.tickCount % 10 == 0) {
			mob.getNavigation().moveTo(goal.x, goal.y, goal.z, b.walkSpeed());
		}
		mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
		if (mob.tickCount % 100 == 0) {
			com.mobsminecraft.debug.AiStats.add("engineer_orbit");
		}
		return true;
	}

	/** A pressure-plate mine one block behind the engineer (on the side away from where it's heading). */
	static boolean layMine(SmartBrain b, ServerLevel level, LivingEntity target) {
		Mob mob = b.mob;
		Vec3 heading = mob.getDeltaMovement().multiply(1.0, 0.0, 1.0);
		if (heading.lengthSqr() < 1.0E-4 && target != null) {
			heading = mob.position().subtract(target.position()).multiply(1.0, 0.0, 1.0);
		}
		// Not a minefield: a few mines in any one area is plenty.
		if (com.mobsminecraft.world.MobMines.countNear(level, mob.blockPosition(), 12) >= 4) {
			return false;
		}
		if (heading.lengthSqr() < 1.0E-4) {
			return false;
		}
		Direction back = Direction.getApproximateNearest(-heading.x, 0.0, -heading.z);
		BlockPos plate = mob.blockPosition().relative(back);
		BlockPos ground = plate.below();
		var gs = level.getBlockState(ground);
		if (!level.getBlockState(plate).canBeReplaced() || !level.getFluidState(plate).isEmpty() || !BlockWork.isNatural(level, ground, gs)
			|| !BlockWork.blocksMovement(level, ground) || target != null && target.distanceTo(mob) < 3.0F) {
			return false;
		}
		level.setBlock(ground, Blocks.TNT.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
		level.setBlock(plate, Blocks.STONE_PRESSURE_PLATE.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
		if (mob instanceof net.minecraft.world.entity.monster.Enemy) {
			com.mobsminecraft.world.MobMines.add(level, plate);
		}
		level.playSound(null, plate, SoundEvents.STONE_PLACE, SoundSource.HOSTILE, 0.6F, 1.2F);
		mob.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
		// Unexploded mines are cleared away after a few minutes.
		com.mobsminecraft.world.TimedCleanup.scheduleBlock(level, plate, Blocks.STONE_PRESSURE_PLATE, 6000);
		com.mobsminecraft.debug.AiStats.add("engineer_mine");
		return true;
	}

	// ------------------------------------------------------------------ hero

	/** Nearly beaten: flee, craft Juggernaut armour and a totem at a table, and come back much stronger. */
	private static boolean hero(SmartBrain b, ServerLevel level, LivingEntity target, boolean griefing) {
		Mob mob = b.mob;
		if (b.heroDone) {
			return false;
		}
		RandomSource r = mob.getRandom();
		if (b.heroPhase == 0) {
			if (target == null || mob.getHealth() / mob.getMaxHealth() > 0.35F) {
				return false;
			}
			b.heroPhase = 1;
			b.heroTicks = 0;
			b.heroFrom = target;
			com.mobsminecraft.debug.AiStats.add("hero_flee");
		}
		b.heroTicks++;
		if (b.heroPhase == 1) {
			// Run for it.
			mob.setTarget(null);
			LivingEntity from = b.heroFrom != null ? b.heroFrom : target;
			if (from != null && from.isAlive() && mob.distanceTo(from) < 16.0F && b.heroTicks < 120) {
				keepAway(b, from, 16.0, true);
				return true;
			}
			b.heroPhase = 2;
			b.heroTicks = 0;
			b.heroTable = griefing ? b.placeAdjacent(level, Blocks.CRAFTING_TABLE.defaultBlockState()) : null;
			return true;
		}
		// Crafting the armour.
		mob.setTarget(null);
		b.holdStill();
		Vec3 focus = b.heroTable != null ? Vec3.atCenterOf(b.heroTable) : mob.getEyePosition().add(mob.getLookAngle());
		mob.getLookControl().setLookAt(focus);
		if (b.heroTicks % 8 == 0) {
			mob.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
			level.playSound(null, mob.blockPosition(), b.heroTicks % 16 == 0 ? SoundEvents.ANVIL_USE : SoundEvents.WOOD_HIT, SoundSource.HOSTILE, 0.6F, 1.0F);
			level.sendParticles(ParticleTypes.ENCHANT, focus.x, focus.y + 0.6, focus.z, 10, 0.5, 0.3, 0.5, 0.5);
		}
		if (b.heroTicks < 100) {
			return true;
		}
		// Transformation.
		if (Juggernaut.canWearArmor(mob)) {
			Juggernaut.make(mob, r);
			if (Juggernaut.isJuggernaut(mob) && mob instanceof net.minecraft.world.entity.monster.skeleton.AbstractSkeleton) {
				b.bowEnchanted = true;
			}
		} else {
			mob.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 20 * 600, 1));
			mob.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 20 * 600, 1));
			mob.addEffect(new MobEffectInstance(MobEffects.SPEED, 20 * 600, 0));
		}
		mob.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.TOTEM_OF_UNDYING));
		mob.setDropChance(EquipmentSlot.OFFHAND, 0.0F);
		mob.setHealth(mob.getMaxHealth());
		level.playSound(null, mob.blockPosition(), SoundEvents.TOTEM_USE, SoundSource.HOSTILE, 1.0F, 1.0F);
		level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, mob.getX(), mob.getY() + 1.0, mob.getZ(), 60, 0.5, 1.0, 0.5, 0.4);
		b.heroDone = true;
		b.heroPhase = 0;
		if (b.heroFrom != null && b.heroFrom.isAlive()) {
			mob.setTarget(b.heroFrom);
		}
		com.mobsminecraft.debug.AiStats.add("hero_transformed");
		return true;
	}

	// ------------------------------------------------------------------ builder in a fight

	/**
	 * A builder in a fight builds for its side: waist-high cover in front of an archer (it can shoot over it),
	 * a barricade across the player's way to its allies, or cover for itself. Returns true while busy.
	 */
	private static boolean builderFight(SmartBrain b, ServerLevel level, boolean griefing) {
		Mob mob = b.mob;
		Player player = nearestPlayer(level, mob, 24.0);
		if (b.wallPlan != null) {
			// The wall gets laid by the brain's wall step this tick; just stay put.
			b.holdStill();
			return false;
		}
		if (!griefing || player == null || !(mob instanceof Enemy)) {
			return false;
		}
		if (b.builderSpot != null) {
			// Walk over to where the wall goes, then lay it.
			if (mob.position().distanceToSqr(b.builderSpot) > 9.0 && ++b.builderSpotTicks < 100) {
				b.walkTo(b.builderSpot, b.walkSpeed());
				return true;
			}
			planWallAt(b, level, BlockPos.containing(b.builderSpot), player, b.builderWallHeight);
			b.builderSpot = null;
			return true;
		}
		if (b.builderWallCooldown > 0) {
			return false;
		}
		RandomSource r = mob.getRandom();
		b.builderWallCooldown = 100 + r.nextInt(80);
		// 1. Cover for an archer.
		Mob archer = null;
		for (Mob m : level.getEntitiesOfClass(Mob.class, mob.getBoundingBox().inflate(16.0), m -> m != mob && m.isAlive()
			&& m instanceof net.minecraft.world.entity.monster.RangedAttackMob && m instanceof Enemy && m.distanceTo(player) > 6.0F)) {
			if (archer == null || m.distanceToSqr(mob) < archer.distanceToSqr(mob)) archer = m;
		}
		Vec3 spot;
		int height;
		String kind;
		if (archer != null) {
			Vec3 toPlayer = player.position().subtract(archer.position()).multiply(1.0, 0.0, 1.0).normalize();
			spot = archer.position().add(toPlayer.scale(2.0));
			height = 1;
			kind = "builder_archer_cover";
		} else {
			// 2. A barricade across the player's way to the nearest allies (or to the builder itself).
			Vec3 toPlayer = player.position().subtract(mob.position()).multiply(1.0, 0.0, 1.0);
			if (toPlayer.lengthSqr() < 9.0) {
				return false;
			}
			spot = mob.position().add(toPlayer.normalize().scale(Math.min(4.0, toPlayer.length() / 2)));
			height = 2;
			kind = "builder_barricade";
		}
		b.builderSpot = spot;
		b.builderSpotTicks = 0;
		b.builderWallHeight = height;
		com.mobsminecraft.debug.AiStats.add(kind);
		return true;
	}

	/** A 5-wide wall at {@code center}, facing the player, {@code height} blocks high (waist-high walls get end posts). */
	private static void planWallAt(SmartBrain b, ServerLevel level, BlockPos center, Player player, int height) {
		Vec3 d = player.position().subtract(Vec3.atCenterOf(center));
		Direction facing = Direction.getApproximateNearest(d.x, 0.0, d.z);
		Direction side = facing.getClockWise();
		java.util.List<BlockPos> plan = new java.util.ArrayList<>();
		java.util.List<BlockPos> bases = new java.util.ArrayList<>();
		for (int i = -2; i <= 2; i++) {
			BlockPos col = center.relative(side, i);
			if (BlockWork.blocksMovement(level, col)) col = col.above();
			else if (!BlockWork.isFloor(level, col.below())) col = col.below();
			bases.add(col);
		}
		for (int y = 0; y < height; y++) {
			for (BlockPos base : bases) plan.add(base.above(y));
		}
		if (height == 1) {
			plan.add(bases.get(0).above());
			plan.add(bases.get(bases.size() - 1).above());
		}
		b.wallPlan = plan;
		b.wallState = b.buildState();
		b.wallIndex = 0;
		b.wallInterval = 2;
	}

	// ------------------------------------------------------------------ crafter

	/**
	 * Keeps back from the fight and crafts for its allies: a shield for anyone without one, an armour or weapon
	 * upgrade, a pickaxe, or a consumable, then tosses it over. (Potions are thrown by the supporter code.)
	 */
	private static boolean crafter(SmartBrain b, ServerLevel level, LivingEntity target, boolean griefing) {
		Mob mob = b.mob;
		if (b.crafterItem != null) {
			return deliver(b, level);
		}
		if (b.crafterTicks == 0 && keepAway(b, target != null ? target : nearestPlayer(level, mob, 4.0), 4.0, true)) {
			return true;
		}
		if (b.crafterTicks == 0) {
			if (b.crafterCooldown > 0 || !mob.onGround()) {
				return false;
			}
			b.crafterCooldown = 60 + mob.getRandom().nextInt(80);
			Mob ally = needyAlly(level, mob);
			if (ally == null) {
				// Nobody around to craft for: work on its own kit instead.
				if (Gear.hasUpgradeBelow(mob, 3)) {
					ally = mob;
				} else {
					return false;
				}
			}
			b.crafterFor = ally;
			b.crafterTable = BlockWork.findNearest(level, mob.blockPosition(), 6, s -> s.is(Blocks.CRAFTING_TABLE));
			if (b.crafterTable == null && griefing) {
				b.crafterTable = b.placeAdjacent(level, Blocks.CRAFTING_TABLE.defaultBlockState());
				// A little workshop: a brewing stand beside the table for its potions.
				if (b.crafterTable != null && BlockWork.findNearest(level, b.crafterTable, 3, st -> st.is(Blocks.BREWING_STAND)) == null) {
					b.placeAdjacent(level, Blocks.BREWING_STAND.defaultBlockState());
				}
			}
		}
		b.crafterTicks++;
		b.holdStill();
		Vec3 focus = b.crafterTable != null ? Vec3.atCenterOf(b.crafterTable) : mob.getEyePosition();
		mob.getLookControl().setLookAt(focus);
		if (b.crafterTicks % 8 == 0) {
			mob.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
			level.playSound(null, mob.blockPosition(), SoundEvents.WOOD_HIT, SoundSource.NEUTRAL, 0.8F, 1.0F);
		}
		if (b.crafterTicks < 30) {
			return true;
		}
		b.crafterTicks = 0;
		Mob ally = b.crafterFor;
		b.crafterFor = null;
		if (ally == null || !ally.isAlive() || ally.distanceTo(mob) > 20.0F) {
			return false;
		}
		ItemStack made = craftFor(ally, mob.getRandom());
		if (made.isEmpty()) {
			return false;
		}
		// Walk it over and hand it to them (nothing left lying on the ground).
		b.crafterItem = made;
		b.crafterFor = ally;
		b.crafterTicks = 0;
		b.crafterGiven.put(ally.getUUID(), mob.tickCount);

		return true;
	}

	/** Carries the crafted item to the ally and equips or stores it for them. */
	private static boolean deliver(SmartBrain b, ServerLevel level) {
		Mob mob = b.mob;
		Mob ally = b.crafterFor;
		if (ally == null || !ally.isAlive() || ++b.crafterTicks > 240 || ally.distanceTo(mob) > 32.0F) {
			b.crafterItem = null;
			b.crafterFor = null;
			b.crafterTicks = 0;
			return false;
		}
		// Close enough to toss it over (allies in a fight don't stand still for long).
		if (mob.distanceTo(ally) > 5.0F || !mob.hasLineOfSight(ally)) {
			b.walkTo(ally.position(), b.walkSpeed() * 1.2);
			return true;
		}
		ItemStack item = b.crafterItem;
		b.crafterItem = null;
		b.crafterFor = null;
		b.crafterTicks = 0;
		mob.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
		mob.getLookControl().setLookAt(ally);
		level.playSound(null, ally.blockPosition(), SoundEvents.ARMOR_EQUIP_GENERIC.value(), SoundSource.NEUTRAL, 0.8F, 1.0F);
		level.sendParticles(ParticleTypes.HAPPY_VILLAGER, ally.getX(), ally.getEyeY(), ally.getZ(), 5, 0.3, 0.3, 0.3, 0.0);
		EquipmentSlot slot = item.is(Items.SHIELD) ? EquipmentSlot.OFFHAND : ally.getEquipmentSlotForItem(item);
		boolean gear = item.is(Items.SHIELD) || slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR || Gear.weaponTier(item) > 0;
		if (gear) {
			ally.setItemSlot(slot, item);
			ally.setDropChance(slot, 0.1F);
		} else {
			Inventory.store(((com.mobsminecraft.SmartMobHolder) ally).mmc$getBrain(), item);
		}
		com.mobsminecraft.debug.AiStats.add("crafter_gave:" + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item.getItem()).getPath());

		return true;
	}

	private static Mob needyAlly(ServerLevel level, Mob mob) {
		Mob best = null;
		for (Mob m : level.getEntitiesOfClass(Mob.class, mob.getBoundingBox().inflate(20.0), m -> m != mob && m.isAlive() && m instanceof Enemy == mob instanceof Enemy)) {
			Integer last = ((com.mobsminecraft.SmartMobHolder) mob).mmc$getBrain().crafterGiven.get(m.getUUID());
			if (last != null && mob.tickCount - last < 600) {
				continue; // already made something for this one recently (and maybe it couldn't use it)
			}
			if (!craftFor(m, null).isEmpty() && (best == null || m.distanceToSqr(mob) < best.distanceToSqr(mob))) {
				best = m;
			}
		}
		if (best == null) {
			// Everyone's kitted out: hand somebody a consumable instead.
			List<Mob> any = level.getEntitiesOfClass(Mob.class, mob.getBoundingBox().inflate(16.0), m -> m != mob && m.isAlive() && m instanceof Enemy == mob instanceof Enemy);
			if (!any.isEmpty()) {
				best = any.get(mob.getRandom().nextInt(any.size()));
			}
		}
		return best;
	}

	/** What the ally needs most. With a null random it only checks whether there's anything. */
	private static ItemStack craftFor(Mob ally, RandomSource r) {
		if (Shields.canCarry(ally) && ally.getOffhandItem().isEmpty()) {
			return new ItemStack(Items.SHIELD);
		}
		if (Juggernaut.canWearArmor(ally)) {
			EquipmentSlot[] armor = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
			Item[][] tiers = {
				{Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS},
				{Items.CHAINMAIL_HELMET, Items.CHAINMAIL_CHESTPLATE, Items.CHAINMAIL_LEGGINGS, Items.CHAINMAIL_BOOTS},
				{Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS},
			};
			for (int i = 0; i < 4; i++) {
				int t = Gear.armorTier(ally.getItemBySlot(armor[i]));
				if (t < 3) {
					return new ItemStack(tiers[t][i]);
				}
			}
		}
		if (Gear.canWeapon(ally) && Gear.weaponTier(ally.getMainHandItem()) < 2) {
			return new ItemStack(Gear.weaponTier(ally.getMainHandItem()) == 0 ? Items.STONE_SWORD : Items.IRON_SWORD);
		}
		if (r == null) {
			return ItemStack.EMPTY;
		}
		// Nothing to upgrade: a consumable.
		// Half the time a drinkable potion it brewed (they drink it when it's useful); otherwise a throwable or food.
		if (r.nextBoolean()) {
			var potions = java.util.List.of(net.minecraft.world.item.alchemy.Potions.HEALING, net.minecraft.world.item.alchemy.Potions.STRENGTH,
				net.minecraft.world.item.alchemy.Potions.SWIFTNESS, net.minecraft.world.item.alchemy.Potions.REGENERATION,
				net.minecraft.world.item.alchemy.Potions.FIRE_RESISTANCE);
			return net.minecraft.world.item.alchemy.PotionContents.createItemStack(Items.POTION, potions.get(r.nextInt(potions.size())));
		}
		Item[] stuff = {Items.SNOWBALL, Items.ENDER_PEARL, Items.FIRE_CHARGE, Items.COOKED_BEEF, Items.WIND_CHARGE};
		Item item = stuff[r.nextInt(stuff.length)];
		return new ItemStack(item, Math.min(item.getDefaultMaxStackSize(), 4));
	}
}
