package com.minecraft.gancity.ai;

/**
 * Tactic tier system for mob AI variation
 * Creates natural difficulty progression with elite, veteran, and rookie mobs
 */
public enum TacticTier {
    /**
     * Elite mobs (top 10% tactics) - Rare, extremely dangerous
     * Uses only the most successful tactics learned globally
     */
    ELITE("elite", 0.10f, 2.0f),
    
    /**
     * Veteran mobs (mid 50% tactics) - Common, challenging
     * Uses proven, reliable tactics
     */
    VETERAN("veteran", 0.50f, 1.0f),
    
    /**
     * Rookie mobs (bottom 40% tactics) - Common, easier
     * Uses less effective or experimental tactics
     */
    ROOKIE("rookie", 0.40f, 0.5f);
    
    private final String name;
    private final float spawnWeight;
    private final float difficultyMultiplier;
    
    TacticTier(String name, float spawnWeight, float difficultyMultiplier) {
        this.name = name;
        this.spawnWeight = spawnWeight;
        this.difficultyMultiplier = difficultyMultiplier;
    }
    
    public String getName() {
        return name;
    }
    
    public float getSpawnWeight() {
        return spawnWeight;
    }
    
    public float getDifficultyMultiplier() {
        return difficultyMultiplier;
    }
    
    /**
     * Get tier folder name for Cloudflare/GitHub storage
     */
    public String getFolderName() {
        return name + "_tactics";
    }
    
    /**
     * Randomly select a tier based on spawn weights
     */
    public static TacticTier selectRandomTier(java.util.Random random) {
        float roll = random.nextFloat();
        float cumulative = 0;
        
        for (TacticTier tier : values()) {
            cumulative += tier.spawnWeight;
            if (roll <= cumulative) {
                return tier;
            }
        }
        
        return VETERAN; // fallback
    }
    
    /**
     * Determine tier based on win rate
     */
    public static TacticTier fromWinRate(float winRate) {
        if (winRate >= 0.65f) {
            return ELITE;
        } else if (winRate >= 0.35f) {
            return VETERAN;
        } else {
            return ROOKIE;
        }
    }
    
    /**
     * Get tier from string name (for loading from NBT)
     */
    public static TacticTier fromName(String name) {
        for (TacticTier tier : values()) {
            if (tier.name.equalsIgnoreCase(name)) {
                return tier;
            }
        }
        return VETERAN; // default
    }
}
