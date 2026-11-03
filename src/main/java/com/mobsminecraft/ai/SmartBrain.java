package com.mobsminecraft.ai;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import com.mobsminecraft.world.MobBuilt;
import net.minecraft.world.level.block.AbstractBedBlock;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Per-mob controller that gives every mob the ability to mine, craft and build.
 * Ticked from {@link com.mobsminecraft.mixin.MobMixin} on the server.
 */
public final class SmartBrain {
	private static final int MAX_STOCK = 128;

	final Mob mob;
	private boolean initialized;

	// Building stock (planks, cobblestone, dirt)
	int blocks;
	private Block primaryBlock = Blocks.COBBLESTONE;
	private Block houseWall = Blocks.OAK_PLANKS;
	private int regenTicks;

	// Consumable
	final List<Consumable.Carried> carried = new ArrayList<>();
	int consumableCooldown;

	// Mining
	private BlockPos miningPos;
	private float miningProgress;
	private boolean miningAbsorb;

	// Movement tricks
	private int towerRemaining;
	private BlockPos towerFeet;
	private int towerJumpTicks;
	private int digRemaining;
	private boolean digRoofAfter;
	private boolean roofPending;
	private int escapeCooldown;
	private int pathCheckCooldown;
	boolean targetReachable = true;
	private int assistTicks;
	private Vec3 lastPos = Vec3.ZERO;
	private int stuckTicks;

	// Retreating behind a wall, and cover walls against ranged attackers
	private int fleeTicks;
	private Vec3 fleeGoal;
	private LivingEntity fleeFrom;
	private int coverCooldown;

	// Tool crafting
	private boolean toolCrafter;
	private int toolCraftTimer = -1;
	private int craftingTicks;
	private BlockPos craftStation;

	// Group decoration
	private int decorCooldown;

	// House building
	private boolean houseBuilder;
	private boolean houseBuilt;
	List<BlockPos> housePlan;
	List<BlockState> houseStates;
	int houseIndex;
	BlockPos houseCenter;
	int buildInterval = 5;
	private int buildTick;

	// Juggernaut castle
	private boolean castleBuilder;
	private BlockPos castleCenter;

	// Mining: stealing ores (dropped) and gathering building blocks (kept)
	private int oreCooldown;
	private int gatherCooldown;
	private BlockPos digTarget;
	private boolean digTargetDrops;
	private int digTimeout;

	// Gardening (see Gardening)
	boolean gardener;
	int gardenCooldown;
	BlockPos gardenPos;
	int gardenTicks;
	int treeTrapCooldown;

	// Mob-specific state (see MobSpecials)
	int specialCooldown;
	boolean bowEnchanted;
	int enchantTimer = -1;
	int enchantTicks;
	BlockPos enchantTable;
	List<BlockPos> wallPlan;
	BlockState wallState;
	int wallIndex;
	int wallCooldown;
	boolean sniper;
	int sniperPhase;
	BlockPos sniperNest;
	List<BlockPos> nestPlan;
	List<BlockState> nestStates;
	int nestIndex;
	int sniperCooldown;
	int punchCooldown;
	int fletchTimer = -1;
	int fletchTicks;
	BlockPos fletchStation;
	boolean fletched;

	// Tripwire traps (see Traps)
	boolean trapBuilder;
	int trapCooldown;
	List<BlockPos> trapPlan;
	List<BlockState> trapStates;
	int trapIndex;

	// Inventory: picked-up items (see Inventory)
	final List<ItemStack> loot = new ArrayList<>();
	int pickupBoostTicks;

	// Underground mining outposts (see Outpost) and cooking (see Furnaces)
	boolean outpostBuilder;
	boolean buildingOutpost;
	int outpostCooldown;
	int cookCooldown;
	BlockPos cookPos;
	int cookTicks;

	// Pickaxe zombies and skeletons carry for mining; their usual item is stashed while it's out
	ItemStack pickaxe = ItemStack.EMPTY;
	private ItemStack stashedHand;

	// Passive mobs build houses out of the wood they chop
	int logsChopped;
	Block woodLog = Blocks.OAK_LOG;
	int houseCooldown;

	// Creeper TNT waiting to be lit (see MobSpecials)
	final List<BlockPos> unlitTnt = new ArrayList<>();
	int tntLightTicks;

	public SmartBrain(Mob mob) {
		this.mob = mob;
	}

	/** Movement speed modifier for everything mobs walk to on their own. */
	double walkSpeed() {
		return 1.0;
	}

	/** Passive mobs: anything that isn't a monster (animals, villagers, golems...). */
	boolean isPassive() {
		return !(this.mob instanceof Enemy);
	}

	Block primaryBlock() {
		return this.primaryBlock;
	}

	public void dropInventory(ServerLevel level) {
		Inventory.dropAll(this, level);
	}

	public boolean isBowEnchanted() {
		return this.bowEnchanted;
	}

	/** Skeletons that worked at a crafting table shoot enchanted arrows more often. */
	public boolean isFletched() {
		return this.fletched;
	}

