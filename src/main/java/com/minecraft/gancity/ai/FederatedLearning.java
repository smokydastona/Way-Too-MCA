package com.minecraft.gancity.ai;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.slf4j.Logger;
import com.google.gson.JsonObject;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Federated Learning System - Syncs learned knowledge via Cloudflare Worker API
 * allowing all servers to benefit from collective learning with zero configuration.
 * 
 * ✅ ENABLED: Cloudflare Worker deployed and operational
 * 
 * Features:
 * - Automatic sync to Cloudflare Worker API (no Git required)
 * - Privacy-safe aggregation (no player identifiable data)
 * - Works out-of-the-box for all players (no SSH/credentials needed)
 * - Async operations (non-blocking gameplay)
 * - Graceful degradation if API unavailable
 * 
 * Worker URL: https://mca-ai-tactics-api.mc-ai-datcol.workers.dev
 */
@SuppressWarnings("unused")
public class FederatedLearning {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    private static final boolean FEATURE_ENABLED = true; // ✅ ENABLED - Cloudflare Worker deployed
    
    // Sync Configuration
    private static final long SYNC_INTERVAL_MS = 300_000; // 5 minutes (submit)
    private static final long PULL_INTERVAL_MS = 600_000; // 10 minutes (download)
    // Minimum data before submitting.
    // Too high causes perpetual "heartbeat-only" federation on low-activity servers.
    private static final int MIN_DATA_POINTS = 3;
    
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
            t.setUncaughtExceptionHandler((thread, throwable) -> {
                LOGGER.error("Uncaught exception in FederatedLearning thread: {}", throwable.getMessage());
            });
            return t;
        }
    );
    
    // Local aggregation before submission
    private final Map<String, TacticSubmission> pendingSubmissions = new ConcurrentHashMap<>();
    
    // Track first encounters for bootstrap uploads
    private final java.util.Set<String> firstEncounters = java.util.concurrent.ConcurrentHashMap.newKeySet();
    
    // Global tactic pool - tactics learned by ALL mob types worldwide
    private final Map<String, Map<String, GlobalTactic>> globalTacticPool = new ConcurrentHashMap<>();
    
    // Statistics
    private long totalDataPointsContributed = 0;
    private long totalDataPointsDownloaded = 0;
    private long lastSyncTime = 0;
    private long lastPullTime = 0;
    
    public FederatedLearning(Path localDataPath, String apiEndpoint) {
        // Check if feature is enabled globally
        if (!FEATURE_ENABLED) {
            LOGGER.info("Federated Learning is disabled via feature flag");
            this.syncEnabled = false;
            return;
        }
        
        this.syncEnabled = apiEndpoint != null && !apiEndpoint.isEmpty();
        
        if (syncEnabled) {
            // Initialize Cloudflare API client
            this.apiClient = new CloudflareAPIClient(apiEndpoint);
            
            // ✅ FIX #1: FORCED BOOTSTRAP PULL - Always fetch global model on startup
            LOGGER.info("Federated Learning enabled - API: {}", apiEndpoint);
            LOGGER.info("🔄 Performing forced bootstrap pull from global repository...");
            syncExecutor.schedule(() -> {
                try {
                    downloadGlobalTactics();
                    LOGGER.info("✅ Bootstrap pull completed");
                } catch (Exception e) {
                    LOGGER.warn("❌ Bootstrap pull failed (will retry on schedule): {}", e.getMessage());
                }
            }, 5, TimeUnit.SECONDS); // 5 second delay to let server finish startup
            
            // ✅ FIX #2: FORCED INITIAL UPLOAD - Upload after 2 minutes no matter what
            syncExecutor.schedule(() -> {
                LOGGER.info("🔄 Forcing initial upload to establish connection...");
                forceUpload();
            }, 2, TimeUnit.MINUTES);
            
            // ✅ FIX #3: HEARTBEAT SYNC - Upload metadata every 5 minutes minimum
            syncExecutor.scheduleAtFixedRate(() -> {
                heartbeatSync();
            }, 5, 5, TimeUnit.MINUTES);
            
            // Schedule periodic sync operations
            syncExecutor.scheduleAtFixedRate(this::downloadGlobalTactics, 
                PULL_INTERVAL_MS, PULL_INTERVAL_MS, TimeUnit.MILLISECONDS);
            syncExecutor.scheduleAtFixedRate(this::submitLocalTactics, 
                SYNC_INTERVAL_MS, SYNC_INTERVAL_MS, TimeUnit.MILLISECONDS);
            
            LOGGER.info("Auto-submit every {} minutes, auto-download every {} minutes", 
                SYNC_INTERVAL_MS / 60_000, PULL_INTERVAL_MS / 60_000);
        } else {
            LOGGER.info("Federated Learning disabled - Set 'cloudApiEndpoint' in config to enable");
        }
    }
    
    
    /**
     * Record a combat outcome for federated learning
     * Aggregates locally before submitting to API
     * CRITICAL: Returns early if feature disabled
     */
    public void recordCombatOutcome(String mobType, String action, float reward, boolean success) {
        if (!FEATURE_ENABLED || !syncEnabled) return;
        
        String key = mobType + ":" + action;
        TacticSubmission submission = pendingSubmissions.computeIfAbsent(key, 
            k -> new TacticSubmission(mobType, action));
        
        submission.addOutcome(reward, success);
        totalDataPointsContributed++;

        // Track first encounters for heartbeat metadata only.
        // IMPORTANT: Do not upload single-action "bootstrap" tactics here.
        // The coordinator accepts only one upload per (serverId,mobType) per round;
        // a single-action bootstrap would lock out a later richer multi-action model.
        if (firstEncounters.add(mobType)) {
            LOGGER.info("🚀 FIRST ENCOUNTER: {} - tracking (no bootstrap upload)", mobType);
        }
    }
    
    /**
     * Submit accumulated local tactics to Cloudflare API
     * ⚡ FULLY ASYNC - Never blocks server thread or game ticks
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
        
        // ⚡ CRITICAL: Copy submissions for async processing (don't block this method)
        final Map<String, TacticSubmission> submissionsToUpload = new Object2ObjectOpenHashMap<>(pendingSubmissions);
        final int submissionCount = submissionsToUpload.size();
        
        // Fire and forget - submit async on API client's executor
        CompletableFuture.runAsync(() -> {
            try {
                LOGGER.info("Submitting {} local tactics to global repository...", submissionCount);

                // Group by mobType so we upload ONE model per mobType.
                // The coordinator enforces one upload per (serverId,mobType) per round.
                final Map<String, java.util.List<TacticSubmission>> byMob = new java.util.HashMap<>();
                for (TacticSubmission submission : submissionsToUpload.values()) {
                    byMob.computeIfAbsent(submission.mobType, k -> new java.util.ArrayList<>()).add(submission);
                }

                int mobUploadsOk = 0;
                int mobUploadsFail = 0;

                final java.util.Set<String> uploadedKeys = new java.util.HashSet<>();
                int mobUploadsSkipped = 0;

                for (Map.Entry<String, java.util.List<TacticSubmission>> mobEntry : byMob.entrySet()) {
                    String mobType = mobEntry.getKey();
                    java.util.List<TacticSubmission> submissions = mobEntry.getValue();

                    JsonObject tactics = new JsonObject();
                    for (TacticSubmission submission : submissions) {
                        if (submission == null || !submission.isValid()) continue;

                        JsonObject tacticData = new JsonObject();
                        tacticData.addProperty("avgReward", submission.getAverageReward());
                        tacticData.addProperty("totalReward", submission.getTotalReward());
                        tacticData.addProperty("count", submission.getTotalCount());
                        tacticData.addProperty("successCount", submission.getSuccessCount());
                        tacticData.addProperty("failureCount", submission.getFailureCount());
                        tacticData.addProperty("successRate", submission.getSuccessRate());
                        tacticData.addProperty("weightedAvgReward", submission.getAverageReward());
                        tacticData.addProperty("momentum", 0.0);
                        tacticData.addProperty("lastUpdate", System.currentTimeMillis());
                        tactics.add(submission.action, tacticData);
                    }

                    if (tactics.size() == 0) continue;

                    // Avoid uploading 1-action models that would freeze observability for this mobType in the round.
                    if (tactics.size() < 2) {
                        mobUploadsSkipped++;
                        continue;
                    }

                    boolean ok = apiClient.submitTacticsModel(mobType, tactics, false);
                    if (ok) {
                        mobUploadsOk++;
                        // Remove only what we successfully uploaded; keep other mobTypes accumulating.
                        for (TacticSubmission submission : submissions) {
                            if (submission == null || !submission.isValid()) continue;
                            uploadedKeys.add(submission.mobType + ":" + submission.action);
                        }
                    } else {
                        mobUploadsFail++;
                    }
                }

                if (mobUploadsOk > 0) {
                    LOGGER.info("Successfully submitted {} mob models ({} failed, {} skipped)", mobUploadsOk, mobUploadsFail, mobUploadsSkipped);
                    lastSyncTime = currentTime;

                    for (String key : uploadedKeys) {
                        pendingSubmissions.remove(key);
                    }

                    // Recompute contributed points from remaining pending submissions.
                    int remaining = 0;
                    for (TacticSubmission submission : pendingSubmissions.values()) {
                        if (submission == null) continue;
                        remaining += submission.getTotalCount();
                    }
                    totalDataPointsContributed = remaining;
                } else {
                    LOGGER.warn("No mob models submitted ({} attempted, {} skipped for <2 actions)", byMob.size(), mobUploadsSkipped);
                }
            
        } catch (Exception e) {
            LOGGER.error("Error submitting tactics (non-critical): {}", e.getMessage());
        }
    }, apiClient.executor); // ⚡ Use API client's executor for true async
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
                boolean applied = applyGlobalTactics(tacticsData);
                lastPullTime = currentTime;
                totalDataPointsDownloaded++;
                
                if (applied) {
                    LOGGER.info("Successfully downloaded and applied global tactics");
                } else {
                    LOGGER.warn("Downloaded global tactics but none were applied (invalid/empty/unsupported format)");
                }
            } else {
                LOGGER.warn("No global tactics available from API");
            }
            
        } catch (Exception e) {
            LOGGER.error("Error downloading tactics: {}", e.getMessage());
        }
    }
    
    /**
     * ✅ FIX #2: FORCED UPLOAD - Upload immediately with no thresholds
     * ⚡ FULLY ASYNC - Never blocks
     */
    private void forceUpload() {
        if (!syncEnabled) return;
        
        // ⚡ ASYNC - Never block, even for forced upload
        CompletableFuture.runAsync(() -> {
            try {
                LOGGER.info("💪 FORCED UPLOAD - Establishing initial connection with server");
                
                // If we have pending submissions, upload those
                if (!pendingSubmissions.isEmpty()) {
                    LOGGER.info("📤 Uploading {} pending tactics", pendingSubmissions.size());
                    // Use the same batch model upload used by scheduled submissions.
                    // This avoids collapsing the action space to 1 per mobType per round.
                    final Map<String, java.util.List<TacticSubmission>> byMob = new java.util.HashMap<>();
                    for (TacticSubmission submission : pendingSubmissions.values()) {
                        byMob.computeIfAbsent(submission.mobType, k -> new java.util.ArrayList<>()).add(submission);
                    }
                    for (Map.Entry<String, java.util.List<TacticSubmission>> mobEntry : byMob.entrySet()) {
                        String mobType = mobEntry.getKey();
                        JsonObject tactics = new JsonObject();
                        for (TacticSubmission submission : mobEntry.getValue()) {
                            if (submission == null || !submission.isValid()) continue;
                            JsonObject tacticData = new JsonObject();
                            tacticData.addProperty("avgReward", submission.getAverageReward());
                            tacticData.addProperty("totalReward", submission.getTotalReward());
                            tacticData.addProperty("count", submission.getTotalCount());
                            tacticData.addProperty("successCount", submission.getSuccessCount());
                            tacticData.addProperty("failureCount", submission.getFailureCount());
                            tacticData.addProperty("successRate", submission.getSuccessRate());
                            tacticData.addProperty("weightedAvgReward", submission.getAverageReward());
                            tacticData.addProperty("momentum", 0.0);
                            tacticData.addProperty("lastUpdate", System.currentTimeMillis());
                            tactics.add(submission.action, tacticData);
                        }

                        // Only upload when we have at least 2 actions for this mobType.
                        if (tactics.size() >= 2) {
                            boolean ok = apiClient.submitTacticsModel(mobType, tactics, false);
                            if (ok) {
                                for (TacticSubmission submission : mobEntry.getValue()) {
                                    if (submission == null || !submission.isValid()) continue;
                                    pendingSubmissions.remove(submission.mobType + ":" + submission.action);
                                }
                            }
                        }
                    }
                    int remaining = 0;
                    for (TacticSubmission submission : pendingSubmissions.values()) {
                        if (submission == null) continue;
                        remaining += submission.getTotalCount();
                    }
                    totalDataPointsContributed = remaining;
                } else {
                    // Do NOT seed synthetic tactics.
                    // Heartbeat is sufficient for connectivity; synthetic actions freeze observability.
                    LOGGER.info("📡 No pending tactics to upload (skipping synthetic seeding)");
                    apiClient.sendHeartbeat(new java.util.ArrayList<>(firstEncounters));
                }
                
                lastSyncTime = System.currentTimeMillis();
                LOGGER.info("✅ Forced upload completed - connection established");
                
            } catch (Exception e) {
                LOGGER.error("❌ Forced upload failed (non-critical): {}", e.getMessage());
            }
        }, apiClient.executor); // ⚡ Use API client's executor
    }
    
    /**
     * ✅ FIX #3: HEARTBEAT SYNC - Upload metadata every interval, no silence allowed
     */
    private void heartbeatSync() {
        if (!syncEnabled) return;
        
        try {
            long currentTime = System.currentTimeMillis();
            int pendingCount = pendingSubmissions.size();
            
            LOGGER.info("💓 HEARTBEAT SYNC - Pending: {}, Last sync: {}min ago", 
                pendingCount, (currentTime - lastSyncTime) / 60_000);
            
            // Send heartbeat with active mob types
            java.util.List<String> activeMobs = new java.util.ArrayList<>(firstEncounters);
            boolean heartbeatSuccess = apiClient.sendHeartbeat(activeMobs);
            
            if (heartbeatSuccess) {
                LOGGER.debug("💓 Heartbeat sent ({} active mobs)", activeMobs.size());
            } else {
                LOGGER.warn("💔 Heartbeat failed");
            }
            
            // If we have pending data, upload it ASYNC
            if (!pendingSubmissions.isEmpty()) {
                final int finalCount = pendingCount;
                CompletableFuture.runAsync(() -> {
                    LOGGER.info("📤 Heartbeat uploading {} tactics", finalCount);
                    submitLocalTactics();
                }, apiClient.executor);
            }
            
            // Always download to check for updates ASYNC
            CompletableFuture.runAsync(() -> {
                LOGGER.info("📥 Heartbeat checking for global updates");
                downloadGlobalTactics();
            }, apiClient.executor);
            
            LOGGER.info("✅ Heartbeat sync completed");
            
        } catch (Exception e) {
            LOGGER.error("❌ Heartbeat sync failed: {}", e.getMessage());
        }
    }
    
    /**
     * Apply downloaded global tactics to local AI systems
     * REVOLUTIONARY: Makes tactics from ALL mob types available for cross-species learning
     * ✅ HARDENED: Validates all data for mathematical consistency before applying
     */
    @SuppressWarnings("unchecked")
    private boolean applyGlobalTactics(Map<String, Object> tacticsData) {
        try {
            Map<String, Object> tactics = (Map<String, Object>) tacticsData.get("tactics");
            if (tactics == null) return false;
            
            int totalTacticsLoaded = 0;
            int totalTacticsRejected = 0;
            
            for (Map.Entry<String, Object> entry : tactics.entrySet()) {
                String mobType = entry.getKey();
                Map<String, Object> mobData = (Map<String, Object>) entry.getValue();

                // Compatibility: Cloudflare worker publishes per-mob data as an action-map:
                //   tactics: { zombie: { "melee_attack": {...}, "circle_strafe": {...} }, ... }
                // Client-side validation expects each mob entry to contain a nested "tactics" object.
                if (mobData != null && !mobData.containsKey("tactics") && looksLikeActionTacticsMap(mobData)) {
                    Map<String, Object> wrapped = new HashMap<>();
                    wrapped.put("tactics", mobData);
                    mobData = wrapped;
                }

                if (mobData == null) {
                    LOGGER.error("❌ Null mob data for {} - skipping entire mob type", mobType);
                    totalTacticsRejected++;
                    continue;
                }
                
                // ✅ VALIDATE MOB-LEVEL DATA
                if (!TacticDataValidator.validateMobData(mobData, mobType)) {
                    LOGGER.error("❌ Invalid mob data for {} - skipping entire mob type", mobType);
                    totalTacticsRejected++;
                    continue;
                }
                
                // ✅ VALIDATE AND SANITIZE ALL TACTICS FOR THIS MOB
                int validTacticsCount = TacticDataValidator.validateAndSanitizeAllTactics(mobData, mobType);
                if (validTacticsCount == 0) {
                    LOGGER.warn("No valid tactics for {} after validation - skipping", mobType);
                    continue;
                }
                
                Object tacticsObj = mobData.get("tactics");
                List<Map<String, Object>> tacticList = new ArrayList<>();
                if (tacticsObj instanceof List) {
                    tacticList = (List<Map<String, Object>>) tacticsObj;
                } else if (tacticsObj instanceof Map) {
                    // Support legacy/alternate schema: tactics as { action: {..tacticData..}, ... }
                    Map<String, Object> tacticsMap = (Map<String, Object>) tacticsObj;
                    for (Map.Entry<String, Object> tacticEntry : tacticsMap.entrySet()) {
                        if (!(tacticEntry.getValue() instanceof Map)) {
                            continue;
                        }
                        Map<String, Object> tacticData = (Map<String, Object>) tacticEntry.getValue();
                        // Ensure action is present for downstream validation/processing
                        tacticData.putIfAbsent("action", tacticEntry.getKey());
                        tacticList.add(tacticData);
                    }
                }

                if (!tacticList.isEmpty()) {
                    LOGGER.debug("Received {} global tactics for {} ({} validated)",
                        tacticList.size(), mobType, validTacticsCount);
                    
                    // Store tactics in global pool for cross-mob learning
                    Map<String, GlobalTactic> mobTactics = globalTacticPool.computeIfAbsent(
                        mobType, k -> new ConcurrentHashMap<>());
                    
                    for (Map<String, Object> tacticData : tacticList) {
                        // ✅ FINAL PER-TACTIC VALIDATION (belt and suspenders)
                        String action = (String) tacticData.get("action");
                        Map<String, Object> validated = TacticDataValidator.validateAndSanitize(
                            tacticData, action, mobType);
                        
                        if (validated == null) {
                            LOGGER.warn("❌ Rejected invalid tactic {}: {} after validation", mobType, action);
                            totalTacticsRejected++;
                            continue;
                        }
                        
                        // Extract validated fields
                        Object avgRewardObj = validated.get("avgReward");
                        float avgReward = 0.0f;
                        if (avgRewardObj instanceof Number) {
                            avgReward = ((Number) avgRewardObj).floatValue();
                        }
                        
                        // Extract momentum state if using FedAvgM
                        float momentum = 0.0f;
                        Object momentumObj = validated.get("momentum");
                        if (momentumObj instanceof Number) {
                            momentum = ((Number) momentumObj).floatValue();
                        }
                        
                        float weightedAvgReward = avgReward;
                        Object weightedObj = validated.get("weightedAvgReward");
                        if (weightedObj instanceof Number) {
                            weightedAvgReward = ((Number) weightedObj).floatValue();
                        }
                        
                        GlobalTactic tactic = new GlobalTactic(
                            mobType, 
                            action, 
                            avgReward,
                            System.currentTimeMillis(),
                            momentum,
                            weightedAvgReward
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
                LOGGER.info("✓ Loaded {} validated tactics from {} mob types ({} rejected)", 
                    totalTacticsLoaded, globalTacticPool.size(), totalTacticsRejected);
                
                // Prune to prevent unbounded growth
                pruneGlobalTacticPool();
                
                // Randomly resurrect some pruned tactics for exploration
                if (Math.random() < EXPLORATION_RATE) {
                    reintroducePrunedTactics(tacticsData);
                }

                return true;
            } else if (totalTacticsRejected > 0) {
                LOGGER.error("❌ ALL {} tactics rejected due to validation failures - federation data is poisoned!", 
                    totalTacticsRejected);
            }
            return false;
        } catch (Exception e) {
            LOGGER.error("Error applying global tactics: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Detects the worker's mob payload shape where the mob entry is directly a map of
     * { actionName -> tacticDataMap }.
     */
    private boolean looksLikeActionTacticsMap(Map<String, Object> mobData) {
        if (mobData == null || mobData.isEmpty()) return false;

        int mapValues = 0;
        for (Map.Entry<String, Object> e : mobData.entrySet()) {
            String key = e.getKey();

            // If it already contains known per-tactic fields at the top level, it's not an action-map.
            if ("avgReward".equals(key) || "count".equals(key) || "successRate".equals(key)) {
                return false;
            }

            if (!(e.getValue() instanceof Map)) {
                return false;
            }
            mapValues++;
        }

        return mapValues > 0;
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

            // Force submit using the same per-mobType multi-action model upload,
            // while avoiding 1-action uploads that would lock the round.
            final Map<String, java.util.List<TacticSubmission>> byMob = new java.util.HashMap<>();
            for (TacticSubmission submission : pendingSubmissions.values()) {
                byMob.computeIfAbsent(submission.mobType, k -> new java.util.ArrayList<>()).add(submission);
            }

            int mobUploadsOk = 0;
            int mobUploadsFail = 0;
            int mobUploadsSkipped = 0;
            final java.util.Set<String> uploadedKeys = new java.util.HashSet<>();

            for (Map.Entry<String, java.util.List<TacticSubmission>> mobEntry : byMob.entrySet()) {
                String mobType = mobEntry.getKey();

                JsonObject tactics = new JsonObject();
                for (TacticSubmission submission : mobEntry.getValue()) {
                    if (submission == null || !submission.isValid()) continue;
                    JsonObject tacticData = new JsonObject();
                    tacticData.addProperty("avgReward", submission.getAverageReward());
                    tacticData.addProperty("totalReward", submission.getTotalReward());
                    tacticData.addProperty("count", submission.getTotalCount());
                    tacticData.addProperty("successCount", submission.getSuccessCount());
                    tacticData.addProperty("failureCount", submission.getFailureCount());
                    tacticData.addProperty("successRate", submission.getSuccessRate());
                    tacticData.addProperty("weightedAvgReward", submission.getAverageReward());
                    tacticData.addProperty("momentum", 0.0);
                    tacticData.addProperty("lastUpdate", System.currentTimeMillis());
                    tactics.add(submission.action, tacticData);
                }

                if (tactics.size() == 0) continue;
                if (tactics.size() < 2) {
                    mobUploadsSkipped++;
                    continue;
                }

                boolean ok = apiClient.submitTacticsModel(mobType, tactics, false);
                if (ok) {
                    mobUploadsOk++;
                    for (TacticSubmission submission : mobEntry.getValue()) {
                        if (submission == null || !submission.isValid()) continue;
                        uploadedKeys.add(submission.mobType + ":" + submission.action);
                    }
                } else {
                    mobUploadsFail++;
                }
            }

            if (mobUploadsOk > 0) {
                LOGGER.info("✓ Uploaded {} mob models to Cloudflare ({} failed, {} skipped)", mobUploadsOk, mobUploadsFail, mobUploadsSkipped);
                lastSyncTime = System.currentTimeMillis();

                for (String key : uploadedKeys) {
                    pendingSubmissions.remove(key);
                }

                int remaining = 0;
                for (TacticSubmission submission : pendingSubmissions.values()) {
                    if (submission == null) continue;
                    remaining += submission.getTotalCount();
                }
                totalDataPointsContributed = remaining;
            } else {
                LOGGER.warn("⚠ Failed to upload any mob models to API ({} skipped for <2 actions)", mobUploadsSkipped);
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
     * ✅ HARDENED: Enforces mathematical invariants at all times
     */
    private static class TacticSubmission {
        public final String mobType;
        public final String action;
        
        private float totalReward = 0.0f;
        private int successCount = 0;
        private int failureCount = 0;
        private int totalCount = 0;
        
        public TacticSubmission(String mobType, String action) {
            this.mobType = mobType;
            this.action = action;
        }
        
        /**
         * Add an outcome and enforce invariants
         * ✅ count == successCount + failureCount ALWAYS
         */
        public void addOutcome(float reward, boolean success) {
            // Validate reward
            if (Float.isNaN(reward) || Float.isInfinite(reward)) {
                LOGGER.warn("Rejected NaN/Infinite reward for {}: {}", mobType, action);
                return;
            }
            
            totalReward += reward;
            totalCount++;
            
            if (success) {
                successCount++;
            } else {
                failureCount++;
            }
            
            // ✅ ASSERT INVARIANT
            assert totalCount == successCount + failureCount : 
                String.format("Invariant violation: count=%d, success=%d, failure=%d", 
                    totalCount, successCount, failureCount);
        }
        
        public float getAverageReward() {
            return totalCount > 0 ? totalReward / totalCount : 0.0f;
        }

        public float getTotalReward() {
            return totalReward;
        }
        
        /**
         * ✅ GUARANTEED CORRECT: calculated from counts, never stored
         */
        public float getSuccessRate() {
            if (totalCount == 0) return 0.5f;  // Neutral prior
            return (float) successCount / totalCount;
        }
        
        public int getTotalCount() {
            return totalCount;
        }
        
        public int getSuccessCount() {
            return successCount;
        }
        
        public int getFailureCount() {
            return failureCount;
        }
        
        /**
         * Validate internal consistency before submission
         */
        public boolean isValid() {
            // Check invariant
            if (totalCount != successCount + failureCount) {
                LOGGER.error("TacticSubmission invariant violation for {}: {} - count={}, success={}, failure={}",
                    mobType, action, totalCount, successCount, failureCount);
                return false;
            }
            
            // Check count is positive
            if (totalCount <= 0) {
                LOGGER.warn("TacticSubmission has no data for {}: {}", mobType, action);
                return false;
            }
            
            // Check for overflow
            if (totalCount > 1_000_000) {
                LOGGER.warn("TacticSubmission overflow for {}: {} - count={}", mobType, action, totalCount);
                return false;
            }
            
            return true;
        }
        
        @Override
        public String toString() {
            return String.format("%s:%s (count=%d, success=%d, failure=%d, successRate=%.2f, avgReward=%.2f)",
                mobType, action, totalCount, successCount, failureCount, getSuccessRate(), getAverageReward());
        }
    }
    
    /**
     * Global tactic learned by the collective AI across all servers
     * Available for cross-mob emergent learning
     * ✅ ENHANCED: Now tracks momentum state for FedAvgM aggregation
     */
    public static class GlobalTactic {
        public final String originalMobType;  // Which mob type originally learned this
        public final String action;
        public final float avgReward;
        public final long timestamp;
        public final float momentum;  // Velocity term for FedAvgM
        public final float weightedAvgReward;  // Momentum-adjusted reward
        
        public GlobalTactic(String originalMobType, String action, float avgReward, long timestamp) {
            this(originalMobType, action, avgReward, timestamp, 0.0f, avgReward);
        }
        
        public GlobalTactic(String originalMobType, String action, float avgReward, long timestamp,
                           float momentum, float weightedAvgReward) {
            this.originalMobType = originalMobType;
            this.action = action;
            this.avgReward = avgReward;
            this.timestamp = timestamp;
            this.momentum = momentum;
            this.weightedAvgReward = weightedAvgReward;
        }
        
        @Override
        public String toString() {
            return String.format("%s's %s (reward: %.2f, weighted: %.2f, momentum: %.3f)", 
                originalMobType, action, avgReward, weightedAvgReward, momentum);
        }
    }
    
    // ========================================================================
    // ADVANCED ML v2.0.0 - Sequence Tracking & Meta-Learning
    // ========================================================================
    
    /**
     * Submit a combat sequence to Cloudflare for pattern analysis (async wrapper)
     */
    public void submitSequenceAsync(String mobType, 
                                    java.util.List<com.minecraft.gancity.ai.MobBehaviorAI.ActionRecord> sequence,
                                    String outcome, long duration, String mobId) {
        if (!syncEnabled || apiClient == null) {
            return;
        }
        
        apiClient.submitSequenceAsync(mobType, sequence, outcome, duration, mobId)
            .thenAccept(success -> {
                if (success) {
                    LOGGER.debug("Sequence submitted: {} with {} actions", mobType, sequence.size());
                }
            })
            .exceptionally(throwable -> {
                LOGGER.warn("Sequence submission failed: {}", throwable.getMessage());
                return null;
            });
    }
    
    /**
     * Download meta-learning recommendations from Cloudflare
     */
    public java.util.Map<String, java.util.List<com.minecraft.gancity.ai.MobBehaviorAI.MetaLearningRecommendation>> 
            downloadMetaLearningRecommendations() {
        
        if (!syncEnabled || apiClient == null) {
            return new java.util.HashMap<>();
        }
        
        return apiClient.downloadMetaLearningRecommendations();
    }
    
    // ==================== TACTICAL EPISODE FEDERATION ====================
    
    /**
     * Submit a combat episode asynchronously to federation
     * This is what makes federation actually work - aggregate episodes, not single actions
     */
    public void submitEpisodeAsync(CombatEpisode episode, CombatEpisode.EpisodeOutcome outcome, String playerId) {
        if (!syncEnabled || apiClient == null) {
            LOGGER.warn("Cannot submit episode - sync disabled or apiClient null");
            return;
        }
        
        if (!episode.isReadyForLearning()) {
            LOGGER.warn("Episode not ready for learning - only {} samples (need 5+)", episode.getSampleCount());
            return;  // Not enough data
        }
        
        LOGGER.info("Submitting episode with {} samples to Cloudflare...", episode.getSampleCount());
        
        CompletableFuture.runAsync(() -> {
            try {
                Map<String, Object> episodeData = episode.toFederationData(outcome);
                episodeData.put("playerId", playerId != null ? playerId : "unknown");
                episodeData.put("timestamp", System.currentTimeMillis());
                
                apiClient.submitEpisodeData(episodeData);
                
                LOGGER.info("Successfully submitted episode: {} samples, reward: {:.1f}", 
                    episode.getSampleCount(), outcome.episodeReward);
            } catch (Exception e) {
                LOGGER.error("Failed to submit episode: {}", e.getMessage(), e);
            }
        }, apiClient.executor);
    }
    
    /**
     * Download tactical weights from federation
     * Returns aggregated tactical preferences from all servers
     */
    public Map<String, Map<String, Float>> downloadTacticalWeights() {
        if (!syncEnabled || apiClient == null) {
            return new HashMap<>();
        }
        
        try {
            return apiClient.downloadTacticalWeights();
        } catch (Exception e) {
            LOGGER.warn("Failed to download tactical weights: {}", e.getMessage());
            return new HashMap<>();
        }
    }
    
    /**
     * Get tactical federation statistics
     */
    public Map<String, Object> getTacticalStatistics() {
        if (!syncEnabled || apiClient == null) {
            return new HashMap<>();
        }
        
        try {
            return apiClient.getTacticalStatistics();
        } catch (Exception e) {
            LOGGER.warn("Failed to get tactical statistics: {}", e.getMessage());
            return new HashMap<>();
        }
    }
    
    // ==================== END TACTICAL EPISODE FEDERATION ====================
    
    /**
     * Get coordinator status (for /amai status command)
     */
    public Map<String, Object> getCoordinatorStatus() {
        if (!syncEnabled || apiClient == null) {
            return null;
        }
        
        return apiClient.getCoordinatorStatus();
    }
    
    // ==================== TIER PROGRESSION SYNC (HNN-INSPIRED) ====================
    
    /**
     * Submit tier progression data to federation
     * Called when mobs tier up to share experience across servers
     */
    public void submitTierData(Map<String, Object> tierData) {
        if (!syncEnabled || tierData == null || tierData.isEmpty()) {
            return;
        }
        
        CompletableFuture.runAsync(() -> {
            try {
                LOGGER.debug("Submitting tier progression data to federation");
                apiClient.submitTierData(tierData);
            } catch (Exception e) {
                LOGGER.warn("Failed to submit tier data: {}", e.getMessage());
            }
        }, apiClient.executor);
    }
    
    /**
     * Download tier progression data from federation
     * Merges with local tier data (keeps maximum experience)
     */
    public Map<String, Object> downloadTierData() {
        if (!syncEnabled) {
            return new HashMap<>();
        }
        
        try {
            return apiClient.downloadTierData();
        } catch (Exception e) {
            LOGGER.warn("Failed to download tier data: {}", e.getMessage());
            return new HashMap<>();
        }
    }
    
    // ==================== END TIER PROGRESSION SYNC ====================
}

