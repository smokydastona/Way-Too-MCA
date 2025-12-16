package com.minecraft.gancity.ai;

import java.util.*;

/**
 * Tracks a full combat encounter - multiple tactical decisions, not just one action
 * This is what should be aggregated in federation: episode-level patterns
 * 
 * Example: Zombie lifetime (30s) → 50-200 tactical samples → 1 episode
 */
public class CombatEpisode {
    
    /**
     * Single tactical decision within an episode
     */
    public static class TacticalSample {
        public final TacticalActionSpace.TacticalState state;
        public final TacticalActionSpace.TacticalAction action;
        public final float immediateReward;  // damage dealt this tick
        public final long timestamp;
        
        public TacticalSample(TacticalActionSpace.TacticalState state, 
                            TacticalActionSpace.TacticalAction action,
                            float immediateReward) {
            this.state = state;
            this.action = action;
            this.immediateReward = immediateReward;
            this.timestamp = System.currentTimeMillis();
        }
    }
    
    /**
     * Episode outcome - what matters for learning
     */
    public static class EpisodeOutcome {
        public final boolean mobKilledPlayer;
        public final boolean playerKilledMob;
        public final float totalDamageDealt;
        public final float totalDamageTaken;
        public final int durationTicks;
        public final float episodeReward;  // calculated from outcome
        
        public EpisodeOutcome(boolean mobKilledPlayer, boolean playerKilledMob,
                            float totalDamageDealt, float totalDamageTaken, 
                            int durationTicks) {
            this.mobKilledPlayer = mobKilledPlayer;
            this.playerKilledMob = playerKilledMob;
            this.totalDamageDealt = totalDamageDealt;
            this.totalDamageTaken = totalDamageTaken;
            this.durationTicks = durationTicks;
            
            // Calculate episode reward
            this.episodeReward = calculateEpisodeReward();
        }
        
        private float calculateEpisodeReward() {
            float reward = 0;
            
            // Win/loss outcome
            if (mobKilledPlayer) {
                reward += 100.0f;
            } else if (playerKilledMob) {
                reward -= 50.0f;
            }
            
            // Damage efficiency
            reward += totalDamageDealt * 2.0f;
            reward -= totalDamageTaken * 1.0f;
            
            // Time efficiency (rewarded for quick kills, penalized for long losing fights)
            if (mobKilledPlayer && durationTicks < 600) {  // < 30s
                reward += 20.0f;
            }
            
            return reward;
        }
        
        public boolean wasSuccessful() {
            return episodeReward > 0;
        }
    }
    
    private final String mobId;
    private final String mobType;
    private final List<TacticalSample> samples;
    private final long startTime;
    private int startTick;
    private float totalDamageDealt;
    private float totalDamageTaken;
    private boolean episodeEnded;
    
    public CombatEpisode(String mobId, String mobType) {
        this.mobId = mobId;
        this.mobType = mobType;
        this.samples = new ArrayList<>();
        this.startTime = System.currentTimeMillis();
        this.startTick = 0;
        this.totalDamageDealt = 0;
        this.totalDamageTaken = 0;
        this.episodeEnded = false;
    }
    
    /**
     * Record a tactical decision during combat
     * Call this every few ticks (e.g., every 10 ticks = 0.5s)
     */
    public void recordTacticalSample(TacticalActionSpace.TacticalState state,
                                     TacticalActionSpace.TacticalAction action,
                                     float damageThisTick) {
        if (episodeEnded) {
            return;
        }
        
        samples.add(new TacticalSample(state, action, damageThisTick));
        totalDamageDealt += Math.max(0, damageThisTick);
    }
    
    /**
     * Record damage taken by the mob
     */
    public void recordDamageTaken(float damage) {
        totalDamageTaken += damage;
    }
    
    /**
     * End the episode and calculate outcome
     */
    public EpisodeOutcome endEpisode(boolean mobKilledPlayer, boolean playerKilledMob, int currentTick) {
        if (episodeEnded) {
            return null;
        }
        
        episodeEnded = true;
        int durationTicks = currentTick - startTick;
        
        return new EpisodeOutcome(
            mobKilledPlayer,
            playerKilledMob,
            totalDamageDealt,
            totalDamageTaken,
            durationTicks
        );
    }
    
    /**
     * Check if episode should be sampled (has enough data)
     * Federation wants dense episodes, not sparse ones
     */
    public boolean isReadyForLearning() {
        // Need at least 5 tactical samples to be meaningful
        return samples.size() >= 5;
    }
    
    /**
     * Get all tactical samples from this episode
     */
    public List<TacticalSample> getSamples() {
        return Collections.unmodifiableList(samples);
    }
    
    /**
     * Count tactical samples
     */
    public int getSampleCount() {
        return samples.size();
    }
    
    /**
     * Get mob type
     */
    public String getMobType() {
        return mobType;
    }
    
    /**
     * Get mob ID
     */
    public String getMobId() {
        return mobId;
    }
    
    /**
     * Get episode duration so far (milliseconds)
     */
    public long getDuration() {
        return System.currentTimeMillis() - startTime;
    }
    
    /**
     * Set start tick (for tracking episode duration in ticks)
     */
    public void setStartTick(int tick) {
        this.startTick = tick;
    }
    
    /**
     * Convert episode to loggable format for federation
     * This is what gets aggregated across players
     */
    public Map<String, Object> toFederationData(EpisodeOutcome outcome) {
        Map<String, Object> data = new HashMap<>();
        data.put("mobType", mobType);
        data.put("sampleCount", samples.size());
        data.put("episodeReward", outcome.episodeReward);
        data.put("wasSuccessful", outcome.wasSuccessful());
        data.put("damageDealt", outcome.totalDamageDealt);
        data.put("damageTaken", outcome.totalDamageTaken);
        data.put("durationTicks", outcome.durationTicks);
        
        // Aggregate tactical preferences from this episode
        Map<String, Integer> tacticCounts = new HashMap<>();
        for (TacticalSample sample : samples) {
            tacticCounts.merge(sample.action.id, 1, Integer::sum);
        }
        data.put("tacticsUsed", tacticCounts);
        
        return data;
    }
    
    /**
     * Extract tactical patterns for learning
     * Returns: what tactics were used in what situations, and did they work?
     */
    public Map<String, Float> extractTacticalWeights(EpisodeOutcome outcome) {
        Map<String, Float> weights = new HashMap<>();
        
        // Weight each tactic by how often it was used and episode success
        float successMultiplier = outcome.wasSuccessful() ? 1.5f : 0.5f;
        
        for (TacticalSample sample : samples) {
            String tacticId = sample.action.id;
            float weight = (1.0f / samples.size()) * successMultiplier;
            weights.merge(tacticId, weight, Float::sum);
        }
        
        return weights;
    }
    
    /**
     * Get situational tactics: what tactics were used in specific situations?
     * This enables context-aware learning
     */
    public Map<String, Map<String, Float>> extractSituationalTactics(EpisodeOutcome outcome) {
        Map<String, Map<String, Float>> situationalTactics = new HashMap<>();
        
        float successMultiplier = outcome.wasSuccessful() ? 1.0f : -0.5f;
        
        for (TacticalSample sample : samples) {
            // Categorize situation
            String situation = categorizeSituation(sample.state);
            String tactic = sample.action.id;
            
            situationalTactics.putIfAbsent(situation, new HashMap<>());
            situationalTactics.get(situation).merge(tactic, successMultiplier, Float::sum);
        }
        
        return situationalTactics;
    }
    
    /**
     * Categorize tactical state into discrete situations
     * Examples: "low_health", "target_low_health", "outnumbered", etc.
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
}