	private void init() {
		this.initialized = true;
		RandomSource r = this.mob.getRandom();
		this.primaryBlock = switch (r.nextInt(3)) {
			case 0 -> Blocks.OAK_PLANKS;
			case 1 -> Blocks.COBBLESTONE;
			default -> Blocks.DIRT;
		};
		this.blocks = 24 + r.nextInt(41);
		this.decorCooldown = 300 + r.nextInt(1200);
		this.oreCooldown = 100 + r.nextInt(300);
		this.gatherCooldown = 100 + r.nextInt(300);
		this.houseBuilder = BlockWork.canBuild(this.mob) && r.nextFloat() < (this.isPassive() ? 0.3F : 0.08F);
		this.outpostBuilder = this.mob instanceof Enemy && BlockWork.canBuild(this.mob) && r.nextFloat() < 0.3F;
		this.outpostCooldown = 200 + r.nextInt(600);
		this.cookCooldown = 400 + r.nextInt(1200);
		if (this.isPassive()) {
			this.gatherCooldown = 60 + r.nextInt(200);
		}
		// Everything a mob holds or wears drops when it dies, and every mob can pick up gear.
		for (EquipmentSlot slot : EquipmentSlot.VALUES) {
			this.mob.setDropChance(slot, 2.0F);
		}
		boolean vanillaPickup = this.mob instanceof Villager || this.mob instanceof net.minecraft.world.entity.monster.piglin.AbstractPiglin;
		this.mob.setCanPickUpLoot(vanillaPickup);
		this.gardener = BlockWork.canBuild(this.mob) && r.nextFloat() < (this.mob instanceof Villager ? 0.25F : 0.08F);
		this.gardenCooldown = 200 + r.nextInt(600);
		this.toolCrafter = BlockWork.isToolHolder(this.mob) && r.nextFloat() < 0.7F;
		if (this.toolCrafter) {
			this.toolCraftTimer = 100 + r.nextInt(500);
		}
		this.trapBuilder = this.mob instanceof Enemy && BlockWork.canBuild(this.mob) && r.nextFloat() < 0.3F;
		Juggernaut.roll(this.mob, r);
		if ((this.mob instanceof net.minecraft.world.entity.monster.zombie.Zombie
			|| this.mob instanceof net.minecraft.world.entity.monster.skeleton.AbstractSkeleton) && r.nextFloat() < 0.75F) {
			float t = r.nextFloat();
			this.pickaxe = new ItemStack(t < 0.1F ? Items.WOODEN_PICKAXE : t < 0.6F ? Items.STONE_PICKAXE : t < 0.95F ? Items.IRON_PICKAXE : Items.DIAMOND_PICKAXE);
		}
		if (Juggernaut.isJuggernaut(this.mob) && r.nextFloat() < 0.6F) {
			this.castleBuilder = true;
			this.houseBuilder = false;
		}
		Consumable.assign(this, r);
		MobSpecials.init(this, r);
	}

	public void tick() {
		if (!(this.mob.level() instanceof ServerLevel level)) {
			return;
		}
		if (!this.initialized) {
			this.init();
		}
		this.tickCooldowns();
		Inventory.tickPickup(this, level);
		if (this.miningPos == null && this.stashedHand != null) {
			this.restoreHand();
		}
		MobSpecials.tickUnlitTnt(this, level);

		boolean griefing = level.getGameRules().get(GameRules.MOB_GRIEFING);
		LivingEntity target = this.resolveTarget(level);
		boolean canBuild = griefing && BlockWork.canBuild(this.mob) && !this.mob.isPassenger();

		if (target != null) {
			Consumable.tryUse(this, level, target, griefing);
			MobSpecials.combat(this, level, target, griefing);
		}

		boolean freeTime = target == null || this.mob.distanceTo(target) > 12.0F;
		if (freeTime && this.miningPos == null && this.towerRemaining == 0 && this.digRemaining == 0) {
			if (this.tickToolCrafting(level, griefing)) {
				return;
			}
			if (MobSpecials.tickEnchanting(this, level, griefing)) {
				return;
			}
			if (MobSpecials.tickFletching(this, level, griefing)) {
				return;
			}
		}

		if (!canBuild) {
			this.cancelMining(level);
			return;
		}

		if (this.miningPos != null) {
			this.tickMining(level);
			return;
		}
		if (this.roofPending && this.mob.onGround()) {
			this.roofPending = false;
			this.place(level, this.mob.blockPosition().above(this.height()), this.buildState(), true);
		}
		if (this.towerRemaining > 0) {
			this.tickTower(level);
			return;
		}
		if (MobSpecials.tickSniperNest(this, level)) {
			return;
		}
		if (this.digRemaining > 0) {
			this.tickDig(level);
			return;
		}
		if (this.fleeTicks > 0) {
			this.tickFlee(level);
			return;
		}
		if (this.wallPlan != null) {
			this.tickWall(level);
		}
		if (this.gardenPos != null) {
			Gardening.tick(this, level);
			return;
		}

		if (target != null) {
			float hp = this.mob.getHealth() / this.mob.getMaxHealth();
			if (hp < 0.3F && this.escapeCooldown == 0) {
				this.startEscape(target);
				return;
			}
			if (MobSpecials.trySniper(this, level, target)) {
				return;
			}
			if (this.gardener && Gardening.tryTreeTrap(this, level, target)) {
				return;
			}
			this.tryCoverWall(level, target);
			if (this.mob.distanceTo(target) > 16.0F && !this.pickaxe.isEmpty() && this.tickDigTarget(level)) {
				return;
			}
			if (this.isChaser(target)) {
				this.chaseAssist(level, target);
			}
			return;
		}

		if (this.tickCastleGuard()) {
			return;
		}
		if (Traps.tick(this, level)) {
			return;
		}
		if (this.housePlan == null && this.outpostBuilder && this.outpostCooldown == 0 && this.mob.onGround()
			&& Outpost.isUnderground(level, this.mob) && this.mob.getRandom().nextInt(100) == 0) {
			this.outpostCooldown = 600;
			Outpost.plan(this, level);
		}
		if (this.housePlan == null && this.isPassive() && this.logsChopped >= 5 && this.houseCooldown == 0
			&& this.mob.onGround() && this.mob.getRandom().nextInt(40) == 0) {
			if (Houses.plan(this, level, Houses.forLog(this.woodLog))) {
				this.logsChopped = 0;
			} else {
				this.houseCooldown = 200;
			}
		}
		if (this.tickBlueprint(level)) {
			return;
		}
		if (this.isPassive() && Furnaces.tickCooking(this, level, griefing)) {
			return;
		}
		if (this.gardener && Gardening.tryPlant(this, level)) {
			return;
		}
		if (this.tickDigTarget(level)) {
			return;
		}
		this.tickDecor(level);
	}

