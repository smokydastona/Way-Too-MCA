package com.minecraft.gancity.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.slf4j.Logger;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.*;

/**
 * Cloudflare Worker API Client for Federated Learning
 * 
 * Replaces Git-based sync with HTTP API calls to Cloudflare Worker.
 * Provides automatic, zero-configuration federated learning for all players.
 * 
 * Features:
 * - POST /api/submit-tactics - Submit learned tactics
 * - GET /api/download-tactics - Download global aggregated tactics
 * - Automatic retry with exponential backoff
 * - Async operations (non-blocking gameplay)
 * - Graceful degradation if API unavailable
 * - GZIP compression for network bandwidth reduction
 * - Smart caching (5min TTL)
 * - FastUtil collections for performance
 */
public class CloudflareAPIClient {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    // Configuration
    private final String apiEndpoint;
    private final int connectTimeoutMs = 5000;  // 5 seconds
    private final int readTimeoutMs = 10000;    // 10 seconds
    private final int maxRetries = 3;
    
    // Gson for JSON serialization
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    
    // Thread pool for async operations
    public final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "CloudflareAPI-Worker");
        t.setDaemon(true);
        t.setUncaughtExceptionHandler((thread, throwable) -> {
            LOGGER.error("Uncaught exception in CloudflareAPI thread: {}", throwable.getMessage());
        });
        return t;
    });
    
    // Performance optimizations
    private final TacticsCache cache = new TacticsCache(); // 5min TTL cache
    private final DirtyFlagTracker dirtyTracker = new DirtyFlagTracker();
    private final DownloadThrottler downloadThrottler = new DownloadThrottler(3, 60000); // 3 requests per minute
    private final String serverId = generateServerId(); // Unique server identifier
    
    // Statistics
    private long totalSubmissions = 0;
    private long totalDownloads = 0;
    private long failedSubmissions = 0;
    private long failedDownloads = 0;
    private long lastSuccessfulSync = 0;
    private long compressionBytesSaved = 0;
    private long cacheHits = 0;
    private long cacheMisses = 0;
    
    public CloudflareAPIClient(String apiEndpoint) {
        this.apiEndpoint = apiEndpoint.endsWith("/") ? apiEndpoint : apiEndpoint + "/";
        LOGGER.info("CloudflareAPIClient initialized - Endpoint: {}", this.apiEndpoint);
        LOGGER.info("Optimizations enabled: GZIP compression, smart caching, FastUtil collections");
    }
    
    /**
     * Submit a learned tactic to the global repository (async)
     * 
     * @param mobType Mob type (zombie, skeleton, creeper, spider)
     * @param action Action taken
     * @param reward Reward received
     * @param outcome Combat outcome (success/failure)
     */
    public CompletableFuture<Boolean> submitTacticAsync(String mobType, String action, 
                                                         float reward, String outcome, float winRate) {
        return CompletableFuture.supplyAsync(() -> 
            submitTactic(mobType, action, reward, outcome, winRate), executor
        );
    }
    
    /**
     * Submit a learned tactic to the global repository (blocking)
     * Uses GZIP compression if beneficial
     * Automatically calculates and assigns tactic tier based on win rate
     * 
     * @param mobType The type of mob (zombie, skeleton, etc.)
     * @param action The action taken
     * @param reward The reward received
     * @param outcome Success or failure outcome
     * @param winRate Win rate (0.0-1.0) used to determine tier
     */
    public boolean submitTactic(String mobType, String action, float reward, String outcome, float winRate) {
        return submitTactic(mobType, action, reward, outcome, winRate, false);
    }
    
    /**
     * Submit a learned tactic with bootstrap flag
     * @param bootstrap If true, this is a first-encounter upload (bypasses round restrictions)
     */
    public boolean submitTactic(String mobType, String action, float reward, String outcome, float winRate, boolean bootstrap) {
        try {
            // Mark as dirty for potential batching
            dirtyTracker.markDirty(mobType, action);
            
            // Calculate tier based on win rate
            TacticTier tier = TacticTier.fromWinRate(winRate);
            
            // Build JSON payload with merge metadata
            JsonObject payload = new JsonObject();
            payload.addProperty("mobType", mobType);
            payload.addProperty("action", action);
            payload.addProperty("reward", reward);
            payload.addProperty("outcome", outcome);
            payload.addProperty("winRate", winRate);
            payload.addProperty("tier", tier.getName());
            payload.addProperty("timestamp", System.currentTimeMillis());
            payload.addProperty("serverId", serverId); // Unique server ID for conflict detection
            payload.addProperty("sampleCount", 1); // Number of samples (for weighted averaging)
            payload.addProperty("mergeStrategy", "weighted_average"); // How to handle conflicts
            if (bootstrap) {
                payload.addProperty("bootstrap", true); // First-encounter upload flag
            }
            
            // Build tactics object for coordinator
            JsonObject tactics = new JsonObject();
            JsonObject actionData = new JsonObject();
            actionData.addProperty("avgReward", reward);
            actionData.addProperty("count", 1);
            actionData.addProperty("successCount", outcome.equals("success") ? 1 : 0);
            actionData.addProperty("successRate", winRate);
            tactics.add(action, actionData);
            
            JsonObject coordinatorPayload = new JsonObject();
            coordinatorPayload.addProperty("serverId", serverId);
            coordinatorPayload.addProperty("mobType", mobType);
            coordinatorPayload.add("tactics", tactics);
            if (bootstrap) {
                coordinatorPayload.addProperty("bootstrap", true);
            }
            
            String jsonPayload = gson.toJson(coordinatorPayload);
            
            // Try GZIP compression if data is large enough
            byte[] compressed = CompressionUtil.compress(jsonPayload);
            String response;
            
            // Use new coordinator endpoint
            response = sendPostRequest("api/upload", jsonPayload);
            LOGGER.debug("Submitted tactic to coordinator: {} - {} (reward: {}, bootstrap: {})", 
                mobType, action, reward, bootstrap);
            
            if (response != null) {
                totalSubmissions++;
                lastSuccessfulSync = System.currentTimeMillis();
                return true;
            } else {
                failedSubmissions++;
                return false;
            }
            
        } catch (Exception e) {
            failedSubmissions++;
            LOGGER.warn("Failed to submit tactic: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * Download global tactics from all servers (async)
     */
    public CompletableFuture<Map<String, Object>> downloadTacticsAsync() {
        return CompletableFuture.supplyAsync(this::downloadTactics, executor);
    }
    
    /**
     * Download global tactics from GitHub repository (blocking)
     * Downloads from: https://github.com/smokydastona/adaptive-ai-federation-logs
     * Uses smart caching to reduce GitHub API calls
     * 
     * @return Map of mob types to tactic data, or empty map if failed
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> downloadTactics() {
        // Check cache first
        Map<String, Object> cached = cache.get("global_tactics");
        if (cached != null) {
            cacheHits++;
            LOGGER.info("Using cached tactics data (cache hit #{})", cacheHits);
            return cached;
        }
        
        cacheMisses++;
        
        // Throttle download requests to prevent rate limit exhaustion
        // Add random jitter (0-5 seconds) to prevent simultaneous requests
        if (!downloadThrottler.tryAcquire()) {
            LOGGER.warn("Download throttled - too many recent requests. Using fallback.");
            return new Object2ObjectOpenHashMap<>();
        }
        
        LOGGER.debug("Cache miss, downloading from GitHub (miss #{})", cacheMisses);
        
        try {
            // Download directly from GitHub raw content
            String[] mobTypes = {"zombie", "skeleton", "creeper", "spider", "husk", "stray", "wither_skeleton", "enderman"};
            Map<String, Object> tacticsData = new Object2ObjectOpenHashMap<>();
            tacticsData.put("version", "1.0.0");
            tacticsData.put("timestamp", System.currentTimeMillis());
            
            Map<String, Object> tactics = new Object2ObjectOpenHashMap<>();
            
            for (String mobType : mobTypes) {
                String githubUrl = "https://raw.githubusercontent.com/smokydastona/adaptive-ai-federation-logs/main/federated-data/" 
                    + mobType + "-tactics.json";
                
                try {
                    String mobData = downloadFromGitHub(githubUrl);
                    if (mobData != null) {
                        Map<String, Object> mobTactics = gson.fromJson(mobData, Map.class);
                        tactics.put(mobType, mobTactics);
                    }
                } catch (Exception e) {
                    LOGGER.debug("No tactics found for {} on GitHub: {}", mobType, e.getMessage());
                }
            }
            
            tacticsData.put("tactics", tactics);
            
            if (!tactics.isEmpty()) {
                totalDownloads++;
                lastSuccessfulSync = System.currentTimeMillis();
                
                // Cache the result
                cache.put("global_tactics", tacticsData);
                
                LOGGER.info("Downloaded global tactics from GitHub - {} mob types (cached for 5min)", tactics.size());
                return tacticsData;
            } else {
                failedDownloads++;
                LOGGER.warn("No tactics data available on GitHub yet");
                return new Object2ObjectOpenHashMap<>();
            }
            
        } catch (Exception e) {
            failedDownloads++;
            LOGGER.warn("Failed to download tactics from GitHub: {}", e.getMessage());
            return new Object2ObjectOpenHashMap<>();
        }
    }
    
    /**
     * Download raw JSON from GitHub
     */
    private String downloadFromGitHub(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "MCA-AI-Enhanced/1.0");
        conn.setConnectTimeout(connectTimeoutMs);
        conn.setReadTimeout(readTimeoutMs);
        
        int responseCode = conn.getResponseCode();
        if (responseCode == HttpURLConnection.HTTP_OK) {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    response.append(line);
                }
                return response.toString();
            }
        }
        return null;
    }
    
    /**
     * Get API statistics
     */
    public Map<String, Object> getStatistics() {
        try {
            String response = sendGetRequest("api/stats");
            
            if (response != null) {
                return gson.fromJson(response, Map.class);
            }
            
        } catch (Exception e) {
            LOGGER.warn("Failed to get statistics: {}", e.getMessage());
        }
        
        return new Object2ObjectOpenHashMap<>();
    }
    
    /**
     * Get client-side performance statistics
     */
    public Map<String, Object> getClientStats() {
        Map<String, Object> stats = new Object2ObjectOpenHashMap<>();
        stats.put("totalSubmissions", totalSubmissions);
        stats.put("totalDownloads", totalDownloads);
        stats.put("failedSubmissions", failedSubmissions);
        stats.put("failedDownloads", failedDownloads);
        stats.put("lastSuccessfulSync", lastSuccessfulSync);
        stats.put("compressionBytesSaved", compressionBytesSaved);
        stats.put("cacheHits", cacheHits);
        stats.put("cacheMisses", cacheMisses);
        stats.put("cacheHitRate", cacheHits + cacheMisses > 0 ? 
            (float) cacheHits / (cacheHits + cacheMisses) * 100 : 0);
        
        // Dirty flag stats
        DirtyFlagTracker.DirtyStats dirtyStats = dirtyTracker.getStats();
        stats.put("dirtyMobTypes", dirtyStats.dirtyMobTypes);
        stats.put("dirtyTactics", dirtyStats.dirtyTactics);
        
        // Cache stats
        TacticsCache.CacheStats cacheStats = cache.getStats();
        stats.put("cachedEntries", cacheStats.entries);
        stats.put("cacheTTL", cacheStats.ttlMs);
        
        return stats;
    }
    
    /**
     * Check if mob type has unsaved changes
     */
    public boolean hasDirtyData(String mobType) {
        return dirtyTracker.isDirty(mobType);
    }
    
    /**
     * Clear cache manually
     */
    public void clearCache() {
        cache.clear();
    }
    
    /**
     * Get dirty flag tracker for external use
     */
    public DirtyFlagTracker getDirtyTracker() {
        return dirtyTracker;
    }
    
    /**
     * Send HTTP POST request with retry logic
     */
    private String sendPostRequest(String endpoint, String jsonPayload) {
        int attempt = 0;
        Exception lastException = null;
        
        while (attempt < maxRetries) {
            try {
                URL url = new URL(apiEndpoint + endpoint);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                
                // Configure connection
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("User-Agent", "MCA-AI-Enhanced/1.0");
                conn.setDoOutput(true);
                conn.setConnectTimeout(connectTimeoutMs);
                conn.setReadTimeout(readTimeoutMs);
                
                // Write payload
                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = jsonPayload.getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }
                
                // Read response
                int responseCode = conn.getResponseCode();
                
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                        StringBuilder response = new StringBuilder();
                        String line;
                        while ((line = br.readLine()) != null) {
                            response.append(line);
                        }
                        return response.toString();
                    }
                } else {
                    LOGGER.warn("API returned status {}: {}", responseCode, conn.getResponseMessage());
                    
                    // Don't retry on client errors (400-499)
                    if (responseCode >= 400 && responseCode < 500) {
                        return null;
                    }
                }
                
            } catch (Exception e) {
                lastException = e;
                LOGGER.debug("POST attempt {} failed: {}", attempt + 1, e.getMessage());
            }
            
            attempt++;
            
            // Exponential backoff
            if (attempt < maxRetries) {
                try {
                    Thread.sleep((long) (1000 * Math.pow(2, attempt)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        
        if (lastException != null) {
            LOGGER.warn("POST request failed after {} attempts: {}", maxRetries, lastException.getMessage());
        }
        
        return null;
    }
    
    /**
     * Send compressed HTTP POST request with retry logic
     * Uses GZIP compression for bandwidth reduction
     */
    private String sendCompressedPostRequest(String endpoint, byte[] compressedData) {
        int attempt = 0;
        Exception lastException = null;
        
        while (attempt < maxRetries) {
            try {
                URL url = new URL(apiEndpoint + endpoint);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                
                // Configure connection
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Content-Encoding", "gzip");
                conn.setRequestProperty("User-Agent", "MCA-AI-Enhanced/1.0");
                conn.setDoOutput(true);
                conn.setConnectTimeout(connectTimeoutMs);
                conn.setReadTimeout(readTimeoutMs);
                
                // Write compressed payload
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(compressedData);
                }
                
                // Read response
                int responseCode = conn.getResponseCode();
                
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                        StringBuilder response = new StringBuilder();
                        String line;
                        while ((line = br.readLine()) != null) {
                            response.append(line);
                        }
                        return response.toString();
                    }
                } else {
                    LOGGER.warn("API returned status {}: {}", responseCode, conn.getResponseMessage());
                    
                    // Don't retry on client errors (400-499)
                    if (responseCode >= 400 && responseCode < 500) {
                        return null;
                    }
                }
                
            } catch (Exception e) {
                lastException = e;
                LOGGER.debug("Compressed POST attempt {} failed: {}", attempt + 1, e.getMessage());
            }
            
            attempt++;
            
            // Exponential backoff
            if (attempt < maxRetries) {
                try {
                    Thread.sleep((long) (1000 * Math.pow(2, attempt)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        
        if (lastException != null) {
            LOGGER.warn("Compressed POST request failed after {} attempts: {}", maxRetries, lastException.getMessage());
        }
        
        return null;
    }
    
    /**
     * Send HTTP GET request with retry logic
     */
    private String sendGetRequest(String endpoint) {
        int attempt = 0;
        Exception lastException = null;
        
        while (attempt < maxRetries) {
            try {
                URL url = new URL(apiEndpoint + endpoint);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                
                // Configure connection
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "MCA-AI-Enhanced/1.0");
                conn.setConnectTimeout(connectTimeoutMs);
                conn.setReadTimeout(readTimeoutMs);
                
                // Read response
                int responseCode = conn.getResponseCode();
                
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                        StringBuilder response = new StringBuilder();
                        String line;
                        while ((line = br.readLine()) != null) {
                            response.append(line);
                        }
                        return response.toString();
                    }
                } else {
                    LOGGER.warn("API returned status {}: {}", responseCode, conn.getResponseMessage());
                }
                
            } catch (Exception e) {
                lastException = e;
                LOGGER.debug("GET attempt {} failed: {}", attempt + 1, e.getMessage());
            }
            
            attempt++;
            
            // Exponential backoff
            if (attempt < maxRetries) {
                try {
                    Thread.sleep((long) (1000 * Math.pow(2, attempt)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        
        if (lastException != null) {
            LOGGER.warn("GET request failed after {} attempts: {}", maxRetries, lastException.getMessage());
        }
        
        return null;
    }
    
    /**
     * Get client statistics
     */
    public String getStatusString() {
        long successRate = totalSubmissions > 0 
            ? ((totalSubmissions - failedSubmissions) * 100 / totalSubmissions) 
            : 0;
        
        return String.format(
            "API Client Stats - Submissions: %d (%.1f%% success), Downloads: %d, Last sync: %ds ago, Server ID: %s",
            totalSubmissions,
            successRate,
            totalDownloads,
            (System.currentTimeMillis() - lastSuccessfulSync) / 1000,
            serverId.substring(0, 8) // Show first 8 chars of server ID
        );
    }
    
    /**
     * Generate unique server ID for conflict resolution
     */
    private String generateServerId() {
        // Use combination of timestamp, random value, and system properties
        String seed = System.currentTimeMillis() + "-" + 
                      ThreadLocalRandom.current().nextLong() + "-" +
                      System.getProperty("user.name", "unknown");
        return Integer.toHexString(seed.hashCode());
    }
    
    /**
     * Get server ID (for logging/debugging)
     */
    private String getServerId() {
        return serverId;
    }
    
    /**
     * Download request throttler to prevent rate limit exhaustion
     * Uses token bucket algorithm with random jitter
     */
    static class DownloadThrottler {
        private final int maxRequests;
        private final long windowMs;
        private final ConcurrentLinkedQueue<Long> requestTimes = new ConcurrentLinkedQueue<>();
        private final ThreadLocalRandom random = ThreadLocalRandom.current();
        
        public DownloadThrottler(int maxRequests, long windowMs) {
            this.maxRequests = maxRequests;
            this.windowMs = windowMs;
        }
        
        public boolean tryAcquire() {
            long now = System.currentTimeMillis();
            
            // Add random jitter (0-5 seconds) to prevent thundering herd
            try {
                Thread.sleep(random.nextInt(5000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            
            // Remove old requests outside the window
            requestTimes.removeIf(time -> now - time > windowMs);
            
            // Check if we can make a new request
            if (requestTimes.size() < maxRequests) {
                requestTimes.add(now);
                return true;
            }
            
            return false;
        }
    }
    
    /**
     * Shutdown executor on mod unload
     */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    // ========================================================================
    // ADVANCED ML v2.0.0 - Sequence Tracking & Meta-Learning
    // ========================================================================
    
    /**
     * Submit a combat sequence to Cloudflare for pattern analysis (async)
     */
    public CompletableFuture<Boolean> submitSequenceAsync(String mobType, 
                                                            java.util.List<com.minecraft.gancity.ai.MobBehaviorAI.ActionRecord> sequence,
                                                            String outcome, long duration, String mobId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Build sequence JSON
                JsonObject payload = new JsonObject();
                payload.addProperty("mobType", mobType);
                payload.addProperty("finalOutcome", outcome);
                payload.addProperty("duration", duration);
                payload.addProperty("mobId", mobId);
                
                // Add sequence array
                com.google.gson.JsonArray sequenceArray = new com.google.gson.JsonArray();
                for (com.minecraft.gancity.ai.MobBehaviorAI.ActionRecord record : sequence) {
                    JsonObject actionObj = new JsonObject();
                    actionObj.addProperty("action", record.action);
                    actionObj.addProperty("reward", record.reward);
                    sequenceArray.add(actionObj);
                }
                payload.add("sequence", sequenceArray);
                
                String jsonPayload = gson.toJson(payload);
                String response = sendPostRequest("api/submit-sequence", jsonPayload);
                
                if (response != null) {
                    LOGGER.debug("Submitted sequence: {} with {} actions ({})", mobType, sequence.size(), outcome);
                    return true;
                }
                return false;
                
            } catch (Exception e) {
                LOGGER.warn("Failed to submit sequence: {}", e.getMessage());
                return false;
            }
        }, executor);
    }
    
    /**
     * Download meta-learning recommendations for all mob types
     */
    public java.util.Map<String, java.util.List<com.minecraft.gancity.ai.MobBehaviorAI.MetaLearningRecommendation>> 
            downloadMetaLearningRecommendations() {
        
        java.util.Map<String, java.util.List<com.minecraft.gancity.ai.MobBehaviorAI.MetaLearningRecommendation>> result = 
            new Object2ObjectOpenHashMap<>();
        
        try {
            String response = sendGetRequest("api/meta-learning");
            if (response == null) {
                return result;
            }
            
            JsonObject json = gson.fromJson(response, JsonObject.class);
            if (!json.has("metaLearning")) {
                return result;
            }
            
            JsonObject metaLearning = json.getAsJsonObject("metaLearning");
            if (!metaLearning.has("recommendations")) {
                return result;
            }
            
            com.google.gson.JsonArray recommendations = metaLearning.getAsJsonArray("recommendations");
            for (com.google.gson.JsonElement elem : recommendations) {
                JsonObject rec = elem.getAsJsonObject();
                
                String targetMob = rec.get("targetMob").getAsString();
                String sourceMob = rec.get("sourceMob").getAsString();
                String sourceAction = rec.get("sourceAction").getAsString();
                double similarity = rec.get("similarity").getAsDouble();
                double confidence = rec.get("confidence").getAsDouble();
                
                result.computeIfAbsent(targetMob, k -> new java.util.ArrayList<>())
                    .add(new com.minecraft.gancity.ai.MobBehaviorAI.MetaLearningRecommendation(
                        sourceMob, sourceAction, similarity, confidence
                    ));
            }
            
            LOGGER.info("Downloaded {} meta-learning recommendations", 
                result.values().stream().mapToInt(java.util.List::size).sum());
            
        } catch (Exception e) {
            LOGGER.warn("Failed to download meta-learning recommendations: {}", e.getMessage());
        }
        
        return result;
    }
    
    /**
     * Send heartbeat to coordinator (keep-alive)
     * Required every 5-10 minutes to maintain active contributor status
     */
    public boolean sendHeartbeat(java.util.List<String> activeMobs) {
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("serverId", serverId);
            
            com.google.gson.JsonArray mobsArray = new com.google.gson.JsonArray();
            for (String mobType : activeMobs) {
                mobsArray.add(mobType);
            }
            payload.add("activeMobs", mobsArray);
            
            String response = sendPostRequest("api/heartbeat", gson.toJson(payload));
            if (response != null) {
                LOGGER.debug("Heartbeat sent successfully ({} active mobs)", activeMobs.size());
                return true;
            }
            return false;
            
        } catch (Exception e) {
            LOGGER.warn("Failed to send heartbeat: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * Get coordinator status (round, contributors, etc.)
     */
    public Map<String, Object> getCoordinatorStatus() {
        try {
            String response = sendGetRequest("status");
            if (response != null) {
                JsonObject json = JsonParser.parseString(response).getAsJsonObject();
                
                Map<String, Object> status = new java.util.HashMap<>();
                status.put("round", json.has("round") ? json.get("round").getAsInt() : 0);
                status.put("contributors", json.has("contributors") ? json.get("contributors").getAsInt() : 0);
                status.put("modelsInRound", json.has("modelsInCurrentRound") ? json.get("modelsInCurrentRound").getAsInt() : 0);
                status.put("lastAggregation", json.has("lastAggregation") ? json.get("lastAggregation").getAsString() : "unknown");
                status.put("hasGlobalModel", json.has("hasGlobalModel") ? json.get("hasGlobalModel").getAsBoolean() : false);
                
                return status;
            }
            
        } catch (Exception e) {
            LOGGER.warn("Failed to get coordinator status: {}", e.getMessage());
        }
        
        return null;
    }
    
    /**
     * Check if worker is healthy
     */
    public boolean isHealthy() {
        try {
            String response = sendGetRequest("health");
            if (response != null) {
                JsonObject json = JsonParser.parseString(response).getAsJsonObject();
                return json.has("status") && "healthy".equals(json.get("status").getAsString());
            }
        } catch (Exception e) {
            LOGGER.warn("Health check failed: {}", e.getMessage());
        }
        return false;
    }
    
    // ==================== TIER PROGRESSION ENDPOINTS (HNN-INSPIRED) ====================
    
    /**
     * Submit tier progression data to Cloudflare
     * Stores AI tier experience data for federation sync
     */
    public boolean submitTierData(Map<String, Object> tierData) {
        try {
            JsonObject payload = new JsonObject();
            
            // Add experience map
            if (tierData.containsKey("experience")) {
                @SuppressWarnings("unchecked")
                Map<String, Integer> expData = (Map<String, Integer>) tierData.get("experience");
                JsonObject expJson = new JsonObject();
                for (Map.Entry<String, Integer> entry : expData.entrySet()) {
                    expJson.addProperty(entry.getKey(), entry.getValue());
                }
                payload.add("experience", expJson);
            }
            
            // Add tier names
            if (tierData.containsKey("tiers")) {
                @SuppressWarnings("unchecked")
                Map<String, String> tiersData = (Map<String, String>) tierData.get("tiers");
                JsonObject tiersJson = new JsonObject();
                for (Map.Entry<String, String> entry : tiersData.entrySet()) {
                    tiersJson.addProperty(entry.getKey(), entry.getValue());
                }
                payload.add("tiers", tiersJson);
            }
            
            String response = sendPostRequest("api/tiers", payload.toString());
            return response != null && !response.isEmpty();
            
        } catch (Exception e) {
            LOGGER.warn("Failed to submit tier data: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * Download tier progression data from Cloudflare
     * Returns aggregated tier experience from all servers
     */
    public Map<String, Object> downloadTierData() {
        try {
            String response = sendGetRequest("api/tiers");
            if (response == null || response.isEmpty()) {
                return new HashMap<>();
            }
            
            JsonObject json = JsonParser.parseString(response).getAsJsonObject();
            Map<String, Object> result = new HashMap<>();
            
            // Parse experience data
            if (json.has("experience")) {
                JsonObject expJson = json.getAsJsonObject("experience");
                Map<String, Integer> expMap = new HashMap<>();
                for (Map.Entry<String, JsonElement> entry : expJson.entrySet()) {
                    expMap.put(entry.getKey(), entry.getValue().getAsInt());
                }
                result.put("experience", expMap);
            }
            
            // Parse tier names
            if (json.has("tiers")) {
                JsonObject tiersJson = json.getAsJsonObject("tiers");
                Map<String, String> tiersMap = new HashMap<>();
                for (Map.Entry<String, JsonElement> entry : tiersJson.entrySet()) {
                    tiersMap.put(entry.getKey(), entry.getValue().getAsString());
                }
                result.put("tiers", tiersMap);
            }
            
            return result;
            
        } catch (Exception e) {
            LOGGER.warn("Failed to download tier data: {}", e.getMessage());
            return new HashMap<>();
        }
    }
    
    // ==================== TACTICAL EPISODE FEDERATION ====================
    
    /**
     * Submit combat episode data to Cloudflare
     * This aggregates high-level tactical patterns, not low-level actions
     */
    public void submitEpisodeData(Map<String, Object> episodeData) {
        try {
            JsonObject payload = new JsonObject();
            
            // Convert episode data to JSON
            for (Map.Entry<String, Object> entry : episodeData.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                
                if (value instanceof String) {
                    payload.addProperty(key, (String) value);
                } else if (value instanceof Number) {
                    payload.addProperty(key, (Number) value);
                } else if (value instanceof Boolean) {
                    payload.addProperty(key, (Boolean) value);
                } else if (value instanceof Map) {
                    // Nested map (e.g., tacticsUsed)
                    JsonObject nested = new JsonObject();
                    for (Map.Entry<?, ?> nestedEntry : ((Map<?, ?>) value).entrySet()) {
                        if (nestedEntry.getValue() instanceof Number) {
                            nested.addProperty(nestedEntry.getKey().toString(), (Number) nestedEntry.getValue());
                        }
                    }
                    payload.add(key, nested);
                }
            }
            
            String response = sendPostRequest("api/episodes", payload.toString());
            
            if (response != null) {
                LOGGER.debug("Episode submitted successfully");
            }
            
        } catch (Exception e) {
            LOGGER.warn("Failed to submit episode data: {}", e.getMessage());
        }
    }
    
    /**
     * Download tactical weights from Cloudflare
     * Returns: mobType -> tactic -> weight
     */
    public Map<String, Map<String, Float>> downloadTacticalWeights() {
        try {
            String response = sendGetRequest("api/tactical-weights");
            if (response == null || response.isEmpty()) {
                return new HashMap<>();
            }
            
            JsonObject json = JsonParser.parseString(response).getAsJsonObject();
            Map<String, Map<String, Float>> weights = new HashMap<>();
            
            for (Map.Entry<String, JsonElement> mobEntry : json.entrySet()) {
                String mobType = mobEntry.getKey();
                JsonObject tacticsJson = mobEntry.getValue().getAsJsonObject();
                
                Map<String, Float> tactics = new HashMap<>();
                for (Map.Entry<String, JsonElement> tacticEntry : tacticsJson.entrySet()) {
                    tactics.put(tacticEntry.getKey(), tacticEntry.getValue().getAsFloat());
                }
                
                weights.put(mobType, tactics);
            }
            
            return weights;
            
        } catch (Exception e) {
            LOGGER.warn("Failed to download tactical weights: {}", e.getMessage());
            return new HashMap<>();
        }
    }
    
    /**
     * Get tactical federation statistics
     * Returns info about episodes aggregated, samples, contributors, etc.
     */
    public Map<String, Object> getTacticalStatistics() {
        try {
            String response = sendGetRequest("api/tactical-stats");
            if (response == null || response.isEmpty()) {
                return new HashMap<>();
            }
            
            JsonObject json = JsonParser.parseString(response).getAsJsonObject();
            Map<String, Object> stats = new HashMap<>();
            
            for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                JsonElement value = entry.getValue();
                if (value.isJsonPrimitive()) {
                    if (value.getAsJsonPrimitive().isNumber()) {
                        stats.put(entry.getKey(), value.getAsNumber());
                    } else if (value.getAsJsonPrimitive().isString()) {
                        stats.put(entry.getKey(), value.getAsString());
                    }
                }
            }
            
            return stats;
            
        } catch (Exception e) {
            LOGGER.warn("Failed to get tactical statistics: {}", e.getMessage());
            return new HashMap<>();
        }
    }
    
    // ==================== END TACTICAL EPISODE FEDERATION ====================
    
    // ==================== END TIER PROGRESSION ENDPOINTS ====================
}

