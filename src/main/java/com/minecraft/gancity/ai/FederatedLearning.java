package com.minecraft.gancity.ai;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Federated Learning System - Syncs learned knowledge via Cloudflare Worker API
 * allowing all servers to benefit from collective learning with zero configuration.
 * 
 * Features:
 * - Automatic sync to Cloudflare Worker API (no Git required)
 * - Privacy-safe aggregation (no player identifiable data)
 * - Works out-of-the-box for all players (no SSH/credentials needed)
 * - Async operations (non-blocking gameplay)
 * - Graceful degradation if API unavailable
 * 
 * Data Flow:
 * 1. Local server records combat outcomes
 * 2. Periodically submits tactics to API (5 min interval)
 * 3. Downloads aggregated global tactics from API (10 min interval)
 * 4. Applies global knowledge to local AI systems
 * 5. All servers contribute and benefit from collective learning
 */
public class FederatedLearning {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    // Sync Configuration
    private static final long SYNC_INTERVAL_MS = 300_000; // 5 minutes (submit)
    private static final long PULL_INTERVAL_MS = 600_000; // 10 minutes (download)
    private static final int MIN_DATA_POINTS = 10; // Minimum data before submitting
    
    // Memory Management
    private static final int MAX_TACTICS_PER_MOB = 50; // Keep top 50 tactics per mob type
    private static final int MAX_TOTAL_TACTICS = 2000; // Global pool max (72 mobs × ~28 avg)
    private static final long TACTIC_EXPIRY_MS = 7 * 24 * 60 * 60 * 1000L; // 7 days
    private static final float MIN_REWARD_THRESHOLD = 1.0f; // Prune tactics below this
    
    // Exploration vs Exploitation (keep gameplay interesting)
    private static final float EXPLORATION_RATE = 0.15f; // 15% chance to resurrect pruned tactics
    private static final int EXPLORATION_TACTICS_PER_MOB = 5; // Resurrect 5 random tactics per mob
    
    // Cloudflare API Client
    private CloudflareAPIClient apiClient;
    private boolean syncEnabled = false;
    
    // Thread Pool for Async Operations
    private final ScheduledExecutorService syncExecutor = Executors.newSingleThreadScheduledExecutor(
        r -> {
            Thread t = new Thread(r, "FederatedLearning-Sync");
            t.setDaemon(true);
            return t;
        }
    );
    
    // Local aggregation before submission
    private final Map<String, TacticSubmission> pendingSubmissions = new ConcurrentHashMap<>();
    
    // Global tactic pool - tactics learned by ALL mob types worldwide
    private final Map<String, Map<String, GlobalTactic>> globalTacticPool = new ConcurrentHashMap<>();
    
    // Statistics
    private long totalDataPointsContributed = 0;
    private long totalDataPointsDownloaded = 0;
    private long lastSyncTime = 0;
    private long lastPullTime = 0;
    
    public FederatedLearning(Path localDataPath, String apiEndpoint) {
        this.syncEnabled = apiEndpoint != null && !apiEndpoint.isEmpty();
        
        if (syncEnabled) {
            // Initialize Cloudflare API client
            this.apiClient = new CloudflareAPIClient(apiEndpoint);
            
            // Schedule periodic sync operations
            syncExecutor.scheduleAtFixedRate(this::downloadGlobalTactics, 
                PULL_INTERVAL_MS, PULL_INTERVAL_MS, TimeUnit.MILLISECONDS);
            syncExecutor.scheduleAtFixedRate(this::submitLocalTactics, 
                SYNC_INTERVAL_MS, SYNC_INTERVAL_MS, TimeUnit.MILLISECONDS);
            
            LOGGER.info("Federated Learning enabled - API: {}", apiEndpoint);
            LOGGER.info("Auto-submit every {} minutes, auto-download every {} minutes", 
                SYNC_INTERVAL_MS / 60_000, PULL_INTERVAL_MS / 60_000);
        } else {
            LOGGER.info("Federated Learning disabled - Set 'cloudApiEndpoint' in config to enable");
        }
    }
    
    
    /**
     * Record a combat outcome for federated learning
     * Aggregates locally before submitting to API
     */
    public void recordCombatOutcome(String mobType, String action, float reward, boolean success) {
        if (!syncEnabled) return;
        
        String key = mobType + ":" + action;
        TacticSubmission submission = pendingSubmissions.computeIfAbsent(key, 
            k -> new TacticSubmission(mobType, action));
        
        submission.addOutcome(reward, success);
        totalDataPointsContributed++;
    }
    
