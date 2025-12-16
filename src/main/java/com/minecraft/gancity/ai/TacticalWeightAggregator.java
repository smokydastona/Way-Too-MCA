package com.minecraft.gancity.ai;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tactical weight aggregation for federation
 * 
 * Instead of aggregating full DQN models (unstable, expensive),
 * we aggregate tactical preferences: which tactics work in which situations
 * 
 * This is what federation should do:
 * - 10 players × 50 zombies = visible learning in hours, not weeks
 * - Aggregate patterns, not gradients
 * - Learn "zombies punish shield spam now" not "Q-value delta 0.0003"
 */
public class TacticalWeightAggregator {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    /**
     * Global tactical weights per mob type
     * Maps: mobType -> tactic -> weight
     * 
     * Positive weight = tactic works well
     * Negative weight = tactic fails often
     */
    private final Map<String, Map<String, Float>> globalTacticalWeights;
    
    /**
     * Situational tactical weights
     * Maps: mobType -> situation -> tactic -> weight
     * 
     * Example: zombie -> "target_low_health" -> "rush_player" -> +0.87
     */
    private final Map<String, Map<String, Map<String, Float>>> situationalWeights;
    
    /**
     * Contribution tracking
     */
    private int totalEpisodesAggregated;
    private int totalSamplesAggregated;
    private final Set<String> contributingPlayers;
    
    /**
     * Learning rate for weight updates
     */
    private static final float LEARNING_RATE = 0.05f;
    
    public TacticalWeightAggregator() {
        this.globalTacticalWeights = new ConcurrentHashMap<>();
        this.situationalWeights = new ConcurrentHashMap<>();
        this.totalEpisodesAggregated = 0;
        this.totalSamplesAggregated = 0;
        this.contributingPlayers = ConcurrentHashMap.newKeySet();
    }
    
    /**
     * Aggregate a combat episode from a player
     * This is what replaces full DQN model synchronization
     */
    public void aggregateEpisode(CombatEpisode episode, CombatEpisode.EpisodeOutcome outcome, String playerId) {
        if (!episode.isReadyForLearning()) {
            return;  // Not enough samples to learn from
        }
        
        String mobType = episode.getMobType();
        
        // Update global tactical weights
        Map<String, Float> episodeWeights = episode.extractTacticalWeights(outcome);
        updateGlobalWeights(mobType, episodeWeights);
        
        // Update situational weights
        Map<String, Map<String, Float>> situationalTactics = episode.extractSituationalTactics(outcome);
        updateSituationalWeights(mobType, situationalTactics);
        
        // Track contribution
        totalEpisodesAggregated++;
        totalSamplesAggregated += episode.getSampleCount();
        contributingPlayers.add(playerId);
        
        // Log significant changes
        if (totalEpisodesAggregated % 50 == 0) {
            LOGGER.info("Federation: {} episodes, {} samples, {} players contributing", 
                totalEpisodesAggregated, totalSamplesAggregated, contributingPlayers.size());
            logTopTactics(mobType);
        }
    }
    
    /**
     * Update global tactical weights using exponential moving average
     */
    private void updateGlobalWeights(String mobType, Map<String, Float> episodeWeights) {
        globalTacticalWeights.putIfAbsent(mobType, new ConcurrentHashMap<>());
        Map<String, Float> currentWeights = globalTacticalWeights.get(mobType);
        
        for (Map.Entry<String, Float> entry : episodeWeights.entrySet()) {
            String tactic = entry.getKey();
            float newWeight = entry.getValue();
            
            // Exponential moving average
            currentWeights.merge(tactic, newWeight, (old, delta) -> 
                old * (1 - LEARNING_RATE) + delta * LEARNING_RATE
            );
        }
    }
    
