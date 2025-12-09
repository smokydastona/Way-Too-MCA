package com.minecraft.gancity.ml;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.*;

/**
 * Curriculum Learning - progressive difficulty stages
 * Mobs learn simpler tactics first, then advance to complex strategies
 */
public class CurriculumLearning {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    private Stage currentStage = Stage.BASIC;
    private int stageProgress = 0;
    private static final int STAGE_THRESHOLD = 200;  // Experiences per stage
    
    private final Map<Stage, List<String>> stageActions = new HashMap<>();
    private final Map<Stage, Float> stageDifficulty = new HashMap<>();
    
    public enum Stage {
        BASIC,      // Simple approach/retreat
        TACTICAL,   // Positioning, kiting
        ADVANCED,   // Coordination, ambush
        EXPERT      // Full tactical repertoire
    }
    
    public CurriculumLearning() {
        initializeStages();
    }
    
    private void initializeStages() {
        // BASIC: Simple movement patterns
        stageActions.put(Stage.BASIC, Arrays.asList(
            "straight_charge",
            "retreat",
            "circle_strafe"
        ));
        stageDifficulty.put(Stage.BASIC, 0.5f);
        
        // TACTICAL: Intermediate strategies
        stageActions.put(Stage.TACTICAL, Arrays.asList(
            "straight_charge",
            "retreat",
            "circle_strafe",
            "kite_backward",
            "strafe_shoot",
            "find_high_ground"
        ));
        stageDifficulty.put(Stage.TACTICAL, 1.0f);
        
        // ADVANCED: Complex maneuvers
        stageActions.put(Stage.ADVANCED, Arrays.asList(
            "straight_charge",
            "retreat",
            "circle_strafe",
            "kite_backward",
            "strafe_shoot",
            "find_high_ground",
            "ambush",
            "fake_retreat",
            "group_rush"
        ));
        stageDifficulty.put(Stage.ADVANCED, 1.5f);
        
        // EXPERT: All actions
        stageActions.put(Stage.EXPERT, Arrays.asList(
            "straight_charge",
            "circle_strafe",
            "kite_backward",
            "retreat",
            "ambush",
            "group_rush",
            "find_cover",
            "strafe_shoot",
            "leap_attack",
            "fake_retreat"
        ));
        stageDifficulty.put(Stage.EXPERT, 2.0f);
        
        LOGGER.info("Curriculum learning initialized at stage: {}", currentStage);
    }
    
    /**
     * Get available actions for current stage
     */
    public List<String> getCurrentStageActions() {
        return stageActions.get(currentStage);
    }
    
    /**
     * Filter actions based on current curriculum stage
     */
    public List<String> filterActionsByStage(List<String> allActions) {
        List<String> stageAvailable = getCurrentStageActions();
        List<String> filtered = new ArrayList<>();
        
        for (String action : allActions) {
            if (stageAvailable.contains(action)) {
                filtered.add(action);
            }
        }
        
        return filtered.isEmpty() ? allActions : filtered;
    }
    
    /**
     * Progress through curriculum based on performance
     */
    public void recordExperience(boolean success) {
        stageProgress++;
        
        // Advance stage if threshold reached and performance is good
        if (stageProgress >= STAGE_THRESHOLD) {
            if (success) {
                advanceStage();
            }
            stageProgress = 0;
        }
    }
    
    private void advanceStage() {
        Stage nextStage = getNextStage(currentStage);
        if (nextStage != currentStage) {
            LOGGER.info("Advancing curriculum: {} → {}", currentStage, nextStage);
            currentStage = nextStage;
            stageProgress = 0;
        }
    }
    
    private Stage getNextStage(Stage current) {
        switch (current) {
            case BASIC: return Stage.TACTICAL;
            case TACTICAL: return Stage.ADVANCED;
            case ADVANCED: return Stage.EXPERT;
            case EXPERT: return Stage.EXPERT;
            default: return current;
        }
    }
    
    /**
     * Get difficulty multiplier for current stage
     */
    public float getStageDifficulty() {
        return stageDifficulty.getOrDefault(currentStage, 1.0f);
    }
    
    /**
     * Get current curriculum stage
     */
    public Stage getCurrentStage() {
        return currentStage;
    }
    
    /**
     * Get progress toward next stage (0.0 - 1.0)
     */
    public float getStageProgress() {
        return (float) stageProgress / STAGE_THRESHOLD;
    }
    
    /**
     * Reset curriculum (for testing or new worlds)
     */
    public void reset() {
        currentStage = Stage.BASIC;
        stageProgress = 0;
        LOGGER.info("Curriculum reset to BASIC stage");
    }
}
