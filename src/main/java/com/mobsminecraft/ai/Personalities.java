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
		Player player = nearestPlayer(level, mob, 12.0);
		if (player != null && keepAway(b, player, 12.0, true)) {
			return true;
		}
		if (!griefing) {
			return false;
		}
		// Torches in the dark (tunnels, caves, night).
		if (b.torches > 0 && mob.tickCount % 40 == 0 && mob.onGround()
			&& level.getBrightness(LightLayer.BLOCK, mob.blockPosition()) < 5 && level.getBlockState(mob.blockPosition()).isAir()
			&& BlockWork.isFloor(level, mob.blockPosition().below())) {
			BlockPos spot = BlockWork.findAdjacentSpot(level, mob);
			if (spot != null && b.place(level, spot, Blocks.TORCH.defaultBlockState(), true)) {
				b.torches--;
				com.mobsminecraft.debug.AiStats.add("mineman_torch");
			}
		}
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
		if (victim != null && (!victim.isAlive() || victim.distanceTo(mob) > 24.0F)) {
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
	private static void slain(SmartBrain b, ServerLevel level, LivingEntity victim) {
		Mob mob = b.mob;
		b.imposterKills++;
		com.mobsminecraft.debug.AiStats.add("imposter_kill");
		for (EquipmentSlot slot : EquipmentSlot.values()) {
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
		if (b.crafterTicks == 0 && keepAway(b, target != null ? target : nearestPlayer(level, mob, 8.0), 8.0, true)) {
			return true;
		}
		if (b.crafterTicks == 0) {
			if (b.crafterCooldown > 0 || !mob.onGround()) {
				return false;
			}
			b.crafterCooldown = 160 + mob.getRandom().nextInt(200);
			Mob ally = needyAlly(level, mob);
			if (ally == null) {
				return false;
			}
			b.crafterFor = ally;
			b.crafterTable = BlockWork.findNearest(level, mob.blockPosition(), 6, s -> s.is(Blocks.CRAFTING_TABLE));
			if (b.crafterTable == null && griefing) {
				b.crafterTable = b.placeAdjacent(level, Blocks.CRAFTING_TABLE.defaultBlockState());
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
		if (b.crafterTicks < 40) {
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
		if (mob.distanceTo(ally) > 2.5F) {
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
		if (++b.crafterMade >= 8) {
			b.crafterMade = 0;
			b.crafterCooldown = 2400 + mob.getRandom().nextInt(2400);
		}
		return true;
	}

	private static Mob needyAlly(ServerLevel level, Mob mob) {
		Mob best = null;
		for (Mob m : level.getEntitiesOfClass(Mob.class, mob.getBoundingBox().inflate(14.0), m -> m != mob && m.isAlive() && m instanceof Enemy == mob instanceof Enemy)) {
			Integer last = ((com.mobsminecraft.SmartMobHolder) mob).mmc$getBrain().crafterGiven.get(m.getUUID());
			if (last != null && mob.tickCount - last < 2400) {
				continue; // already made something for this one recently (and maybe it couldn't use it)
			}
			if (!craftFor(m, null).isEmpty() && (best == null || m.distanceToSqr(mob) < best.distanceToSqr(mob))) {
				best = m;
			}
		}
		if (best == null && mob.getRandom().nextFloat() < 0.5F) {
			// Everyone's kitted out: hand somebody a consumable instead.
			List<Mob> any = level.getEntitiesOfClass(Mob.class, mob.getBoundingBox().inflate(10.0), m -> m != mob && m.isAlive() && m instanceof Enemy == mob instanceof Enemy);
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
		Item[] stuff = {Items.SNOWBALL, Items.ENDER_PEARL, Items.FIRE_CHARGE, Items.COOKED_BEEF, Items.WIND_CHARGE};
		Item item = stuff[r.nextInt(stuff.length)];
		return new ItemStack(item, Math.min(item.getDefaultMaxStackSize(), 4));
	}
}