	private void tickCooldowns() {
		if (this.consumableCooldown > 0) this.consumableCooldown--;
		if (this.escapeCooldown > 0) this.escapeCooldown--;
		if (this.decorCooldown > 0) this.decorCooldown--;
		if (this.oreCooldown > 0) this.oreCooldown--;
		if (this.specialCooldown > 0) this.specialCooldown--;
		if (this.wallCooldown > 0) this.wallCooldown--;
		if (this.punchCooldown > 0) this.punchCooldown--;
		if (this.trapCooldown > 0) this.trapCooldown--;
		if (this.pickupBoostTicks > 0) this.pickupBoostTicks--;
		if (this.outpostCooldown > 0) this.outpostCooldown--;
		if (this.cookCooldown > 0) this.cookCooldown--;
		if (this.houseCooldown > 0) this.houseCooldown--;
		if (this.assistTicks > 0) this.assistTicks--;
		if (this.coverCooldown > 0) this.coverCooldown--;
		if (this.gatherCooldown > 0) this.gatherCooldown--;
		if (this.gardenCooldown > 0) this.gardenCooldown--;
		if (this.treeTrapCooldown > 0) this.treeTrapCooldown--;
		if (this.sniperCooldown > 0) this.sniperCooldown--;
		if (++this.regenTicks >= 200) {
			this.regenTicks = 0;
			if (this.blocks < 64) this.blocks++;
		}
	}

	// ------------------------------------------------------------------ targeting

	static boolean isValidTarget(LivingEntity e) {
		return e instanceof Player player && player.isAlive() && !player.isCreative() && !player.isSpectator();
	}

	private LivingEntity resolveTarget(ServerLevel level) {
		LivingEntity t = this.mob.getTarget();
		if (isValidTarget(t)) {
			return t;
		}
		if (this.mob instanceof Villager) {
			Player p = level.getNearestPlayer(this.mob.getX(), this.mob.getY(), this.mob.getZ(), 16.0,
				EntitySelector.NO_CREATIVE_OR_SPECTATOR.and(e -> e.isAlive()));
			if (p != null) {
				return p;
			}
		}
		LivingEntity attacker = this.mob.getLastHurtByMob();
		if (isValidTarget(attacker) && this.mob.tickCount - this.mob.getLastHurtByMobTimestamp() < 300) {
			return attacker;
		}
		return null;
	}

	/** Mobs that actively hunt the target, as opposed to passive mobs defending themselves. */
	private boolean isChaser(LivingEntity target) {
		if (this.mob instanceof Villager) {
			return true;
		}
		if (this.mob.getTarget() != target || !(this.mob instanceof Enemy || this.mob instanceof NeutralMob)) {
			return false;
		}
		if (this.mob instanceof RangedAttackMob) {
			return this.mob.distanceTo(target) > 16.0F || !this.mob.hasLineOfSight(target);
		}
		return true;
	}

	// ------------------------------------------------------------------ chasing: bridge, tunnel, tower, dig

	private void chaseAssist(ServerLevel level, LivingEntity target) {
		if (--this.pathCheckCooldown <= 0) {
			this.pathCheckCooldown = 20;
			Path path = this.mob.getNavigation().createPath(target, 1);
			this.targetReachable = path != null && path.canReach();
		}

		Vec3 pos = this.mob.position();
		if (pos.distanceToSqr(this.lastPos) < 0.01) {
			this.stuckTicks++;
		} else {
			this.stuckTicks = 0;
		}
		this.lastPos = pos;

		if (!this.targetReachable && (this.stuckTicks > 10 || this.mob.getNavigation().isDone())) {
			this.assistTicks = 80;
		}
		if (this.assistTicks <= 0) {
			return;
		}
		if (!this.mob.onGround() && !this.mob.isInWater()) {
			return;
		}

		this.mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
		double dx = target.getX() - this.mob.getX();
		double dz = target.getZ() - this.mob.getZ();
		double dy = target.getY() - this.mob.getY();
		double horiz = Math.sqrt(dx * dx + dz * dz);

		if (dy > 1.5 && horiz < 2.5) {
			this.startTower(Mth.ceil(dy));
			return;
		}
		if (dy < -2.5 && horiz < 1.5) {
			this.startDig(1, false);
			return;
		}
		if (horiz < 1.0) {
			return;
		}
		if (!this.stepToward(level, target.position())) {
			this.assistTicks = 0;
		}
	}

