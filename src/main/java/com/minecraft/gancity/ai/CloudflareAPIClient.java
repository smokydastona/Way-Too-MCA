package com.minecraft.gancity.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.slf4j.Logger;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
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
    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "CloudflareAPI-Worker");
        t.setDaemon(true);
        return t;
    });
    
    // Performance optimizations
    private final TacticsCache cache = new TacticsCache(); // 5min TTL cache
    private final DirtyFlagTracker dirtyTracker = new DirtyFlagTracker();
    
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
        try {
            // Mark as dirty for potential batching
            dirtyTracker.markDirty(mobType, action);
            
            // Calculate tier based on win rate
            TacticTier tier = TacticTier.fromWinRate(winRate);
            
            // Build JSON payload
            JsonObject payload = new JsonObject();
            payload.addProperty("mobType", mobType);
            payload.addProperty("action", action);
            payload.addProperty("reward", reward);
            payload.addProperty("outcome", outcome);
            payload.addProperty("winRate", winRate);
            payload.addProperty("tier", tier.getName());
            payload.addProperty("timestamp", System.currentTimeMillis());
            
            String jsonPayload = gson.toJson(payload);
            
            // Try GZIP compression if data is large enough
            byte[] compressed = CompressionUtil.compress(jsonPayload);
            String response;
            
            if (compressed != null) {
                // Send compressed data
                response = sendCompressedPostRequest("api/submit-tactics", compressed);
                compressionBytesSaved += (jsonPayload.getBytes(StandardCharsets.UTF_8).length - compressed.length);
                LOGGER.debug("Submitted compressed tactic: {} - {} ({} bytes saved)", 
                    mobType, action, jsonPayload.length() - compressed.length);
            } else {
                // Send uncompressed
                response = sendPostRequest("api/submit-tactics", jsonPayload);
                LOGGER.debug("Submitted tactic: {} - {} (reward: {})", mobType, action, reward);
            }
            
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
     * Downloads from: https://github.com/smokydastona/Minecraft-machine-learned-collected
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
            LOGGER.info("Using cached tactics data (cache hit #{}})", cacheHits);
            return cached;
        }
        
        cacheMisses++;
        LOGGER.debug("Cache miss, downloading from GitHub (miss #{})", cacheMisses);
        
        try {
            // Download directly from GitHub raw content
            String[] mobTypes = {"zombie", "skeleton", "creeper", "spider", "husk", "stray", "wither_skeleton", "enderman"};
            Map<String, Object> tacticsData = new Object2ObjectOpenHashMap<>();
            tacticsData.put("version", "1.0.0");
            tacticsData.put("timestamp", System.currentTimeMillis());
            
            Map<String, Object> tactics = new Object2ObjectOpenHashMap<>();
            
            for (String mobType : mobTypes) {
                String githubUrl = "https://raw.githubusercontent.com/smokydastona/Minecraft-machine-learned-collected/main/federated-data/" 
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
            "API Client Stats - Submissions: %d (%.1f%% success), Downloads: %d, Last sync: %ds ago",
            totalSubmissions,
            successRate,
            totalDownloads,
            (System.currentTimeMillis() - lastSuccessfulSync) / 1000
        );
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
}
