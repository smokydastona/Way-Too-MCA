package com.minecraft.gancity.ml;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.*;

/**
 * Multi-Agent Reinforcement Learning coordinator
 * Enables mobs to learn coordinated team strategies
 */
public class MultiAgentLearning {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    private final Map<String, TeamState> activeTeams = new HashMap<>();
    private final Map<String, List<String>> mobTeams = new HashMap<>();
    private float cooperationBonus = 2.0f;
    
    /**
     * Register mobs as a team for coordinated learning
     */
    public String formTeam(List<String> mobIds, String teamType) {
        String teamId = UUID.randomUUID().toString();
        TeamState team = new TeamState(teamId, teamType, mobIds);
        activeTeams.put(teamId, team);
        
        for (String mobId : mobIds) {
            mobTeams.put(mobId, new ArrayList<>(mobIds));
        }
        
        LOGGER.debug("Formed team {} with {} members", teamId, mobIds.size());
        return teamId;
    }
    
    /**
     * Get team reward modifier based on coordination
     */
    public float getTeamRewardModifier(String mobId, float individualReward) {
        List<String> team = mobTeams.get(mobId);
        if (team == null || team.size() <= 1) {
            return individualReward;
        }
        
        // Bonus for team coordination
        float teamSize = team.size();
        float coordinationBonus = cooperationBonus * (teamSize - 1) / teamSize;
        
        return individualReward * (1.0f + coordinationBonus);
    }
    
    /**
     * Share learning across team members
     */
    public List<SharedExperience> getSharedExperiences(String mobId, float[] state, String action, float reward) {
        List<String> team = mobTeams.get(mobId);
        if (team == null) {
            return Collections.emptyList();
        }
        
        List<SharedExperience> shared = new ArrayList<>();
        for (String teammateId : team) {
            if (!teammateId.equals(mobId)) {
                // Teammates learn from each other's experiences (with reduced weight)
                shared.add(new SharedExperience(teammateId, state, action, reward * 0.5f));
            }
        }
        
        return shared;
    }
    
    /**
     * Update team strategy based on collective performance
     */
    public void updateTeamStrategy(String teamId, boolean success) {
        TeamState team = activeTeams.get(teamId);
        if (team == null) return;
        
        team.combatCount++;
        if (success) {
            team.successCount++;
        }
        
        // Adjust cooperation bonus based on team performance
        float successRate = (float) team.successCount / team.combatCount;
        if (successRate > 0.7f) {
            cooperationBonus = Math.min(5.0f, cooperationBonus * 1.05f);
        } else if (successRate < 0.3f) {
            cooperationBonus = Math.max(1.0f, cooperationBonus * 0.95f);
        }
    }
    
    /**
     * Disband team when mobs are no longer together
     */
    public void disbandTeam(String teamId) {
        TeamState team = activeTeams.remove(teamId);
        if (team != null) {
            for (String mobId : team.members) {
                mobTeams.remove(mobId);
            }
        }
    }
    
    public boolean isInTeam(String mobId) {
        return mobTeams.containsKey(mobId);
    }
    
    public List<String> getTeamMembers(String mobId) {
        return mobTeams.getOrDefault(mobId, Collections.emptyList());
    }
    
    private static class TeamState {
        final String teamId;
        final String teamType;
        final List<String> members;
        int combatCount = 0;
        int successCount = 0;

        TeamState(String teamId, String teamType, List<String> members) {
            this.teamId = teamId;
            this.teamType = teamType;
            this.members = new ArrayList<>(members);
        }
    }
    
    public static class SharedExperience {
        public final String mobId;
        public final float[] state;
        public final String action;
        public final float reward;

        public SharedExperience(String mobId, float[] state, String action, float reward) {
            this.mobId = mobId;
            this.state = state;
            this.action = action;
            this.reward = reward;
        }
    }
}
