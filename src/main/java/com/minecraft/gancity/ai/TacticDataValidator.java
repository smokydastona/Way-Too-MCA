package com.minecraft.gancity.ai;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.Map;

/**
 * Validates and sanitizes federated learning tactic data
 * Enforces mathematical invariants to prevent poisoned learning
 * 
 * Critical Invariants:
 * 1. count == successCount + failureCount
 * 2. successRate == successCount / count
 * 3. weightedAvgReward present for ALL tactics using FedAvgM
 * 4. aggregationMethod == FedAvgM → momentum required
 * 5. count > 0 always
 * 
 * This prevents:
 * - Bad learning behavior from inconsistent data
 * - Division by zero / NPE crashes
 * - Tactics locking into suboptimal strategies
 * - Servers diverging in behavior
 */
public class TacticDataValidator {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    // Validation thresholds
    private static final float MIN_SUCCESS_RATE = 0.0f;
    private static final float MAX_SUCCESS_RATE = 1.0f;
    private static final float MIN_AVG_REWARD = -100.0f;
    private static final float MAX_AVG_REWARD = 100.0f;
    private static final int MAX_COUNT = 1_000_000;
    private static final float EPSILON = 0.001f; // Tolerance for floating point comparison
    
    /**
     * Validate and sanitize a single tactic entry from federated data
     * 
     * @param tacticData Raw tactic data from federation
     * @param action Action name (for logging)
     * @param mobType Mob type (for logging)
     * @return Sanitized tactic data, or null if unrecoverable
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> validateAndSanitize(
            Map<String, Object> tacticData, 
            String action, 
            String mobType) {
        
        if (tacticData == null || tacticData.isEmpty()) {
            LOGGER.warn("Null or empty tactic data for {}: {}", mobType, action);
            return null;
        }
        
        try {
            // Extract fields with safe defaults
            int count = getIntValue(tacticData, "count", 0);
            int successCount = getIntValue(tacticData, "successCount", 0);
            int failureCount = getIntValue(tacticData, "failureCount", 0);
            float successRate = getFloatValue(tacticData, "successRate", 0.5f);
            float avgReward = getFloatValue(tacticData, "avgReward", 0.0f);
            float totalReward = getFloatValue(tacticData, "totalReward", avgReward * count);
            
            // Get aggregation method if present
            String aggregationMethod = (String) tacticData.get("aggregationMethod");
            
            // ==================== VALIDATION & SANITIZATION ====================
            
            // Rule 1: count must be positive
            if (count <= 0) {
                LOGGER.warn("Invalid count ({}) for {}: {} - discarding", count, mobType, action);
                return null;
            }
            
            // Rule 2: count must not exceed maximum (prevents overflow attacks)
            if (count > MAX_COUNT) {
                LOGGER.warn("Suspiciously large count ({}) for {}: {} - capping at {}", 
                    count, mobType, action, MAX_COUNT);
                count = MAX_COUNT;
                tacticData.put("count", count);
            }
            
            // Rule 3: Enforce count == successCount + failureCount
            int calculatedTotal = successCount + failureCount;
            if (calculatedTotal != count) {
                LOGGER.warn("Count mismatch for {}: {} - count={}, success={}, failure={} (expected {})",
                    mobType, action, count, successCount, failureCount, calculatedTotal);
                
                // Attempt repair: if we have success/failure counts, recalculate total
                if (successCount >= 0 && failureCount >= 0 && calculatedTotal > 0) {
                    count = calculatedTotal;
                    tacticData.put("count", count);
                    LOGGER.info("✓ Repaired count: {} → {}", calculatedTotal, count);
                } else if (count > 0 && successCount >= 0) {
                    // Recalculate failure count
                    failureCount = count - successCount;
                    if (failureCount < 0) {
                        // successCount is invalid, fall back to successRate
                        successCount = Math.round(count * successRate);
                        failureCount = count - successCount;
                    }
                    tacticData.put("failureCount", failureCount);
                    tacticData.put("successCount", successCount);
                    LOGGER.info("✓ Repaired failure count: {}", failureCount);
                } else {
                    LOGGER.error("Cannot repair count invariant - discarding tactic");
                    return null;
                }
            }
            
            // Rule 4: Recalculate and enforce successRate = successCount / count
            float calculatedSuccessRate = (float) successCount / count;
            if (Math.abs(successRate - calculatedSuccessRate) > EPSILON) {
                LOGGER.warn("Success rate mismatch for {}: {} - stored={}, calculated={}",
                    mobType, action, successRate, calculatedSuccessRate);
                successRate = calculatedSuccessRate;
                tacticData.put("successRate", successRate);
                LOGGER.info("✓ Repaired successRate: {}", successRate);
            }
            
            // Rule 5: Validate successRate bounds
            if (successRate < MIN_SUCCESS_RATE || successRate > MAX_SUCCESS_RATE) {
                LOGGER.warn("Out of bounds successRate ({}) for {}: {} - clamping",
                    successRate, mobType, action);
                successRate = Math.max(MIN_SUCCESS_RATE, Math.min(MAX_SUCCESS_RATE, successRate));
                tacticData.put("successRate", successRate);
            }
            
            // Rule 6: Validate avgReward bounds
            if (avgReward < MIN_AVG_REWARD || avgReward > MAX_AVG_REWARD) {
                LOGGER.warn("Out of bounds avgReward ({}) for {}: {} - clamping",
                    avgReward, mobType, action);
                avgReward = Math.max(MIN_AVG_REWARD, Math.min(MAX_AVG_REWARD, avgReward));
                tacticData.put("avgReward", avgReward);
            }
            
            // Rule 7: Validate totalReward consistency
            float calculatedTotalReward = avgReward * count;
            if (Math.abs(totalReward - calculatedTotalReward) > EPSILON * count) {
                LOGGER.warn("Total reward mismatch for {}: {} - stored={}, calculated={}",
                    mobType, action, totalReward, calculatedTotalReward);
                totalReward = calculatedTotalReward;
                tacticData.put("totalReward", totalReward);
                LOGGER.info("✓ Repaired totalReward: {}", totalReward);
            }
            
            // Rule 8: FedAvgM requires momentum state
            if ("FedAvgM".equals(aggregationMethod)) {
                // Ensure weightedAvgReward exists
                if (!tacticData.containsKey("weightedAvgReward")) {
                    LOGGER.warn("FedAvgM tactic missing weightedAvgReward for {}: {} - initializing",
                        mobType, action);
                    tacticData.put("weightedAvgReward", avgReward);
                }
                
                // Ensure momentum exists (velocity term)
                if (!tacticData.containsKey("momentum")) {
                    LOGGER.debug("FedAvgM tactic missing momentum for {}: {} - initializing to 0.0",
                        mobType, action);
                    tacticData.put("momentum", 0.0);
                }
            }
            
            // Rule 9: Ensure lastUpdate timestamp exists
            if (!tacticData.containsKey("lastUpdate")) {
                tacticData.put("lastUpdate", System.currentTimeMillis());
            }
            
            LOGGER.debug("✓ Validated tactic {}: {} (count={}, successRate={}, avgReward={})",
                mobType, action, count, successRate, avgReward);
            
            return tacticData;
            
        } catch (Exception e) {
            LOGGER.error("Failed to validate tactic {}: {} - {}", mobType, action, e.getMessage());
            return null;
        }
    }
    
    /**
     * Validate mob-level aggregated data
     */
    @SuppressWarnings("unchecked")
    public static boolean validateMobData(Map<String, Object> mobData, String mobType) {
        if (mobData == null) {
            LOGGER.warn("Null mob data for {}", mobType);
            return false;
        }
        
        // Check required fields
        Object tacticsObj = mobData.get("tactics");
        if (tacticsObj == null) {
            LOGGER.warn("Missing tactics field for {}", mobType);
            return false;
        }
        
        // Validate aggregation method if present
        String aggregationMethod = (String) mobData.get("aggregationMethod");
        if (aggregationMethod != null && !aggregationMethod.equals("FedAvgM") && !aggregationMethod.equals("FedAvg")) {
            LOGGER.warn("Unknown aggregation method '{}' for {}", aggregationMethod, mobType);
        }
        
        // Validate submissions count
        Object submissionsObj = mobData.get("submissions");
        if (submissionsObj != null) {
            int submissions = getIntValue(mobData, "submissions", 0);
            if (submissions < 0) {
                LOGGER.warn("Negative submissions ({}) for {} - resetting to 0", submissions, mobType);
                mobData.put("submissions", 0);
            }
        }
        
        return true;
    }
    