	/**
	 * Walks one block straight toward the goal, mining through anything in the way and bridging gaps.
	 * Returns false if the way is blocked by something unbreakable.
	 */
	private boolean stepToward(ServerLevel level, Vec3 goal) {
		double dx = goal.x - this.mob.getX();
		double dz = goal.z - this.mob.getZ();
		double dy = goal.y - this.mob.getY();
		Direction dir = Direction.getApproximateNearest(dx, 0.0, dz);
		BlockPos feet = this.mob.blockPosition();
		BlockPos next = feet.relative(dir);
		int height = this.height();

		BlockPos obstruction = null;
		for (int i = 0; i < height; i++) {
			BlockPos p = next.above(i);
			if (BlockWork.blocksMovement(level, p)) {
				obstruction = p;
				break;
			}
		}

		double wantY = next.getY();
		if (obstruction != null) {
			boolean stepUp = obstruction.equals(next)
				&& dy >= 0.0
				&& !BlockWork.blocksMovement(level, next.above(height))
				&& !BlockWork.blocksMovement(level, feet.above(height));
			for (int i = 1; stepUp && i < height; i++) {
				stepUp = !BlockWork.blocksMovement(level, next.above(i));
			}
			if (stepUp) {
				wantY = next.getY() + 1;
			} else {
				if (!BlockWork.canMine(this.mob, level, obstruction, level.getBlockState(obstruction))) {
					return false;
				}
				this.startMining(obstruction, true);
				return true;
			}
		} else if (!BlockWork.isFloor(level, next.below())) {
			int drop = 0;
			while (drop < 5 && !BlockWork.isFloor(level, next.below(drop + 1))) {
				drop++;
			}
			boolean safeDrop = dy < -1.0 && drop <= 3;
			if (!safeDrop && !this.place(level, next.below(), this.buildState(), true)) {
				return this.blocks > 0;
			}
		}

		this.mob.getNavigation().stop();
		this.mob.getMoveControl().setWantedPosition(next.getX() + 0.5, wantY, next.getZ() + 0.5, this.walkSpeed());
		return true;
	}

	private int height() {
		return Math.max(1, Mth.ceil(this.mob.getBbHeight()));
	}

	void holdStill() {
		this.mob.getNavigation().stop();
		this.mob.getMoveControl().setWantedPosition(this.mob.getX(), this.mob.getY(), this.mob.getZ(), 0.0);
	}

	/** Low on health: tower up, dig in, or run away and wall itself off from the attacker. */
	private void startEscape(LivingEntity target) {
		RandomSource r = this.mob.getRandom();
		this.escapeCooldown = 400 + r.nextInt(200);
		float roll = r.nextFloat();
		if (roll < 0.4F) {
			this.startTower(3 + r.nextInt(3));
		} else if (roll < 0.65F) {
			this.startDig(3, true);
		} else {
			Vec3 away = this.mob.position().subtract(target.position()).multiply(1.0, 0.0, 1.0);
			if (away.lengthSqr() < 1.0E-4) {
				away = new Vec3(1.0, 0.0, 0.0);
			}
			this.fleeGoal = this.mob.position().add(away.normalize().scale(8.0));
			this.fleeFrom = target;
			this.fleeTicks = 60;
		}
	}

	private void tickFlee(ServerLevel level) {
		this.fleeTicks--;
		if (this.mob.tickCount % 10 == 0) {
			this.mob.getNavigation().moveTo(this.fleeGoal.x, this.fleeGoal.y, this.fleeGoal.z, this.walkSpeed() * 1.3);
		}
		if (this.mob.position().distanceToSqr(this.fleeGoal) < 4.0) {
			this.fleeTicks = 0;
		}
		if (this.fleeTicks == 0) {
			if (this.fleeFrom != null && this.fleeFrom.isAlive() && this.mob.onGround()) {
				this.holdStill();
				this.planWall(level, this.fleeFrom, 1, 1 + this.mob.getRandom().nextInt(2), 2 + this.mob.getRandom().nextInt(2), this.buildState(), false);
			}
			this.fleeFrom = null;
		}
	}

	/** Ranged attackers make mobs block line of sight with a cover wall. */
	private void tryCoverWall(ServerLevel level, LivingEntity target) {
		if (this.wallPlan != null || this.coverCooldown > 0 || !this.mob.onGround()) {
			return;
		}
		this.coverCooldown = 60;
		double dist = this.mob.distanceTo(target);
		boolean ranged = isRangedWeapon(target.getMainHandItem()) || isRangedWeapon(target.getOffhandItem());
		boolean passive = !(this.mob instanceof Enemy);
		boolean wants = ranged ? dist > 10 && dist < 32 : passive && dist > 4 && dist < 12;
		RandomSource r = this.mob.getRandom();
		if (!wants || !this.mob.hasLineOfSight(target) || r.nextFloat() > 0.35F) {
			return;
		}
		this.coverCooldown = 300 + r.nextInt(300);
		this.planWall(level, target, 2, 1 + r.nextInt(2), 2 + r.nextInt(2), this.buildState(), false);
	}

	private static boolean isRangedWeapon(ItemStack stack) {
		return stack.is(Items.BOW) || stack.is(Items.CROSSBOW) || stack.is(Items.TRIDENT);
	}

	/** Plans a wall {@code distance} blocks toward the target, {@code 2*halfWidth+1} wide. */
	void planWall(ServerLevel level, LivingEntity target, int distance, int halfWidth, int height, BlockState state, boolean posts) {
		Vec3 d = target.position().subtract(this.mob.position());
		Direction facing = Direction.getApproximateNearest(d.x, 0.0, d.z);
		Direction side = facing.getClockWise();
		BlockPos center = this.mob.blockPosition().relative(facing, distance);
		List<BlockPos> bases = new ArrayList<>();
		for (int i = -halfWidth; i <= halfWidth; i++) {
			BlockPos col = center.relative(side, i);
			if (BlockWork.blocksMovement(level, col)) {
				col = col.above();
			} else if (!BlockWork.isFloor(level, col.below())) {
				col = col.below();
			}
			bases.add(col);
		}
		List<BlockPos> plan = new ArrayList<>();
		for (int y = 0; y < height; y++) {
			for (BlockPos base : bases) {
				plan.add(base.above(y));
			}
		}
		if (posts) {
			plan.add(bases.get(0).above(height));
			plan.add(bases.get(bases.size() - 1).above(height));
		}
		this.wallPlan = plan;
		this.wallState = state;
		this.wallIndex = 0;
	}

