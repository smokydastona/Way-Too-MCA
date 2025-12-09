package com.minecraft.gancity.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
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
    
    // Statistics
    private long totalSubmissions = 0;
    private long totalDownloads = 0;
    private long failedSubmissions = 0;
    private long failedDownloads = 0;
    private long lastSuccessfulSync = 0;
    
    public CloudflareAPIClient(String apiEndpoint) {
        this.apiEndpoint = apiEndpoint.endsWith("/") ? apiEndpoint : apiEndpoint + "/";
        LOGGER.info("CloudflareAPIClient initialized - Endpoint: {}", this.apiEndpoint);
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
                                                         float reward, String outcome) {
        return CompletableFuture.supplyAsync(() -> 
            submitTactic(mobType, action, reward, outcome), executor
        );
    }
    
    /**
     * Submit a learned tactic to the global repository (blocking)
     */
    public boolean submitTactic(String mobType, String action, float reward, String outcome) {
        try {
            // Build JSON payload
            JsonObject payload = new JsonObject();
            payload.addProperty("mobType", mobType);
            payload.addProperty("action", action);
            payload.addProperty("reward", reward);
            payload.addProperty("outcome", outcome);
            payload.addProperty("timestamp", System.currentTimeMillis());
            
            String jsonPayload = gson.toJson(payload);
            
            // Make HTTP POST request
            String response = sendPostRequest("api/submit-tactics", jsonPayload);
            
            if (response != null) {
                totalSubmissions++;
                lastSuccessfulSync = System.currentTimeMillis();
                LOGGER.debug("Submitted tactic: {} - {} (reward: {})", mobType, action, reward);
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
     * Download global tactics from all servers (blocking)
     * 
     * @return Map of mob types to tactic data, or empty map if failed
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> downloadTactics() {
        try {
            String response = sendGetRequest("api/download-tactics");
            
            if (response != null) {
                totalDownloads++;
                lastSuccessfulSync = System.currentTimeMillis();
                
                // Parse JSON response
                Map<String, Object> data = gson.fromJson(response, Map.class);
                LOGGER.info("Downloaded global tactics - Version: {}", data.get("version"));
                
                return data;
            } else {
                failedDownloads++;
                return Map.of();
            }
            
        } catch (Exception e) {
            failedDownloads++;
            LOGGER.warn("Failed to download tactics: {}", e.getMessage());
            return Map.of();
        }
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
        
        return Map.of();
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
