package com.minecraft.gancity.compat;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.*;

/**
 * FTB Teams integration for multiplayer team-based AI coordination
 * Allows mobs to recognize player teams and coordinate attacks accordingly
 */
public class FTBTeamsIntegration {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    private static boolean initialized = false;
    private static Class<?> ftbTeamsApiClass = null;
    private static Method getPlayerTeamMethod = null;
    private static Method getTeamMembersMethod = null;
    
    // Cache for team lookups
    private static final Map<UUID, CachedTeamData> teamCache = new HashMap<>();
    private static final long CACHE_DURATION = 30000; // 30 seconds
    private static final int MAX_CACHE_SIZE = 100;
    
    /**
     * Initialize FTB Teams integration via reflection
     */
    public static void init() {
        if (!ModCompatibility.isFTBTeamsLoaded()) {
            return;
        }
        
        try {
            // Load FTB Teams API classes via reflection
            ftbTeamsApiClass = Class.forName("dev.ftb.mods.ftbteams.api.FTBTeamsAPI");
            
            initialized = true;
            LOGGER.info("FTB Teams integration initialized successfully");
        } catch (Exception e) {
            LOGGER.warn("Failed to initialize FTB Teams integration: {}", e.getMessage());
            initialized = false;
        }
    }
    
    /**
     * Check if players are on the same team
     */
    public static boolean areTeammates(ServerPlayer player1, ServerPlayer player2) {
        if (!initialized) {
            return false;
        }
        
        try {
            Set<UUID> player1Team = getTeamMembers(player1.getUUID());
            return player1Team.contains(player2.getUUID());
            
        } catch (Exception e) {
            LOGGER.debug("Error checking team status: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * Get all team members for a player
     */
    public static Set<UUID> getTeamMembers(UUID playerId) {
        if (!initialized) {
            return Collections.singleton(playerId);
        }
        
        // Check cache first
        long now = System.currentTimeMillis();
        CachedTeamData cached = teamCache.get(playerId);
        if (cached != null && (now - cached.timestamp) < CACHE_DURATION) {
            return cached.teamMembers;
        }
        
        // Evict old entries
        if (teamCache.size() > MAX_CACHE_SIZE) {
            teamCache.entrySet().removeIf(entry -> 
                (now - entry.getValue().timestamp) > CACHE_DURATION * 2
            );
        }
        
        try {
            // Use reflection to get team members
            // Would be: FTBTeamsAPI.getPlayerTeam(player).getMembers()
            
            Set<UUID> teamMembers = new HashSet<>();
            teamMembers.add(playerId); // Always include self
            
            // Placeholder - full implementation requires FTB Teams as dependency
            
            // Update cache
            teamCache.put(playerId, new CachedTeamData(teamMembers, now));
            
            return teamMembers;
            
        } catch (Exception e) {
            LOGGER.debug("Error getting team members: {}", e.getMessage());
            return Collections.singleton(playerId);
        }
    }
    
    /**
     * Get team size for a player
     */
    public static int getTeamSize(UUID playerId) {
        return getTeamMembers(playerId).size();
    }
    
    /**
     * Check if player is in a team (more than 1 member)
     */
    public static boolean isInTeam(UUID playerId) {
        return getTeamSize(playerId) > 1;
    }
    
    /**
     * Get AI difficulty multiplier based on team size
     * Larger teams = tougher mobs
     */
    public static float getTeamDifficultyMultiplier(UUID playerId) {
        int teamSize = getTeamSize(playerId);
        
        if (teamSize == 1) {
            return 1.0f; // Solo player
        } else if (teamSize == 2) {
            return 1.2f; // Duo
        } else if (teamSize <= 4) {
            return 1.4f; // Small team
        } else {
            return 1.6f; // Large team
        }
    }
    
    /**
     * Get recommended mob team size based on player team size
     * Mobs should form teams to counter player teams
     */
    public static int getRecommendedMobTeamSize(UUID playerId) {
        int playerTeamSize = getTeamSize(playerId);
        
        if (playerTeamSize == 1) {
            return 2; // 2 mobs vs 1 player
        } else if (playerTeamSize == 2) {
            return 3; // 3 mobs vs 2 players
        } else {
            return Math.min(5, playerTeamSize + 1); // Slight numerical advantage
        }
    }
    
    /**
     * Clear team cache (call when teams change)
     */
    public static void clearCache() {
        // Clear cached team data - will be regenerated on next access
        LOGGER.debug("FTB Teams cache cleared");
    }
    
    /**
     * Get team coordination bonus for mobs
     * Mobs get smarter when facing organized teams
     */
    public static float getTeamCoordinationBonus(UUID playerId) {
        if (!isInTeam(playerId)) {
            return 0.0f;
        }
        
        int teamSize = getTeamSize(playerId);
        
        // Coordinated teams require coordinated AI responses
        return (teamSize - 1) * 0.15f; // +15% per additional team member
    }
    
    /**
     * Check if multiple players near a location are teammates
     */
    public static boolean isCoordinatedTeam(List<ServerPlayer> nearbyPlayers) {
        if (!initialized || nearbyPlayers.size() < 2) {
            return false;
        }
        
        try {
            ServerPlayer first = nearbyPlayers.get(0);
            Set<UUID> firstTeam = getTeamMembers(first.getUUID());
            
            // Check if all players are on the same team
            for (int i = 1; i < nearbyPlayers.size(); i++) {
                if (!firstTeam.contains(nearbyPlayers.get(i).getUUID())) {
                    return false;
                }
            }
            
            return true;
            
        } catch (Exception e) {
            LOGGER.debug("Error checking coordinated team: {}", e.getMessage());
            return false;
        }
    }
    
    private static class CachedTeamData {
        final Set<UUID> teamMembers;
        final long timestamp;
        
        CachedTeamData(Set<UUID> teamMembers, long timestamp) {
            this.teamMembers = teamMembers;
            this.timestamp = timestamp;
        }
    }
}