	private void tickWall(ServerLevel level) {
		if (this.wallIndex >= this.wallPlan.size() || this.blocks <= 0) {
			this.wallPlan = null;
			return;
		}
		if (this.mob.tickCount % 4 != 0) {
			return;
		}
		BlockPos p = this.wallPlan.get(this.wallIndex++);
		if (this.mob.distanceToSqr(Vec3.atCenterOf(p)) > 36.0) {
			this.wallPlan = null;
			return;
		}
		this.place(level, p, this.wallState, true);
	}

	void startTower(int count) {
		this.towerRemaining = Math.min(count, 8);
		this.towerFeet = null;
	}

	private void tickTower(ServerLevel level) {
		this.holdStill();
		if (this.blocks <= 0) {
			this.towerRemaining = 0;
			return;
		}
		if (this.towerFeet == null) {
			if (!this.mob.onGround() && !this.mob.isInWater()) {
				return;
			}
			BlockPos feet = this.mob.blockPosition();
			BlockPos ceiling = feet.above(this.height());
			if (BlockWork.blocksMovement(level, ceiling)) {
				if (BlockWork.canMine(this.mob, level, ceiling, level.getBlockState(ceiling))) {
					this.startMining(ceiling, true);
				} else {
					this.towerRemaining = 0;
				}
				return;
			}
			if (!level.getBlockState(feet).canBeReplaced()) {
				this.towerRemaining = 0;
				return;
			}
			this.mob.setPos(feet.getX() + 0.5, this.mob.getY(), feet.getZ() + 0.5);
			this.mob.setDeltaMovement(0.0, 0.45, 0.0);
			this.towerFeet = feet;
			this.towerJumpTicks = 0;
		} else {
			this.towerJumpTicks++;
			if (this.mob.getY() >= this.towerFeet.getY() + 1.0 && this.place(level, this.towerFeet, this.buildState(), true)) {
				this.towerRemaining--;
				this.towerFeet = null;
			} else if (this.towerJumpTicks > 15) {
				this.towerFeet = null;
				this.towerRemaining--;
			}
		}
	}

	private void startDig(int count, boolean roof) {
		this.digRemaining = count;
		this.roofPending = false;
		// The roof is placed once the last block is dug out and the mob has landed.
		this.digRoofAfter = roof;
	}


	private void tickDig(ServerLevel level) {
		this.holdStill();
		if (!this.mob.onGround()) {
			return;
		}
		BlockPos below = this.mob.blockPosition().below();
		BlockState state = level.getBlockState(below);
		boolean dangerBelow = !level.getFluidState(below.below()).isEmpty();
		if (dangerBelow || !BlockWork.canMine(this.mob, level, below, state)) {
			this.digRemaining = 0;
		} else {
			this.mob.setPos(below.getX() + 0.5, this.mob.getY(), below.getZ() + 0.5);
			this.startMining(below, true);
			this.digRemaining--;
		}
		if (this.digRemaining == 0 && this.digRoofAfter) {
			this.digRoofAfter = false;
			this.roofPending = true;
		}
	}

	// ------------------------------------------------------------------ mining & placing

	void startMining(BlockPos pos, boolean absorb) {
		this.miningPos = pos.immutable();
		this.miningProgress = 0.0F;
		this.miningAbsorb = absorb;
		this.mob.getNavigation().stop();
		this.takeOutPickaxeFor(this.mob.level().getBlockState(pos));
	}

	private void cancelMining(ServerLevel level) {
		if (this.miningPos != null) {
			level.destroyBlockProgress(this.mob.getId(), this.miningPos, -1);
			this.miningPos = null;
		}
		this.restoreHand();
	}

	/** Zombies and skeletons visibly switch to their pickaxe when the block needs it. */
	private void takeOutPickaxeFor(BlockState state) {
		if (this.pickaxe.isEmpty() || this.stashedHand != null) {
			return;
		}
		if (BlockWork.tool(this.mob, state) != this.pickaxe) {
			return;
		}
		this.stashedHand = this.mob.getMainHandItem().copy();
		this.mob.setItemSlot(EquipmentSlot.MAINHAND, this.pickaxe);
	}

	/** Puts the pickaxe away and gives the mob back its usual item. */
	public void restoreHand() {
		if (this.stashedHand == null) {
			return;
		}
		this.mob.setItemSlot(EquipmentSlot.MAINHAND, this.stashedHand);
		this.stashedHand = null;
	}

	private void tickMining(ServerLevel level) {
		BlockState state = level.getBlockState(this.miningPos);
		Vec3 center = Vec3.atCenterOf(this.miningPos);
		if (!BlockWork.canMine(this.mob, level, this.miningPos, state) || this.mob.distanceToSqr(center) > 30.0) {
			this.cancelMining(level);
			return;
		}
		this.holdStill();
		this.mob.getLookControl().setLookAt(center);
		this.miningProgress += BlockWork.progressPerTick(this.mob, level, this.miningPos, state);
		if (this.mob.tickCount % 5 == 0) {
			this.mob.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
			level.playSound(null, this.miningPos, state.getSoundType().getHitSound(), SoundSource.HOSTILE, 0.5F, 0.75F);
		}
		if (this.miningProgress >= 1.0F) {
			level.destroyBlockProgress(this.mob.getId(), this.miningPos, -1);
			boolean absorb = this.miningAbsorb && BlockWork.isBuildingMaterial(state);
			level.destroyBlock(this.miningPos, !absorb, this.mob);
			if (absorb) {
				this.blocks = Math.min(this.blocks + (state.is(BlockTags.LOGS) ? 4 : 1), MAX_STOCK);
			} else {
				// Go pick up what dropped (ores, etc.).
				this.pickupBoostTicks = 60;
			}
			BlockPos broken = this.miningPos;
			this.miningPos = null;
			this.restoreHand();
			if (state.is(BlockTags.LOGS)) {
				this.logsChopped++;
				this.woodLog = state.getBlock();
				// Keep chopping the rest of the tree.
				BlockPos nextLog = BlockWork.findNearestAt(level, broken, 2, (p, st) -> st.is(BlockTags.LOGS) && BlockWork.isNatural(level, p, st) && BlockWork.canMine(this.mob, level, p, st));
				if (nextLog != null) {
					this.setDigTarget(nextLog, false);
				}
			}
		} else {
			level.destroyBlockProgress(this.mob.getId(), this.miningPos, (int) (this.miningProgress * 10.0F));
		}
	}

