package com.mobsminecraft.ai;

import com.mobsminecraft.world.TimedCleanup;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.monster.spider.Spider;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.vehicle.boat.Boat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;

/** Behaviour unique to particular mob types. */
final class MobSpecials {
	private MobSpecials() {}

	static void init(SmartBrain brain, RandomSource r) {
		brain.specialCooldown = 40 + r.nextInt(60);
		if (brain.mob instanceof AbstractSkeleton) {
			brain.wallCooldown = 100 + r.nextInt(200);
			brain.sniper = r.nextFloat() < 0.35F;
			if (brain.sniper) {
				brain.blocks += 20;
			}
			if (r.nextFloat() < 0.15F) {
				brain.enchantTimer = 200 + r.nextInt(1200);
			}
			if (r.nextFloat() < 0.5F) {
				brain.fletchTimer = 100 + r.nextInt(600);
			}
		}
	}

	static void combat(SmartBrain brain, ServerLevel level, LivingEntity target, boolean griefing) {
		Mob mob = brain.mob;
		if (mob instanceof Spider) {
			spiderWebs(brain, level, target, griefing);
		} else if (mob instanceof Enderman) {
			endermanBoats(brain, level, target);
		} else if (mob instanceof Creeper) {
			creeperTnt(brain, level, target);
		} else if (mob instanceof AbstractSkeleton) {
			skeletonWall(brain, level, target, griefing);
		} else if (mob instanceof Villager villager) {
			villagerAttack(brain, level, villager, target);
		}
	}

	// ------------------------------------------------------------------ spiders

	private static void spiderWebs(SmartBrain brain, ServerLevel level, LivingEntity target, boolean griefing) {
		Mob mob = brain.mob;
		if (!griefing || brain.specialCooldown > 0 || mob.distanceTo(target) > 8 || !mob.hasLineOfSight(target)) {
			return;
		}
		RandomSource r = mob.getRandom();
		brain.specialCooldown = 300 + r.nextInt(200);
		BlockPos base = target.blockPosition();
		int wanted = r.nextFloat() < 0.25F ? 2 : 1;
		int placed = 0;
		if (r.nextFloat() < 0.1F && placeWeb(level, base)) {
			placed++;
		}
		for (int i = 0; i < 16 && placed < wanted; i++) {
			BlockPos p = base.offset(r.nextInt(5) - 2, r.nextInt(2), r.nextInt(5) - 2);
			if (!p.equals(base) && placeWeb(level, p)) {
				placed++;
			}
		}
		if (placed > 0) {
			mob.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
			level.playSound(null, base, SoundEvents.SPIDER_AMBIENT, SoundSource.HOSTILE, 1.0F, 1.4F);
		}
	}

	private static boolean placeWeb(ServerLevel level, BlockPos pos) {
		if (!level.getBlockState(pos).canBeReplaced() || !level.getFluidState(pos).isEmpty()) {
			return false;
		}
		level.setBlock(pos, Blocks.COBWEB.defaultBlockState(), Block.UPDATE_ALL);
		TimedCleanup.scheduleBlock(level, pos, Blocks.COBWEB, 300);
		return true;
	}

	// ------------------------------------------------------------------ endermen