    /**
     * Update situational weights
     */
    private void updateSituationalWeights(String mobType, Map<String, Map<String, Float>> situationalTactics) {
        situationalWeights.putIfAbsent(mobType, new ConcurrentHashMap<>());
        Map<String, Map<String, Float>> mobSituational = situationalWeights.get(mobType);
        
        for (Map.Entry<String, Map<String, Float>> situationEntry : situationalTactics.entrySet()) {
            String situation = situationEntry.getKey();
            Map<String, Float> tactics = situationEntry.getValue();
            
            mobSituational.putIfAbsent(situation, new ConcurrentHashMap<>());
            Map<String, Float> currentTactics = mobSituational.get(situation);
            
            for (Map.Entry<String, Float> tacticEntry : tactics.entrySet()) {
                String tactic = tacticEntry.getKey();
                float weight = tacticEntry.getValue();
                
                currentTactics.merge(tactic, weight, (old, delta) ->
                    old * (1 - LEARNING_RATE) + delta * LEARNING_RATE
                );
            }
        }
    }
    
    /**
     * Select best tactic for a given situation using aggregated knowledge
     * This is what replaces the DQN forward pass
     */
    public TacticalActionSpace.TacticalAction selectTactic(String mobType, 
                                                           TacticalActionSpace.TacticalState state,
                                                           List<TacticalActionSpace.TacticalAction> availableActions) {
        // Categorize situation
        String situation = categorizeSituation(state);
        
        // Get situational weights
        Map<String, Float> tacticWeights = getSituationalWeights(mobType, situation);
        
        if (tacticWeights.isEmpty()) {
            // Fall back to global weights
            tacticWeights = getGlobalWeights(mobType);
        }
        
        if (tacticWeights.isEmpty()) {
            // No learned data yet, use random
            return availableActions.get(new Random().nextInt(availableActions.size()));
        }
        
        // Select tactic with highest weight (softmax-style exploration)
        return selectWithExploration(availableActions, tacticWeights);
    }
    
    /**
     * Select action using softmax exploration
     * High-weight tactics more likely, but exploration still happens
     */
    private TacticalActionSpace.TacticalAction selectWithExploration(
            List<TacticalActionSpace.TacticalAction> availableActions,
            Map<String, Float> weights) {
        
        // Calculate softmax probabilities
        float[] probabilities = new float[availableActions.size()];
        float sumExp = 0;
        
        for (int i = 0; i < availableActions.size(); i++) {
            String tacticId = availableActions.get(i).id;
            float weight = weights.getOrDefault(tacticId, 0.0f);
            float exp = (float) Math.exp(weight);
            probabilities[i] = exp;
            sumExp += exp;
        }
        
        // Normalize
        for (int i = 0; i < probabilities.length; i++) {
            probabilities[i] /= sumExp;
        }
        
        // Sample from distribution
        float rand = new Random().nextFloat();
        float cumulative = 0;
        for (int i = 0; i < probabilities.length; i++) {
            cumulative += probabilities[i];
            if (rand <= cumulative) {
                return availableActions.get(i);
            }
        }
        
        // Fallback
        return availableActions.get(availableActions.size() - 1);
    }
    
    /**
     * Get global weights for a mob type
     */
    private Map<String, Float> getGlobalWeights(String mobType) {
        return globalTacticalWeights.getOrDefault(mobType, new HashMap<>());
    }
    
    /**
     * Get situational weights for a mob type and situation
     */
    private Map<String, Float> getSituationalWeights(String mobType, String situation) {
        Map<String, Map<String, Float>> mobSituational = situationalWeights.get(mobType);
        if (mobSituational == null) {
            return new HashMap<>();
        }
        return mobSituational.getOrDefault(situation, new HashMap<>());
    }
    
    /**
     * Categorize situation (same as in CombatEpisode)
     */
    private String categorizeSituation(TacticalActionSpace.TacticalState state) {
        if (state.selfLowHealth) {
            return "low_health";
        } else if (state.targetLowHealth) {
            return "target_low_health";
        } else if (state.targetHasShield) {
            return "target_shielding";
        } else if (state.nearbyAllies >= 2) {
            return "group_combat";
        } else if (state.distanceToTarget < 3) {
            return "close_range";
        } else if (state.distanceToTarget > 8) {
            return "long_range";
        } else {
            return "neutral";
        }
    }
    
