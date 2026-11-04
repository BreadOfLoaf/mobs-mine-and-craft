package com.mobsminecraft.test;

import com.mobsminecraft.debug.AiStats;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Plays the test bot like a real person would, using only what a player can do ({@link Bot}'s body: walking and
 * jumping with physics, mining at real speed, placing one block at a time, drawing a bow, raising a shield).
 * <ul>
 * <li>Fighting: times its swings to the attack cooldown, jumps for critical hits, strafes, blocks between swings and
 * against arrows, shoots skeletons, creepers and anything out of reach with the bow, backs off from groups, runs from a
 * fizzing creeper, eats when hurt, and pillars up out of reach when it's losing.</li>
 * <li>Survival (open world): explores on foot, mines the ore it can actually see, chops trees, digs tunnels into
 * hillsides with torches, builds and lights a small shelter at dusk, and goes back to it when hurt.</li>
 * </ul>
 * Damage is still recorded and cancelled by the harness, but the bot feels it: its health drops (after armour and
 * shield), it is knocked back, it heals by eating and resting, and if its health runs out it "dies" and respawns.
 */
final class PlayerBrain {
	enum Mode { ARENA, SURVIVAL }

	private final Bot bot;
	private final ServerLevel level;
	private final Mode mode;
	private final RandomSource r;
	private Vec3 home;

	int blocksMined, blocksPlaced, swings, crits, arrowsShot, arrowHits, shieldBlocks, deaths, meals, pillars, kills, torches;
	String doing = "starting";
	final Map<String, Integer> doingSamples = new TreeMap<>();

	// movement intent for this tick
	private Vec3 wish = Vec3.ZERO;
	private boolean sprint, jump, sneak;

	private int eatTicks = -1;
	private BlockPos pillarFeet;
	private int pillarLeft;
	private boolean onPillar;
	private Vec3 goal;
	private int goalTicks;
	private Vec3 lastPos = Vec3.ZERO;
	private int stuckTicks;
	private BlockPos digTarget;
	private int digGiveUp;
	private final List<BlockPos> buildPlan = new ArrayList<>();
	private final List<BlockState> buildStates = new ArrayList<>();
	private int buildIndex;
	private int buildStuck;
	private BlockPos base;
	private Direction tunnelDir;
	private int tunnelLeft;
	private int strafeDir = 1;
	private int strafeTicks;
	private int critJumpTick = -100;
	private int choreCooldown = 100;
	private int lastHurtTick = -1000;
	private int respawnTick = -1000;

	PlayerBrain(Bot bot, ServerLevel level, Mode mode, Vec3 home) {
		this.bot = bot;
		this.level = level;
		this.mode = mode;
		this.home = home;
		this.r = level.getRandom();
	}

	/** A mid-game survival loadout: full iron, sword, bow and arrows, pickaxe, axe, shovel, shield, blocks, food, torches. */
	static void giveKit(Bot bot) {
		ServerPlayer p = bot.player;
		var inv = p.getInventory();
		inv.clearContent();
		p.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
		p.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
		p.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
		p.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
		p.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
		inv.setItem(0, new ItemStack(Items.IRON_SWORD));
		inv.setItem(1, new ItemStack(Items.BOW));
		inv.setItem(2, new ItemStack(Items.IRON_PICKAXE));
		inv.setItem(3, new ItemStack(Items.IRON_AXE));
		inv.setItem(4, new ItemStack(Items.IRON_SHOVEL));
		inv.setItem(5, new ItemStack(Items.COBBLESTONE, 64));
		inv.setItem(6, new ItemStack(Items.COOKED_BEEF, 16));
		inv.setItem(7, new ItemStack(Items.TORCH, 48));
		inv.setItem(8, new ItemStack(Items.OAK_PLANKS, 64));
		inv.setItem(9, new ItemStack(Items.ARROW, 64));
		inv.setItem(10, new ItemStack(Items.COBBLESTONE, 64));
		inv.setSelectedSlot(0);
		p.setHealth(p.getMaxHealth());
		p.getFoodData().setFoodLevel(20);
		p.getFoodData().setSaturation(5.0F);
	}

	// ================================================================== the tick

	void tick() {
		ServerPlayer p = this.bot.player;
		if (!p.isAlive()) return;
		this.wish = Vec3.ZERO;
		this.sprint = false;
		this.jump = false;
		this.sneak = false;
		this.think();
		if (p.isInWater() && !this.onPillar) this.jump = true; // keep your head above water
		this.bot.move(this.wish, this.sprint, this.jump, this.sneak);
		if (p.tickCount % 100 == 0) this.doingSamples.merge(this.doing, 1, Integer::sum);
		if (p.position().distanceToSqr(this.lastPos) < 0.01 && this.wish.lengthSqr() > 0 && !this.bot.isMining()) {
			this.stuckTicks++;
		} else {
			this.stuckTicks = 0;
		}
		this.lastPos = p.position();
	}