	/** Each mob builds with one block type, so bridges, towers and walls look consistent. */
	BlockState buildState() {
		return this.primaryBlock.defaultBlockState();
	}

	/** Places a block if the spot is free. Uses one block from the mob's stock when {@code useStock}. */
	boolean place(ServerLevel level, BlockPos pos, BlockState state, boolean useStock) {
		BlockState current = level.getBlockState(pos);
		if (!current.canBeReplaced() || !level.isUnobstructed(state, pos, CollisionContext.empty())) {
			return false;
		}
		// Mobs never run out of building blocks; the stock only decides how many they drop.
		level.setBlock(pos, Block.updateFromNeighbourShapes(state, level, pos), Block.UPDATE_ALL);
		MobBuilt.mark(level, pos);
		level.playSound(null, pos, state.getSoundType().getPlaceSound(), SoundSource.HOSTILE, 1.0F, 0.8F);
		if (state.getBlock() instanceof AbstractFurnaceBlock) {
			Furnaces.load(level, pos, this.mob.getRandom());
		}
		this.mob.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
		return true;
	}

	/** Places a utility block (crafting table, furnace, enchanting table) next to the mob. */
	BlockPos placeAdjacent(ServerLevel level, BlockState state) {
		BlockPos spot = BlockWork.findAdjacentSpot(level, this.mob);
		if (spot != null && this.place(level, spot, state, false)) {
			return spot;
		}
		return null;
	}

	// ------------------------------------------------------------------ tool crafting

	private boolean tickToolCrafting(ServerLevel level, boolean griefing) {
		if (!this.toolCrafter) {
			return false;
		}
		if (!this.mob.getMainHandItem().isEmpty()) {
			this.toolCraftTimer = -1;
			this.craftingTicks = 0;
			return false;
		}
		if (this.toolCraftTimer < 0) {
			// Lost or never had a tool: plan to craft one.
			this.toolCraftTimer = 200 + this.mob.getRandom().nextInt(400);
			return false;
		}
		if (this.toolCraftTimer > 0) {
			this.toolCraftTimer--;
			return false;
		}

		if (this.craftingTicks == 0) {
			this.craftStation = BlockWork.findNearest(level, this.mob.blockPosition(), 10, s -> s.is(Blocks.CRAFTING_TABLE));
			if (this.craftStation == null && griefing) {
				this.craftStation = this.placeAdjacent(level, Blocks.CRAFTING_TABLE.defaultBlockState());
			}
		}
		if (this.craftStation != null) {
			Vec3 c = Vec3.atCenterOf(this.craftStation);
			if (this.mob.distanceToSqr(c) > 9.0) {
				this.mob.getNavigation().moveTo(c.x, c.y, c.z, this.walkSpeed());
				if (++this.craftingTicks > 200) {
					this.craftStation = null;
				}
				return true;
			}
			this.mob.getLookControl().setLookAt(c);
		}
		this.holdStill();
		this.craftingTicks++;
		if (this.craftingTicks % 8 == 0) {
			this.mob.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
			level.playSound(null, this.mob.blockPosition(), SoundEvents.WOOD_HIT, SoundSource.HOSTILE, 0.8F, 1.0F);
		}
		if (this.craftingTicks >= 50) {
			this.mob.setItemSlot(EquipmentSlot.MAINHAND, randomTool(this.mob.getRandom()));
			this.mob.setDropChance(EquipmentSlot.MAINHAND, 2.0F);
			level.playSound(null, this.mob.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.HOSTILE, 0.8F, 1.0F);
			this.craftingTicks = 0;
			this.toolCraftTimer = -1;
		}
		return true;
	}

	private static ItemStack randomTool(RandomSource r) {
		int tier = r.nextInt(100);
		int roll = r.nextInt(4);
		int kind = roll >= 2 ? 1 : roll; // 50% pickaxe, 25% shovel, 25% axe
		if (tier < 30) {
			return new ItemStack(kind == 0 ? Items.WOODEN_SHOVEL : kind == 1 ? Items.WOODEN_PICKAXE : Items.WOODEN_AXE);
		} else if (tier < 60) {
			return new ItemStack(kind == 0 ? Items.STONE_SHOVEL : kind == 1 ? Items.STONE_PICKAXE : Items.STONE_AXE);
		} else if (tier < 72) {
			return new ItemStack(kind == 0 ? Items.COPPER_SHOVEL : kind == 1 ? Items.COPPER_PICKAXE : Items.COPPER_AXE);
		} else if (tier < 90) {
			return new ItemStack(kind == 0 ? Items.IRON_SHOVEL : kind == 1 ? Items.IRON_PICKAXE : Items.IRON_AXE);
		} else if (tier < 97) {
			return new ItemStack(kind == 0 ? Items.GOLDEN_SHOVEL : kind == 1 ? Items.GOLDEN_PICKAXE : Items.GOLDEN_AXE);
		}
		return new ItemStack(kind == 0 ? Items.DIAMOND_SHOVEL : kind == 1 ? Items.DIAMOND_PICKAXE : Items.DIAMOND_AXE);
	}

