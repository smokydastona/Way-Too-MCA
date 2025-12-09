package com.minecraft.gancity.ml;

import com.minecraft.gancity.ai.FederatedLearning;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RAG (Retrieval-Augmented Generation) Knowledge Base
 * Inspired by AI-Player's RAG system with web search
 * 
 * Stores and retrieves successful tactics:
 * - Combat strategies that worked
 * - Player behavior patterns
 * - Counter-tactics database
 * - Learning from past encounters
 * - Federated learning integration for global knowledge sharing
 */
public class TacticKnowledgeBase {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    // Knowledge storage
    private final Map<String, List<TacticEntry>> tacticsByCategory = new ConcurrentHashMap<>();
    private final Map<String, PlayerBehaviorProfile> playerProfiles = new ConcurrentHashMap<>();
    private final Map<String, Float> tacticSuccessRates = new ConcurrentHashMap<>();
    
    // Federated learning (optional)
    private FederatedLearning federatedLearning;
    
    // Configuration
    private static final int MAX_ENTRIES_PER_CATEGORY = 100;
    private static final float MIN_SUCCESS_RATE = 0.3f;
    private static final long ENTRY_EXPIRATION_MS = 3600000; // 1 hour
    
    public TacticKnowledgeBase() {
        initializeKnowledgeBase();
    }
    
    /**
     * Set federated learning instance for global knowledge sharing
     */
    public void setFederatedLearning(FederatedLearning federatedLearning) {
        this.federatedLearning = federatedLearning;
        LOGGER.info("Federated learning enabled for knowledge base");
    }
    
    /**
     * Initialize with baseline tactics
     */
    private void initializeKnowledgeBase() {
        // Anti-armor tactics
        addBaselineTactic("counter_armor", new TacticEntry(
            "group_rush",
            "Overwhelm heavily armored player with numbers",
            Arrays.asList("armor_level > 0.8", "allies_nearby >= 3"),
            0.7f,
            TacticType.COMBAT
        ));
        
        addBaselineTactic("counter_armor", new TacticEntry(
            "strafe_shoot",
            "Wear down armor from range with sustained fire",
            Arrays.asList("armor_level > 0.8", "has_ranged_weapon"),
            0.6f,
            TacticType.COMBAT
        ));
        
        // Anti-ranged tactics
        addBaselineTactic("counter_ranged", new TacticEntry(
            "circle_strafe",
            "Circle player to avoid arrows",
            Arrays.asList("player_has_bow", "distance > 5"),
            0.75f,
            TacticType.MOVEMENT
        ));
        
        addBaselineTactic("counter_ranged", new TacticEntry(
            "ambush",
            "Close distance quickly from cover",
            Arrays.asList("player_has_bow", "cover_available"),
            0.8f,
            TacticType.STEALTH
        ));
        
        // Shield counters
        addBaselineTactic("counter_shield", new TacticEntry(
            "flank_attack",
            "Attack from sides or behind",
            Arrays.asList("player_has_shield", "can_flank"),
            0.7f,
            TacticType.TACTICAL
        ));
        
        // Terrain tactics
        addBaselineTactic("terrain_use", new TacticEntry(
            "high_ground",
            "Secure elevated position for advantage",
            Arrays.asList("high_ground_available", "is_ranged_mob"),
            0.65f,
            TacticType.POSITIONING
        ));
        
        LOGGER.info("Knowledge Base initialized with {} baseline tactics", 
            tacticsByCategory.values().stream().mapToInt(List::size).sum());
    }
    
    /**
     * Query knowledge base for relevant tactics
     */
    public List<TacticEntry> queryTactics(String category, Map<String, Object> context) {
        List<TacticEntry> candidates = tacticsByCategory.getOrDefault(category, new ArrayList<>());
        List<TacticEntry> relevant = new ArrayList<>();
        
        for (TacticEntry tactic : candidates) {
            // Check if tactic conditions are met
            if (matchesConditions(tactic, context)) {
                relevant.add(tactic);
            }
        }
        
        // Sort by success rate
        relevant.sort((a, b) -> Float.compare(b.successRate, a.successRate));
        
        return relevant;
    }
    
    /**
     * Get best tactic for situation
     */
    public TacticEntry getBestTactic(String category, Map<String, Object> context) {
        List<TacticEntry> tactics = queryTactics(category, context);
        
        if (tactics.isEmpty()) {
            return null;
        }
        
        // Return highest success rate
        return tactics.get(0);
    }
    
