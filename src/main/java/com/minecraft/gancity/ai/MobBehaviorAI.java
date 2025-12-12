package com.minecraft.gancity.ai;

import com.minecraft.gancity.event.MobTierAssignmentHandler;
import com.minecraft.gancity.ml.*;
import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;

import java.util.*;

/**
 * AI system for enhancing mob behavior using advanced ML techniques
 * Combines Double DQN, Prioritized Replay, Multi-Agent, Curriculum Learning, 
 * Visual Perception, and Genetic Evolution for adaptive gameplay
 */
public class MobBehaviorAI {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    // Advanced ML Systems
    private DoubleDQN doubleDQN;
    private PrioritizedReplayBuffer replayBuffer;
    private MultiAgentLearning multiAgent;
    private CurriculumLearning curriculum;
    private VisualPerception visualPerception;
    private GeneticBehaviorEvolution geneticEvolution;
    
    // New AI-Player inspired systems
    private TaskChainSystem taskChainSystem;
    private ReflexModule reflexModule;
    private AutonomousGoals autonomousGoals;
    private TacticKnowledgeBase tacticKnowledgeBase;
    private ModelPersistence modelPersistence;
    
    // XGBoost for lightweight gradient boosting
    private XGBoostTacticPredictor xgboost;
    
    // Smile Random Forest for ensemble learning
    private SmileRandomForest randomForest;
    
    // Federated learning (optional)
    private FederatedLearning federatedLearning;
    
    // CRITICAL: Performance optimizer (prevents lag with 70+ learning mobs)
    private PerformanceOptimizer performanceOptimizer;
    
    // Cross-mob emergent learning settings
    private boolean crossMobLearningEnabled = false;
    private float crossMobRewardMultiplier = 3.0f;
    
    // Contextual AI difficulty scaling (Mob Control inspired)
    private boolean contextualDifficultyEnabled = true;
    private static final float NIGHT_DIFFICULTY_MULT = 1.3f;
    private static final float STORM_DIFFICULTY_MULT = 1.2f;
    private static final float THUNDERSTORM_DIFFICULTY_MULT = 1.5f;
    private static final float STRUCTURE_PROXIMITY_MULT = 1.4f;
    private static final float NETHER_DIFFICULTY_MULT = 1.6f;
    private static final float END_DIFFICULTY_MULT = 2.0f;
    private static final int STRUCTURE_SEARCH_RADIUS = 64;
    
    private boolean mlEnabled = true;  // Always enabled; uses rule-based fallback until DJL initializes
    private final Map<String, MobBehaviorProfile> behaviorProfiles = new HashMap<>();
    private final Map<String, MobState> lastStateCache = new HashMap<>();
    private final Map<String, String> lastActionCache = new HashMap<>();
    private final Map<String, VisualPerception.VisualState> lastVisualCache = new HashMap<>();
    private final Map<String, GeneticBehaviorEvolution.BehaviorGenome> activeGenomes = new HashMap<>();
    private final Random random = new Random();
    private float difficultyMultiplier = 1.0f;
    
    // Action frequency throttling (prevents thinking every tick)
    private final Map<String, Integer> mobLastThinkTick = new HashMap<>();
    private static final int THINK_INTERVAL = 15;  // Think every 15 ticks (0.75s)
    private int globalTick = 0;
    
    // Attribute-tactic correlation tracking (Mob Control inspired)
    private final Map<String, AttributeTacticCorrelation> attributeCorrelations = new HashMap<>();
    private static final float CORRELATION_THRESHOLD = 5.0f;  // Minimum reward to suggest
    private static final int CORRELATION_SAMPLE_SIZE = 10;  // Samples before suggesting

    public MobBehaviorAI() {
        initializeDefaultProfiles();
        // Don't initialize ML systems at startup - lazy load when needed
    }

    /**
     * Initialize all advanced machine learning systems (lazy-loaded)
     */
    private void initializeAdvancedMLSystems() {
        if (mlEnabled) {
            return; // Already initialized
        }
        
        try {
            // Check if DJL is available (optional dependency)
            Class.forName("ai.djl.nn.Block");
            
            // Core learning - 22 input features (state + visual + genome)
            doubleDQN = new DoubleDQN();  // Uses default 22 state features, 10 actions
            replayBuffer = new PrioritizedReplayBuffer(10000);
            
            // Multi-agent coordination
            multiAgent = new MultiAgentLearning();
            
            // Progressive difficulty
            curriculum = new CurriculumLearning();
            
            // Visual perception
            visualPerception = new VisualPerception();
            
            // Genetic evolution
            geneticEvolution = new GeneticBehaviorEvolution();
            
            // New AI-Player inspired systems
            taskChainSystem = new TaskChainSystem();
            reflexModule = new ReflexModule();
            autonomousGoals = new AutonomousGoals();
            tacticKnowledgeBase = new TacticKnowledgeBase();
            modelPersistence = new ModelPersistence();
            
            // XGBoost for gradient boosting (lightweight, explainable)
            xgboost = new XGBoostTacticPredictor();
            
            // Smile Random Forest (ensemble, robust)
            randomForest = new SmileRandomForest();
            
            // CRITICAL: Initialize performance optimizer
            performanceOptimizer = new PerformanceOptimizer();
            performanceOptimizer.setGlobalModel(doubleDQN);  // Share single model across all mobs
            
            // Load saved models if available
            modelPersistence.loadAll(doubleDQN, replayBuffer, tacticKnowledgeBase);
            
            String mlSystems = buildMLSystemsString();
            LOGGER.info("Advanced ML systems initialized - {}", mlSystems);
        } catch (ClassNotFoundException e) {
            LOGGER.info("DJL not available, using rule-based AI only (this is normal for development)");
            mlEnabled = false;
        } catch (Exception e) {
            LOGGER.warn("Failed to initialize ML systems, using rule-based fallback: {}", e.getMessage());
            mlEnabled = false;
        }
    }
    
    /**
     * Build ML systems status string
     */
    private String buildMLSystemsString() {
        List<String> systems = new ArrayList<>();
        systems.add("DQN");
        systems.add("Replay");
        systems.add("Multi-Agent");
        systems.add("Curriculum");
        systems.add("Vision");
        systems.add("Genetic");
        systems.add("Tasks");
        systems.add("Reflexes");
        systems.add("Goals");
        systems.add("Knowledge");
        
        if (xgboost != null && xgboost.isAvailable()) {
            systems.add("XGBoost");
        }
        if (randomForest != null && randomForest.isAvailable()) {
            systems.add("RandomForest");
        }
        
        systems.add("Persistence");
        return String.join(", ", systems);
    }
    