	// ------------------------------------------------------------------ group decoration

	private void tickDecor(ServerLevel level) {
		if (this.decorCooldown > 0 || !this.mob.onGround()) {
			return;
		}
		RandomSource r = this.mob.getRandom();
		this.decorCooldown = 600 + r.nextInt(1200);
		List<? extends Mob> group = level.getEntitiesOfClass(this.mob.getClass(), this.mob.getBoundingBox().inflate(8.0));
		if (group.size() < 2 && r.nextFloat() > 0.3F) {
			return;
		}
		BlockPos here = this.mob.blockPosition();
		boolean hasTable = BlockWork.findNearest(level, here, 10, s -> s.is(Blocks.CRAFTING_TABLE)) != null;
		boolean hasFurnace = BlockWork.findNearest(level, here, 10, s -> s.getBlock() instanceof AbstractFurnaceBlock) != null;
		if (!hasTable) {
			this.placeAdjacent(level, Blocks.CRAFTING_TABLE.defaultBlockState());
		} else if (!hasFurnace && r.nextBoolean()) {
			BlockState furnace = Blocks.FURNACE.defaultBlockState()
				.setValue(AbstractFurnaceBlock.FACING, this.mob.getDirection().getOpposite())
				.setValue(AbstractFurnaceBlock.LIT, r.nextBoolean());
			this.placeAdjacent(level, furnace);
		}
	}

	// ------------------------------------------------------------------ makeshift houses

	/** Juggernauts stay near the castle they built. */
	private boolean tickCastleGuard() {
		if (this.castleCenter == null) {
			return false;
		}
		Vec3 c = Vec3.atBottomCenterOf(this.castleCenter);
		if (this.mob.distanceToSqr(c) <= 25.0) {
			return false;
		}
		if (this.mob.tickCount % 40 == 0) {
			this.mob.getNavigation().moveTo(c.x, c.y, c.z, this.walkSpeed());
		}
		return true;
	}

	/** Builds a makeshift house, or a castle for Juggernauts, one block at a time. */
	private boolean tickBlueprint(ServerLevel level) {
		if (this.housePlan == null) {
			if (!(this.houseBuilder || this.castleBuilder) || this.houseBuilt) {
				return false;
			}
			if (this.mob.tickCount < 200 || !this.mob.onGround() || this.mob.getRandom().nextInt(this.castleBuilder ? 100 : 200) != 0) {
				return false;
			}
			if (this.castleBuilder) {
				Castle.plan(this, level);
			} else {
				this.planHouse(level);
			}
			if (this.housePlan == null) {
				return false;
			}
		}
		Vec3 c = Vec3.atBottomCenterOf(this.houseCenter);
		double distSq = this.mob.distanceToSqr(c);
		if (distSq > (this.castleBuilder ? 256.0 : 100.0)) {
			this.housePlan = null;
			return false;
		}
		if (distSq > 1.0) {
			if (this.mob.tickCount % 10 == 0) {
				this.mob.getNavigation().moveTo(c.x, c.y, c.z, this.walkSpeed());
			}
			return true;
		}
		this.holdStill();
		if (++this.buildTick % this.buildInterval != 0) {
			return true;
		}
		while (this.houseIndex < this.housePlan.size()) {
			BlockPos p = this.housePlan.get(this.houseIndex);
			BlockState s = this.houseStates.get(this.houseIndex);
			this.houseIndex++;
			this.mob.getLookControl().setLookAt(Vec3.atCenterOf(p));
			if (this.placeBlueprintBlock(level, p, s)) {
				break;
			}
		}
		if (this.houseIndex >= this.housePlan.size()) {
			this.housePlan = null;
			if (this.buildingOutpost) {
				this.buildingOutpost = false;
				this.outpostCooldown = 2400 + this.mob.getRandom().nextInt(2400);
				return true;
			}
			if (this.castleBuilder) {
				this.houseBuilt = true;
				this.castleCenter = this.houseCenter;
			} else if (this.isPassive()) {
				this.houseCooldown = 2400 + this.mob.getRandom().nextInt(2400);
			} else {
				this.houseBuilt = true;
			}
		}
		return true;
	}

	/**
	 * Places one blueprint block. {@link Outpost#DIG} means dig the spot out; blocks below the build's base
	 * (floors, foundations) may replace natural ground; supply chests get filled.
	 */
	private boolean placeBlueprintBlock(ServerLevel level, BlockPos pos, BlockState state) {
		BlockState current = level.getBlockState(pos);
		if (state == Outpost.DIG) {
			if (current.canBeReplaced() || !BlockWork.canMine(this.mob, level, pos, current)) {
				return false;
			}
			level.destroyBlock(pos, false, this.mob);
			this.blocks = Math.min(this.blocks + 1, MAX_STOCK);
			this.mob.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
			return true;
		}
		if (state.getBlock() instanceof DoorBlock || state.getBlock() instanceof AbstractBedBlock) {
			return this.placeTwoPart(level, pos, state);
		}
		if (pos.getY() < this.houseCenter.getY() && !current.canBeReplaced()) {
			if (!BlockWork.isNatural(level, pos, current) || current.hasBlockEntity() || current.is(state.getBlock())) {
				return false;
			}
			level.setBlock(pos, state, Block.UPDATE_ALL);
			MobBuilt.mark(level, pos);
			level.playSound(null, pos, state.getSoundType().getPlaceSound(), SoundSource.HOSTILE, 1.0F, 0.8F);
			this.mob.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
			return true;
		}
		boolean placed = this.place(level, pos, state, false);
		if (placed && this.buildingOutpost && (state.is(Blocks.CHEST) || state.is(Blocks.BARREL))) {
			Furnaces.fillSupplyChest(level, pos, this.mob.getRandom());
		}
		return placed;
	}