    /**
     * Add new tactic from experience
     */
    public void addTactic(String category, TacticEntry tactic) {
        List<TacticEntry> entries = tacticsByCategory.computeIfAbsent(
            category, k -> new ArrayList<>()
        );
        
        // Check if tactic already exists
        Optional<TacticEntry> existing = entries.stream()
            .filter(e -> e.name.equals(tactic.name))
            .findFirst();
        
        if (existing.isPresent()) {
            // Update existing tactic
            TacticEntry existingTactic = existing.get();
            existingTactic.updateSuccessRate(tactic.successRate);
            existingTactic.lastUsed = System.currentTimeMillis();
        } else {
            // Add new tactic
            entries.add(tactic);
            
            // Evict old tactics if limit exceeded
            if (entries.size() > MAX_ENTRIES_PER_CATEGORY) {
                evictOldTactics(entries);
            }
        }
        
        LOGGER.debug("Added tactic '{}' to category '{}'", tactic.name, category);
    }
    
    /**
     * Record tactic outcome
     */
    public void recordOutcome(String tacticName, boolean success) {
        String key = tacticName.toLowerCase();
        Float currentRate = tacticSuccessRates.getOrDefault(key, 0.5f);
        
        // Update using exponential moving average
        float alpha = 0.1f; // Learning rate
        float newRate = success ? 
            currentRate + alpha * (1.0f - currentRate) :
            currentRate - alpha * currentRate;
        
        tacticSuccessRates.put(key, newRate);
        
        // Update tactic in knowledge base
        String category = null;
        Map<String, String> conditions = new HashMap<>();
        
        for (Map.Entry<String, List<TacticEntry>> catEntry : tacticsByCategory.entrySet()) {
            for (TacticEntry entry : catEntry.getValue()) {
                if (entry.name.equalsIgnoreCase(tacticName)) {
                    entry.updateSuccessRate(newRate);
                    entry.timesUsed++;
                    category = catEntry.getKey();
                    
                    // Extract conditions for federated learning
                    for (String cond : entry.conditions) {
                        String[] parts = cond.split(" ");
                        if (parts.length >= 2) {
                            conditions.put(parts[0], parts.length > 2 ? parts[2] : parts[1]);
                        }
                    }
                }
            }
        }
        
        // Sync to federated learning if enabled
        if (federatedLearning != null && category != null) {
            // Extract action from key (format: "mobType:action" or just "action")
            String action = key.contains(":") ? key.substring(key.indexOf(':') + 1) : key;
            String mobType = category; // Use category as mob type
            
            // Calculate reward based on success
            float reward = success ? 1.0f : -1.0f;
            
            federatedLearning.recordCombatOutcome(mobType, action, reward, success);
        }
        
        LOGGER.debug("Tactic '{}' outcome: {} (new rate: {:.2f})", 
            tacticName, success ? "SUCCESS" : "FAILURE", newRate);
    }
    
    /**
     * Learn player behavior pattern
     */
    public void learnPlayerBehavior(String playerId, String behavior, boolean effective) {
        PlayerBehaviorProfile profile = playerProfiles.computeIfAbsent(
            playerId, k -> new PlayerBehaviorProfile()
        );
        
        profile.recordBehavior(behavior, effective);
        
        LOGGER.debug("Learned player {} behavior: {}", playerId, behavior);
    }
    
    /**
     * Get recommended counter for player
     */
    public String getPlayerCounter(String playerId) {
        PlayerBehaviorProfile profile = playerProfiles.get(playerId);
        
        if (profile == null || profile.behaviorCount.isEmpty()) {
            return "adaptive"; // Default
        }
        
        // Find most common behavior
        String mostCommon = profile.behaviorCount.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse("unknown");
        
        // Map to counter-tactic
        return mapBehaviorToCounter(mostCommon);
    }
    
    /**
     * Search knowledge base (RAG query)
     */
    public List<TacticEntry> search(String query) {
        List<TacticEntry> results = new ArrayList<>();
        String lowerQuery = query.toLowerCase();
        
        for (List<TacticEntry> entries : tacticsByCategory.values()) {
            for (TacticEntry entry : entries) {
                if (entry.name.toLowerCase().contains(lowerQuery) ||
                    entry.description.toLowerCase().contains(lowerQuery)) {
                    results.add(entry);
                }
            }
        }
        
        // Sort by relevance (success rate)
        results.sort((a, b) -> Float.compare(b.successRate, a.successRate));
        
        return results;
    }
    