    /**
     * Log top tactics for debugging
     */
    private void logTopTactics(String mobType) {
        Map<String, Float> weights = getGlobalWeights(mobType);
        if (weights.isEmpty()) {
            return;
        }
        
        // Sort by weight
        List<Map.Entry<String, Float>> sorted = new ArrayList<>(weights.entrySet());
        sorted.sort((a, b) -> Float.compare(b.getValue(), a.getValue()));
        
        StringBuilder msg = new StringBuilder(String.format("%s top tactics: ", mobType));
        for (int i = 0; i < Math.min(3, sorted.size()); i++) {
            Map.Entry<String, Float> entry = sorted.get(i);
            msg.append(String.format("%s(%.2f) ", entry.getKey(), entry.getValue()));
        }
        
        LOGGER.info(msg.toString());
    }
    
    /**
     * Get aggregation statistics for monitoring
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalEpisodes", totalEpisodesAggregated);
        stats.put("totalSamples", totalSamplesAggregated);
        stats.put("contributors", contributingPlayers.size());
        stats.put("mobTypesLearned", globalTacticalWeights.keySet().size());
        
        // Calculate average samples per episode
        if (totalEpisodesAggregated > 0) {
            stats.put("avgSamplesPerEpisode", totalSamplesAggregated / (float) totalEpisodesAggregated);
        }
        
        return stats;
    }
    
    /**
     * Export weights for federation sync
     * Returns: mobType -> tactic -> weight
     */
    public Map<String, Map<String, Float>> exportWeights() {
        Map<String, Map<String, Float>> export = new HashMap<>();
        
        for (Map.Entry<String, Map<String, Float>> entry : globalTacticalWeights.entrySet()) {
            export.put(entry.getKey(), new HashMap<>(entry.getValue()));
        }
        
        return export;
    }
    
    /**
     * Import weights from federation (merge with existing)
     */
    public void importWeights(Map<String, Map<String, Float>> incomingWeights) {
        for (Map.Entry<String, Map<String, Float>> mobEntry : incomingWeights.entrySet()) {
            String mobType = mobEntry.getKey();
            Map<String, Float> incomingTactics = mobEntry.getValue();
            
            globalTacticalWeights.putIfAbsent(mobType, new ConcurrentHashMap<>());
            Map<String, Float> currentTactics = globalTacticalWeights.get(mobType);
            
            // Merge: average of local and incoming
            for (Map.Entry<String, Float> tacticEntry : incomingTactics.entrySet()) {
                String tactic = tacticEntry.getKey();
                float incomingWeight = tacticEntry.getValue();
                
                currentTactics.merge(tactic, incomingWeight, (local, incoming) ->
                    (local + incoming) / 2.0f
                );
            }
        }
        
        LOGGER.info("Imported tactical weights from federation server");
    }
    
    /**
     * Reset aggregator (for testing)
     */
    public void reset() {
        globalTacticalWeights.clear();
        situationalWeights.clear();
        totalEpisodesAggregated = 0;
        totalSamplesAggregated = 0;
        contributingPlayers.clear();
    }
    
    /**
     * Check if a mob type has learned tactical knowledge
     */
    public boolean hasLearnedTactics(String mobType) {
        Map<String, Float> weights = getGlobalWeights(mobType);
        return !weights.isEmpty() && totalEpisodesAggregated >= 10;
    }
    
    /**
     * Get magnitude of recent tactical changes (for monitoring)
     * Returns delta magnitude to show if learning is happening
     */
    public float getDeltaMagnitude(String mobType) {
        Map<String, Float> weights = getGlobalWeights(mobType);
        if (weights.isEmpty()) {
            return 0.0f;
        }
        
        // Sum absolute values of weights (proxy for learning activity)
        float magnitude = 0;
        for (float weight : weights.values()) {
            magnitude += Math.abs(weight);
        }
        
        return magnitude / weights.size();
    }
}