	/** Doors (lower + upper half) and beds (foot + head) need both blocks placed together. */
	private boolean placeTwoPart(ServerLevel level, BlockPos pos, BlockState state) {
		BlockPos other;
		BlockState otherState;
		if (state.getBlock() instanceof DoorBlock) {
			other = pos.above();
			otherState = state.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER);
		} else {
			other = pos.relative(state.getValue(HorizontalDirectionalBlock.FACING));
			otherState = state.setValue(AbstractBedBlock.PART, BedPart.HEAD);
		}
		if (!level.getBlockState(pos).canBeReplaced() || !level.getBlockState(other).canBeReplaced()
			|| !level.isUnobstructed(state, pos, CollisionContext.empty()) || !level.isUnobstructed(otherState, other, CollisionContext.empty())) {
			return false;
		}
		int flags = Block.UPDATE_ALL | Block.UPDATE_KNOWN_SHAPE;
		level.setBlock(pos, state, flags);
		level.setBlock(other, otherState, flags);
		MobBuilt.mark(level, pos);
		MobBuilt.mark(level, other);
		level.playSound(null, pos, state.getSoundType().getPlaceSound(), SoundSource.HOSTILE, 1.0F, 0.8F);
		this.mob.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
		return true;
	}

	private void planHouse(ServerLevel level) {
		Houses.plan(this, level, Houses.random(this.mob.getRandom()));
	}

	private BlockState makeshiftState(RandomSource r) {
		return this.houseWall.defaultBlockState();
	}

	// ------------------------------------------------------------------ mining: ores and gathering

	private boolean tickDigTarget(ServerLevel level) {
		if (this.digTarget != null) {
			BlockState state = level.getBlockState(this.digTarget);
			boolean valid = this.digTargetDrops ? BlockWork.isOre(state) : BlockWork.isBuildingMaterial(state);
			if (--this.digTimeout <= 0 || !valid) {
				this.digTarget = null;
				return false;
			}
			Vec3 c = Vec3.atCenterOf(this.digTarget);
			double reachSq = state.is(BlockTags.LOGS) ? 20.0 : 9.0;
			if (this.mob.distanceToSqr(c) <= reachSq) {
				this.startMining(this.digTarget, !this.digTargetDrops);
				this.digTarget = null;
				return true;
			}
			if (!this.mob.onGround() && !this.mob.isInWater()) {
				return true;
			}
			double dy = c.y - this.mob.getY();
			double horiz = Math.sqrt(Math.pow(c.x - this.mob.getX(), 2) + Math.pow(c.z - this.mob.getZ(), 2));
			if (dy < -1.5 && horiz < 1.5) {
				this.startDig(1, false);
			} else if (dy > 2.5 && horiz < 2.0) {
				this.startTower(1);
			} else if (!this.stepToward(level, c)) {
				this.digTarget = null;
			}
			return true;
		}

		RandomSource r = this.mob.getRandom();
		BlockPos here = this.mob.blockPosition();
		if (this.oreCooldown == 0) {
			this.oreCooldown = 100 + r.nextInt(150);
			if (r.nextFloat() < 0.85F) {
				BlockPos ore = BlockWork.findNearestAt(level, here, 12, (p, s) -> BlockWork.isOre(s) && BlockWork.isExposed(level, p) && BlockWork.canMine(this.mob, level, p, s));
				if (ore == null) {
					ore = BlockWork.findNearestAt(level, here, 8, (p, s) -> BlockWork.isOre(s) && BlockWork.canMine(this.mob, level, p, s));
				}
				if (ore != null) {
					this.setDigTarget(ore, true);
					return true;
				}
			}
		}
		boolean passive = this.isPassive();
		if (this.gatherCooldown == 0 && this.blocks < (passive ? 112 : 64)) {
			this.gatherCooldown = passive ? 80 + r.nextInt(160) : 200 + r.nextInt(300);
			BlockPos log = BlockWork.findNearestAt(level, here, passive ? 10 : 6, (p, s) -> s.is(BlockTags.LOGS) && BlockWork.isNatural(level, p, s) && BlockWork.isExposed(level, p) && BlockWork.canMine(this.mob, level, p, s));
			if (log == null && passive) {
				// Passive mobs quarry stone into cobblestone.
				log = BlockWork.findNearestAt(level, here, 5, (p, st) -> st.is(BlockTags.BASE_STONE_OVERWORLD) && BlockWork.isNatural(level, p, st)
					&& BlockWork.isExposed(level, p) && !p.equals(here.below()) && BlockWork.canMine(this.mob, level, p, st));
			}
			BlockPos pick = log != null ? log : BlockWork.findNearestAt(level, here, 3,
				(p, s) -> BlockWork.isNatural(level, p, s) && BlockWork.isExposed(level, p) && !p.equals(here.below()) && BlockWork.canMine(this.mob, level, p, s));
			if (pick != null) {
				this.setDigTarget(pick, false);
				return true;
			}
		}
		return false;
	}

	void setDigTarget(BlockPos pos, boolean drops) {
		this.digTarget = pos;
		this.digTargetDrops = drops;
		this.digTimeout = 300;
	}
}