    /**
     * Get knowledge base statistics
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        
        int totalTactics = tacticsByCategory.values().stream()
            .mapToInt(List::size)
            .sum();
        
        stats.put("total_tactics", totalTactics);
        stats.put("categories", tacticsByCategory.size());
        stats.put("player_profiles", playerProfiles.size());
        stats.put("success_rates", tacticSuccessRates.size());
        
        // Average success rate
        double avgSuccess = tacticSuccessRates.values().stream()
            .mapToDouble(Float::doubleValue)
            .average()
            .orElse(0.5);
        stats.put("avg_success_rate", avgSuccess);
        
        return stats;
    }
    
    /**
     * Cleanup old entries
     */
    public void cleanup() {
        long currentTime = System.currentTimeMillis();
        
        for (List<TacticEntry> entries : tacticsByCategory.values()) {
            entries.removeIf(entry -> 
                (currentTime - entry.lastUsed) > ENTRY_EXPIRATION_MS &&
                entry.successRate < MIN_SUCCESS_RATE
            );
        }
        
        LOGGER.debug("Cleaned up old knowledge base entries");
    }
    
    /**
     * Helper methods
     */
    
    private void addBaselineTactic(String category, TacticEntry tactic) {
        List<TacticEntry> entries = tacticsByCategory.computeIfAbsent(
            category, k -> new ArrayList<>()
        );
        entries.add(tactic);
    }
    
    private boolean matchesConditions(TacticEntry tactic, Map<String, Object> context) {
        for (String condition : tactic.conditions) {
            if (!evaluateCondition(condition, context)) {
                return false;
            }
        }
        return true;
    }
    
    private boolean evaluateCondition(String condition, Map<String, Object> context) {
        // Simple condition parsing (can be enhanced with actual expression evaluator)
        // Format: "key operator value"
        String[] parts = condition.split(" ");
        
        if (parts.length < 2) {
            return true; // Invalid condition, skip
        }
        
        String key = parts[0];
        Object value = context.get(key);
        
        if (value == null) {
            return false;
        }
        
        // Simple existence check for now
        return true;
    }
    
    private void evictOldTactics(List<TacticEntry> entries) {
        // Remove lowest success rate tactics
        entries.sort((a, b) -> Float.compare(a.successRate, b.successRate));
        
        int toRemove = entries.size() - MAX_ENTRIES_PER_CATEGORY;
        for (int i = 0; i < toRemove; i++) {
            entries.remove(0);
        }
    }
    
    private String mapBehaviorToCounter(String behavior) {
        switch (behavior.toLowerCase()) {
            case "aggressive": return "defensive_trap";
            case "cautious": return "aggressive_rush";
            case "ranged": return "close_distance";
            case "melee": return "keep_distance";
            default: return "balanced";
        }
    }
    
    /**
     * Tactic entry in knowledge base
     */
    public static class TacticEntry {
        public final String name;
        public final String description;
        public final List<String> conditions;
        public float successRate;
        public final TacticType type;
        public long lastUsed;
        public int timesUsed;
        
        public TacticEntry(String name, String description, List<String> conditions, 
                          float successRate, TacticType type) {
            this.name = name;
            this.description = description;
            this.conditions = new ArrayList<>(conditions);
            this.successRate = successRate;
            this.type = type;
            this.lastUsed = System.currentTimeMillis();
            this.timesUsed = 0;
        }
        
        public void updateSuccessRate(float newRate) {
            // Exponential moving average
            this.successRate = 0.7f * this.successRate + 0.3f * newRate;
        }
    }
    
    /**
     * Player behavior profile
     */
    private static class PlayerBehaviorProfile {
        Map<String, Integer> behaviorCount = new HashMap<>();
        Map<String, Float> effectivenessList = new HashMap<>();
        
        void recordBehavior(String behavior, boolean effective) {
            behaviorCount.merge(behavior, 1, Integer::sum);
            
            Float currentRate = effectivenessList.getOrDefault(behavior, 0.5f);
            float newRate = effective ?
                currentRate + 0.1f * (1.0f - currentRate) :
                currentRate - 0.1f * currentRate;
            
            effectivenessList.put(behavior, newRate);
        }
    }
    
    /**
     * Tactic type categories
     */
    public enum TacticType {
        COMBAT,
        MOVEMENT,
        STEALTH,
        TACTICAL,
        POSITIONING,
        DEFENSIVE,
        UTILITY
    }
}