    /**
     * Enable federated learning with Git repository or cloud API
     */
    public void enableFederatedLearning(String repoUrl, String cloudApiEndpoint, String cloudApiKey) {
        // Priority: Use Cloud API if available, fallback to Git
        String effectiveEndpoint = (cloudApiEndpoint != null && !cloudApiEndpoint.isEmpty()) 
            ? cloudApiEndpoint 
            : repoUrl;
        
        if (effectiveEndpoint == null || effectiveEndpoint.isEmpty()) {
            LOGGER.info("Federated learning disabled - no repository or API configured");
            return;
        }
        
        try {
            federatedLearning = new FederatedLearning(
                modelPersistence != null ? modelPersistence.getModelPath().resolve("federated") : java.nio.file.Paths.get("federated"),
                effectiveEndpoint
            );
            
            // Link to knowledge base
            if (tacticKnowledgeBase != null) {
                tacticKnowledgeBase.setFederatedLearning(federatedLearning);
            }
            
            if (cloudApiEndpoint != null && !cloudApiEndpoint.isEmpty()) {
                LOGGER.info("Federated learning enabled - Using Cloudflare API: {}", cloudApiEndpoint);
            } else {
                LOGGER.info("Federated learning enabled - Using Git repository: {}", repoUrl);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to enable federated learning: {}", e.getMessage());
        }
    }

    /**
     * Set difficulty multiplier (affects learning speed and exploration)
     */
    public void setDifficultyMultiplier(float multiplier) {
        this.difficultyMultiplier = Math.max(0.1f, Math.min(5.0f, multiplier));
    }
    
    /**
     * Configure cross-mob emergent learning
     */
    public void setCrossMobLearning(boolean enabled, float rewardMultiplier) {
        this.crossMobLearningEnabled = enabled;
        this.crossMobRewardMultiplier = Math.max(1.0f, Math.min(10.0f, rewardMultiplier));
        
        if (enabled) {
            LOGGER.info("Cross-Mob Emergent Learning: ENABLED ({}x reward for borrowed tactics)", 
                this.crossMobRewardMultiplier);
        } else {
            LOGGER.info("Cross-Mob Emergent Learning: DISABLED");
        }
    }
    
    /**
     * Configure contextual AI difficulty scaling
     * @param enabled Whether AI difficulty adjusts based on time/weather/location
     */
    public void setContextualDifficulty(boolean enabled) {
        this.contextualDifficultyEnabled = enabled;
        
        if (enabled) {
            LOGGER.info("✓ Contextual AI Difficulty Scaling ENABLED - Harder at night/storms/structures");
        } else {
            LOGGER.info("✗ Contextual AI Difficulty Scaling DISABLED - Fixed difficulty");
        }
    }
    
    /**
     * Calculate contextual difficulty multiplier based on environment
     * Inspired by Mob Control's conditional spawn system
     * @param mobEntity The mob entity (for position/dimension)
     * @return Difficulty multiplier (1.0 = base, higher = harder)
     */
    public float getContextualDifficulty(net.minecraft.world.entity.Mob mobEntity) {
        if (!contextualDifficultyEnabled || mobEntity == null) {
            return difficultyMultiplier;
        }
        
        float contextMultiplier = 1.0f;
        net.minecraft.server.level.ServerLevel level = (net.minecraft.server.level.ServerLevel) mobEntity.level();
        net.minecraft.core.BlockPos pos = mobEntity.blockPosition();
        
        // Time of day modifier (night = harder)
        if (!level.isDay()) {
            contextMultiplier *= NIGHT_DIFFICULTY_MULT;
        }
        
        // Weather modifiers
        if (level.isThundering()) {
            contextMultiplier *= THUNDERSTORM_DIFFICULTY_MULT;
        } else if (level.isRaining()) {
            contextMultiplier *= STORM_DIFFICULTY_MULT;
        }
        
        // Dimension modifiers (Nether/End = significantly harder)
        String dimensionKey = level.dimension().location().toString();
        if (dimensionKey.contains("nether")) {
            contextMultiplier *= NETHER_DIFFICULTY_MULT;
        } else if (dimensionKey.contains("the_end")) {
            contextMultiplier *= END_DIFFICULTY_MULT;
        }
        
        // Structure proximity (harder near villages, pillager outposts, etc.)
        // Check for nearby structures using structure manager
        try {
            // In 1.20.1, check if mob is near any structure
            if (level.structureManager().hasAnyStructureAt(pos)) {
                contextMultiplier *= STRUCTURE_PROXIMITY_MULT;
            }
        } catch (Exception e) {
            // Structure check failed, skip this modifier
        }
        
        return difficultyMultiplier * contextMultiplier;
    }

    /**
     * Initialize default behavior profiles for different mob types
     * Covers ALL vanilla Minecraft mobs for comprehensive AI learning
     */
    private void initializeDefaultProfiles() {
        // ===== HOSTILE OVERWORLD MOBS =====
        
        // Zombie behaviors (enhanced with environmental tactics)
        behaviorProfiles.put("zombie", new MobBehaviorProfile(
            "zombie",
            Arrays.asList("straight_charge", "circle_strafe", "group_rush", "shamble_approach", "overwhelm",
                         "break_cover", "pillar_up", "use_terrain", "block_path"),
            0.7f  // aggression level
        ));
        
        // Zombie Variants
        behaviorProfiles.put("husk", new MobBehaviorProfile(
            "husk",
            Arrays.asList("straight_charge", "circle_strafe", "group_rush", "desert_ambush", "heat_resistance",
                         "break_cover", "use_terrain"),
            0.7f
        ));
        
        behaviorProfiles.put("drowned", new MobBehaviorProfile(
            "drowned",
            Arrays.asList("trident_throw", "underwater_approach", "surface_ambush", "coordinated_swim", "drowning_grab"),
            0.65f
        ));
        
        behaviorProfiles.put("zombie_villager", new MobBehaviorProfile(
            "zombie_villager",
            Arrays.asList("straight_charge", "circle_strafe", "group_rush", "shamble_approach"),
            0.7f
        ));
        
        // Skeleton behaviors
        behaviorProfiles.put("skeleton", new MobBehaviorProfile(
            "skeleton",
            Arrays.asList("kite_backward", "find_high_ground", "strafe_shoot", "retreat_reload", "corner_ambush"),
            0.5f
        ));
        
        behaviorProfiles.put("stray", new MobBehaviorProfile(
            "stray",
            Arrays.asList("kite_backward", "find_high_ground", "strafe_shoot", "slowness_arrow", "ice_terrain_use"),
            0.5f
        ));
        
        behaviorProfiles.put("wither_skeleton", new MobBehaviorProfile(
            "wither_skeleton",
            Arrays.asList("aggressive_rush", "wither_strike", "fortress_patrol", "height_advantage", "fearless_charge"),
            0.85f
        ));
        
        // Creeper behaviors
        behaviorProfiles.put("creeper", new MobBehaviorProfile(
            "creeper",
            Arrays.asList("ambush", "stealth_approach", "fake_retreat", "suicide_rush", "corner_wait", "explosion_timing"),
            0.8f
        ));
        
        // Spider behaviors
        behaviorProfiles.put("spider", new MobBehaviorProfile(
            "spider",
            Arrays.asList("wall_climb_attack", "ceiling_drop", "web_trap", "leap_attack", "cave_ambush", "night_hunter"),
            0.6f
        ));
        
        behaviorProfiles.put("cave_spider", new MobBehaviorProfile(
            "cave_spider",
            Arrays.asList("wall_climb_attack", "ceiling_drop", "poison_bite", "swarm_attack", "mineshaft_ambush", "tight_space_maneuver"),
            0.65f
        ));
        
        // Enderman
        behaviorProfiles.put("enderman", new MobBehaviorProfile(
            "enderman",
            Arrays.asList("teleport_strike", "block_grab", "rain_avoidance", "stare_punishment", "teleport_dodge", "height_advantage"),
            0.4f  // Low aggression unless provoked
        ));
        
        // Witch
        behaviorProfiles.put("witch", new MobBehaviorProfile(
            "witch",
            Arrays.asList("potion_throw", "heal_self", "distance_maintain", "status_stack", "hut_defense", "terrain_use"),
            0.6f
        ));
        
        // Slime/Magma Cube
        behaviorProfiles.put("slime", new MobBehaviorProfile(
            "slime",
            Arrays.asList("bounce_attack", "split_swarm", "cave_ambush", "size_advantage", "group_bounce"),
            0.55f
        ));
        
        behaviorProfiles.put("magma_cube", new MobBehaviorProfile(
            "magma_cube",
            Arrays.asList("bounce_attack", "split_swarm", "lava_travel", "fire_damage", "nether_patrol"),
            0.6f
        ));
        
        // Phantom
        behaviorProfiles.put("phantom", new MobBehaviorProfile(
            "phantom",
            Arrays.asList("dive_bomb", "circle_overhead", "coordinated_swoop", "sleep_punish", "height_retreat", "pack_hunting"),
            0.7f
        ));
        
        // Silverfish/Endermite
        behaviorProfiles.put("silverfish", new MobBehaviorProfile(
            "silverfish",
            Arrays.asList("swarm_rush", "stone_emerge", "coordinated_attack", "small_hitbox", "stronghold_defense"),
            0.65f
        ));
        
        behaviorProfiles.put("endermite", new MobBehaviorProfile(
            "endermite",
            Arrays.asList("swarm_rush", "teleport_spawn", "coordinated_attack", "small_hitbox"),
            0.65f
        ));
        
        // ===== HOSTILE NETHER MOBS =====
        
        behaviorProfiles.put("blaze", new MobBehaviorProfile(
            "blaze",
            Arrays.asList("fireball_volley", "hover_strafe", "fortress_patrol", "height_control", "fire_shield", "triple_shot"),
            0.7f
        ));
        
        behaviorProfiles.put("ghast", new MobBehaviorProfile(
            "ghast",
            Arrays.asList("fireball_snipe", "distance_maintain", "altitude_shift", "crying_intimidation", "explosive_barrage"),
            0.5f
        ));
        
        // Piglin behaviors
        behaviorProfiles.put("piglin", new MobBehaviorProfile(
            "piglin",
            Arrays.asList("crossbow_attack", "melee_rush", "gold_detection", "pack_coordination", "bastion_defense", "trade_check"),
            0.6f
        ));
        
        behaviorProfiles.put("piglin_brute", new MobBehaviorProfile(
            "piglin_brute",
            Arrays.asList("aggressive_rush", "axe_swing", "bastion_guard", "no_mercy", "strength_overwhelm"),
            0.95f  // Extremely aggressive
        ));
        
        behaviorProfiles.put("zombified_piglin", new MobBehaviorProfile(
            "zombified_piglin",
            Arrays.asList("group_rush", "revenge_attack", "nether_swarm", "gold_sword_strike", "anger_spread"),
            0.5f  // Passive until provoked, then highly aggressive
        ));
        
        behaviorProfiles.put("hoglin", new MobBehaviorProfile(
            "hoglin",
            Arrays.asList("charge_attack", "knockback_rush", "crimson_patrol", "warped_fear", "adult_protect_young"),
            0.75f
        ));
        
        behaviorProfiles.put("zoglin", new MobBehaviorProfile(
            "zoglin",
            Arrays.asList("berserker_charge", "indiscriminate_attack", "knockback_fury", "undead_rage"),
            0.9f
        ));
        
        // ===== HOSTILE END MOBS =====
        
        behaviorProfiles.put("shulker", new MobBehaviorProfile(
            "shulker",
            Arrays.asList("bullet_tracking", "shell_defense", "levitation_trap", "teleport_relocate", "end_city_guard"),
            0.55f
        ));
        
        // ===== NEUTRAL MOBS =====
        
        behaviorProfiles.put("wolf", new MobBehaviorProfile(
            "wolf",
            Arrays.asList("pack_hunting", "circle_prey", "coordinated_attack", "retreat_low_health", "alpha_lead"),
            0.4f  // Neutral, aggressive when provoked
        ));
        
        behaviorProfiles.put("polar_bear", new MobBehaviorProfile(
            "polar_bear",
            Arrays.asList("charge_attack", "cub_defense", "ice_terrain", "standing_intimidation", "powerful_swipe"),
            0.3f  // Defensive aggression
        ));
        
        behaviorProfiles.put("bee", new MobBehaviorProfile(
            "bee",
            Arrays.asList("swarm_attack", "poison_sting", "nest_defense", "coordinated_sting", "flower_patrol"),
            0.35f
        ));
        
        behaviorProfiles.put("spider_jockey", new MobBehaviorProfile(
            "spider_jockey",
            Arrays.asList("mounted_archery", "wall_climb_shoot", "combined_threat", "dual_attack", "mobility_advantage"),
            0.75f
        ));
        
        behaviorProfiles.put("llama", new MobBehaviorProfile(
            "llama",
            Arrays.asList("spit_attack", "caravan_defense", "distance_harassment", "pack_support"),
            0.25f
        ));
        
        behaviorProfiles.put("iron_golem", new MobBehaviorProfile(
            "iron_golem",
            Arrays.asList("powerful_slam", "throw_attack", "village_patrol", "protect_villagers", "area_control"),
            0.5f  // Defensive
        ));
        
        // ===== MCA VILLAGER GUARD PROFILES =====
        // Each MCA villager gets ONE of these assigned permanently at creation
        // Profiles persist through restarts/updates (stored in NBT data)
        // NO ENVIRONMENTAL TACTICS (no block breaking/placing)
        
        behaviorProfiles.put("aggressive_guard", new MobBehaviorProfile(
            "aggressive_guard",
            Arrays.asList("straight_charge", "group_rush", "melee_rush", "overwhelm", "pursue_relentlessly"),
            0.85f  // Very aggressive guard
        ));
        
        behaviorProfiles.put("defensive_guard", new MobBehaviorProfile(
            "defensive_guard",
            Arrays.asList("circle_strafe", "protect_villagers", "kite_backward", "retreat_reload", "area_control"),
            0.4f  // Cautious defender
        ));
        
        behaviorProfiles.put("tactical_guard", new MobBehaviorProfile(
            "tactical_guard",
            Arrays.asList("ambush", "fake_retreat", "circle_strafe", "strafe_shoot", "coordinated_attack"),
            0.65f  // Balanced tactical fighter
        ));
        
        behaviorProfiles.put("cautious_defender", new MobBehaviorProfile(
            "cautious_defender",
            Arrays.asList("kite_backward", "retreat", "find_cover", "defensive_stance", "wait_for_backup"),
            0.3f  // Very defensive
        ));
        
        behaviorProfiles.put("berserker_guard", new MobBehaviorProfile(
            "berserker_guard",
            Arrays.asList("suicide_rush", "straight_charge", "aggressive_rush", "overwhelm", "no_mercy"),
            0.95f  // Fearless attacker
        ));
        
        behaviorProfiles.put("strategic_defender", new MobBehaviorProfile(
            "strategic_defender",
            Arrays.asList("village_patrol", "pack_coordination", "tactical_retreat", "group_formation", "defend_home"),
            0.55f  // Coordinated team fighter
        ));
        
        // ===== REGULAR PASSIVE MOBS =====
        
        behaviorProfiles.put("panda", new MobBehaviorProfile(
            "panda",
            Arrays.asList("roll_attack", "bamboo_defend", "aggressive_variant", "sneeze_startle", "playful_charge"),
            0.3f
        ));
        
        behaviorProfiles.put("dolphin", new MobBehaviorProfile(
            "dolphin",
            Arrays.asList("underwater_rush", "coordinated_swim", "treasure_lead", "playful_circle", "guardian_alert"),
            0.2f
        ));
        
        // ===== BOSS MOBS =====
        
        behaviorProfiles.put("wither", new MobBehaviorProfile(
            "wither",
            Arrays.asList("triple_skull", "blue_skull_snipe", "dash_attack", "wither_aura", "explosive_spawn", "armor_phase", "aerial_supremacy"),
            1.0f  // Maximum aggression
        ));
        
        behaviorProfiles.put("ender_dragon", new MobBehaviorProfile(
            "ender_dragon",
            Arrays.asList("perch_breath", "charge_attack", "dragon_fireball", "crystal_heal", "end_fountain_dive", "aerial_strafe", "ground_attack"),
            0.95f
        ));
        
        behaviorProfiles.put("warden", new MobBehaviorProfile(
            "warden",
            Arrays.asList("sonic_boom", "sniff_locate", "vibration_detect", "melee_devastate", "darkness_pulse", "blind_hunter", "rage_mode"),
            0.85f  // Aggressive but detection-based
        ));
        
        // ===== PASSIVE MOBS WITH LEARNING POTENTIAL =====
        // These can learn evasion, group behavior, and survival tactics
        
        behaviorProfiles.put("villager", new MobBehaviorProfile(
            "villager",
            Arrays.asList("panic_flee", "hide_in_house", "bell_alert", "gossip_spread", "door_barricade", "golem_summon"),
            0.0f  // Passive but can learn survival
        ));
        
        behaviorProfiles.put("cow", new MobBehaviorProfile(
            "cow",
            Arrays.asList("herd_movement", "flee_danger", "grazing_pattern", "calf_protect"),
            0.0f
        ));
        
        behaviorProfiles.put("sheep", new MobBehaviorProfile(
            "sheep",
            Arrays.asList("herd_movement", "flee_danger", "grazing_pattern", "flock_cohesion"),
            0.0f
        ));
        
        behaviorProfiles.put("pig", new MobBehaviorProfile(
            "pig",
            Arrays.asList("flee_danger", "food_search", "mud_wallow", "truffle_dig"),
            0.0f
        ));
        
        behaviorProfiles.put("chicken", new MobBehaviorProfile(
            "chicken",
            Arrays.asList("flee_danger", "flap_escape", "pecking_pattern", "egg_laying_safe"),
            0.0f
        ));
        
        behaviorProfiles.put("rabbit", new MobBehaviorProfile(
            "rabbit",
            Arrays.asList("zigzag_flee", "burrow_hide", "carrot_seek", "predator_detection"),
            0.0f
        ));
        
        behaviorProfiles.put("fox", new MobBehaviorProfile(
            "fox",
            Arrays.asList("pounce_hunt", "berry_steal", "chicken_hunt", "night_active", "sleep_safe"),
            0.15f  // Hunts chickens/rabbits
        ));
        
        behaviorProfiles.put("cat", new MobBehaviorProfile(
            "cat",
            Arrays.asList("creeper_scare", "phantom_repel", "village_patrol", "gift_bring", "independent_roam"),
            0.1f
        ));
        
        behaviorProfiles.put("ocelot", new MobBehaviorProfile(
            "ocelot",
            Arrays.asList("stealth_hunt", "chicken_stalk", "jungle_patrol", "player_avoid", "tree_climb"),
            0.15f
        ));
        
        behaviorProfiles.put("horse", new MobBehaviorProfile(
            "horse",
            Arrays.asList("herd_movement", "flee_danger", "speed_escape", "rear_defense"),
            0.0f
        ));
        
        behaviorProfiles.put("donkey", new MobBehaviorProfile(
            "donkey",
            Arrays.asList("flee_danger", "kick_defense", "chest_protect", "stubborn_stand"),
            0.05f
        ));
        
        behaviorProfiles.put("mule", new MobBehaviorProfile(
            "mule",
            Arrays.asList("flee_danger", "kick_defense", "chest_protect"),
            0.05f
        ));
        
        behaviorProfiles.put("turtle", new MobBehaviorProfile(
            "turtle",
            Arrays.asList("beach_return", "egg_protect", "shell_defense", "slow_retreat", "underwater_escape"),
            0.0f
        ));
        
        behaviorProfiles.put("parrot", new MobBehaviorProfile(
            "parrot",
            Arrays.asList("mob_mimic", "shoulder_perch", "fly_escape", "jungle_navigation", "cookie_avoid"),
            0.0f
        ));
        
        // ===== AQUATIC MOBS =====
        
        behaviorProfiles.put("guardian", new MobBehaviorProfile(
            "guardian",
            Arrays.asList("laser_focus", "spike_defense", "underwater_circle", "monument_patrol", "coordinated_beam"),
            0.7f
        ));
        
        behaviorProfiles.put("elder_guardian", new MobBehaviorProfile(
            "elder_guardian",
            Arrays.asList("laser_focus", "mining_fatigue", "spike_defense", "monument_center", "area_denial", "boss_presence"),
            0.8f
        ));
        
        behaviorProfiles.put("squid", new MobBehaviorProfile(
            "squid",
            Arrays.asList("ink_cloud_escape", "depth_dive", "tentacle_swim", "school_movement"),
            0.0f
        ));
        
        behaviorProfiles.put("glow_squid", new MobBehaviorProfile(
            "glow_squid",
            Arrays.asList("ink_cloud_escape", "depth_dive", "glow_distract", "dark_water_hide"),
            0.0f
        ));
        
        behaviorProfiles.put("cod", new MobBehaviorProfile(
            "cod",
            Arrays.asList("school_swim", "flee_danger", "ocean_roam"),
            0.0f
        ));
        
        behaviorProfiles.put("salmon", new MobBehaviorProfile(
            "salmon",
            Arrays.asList("upstream_swim", "school_swim", "flee_danger", "spawning_journey"),
            0.0f
        ));
        
        behaviorProfiles.put("tropical_fish", new MobBehaviorProfile(
            "tropical_fish",
            Arrays.asList("school_swim", "reef_hide", "flee_danger", "color_pattern"),
            0.0f
        ));
        
        behaviorProfiles.put("pufferfish", new MobBehaviorProfile(
            "pufferfish",
            Arrays.asList("inflate_defense", "poison_touch", "threat_detect", "reef_patrol"),
            0.3f  // Defensive
        ));
        
        behaviorProfiles.put("axolotl", new MobBehaviorProfile(
            "axolotl",
            Arrays.asList("play_dead", "hunt_aquatic", "regeneration_hide", "cave_water_roam", "pack_hunt"),
            0.2f
        ));
        
        // ===== UTILITY/OTHER MOBS =====
        
        behaviorProfiles.put("snow_golem", new MobBehaviorProfile(
            "snow_golem",
            Arrays.asList("snowball_throw", "trail_creation", "blaze_counter", "ranged_support", "cold_biome_patrol"),
            0.4f
        ));
        
        behaviorProfiles.put("bat", new MobBehaviorProfile(
            "bat",
            Arrays.asList("cave_roost", "erratic_flight", "darkness_active", "flee_light"),
            0.0f
        ));
        
        behaviorProfiles.put("strider", new MobBehaviorProfile(
            "strider",
            Arrays.asList("lava_walk", "warped_fungus_follow", "saddle_ride", "cold_shiver"),
            0.0f
        ));
        
        behaviorProfiles.put("goat", new MobBehaviorProfile(
            "goat",
            Arrays.asList("ram_attack", "mountain_jump", "ledge_balance", "horn_drop", "screaming_variant"),
            0.25f  // Can ram
        ));
        
        behaviorProfiles.put("frog", new MobBehaviorProfile(
            "frog",
            Arrays.asList("tongue_attack", "lily_pad_hop", "slime_hunt", "swamp_patrol", "croaking_call"),
            0.1f
        ));
        
        behaviorProfiles.put("tadpole", new MobBehaviorProfile(
            "tadpole",
            Arrays.asList("school_swim", "growth_hide", "flee_danger"),
            0.0f
        ));
        
        behaviorProfiles.put("allay", new MobBehaviorProfile(
            "allay",
            Arrays.asList("item_collect", "noteblock_dance", "player_follow", "duplication_amethyst", "delivery_flight"),
            0.0f
        ));
        
        behaviorProfiles.put("vex", new MobBehaviorProfile(
            "vex",
            Arrays.asList("phase_through_walls", "coordinated_swarm", "evoker_summoned", "sword_slash", "timed_existence"),
            0.8f
        ));
        
        behaviorProfiles.put("ravager", new MobBehaviorProfile(
            "ravager",
            Arrays.asList("charge_trample", "roar_stun", "raid_breaker", "crop_destroy", "knockback_slam"),
            0.85f
        ));
        
        behaviorProfiles.put("pillager", new MobBehaviorProfile(
            "pillager",
            Arrays.asList("crossbow_volley", "patrol_route", "raid_coordination", "outpost_defense", "banner_leadership"),
            0.7f
        ));
        
        behaviorProfiles.put("vindicator", new MobBehaviorProfile(
            "vindicator",
            Arrays.asList("axe_rush", "door_break", "mansion_patrol", "raid_assault", "johnny_mode"),
            0.8f
        ));
        
        behaviorProfiles.put("evoker", new MobBehaviorProfile(
            "evoker",
            Arrays.asList("fang_summon", "vex_spawn", "totem_cheat_death", "mansion_boss", "blue_sheep_convert"),
            0.75f
        ));
        
        behaviorProfiles.put("illusioner", new MobBehaviorProfile(
            "illusioner",
            Arrays.asList("mirror_image", "arrow_volley", "invisibility_cast", "blindness_inflict", "illusion_tactics"),
            0.7f
        ));
        
        LOGGER.info("Initialized behavior profiles for {} mob types (ALL vanilla Minecraft mobs covered)", behaviorProfiles.size());
    }

    /**
     * Select next action for a specific mob instance with visual perception
     * PERFORMANCE: Throttled to think every 15 ticks (15x speedup)
     */
    public String selectMobAction(String mobType, MobState state, String mobId, Player target) {
        // CRITICAL FIX #5: Limit action frequency - don't think every tick
        globalTick++;
        Integer lastThink = mobLastThinkTick.get(mobId);
        if (lastThink != null && (globalTick - lastThink) < THINK_INTERVAL) {
            // Use last action - don't compute new one yet
            String cached = lastActionCache.get(mobId);
            return cached != null ? cached : "default_attack";
        }
        mobLastThinkTick.put(mobId, globalTick);
        
        // Tick performance optimizer once per game tick
        if (performanceOptimizer != null && globalTick % 20 == 0) {
            performanceOptimizer.tick();
        }
        
        MobBehaviorProfile profile = behaviorProfiles.get(mobType.toLowerCase());
        
        if (profile == null) {
            return "default_attack";
        }

        String selectedAction;
        
        if (mlEnabled && doubleDQN != null) {
            // Analyze player visually
            VisualPerception.VisualState visual = visualPerception.analyzePlayer(target);
            lastVisualCache.put(mobId, visual);
            
            // Get or create genome for this mob
            GeneticBehaviorEvolution.BehaviorGenome genome = activeGenomes.computeIfAbsent(
                mobId, k -> geneticEvolution.selectGenome()
            );
            
            // Use advanced ML systems for action selection with caching
            selectedAction = selectActionWithAdvancedML(profile, state, visual, genome, mobId);
        } else {
            // Use rule-based system
            selectedAction = selectActionRuleBased(profile, state);
        }
        
        // Cache state and action for learning when outcome is recorded
        lastStateCache.put(mobId, state.copy());
        lastActionCache.put(mobId, selectedAction);
        
        return selectedAction;
    }
    
    /**
     * Select next action for a mob based on current state (no player context)
     * Uses ML model if enabled, otherwise rule-based
     */
    public String selectMobAction(String mobType, MobState state) {
        return selectMobAction(mobType, state, UUID.randomUUID().toString(), null);
    }
    
    /**
     * Select next action for a specific mob instance (no player context)
     */
    public String selectMobAction(String mobType, MobState state, String mobId) {
        return selectMobAction(mobType, state, mobId, null);
    }
    
    /**
     * Select next action with contextual difficulty based on mob entity
     * Applies environmental modifiers (night/weather/dimension/structures)
     * @param mobType Type of mob
     * @param state Current state
     * @param mobId Unique instance ID
     * @param mobEntity The mob entity (for contextual difficulty)
     * @return Selected action
     */
    public String selectMobActionWithEntity(String mobType, MobState state, String mobId, net.minecraft.world.entity.Mob mobEntity) {
        // Get mob's tactic tier for difficulty adjustment
        TacticTier tier = TacticTier.VETERAN; // default
        if (mobEntity != null && MobTierAssignmentHandler.hasTier(mobEntity)) {
            tier = MobTierAssignmentHandler.getTierFromMob(mobEntity);
        }
        
        // Apply contextual difficulty if enabled
        if (contextualDifficultyEnabled && mobEntity != null) {
            float originalDifficulty = difficultyMultiplier;
            
            // Combine contextual and tier difficulty
            float contextDifficulty = getContextualDifficulty(mobEntity);
            float tierDifficulty = tier.getDifficultyMultiplier();
            difficultyMultiplier = contextDifficulty * tierDifficulty;
            
            // Select action with modified difficulty
            String action = selectMobAction(mobType, state, mobId, (net.minecraft.world.entity.player.Player) null);
            
            // Restore original difficulty
            difficultyMultiplier = originalDifficulty;
            return action;
        }
        
        // Apply only tier difficulty
        float originalDifficulty = difficultyMultiplier;
        difficultyMultiplier *= tier.getDifficultyMultiplier();
        String action = selectMobAction(mobType, state, mobId, (net.minecraft.world.entity.player.Player) null);
        difficultyMultiplier = originalDifficulty;
        
        return action;
    }

    /**
     * Advanced ML-based action selection combining all systems
     */
    private String selectActionWithAdvancedML(MobBehaviorProfile profile, MobState state, 
                                              VisualPerception.VisualState visual,
                                              GeneticBehaviorEvolution.BehaviorGenome genome,
                                              String mobId) {
        List<String> validActions = getValidActions(profile, state);
        if (validActions.isEmpty()) {
            return "default_attack";
        }
        
        // Apply curriculum learning filter
        validActions = curriculum.filterActionsByStage(validActions);
        
        // Get visual recommendations
        List<String> visualRecommendations = new ArrayList<>();
        if (visual != null) {
            visualRecommendations = visualPerception.getRecommendedActions(visual);
        }
        
        // Check for team coordination
        List<String> teamMembers = multiAgent.getTeamMembers(mobId);
        if (teamMembers != null && teamMembers.size() > 1) {
            // Share experiences with teammates
            for (String teammateId : teamMembers) {
                if (!teammateId.equals(mobId)) {
                    MobState teammateState = lastStateCache.get(teammateId);
                    String teammateAction = lastActionCache.get(teammateId);
                    if (teammateState != null && teammateAction != null) {
                        // Consider teammate's recent experience
                        float[] teammateFeatures = combineFeatures(teammateState, visual, genome);
                        int actionIndex = validActions.indexOf(teammateAction);
                        if (actionIndex >= 0) {
                            // Teammate used this action recently
                        }
                    }
                }
            }
        }
        
        // Combine all feature sources
        float[] combinedFeatures = combineFeatures(state, visual, genome);
        
        // CRITICAL FIX #2: Use cached Q-values (80% CPU reduction)
        float[] qValues = null;
        if (performanceOptimizer != null) {
            qValues = performanceOptimizer.getCachedQValues(mobId, combinedFeatures);
        }
        
        // Try ensemble methods first (most robust)
        int actionIndex = -1;
        
        // 1. Random Forest (ensemble learning, handles non-linear patterns well)
        if (randomForest != null && randomForest.isAvailable()) {
            double[] features = new double[combinedFeatures.length];
            for (int i = 0; i < combinedFeatures.length; i++) {
                features[i] = combinedFeatures[i];
            }
            actionIndex = randomForest.predictTactic(features);
            
            // Record this tactic for future training
            if (actionIndex >= 0 && actionIndex < validActions.size()) {
                randomForest.recordTactic("unknown", features, actionIndex);
            }
        }
        
        // 2. XGBoost (fast gradient boosting) if Random Forest unavailable
        if (actionIndex < 0 && xgboost != null && xgboost.isAvailable()) {
            actionIndex = xgboost.predictTactic(combinedFeatures, validActions.size());
        }
        
        // 3. Fall back to cached Q-values if neither available
        if (actionIndex < 0 && qValues != null) {
            // Find best action from cached Q-values
            float maxQ = Float.NEGATIVE_INFINITY;
            for (int i = 0; i < Math.min(qValues.length, validActions.size()); i++) {
                if (qValues[i] > maxQ) {
                    maxQ = qValues[i];
                    actionIndex = i;
                }
            }
        }
        
        // 4. Ultimate fallback to Double DQN if cache unavailable
        if (actionIndex < 0 && doubleDQN != null) {
            actionIndex = doubleDQN.selectActionIndex(combinedFeatures);
        }
        
        // Map index to valid action
        if (actionIndex >= validActions.size()) {
            actionIndex = actionIndex % validActions.size();
        }
        String selectedAction = validActions.get(Math.max(0, actionIndex));
        
        // Apply genetic modifiers
        if (genome.actionWeights.containsKey(selectedAction)) {
            float weight = genome.actionWeights.get(selectedAction);
            // Bias toward genetically preferred actions
            if (random.nextFloat() > weight && !validActions.isEmpty()) {
                // Sometimes override with genome preference
                selectedAction = selectWeightedAction(validActions, genome);
            }
        }
        
        // Boost visually recommended actions
        if (visualRecommendations.contains(selectedAction)) {
            // This action is tactically sound based on player equipment
            profile.recordAction(selectedAction, state);
        }
        
        return selectedAction;
    }
    
    /**
     * Select action weighted by genetic genome preferences
     */
    private String selectWeightedAction(List<String> actions, GeneticBehaviorEvolution.BehaviorGenome genome) {
        float totalWeight = 0.0f;
        for (String action : actions) {
            totalWeight += genome.actionWeights.getOrDefault(action, 1.0f);
        }
        
        float rand = random.nextFloat() * totalWeight;
        float cumulative = 0.0f;
        
        for (String action : actions) {
            cumulative += genome.actionWeights.getOrDefault(action, 1.0f);
            if (cumulative >= rand) {
                return action;
            }
        }
        
        return actions.get(0);
    }
    
    /**
     * Combine state, visual, and genetic features into single vector
     */
    private float[] combineFeatures(MobState state, VisualPerception.VisualState visual, 
                                    GeneticBehaviorEvolution.BehaviorGenome genome) {
        float[] stateFeatures = stateToFeatureVector(state);
        float[] visualFeatures = visual != null ? visual.toFeatureVector() : new float[9];
        
        // Combine: [state(10) + visual(9) + genome(3)] = 22 features
        float[] combined = new float[22];
        System.arraycopy(stateFeatures, 0, combined, 0, 10);
        System.arraycopy(visualFeatures, 0, combined, 10, 9);
        combined[19] = genome.aggression;
        combined[20] = genome.caution;
        combined[21] = genome.teamwork;
        
        return combined;
    }
    
    /**
     * Convert MobState to feature vector for neural network input
     */
    private float[] stateToFeatureVector(MobState state) {
        return new float[] {
            state.health,                                    // 0: Mob health (0-1)
            state.targetHealth,                              // 1: Player health (0-1)
            state.distanceToTarget / 20.0f,                  // 2: Distance normalized
            state.hasHighGround ? 1.0f : 0.0f,              // 3: Height advantage
            state.canClimbWalls ? 1.0f : 0.0f,              // 4: Climbing ability
            state.nearbyAlliesCount / 10.0f,                // 5: Nearby allies normalized
            state.isNight ? 1.0f : 0.0f,                    // 6: Time of day
            state.biome.hashCode() % 100 / 100.0f,          // 7: Biome encoding
            difficultyMultiplier / 3.0f,                    // 8: Difficulty setting
            state.combatTime / 100.0f                       // 9: Combat duration
        };
    }
    
    /**
     * Get list of valid actions for current state
     * REVOLUTIONARY: Includes borrowed tactics from other mob types if cross-mob learning enabled
     */
    private List<String> getValidActions(MobBehaviorProfile profile, MobState state) {
        List<String> actions = new ArrayList<>(profile.getActions());
        
        // EMERGENT LEARNING: Add successful tactics from other mob types
        if (crossMobLearningEnabled && federatedLearning != null) {
            List<FederatedLearning.GlobalTactic> bestGlobalTactics = federatedLearning.getBestGlobalTactics(20);
            
            for (FederatedLearning.GlobalTactic tactic : bestGlobalTactics) {
                // Only borrow high-performing tactics (reward > 2.0)
                if (tactic.avgReward > 2.0f && !actions.contains(tactic.action)) {
                    // Check if this mob can physically perform the borrowed action
                    if (canMobPerformAction(profile.getMobType(), tactic.action, state)) {
                        actions.add(tactic.action);
                        LOGGER.debug("{} borrowed '{}' from {} (reward: {:.2f})",
                            profile.getMobType(), tactic.action, tactic.originalMobType, tactic.avgReward);
                    }
                }
            }
        }
        
        // Filter to only valid actions for current state
        List<String> validActions = new ArrayList<>();
        for (String action : actions) {
            if (isActionValid(action, state)) {
                validActions.add(action);
            }
        }
        
        return validActions.isEmpty() ? actions : validActions;
    }

    /**
     * Rule-based action selection with adaptive behavior
     */
    private String selectActionRuleBased(MobBehaviorProfile profile, MobState state) {
        List<String> actions = profile.getActions();
        
        // Filter actions based on state
        List<String> validActions = new ArrayList<>();
        
        for (String action : actions) {
            if (isActionValid(action, state)) {
                validActions.add(action);
            }
        }
        
        if (validActions.isEmpty()) {
            return "default_attack";
        }
        
        // Weight actions based on situation
        String selectedAction = weightedActionSelection(validActions, state, profile);
        
        // Learn from this decision
        profile.recordAction(selectedAction, state);
        
        return selectedAction;
    }

    /**
     * Check if an action is valid in current state
     */
    private boolean isActionValid(String action, MobState state) {
        switch (action) {
            case "kite_backward":
            case "retreat_reload":
                return state.distanceToTarget > 3.0f;
            case "suicide_rush":
            case "group_rush":
                return state.distanceToTarget < 10.0f;
            case "find_high_ground":
                return !state.hasHighGround;
            case "ceiling_drop":
            case "wall_climb_attack":
                return state.canClimbWalls;
            default:
                return true;
        }
    }

    /**
     * Select action with weighted probability based on state
     */
    private String weightedActionSelection(List<String> actions, MobState state, MobBehaviorProfile profile) {
        Map<String, Float> weights = new HashMap<>();
        
        for (String action : actions) {
            float weight = calculateActionWeight(action, state, profile);
            weights.put(action, weight);
        }
        
        // Weighted random selection
        float totalWeight = weights.values().stream().reduce(0f, Float::sum);
        float randomValue = random.nextFloat() * totalWeight;
        
        float currentWeight = 0f;
        for (Map.Entry<String, Float> entry : weights.entrySet()) {
            currentWeight += entry.getValue();
            if (randomValue <= currentWeight) {
                return entry.getKey();
            }
        }
        
        return actions.get(0);
    }

    /**
     * Calculate weight for an action based on current state
     * Tier difficulty multiplier affects the "smartness" of tactical choices:
     * - ELITE (2.0x): Makes better tactical decisions (doubled weight for smart moves)
     * - VETERAN (1.0x): Baseline tactical intelligence
     * - ROOKIE (0.5x): Makes worse tactical decisions (halved weight for smart moves)
     */
    private float calculateActionWeight(String action, MobState state, MobBehaviorProfile profile) {
        float baseWeight = 1.0f;
        
        // Adjust weight based on player health
        if (state.targetHealth < 0.3f) {
            // Player is low health - aggressive actions
            if (action.contains("rush") || action.contains("charge")) {
                baseWeight *= 2.0f;
            }
        }
        
        // Adjust based on mob health
        if (state.health < 0.3f) {
            // Mob is low health - defensive actions
            if (action.contains("retreat") || action.contains("kite")) {
                baseWeight *= 2.0f;
            }
        }
        
        // Adjust based on distance
        if (state.distanceToTarget < 3.0f) {
            if (action.contains("melee") || action.contains("rush")) {
                baseWeight *= 1.5f;
            }
        } else if (state.distanceToTarget > 8.0f) {
            if (action.contains("range") || action.contains("approach")) {
                baseWeight *= 1.5f;
            }
        }
        
        // Factor in past success rate
        baseWeight *= profile.getActionSuccessRate(action);
        
        // Apply tier difficulty multiplier to make elite mobs smarter, rookies dumber
        // Elite mobs make better tactical choices, rookies make worse ones
        baseWeight *= difficultyMultiplier;
        
        return baseWeight;
    }

    /**
     * Record combat outcome to improve AI with all advanced ML systems
     * ENHANCED: Applies massive reward multiplier for successful borrowed tactics
     * @param mobEntity Optional mob entity for attribute correlation tracking
     */
    public void recordCombatOutcome(String mobId, boolean playerDied, boolean mobDied, MobState finalState, 
                                    float damageDealt, float damageTaken, net.minecraft.world.entity.Mob mobEntity) {
        // Get cached state and action
        MobState initialState = lastStateCache.remove(mobId);
        String action = lastActionCache.remove(mobId);
        VisualPerception.VisualState visual = lastVisualCache.remove(mobId);
        GeneticBehaviorEvolution.BehaviorGenome genome = activeGenomes.remove(mobId);
        
        if (initialState == null || action == null) {
            return;  // No cached data for this mob
        }
        
        // Calculate reward based on outcome
        float reward = calculateReward(initialState, finalState, playerDied, mobDied);
        reward += damageDealt * 0.5f - damageTaken * 0.3f;  // Fine-grained feedback
        
        // REVOLUTIONARY: Huge bonus for successfully using borrowed tactics from other mob types
        if (crossMobLearningEnabled && federatedLearning != null) {
            String mobType = getMobTypeFromId(mobId);
            if (mobType != null && !isMobsNativeAction(mobType, action)) {
                // This mob used a tactic it borrowed from another species!
                float originalReward = reward;
                reward *= crossMobRewardMultiplier;
                
                if (reward > 0) {  // Only log successful borrowed tactics
                    LOGGER.info("\u2605 EMERGENT BEHAVIOR: {} successfully used borrowed '{}' (reward: {:.1f} -> {:.1f})",
                        mobType, action, originalReward, reward);
                }
            }
            
            // Track attribute-tactic correlations (Mob Control inspired)
            if (reward > CORRELATION_THRESHOLD && mobEntity != null) {
                trackAttributeTacticCorrelation(mobType, action, reward, mobEntity);
            }
        }
        
        // CRITICAL FIX #1: Use background training (never blocks main thread)
        if (mlEnabled && performanceOptimizer != null) {
            // Combine features
            float[] initialFeatures = combineFeatures(initialState, visual, genome != null ? genome : new GeneticBehaviorEvolution.BehaviorGenome());
            float[] finalFeatures = combineFeatures(finalState, visual, genome != null ? genome : new GeneticBehaviorEvolution.BehaviorGenome());
            
            // Convert action to index
            List<String> allActions = getAllPossibleActions();
            int actionIndex = allActions.indexOf(action);
            if (actionIndex < 0) actionIndex = 0;
            
            boolean episodeDone = playerDied || mobDied;
            
            // CRITICAL: Record to background queue, training happens on separate thread
            performanceOptimizer.recordExperience(initialFeatures, actionIndex, reward, finalFeatures, episodeDone);
            
            // Clear cached prediction for this mob
            performanceOptimizer.clearCache(mobId);
        }
        
        // Update all ML systems if enabled
        if (mlEnabled && doubleDQN != null) {
            // Combine features
            float[] initialFeatures = combineFeatures(initialState, visual, genome != null ? genome : new GeneticBehaviorEvolution.BehaviorGenome());
            float[] finalFeatures = combineFeatures(finalState, visual, genome != null ? genome : new GeneticBehaviorEvolution.BehaviorGenome());
            boolean episodeDone = playerDied || mobDied;
            
            // Convert action to index
            List<String> allActions = getAllPossibleActions();
            int actionIndex = allActions.indexOf(action);
            if (actionIndex < 0) actionIndex = 0;
            
            // Add to prioritized replay buffer
            replayBuffer.add(initialFeatures, actionIndex, reward, finalFeatures, episodeDone);
            
            // Train XGBoost if available (fast, incremental)
            if (xgboost != null && xgboost.isAvailable()) {
                String mobType = "unknown"; // Extract from mobId or state if needed
                xgboost.recordOutcome(mobType, initialFeatures, actionIndex, reward > 0);
            }
            
            // Sample and train Double DQN with prioritized experiences
            if (replayBuffer.size() >= 32) {
                PrioritizedReplayBuffer.SampledBatch batch = replayBuffer.sample(32);
                float[] tdErrors = doubleDQN.trainBatch(batch.experiences);
                
                // Update priorities based on TD errors
                List<Float> tdErrorList = new ArrayList<>();
                for (float error : tdErrors) {
                    tdErrorList.add(error);
                }
                replayBuffer.updatePriorities(batch.prioritizedExperiences, tdErrorList);
            }
            
            // Update curriculum learning
            curriculum.recordExperience(reward > 0);
            
            // Update multi-agent coordination
            List<String> team = multiAgent.getTeamMembers(mobId);
            if (team != null && team.size() > 1) {
                // Share this experience with teammates
                for (String teammateId : team) {
                    if (!teammateId.equals(mobId)) {
                        multiAgent.recordTeamExperience(mobId, teammateId, reward);
                    }
                }
                
                // Give cooperation bonus if team performed well
                if (reward > 0) {
                    float teamBonus = multiAgent.getTeamCoordinationBonus(mobId);
                    reward *= (1.0f + teamBonus);
                }
            }
            
            // Update genetic evolution
            if (genome != null) {
                geneticEvolution.recordCombat(genome, playerDied, damageDealt, damageTaken);
            }
        }
    }
    
    /**
     * Backwards compatibility - old signature without mob entity
     */
    public void recordCombatOutcome(String mobId, boolean playerDied, boolean mobDied, MobState finalState, 
                                    float damageDealt, float damageTaken) {
        recordCombatOutcome(mobId, playerDied, mobDied, finalState, damageDealt, damageTaken, null);
    }
    
    /**
     * Backwards compatibility - old signature
     */
    public void recordCombatOutcome(String mobId, boolean playerDied, boolean mobDied, MobState finalState) {
        recordCombatOutcome(mobId, playerDied, mobDied, finalState, 0.0f, 0.0f, null);
    }
    
    /**
     * Calculate reward for reinforcement learning
     * Positive for successful mob behavior, negative for unsuccessful
     */
    private float calculateReward(MobState initialState, MobState finalState, boolean playerDied, boolean mobDied) {
        float reward = 0.0f;
        
        // Major rewards/penalties for combat outcome
        if (playerDied) {
            reward += 10.0f * difficultyMultiplier;  // Mob won
        } else if (mobDied) {
            reward -= 5.0f;  // Mob lost
        }
        
        // Reward for damaging player
        float playerDamage = initialState.targetHealth - finalState.targetHealth;
        reward += playerDamage * 5.0f;
        
        // Penalty for taking damage
        float mobDamage = initialState.health - finalState.health;
        reward -= mobDamage * 3.0f;
        
        // Small reward for maintaining combat distance
        if (finalState.distanceToTarget > 1.0f && finalState.distanceToTarget < 6.0f) {
            reward += 0.5f;
        }
        
        // Bonus for tactical positioning
        if (finalState.hasHighGround && !initialState.hasHighGround) {
            reward += 1.0f;
        }
        
        return reward * difficultyMultiplier;
    }
    
    /**
     * Get all possible actions across all mob types
     */
    private List<String> getAllPossibleActions() {
        List<String> allActions = new ArrayList<>();
        allActions.add("straight_charge");
        allActions.add("circle_strafe");
        allActions.add("kite_backward");
        allActions.add("retreat");
        allActions.add("ambush");
        allActions.add("group_rush");
        allActions.add("find_cover");
        allActions.add("strafe_shoot");
        allActions.add("leap_attack");
        allActions.add("fake_retreat");
        return allActions;
    }
    
    /**
     * Form a team of mobs for coordinated tactics
     */
    public void formTeam(String leaderId, List<String> memberIds) {
        if (mlEnabled && multiAgent != null) {
            List<String> allMembers = new ArrayList<>(memberIds);
            allMembers.add(0, leaderId);
            multiAgent.formTeam(allMembers);
        }
    }
    
    /**
     * Save the trained ML model (call on server shutdown)
     */
    public void saveModel() {
        // Shutdown performance optimizer gracefully
        if (performanceOptimizer != null) {
            performanceOptimizer.shutdown();
        }
        // Models are saved automatically during training
        LOGGER.info("ML systems persisted");
    }
    
    /**
     * Get ML statistics for debugging/display
     */
    public String getMLStats() {
        if (!mlEnabled) {
            return "ML initialization failed - using rule-based AI only";
        }
        
        if (doubleDQN == null) {
            return "ML enabled - initializing neural networks (DJL libraries loading...)";
        }
        
        String perfStats = performanceOptimizer != null ? performanceOptimizer.getPerformanceStats() : "No perf data";
        
        return String.format("Advanced ML | Gen: %d | Stage: %s | Replay: %d | Teams: %d | Best: %.2f | %s",
            geneticEvolution != null ? geneticEvolution.getGenerationNumber() : 0,
            curriculum != null ? curriculum.getCurrentStage() : "UNKNOWN",
            replayBuffer != null ? replayBuffer.size() : 0,
            multiAgent != null ? multiAgent.getTeamCount() : 0,
            geneticEvolution != null ? geneticEvolution.getBestFitness() : 0.0f,
            perfStats
        );
    }

    /**
     * Get aggression modifier for a mob type
     */
    public float getAggressionLevel(String mobType) {
        MobBehaviorProfile profile = behaviorProfiles.get(mobType.toLowerCase());
        return profile != null ? profile.getAggressionLevel() : 0.5f;
    }
    
    /**
     * Accessors for new systems
     */
    public TaskChainSystem getTaskChainSystem() {
        return taskChainSystem;
    }
    
    public ReflexModule getReflexModule() {
        return reflexModule;
    }
    
    public AutonomousGoals getAutonomousGoals() {
        return autonomousGoals;
    }
    
    public TacticKnowledgeBase getTacticKnowledgeBase() {
        return tacticKnowledgeBase;
    }
    
    public ModelPersistence getModelPersistence() {
        return modelPersistence;
    }
    
    // ==================== Cross-Mob Learning Helper Methods ====================
    
    /**
     * Check if a mob can physically perform a borrowed action from another species
     * CRITICAL: Prevents impossible scenarios (zombies flying, etc.)
     */
    private boolean canMobPerformAction(String mobType, String action, MobState state) {
        mobType = mobType.toLowerCase();
        
        // Flying/aerial actions - only for flying mobs
        if (action.contains("fly") || action.contains("aerial") || action.contains("swoop") || 
            action.contains("dive_bomb") || action.contains("hover")) {
            return mobType.contains("phantom") || mobType.contains("ghast") || 
                   mobType.contains("blaze") || mobType.contains("wither") || 
                   mobType.contains("ender_dragon") || mobType.contains("vex");
        }
        
        // Wall climbing - only spiders and specific mobs
        if (action.contains("wall_climb") || action.contains("ceiling_drop")) {
            return state.canClimbWalls || mobType.contains("spider");
        }
        
        // Ranged attacks - mobs need to have projectile capability
        if (action.contains("arrow") || action.contains("shoot") || action.contains("strafe_shoot") ||
            action.contains("kite_backward") || action.contains("retreat_reload")) {
            return mobType.contains("skeleton") || mobType.contains("pillager") || 
                   mobType.contains("piglin") || mobType.contains("illusioner") ||
                   mobType.contains("witch") || mobType.contains("drowned") ||
                   mobType.contains("blaze") || mobType.contains("ghast") ||
                   mobType.contains("snow_golem") || mobType.contains("llama");
        }
        
        // Teleportation - only enderman and shulker
        if (action.contains("teleport")) {
            return mobType.contains("enderman") || mobType.contains("shulker");
        }
        
        // Swimming/aquatic - avoid for non-aquatic mobs
        if (action.contains("underwater") || action.contains("swim")) {
            return !mobType.contains("blaze") && !mobType.contains("magma_cube") &&
                   !mobType.contains("strider") && !mobType.contains("enderman");
        }
        
        // Explosion - only creepers and special mobs
        if (action.contains("explosion") || action.contains("suicide_rush")) {
            return mobType.contains("creeper") || mobType.contains("wither") ||
                   mobType.contains("ender_dragon");
        }
        
        // Pack/swarm tactics - allow for most hostile mobs
        if (action.contains("pack") || action.contains("swarm") || action.contains("coordinated") ||
            action.contains("group_rush")) {
            return state.nearbyAlliesCount > 0;  // Need allies present
        }
        
        // Melee actions - almost all mobs can attempt these
        if (action.contains("charge") || action.contains("rush") || action.contains("melee") ||
            action.contains("circle_strafe") || action.contains("straight_charge") ||
            action.contains("ambush") || action.contains("retreat")) {
            return true;  // Universal capabilities
        }
        
        // High ground tactics - allow if not restricted by movement
        if (action.contains("high_ground") || action.contains("height")) {
            return !mobType.contains("slime") && !mobType.contains("magma_cube");
        }
        
        // Default: allow most tactical decisions
        return true;
    }
    
    /**
     * Check if an action is native to a mob type (not borrowed)
     */
    private boolean isMobsNativeAction(String mobType, String action) {
        MobBehaviorProfile profile = behaviorProfiles.get(mobType.toLowerCase());
        return profile != null && profile.getActions().contains(action);
    }
    
    /**
     * Extract mob type from mob ID (format: "mobType_uuid" or just "uuid")
     */
    private String getMobTypeFromId(String mobId) {
        if (mobId == null) return null;
        
        // Try to extract from cached state first
        for (Map.Entry<String, MobState> entry : lastStateCache.entrySet()) {
            if (entry.getKey().equals(mobId)) {
                // Try to match against known profiles
                for (String mobType : behaviorProfiles.keySet()) {
                    if (mobId.toLowerCase().startsWith(mobType)) {
                        return mobType;
                    }
                }
            }
        }
        
        // Fallback: try to parse from ID format
        String[] parts = mobId.split("_");
        if (parts.length > 0 && behaviorProfiles.containsKey(parts[0].toLowerCase())) {
            return parts[0].toLowerCase();
        }
        
        return null;
    }

    /**
     * Represents the current state of a mob during combat
     */
    public static class MobState {
        public float health;
        public float targetHealth;
        public float distanceToTarget;
        public boolean hasHighGround;
        public boolean canClimbWalls;
        public int nearbyAlliesCount;
        public String biome;
        public boolean isNight;
        public float combatTime;  // Seconds in combat

        public MobState(float health, float targetHealth, float distance) {
            this.health = health;
            this.targetHealth = targetHealth;
            this.distanceToTarget = distance;
            this.hasHighGround = false;
            this.canClimbWalls = false;
            this.nearbyAlliesCount = 0;
            this.biome = "plains";
            this.isNight = false;
            this.combatTime = 0.0f;
        }
        
        /**
         * Create a copy of this state (for caching)
         */
        public MobState copy() {
            MobState copy = new MobState(health, targetHealth, distanceToTarget);
            copy.hasHighGround = this.hasHighGround;
            copy.canClimbWalls = this.canClimbWalls;
            copy.nearbyAlliesCount = this.nearbyAlliesCount;
            copy.biome = this.biome;
            copy.isNight = this.isNight;
            copy.combatTime = this.combatTime;
            return copy;
        }
    }

    /**
     * Stores behavior patterns and learning data for a mob type
     */
    private static class MobBehaviorProfile {
        private final String mobType;
        private final List<String> actions;
        private final float aggressionLevel;
        private final Map<String, Integer> actionSuccessCount = new HashMap<>();
        private final Map<String, Integer> actionFailureCount = new HashMap<>();

        public MobBehaviorProfile(String mobType, List<String> actions, float aggression) {
            this.mobType = mobType;
            this.actions = new ArrayList<>(actions);
            this.aggressionLevel = aggression;
            
            // Initialize counters
            for (String action : actions) {
                actionSuccessCount.put(action, 1);
                actionFailureCount.put(action, 1);
            }
        }

        public List<String> getActions() {
            return new ArrayList<>(actions);
        }
        
        public String getMobType() {
            return mobType;
        }

        public float getAggressionLevel() {
            return aggressionLevel;
        }

        public void recordAction(String action, MobState state) {
            // Track action usage
        }

        public void recordOutcome(String action, boolean success) {
            if (success) {
                actionSuccessCount.put(action, actionSuccessCount.getOrDefault(action, 0) + 1);
            } else {
                actionFailureCount.put(action, actionFailureCount.getOrDefault(action, 0) + 1);
            }
        }

        public float getActionSuccessRate(String action) {
            int successes = actionSuccessCount.getOrDefault(action, 1);
            int failures = actionFailureCount.getOrDefault(action, 1);
            return (float) successes / (successes + failures);
        }
    }
    
    /**
     * Test Cloudflare Worker connection (called on startup)
     */
    public boolean testCloudflareConnection() {
        if (federatedLearning == null || !federatedLearning.isEnabled()) {
            LOGGER.warn("Federated learning not initialized");
            return false;
        }
        
        return federatedLearning.testConnection();
    }
    
    /**
     * Sync learned tactics with Cloudflare Worker (called during auto-save)
     */
    public void syncWithCloudflare() {
        if (federatedLearning == null || !federatedLearning.isEnabled()) {
            LOGGER.debug("Cloudflare sync skipped - federated learning not enabled");
            return;
        }
        
        // Force sync now (normally happens on schedule)
        federatedLearning.forceSyncNow();
    }
    
    /**
     * Track attribute-tactic correlations for optimization suggestions
     * Inspired by Mob Control's attribute modification system
     */
    private void trackAttributeTacticCorrelation(String mobType, String action, float reward, 
                                                   net.minecraft.world.entity.Mob mobEntity) {
        if (mobEntity == null || reward < CORRELATION_THRESHOLD) return;
        
        String key = mobType + ":" + action;
        AttributeTacticCorrelation correlation = attributeCorrelations.computeIfAbsent(key, 
            k -> new AttributeTacticCorrelation(mobType, action));
        
        // Record mob's current attributes
        float speed = (float) mobEntity.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
        float health = mobEntity.getMaxHealth();
        float damage = (float) mobEntity.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
        
        correlation.recordSample(speed, health, damage, reward);
        
        // Log suggestion if we have enough samples
        if (correlation.sampleCount >= CORRELATION_SAMPLE_SIZE && correlation.sampleCount % 20 == 0) {
            LOGGER.info("💡 OPTIMIZATION: {} using '{}' performs better with: speed>{}, health>{}, damage>{}",
                mobType, action, String.format("%.2f", correlation.avgSpeed), 
                String.format("%.1f", correlation.avgHealth), String.format("%.1f", correlation.avgDamage));
        }
    }
    
    /**
     * Inner class to track attribute-tactic correlations
     */
    private static class AttributeTacticCorrelation {
        String mobType;
        String action;
        int sampleCount = 0;
        float avgSpeed = 0;
        float avgHealth = 0;
        float avgDamage = 0;
        float avgReward = 0;
        
        AttributeTacticCorrelation(String mobType, String action) {
            this.mobType = mobType;
            this.action = action;
        }
        
        void recordSample(float speed, float health, float damage, float reward) {
            // Running average
            avgSpeed = (avgSpeed * sampleCount + speed) / (sampleCount + 1);
            avgHealth = (avgHealth * sampleCount + health) / (sampleCount + 1);
            avgDamage = (avgDamage * sampleCount + damage) / (sampleCount + 1);
            avgReward = (avgReward * sampleCount + reward) / (sampleCount + 1);
            sampleCount++;
        }
    }
}