	private void think() {
		ServerPlayer p = this.bot.player;
		List<Mob> threats = this.level.getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(24.0),
			m -> m.isAlive() && (m instanceof Enemy || m.getTarget() == p) && (m.distanceTo(p) < 6.0F || p.hasLineOfSight(m)));
		Mob nearest = null;
		int close = 0;
		for (Mob m : threats) {
			if (nearest == null || m.distanceToSqr(p) < nearest.distanceToSqr(p)) nearest = m;
			if (m.distanceTo(p) < 4.5F) close++;
		}
		float hp = p.getHealth();

		// 1. A creeper about to blow: get out of there.
		for (Mob m : threats) {
			if (m instanceof Creeper c && (c.getSwellDir() > 0 || c.isIgnited()) && c.distanceTo(p) < 6.0F && !this.onPillar) {
				this.doing = "fleeing_creeper";
				this.bot.cancelBow();
				this.bot.stopMining();
				this.runFrom(c.position());
				return;
			}
		}
		// 2. Up a pillar: stay there while it's bad, shoot from the top, eat, come back down when it's safe.
		if (this.pillarLeft > 0 || this.onPillar) {
			this.pillarTick(threats, hp);
			return;
		}
		// 3. Losing a fight: pillar up out of reach.
		if (hp <= 8.0F && close >= 2 && p.onGround() && this.bot.has(this::isBuildBlock) && this.headroom(4)) {
			this.pillarFeet = p.blockPosition();
			this.pillarLeft = 3 + this.r.nextInt(2);
			this.pillars++;
			AiStats.add("bot_pillar_up");
			this.pillarTick(threats, hp);
			return;
		}
		// 4. Eating (keeps going once started, backing away from whoever's nearest).
		if (this.eatTicks >= 0 || hp <= 12.0F && this.bot.has(this::isFood) && (nearest == null || nearest.distanceTo(p) > 4.0F || hp <= 6.0F)) {
			if (this.eatTick(nearest)) return;
		}
		// 5. Fighting.
		if (nearest != null && this.fight(threats, nearest, close)) {
			return;
		}
		this.bot.cancelBow();
		if (this.bot.player.isUsingItem() && this.bot.player.getUseItem().is(Items.SHIELD)) this.bot.stopUsing();
		// 6. Nothing to fight.
		if (this.mode == Mode.ARENA) {
			this.doing = "holding_ground";
			if (p.position().distanceTo(this.home) > 3.0) this.walkTo(this.home, false);
			return;
		}
		this.survive(hp);
	}

	// ================================================================== fighting

	private boolean fight(List<Mob> threats, Mob nearest, int close) {
		ServerPlayer p = this.bot.player;
		double dist = nearest.distanceTo(p);
		// Melee: anyone in reach.
		if (dist < 3.6) {
			this.bot.cancelBow();
			this.bot.stopMining();
			this.doing = "fighting";
			this.melee(nearest, threats, close);
			return true;
		}
		// Ranged: skeletons and other shooters, creepers before they arrive, anything we can't walk up to.
		Mob shootAt = null;
		for (Mob m : threats) {
			double d = m.distanceTo(p);
			boolean worth = m instanceof RangedAttackMob || m instanceof Creeper || m.getY() - p.getY() > 2.5 || this.mode == Mode.ARENA && d > 10;
			// Out in the world, don't waste arrows on things that aren't coming for us (except creepers getting close).
			if (this.mode == Mode.SURVIVAL && m.getTarget() != p && !(m instanceof Creeper && d < 14)) worth = false;
			if (this.mode == Mode.SURVIVAL && d > 22) worth = false;
			if (worth && d > 5.5 && d < 30 && p.hasLineOfSight(m) && (shootAt == null || d < shootAt.distanceTo(p))) shootAt = m;
		}
		boolean someoneClose = dist < 6.0;
		if (shootAt != null && !someoneClose && this.bot.has(s -> s.is(Items.BOW)) && this.bot.has(s -> s.is(ItemTags.ARROWS))) {
			this.bot.stopMining();
			this.doing = "shooting";
			if (this.bot.drawBow()) {
				this.bot.look(shootAt.getEyePosition());
				this.strafe(shootAt, 0.0, 0.35);
				if (this.bot.drawTicks() >= 20 + this.r.nextInt(4)) {
					this.bot.loose(shootAt);
					this.arrowsShot++;
					AiStats.add("bot_arrow_shot");
				}
				return true;
			}
		}
		this.bot.cancelBow();
		// Something is after us (or we're holding ground in the arena): go and meet it, shield up against shooters.
		Mob go = null;
		for (Mob m : threats) {
			if ((m.getTarget() == p || m.distanceTo(p) < 10.0F) && (go == null || m.distanceTo(p) < go.distanceTo(p))) go = m;
		}
		if (go == null) return false;
		if (this.mode == Mode.ARENA && go.position().distanceTo(this.home) > 14) return false;
		this.bot.stopMining();
		this.doing = "closing_in";
		boolean shooter = go instanceof RangedAttackMob && go.distanceTo(p) > 4.0F;
		if (shooter) {
			this.bot.raiseShield();
		} else if (p.isUsingItem() && p.getUseItem().is(Items.SHIELD)) {
			this.bot.stopUsing();
		}
		this.bot.look(go.getEyePosition());
		this.walkTo(go.position(), !shooter && go.distanceTo(p) > 5.0F);
		return true;
	}

	private void melee(Mob target, List<Mob> threats, int close) {
		ServerPlayer p = this.bot.player;
		this.bot.look(target.getEyePosition());
		float charge = p.getAttackStrengthScale(0.5F);
		double dist = target.distanceTo(p);
		boolean falling = !p.onGround() && p.getDeltaMovement().y < 0.0;
		// A skeleton drawing on us while we fight something else: turn the shield to it between swings.
		Mob aiming = null;
		for (Mob m : threats) {
			if (m instanceof RangedAttackMob && m.isUsingItem() && m != target && m.distanceTo(p) < 24.0F && p.hasLineOfSight(m)) aiming = m;
		}
		// Swing when recharged; better still on the way down from a jump (a critical hit).
		if (charge >= 0.95F && (falling || p.onGround() && p.tickCount - this.critJumpTick > 12)) {
			boolean shieldUp = target.isBlocking();
			if (shieldUp) AiStats.add("bot_axe_vs_shield");
			if (this.bot.swingAt(target, shieldUp)) {
				this.swings++;
				if (falling && p.fallDistance > 0 && !p.isSprinting()) {
					this.crits++;
					AiStats.add("bot_crit");
				}
				AiStats.add("bot_swing");
				this.strafeTicks = 0; // a step back after the hit (w-tap)
			}
		} else if (charge > 0.7F && p.onGround() && dist < 3.0 && this.r.nextFloat() < 0.6F && !(target instanceof Creeper)) {
			this.jump = true; // jump now, hit on the way down
			this.critJumpTick = p.tickCount;
		} else if (aiming != null && charge < 0.9F && dist > 2.0) {
			this.bot.look(aiming.getEyePosition());
			this.bot.raiseShield(); // the arrow's coming: block it
		} else if (charge < 0.6F && dist < 2.8 && !(target instanceof Creeper)) {
			this.bot.raiseShield(); // block between swings
		}
		// Footwork: keep at sword's length, circle, and keep a crowd in front rather than around.
		Vec3 away = Vec3.ZERO;
		if (close >= 3) {
			Vec3 c = Vec3.ZERO;
			int n = 0;
			for (Mob m : threats) {
				if (m.distanceTo(p) < 5.0F) {
					c = c.add(m.position());
					n++;
				}
			}
			away = p.position().subtract(c.scale(1.0 / n)).multiply(1, 0, 1);
			if (this.mode == Mode.ARENA && p.position().distanceTo(this.home) > 10) away = Vec3.ZERO;
		}
		double toward = target instanceof Creeper ? -1.0 : dist > 2.9 ? 0.7 : dist < 2.0 ? -0.6 : 0.0;
		if (this.strafeTicks++ < 4) toward = -0.8;
		this.strafe(target, toward, 0.6);
		if (away.lengthSqr() > 1.0E-4) this.wish = this.wish.add(away.normalize().scale(0.8));
		this.sprint = toward > 0 && p.onGround() && p.tickCount - this.critJumpTick > 14;
	}

	/** Circles the target: {@code toward} >0 closes in, <0 backs off; {@code side} is how much to strafe. */
	private void strafe(LivingEntity target, double toward, double side) {
		ServerPlayer p = this.bot.player;
		Vec3 to = target.position().subtract(p.position()).multiply(1, 0, 1);
		if (to.lengthSqr() < 1.0E-4) return;
		to = to.normalize();
		if (p.tickCount % 40 == 0 && this.r.nextFloat() < 0.4F) this.strafeDir = -this.strafeDir;
		Vec3 perp = new Vec3(-to.z, 0, to.x).scale(this.strafeDir * side);
		Vec3 w = to.scale(toward).add(perp);
		// Don't strafe off a ledge.
		BlockPos next = BlockPos.containing(p.position().add(w.normalize().scale(0.8)));
		if (w.lengthSqr() > 1.0E-4 && this.dropBelow(next) > 2) {
			this.strafeDir = -this.strafeDir;
			w = to.scale(toward);
		}
		this.wish = w;
		if (p.horizontalCollision && p.onGround()) this.jump = true;
	}

	private void runFrom(Vec3 danger) {
		ServerPlayer p = this.bot.player;
		Vec3 away = p.position().subtract(danger).multiply(1, 0, 1);
		if (away.lengthSqr() < 1.0E-4) away = new Vec3(1, 0, 0);
		this.wish = away.normalize();
		this.sprint = true;
		if (p.horizontalCollision && p.onGround()) this.jump = true;
		if (p.isUsingItem() && !p.getUseItem().is(Items.SHIELD)) this.bot.stopUsing();
	}

	private boolean eatTick(Mob nearest) {
		ServerPlayer p = this.bot.player;
		if (this.eatTicks < 0) {
			if (!this.bot.select(this::isFood)) return false;
			this.bot.cancelBow();
			this.bot.stopMining();
			p.getFoodData().setFoodLevel(Math.min(p.getFoodData().getFoodLevel(), 17)); // a hurt player is rarely full
			p.startUsingItem(net.minecraft.world.InteractionHand.MAIN_HAND);
			this.eatTicks = 0;
		}
		this.doing = "eating";
		if (nearest != null && nearest.distanceTo(p) < 8.0F) {
			Vec3 away = p.position().subtract(nearest.position()).multiply(1, 0, 1);
			if (away.lengthSqr() > 1.0E-4) this.wish = away.normalize();
		}
		if (++this.eatTicks >= 32) {
			ItemStack food = p.getMainHandItem();
			if (p.isUsingItem() && this.isFood(food)) {
				food.shrink(1); // vanilla didn't finish it for us
			}
			this.bot.stopUsing();
			p.heal(8.0F);
			this.meals++;
			AiStats.add("bot_ate");
			this.eatTicks = -1;
		}
		return true;
	}

	private void pillarTick(List<Mob> threats, float hp) {
		ServerPlayer p = this.bot.player;
		this.doing = "pillaring";
		if (this.pillarLeft > 0) {
			this.bot.look(Vec3.atCenterOf(this.pillarFeet).add(0, -1, 0));
			if (p.onGround()) this.jump = true;
			if (!p.onGround() && p.getY() > this.pillarFeet.getY() + 1.05) {
				if (this.bot.place(this.pillarFeet, this::isBuildBlock)) {
					this.blocksPlaced++;
					this.pillarFeet = this.pillarFeet.above();
					if (--this.pillarLeft == 0) this.onPillar = true;
				}
			}
			// Ran out of blocks or something's in the way: stop climbing.
			if (!this.bot.has(this::isBuildBlock) || !this.headroom(2)) {
				this.pillarLeft = 0;
				this.onPillar = true;
			}
			return;
		}
		// On top: heal, shoot whoever's below, and climb down once it's safe.
		Mob shootAt = null;
		for (Mob m : threats) {
			if (m.distanceTo(p) < 24.0F && (shootAt == null || m.distanceTo(p) < shootAt.distanceTo(p))) shootAt = m;
		}
		if (hp < 16.0F && this.bot.has(this::isFood) && this.eatTick(null)) {
			this.doing = "pillar_eating";
			return;
		}
		if (shootAt != null && this.bot.drawBow()) {
			this.doing = "pillar_shooting";
			this.bot.look(shootAt.getEyePosition());
			if (this.bot.drawTicks() >= 20) {
				this.bot.loose(shootAt);
				this.arrowsShot++;
				AiStats.add("bot_arrow_shot");
			}
			return;
		}
		this.bot.cancelBow();
		boolean crowded = false;
		for (Mob m : threats) {
			if (m.distanceTo(p) < 5.0F && !(m instanceof RangedAttackMob)) crowded = true;
		}
		if (hp >= 16.0F && !crowded || shootAt == null) {
			// Dig back down the pillar.
			BlockPos below = p.blockPosition().below();
			if (p.getY() - this.groundY(p.blockPosition()) <= 1.01) {
				this.onPillar = false;
				AiStats.add("bot_pillar_down");
				return;
			}
			if (this.bot.mine(below)) this.blocksMined++;
		}
	}

	/** Called by the harness for each hit the bot takes (after vanilla's invulnerability frames). */
	void onHurt(DamageSource source, float amount) {
		ServerPlayer p = this.bot.player;
		Vec3 from = source.getSourcePosition();
		if (amount <= 0) return;
		if (this.bot.shielding() && from != null && !source.is(DamageTypeTags.BYPASSES_SHIELD)) {
			Vec3 look = p.getViewVector(1.0F).multiply(1, 0, 1);
			Vec3 to = from.subtract(p.position()).multiply(1, 0, 1);
			if (look.lengthSqr() > 0 && to.lengthSqr() > 0 && look.normalize().dot(to.normalize()) > 0.0) {
				this.shieldBlocks++;
				AiStats.add("bot_shield_block");
				p.getOffhandItem().hurtAndBreak(1, p, EquipmentSlot.OFFHAND);
				if (source.getDirectEntity() instanceof LivingEntity attacker) {
					Vec3 push = attacker.position().subtract(p.position()).multiply(1, 0, 1).normalize().scale(0.5);
					attacker.push(push.x, 0.1, push.z);
				}
				return;
			}
		}
		if (p.tickCount - this.respawnTick < 60) return; // vanilla's 3 seconds of spawn protection
		float dmg = source.is(DamageTypeTags.BYPASSES_ARMOR) ? amount
			: CombatRules.getDamageAfterAbsorb(p, amount, source, p.getArmorValue(), (float) p.getAttributeValue(Attributes.ARMOR_TOUGHNESS));
		this.lastHurtTick = p.tickCount;
		if (from != null && !source.is(DamageTypeTags.NO_KNOCKBACK) && !source.is(DamageTypeTags.IS_FALL)) {
			this.bot.knockedBack(from, 0.4);
		}
		// An interrupted meal is lost, as for a player hit while eating... no: players keep eating. Keep going.
		if (p.getHealth() - dmg <= 0.0F) {
			this.die(source);
		} else {
			p.setHealth(p.getHealth() - dmg);
		}
	}

	private void die(DamageSource source) {
		ServerPlayer p = this.bot.player;
		this.deaths++;
		this.respawnTick = p.tickCount;
		AiStats.add("bot_died:" + source.type().msgId());
		p.setHealth(p.getMaxHealth());
		p.getFoodData().setFoodLevel(20);
		p.clearFire();
		p.setDeltaMovement(Vec3.ZERO);
		p.resetFallDistance();
		this.bot.stopUsing();
		this.bot.stopMining();
		this.pillarLeft = 0;
		this.onPillar = false;
		this.eatTicks = -1;
		this.buildPlan.clear();
		Vec3 spawn = this.mode == Mode.SURVIVAL && this.base != null ? Vec3.atBottomCenterOf(this.base) : this.home;
		this.bot.teleport(spawn);
		// Respawning restocks the essentials (the harness keeps the inventory; a real player would go and fetch it).
		if (!this.bot.has(s -> s.is(ItemTags.ARROWS))) this.bot.player.getInventory().add(new ItemStack(Items.ARROW, 32));
		if (!this.bot.has(this::isFood)) this.bot.player.getInventory().add(new ItemStack(Items.COOKED_BEEF, 8));
	}

	// ================================================================== survival

	private void survive(float hp) {
		ServerPlayer p = this.bot.player;
		long day = this.level.getOverworldClockTime() % 24000L;
		boolean dusk = day > 11500 && day < 23000;
		// Light up dark spots around us at night and in tunnels, like any player does.
		if (--this.choreCooldown <= 0) {
			this.choreCooldown = 20;
			BlockPos feet = p.blockPosition();
			if (this.level.getMaxLocalRawBrightness(feet) < 6 && this.noTorchNear(feet)) {
				for (Direction d : Direction.Plane.HORIZONTAL) {
					BlockPos t = feet.relative(d);
					if (this.level.getBlockState(t).isAir() && this.level.getBlockState(t.below()).isFaceSturdy(this.level, t.below(), Direction.UP)) {
						if (this.bot.place(t, s -> s.is(Items.TORCH))) {
							this.torches++;
							AiStats.add("bot_torch");
						}
						break;
					}
				}
			}
		}
		// Build a shelter as night falls.
		if (!this.buildPlan.isEmpty()) {
			this.build();
			return;
		}
		if (dusk && this.base == null && p.onGround()) {
			this.planShelter();
			if (!this.buildPlan.isEmpty()) {
				this.build();
				return;
			}
		}
		// Hurt: head back to the shelter to recover.
		if (hp < 10.0F && this.base != null && p.position().distanceTo(Vec3.atBottomCenterOf(this.base)) > 2.0) {
			this.doing = "going_home";
			this.walkTo(Vec3.atBottomCenterOf(this.base), true);
			return;
		}
		// Mining something specific (an ore we saw, a log, the next block of a tunnel).
		if (this.digTarget != null) {
			this.dig();
			return;
		}
		if (this.tunnelLeft > 0 && this.tunnelDir != null) {
			this.tunnel();
			return;
		}
		if (p.tickCount % 20 == 0) {
			BlockPos ore = this.findVisible(st -> st.is(BlockTags.ORES), 7);
			if (ore != null) {
				this.digTarget = ore;
				this.digGiveUp = 300;
				AiStats.add("bot_spotted_ore");
				return;
			}
			if (this.countItems(s -> s.is(ItemTags.LOGS) || s.is(ItemTags.PLANKS)) < 32) {
				BlockPos log = this.findVisible(st -> st.is(BlockTags.LOGS), 8);
				if (log != null) {
					this.digTarget = log;
					this.digGiveUp = 300;
					return;
				}
			}
			// Now and then dig into a hillside.
			if (this.r.nextInt(30) == 0) {
				Direction d = Direction.Plane.HORIZONTAL.getRandomDirection(this.r);
				BlockPos f = p.blockPosition().relative(d);
				if (this.minable(f) && this.minable(f.above())) {
					this.tunnelDir = d;
					this.tunnelLeft = 8 + this.r.nextInt(10);
					AiStats.add("bot_tunnel");
					return;
				}
			}
		}
		// Otherwise explore.
		this.doing = dusk ? "exploring_night" : "exploring";
		if (this.goal == null || ++this.goalTicks > 600 || p.position().distanceToSqr(this.goal) < 2.0 || this.stuckTicks > 40) {
			double a = this.r.nextDouble() * Math.PI * 2;
			double d = 12 + this.r.nextInt(24);
			Vec3 centre = this.base != null && this.r.nextBoolean() ? Vec3.atBottomCenterOf(this.base) : p.position();
			this.goal = centre.add(Math.cos(a) * d, 0, Math.sin(a) * d);
			this.goalTicks = 0;
			this.stuckTicks = 0;
		}
		this.walkTo(this.goal, false);
	}

	private void dig() {
		ServerPlayer p = this.bot.player;
		this.doing = this.level.getBlockState(this.digTarget).is(BlockTags.LOGS) ? "chopping" : "mining";
		if (this.level.getBlockState(this.digTarget).isAir() || --this.digGiveUp <= 0) {
			this.bot.stopMining();
			this.digTarget = null;
			return;
		}
		Vec3 c = Vec3.atCenterOf(this.digTarget);
		if (p.getEyePosition().distanceTo(c) > Bot.REACH) {
			this.walkTo(c, false);
			return;
		}
		// Can we actually reach its face? If another block is in the way, mine that first.
		BlockHitResult hit = this.level.clip(new ClipContext(p.getEyePosition(), c, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
		BlockPos at = hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos() : this.digTarget;
		if (!at.equals(this.digTarget) && !this.minable(at)) {
			this.digTarget = null;
			return;
		}
		boolean ore = this.level.getBlockState(at).is(BlockTags.LOGS) == false && !this.level.getBlockState(at).is(BlockTags.BASE_STONE_OVERWORLD);
		if (this.bot.mine(at)) {
			this.blocksMined++;
			if (at.equals(this.digTarget) && ore) AiStats.add("bot_mined_ore");
		}
	}

	private void tunnel() {
		ServerPlayer p = this.bot.player;
		this.doing = "tunnelling";
		BlockPos feet = p.blockPosition();
		BlockPos f = feet.relative(this.tunnelDir);
		for (BlockPos b : new BlockPos[] {f.above(), f}) {
			BlockState s = this.level.getBlockState(b);
			if (!s.getCollisionShape(this.level, b).isEmpty()) {
				if (!this.minable(b) || !this.level.getFluidState(b).isEmpty() || this.touchesFluid(b)) {
					this.tunnelLeft = 0; // lava or water behind it: stop, like a careful player
					return;
				}
				if (this.bot.mine(b)) this.blocksMined++;
				return;
			}
		}
		if (this.dropBelow(f) > 1) {
			this.tunnelLeft = 0;
			return;
		}
		this.wish = Vec3.atBottomCenterOf(f).subtract(p.position()).multiply(1, 0, 1);
		if (p.position().distanceTo(Vec3.atBottomCenterOf(f)) < 0.4) this.tunnelLeft--;
		if (this.stuckTicks > 40) this.tunnelLeft = 0;
	}

	/** A 5x5 cobblestone hut with a doorway, a roof and a torch inside, built around where we stand. */
	private void planShelter() {
		ServerPlayer p = this.bot.player;
		BlockPos c = p.blockPosition();
		// Needs reasonably flat, dry ground.
		for (int dx = -2; dx <= 2; dx++)
			for (int dz = -2; dz <= 2; dz++) {
				BlockPos g = c.offset(dx, -1, dz);
				if (this.level.getBlockState(g).getCollisionShape(this.level, g).isEmpty() || !this.level.getFluidState(g.above()).isEmpty()) return;
			}
		Direction door = p.getDirection();
		for (int y = 0; y < 3; y++)
			for (int dx = -2; dx <= 2; dx++)
				for (int dz = -2; dz <= 2; dz++) {
					if (Math.abs(dx) != 2 && Math.abs(dz) != 2) continue;
					BlockPos b = c.offset(dx, y, dz);
					if (y < 2 && b.equals(c.relative(door, 2).above(y))) continue;
					this.addBuild(b, y == 0 ? Blocks.COBBLESTONE : Blocks.OAK_PLANKS);
				}
		List<BlockPos> roof = new ArrayList<>();
		for (int dx = -2; dx <= 2; dx++)
			for (int dz = -2; dz <= 2; dz++) roof.add(c.offset(dx, 3, dz));
		roof.sort((a, b) -> Integer.compare(Math.max(Math.abs(b.getX() - c.getX()), Math.abs(b.getZ() - c.getZ())), Math.max(Math.abs(a.getX() - c.getX()), Math.abs(a.getZ() - c.getZ()))));
		for (BlockPos b : roof) this.addBuild(b, Blocks.COBBLESTONE);
		this.base = c;
		this.buildIndex = 0;
		AiStats.add("bot_shelter_started");
	}

	private void addBuild(BlockPos b, net.minecraft.world.level.block.Block block) {
		if (this.level.getBlockState(b).canBeReplaced()) {
			this.buildPlan.add(b);
			this.buildStates.add(block.defaultBlockState());
		}
	}

	private void build() {
		ServerPlayer p = this.bot.player;
		this.doing = "building";
		while (this.buildIndex < this.buildPlan.size() && !this.level.getBlockState(this.buildPlan.get(this.buildIndex)).canBeReplaced()) this.buildIndex++;
		if (this.buildIndex >= this.buildPlan.size()) {
			this.buildPlan.clear();
			this.buildStates.clear();
			AiStats.add("bot_shelter_finished");
			// A torch in the middle.
			if (this.bot.place(this.base, s -> s.is(Items.TORCH))) this.torches++;
			return;
		}
		Vec3 stand = Vec3.atBottomCenterOf(this.base);
		if (p.position().distanceTo(stand) > 0.6) {
			this.walkTo(stand, false);
			if (++this.buildStuck > 200) {
				this.buildPlan.clear();
				this.buildStates.clear();
			}
			return;
		}
		this.buildStuck = 0;
		BlockPos b = this.buildPlan.get(this.buildIndex);
		net.minecraft.world.level.block.Block want = this.buildStates.get(this.buildIndex).getBlock();
		if (this.bot.place(b, s -> s.is(want.asItem()) || this.isBuildBlock(s))) {
			this.blocksPlaced++;
			AiStats.add("bot_placed");
			this.buildIndex++;
		} else if (!this.bot.has(this::isBuildBlock)) {
			this.buildPlan.clear();
			this.buildStates.clear();
		}
	}

	// ================================================================== walking

	/** Heads for a spot on foot: jumps up single steps, mines through walls, won't walk off cliffs, swims across water. */
	private void walkTo(Vec3 target, boolean run) {
		ServerPlayer p = this.bot.player;
		Vec3 d = target.subtract(p.position()).multiply(1, 0, 1);
		if (d.lengthSqr() < 0.04) return;
		Vec3 dir = d.normalize();
		BlockPos feet = p.blockPosition();
		BlockPos ahead = BlockPos.containing(p.position().add(dir.scale(0.8)));
		boolean blocked = this.solid(ahead) || this.solid(ahead.above());
		if (blocked) {
			boolean step = this.solid(ahead) && !this.solid(ahead.above()) && !this.solid(ahead.above(2)) && !this.solid(feet.above(2));
			if (step) {
				if (p.onGround()) this.jump = true;
			} else if (this.mode == Mode.SURVIVAL || this.stuckTicks > 20) {
				// A wall: dig through it (head height first, like a player).
				BlockPos b = this.solid(ahead.above()) ? ahead.above() : ahead;
				if (this.minable(b) && !this.touchesFluid(b)) {
					this.doing = "digging_through";
					if (this.bot.mine(b)) this.blocksMined++;
					return;
				}
				this.goal = null;
				return;
			}
		}
		// A drop of more than 3 blocks ahead: bridge across short gaps, otherwise go round.
		int drop = this.dropBelow(ahead);
		if (drop > 3 && !p.isInWater()) {
			if (this.mode == Mode.SURVIVAL && this.bot.has(this::isBuildBlock) && d.length() < 8) {
				this.doing = "bridging";
				this.sneak = true;
				this.wish = dir;
				BlockPos under = ahead.below();
				if (this.bot.place(under, this::isBuildBlock)) {
					this.blocksPlaced++;
					AiStats.add("bot_bridge");
				}
				return;
			}
			this.goal = null;
			return;
		}
		this.wish = dir;
		this.sprint = run && p.getFoodData().getFoodLevel() > 6;
		if (p.horizontalCollision && p.onGround()) this.jump = true;
	}

	// ================================================================== looking around

	/** The nearest block matching {@code what} within {@code radius} that we can actually see (no x-ray). */
	private BlockPos findVisible(java.util.function.Predicate<BlockState> what, int radius) {
		ServerPlayer p = this.bot.player;
		Vec3 eye = p.getEyePosition();
		BlockPos at = p.blockPosition();
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
		for (int dx = -radius; dx <= radius; dx++)
			for (int dy = -radius / 2; dy <= radius / 2 + 2; dy++)
				for (int dz = -radius; dz <= radius; dz++) {
					q.set(at.getX() + dx, at.getY() + dy, at.getZ() + dz);
					double dd = q.distSqr(at);
					if (dd >= bestD || !what.test(this.level.getBlockState(q))) continue;
					BlockHitResult hit = this.level.clip(new ClipContext(eye, Vec3.atCenterOf(q), ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, p));
					if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(q)) {
						best = q.immutable();
						bestD = dd;
					}
				}
		return best;
	}

	private boolean solid(BlockPos b) {
		return !this.level.getBlockState(b).getCollisionShape(this.level, b).isEmpty();
	}

	private int dropBelow(BlockPos b) {
		for (int i = 0; i < 6; i++) {
			if (this.solid(b.below(i + 1)) || !this.level.getFluidState(b.below(i + 1)).isEmpty()) return i;
		}
		return 6;
	}

	private int groundY(BlockPos from) {
		for (int i = 1; i < 40; i++) {
			if (this.solid(from.below(i)) && !this.level.getBlockState(from.below(i)).is(Blocks.COBBLESTONE)) return from.getY() - i + 1;
		}
		return from.getY();
	}

	private boolean headroom(int blocks) {
		BlockPos h = this.bot.player.blockPosition();
		for (int i = 2; i < 2 + blocks; i++) {
			if (this.solid(h.above(i))) return false;
		}
		return true;
	}

	private boolean minable(BlockPos b) {
		BlockState s = this.level.getBlockState(b);
		float hard = s.getDestroySpeed(this.level, b);
		return !s.isAir() && hard >= 0 && hard < 10 && s.getFluidState().isEmpty()
			&& (s.is(BlockTags.MINEABLE_WITH_PICKAXE) || s.is(BlockTags.MINEABLE_WITH_SHOVEL) || s.is(BlockTags.MINEABLE_WITH_AXE) || s.is(BlockTags.LEAVES));
	}

	private boolean touchesFluid(BlockPos b) {
		for (Direction d : Direction.values()) {
			if (!this.level.getFluidState(b.relative(d)).isEmpty()) return true;
		}
		return false;
	}

	private boolean noTorchNear(BlockPos feet) {
		for (BlockPos q : BlockPos.betweenClosed(feet.offset(-5, -2, -5), feet.offset(5, 2, 5))) {
			if (this.level.getBlockState(q).is(Blocks.TORCH) || this.level.getBlockState(q).is(Blocks.WALL_TORCH)) return false;
		}
		return true;
	}

	private boolean isFood(ItemStack s) {
		return s.is(Items.COOKED_BEEF) || s.is(Items.BREAD) || s.is(Items.COOKED_PORKCHOP) || s.is(Items.APPLE);
	}

	private boolean isBuildBlock(ItemStack s) {
		return s.is(Items.COBBLESTONE) || s.is(Items.OAK_PLANKS) || s.is(Items.DIRT) || s.is(Items.COBBLED_DEEPSLATE);
	}

	private int countItems(java.util.function.Predicate<ItemStack> what) {
		var inv = this.bot.player.getInventory();
		int n = 0;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (what.test(inv.getItem(i))) n += inv.getItem(i).getCount();
		}
		return n;
	}

	String summary() {
		StringBuilder sb = new StringBuilder();
		sb.append(String.format(java.util.Locale.ROOT,
			"Bot (player brain): swings %d (crits %d), arrows shot %d (hit %d), shield blocks %d, kills %d, meals %d, pillar-ups %d, deaths %d, mined %d, placed %d, torches %d, health %.0f%n",
			this.swings, this.crits, this.arrowsShot, this.arrowHits, this.shieldBlocks, this.kills, this.meals, this.pillars, this.deaths,
			this.blocksMined, this.blocksPlaced, this.torches, this.bot.player.getHealth()));
		int total = this.doingSamples.values().stream().mapToInt(Integer::intValue).sum();
		if (total > 0) {
			sb.append("  bot was:");
			this.doingSamples.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
				.forEach(e -> sb.append(String.format(java.util.Locale.ROOT, " %s %d%%", e.getKey(), Math.round(100.0 * e.getValue() / total))));
			sb.append('\n');
		}
		return sb.toString();
	}
}
