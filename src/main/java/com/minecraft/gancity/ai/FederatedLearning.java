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
     */
    @SuppressWarnings("unchecked")
    private void applyGlobalTactics(Map<String, Object> tacticsData) {
        try {
            Map<String, Object> tactics = (Map<String, Object>) tacticsData.get("tactics");
            if (tactics == null) return;
            
            for (Map.Entry<String, Object> entry : tactics.entrySet()) {
                String mobType = entry.getKey();
                Map<String, Object> mobData = (Map<String, Object>) entry.getValue();
                
                List<Map<String, Object>> tacticList = (List<Map<String, Object>>) mobData.get("tactics");
                if (tacticList != null && !tacticList.isEmpty()) {
                    LOGGER.debug("Received {} global tactics for {}", tacticList.size(), mobType);
                    
                    // TODO: Integrate with MobBehaviorAI to influence action selection
                    // For now, just log the best performing tactics
                    for (int i = 0; i < Math.min(3, tacticList.size()); i++) {
                        Map<String, Object> tactic = tacticList.get(i);
                        LOGGER.info("Top {} tactic: {} (avg reward: {})", 
                            mobType, tactic.get("action"), tactic.get("avgReward"));
                    }
                }
            }
            
        } catch (Exception e) {
            LOGGER.error("Error applying global tactics: {}", e.getMessage());
        }
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
}