    /**
     * Submit accumulated local tactics to Cloudflare API
     */
    private void submitLocalTactics() {
        if (!syncEnabled || pendingSubmissions.isEmpty()) return;
        
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastSyncTime < SYNC_INTERVAL_MS) {
            return; // Too soon
        }
        
        // Check if we have enough data to contribute
        if (totalDataPointsContributed < MIN_DATA_POINTS) {
            LOGGER.debug("Not enough local data to contribute ({}), waiting for more...", 
                totalDataPointsContributed);
            return;
        }
        
        try {
            LOGGER.info("Submitting {} local tactics to global repository...", pendingSubmissions.size());
            
            int successCount = 0;
            int failCount = 0;
            
            // Submit each pending tactic
            for (TacticSubmission submission : pendingSubmissions.values()) {
                boolean success = apiClient.submitTactic(
                    submission.mobType,
                    submission.action,
                    submission.getAverageReward(),
                    submission.getSuccessRate() > 0.5f ? "success" : "failure"
                );
                
                if (success) {
                    successCount++;
                } else {
                    failCount++;
                }
            }
            
            if (successCount > 0) {
                LOGGER.info("Successfully submitted {} tactics ({} failed)", successCount, failCount);
                lastSyncTime = currentTime;
                
                // Clear submitted tactics
                pendingSubmissions.clear();
                totalDataPointsContributed = 0;
            } else {
                LOGGER.warn("Failed to submit any tactics to API");
            }
            
        } catch (Exception e) {
            LOGGER.error("Error submitting tactics: {}", e.getMessage());
        }
    }
    
    /**
     * Download global tactics from Cloudflare API
     */
    private void downloadGlobalTactics() {
        if (!syncEnabled) return;
        
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastPullTime < PULL_INTERVAL_MS) {
            return; // Too soon
        }
        
        try {
            LOGGER.info("Downloading global tactics from API...");
            
            Map<String, Object> tacticsData = apiClient.downloadTactics();
            
            if (tacticsData != null && !tacticsData.isEmpty()) {
                applyGlobalTactics(tacticsData);
                lastPullTime = currentTime;
                totalDataPointsDownloaded++;
                
                LOGGER.info("Successfully downloaded and applied global tactics");
            } else {
                LOGGER.warn("No global tactics available from API");
            }
            
        } catch (Exception e) {
            LOGGER.error("Error downloading tactics: {}", e.getMessage());
        }
    }
    
    /**
     * Apply downloaded global tactics to local AI systems
     * REVOLUTIONARY: Makes tactics from ALL mob types available for cross-species learning
     */
    @SuppressWarnings("unchecked")
    private void applyGlobalTactics(Map<String, Object> tacticsData) {
        try {
            Map<String, Object> tactics = (Map<String, Object>) tacticsData.get("tactics");
            if (tactics == null) return;
            
            int totalTacticsLoaded = 0;
            
            for (Map.Entry<String, Object> entry : tactics.entrySet()) {
                String mobType = entry.getKey();
                Map<String, Object> mobData = (Map<String, Object>) entry.getValue();
                
                List<Map<String, Object>> tacticList = (List<Map<String, Object>>) mobData.get("tactics");
                if (tacticList != null && !tacticList.isEmpty()) {
                    LOGGER.debug("Received {} global tactics for {}", tacticList.size(), mobType);
                    
                    // Store tactics in global pool for cross-mob learning
                    Map<String, GlobalTactic> mobTactics = globalTacticPool.computeIfAbsent(
                        mobType, k -> new ConcurrentHashMap<>());
                    
                    for (Map<String, Object> tacticData : tacticList) {
                        String action = (String) tacticData.get("action");
                        Object avgRewardObj = tacticData.get("avgReward");
                        
                        float avgReward = 0.0f;
                        if (avgRewardObj instanceof Number) {
                            avgReward = ((Number) avgRewardObj).floatValue();
                        }
                        
                        GlobalTactic tactic = new GlobalTactic(
                            mobType, 
                            action, 
                            avgReward,
                            System.currentTimeMillis()
                        );
                        
                        mobTactics.put(action, tactic);
                        totalTacticsLoaded++;
                    }
                    
                    // Log best performing tactics
                    for (int i = 0; i < Math.min(3, tacticList.size()); i++) {
                        Map<String, Object> tactic = tacticList.get(i);
                        LOGGER.info("Top {} tactic: {} (avg reward: {})", 
                            mobType, tactic.get("action"), tactic.get("avgReward"));
                    }
                }
            }
            
            if (totalTacticsLoaded > 0) {
                LOGGER.info("✓ Loaded {} global tactics from {} mob types into cross-species pool", 
                    totalTacticsLoaded, globalTacticPool.size());
                
                // Prune to prevent unbounded growth
                pruneGlobalTacticPool();
                
                // Randomly resurrect some pruned tactics for exploration
                if (Math.random() < EXPLORATION_RATE) {
                    reintroducePrunedTactics(tacticsData);
                }
            }
            
        } catch (Exception e) {
            LOGGER.error("Error applying global tactics: {}", e.getMessage());
        }
    }
    
    /**
     * Prune global tactic pool to prevent unbounded memory growth
     * Keeps only the best tactics and removes stale/low-performing entries
     */
    private void pruneGlobalTacticPool() {
        int totalTacticsBefore = 0;
        int totalTacticsAfter = 0;
        long currentTime = System.currentTimeMillis();
        
        for (Map.Entry<String, Map<String, GlobalTactic>> mobEntry : globalTacticPool.entrySet()) {
            String mobType = mobEntry.getKey();
            Map<String, GlobalTactic> tactics = mobEntry.getValue();
            
            totalTacticsBefore += tactics.size();
            
            // Remove expired tactics (older than 7 days)
            tactics.entrySet().removeIf(entry -> {
                long age = currentTime - entry.getValue().timestamp;
                return age > TACTIC_EXPIRY_MS;
            });
            
            // Remove low-performing tactics
            tactics.entrySet().removeIf(entry -> 
                entry.getValue().avgReward < MIN_REWARD_THRESHOLD
            );
            
            // If still too many, keep only top performers
            if (tactics.size() > MAX_TACTICS_PER_MOB) {
                List<Map.Entry<String, GlobalTactic>> sortedTactics = new ArrayList<>(tactics.entrySet());
                sortedTactics.sort((a, b) -> 
                    Float.compare(b.getValue().avgReward, a.getValue().avgReward)
                );
                
                // Keep top MAX_TACTICS_PER_MOB
                Set<String> toKeep = new HashSet<>();
                for (int i = 0; i < Math.min(MAX_TACTICS_PER_MOB, sortedTactics.size()); i++) {
                    toKeep.add(sortedTactics.get(i).getKey());
                }
                
                tactics.keySet().retainAll(toKeep);
            }
            
            totalTacticsAfter += tactics.size();
        }
        
        // Global size check - if still too large, prune across all mobs
        if (totalTacticsAfter > MAX_TOTAL_TACTICS) {
            pruneGlobalPoolAcrossAllMobs();
            
            // Recount after global pruning
            totalTacticsAfter = 0;
            for (Map<String, GlobalTactic> tactics : globalTacticPool.values()) {
                totalTacticsAfter += tactics.size();
            }
        }
        
        if (totalTacticsBefore > totalTacticsAfter) {
            LOGGER.info("Pruned global tactic pool: {} -> {} tactics ({} removed)",
                totalTacticsBefore, totalTacticsAfter, totalTacticsBefore - totalTacticsAfter);
        }
    }
    
    /**
     * Aggressive pruning when total pool exceeds maximum size
     * Keeps only the absolute best tactics across all mob types
     */
    private void pruneGlobalPoolAcrossAllMobs() {
        // Collect ALL tactics from all mobs
        List<Map.Entry<String, GlobalTactic>> allTactics = new ArrayList<>();
        Map<GlobalTactic, String> tacticToMobType = new HashMap<>();
        
        for (Map.Entry<String, Map<String, GlobalTactic>> mobEntry : globalTacticPool.entrySet()) {
            String mobType = mobEntry.getKey();
            for (Map.Entry<String, GlobalTactic> tacticEntry : mobEntry.getValue().entrySet()) {
                allTactics.add(tacticEntry);
                tacticToMobType.put(tacticEntry.getValue(), mobType);
            }
        }
        
        // Sort by reward (descending)
        allTactics.sort((a, b) -> 
            Float.compare(b.getValue().avgReward, a.getValue().avgReward)
        );
        
        // Keep only top MAX_TOTAL_TACTICS globally
        Set<String> tacticsToKeep = new HashSet<>();
        Map<String, Set<String>> keepPerMob = new HashMap<>();
        
        for (int i = 0; i < Math.min(MAX_TOTAL_TACTICS, allTactics.size()); i++) {
            GlobalTactic tactic = allTactics.get(i).getValue();
            String mobType = tacticToMobType.get(tactic);
            String actionKey = allTactics.get(i).getKey();
            
            keepPerMob.computeIfAbsent(mobType, k -> new HashSet<>()).add(actionKey);
        }
        
        // Apply pruning to each mob's tactics
        for (Map.Entry<String, Map<String, GlobalTactic>> mobEntry : globalTacticPool.entrySet()) {
            String mobType = mobEntry.getKey();
            Map<String, GlobalTactic> tactics = mobEntry.getValue();
            
            Set<String> toKeep = keepPerMob.getOrDefault(mobType, Collections.emptySet());
            tactics.keySet().retainAll(toKeep);
        }
        
        LOGGER.warn("Aggressive pruning applied - global pool exceeded {} tactics", MAX_TOTAL_TACTICS);
    }
    
    /**
     * Randomly reintroduce pruned tactics for exploration (prevents local optima)
     * 15% chance to resurrect 5 random "bad" tactics per mob to test if they're good now
     */
    @SuppressWarnings("unchecked")
    private void reintroducePrunedTactics(Map<String, Object> fullTacticsData) {
        try {
            Map<String, Object> allTactics = (Map<String, Object>) fullTacticsData.get("tactics");
            if (allTactics == null) return;
            
            Random random = new Random();
            int resurrectedCount = 0;
            
            for (Map.Entry<String, Object> entry : allTactics.entrySet()) {
                String mobType = entry.getKey();
                Map<String, Object> mobData = (Map<String, Object>) entry.getValue();
                List<Map<String, Object>> allMobTactics = (List<Map<String, Object>>) mobData.get("tactics");
                
                if (allMobTactics == null || allMobTactics.isEmpty()) continue;
                
                // Get currently active tactics for this mob
                Map<String, GlobalTactic> activeTactics = globalTacticPool.getOrDefault(
                    mobType, new ConcurrentHashMap<>());
                
                // Find tactics that were pruned (in full download but not in active pool)
                List<Map<String, Object>> prunedTactics = new ArrayList<>();
                for (Map<String, Object> tacticData : allMobTactics) {
                    String action = (String) tacticData.get("action");
                    if (!activeTactics.containsKey(action)) {
                        prunedTactics.add(tacticData);
                    }
                }
                
                if (prunedTactics.isEmpty()) continue;
                
                // Resurrect EXPLORATION_TACTICS_PER_MOB random pruned tactics
                Collections.shuffle(prunedTactics, random);
                int toResurrect = Math.min(EXPLORATION_TACTICS_PER_MOB, prunedTactics.size());
                
                for (int i = 0; i < toResurrect; i++) {
                    Map<String, Object> tacticData = prunedTactics.get(i);
                    String action = (String) tacticData.get("action");
                    Object avgRewardObj = tacticData.get("avgReward");
                    
                    float avgReward = 0.0f;
                    if (avgRewardObj instanceof Number) {
                        avgReward = ((Number) avgRewardObj).floatValue();
                    }
                    
                    // Create resurrected tactic with reset timestamp (fresh start)
                    GlobalTactic resurrected = new GlobalTactic(
                        mobType,
                        action,
                        avgReward,
                        System.currentTimeMillis() // Fresh timestamp for re-testing
                    );
                    
                    activeTactics.put(action, resurrected);
                    resurrectedCount++;
                    
                    LOGGER.debug("🔄 EXPLORATION: Resurrected {} tactic '{}' (reward: {:.2f}) for re-testing",
                        mobType, action, avgReward);
                }
            }
            
            if (resurrectedCount > 0) {
                LOGGER.info("🔄 Exploration mode: Resurrected {} previously pruned tactics for re-testing",
                    resurrectedCount);
            }
            
        } catch (Exception e) {
            LOGGER.error("Error reintroducing pruned tactics: {}", e.getMessage());
        }
    }
    
    /**
     * Get all global tactics (for cross-mob learning)
     */
    public Map<String, Map<String, GlobalTactic>> getGlobalTacticPool() {
        return new HashMap<>(globalTacticPool);
    }
    
    /**
     * Get best global tactics regardless of mob type (for emergent learning)
     */
    public List<GlobalTactic> getBestGlobalTactics(int limit) {
        List<GlobalTactic> allTactics = new ArrayList<>();
        
        for (Map<String, GlobalTactic> mobTactics : globalTacticPool.values()) {
            allTactics.addAll(mobTactics.values());
        }
        
        // Sort by average reward (descending)
        allTactics.sort((a, b) -> Float.compare(b.avgReward, a.avgReward));
        
        return allTactics.subList(0, Math.min(limit, allTactics.size()));
    }
    
    /**
     * Shutdown federated learning system
     */
    public void shutdown() {
        LOGGER.info("Shutting down Federated Learning system...");
        
        if (syncEnabled) {
            // Final submission before shutdown
            submitLocalTactics();
            
            // Shutdown API client
            apiClient.shutdown();
        }
        
        syncExecutor.shutdown();
        try {
            syncExecutor.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            LOGGER.warn("Federated Learning shutdown interrupted");
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * Get sync statistics
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("enabled", syncEnabled);
        stats.put("pending_submissions", pendingSubmissions.size());
        stats.put("contributed_data_points", totalDataPointsContributed);
        stats.put("downloaded_updates", totalDataPointsDownloaded);
        stats.put("last_sync_ms_ago", System.currentTimeMillis() - lastSyncTime);
        stats.put("last_pull_ms_ago", System.currentTimeMillis() - lastPullTime);
        
        if (syncEnabled && apiClient != null) {
            stats.put("api_status", apiClient.getStatusString());
        }
        
        return stats;
    }
    
    /**
     * Get status string for commands/debugging
     */
    public String getStatusString() {
        if (!syncEnabled) {
            return "Federated Learning: DISABLED";
        }
        
        return String.format(
            "Federated Learning: ACTIVE | Pending: %d tactics | Contributed: %d points | Downloaded: %d updates",
            pendingSubmissions.size(),
            totalDataPointsContributed,
            totalDataPointsDownloaded
        );
    }
    
    /**
     * Test Cloudflare connection (called on startup)
     */
    public boolean testConnection() {
        if (!syncEnabled || apiClient == null) {
            return false;
        }
        
        try {
            // Try to download tactics to test connection
            Map<String, Object> result = apiClient.downloadTactics();
            return result != null;
        } catch (Exception e) {
            LOGGER.error("Connection test failed: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * Force immediate sync (called during auto-save)
     */
    public void forceSyncNow() {
        if (!syncEnabled) {
            return;
        }
        
        LOGGER.info("[Federated Learning] Force sync triggered - uploading and downloading...");
        
        // Submit any pending local tactics (bypassing time check)
        forceSubmitLocalTactics();
        
        // Download latest global tactics (bypassing time check)
        forceDownloadGlobalTactics();
        
        LOGGER.info("[Federated Learning] Force sync completed");
    }
    
    /**
     * Force submit local tactics (ignores time interval)
     */
    private void forceSubmitLocalTactics() {
        if (!syncEnabled || pendingSubmissions.isEmpty()) {
            LOGGER.debug("No pending tactics to submit");
            return;
        }
        
        try {
            LOGGER.info("Force uploading {} local tactics to global repository...", pendingSubmissions.size());
            
            int successCount = 0;
            int failCount = 0;
            
            // Submit each pending tactic
            for (TacticSubmission submission : pendingSubmissions.values()) {
                boolean success = apiClient.submitTactic(
                    submission.mobType,
                    submission.action,
                    submission.getAverageReward(),
                    submission.getSuccessRate() > 0.5f ? "success" : "failure"
                );
                
                if (success) {
                    successCount++;
                } else {
                    failCount++;
                }
            }
            
            if (successCount > 0) {
                LOGGER.info("✓ Uploaded {} tactics to Cloudflare ({} failed)", successCount, failCount);
                lastSyncTime = System.currentTimeMillis();
                
                // Clear submitted tactics
                pendingSubmissions.clear();
                totalDataPointsContributed = 0;
            } else {
                LOGGER.warn("⚠ Failed to upload any tactics to API");
            }
            
        } catch (Exception e) {
            LOGGER.error("Error force-submitting tactics: {}", e.getMessage());
        }
    }
    
    /**
     * Force download global tactics (ignores time interval)
     */
    private void forceDownloadGlobalTactics() {
        if (!syncEnabled) {
            return;
        }
        
        try {
            LOGGER.info("Force downloading global tactics from Cloudflare...");
            
            Map<String, Object> tacticsData = apiClient.downloadTactics();
            
            if (tacticsData != null && !tacticsData.isEmpty()) {
                applyGlobalTactics(tacticsData);
                lastPullTime = System.currentTimeMillis();
                totalDataPointsDownloaded++;
                
                LOGGER.info("✓ Downloaded and applied global tactics from {} servers", 
                    tacticsData.getOrDefault("server_count", "unknown"));
            } else {
                LOGGER.warn("⚠ No global tactics available from API yet");
            }
            
        } catch (Exception e) {
            LOGGER.error("Error force-downloading tactics: {}", e.getMessage());
        }
    }
    
    /**
     * Check if federated learning is enabled
     */
    public boolean isEnabled() {
        return syncEnabled;
    }
    
    // ==================== Inner Classes ====================
    
    /**
     * Tactic submission aggregator - collects outcomes before API submission
     */
    private static class TacticSubmission {
        public final String mobType;
        public final String action;
        
        private float totalReward = 0.0f;
        private int successCount = 0;
        private int totalCount = 0;
        
        public TacticSubmission(String mobType, String action) {
            this.mobType = mobType;
            this.action = action;
        }
        
        public void addOutcome(float reward, boolean success) {
            totalReward += reward;
            totalCount++;
            if (success) successCount++;
        }
        
        public float getAverageReward() {
            return totalCount > 0 ? totalReward / totalCount : 0.0f;
        }
        
        public float getSuccessRate() {
            return totalCount > 0 ? (float) successCount / totalCount : 0.5f;
        }
    }
    
    /**
     * Global tactic learned by the collective AI across all servers
     * Available for cross-mob emergent learning
     */
    public static class GlobalTactic {
        public final String originalMobType;  // Which mob type originally learned this
        public final String action;
        public final float avgReward;
        public final long timestamp;
        
        public GlobalTactic(String originalMobType, String action, float avgReward, long timestamp) {
            this.originalMobType = originalMobType;
            this.action = action;
            this.avgReward = avgReward;
            this.timestamp = timestamp;
        }
        
        @Override
        public String toString() {
            return String.format("%s's %s (reward: %.2f)", originalMobType, action, avgReward);
        }
    }
}