    /**
     * Safe integer extraction with default
     */
    private static int getIntValue(Map<String, Object> map, String key, int defaultValue) {
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            LOGGER.warn("Cannot parse int from '{}' for key '{}' - using default {}", 
                value, key, defaultValue);
            return defaultValue;
        }
    }
    
    /**
     * Safe float extraction with default
     */
    private static float getFloatValue(Map<String, Object> map, String key, float defaultValue) {
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number) {
            return ((Number) value).floatValue();
        }
        try {
            return Float.parseFloat(value.toString());
        } catch (NumberFormatException e) {
            LOGGER.warn("Cannot parse float from '{}' for key '{}' - using default {}", 
                value, key, defaultValue);
            return defaultValue;
        }
    }
    
    /**
     * Validate that tactics in a mob's data are consistent
     * Returns number of tactics that passed validation
     */
    @SuppressWarnings("unchecked")
    public static int validateAndSanitizeAllTactics(Map<String, Object> mobData, String mobType) {
        Object tacticsObj = mobData.get("tactics");
        if (!(tacticsObj instanceof Map)) {
            return 0;
        }
        
        Map<String, Object> tactics = (Map<String, Object>) tacticsObj;
        int validCount = 0;
        int invalidCount = 0;
        
        // Validate each tactic
        for (Map.Entry<String, Object> entry : tactics.entrySet()) {
            String action = entry.getKey();
            Object tacticDataObj = entry.getValue();
            
            if (!(tacticDataObj instanceof Map)) {
                LOGGER.warn("Invalid tactic data type for {}: {} - skipping", mobType, action);
                invalidCount++;
                continue;
            }
            
            Map<String, Object> tacticData = (Map<String, Object>) tacticDataObj;
            Map<String, Object> sanitized = validateAndSanitize(tacticData, action, mobType);
            
            if (sanitized != null) {
                // Replace with sanitized version
                entry.setValue(sanitized);
                validCount++;
            } else {
                LOGGER.error("Failed to sanitize {}: {} - will be excluded from learning", 
                    mobType, action);
                invalidCount++;
            }
        }
        
        // Remove invalid tactics
        if (invalidCount > 0) {
            tactics.entrySet().removeIf(entry -> 
                !(entry.getValue() instanceof Map) || 
                validateAndSanitize((Map<String, Object>) entry.getValue(), entry.getKey(), mobType) == null
            );
            
            LOGGER.warn("Removed {} invalid tactics from {} (kept {})", invalidCount, mobType, validCount);
        }
        
        return validCount;
    }
}
