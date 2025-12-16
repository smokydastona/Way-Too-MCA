package com.minecraft.gancity.ai;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.*;

/**
 * Heuristic seeding for tactical AI - gives mobs baseline knowledge before learning
 * 
 * This is the "synthetic knowledge" that makes federation work from day one.
 * Instead of learning from scratch, mobs start with basic combat sense.
 * 
 * Then federation refines it: "zombies now punish shield spam" not "zombies learn to attack"
 */
public class HeuristicTacticSeeding {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    /**
     * Seed tactical weights with baseline heuristics
     * These are reasonable starting tactics that work ~40-60% of the time
     */
    public static void seedTacticalWeights(TacticalWeightAggregator aggregator) {
        LOGGER.info("Seeding tactical AI with heuristic baseline knowledge...");
        
        // Seed each major mob type
        seedZombie(aggregator);
        seedSkeleton(aggregator);
        seedCreeper(aggregator);
        seedSpider(aggregator);
        seedEnderman(aggregator);
        
        LOGGER.info("Tactical AI seeded - mobs have baseline combat knowledge");
    }
    
    /**
     * Zombie heuristics: aggressive, group-oriented
     */
    private static void seedZombie(TacticalWeightAggregator aggregator) {
        Map<String, Map<String, Float>> zombieSeeds = new HashMap<>();
        
        Map<String, Float> globalTactics = new HashMap<>();
        globalTactics.put("rush_player", 0.4f);              // Zombies naturally aggressive
        globalTactics.put("call_reinforcements", 0.3f);      // Horde behavior
        globalTactics.put("overhead_combo", 0.2f);           // Sometimes jump attacks
        globalTactics.put("exploit_weakness", 0.1f);         // Basic opportunism
        
        zombieSeeds.put("zombie", globalTactics);
        
        // Also seed variants
        zombieSeeds.put("husk", new HashMap<>(globalTactics));
        zombieSeeds.put("drowned", new HashMap<>(globalTactics));
        
        aggregator.importWeights(zombieSeeds);
    }
    
    /**
     * Skeleton heuristics: ranged, defensive positioning
     */
    private static void seedSkeleton(TacticalWeightAggregator aggregator) {
        Map<String, Map<String, Float>> skeletonSeeds = new HashMap<>();
        
        Map<String, Float> globalTactics = new HashMap<>();
        globalTactics.put("wait_for_opening", 0.4f);         // Maintain distance
        globalTactics.put("dodge_weave", 0.3f);              // Avoid melee
        globalTactics.put("use_terrain", 0.2f);              // Take cover
        globalTactics.put("retreat_and_heal", 0.1f);         // Kite when damaged
        
        skeletonSeeds.put("skeleton", globalTactics);
        skeletonSeeds.put("stray", new HashMap<>(globalTactics));
        
        aggregator.importWeights(skeletonSeeds);
    }
    
    /**
     * Creeper heuristics: suicidal but strategic
     */
    private static void seedCreeper(TacticalWeightAggregator aggregator) {
        Map<String, Map<String, Float>> creeperSeeds = new HashMap<>();
        
        Map<String, Float> globalTactics = new HashMap<>();
        globalTactics.put("rush_player", 0.35f);             // Get in explosion range
        globalTactics.put("feint_retreat", 0.25f);           // Bait player forward
        globalTactics.put("use_terrain", 0.2f);              // Ambush from cover
        globalTactics.put("wait_for_opening", 0.2f);         // Patience for perfect explosion
        
        creeperSeeds.put("creeper", globalTactics);
        
        aggregator.importWeights(creeperSeeds);
    }
    
    /**
     * Spider heuristics: mobile, aggressive flanking
     */
    private static void seedSpider(TacticalWeightAggregator aggregator) {
        Map<String, Map<String, Float>> spiderSeeds = new HashMap<>();
        
        Map<String, Float> globalTactics = new HashMap<>();
        globalTactics.put("strafe_aggressive", 0.4f);        // Circle strafe
        globalTactics.put("overhead_combo", 0.3f);           // Wall climbing attacks
        globalTactics.put("dodge_weave", 0.2f);              // Fast movement
        globalTactics.put("punish_shield_drop", 0.1f);       // Opportunistic
        
        spiderSeeds.put("spider", globalTactics);
        spiderSeeds.put("cave_spider", new HashMap<>(globalTactics));
        
        aggregator.importWeights(spiderSeeds);
    }
    
    /**
     * Enderman heuristics: teleporting, counter-based
     */
    private static void seedEnderman(TacticalWeightAggregator aggregator) {
        Map<String, Map<String, Float>> endermanSeeds = new HashMap<>();
        
        Map<String, Float> globalTactics = new HashMap<>();
        globalTactics.put("counter_attack", 0.4f);           // Teleport + punish
        globalTactics.put("dodge_weave", 0.3f);              // Teleport dodge
        globalTactics.put("punish_shield_drop", 0.2f);       // Wait for vulnerability
        globalTactics.put("exploit_weakness", 0.1f);         // Opportunistic
        
        endermanSeeds.put("enderman", globalTactics);
        
        aggregator.importWeights(endermanSeeds);
    }
    
    /**
     * Seed situational tactics - context-aware baseline behavior
     * 
     * This teaches mobs basic "if-then" combat sense:
     * - If I'm low health -> retreat
     * - If target is low health -> rush
     * - If target is shielding -> wait
     */
    public static void seedSituationalTactics(TacticalWeightAggregator aggregator) {
        // These would require modifying TacticalWeightAggregator to accept situational seeds
        // For now, global seeds provide baseline behavior
        
        // Future enhancement: seed situational tactics like:
        // - "low_health" -> "retreat_and_heal" (0.8)
        // - "target_low_health" -> "rush_player" (0.7)
        // - "target_shielding" -> "wait_for_opening" (0.6)
    }
    
    /**
     * Generate difficulty-adjusted seeds
     * Higher difficulty = more sophisticated starting tactics
     */
    public static void seedWithDifficulty(TacticalWeightAggregator aggregator, float difficulty) {
        // Standard seeding
        seedTacticalWeights(aggregator);
        
        // On higher difficulties, boost advanced tactics
        if (difficulty > 1.5f) {
            Map<String, Map<String, Float>> advancedBoost = new HashMap<>();
            
            // Boost counter-play and exploitation tactics
            for (String mobType : Arrays.asList("zombie", "skeleton", "spider", "creeper")) {
                Map<String, Float> boost = new HashMap<>();
                boost.put("punish_shield_drop", 0.2f);
                boost.put("exploit_weakness", 0.15f);
                boost.put("counter_attack", 0.15f);
                advancedBoost.put(mobType, boost);
            }
            
            aggregator.importWeights(advancedBoost);
            LOGGER.info("Applied difficulty boost to tactical seeding (difficulty: {})", difficulty);
        }
    }
    
    /**
     * Check if seeding is needed (no learned data yet)
     */
    public static boolean needsSeeding(TacticalWeightAggregator aggregator) {
        // Check if any mob type has learned tactics
        for (String mobType : Arrays.asList("zombie", "skeleton", "spider", "creeper", "enderman")) {
            if (aggregator.hasLearnedTactics(mobType)) {
                return false;  // Already has learned data
            }
        }
        return true;  // No learned data, needs seeding
    }
}