	private static void endermanBoats(SmartBrain brain, ServerLevel level, LivingEntity target) {
		Mob mob = brain.mob;
		if (brain.specialCooldown > 0 || mob.getTarget() != target || mob.distanceTo(target) > 10) {
			return;
		}
		RandomSource r = mob.getRandom();
		brain.specialCooldown = 140 + r.nextInt(100);
		Vec3 t = target.position();
		if (r.nextFloat() < 0.45F && !target.isPassenger()) {
			Boat boat = spawnBoat(level, r, t.x, t.y, t.z);
			if (boat != null) {
				target.startRiding(boat);
			}
		} else {
			int count = 2 + r.nextInt(3);
			double offset = r.nextDouble() * Math.PI * 2;
			for (int i = 0; i < count; i++) {
				double a = offset + i * (Math.PI * 2 / count);
				spawnBoat(level, r, t.x + Math.cos(a) * 1.6, t.y, t.z + Math.sin(a) * 1.6);
			}
		}
		level.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1.0F, 0.7F);
	}

	private static Boat spawnBoat(ServerLevel level, RandomSource r, double x, double y, double z) {
		EntityType<Boat> type = switch (r.nextInt(4)) {
			case 0 -> EntityTypes.OAK_BOAT;
			case 1 -> EntityTypes.SPRUCE_BOAT;
			case 2 -> EntityTypes.BIRCH_BOAT;
			default -> EntityTypes.DARK_OAK_BOAT;
		};
		Boat boat = type.create(level, EntitySpawnReason.EVENT);
		if (boat == null) {
			return null;
		}
		boat.snapTo(x, y, z, r.nextFloat() * 360.0F, 0.0F);
		if (!level.noCollision(boat)) {
			return null;
		}
		level.addFreshEntity(boat);
		level.sendParticles(ParticleTypes.PORTAL, x, y + 0.5, z, 20, 0.5, 0.5, 0.5, 0.2);
		TimedCleanup.scheduleEntity(level, boat, 600);
		return boat;
	}

	// ------------------------------------------------------------------ creepers

	private static void creeperTnt(SmartBrain brain, ServerLevel level, LivingEntity target) {
		Mob mob = brain.mob;
		if (brain.specialCooldown > 0 || mob.distanceTo(target) > 10 || !mob.hasLineOfSight(target)) {
			return;
		}
		RandomSource r = mob.getRandom();
		brain.specialCooldown = 200 + r.nextInt(160);

		Vec3 t = target.position();
		Vec3 away = t.subtract(mob.position()).multiply(1.0, 0.0, 1.0);
		away = away.lengthSqr() < 1.0E-4 ? new Vec3(1.0, 0.0, 0.0) : away.normalize();
		Vec3 side = new Vec3(-away.z, 0.0, away.x);
		Vec3 predicted = t.add(target.getKnownMovement().multiply(15.0, 0.0, 15.0));

		// Where the player is heading, behind them (their escape route), and both flanks.
		List<Vec3> spots = new ArrayList<>(List.of(predicted, t.add(away.scale(2.5)), t.add(side.scale(2.5)), t.subtract(side.scale(2.5))));
		int count = 1 + r.nextInt(3);
		int placed = 0;
		while (placed < count && !spots.isEmpty()) {
			Vec3 spot = spots.remove(r.nextInt(spots.size()));
			BlockPos p = BlockPos.containing(spot);
			for (int i = 0; i < 3 && !BlockWork.isFloor(level, p.below()); i++) {
				p = p.below();
			}
			// Place an unlit TNT block; the creeper lights it a moment later (see tickUnlitTnt).
			if (!brain.place(level, p, Blocks.TNT.defaultBlockState(), false)) {
				continue;
			}
			brain.unlitTnt.add(p);
			placed++;
		}
		if (placed > 0) {
			brain.tntLightTicks = 20 + r.nextInt(20);
		}
	}

	/**
	 * Lights the creeper's placed TNT with flint and steel. If the player breaks the TNT first,
	 * or the creeper dies or wanders off, it stays unlit.
	 */
	static void tickUnlitTnt(SmartBrain brain, ServerLevel level) {
		if (brain.unlitTnt.isEmpty() || --brain.tntLightTicks > 0) {
			return;
		}
		Mob mob = brain.mob;
		for (BlockPos p : brain.unlitTnt) {
			if (!level.getBlockState(p).is(Blocks.TNT) || mob.distanceToSqr(Vec3.atCenterOf(p)) > 100.0) {
				continue;
			}
			mob.getLookControl().setLookAt(Vec3.atCenterOf(p));
			mob.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
			level.playSound(null, p, SoundEvents.FLINTANDSTEEL_USE, SoundSource.HOSTILE, 1.0F, 1.0F);
			if (TntBlock.prime(level, p, mob, new ItemStack(Items.FLINT_AND_STEEL))) {
				level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL_IMMEDIATE);
			}
		}
		brain.unlitTnt.clear();
	}

	// ------------------------------------------------------------------ skeletons

	private static void skeletonWall(SmartBrain brain, ServerLevel level, LivingEntity target, boolean griefing) {
		Mob mob = brain.mob;
		double dist = mob.distanceTo(target);
		if (!griefing || brain.wallPlan != null || brain.wallCooldown > 0 || brain.sniperPhase != 0 || dist < 7 || dist > 24 || !mob.onGround()) {
			return;
		}
		RandomSource r = mob.getRandom();
		brain.wallCooldown = 500 + r.nextInt(400);

		brain.planWall(level, target, 2, 1 + r.nextInt(3), 1 + r.nextInt(2), Blocks.COBBLESTONE.defaultBlockState(), true);
	}

	/**
	 * Sniper skeletons pillar up 5-7 blocks, build a platform with fence railings, and shoot from the top.
	 * Returns true while the skeleton is perched (it should not wander off).
	 */
	static boolean trySniper(SmartBrain brain, ServerLevel level, LivingEntity target) {
		Mob mob = brain.mob;
		if (!brain.sniper) {
			return false;
		}
		if (brain.sniperPhase == 3) {
			if (brain.sniperNest != null && mob.blockPosition().distManhattan(brain.sniperNest) <= 1) {
				brain.holdStill();
				return true;
			}
			// Knocked off the tower.
			brain.sniperPhase = 0;
			brain.sniperCooldown = 600;
		}
		if (brain.sniperPhase != 0 || brain.sniperCooldown > 0 || !mob.onGround()) {
			return false;
		}
		double dist = mob.distanceTo(target);
		if (dist < 10 || dist > 28 || !mob.hasLineOfSight(target) || brain.blocks < 6) {
			return false;
		}
		brain.sniperPhase = 1;
		brain.startTower(5 + mob.getRandom().nextInt(3));
		return true;
	}

	/** After the pillar is up: build the crow's nest. Returns true while busy. */
	static boolean tickSniperNest(SmartBrain brain, ServerLevel level) {
		Mob mob = brain.mob;
		if (brain.sniperPhase == 1) {
			if (!mob.onGround()) {
				return true;
			}
			BlockPos feet = mob.blockPosition();
			List<BlockPos> plan = new ArrayList<>();
			List<BlockState> states = new ArrayList<>();
			for (int pass = 0; pass < 2; pass++) {
				for (int dx = -1; dx <= 1; dx++) {
					for (int dz = -1; dz <= 1; dz++) {
						if (dx == 0 && dz == 0) {
							continue;
						}
						plan.add(feet.offset(dx, pass - 1, dz));
						states.add(pass == 0 ? Blocks.COBBLESTONE.defaultBlockState() : Blocks.OAK_FENCE.defaultBlockState());
					}
				}
			}
			brain.nestPlan = plan;
			brain.nestStates = states;
			brain.nestIndex = 0;
			brain.sniperNest = feet;
			brain.sniperPhase = 2;
		}
		if (brain.sniperPhase != 2) {
			return false;
		}
		brain.holdStill();
		if (mob.tickCount % 3 != 0) {
			return true;
		}
		brain.place(level, brain.nestPlan.get(brain.nestIndex), brain.nestStates.get(brain.nestIndex), false);
		if (++brain.nestIndex >= brain.nestPlan.size()) {
			brain.nestPlan = null;
			brain.sniperPhase = 3;
		}
		return true;
	}

	static boolean tickEnchanting(SmartBrain brain, ServerLevel level, boolean griefing) {
		Mob mob = brain.mob;
		if (brain.enchantTimer < 0 || brain.bowEnchanted) {
			return false;
		}
		ItemStack bow = mob.getMainHandItem();
		if (!bow.is(Items.BOW)) {
			brain.enchantTimer = -1;
			return false;
		}
		if (brain.enchantTimer > 0) {
			brain.enchantTimer--;
			return false;
		}

		if (brain.enchantTicks == 0) {
			brain.enchantTable = BlockWork.findNearest(level, mob.blockPosition(), 6, s -> s.is(Blocks.ENCHANTING_TABLE));
			if (brain.enchantTable == null && griefing) {
				brain.enchantTable = brain.placeAdjacent(level, Blocks.ENCHANTING_TABLE.defaultBlockState());
			}
		}
		Vec3 focus = brain.enchantTable != null ? Vec3.atCenterOf(brain.enchantTable) : mob.getEyePosition();
		if (brain.enchantTable != null && mob.distanceToSqr(focus) > 9.0) {
			mob.getNavigation().moveTo(focus.x, focus.y, focus.z, brain.walkSpeed());
			if (++brain.enchantTicks > 200) {
				brain.enchantTable = null;
			}
			return true;
		}
		mob.getNavigation().stop();
		mob.getLookControl().setLookAt(focus);
		brain.enchantTicks++;
		if (brain.enchantTicks % 10 == 0) {
			level.sendParticles(ParticleTypes.ENCHANT, focus.x, focus.y + 0.8, focus.z, 15, 0.6, 0.4, 0.6, 0.6);
		}
		if (brain.enchantTicks >= 60) {
			ItemStack enchanted = bow.copy();
			applyBowEnchantments(level, enchanted, mob.getRandom());
			mob.setItemSlot(EquipmentSlot.MAINHAND, enchanted);
			// The bow is dropped by our own death handler (sometimes at half durability).
			mob.setDropChance(EquipmentSlot.MAINHAND, 0.0F);
			level.playSound(null, mob.blockPosition(), SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.HOSTILE, 1.0F, 1.0F);
			brain.bowEnchanted = true;
			brain.enchantTicks = 0;
		}
		return true;
	}

	/** Skeletons place (or reuse) a crafting table and fletch better arrows. */
	static boolean tickFletching(SmartBrain brain, ServerLevel level, boolean griefing) {
		Mob mob = brain.mob;
		if (brain.fletchTimer < 0 || !(mob instanceof AbstractSkeleton)) {
			return false;
		}
		if (brain.fletchTimer > 0) {
			brain.fletchTimer--;
			return false;
		}
		if (brain.fletchTicks == 0) {
			brain.fletchStation = BlockWork.findNearest(level, mob.blockPosition(), 6, s -> s.is(Blocks.CRAFTING_TABLE));
			if (brain.fletchStation == null && griefing) {
				brain.fletchStation = brain.placeAdjacent(level, Blocks.CRAFTING_TABLE.defaultBlockState());
			}
		}
		Vec3 focus = brain.fletchStation != null ? Vec3.atCenterOf(brain.fletchStation) : mob.getEyePosition();
		if (brain.fletchStation != null && mob.distanceToSqr(focus) > 9.0) {
			mob.getNavigation().moveTo(focus.x, focus.y, focus.z, brain.walkSpeed());
			if (++brain.fletchTicks > 200) {
				brain.fletchStation = null;
			}
			return true;
		}
		mob.getNavigation().stop();
		mob.getLookControl().setLookAt(focus);
		brain.fletchTicks++;
		if (brain.fletchTicks % 8 == 0) {
			mob.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
			level.playSound(null, mob.blockPosition(), SoundEvents.WOOD_HIT, SoundSource.HOSTILE, 0.8F, 1.0F);
		}
		if (brain.fletchTicks >= 50) {
			brain.fletched = true;
			brain.fletchTimer = -1;
			brain.fletchTicks = 0;
			level.playSound(null, mob.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.HOSTILE, 0.8F, 1.0F);
		}
		return true;
	}

	private static void applyBowEnchantments(ServerLevel level, ItemStack bow, RandomSource r) {
		HolderLookup.RegistryLookup<Enchantment> lookup = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
		bow.enchant(lookup.getOrThrow(Enchantments.POWER), 1 + r.nextInt(5));
		if (r.nextFloat() < 0.5F) {
			bow.enchant(lookup.getOrThrow(Enchantments.PUNCH), 1 + r.nextInt(2));
		}
		if (r.nextFloat() < 0.4F) {
			bow.enchant(lookup.getOrThrow(Enchantments.FLAME), 1);
		}
		if (r.nextFloat() < 0.5F) {
			bow.enchant(lookup.getOrThrow(Enchantments.UNBREAKING), 1 + r.nextInt(3));
		}
	}

	// ------------------------------------------------------------------ villagers

	private static void villagerAttack(SmartBrain brain, ServerLevel level, Villager villager, LivingEntity target) {
		if (villager.tickCount % 20 == 0) {
			villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(target, 0.5F, 3));
			villager.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, new EntityTracker(target, true));
		}
		if (brain.punchCooldown == 0 && villager.distanceTo(target) < 1.8F) {
			brain.punchCooldown = 25;
			villager.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
			target.hurtServer(level, villager.damageSources().mobAttack(villager), 1.5F);
		}
	}
}
