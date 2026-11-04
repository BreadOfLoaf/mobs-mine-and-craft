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
	private int assistBackoff;
	private Vec3 lastPos = Vec3.ZERO;
	private int stuckTicks;

	// Retreating behind a wall, and cover walls against ranged attackers
	private int fleeTicks;
	private Vec3 fleeGoal;
	private LivingEntity fleeFrom;
	private int coverCooldown;

	// Tool crafting
	boolean toolCrafter;
	private int toolCraftTimer = -1;
	private int craftingTicks;
	private BlockPos craftStation;

	// Group decoration
	private int decorCooldown;

	// House building
	boolean houseBuilder;
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
	int oreCooldown;
	private int gatherCooldown;
	BlockPos digTarget;
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
	int sniperBlindTicks;
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

	// Mid-fight crafting at a table: ladders, buckets, anvils (see CombatCrafting)
	boolean combatCrafter;
	int combatCraftCooldown;
	CombatCrafting.Craft crafting;
	int craftTicks;
	BlockPos craftTable;
	int ladders;
	int anvils;
	int anvilCooldown;
	int climbTicks;
	BlockPos climbToward;
	private int buildWalkTicks;

	// Big-fight roles: potion support and redstone engineers (see Support, RedstoneTraps)
	boolean supporter;
	int supportCooldown;
	boolean engineer;
	int rsCooldown;
	RedstoneTraps.Kind rsKind;
	List<BlockPos> rsPlan;
	List<BlockState> rsStates;
	int rsIndex;
	BlockPos rsButtonSpot;
	BlockPos rsButton;
	BlockPos rsCartPos;
	Direction rsDir;
	int rsPresses;
	int rsPressCooldown;
	int rsOperateTicks;

	// Shields (see Shields)
	int shieldTicks;
	int shieldRest;

	// Retreat and heal, food (see Recovery)
	int rawMeat;
	int cookedMeat;
	int meatType;
	boolean recovering;
	int recoverTicks;
	int recoverCooldown;
	Vec3 recoverGoal;
	boolean recoverWalled;
	BlockPos recoverFire;
	int recoverCookTicks;
	Mob healRequest;
	LivingEntity recoverFrom;

	// Tunnelling under the player (see Tunnel)
	boolean tunneler;
	int tunnelTicks;
	int tunnelCooldown;
	int tunnelY;
	double tunnelSurface;
	boolean tunnelReported;

	// Staircases up to a towering player (see Stairs)
	int stairTicks;
	int stairCooldown;
	int stairPhase;
	int stairStepWait;
	int stairTopY;
	BlockPos stairStart;
	BlockPos stairPillar;
	Direction stairDir;
	BlockPos stairNext;
	boolean preferStairs;

	// Enchanting gear at a table mid-fight (see CombatCrafting, Gear)
	boolean enchanter;
	boolean gearEnchanted;

	// Passive farming (see Farming)
	boolean farmer;
	int farmCooldown;
	List<BlockPos> farmPlan;
	int farmIndex;
	int farmTicks;
	BlockPos farmWater;
	Block farmCrop;

	// Passive prospecting trips (see Prospecting)
	int prospectCooldown;
	int prospectPhase;
	List<Prospecting.Op> prospectOps;
	int prospectIndex;
	int prospectTicks;
	int prospectStuck;
	BlockPos prospectStart;
	Direction prospectDir;
	BlockPos prospectFurnace;
	Mob prospectLeader;
	boolean forceProspect;
	boolean forceBunker;
	boolean forceClock;

	// Personality (see Personality, Personalities)
	Personality personality = Personality.TACTICIAN;
	boolean greedy;
	int crafterCooldown;
	int crafterTicks;
	Mob crafterFor;
	final java.util.Map<java.util.UUID, Integer> crafterGiven = new java.util.HashMap<>();
	int crafterMade;
	int craftDisengage;
	int mineCooldown;
	Vec3 builderSpot;
	int builderSpotTicks;
	int builderWallHeight;
	int builderWallCooldown;
	Vec3 stuckCheckPos;
	int stuckIdleTicks;
	Mob escortOf;
	Vec3 siteGoal;
	boolean engineerAmbush;
	int siteTicks;
	int idleCraftCooldown;
	ItemStack crafterItem;
	BlockPos crafterTable;
	int torches;
	BlockPos stashPos;
	int stashTicks;
	LivingEntity imposterVictim;
	int imposterKills;
	int imposterUtilityCooldown;
	final List<ItemStack> stolen = new ArrayList<>();
	int imposterCooldown;
	Vec3 awayGoal;
	int awayTicks;
	int orbitDir = 1;
	int heroPhase;
	int heroTicks;
	LivingEntity heroFrom;
	BlockPos heroTable;
	boolean heroDone;
	int giftsLeft;
	int giftCooldown;
	int griefCooldown;
	int griefTntCooldown;
	Vec3 griefFleeFrom;
	int griefFireCooldown;
	int panicTicks;
	int griefFleeTicks;
	int cleanupCooldown;
	BlockPos cleanupTarget;
	private boolean digAny;
	// Building budget for reaching a dig target, and targets it gave up on (unreachable) for a while
	private int digBuildBudget;
	private boolean lastStepPlaced;
	private final it.unimi.dsi.fastutil.longs.LongOpenHashSet badDigTargets = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

	// Debug personality name tag (/modsmac nametags)
	private boolean ownNameTag;
	private String nameTagText;

	// What it knows about the player (see Awareness), investigating, flanking, anvil warnings
	Player knownPlayer;
	Vec3 knownPos;
	long knownTick;
	long lastSeenTick = -10000;
	int investigateTicks;
	int flankSlot = Integer.MIN_VALUE;
	Vec3 flankBase;
	int sidestepTicks;
	final List<long[]> pendingAnvils = new ArrayList<>();

	// Going after items on the ground (see Looting)
	net.minecraft.world.entity.item.ItemEntity lootItem;
	int lootTicks;
	Mob lootRival;
	int lootRivalTicks;

	// Eating takes time (see Eating)
	ItemStack eatFood;
	int eatTicks;
	int eatDuration;
	Runnable eatFinish;
	float eatHealth;
	ItemStack eatStashedOffhand;
	float eatOffhandDrop;

	// Neural tactics (see NeuralTactics): weights being trained (null = the shipped ones), current choice
	float[] tacticsWeights;
	NeuralTactics.Tactic tactic = NeuralTactics.Tactic.USUAL;
	int tacticTimer;
	int fightTicks;
	int coverCooldownNeural;

	// Skeleton cover fighting (see Cover)
	int coverPhase;
	int coverTicks;
	int coverPhaseTicks;
	BlockPos coverHide;
	BlockPos coverPeek;
	boolean coverShot;

	// Mob-on-mob fights (see Brawl)
	boolean brave;
	LivingEntity brawlFoe;

	// Juggernaut's knights (see Knights) and shield walls (see Shields)
	Mob knightOf;
	boolean shieldWall;
	private boolean knightsSpawned;

	// Boats (see Boats)
	net.minecraft.world.entity.vehicle.boat.Boat boat;
	int boatCooldown;
	int boatCraftTicks;
	int boatTicks;

	// Concealed bunker while healing (see Recovery)
	List<BlockPos> bunker;
	int wallInterval = 4;

	// Building in pairs: the lead builder's partner, and who a helper is helping (see tickBlueprint)
	SmartBrain partner;
	SmartBrain helpingWith;
	int helperIndex = -1;
	int workingIndex = -1;
	int partnerWaitTicks;
	boolean soloBuild;

	// Blueprint blocks that were blocked (by a mob standing there) get retried at the end
	private final java.util.Map<BlockPos, Integer> blueprintRetries = new java.util.HashMap<>();

	// Juggernaut fort: tries before it gives up on rough ground
	private int castleTries;

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
		if (Juggernaut.isJuggernaut(this.mob) && !this.pickaxe.isEmpty()) {
			this.restoreHand();
			this.mob.spawnAtLocation(level, Juggernaut.worn(this.pickaxe, this.mob.getRandom()));
			this.pickaxe = ItemStack.EMPTY;
		}
		Juggernaut.dropGear(level, this.mob);
		Inventory.dropAll(this, level);
	}

	/** One-line summary of what the AI is busy with (for playtest reports). */
	public String debugState() {
		return String.format(java.util.Locale.ROOT,
			"pick=%s stashed=%s mining=%s tower=%d dig=%d flee=%d climb=%d plan=%s sniper=%d wall=%s craft=%s rs=%s/%s job=%s dig=%s assist=%d reach=%s hand=%s",
			this.pickaxe.getItem(), this.stashedHand == null ? "-" : this.stashedHand.getItem(), this.miningPos != null, this.towerRemaining, this.digRemaining, this.fleeTicks, this.climbTicks,
			this.housePlan == null ? "-" : this.houseIndex + "/" + this.housePlan.size(), this.sniperPhase,
			this.wallPlan == null ? "-" : this.wallIndex + "/" + this.wallPlan.size(), this.crafting,
			this.rsPlan != null, this.rsButton != null, this.enchantTicks > 0 || this.fletchTicks > 0 || this.craftingTicks > 0,
			this.digTarget, this.assistTicks, this.targetReachable, this.mob.getMainHandItem().getItem());
	}

	public boolean isBuilding() {
		return this.housePlan != null || this.helpingWith != null || this.rsPlan != null || this.trapPlan != null || this.prospectPhase != Prospecting.NONE || this.farmPlan != null;
	}

	/** Test hook: switch on one behaviour right away. */
	public void debugForce(String what) {
		if (!this.initialized) {
			this.init();
		}
		RandomSource r = this.mob.getRandom();
		switch (what) {
			case "shield" -> Shields.give(this);
			case "tunnel" -> { this.tunneler = true; this.tunnelCooldown = 0; this.engineer = false; this.combatCrafter = false; }
			case "stairs" -> { this.preferStairs = true; this.ladders = 0; this.stairCooldown = 0; this.engineer = false; this.combatCrafter = false; }
			case "ladders" -> { this.ladders = 16; this.engineer = false; this.combatCrafter = false; }
			case "crafter" -> { this.combatCrafter = true; this.combatCraftCooldown = 0; this.enchanter = true; this.engineer = false; }
			case "farmer" -> { this.farmer = true; this.farmCooldown = 0; }
			case "prospector" -> { this.prospectCooldown = 0; this.forceProspect = true; }
			case "bunker" -> { this.mob.setHealth(this.mob.getMaxHealth() * 0.3F); this.cookedMeat = 2; this.recoverCooldown = 0; this.forceBunker = true; }
			case "clock" -> { this.engineer = true; this.rsCooldown = 0; this.combatCrafter = false; this.forceClock = true; }
			case "brave" -> this.brave = true;
			case "greedy" -> this.greedy = true;
			case "anvils" -> { this.anvils = 6; this.anvilCooldown = 0; this.combatCrafter = false; this.engineer = false; }
			case "engineer_now" -> { this.rsCooldown = 0; this.trapCooldown = 0; }
			case "nosniper" -> { this.sniper = false; this.engineer = false; this.combatCrafter = false; this.wallCooldown = 0; }
			case "mess" -> {
				// Leave a little fight mess nearby: a 3x3x3 block of cobblestone (for cleanup tests).
				BlockPos base = this.mob.blockPosition().offset(3, 0, 0);
				for (int x = 0; x < 3; x++) for (int y = 0; y < 3; y++) for (int z = 0; z < 3; z++) {
					this.place((ServerLevel) this.mob.level(), base.offset(x, y, z), Blocks.COBBLESTONE.defaultBlockState(), true);
				}
			}
			case "sniper" -> { this.sniper = true; this.sniperCooldown = 0; this.blocks = 64; this.engineer = false; this.combatCrafter = false; }
			case "hurt" -> { this.mob.setHealth(this.mob.getMaxHealth() * 0.3F); this.rawMeat = 2; this.cookedMeat = 1; this.recoverCooldown = 0; }
			case "juggernaut" -> { Juggernaut.make(this.mob, r); this.knightsSpawned = false; this.pickaxe = Juggernaut.pickaxe(this.mob, r); this.castleBuilder = true; this.houseBuilder = false; this.houseBuilt = false;
				if (this.mob instanceof net.minecraft.world.entity.monster.skeleton.AbstractSkeleton) this.bowEnchanted = true; }
			default -> {}
		}
	}

	/** Fraction of the current build placed, or -1 if not building a house/outpost. */
	public double buildProgress() {
		return this.housePlan == null ? -1 : this.houseIndex / (double) this.housePlan.size();
	}

	/** Test hook: make this mob a redstone engineer, ready to build a trap now. */
	public void debugMakeEngineer() {
		this.engineer = true;
		this.rsCooldown = 0;
		this.combatCrafter = false;
	}

	/** Test hook: start a build right here (house above ground, underground build below). */
	public boolean debugStartBuild(ServerLevel level) {
		if (this.housePlan == null) {
			this.planHouse(level);
		}
		return this.housePlan != null;
	}

	/** Shows (or removes) the personality name tag, without touching names players gave their mobs. */
	private void updateNameTag() {
		if (!com.mobsminecraft.world.NameTags.enabled()) {
			if (this.ownNameTag) {
				this.mob.setCustomName(null);
				this.mob.setCustomNameVisible(false);
				this.ownNameTag = false;
				this.nameTagText = null;
			}
			return;
		}
		if (this.mob.hasCustomName() && !this.ownNameTag) {
			return;
		}
		// Bats, fish and the like: a personality means nothing for them, so no tag.
		if (!BlockWork.canBuild(this.mob) && !(this.mob instanceof Enemy)) {
			return;
		}
		String name = this.personality.name().charAt(0) + this.personality.name().substring(1).toLowerCase();
		if (this.personality == Personality.HERO && this.heroDone) {
			name = "Hero (risen)";
		}
		if (Juggernaut.isJuggernaut(this.mob) && !(this.personality == Personality.HERO && this.heroDone)) {
			name = "Juggernaut " + name;
		} else if (this.knightOf != null) {
			name = "Knight " + name;
		}
		if (name.equals(this.nameTagText)) {
			return;
		}
		net.minecraft.ChatFormatting color = switch (this.personality) {
			case FIGHTER -> net.minecraft.ChatFormatting.RED;
			case TACTICIAN -> net.minecraft.ChatFormatting.GOLD;
			case BUILDER -> net.minecraft.ChatFormatting.GREEN;
			case ENGINEER -> net.minecraft.ChatFormatting.DARK_RED;
			case CRAFTER -> net.minecraft.ChatFormatting.AQUA;
			case MINEMAN -> net.minecraft.ChatFormatting.GRAY;
			case IMPOSTER -> net.minecraft.ChatFormatting.DARK_PURPLE;
			case HERO -> net.minecraft.ChatFormatting.YELLOW;
			case GIFTER -> net.minecraft.ChatFormatting.LIGHT_PURPLE;
			case GRIEFER -> net.minecraft.ChatFormatting.DARK_GREEN;
		};
		this.mob.setCustomName(net.minecraft.network.chat.Component.literal(name).withStyle(color));
		this.mob.setCustomNameVisible(true);
		this.ownNameTag = true;
		this.nameTagText = name;
	}

	/** Whether this mob refuses to take {@code target} as its attack target (called from setTarget). */
	public boolean refusesTarget(LivingEntity target) {
		if (!(target instanceof Player) || !this.initialized) {
			return false;
		}
		Personality p = this.personality;
		if (p.peaceful() || p == Personality.ENGINEER || p == Personality.GRIEFER) {
			return true;
		}
		if (p.busyWithRole()) {
			// Builders and crafters only fight back.
			return !(this.mob.getLastHurtByMob() == target && this.mob.tickCount - this.mob.getLastHurtByMobTimestamp() < 200);
		}
		return false;
	}

	/** No target, but it knows roughly where a player was: go and look, then look around, then give up. */
	private boolean investigate(ServerLevel level) {
		if (!(this.mob instanceof Enemy) || this.knownPos == null || !this.personality.usesTricks() && this.personality != Personality.FIGHTER
			|| this.personality.peaceful() || this.personality.busyWithRole() || level.getGameTime() - this.knownTick > 400) {
			this.investigateTicks = 0;
			return false;
		}
		if (++this.investigateTicks > 300) {
			this.knownPos = null;
			this.knownPlayer = null;
			this.investigateTicks = 0;
			com.mobsminecraft.debug.AiStats.add("investigate_gave_up");
			return false;
		}
		if (this.investigateTicks == 1) {
			com.mobsminecraft.debug.AiStats.add("investigate");
		}
		if (this.mob.position().distanceToSqr(this.knownPos) > 4.0) {
			this.walkTo(this.knownPos, this.walkSpeed());
		} else {
			// Arrived: look around.
			this.holdStill();
			float yaw = this.mob.getYHeadRot() + 9.0F;
			this.mob.getLookControl().setLookAt(this.mob.getX() - Math.sin(Math.toRadians(yaw)) * 4, this.mob.getEyeY(), this.mob.getZ() + Math.cos(Math.toRadians(yaw)) * 4);
		}
		return true;
	}

	/** Called when a skeleton holds fire because an ally is in the way: shuffle sideways for a clear shot. */
	public void stepAsideForShot() {
		if (this.sniperPhase != 3 && this.coverPhase == 0) {
			this.sidestepTicks = 12;
		}
	}

	boolean eating() {
		return this.eatFood != null;
	}

	/** What the mob is busy with right now, in a word (for playtest reports). */
	public String activity() {
		if (this.eating()) return "eating";
		if (this.prospectPhase != Prospecting.NONE || this.prospectLeader != null) return "prospecting";
		if (this.stashPos != null) return "stashing";
		if (this.miningPos != null) return "mining";
		if (this.digTarget != null) return "going_to_dig";
		if (this.housePlan != null) return "building";
		if (this.rsPlan != null || this.trapPlan != null) return "building_trap";
		if (this.rsButton != null) return "operating_trap";
		if (this.crafting != null || this.crafterTicks > 0 || this.crafterItem != null || this.craftDisengage > 0) return "crafting";
		if (this.farmPlan != null) return "farming";
		if (this.cookPos != null) return "cooking";
		if (this.recovering || this.fleeTicks > 0) return "retreating";
		if (this.towerRemaining > 0 || this.digRemaining > 0 || this.stairTicks > 0 || this.tunnelTicks > 0 || this.climbTicks > 0) return "climbing_digging";
		if (this.lootItem != null) return "looting";
		if (this.griefFleeFrom != null) return "fleeing_tnt";
		if (this.personality == Personality.ENGINEER && this.knownPlayer != null && this.mob.level().getGameTime() - this.knownTick < 200) return "stalking";
		if (this.imposterVictim != null) return "betraying";
		if (this.mob.getTarget() != null) return "fighting";
		if (this.knownPos != null && this.investigateTicks > 0) return "investigating";
		if (this.knightOf != null) return "guarding";
		if (this.sniperPhase == 3) return "sniping";
		return this.mob.getNavigation().isDone() ? "idle" : "wandering";
	}

	/** The mob's personality (for reports). */
	public String personalityName() {
		return this.personality.name().toLowerCase();
	}

	/** Test hook: give this mob a particular personality (before its first tick). */
	public void debugPersonality(String name) {
		Personality.set(this.mob, Personality.valueOf(name.toUpperCase()));
		if (this.initialized) {
			this.personality = Personality.valueOf(name.toUpperCase());
			Personality.apply(this, this.mob.getRandom());
			if (this.personality == Personality.GIFTER) {
				this.giftsLeft = 4;
			}
		}
	}

	/** Training hook: fight with these network weights (null = the shipped network). */
	public void setTacticsWeights(float[] w) {
		this.tacticsWeights = w;
	}

	/** Training hook: the tactic the network last picked. */
	public int tacticOrdinal() {
		return this.tactic.ordinal();
	}

	/** Test hook: is this mob perched on its sniper tower? */
	public boolean debugPerched() {
		return this.sniperPhase == 3;
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
		// Builds with what its home biome gives it: spruce in the taiga, sandstone in the desert, and so on.
		this.primaryBlock = this.mob.level() instanceof ServerLevel sl ? Houses.buildBlockFor(sl, this.mob.blockPosition(), r) : Blocks.COBBLESTONE;
		this.blocks = 24 + r.nextInt(41);
		this.decorCooldown = 300 + r.nextInt(1200);
		this.oreCooldown = 100 + r.nextInt(300);
		this.gatherCooldown = 100 + r.nextInt(300);
		this.houseBuilder = BlockWork.canBuild(this.mob) && r.nextFloat() < (this.isPassive() ? 0.12F : 0.04F);
		this.outpostBuilder = this.mob instanceof Enemy && BlockWork.canBuild(this.mob) && r.nextFloat() < 0.15F;
		this.combatCrafter = this.mob instanceof Enemy && BlockWork.canBuild(this.mob) && r.nextFloat() < 0.75F;
		this.idleCraftCooldown = 200 + r.nextInt(1000);
		// A few crafters also know how to enchant (skeletons have their own bow enchanting).
		this.enchanter = this.combatCrafter && Juggernaut.canWearArmor(this.mob) && r.nextFloat() < 0.12F;
		this.tunneler = this.mob instanceof Enemy && BlockWork.canBuild(this.mob) && r.nextFloat() < 0.25F;
		this.tunnelCooldown = 100 + r.nextInt(300);
		if (this.mob instanceof Enemy && BlockWork.canBuild(this.mob) && r.nextFloat() < 0.25F) {
			this.ladders = 8 + r.nextInt(9);
		}
		this.farmer = this.isPassive() && BlockWork.canBuild(this.mob) && r.nextFloat() < (this.mob instanceof Villager ? 0.4F : 0.2F);
		this.farmCooldown = 200 + r.nextInt(1200);
		this.prospectCooldown = 600 + r.nextInt(3000);
		this.combatCraftCooldown = 100 + r.nextInt(300);
		this.supporter = this.mob instanceof Enemy && r.nextFloat() < 0.45F;
		this.engineer = this.mob instanceof Enemy && BlockWork.canBuild(this.mob) && r.nextFloat() < 0.3F;
		this.rsCooldown = 100 + r.nextInt(300);
		this.outpostCooldown = 200 + r.nextInt(600);
		this.cookCooldown = 400 + r.nextInt(1200);
		if (this.isPassive()) {
			this.gatherCooldown = 60 + r.nextInt(200);
		}
		boolean vanillaPickup = this.mob instanceof Villager || this.mob instanceof net.minecraft.world.entity.monster.piglin.AbstractPiglin;
		this.mob.setCanPickUpLoot(vanillaPickup);
		this.gardener = BlockWork.canBuild(this.mob) && r.nextFloat() < (this.mob instanceof Villager ? 0.25F : 0.08F);
		this.gardenCooldown = 200 + r.nextInt(600);
		this.toolCrafter = BlockWork.isToolHolder(this.mob) && r.nextFloat() < 0.7F;
		if (this.toolCrafter) {
			this.toolCraftTimer = 60 + r.nextInt(360);
		}
		this.trapBuilder = this.mob instanceof Enemy && BlockWork.canBuild(this.mob) && r.nextFloat() < 0.3F;
		Juggernaut.roll(this.mob, r);
		if ((this.mob instanceof net.minecraft.world.entity.monster.zombie.Zombie
			|| this.mob instanceof net.minecraft.world.entity.monster.skeleton.AbstractSkeleton) && r.nextFloat() < 0.75F) {
			float t = r.nextFloat();
			this.pickaxe = new ItemStack(t < 0.1F ? Items.WOODEN_PICKAXE : t < 0.6F ? Items.STONE_PICKAXE : t < 0.95F ? Items.IRON_PICKAXE : Items.DIAMOND_PICKAXE);
		}
		if (Juggernaut.isJuggernaut(this.mob)) {
			this.pickaxe = Juggernaut.pickaxe(this.mob, r);
			// Juggernauts always build their fort, the moment they spot a player.
			this.castleBuilder = true;
			this.houseBuilder = false;
			if (this.mob instanceof net.minecraft.world.entity.monster.skeleton.AbstractSkeleton) {
				this.bowEnchanted = true;
			}
		}
		Consumable.assign(this, r);
		Recovery.assign(this, r);
		Shields.assign(this, r);
		Brawl.assign(this, r);
		if (this.knightOf != null) {
			Knights.init(this);
		}
		MobSpecials.init(this, r);
		// Personality last: it switches the abilities rolled above on or off.
		this.personality = Personality.of(this.mob, r);
		Personality.apply(this, r);
		if (this.knightOf != null) {
			this.shieldWall = true;
		}
		if (this.personality == Personality.GIFTER) {
			this.giftsLeft = 3 + r.nextInt(4);
		}
		if (this.personality == Personality.GIFTER || this.personality == Personality.MINEMAN) {
			this.brave = false;
		}
		// Only some of the Juggernauts that like building put up a fort.
		if (this.castleBuilder && (this.personality != Personality.TACTICIAN && this.personality != Personality.BUILDER || r.nextFloat() > 0.4F)) {
			this.castleBuilder = false;
		}
	}

	public void tick() {
		if (!(this.mob.level() instanceof ServerLevel level)) {
			return;
		}
		if (!this.initialized) {
			this.init();
		}
		this.tickCooldowns();
		if (this.mob.tickCount % 20 == 0) {
			this.updateNameTag();
		}
		Inventory.tickPickup(this, level);
		if (this.miningPos == null && this.stashedHand != null) {
			this.restoreHand();
		}
		MobSpecials.tickUnlitTnt(this, level);

		boolean griefing = level.getGameRules().get(GameRules.MOB_GRIEFING);
		LivingEntity target = this.resolveTarget(level);
		boolean canBuild = griefing && BlockWork.canBuild(this.mob) && !this.mob.isPassenger();
		// Gifters, imposters, minemen, engineers (and builders/crafters unless attacked) leave the player alone.
		if (this.refusesTarget(this.mob.getTarget())) {
			this.mob.setTarget(null);
		}
		if (this.personality.peaceful() && target instanceof Player) {
			target = null;
		}
		if (Eating.tick(this, level)) {
			return;
		}
		Awareness.tick(this, level);
		CombatCrafting.tickPendingAnvils(this, level);
		// Nobody (but a creeper) wants to be next to lit TNT.
		if (!(this.mob instanceof net.minecraft.world.entity.monster.Creeper) && BlockWork.canBuild(this.mob) && Griefer.dodgeTnt(this, level)) {
			this.cancelMining(level);
			return;
		}
		if (this.sidestepTicks > 0 && target != null) {
			this.sidestepTicks--;
			Vec3 to = target.position().subtract(this.mob.position()).multiply(1.0, 0.0, 1.0);
			if (to.lengthSqr() > 1.0E-4) {
				Vec3 side = new Vec3(-to.z, 0.0, to.x).normalize().scale((this.mob.getId() & 1) == 0 ? 2.0 : -2.0);
				this.mob.getNavigation().stop();
				this.mob.getMoveControl().strafe(0.0F, (this.mob.getId() & 1) == 0 ? 0.6F : -0.6F);
			}
		}

		if (!this.knightsSpawned) {
			this.knightsSpawned = true;
			if (Juggernaut.isJuggernaut(this.mob) && !this.mob.entityTags().contains(Juggernaut.KNIGHTS_TAG)) {
				this.mob.addTag(Juggernaut.KNIGHTS_TAG);
				Knights.spawnFor(this.mob, level, this.mob.getRandom());
			}
		}
		if (Boats.tick(this, level, target, griefing)) {
			return;
		}
		if (Brawl.tick(this, level, target)) {
			return;
		}
		if (this.miningPos == null && Personalities.tick(this, level, target, griefing)) {
			return;
		}
		if (this.miningPos == null && this.housePlan == null && Looting.tick(this, level, target)) {
			return;
		}

		// A Juggernaut that spots a player builds its fort first, ignoring the player until it's done.
		if (canBuild && this.castleBuilder && !this.houseBuilt && (target != null || this.housePlan != null) && this.tickFortFirst(level)) {
			return;
		}

		if (target != null) {
			Consumable.tryUse(this, level, target, griefing);
			MobSpecials.combat(this, level, target, griefing);
		}
		Shields.tick(this, level, target);
		if (this.supporter && (target != null || this.mob.tickCount % 10 == 0)) {
			Support.tick(this, level, target);
		}

		boolean freeTime = target == null || this.mob.distanceTo(target) > 12.0F;
		if (freeTime && this.miningPos == null && this.towerRemaining == 0 && this.digRemaining == 0 && this.housePlan == null) {
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
		if (this.recovering) {
			if (Recovery.tick(this, level, griefing)) {
				if (this.wallPlan != null) {
					this.tickWall(level);
				}
				return;
			}
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
		if (CombatCrafting.tickClimb(this)) {
			return;
		}
		if (this.wallPlan != null) {
			this.tickWall(level);
		}
		if (this.gardenPos != null) {
			Gardening.tick(this, level);
			return;
		}
		// Griefers wreck things whether or not there's a fight on.
		if (Griefer.grieves(this)) {
			if (Griefer.tick(this, level, target) && this.griefFleeFrom != null) {
				return;
			}
		}
		if (this.digAny && this.digTarget != null && this.tickDigTarget(level)) {
			return;
		}

		if (target != null) {
			float hp = this.mob.getHealth() / this.mob.getMaxHealth();
			// A sniper on its perch stays put, whatever else is going on.
			if (this.sniperPhase == 3 && MobSpecials.trySniper(this, level, target)) {
				Recovery.snack(this, level);
				return;
			}
			if (Recovery.tryStart(this, level, target)) {
				return;
			}
			if (hp < 0.3F && this.escapeCooldown == 0) {
				this.startEscape(target);
				return;
			}
			if (Stairs.tick(this, level, target)) {
				return;
			}
			// Fighters (and gifters) just charge like vanilla mobs: no tricks, no crafting.
			if (!this.personality.usesTricks()) {
				return;
			}
			if (Cover.tick(this, level, target, griefing)) {
				return;
			}
			// Tacticians and heroes pick their tactic with the trained network (if there is one).
			if ((this.personality == Personality.TACTICIAN || this.personality == Personality.HERO) && NeuralTactics.tick(this, level, target)) {
				return;
			}
			// Knights guard their Juggernaut while it builds its fort.
			if (this.knightOf != null && brainOf(this.knightOf).housePlan != null && Knights.stayClose(this, true)) {
				return;
			}
			// A builder stays on its build; in a group of builders, one keeps building and the rest fight.
			if (this.housePlan != null && this.isLeadBuilder(level) && this.tickBlueprint(level)) {
				return;
			}
			if (MobSpecials.trySniper(this, level, target)) {
				return;
			}
			if (RedstoneTraps.tick(this, level, target, griefing)) {
				return;
			}
			if (CombatCrafting.tick(this, level, target, griefing)) {
				return;
			}
			CombatCrafting.dropAnvil(this, level, target);
			if (Tunnel.tick(this, level, target, griefing)) {
				return;
			}
			if (this.gardener && Gardening.tryTreeTrap(this, level, target)) {
				return;
			}
			this.tryCoverWall(level, target);
			if (this.mob.distanceTo(target) > 16.0F && !this.pickaxe.isEmpty() && this.tickDigTarget(level)) {
				return;
			}
			this.updateReachable(target);
			if (Flank.tick(this, level, target)) {
				return;
			}
			if (this.isChaser(target)) {
				this.chaseAssist(level, target);
			}
			return;
		}

		if (this.housePlan != null && this.tickBlueprint(level)) {
			return;
		}
		if (this.tickHelping(level)) {
			return;
		}
		if (this.investigate(level)) {
			return;
		}
		if (Wolves.isWildWolf(this) && Wolves.heal(this, level)) {
			return;
		}
		// Monsters take a dislike to any griefer wrecking things near them.
		if (this.mob instanceof Enemy && !Griefer.grieves(this) && this.mob.tickCount % 40 == 0 && this.mob.getTarget() == null && this.personality != Personality.GIFTER) {
			for (Mob m : level.getEntitiesOfClass(Mob.class, this.mob.getBoundingBox().inflate(12.0), m -> m != this.mob && m.isAlive() && Griefer.grieves(brainOf(m)))) {
				if (this.mob.hasLineOfSight(m) && this.mob.getRandom().nextFloat() < 0.5F) {
					this.mob.setTarget(m);
					com.mobsminecraft.debug.AiStats.add("mob_hunts_griefer");
					break;
				}
			}
		}
		// Each personality's own work when there's no fight on.
		if (canBuild && Roles.idle(this, level)) {
			return;
		}
		if (Knights.stayClose(this, false)) {
			return;
		}
		// A sniper keeps watch from its perch even when it has lost sight of the player.
		if (this.sniperPhase == 3 && this.sniperNest != null && this.mob.blockPosition().distManhattan(this.sniperNest) <= 1) {
			MobSpecials.holdPerch(this);
			return;
		}
		Recovery.snack(this, level);
		if ((this.isPassive() || this.personality == Personality.MINEMAN) && Prospecting.tick(this, level, griefing)) {
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
			if (Underground.plan(this, level)) {
				com.mobsminecraft.debug.AiStats.add("build_started");
			}
		}
		if (this.housePlan == null && this.isPassive() && this.logsChopped >= 10 && this.houseCooldown == 0
			&& this.mob.onGround() && this.mob.getRandom().nextInt(40) == 0) {
			if (Houses.plan(this, level, Houses.forLog(this.woodLog), false)) {
				com.mobsminecraft.debug.AiStats.add("build_started");
				this.logsChopped = 0;
			} else {
				this.houseCooldown = 200;
			}
		}
		if (this.tickBlueprint(level)) {
			return;
		}
		if (this.isPassive() && Farming.tick(this, level, griefing)) {
			return;
		}
		if ((this.isPassive() || this.rawMeat > 0) && Furnaces.tickCooking(this, level, griefing)) {
			return;
		}
		if (this.gardener && Gardening.tryPlant(this, level)) {
			return;
		}
		if (CombatCrafting.tickIdle(this, level, griefing)) {
			return;
		}
		if (this.personality == Personality.BUILDER && this.digTarget == null && Cleanup.tick(this, level)) {
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
		if (this.combatCraftCooldown > 0) this.combatCraftCooldown--;
		if (this.anvilCooldown > 0) this.anvilCooldown--;
		if (this.supportCooldown > 0) this.supportCooldown--;
		if (this.boatCooldown > 0) this.boatCooldown--;
		if (this.crafterCooldown > 0 && this.crafterCooldown != Integer.MAX_VALUE) this.crafterCooldown--;
		if (this.idleCraftCooldown > 0) this.idleCraftCooldown--;
		if (this.mineCooldown > 0) this.mineCooldown--;
		if (this.coverCooldownNeural > 0) this.coverCooldownNeural--;
		if (this.builderWallCooldown > 0) this.builderWallCooldown--;
		if (this.imposterCooldown > 0) this.imposterCooldown--;
		if (this.imposterUtilityCooldown > 0) this.imposterUtilityCooldown--;
		if (this.giftCooldown > 0) this.giftCooldown--;
		if (this.griefCooldown > 0) this.griefCooldown--;
		if (this.griefTntCooldown > 0) this.griefTntCooldown--;
		if (this.griefFireCooldown > 0) this.griefFireCooldown--;
		if (this.cleanupCooldown > 0) this.cleanupCooldown--;
		if (this.rsCooldown > 0) this.rsCooldown--;
		if (this.assistTicks > 0) this.assistTicks--;
		if (this.coverCooldown > 0) this.coverCooldown--;
		if (this.gatherCooldown > 0) this.gatherCooldown--;
		if (this.gardenCooldown > 0) this.gardenCooldown--;
		if (this.treeTrapCooldown > 0) this.treeTrapCooldown--;
		if (this.sniperCooldown > 0) this.sniperCooldown--;
		if (this.recoverCooldown > 0) this.recoverCooldown--;
		if (this.tunnelCooldown > 0) this.tunnelCooldown--;
		if (this.stairCooldown > 0) this.stairCooldown--;
		if (this.farmCooldown > 0) this.farmCooldown--;
		if (this.prospectCooldown > 0) this.prospectCooldown--;
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
		if (this.initialized && this.personality == Personality.ENGINEER) {
			// Engineers don't charge in: they set traps for a player they've seen or heard (not one hidden behind walls).
			Player p = this.knownPlayer;
			return p != null && p.isAlive() && level.getGameTime() - this.knownTick < 200 && p.distanceTo(this.mob) < 28.0F ? p : null;
		}
		if (this.initialized && this.personality.busyWithRole()) {
			LivingEntity attacker = this.mob.getLastHurtByMob();
			return isValidTarget(attacker) && this.mob.tickCount - this.mob.getLastHurtByMobTimestamp() < 200 ? attacker : null;
		}
		LivingEntity t = this.mob.getTarget();
		if (isValidTarget(t)) {
			return t;
		}
		// Hostile mobs keep after a player who ducked out of sight, but only one they know is there: seen
		// moments ago, heard (mining, fighting, footsteps), or reported by an ally. No seeing through walls.
		if (this.mob instanceof Enemy && this.mob.tickCount % 20 == 0 && !(this.mob instanceof net.minecraft.world.entity.monster.Enderman)) {
			Player hidden = this.knownPlayer;
			if (hidden != null && hidden.distanceTo(this.mob) < 24.0F && !this.mob.hasLineOfSight(hidden) && Awareness.knowsWhere(this, hidden, 200)) {
				this.mob.setTarget(hidden);
				com.mobsminecraft.debug.AiStats.add("hunt_hidden");
				return hidden;
			}
		}
		if (this.mob instanceof Villager) {
			Player p = this.knownPlayer;
			if (p != null && p.isAlive() && p.distanceTo(this.mob) < 16.0F && level.getGameTime() - this.knownTick < 100) {
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

	/** Can the mob walk to the target? Re-checked once a second (it decides between flanking and building). */
	private void updateReachable(LivingEntity target) {
		if (--this.pathCheckCooldown <= 0) {
			this.pathCheckCooldown = 20;
			Path path = this.mob.getNavigation().createPath(target, 1);
			this.targetReachable = path != null && path.canReach();
		}
	}

	private void chaseAssist(ServerLevel level, LivingEntity target) {
		// A player in the water isn't reached by bridging, towering or digging (boats handle that).
		if (target.isInWater() || target.getVehicle() instanceof net.minecraft.world.entity.vehicle.boat.Boat) {
			this.assistTicks = 0;
			return;
		}
		this.updateReachable(target);

		// Progress check over 1-second windows: jittering back and forth on a dead-end path still counts as stuck.
		if (this.mob.tickCount % 20 == 0) {
			Vec3 pos = this.mob.position();
			if (pos.distanceToSqr(this.lastPos) < 0.25) {
				this.stuckTicks += 20;
			} else {
				this.stuckTicks = 0;
			}
			this.lastPos = pos;
		}

		// Take over when the path is impossible, or when the mob hasn't moved for 3 seconds even though
		// vanilla thinks there's a path (e.g. swimming in a moat it can't climb out of).
		boolean shooting = this.mob instanceof RangedAttackMob && this.mob.distanceTo(target) < 16.0F && this.mob.hasLineOfSight(target);
		boolean stuckAnyway = this.stuckTicks > 60 && this.mob.distanceTo(target) > 2.5F && !shooting;
		if (this.assistBackoff > 0) {
			// The last straight-line attempt was blocked: give vanilla pathing a moment before trying again.
			this.assistBackoff--;
			return;
		}
		if ((!this.targetReachable && (this.stuckTicks >= 20 || this.mob.getNavigation().isDone())) || stuckAnyway) {
			if (stuckAnyway && this.assistTicks == 0) {
				com.mobsminecraft.debug.AiStats.add("unstuck_assist");
			}
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

		if (!this.preferStairs && CombatCrafting.tryLadders(this, level, dx, dy, dz, horiz)) {
			return;
		}
		// Carrying ladders and the player is up a wall or pillar nearby: walk right up to it to ladder it.
		if (!this.preferStairs && this.ladders > 0 && dy > 2.5 && horiz >= 1.2 && horiz < 7.0 && this.stepToward(level, target.position())) {
			return;
		}
		// A player towering up: build a staircase up to them (or tower up beside them).
		if (dy > 2.5 && horiz < 6.0 && (this.preferStairs || this.mob.getRandom().nextFloat() < 0.6F) && Stairs.tryStart(this, level, target, dy)) {
			return;
		}
		if (dy > 1.5 && horiz < 2.5) {
			this.startTower(Mth.ceil(dy));
			return;
		}
		if (dy < -2.5 && horiz < 1.5) {
			BlockPos below = this.mob.blockPosition().below();
			if (BlockWork.canMine(this.mob, level, below, level.getBlockState(below))) {
				this.startDig(1, false);
			} else {
				this.stepOffLedge(level);
			}
			return;
		}
		if (horiz < 1.0) {
			return;
		}
		if (!this.stepToward(level, target.position())) {
			this.assistTicks = 0;
			this.assistBackoff = 40;
		}
	}

	/**
	 * Walks one block straight toward the goal, mining through anything in the way and bridging gaps.
	 * Returns false if the way is blocked by something unbreakable.
	 */
	boolean stepToward(ServerLevel level, Vec3 goal) {
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
				com.mobsminecraft.debug.AiStats.add("tunnel_mine");
				return true;
			}
		} else if (!BlockWork.isFloor(level, next.below())) {
			int drop = 0;
			while (drop < 5 && !BlockWork.isFloor(level, next.below(drop + 1))) {
				drop++;
			}
			boolean safeDrop = dy < -1.0 && drop <= 3;
			if (!safeDrop) {
				this.lastStepPlaced = true;
				com.mobsminecraft.debug.AiStats.add("bridge_block");
			}
			if (!safeDrop && !this.place(level, next.below(), this.buildState(), true)) {
				return this.blocks > 0;
			}
			// Counts as building toward the goal even if the block couldn't go in.
		}

		this.mob.getNavigation().stop();
		this.mob.getMoveControl().setWantedPosition(next.getX() + 0.5, wantY, next.getZ() + 0.5, this.walkSpeed());
		return true;
	}

	/** Walks off the edge of whatever the mob is standing on, toward an open spot with ground within 4 blocks. */
	private void stepOffLedge(ServerLevel level) {
		BlockPos feet = this.mob.blockPosition();
		for (Direction d : Direction.Plane.HORIZONTAL) {
			BlockPos next = feet.relative(d);
			if (BlockWork.blocksMovement(level, next) || BlockWork.blocksMovement(level, next.above())) {
				continue;
			}
			for (int drop = 1; drop <= 4; drop++) {
				if (BlockWork.isFloor(level, next.below(drop))) {
					this.mob.getNavigation().stop();
					this.mob.getMoveControl().setWantedPosition(next.getX() + 0.5, next.getY(), next.getZ() + 0.5, this.walkSpeed());
					com.mobsminecraft.debug.AiStats.add("step_off_ledge");
					return;
				}
			}
		}
	}

	int height() {
		return Math.max(1, Mth.ceil(this.mob.getBbHeight()));
	}

	/** Walks to a spot, re-pathing only when something else (a vanilla goal) changed the path. */
	void walkTo(Vec3 goal, double speed) {
		Path path = this.mob.getNavigation().getPath();
		if (path == null || path.isDone() || path.getTarget().distToCenterSqr(goal) > 4.0) {
			this.mob.getNavigation().moveTo(goal.x, goal.y, goal.z, speed);
		}
	}

	void holdStill() {
		this.mob.getNavigation().stop();
		this.mob.getMoveControl().setWantedPosition(this.mob.getX(), this.mob.getY(), this.mob.getZ(), 0.0);
	}

	/** Low on health: tower up, dig in, or run away and wall itself off from the attacker. */
	private void startEscape(LivingEntity target) {
		RandomSource r = this.mob.getRandom();
		this.escapeCooldown = 400 + r.nextInt(200);
		com.mobsminecraft.debug.AiStats.add("escape");
		float roll = r.nextFloat();
		if (roll < 0.4F) {
			this.startTower(3 + r.nextInt(3));
		} else if (roll < 0.65F) {
			BlockPos below = this.mob.blockPosition().below();
			if (this.mob.level() instanceof ServerLevel sl && BlockWork.canMine(this.mob, sl, below, sl.getBlockState(below))) {
				this.startDig(3, true);
			} else {
				this.startTower(3 + r.nextInt(3));
			}
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
			this.mob.getNavigation().moveTo(this.fleeGoal.x, this.fleeGoal.y, this.fleeGoal.z, this.walkSpeed());
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
		// Passive mobs wall themselves off from a player; neutral ones like wolves fight instead.
		boolean passive = !(this.mob instanceof Enemy) && !(this.mob instanceof NeutralMob);
		boolean wants = ranged ? dist > 10 && dist < 32 : passive && dist > 4 && dist < 12;
		RandomSource r = this.mob.getRandom();
		if (!wants || !this.mob.hasLineOfSight(target) || r.nextFloat() > 0.35F) {
			return;
		}
		this.coverCooldown = 300 + r.nextInt(300);
		com.mobsminecraft.debug.AiStats.add("cover_wall");
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
			this.wallInterval = 4;
			return;
		}
		if (this.mob.tickCount % this.wallInterval != 0) {
			return;
		}
		BlockPos p = this.wallPlan.get(this.wallIndex++);
		if (this.mob.distanceToSqr(Vec3.atCenterOf(p)) > 36.0) {
			this.wallPlan = null;
			return;
		}
		if (this.place(level, p, this.wallState, true) && this.bunker != null) {
			this.bunker.add(p);
			// Bunker blocks crumble away after a while.
			com.mobsminecraft.world.TimedCleanup.scheduleBlock(level, p, this.wallState.getBlock(), 900);
		}
	}

	void startTower(int count) {
		com.mobsminecraft.debug.AiStats.add("tower");
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
		com.mobsminecraft.debug.AiStats.add("dig_down");
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
		com.mobsminecraft.debug.AiStats.add("pickaxe_out");
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
			if (!absorb) {
				// Drops come from the tool actually used (a stashed pickaxe, or a passive mob's stand-in one), not the empty hand.
				// They go straight into the miner's inventory; anything that doesn't fit drops.
				for (ItemStack drop : Block.getDrops(state, level, this.miningPos, level.getBlockEntity(this.miningPos), this.mob, BlockWork.tool(this.mob, state))) {
					if (!Inventory.store(this, drop)) {
						Block.popResource(level, this.miningPos, drop);
					}
				}
			}
			level.destroyBlock(this.miningPos, false, this.mob);
			if (absorb) {
				this.blocks = Math.min(this.blocks + (state.is(BlockTags.LOGS) ? 4 : 1), MAX_STOCK);
			} else {
				// Go pick up what dropped (ores, etc.).
				this.pickupBoostTicks = 60;
			}
			BlockPos broken = this.miningPos;
			this.miningPos = null;
			com.mobsminecraft.debug.AiStats.add(BlockWork.isOre(state) ? "ore_mined" : "block_mined");
			this.restoreHand();
			if (state.is(BlockTags.LOGS)) {
				this.logsChopped++;
				this.woodLog = state.getBlock();
				// Keep chopping the rest of the tree.
				BlockPos nextLog = BlockWork.findNearestAt(level, broken, 2, st0 -> st0.is(BlockTags.LOGS), (p, st) -> st.is(BlockTags.LOGS) && BlockWork.isNatural(level, p, st) && BlockWork.canMine(this.mob, level, p, st));
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
		if (!useStock) {
			// Structures (houses, outposts, traps...) are protected; towers, bridges and walls are not.
			MobBuilt.mark(level, pos);
		} else {
			// ...those are fight mess, which Builders tidy up later.
			Cleanup.add(level, pos);
			com.mobsminecraft.debug.AiStats.add("fight_block:" + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath());
		}
		com.mobsminecraft.debug.AiStats.add("block_placed");
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
			this.toolCraftTimer = 150 + this.mob.getRandom().nextInt(300);
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
			this.mob.setDropChance(EquipmentSlot.MAINHAND, 0.15F);
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

	/** Plans the Juggernaut's fort right where it is, then builds it, ignoring the player. Returns true while busy. */
	private boolean tickFortFirst(ServerLevel level) {
		if (this.housePlan == null) {
			if (!this.mob.onGround() || this.mob.tickCount % 20 != 0) {
				return this.castleTries < 10;
			}
			Castle.plan(this, level);
			this.soloBuild = true;
			if (this.housePlan == null) {
				if (++this.castleTries >= 10) {
					com.mobsminecraft.debug.AiStats.add("fort_no_room");
					this.houseBuilt = true;
					return false;
				}
				return true;
			}
			com.mobsminecraft.debug.AiStats.add("fort_started");
		}
		// Ignore the player while building.
		if (this.mob.getTarget() != null) {
			this.mob.setTarget(null);
		}
		if (this.mob.isUsingItem()) {
			this.mob.stopUsingItem();
		}
		boolean busy = this.tickBlueprint(level);
		if (this.houseBuilt) {
			com.mobsminecraft.debug.AiStats.add("fort_finished");
		}
		return busy || this.housePlan != null;
	}

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
			if (this.mob.tickCount < 200 || !this.mob.onGround() || (!this.castleBuilder && this.houseCooldown > 0)
				|| this.mob.getRandom().nextInt(this.castleBuilder ? 100 : this.personality == Personality.BUILDER ? 60 : 200) != 0) {
				return false;
			}
			if (this.castleBuilder) {
				Castle.plan(this, level);
				this.soloBuild = true;
			} else {
				this.planHouse(level);
				this.soloBuild = false;
			}
			if (this.housePlan == null) {
				return false;
			}
		}
		Vec3 c = Vec3.atBottomCenterOf(this.houseCenter);
		double distSq = this.mob.distanceToSqr(c);
		if (distSq > 1024.0) {
			com.mobsminecraft.debug.AiStats.add("build_abandoned:too_far");
			this.endBuild();
			return false;
		}
		// Houses, outposts and mineshafts take two builders working together; a Juggernaut builds its fort alone.
		boolean duo = !this.soloBuild;
		if (duo && !this.hasPartner()) {
			if (this.mob.tickCount % 40 == 0) {
				this.findPartner(level);
			}
			if (!this.hasPartner()) {
				if (++this.partnerWaitTicks > 2400) {
					com.mobsminecraft.debug.AiStats.add("build_abandoned:no_partner");
					this.endBuild();
					this.houseCooldown = 1200 + this.mob.getRandom().nextInt(1200);
					return false;
				}
				// Waits at the site for help.
				if (distSq > 16.0) {
					this.walkTo(c, this.walkSpeed());
				} else {
					this.holdStill();
				}
				if (this.partnerWaitTicks == 1) {
					com.mobsminecraft.debug.AiStats.add("build_waiting_for_partner");
				}
				return true;
			}
		}
		this.partnerWaitTicks = 0;
		int next = this.nextBlueprintIndex(level);
		if (next < 0) {
			// Everything's handed out; the partner may still be placing its last block.
			this.holdStill();
		} else if (this.workBlueprint(level, this, next, duo)) {
			return true;
		}
		if (this.houseIndex >= this.housePlan.size() && this.workingIndex < 0 && (this.partner == null || this.partner.helperIndex < 0)) {
			com.mobsminecraft.debug.AiStats.add("build_finished");
			this.housePlan = null;
			this.blueprintRetries.clear();
			this.releasePartner();
			if (this.buildingOutpost) {
				this.buildingOutpost = false;
				this.outpostCooldown = 4800 + this.mob.getRandom().nextInt(4800);
				return true;
			}
			if (this.castleBuilder) {
				this.houseBuilt = true;
				this.castleCenter = this.houseCenter;
			} else if (this.isPassive() || this.personality == Personality.BUILDER || this.personality == Personality.GRIEFER) {
				// Builders (and griefers) keep at it: another build after a break.
				this.houseCooldown = (this.personality == Personality.BUILDER ? 900 : 6000) + this.mob.getRandom().nextInt(this.personality == Personality.BUILDER ? 1200 : 4000);
			} else {
				this.houseBuilt = true;
			}
		}
		return true;
	}

	// ------------------------------------------------------------------ building in pairs

	private boolean hasPartner() {
		return this.partner != null && this.partner.mob.isAlive() && this.partner.helpingWith == this && this.partner.mob.distanceTo(this.mob) < 32.0F;
	}

	/** Recruits a nearby mob of the same side that isn't busy to help build. */
	private void findPartner(ServerLevel level) {
		this.releasePartner();
		boolean hostile = this.mob instanceof Enemy;
		Mob best = null;
		for (Mob m : level.getEntitiesOfClass(Mob.class, this.mob.getBoundingBox().inflate(20.0), m -> m != this.mob && m.isAlive()
			&& (m instanceof Enemy) == hostile && BlockWork.canBuild(m) && m.getTarget() == null)) {
			SmartBrain o = brainOf(m);
			boolean willing = hostile
				? o.personality == Personality.BUILDER || o.personality == Personality.TACTICIAN || o.personality == Personality.HERO
				: !o.personality.peaceful() && o.personality != Personality.GRIEFER;
			if (!willing || o.housePlan != null || o.helpingWith != null || o.prospectPhase != Prospecting.NONE || o.prospectLeader != null) continue;
			if (best == null || m.distanceToSqr(this.mob) < best.distanceToSqr(this.mob)) best = m;
		}
		if (best != null) {
			SmartBrain o = brainOf(best);
			o.helpingWith = this;
			o.helperIndex = -1;
			this.partner = o;
			com.mobsminecraft.debug.AiStats.add("build_partner_found");
		}
	}

	private void releasePartner() {
		if (this.partner != null && this.partner.helpingWith == this) {
			this.partner.helpingWith = null;
			this.partner.helperIndex = -1;
		}
		this.partner = null;
	}

	private void endBuild() {
		this.housePlan = null;
		this.workingIndex = -1;
		this.buildingOutpost = false;
		this.soloBuild = false;
		this.partnerWaitTicks = 0;
		this.releasePartner();
	}

	/** Hands out the next blueprint index still to be placed (shared between the two builders), or -1. */
	private int nextBlueprintIndex(ServerLevel level) {
		if (this.workingIndex >= 0) {
			return this.workingIndex;
		}
		if (this.houseIndex < this.housePlan.size()) {
			this.workingIndex = this.houseIndex++;
			return this.workingIndex;
		}
		return -1;
	}

	/**
	 * One builder's turn at a blueprint block: walk within reach of it, then place it after a short pause, like a
	 * player would. Returns true while busy (walking or working). {@code owner} is the brain holding the blueprint.
	 */
	private boolean workBlueprint(ServerLevel level, SmartBrain owner, int index, boolean duo) {
		BlockPos p = owner.housePlan.get(index);
		BlockState s = owner.houseStates.get(index);
		double hx = p.getX() + 0.5 - this.mob.getX();
		double hz = p.getZ() + 0.5 - this.mob.getZ();
		double reach = duo ? 4.5 : 9.0;
		boolean outOfReach = hx * hx + hz * hz > reach * reach;
		if (outOfReach && this.buildWalkTicks < 100) {
			this.buildWalkTicks++;
			if (this.mob.tickCount % 10 == 0) {
				this.mob.getNavigation().moveTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, this.walkSpeed());
			}
			return true;
		}
		this.holdStill();
		this.mob.getLookControl().setLookAt(Vec3.atCenterOf(p));
		int interval = duo ? 10 : owner.buildInterval;
		if (++this.buildTick % interval != 0) {
			return true;
		}
		this.buildWalkTicks = 0;
		// Place it (blocks already there, or ground that doesn't need replacing, are just skipped).
		this.houseCenter = owner.houseCenter;
		this.buildingOutpost = owner.buildingOutpost;
		boolean placed = this.placeBlueprintBlock(level, p, s);
		if (owner == this) {
			this.workingIndex = -1;
		} else {
			this.helperIndex = -1;
		}
		if (!placed) {
			// Blocked by a mob (or the player) standing there: try it again at the end, so walls get no holes.
			BlockState now = level.getBlockState(p);
			if (s != Outpost.DIG && now.canBeReplaced() && !now.is(s.getBlock()) && !level.isUnobstructed(s, p, net.minecraft.world.phys.shapes.CollisionContext.empty())) {
				int tries = owner.blueprintRetries.merge(p, 1, Integer::sum);
				if (tries <= 6) {
					owner.housePlan.add(p);
					owner.houseStates.add(s);
				}
			}
			// Nothing to do there: no pause before the next one.
			this.buildTick = interval - 1;
		}
		return true;
	}

	/** A helper's turn: take a block from the lead builder's blueprint and place it. Returns true while helping. */
	private boolean tickHelping(ServerLevel level) {
		SmartBrain lead = this.helpingWith;
		if (lead == null) {
			return false;
		}
		if (!lead.mob.isAlive() || lead.housePlan == null || lead.partner != this || lead.mob.distanceTo(this.mob) > 32.0F) {
			this.helpingWith = null;
			this.helperIndex = -1;
			return false;
		}
		if (this.helperIndex < 0) {
			if (lead.houseIndex >= lead.housePlan.size()) {
				this.holdStill();
				return true;
			}
			this.helperIndex = lead.houseIndex++;
		}
		if (this.mob.tickCount % 200 == 0) {
			com.mobsminecraft.debug.AiStats.add("build_helping");
		}
		return this.workBlueprint(level, lead, this.helperIndex, true);
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
		if (placed && (state.is(Blocks.CHEST) || state.is(Blocks.BARREL))) {
			if (this.buildingOutpost) {
				Furnaces.fillSupplyChest(level, pos, this.mob.getRandom());
			} else {
				Furnaces.fillHouseChest(level, pos, this.mob.getRandom());
			}
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

	/** Surface houses above ground; underground, an outpost, cave house or mineshaft instead. */
	void planHouse(ServerLevel level) {
		RandomSource r = this.mob.getRandom();
		if (Outpost.isUnderground(level, this.mob)) {
			Underground.plan(this, level);
		} else if (this.personality == Personality.GRIEFER) {
			Houses.plan(this, level, Houses.DIRT_HUT, false);

		} else {
			Houses.plan(this, level, Houses.forBiome(level, this.mob.blockPosition(), r), false);
		}
		if (this.housePlan != null) {
			com.mobsminecraft.debug.AiStats.add("build_started");
		}
	}

	/** True unless another mob within 16 blocks with its own build in progress has a lower id. */
	private boolean isLeadBuilder(ServerLevel level) {
		for (Mob other : level.getEntitiesOfClass(Mob.class, this.mob.getBoundingBox().inflate(16.0), m -> m != this.mob && m.isAlive())) {
			if (brainOf(other).housePlan != null && other.getId() < this.mob.getId()) {
				return false;
			}
		}
		return true;
	}

	private static SmartBrain brainOf(Mob mob) {
		return ((com.mobsminecraft.SmartMobHolder) mob).mmc$getBrain();
	}

	private BlockState makeshiftState(RandomSource r) {
		return this.houseWall.defaultBlockState();
	}

	// ------------------------------------------------------------------ mining: ores and gathering

	private boolean tickDigTarget(ServerLevel level) {
		if (this.digTarget != null) {
			BlockState state = level.getBlockState(this.digTarget);
			boolean valid = this.digAny ? !state.isAir() && BlockWork.canMine(this.mob, level, this.digTarget, state)
				: this.digTargetDrops ? BlockWork.isOre(state) : BlockWork.isBuildingMaterial(state);
			if (--this.digTimeout <= 0 || !valid) {
				this.digTarget = null;
				this.digAny = false;
				return false;
			}
			Vec3 c = Vec3.atCenterOf(this.digTarget);
			double reachSq = state.is(BlockTags.LOGS) ? 20.0 : 9.0;
			if (this.mob.distanceToSqr(c) <= reachSq) {
				this.startMining(this.digTarget, !this.digTargetDrops);
				this.digTarget = null;
				this.digAny = false;
				return true;
			}
			if (!this.mob.onGround() && !this.mob.isInWater()) {
				return true;
			}
			double dy = c.y - this.mob.getY();
			double horiz = Math.sqrt(Math.pow(c.x - this.mob.getX(), 2) + Math.pow(c.z - this.mob.getZ(), 2));
			// Only a few blocks of towering/bridging to reach something to mine; beyond that it's unreachable.
			if (this.digBuildBudget < 0) {
				this.giveUpDigTarget();
				return false;
			}
			if (dy < -1.5 && horiz < 1.5) {
				this.digBuildBudget--;
				this.startDig(1, false);
			} else if (dy > 2.5 && horiz < 2.0) {
				this.digBuildBudget--;
				this.startTower(1);
			} else {
				this.lastStepPlaced = false;
				if (!this.stepToward(level, c)) {
					this.giveUpDigTarget();
				} else if (this.lastStepPlaced) {
					this.digBuildBudget--;
				}
			}
			return true;
		}

		// Crafters and engineers stick to their job instead of going off to dig.
		if (this.personality == Personality.CRAFTER || this.personality == Personality.ENGINEER) {
			return false;
		}
		RandomSource r = this.mob.getRandom();
		BlockPos here = this.mob.blockPosition();
		if (this.oreCooldown == 0) {
			this.oreCooldown = 100 + r.nextInt(150);
			if (r.nextFloat() < 0.85F) {
				BlockPos ore = BlockWork.findNearestAt(level, here, 12, BlockWork::isOre, (p, s) -> BlockWork.isOre(s) && BlockWork.isExposed(level, p) && BlockWork.canMine(this.mob, level, p, s)
					&& (!this.isPassive() || p.getY() >= here.getY() - 1));
				if (ore == null && !this.isPassive()) {
					ore = BlockWork.findNearestAt(level, here, 8, BlockWork::isOre, (p, s) -> BlockWork.isOre(s) && BlockWork.canMine(this.mob, level, p, s));
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
			BlockPos log = BlockWork.findNearestAt(level, here, passive ? 10 : 6, st -> st.is(BlockTags.LOGS), (p, s) -> s.is(BlockTags.LOGS) && BlockWork.isNatural(level, p, s) && BlockWork.isExposed(level, p) && BlockWork.canMine(this.mob, level, p, s));
			if (log == null && passive) {
				// Passive mobs quarry stone into cobblestone.
				log = BlockWork.findNearestAt(level, here, 5, (p, st) -> st.is(BlockTags.BASE_STONE_OVERWORLD) && BlockWork.isNatural(level, p, st) && p.getY() >= here.getY()
					&& BlockWork.isExposed(level, p) && !p.equals(here.below()) && BlockWork.canMine(this.mob, level, p, st));
			}
			BlockPos pick = log != null ? log : BlockWork.findNearestAt(level, here, 3,
				(p, s) -> BlockWork.isNatural(level, p, s) && BlockWork.isExposed(level, p) && !p.equals(here.below())
				&& (!passive || p.getY() >= here.getY()) && BlockWork.canMine(this.mob, level, p, s));
			if (pick != null) {
				this.setDigTarget(pick, false);
				return true;
			}
		}
		return false;
	}

	private void giveUpDigTarget() {
		if (this.digTarget != null) {
			if (this.badDigTargets.size() > 256) {
				this.badDigTargets.clear();
			}
			this.badDigTargets.add(this.digTarget.asLong());
			com.mobsminecraft.debug.AiStats.add("dig_unreachable");
		}
		this.digTarget = null;
		this.digAny = false;
	}

	void setDigTarget(BlockPos pos, boolean drops) {
		if (this.mob.tickCount % 2400 < 20) {
			this.badDigTargets.clear();
		}
		if (this.badDigTargets.contains(pos.asLong())) {
			return;
		}
		this.digBuildBudget = 6;
		this.digTarget = pos;
		this.digTimeout = 160;
		this.digTargetDrops = drops;
		this.digAny = false;
	}

	/** Dig out any block (griefing, cleaning up): not just building materials or ores. */
	void setDigTargetAny(BlockPos pos) {
		this.setDigTarget(pos, false);
		this.digAny = true;
		this.digTimeout = 200;
	}
}
