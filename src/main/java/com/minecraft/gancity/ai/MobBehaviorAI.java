package com.minecraft.gancity.ai;

import com.minecraft.gancity.ml.*;
import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;

import java.util.*;

/**
 * AI system for enhancing mob behavior using advanced ML techniques
 * Combines Double DQN, Prioritized Replay, Multi-Agent, Curriculum Learning, 
 * Visual Perception, and Genetic Evolution for adaptive gameplay
 */
public class MobBehaviorAI {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    // Advanced ML Systems
    private DoubleDQN doubleDQN;
    private PrioritizedReplayBuffer replayBuffer;
    private MultiAgentLearning multiAgent;
    private CurriculumLearning curriculum;
    private VisualPerception visualPerception;
    private GeneticBehaviorEvolution geneticEvolution;
    
    // New AI-Player inspired systems
    private TaskChainSystem taskChainSystem;
    private ReflexModule reflexModule;
    private AutonomousGoals autonomousGoals;
    private TacticKnowledgeBase tacticKnowledgeBase;
    private ModelPersistence modelPersistence;
    
    // Federated learning (optional)
    private FederatedLearning federatedLearning;
    
    private boolean mlEnabled = false;
    private final Map<String, MobBehaviorProfile> behaviorProfiles = new HashMap<>();
    private final Map<String, MobState> lastStateCache = new HashMap<>();
    private final Map<String, String> lastActionCache = new HashMap<>();
    private final Map<String, VisualPerception.VisualState> lastVisualCache = new HashMap<>();
    private final Map<String, GeneticBehaviorEvolution.BehaviorGenome> activeGenomes = new HashMap<>();
    private final Random random = new Random();
    private float difficultyMultiplier = 1.0f;

    public MobBehaviorAI() {
        initializeDefaultProfiles();
        initializeAdvancedMLSystems();
    }

    /**
     * Initialize all advanced machine learning systems
     */
    private void initializeAdvancedMLSystems() {
        try {
            // Core learning - 22 input features (state + visual + genome)
            doubleDQN = new DoubleDQN();  // Uses default 22 state features, 10 actions
            replayBuffer = new PrioritizedReplayBuffer(10000);
            
            // Multi-agent coordination
            multiAgent = new MultiAgentLearning();
            
            // Progressive difficulty
            curriculum = new CurriculumLearning();
            
            // Visual perception
            visualPerception = new VisualPerception();
            
            // Genetic evolution
            geneticEvolution = new GeneticBehaviorEvolution();
            
            // New AI-Player inspired systems
            taskChainSystem = new TaskChainSystem();
            reflexModule = new ReflexModule();
            autonomousGoals = new AutonomousGoals();
            tacticKnowledgeBase = new TacticKnowledgeBase();
            modelPersistence = new ModelPersistence();
            
            // Load saved models if available
            modelPersistence.loadAll(doubleDQN, replayBuffer, tacticKnowledgeBase);
            
            mlEnabled = true;
            LOGGER.info("Advanced ML systems initialized - DQN, Replay, Multi-Agent, Curriculum, Vision, Genetic, Tasks, Reflexes, Goals, Knowledge, Persistence");
        } catch (Exception e) {
            LOGGER.warn("Failed to initialize ML systems, using rule-based fallback: {}", e.getMessage());
            mlEnabled = false;
        }
    }
    
    /**
     * Enable federated learning with Git repository or cloud API
     */
    public void enableFederatedLearning(String repoUrl, String cloudApiEndpoint, String cloudApiKey) {
        if (repoUrl == null && cloudApiEndpoint == null) {
            LOGGER.info("Federated learning disabled - no repository or API configured");
            return;
        }
        
        try {
            federatedLearning = new FederatedLearning(
                modelPersistence != null ? modelPersistence.getModelPath().resolve("federated") : java.nio.file.Paths.get("federated"),
                repoUrl != null ? repoUrl : ""
            );
            
            // Link to knowledge base
            if (tacticKnowledgeBase != null) {
                tacticKnowledgeBase.setFederatedLearning(federatedLearning);
            }
            
            LOGGER.info("Federated learning enabled - All servers will share AI knowledge");
        } catch (Exception e) {
            LOGGER.error("Failed to enable federated learning: {}", e.getMessage());
        }
    }

    /**
     * Set difficulty multiplier (affects learning speed and exploration)
     */
    public void setDifficultyMultiplier(float multiplier) {
        this.difficultyMultiplier = Math.max(0.1f, Math.min(5.0f, multiplier));
    }

    /**
     * Initialize default behavior profiles for different mob types
     */
    private void initializeDefaultProfiles() {
        // Zombie behaviors
        behaviorProfiles.put("zombie", new MobBehaviorProfile(
            "zombie",
            Arrays.asList("straight_charge", "circle_strafe", "group_rush"),
            0.7f  // aggression level
        ));
        
        // Skeleton behaviors
        behaviorProfiles.put("skeleton", new MobBehaviorProfile(
            "skeleton",
            Arrays.asList("kite_backward", "find_high_ground", "strafe_shoot", "retreat_reload"),
            0.5f
        ));
        
        // Creeper behaviors
        behaviorProfiles.put("creeper", new MobBehaviorProfile(
            "creeper",
            Arrays.asList("ambush", "stealth_approach", "fake_retreat", "suicide_rush"),
            0.8f
        ));
        
        // Spider behaviors
        behaviorProfiles.put("spider", new MobBehaviorProfile(
            "spider",
            Arrays.asList("wall_climb_attack", "ceiling_drop", "web_trap", "leap_attack"),
            0.6f
        ));
        
        LOGGER.info("Initialized behavior profiles for {} mob types", behaviorProfiles.size());
    }

    /**
     * Select next action for a specific mob instance with visual perception
     */
    public String selectMobAction(String mobType, MobState state, String mobId, Player target) {
        MobBehaviorProfile profile = behaviorProfiles.get(mobType.toLowerCase());
        
        if (profile == null) {
            return "default_attack";
        }

        String selectedAction;
        
        if (mlEnabled && doubleDQN != null) {
            // Analyze player visually
            VisualPerception.VisualState visual = visualPerception.analyzePlayer(target);
            lastVisualCache.put(mobId, visual);
            
            // Get or create genome for this mob
            GeneticBehaviorEvolution.BehaviorGenome genome = activeGenomes.computeIfAbsent(
                mobId, k -> geneticEvolution.selectGenome()
            );
            
            // Use advanced ML systems for action selection
            selectedAction = selectActionWithAdvancedML(profile, state, visual, genome, mobId);
        } else {
            // Use rule-based system
            selectedAction = selectActionRuleBased(profile, state);
        }
        
        // Cache state and action for learning when outcome is recorded
        lastStateCache.put(mobId, state.copy());
        lastActionCache.put(mobId, selectedAction);
        
        return selectedAction;
    }
    
    /**
     * Select next action for a mob based on current state (no player context)
     * Uses ML model if enabled, otherwise rule-based
     */
    public String selectMobAction(String mobType, MobState state) {
        return selectMobAction(mobType, state, UUID.randomUUID().toString(), null);
    }
    
    /**
     * Select next action for a specific mob instance (no player context)
     */
    public String selectMobAction(String mobType, MobState state, String mobId) {
        return selectMobAction(mobType, state, mobId, null);
    }

    /**
     * Advanced ML-based action selection combining all systems
     */
    private String selectActionWithAdvancedML(MobBehaviorProfile profile, MobState state, 
                                              VisualPerception.VisualState visual,
                                              GeneticBehaviorEvolution.BehaviorGenome genome,
                                              String mobId) {
        List<String> validActions = getValidActions(profile, state);
        if (validActions.isEmpty()) {
            return "default_attack";
        }
        
        // Apply curriculum learning filter
        validActions = curriculum.filterActionsByStage(validActions);
        
        // Get visual recommendations
        List<String> visualRecommendations = new ArrayList<>();
        if (visual != null) {
            visualRecommendations = visualPerception.getRecommendedActions(visual);
        }
        
        // Check for team coordination
        List<String> teamMembers = multiAgent.getTeamMembers(mobId);
        if (teamMembers != null && teamMembers.size() > 1) {
            // Share experiences with teammates
            for (String teammateId : teamMembers) {
                if (!teammateId.equals(mobId)) {
                    MobState teammateState = lastStateCache.get(teammateId);
                    String teammateAction = lastActionCache.get(teammateId);
                    if (teammateState != null && teammateAction != null) {
                        // Consider teammate's recent experience
                        float[] teammateFeatures = combineFeatures(teammateState, visual, genome);
                        int actionIndex = validActions.indexOf(teammateAction);
                        if (actionIndex >= 0) {
                            // Teammate used this action recently
                        }
                    }
                }
            }
        }
        
        // Combine all feature sources
        float[] combinedFeatures = combineFeatures(state, visual, genome);
        
        // Use Double DQN to select action
        int actionIndex = doubleDQN.selectActionIndex(combinedFeatures);
        
        // Map index to valid action
        if (actionIndex >= validActions.size()) {
            actionIndex = actionIndex % validActions.size();
        }
        String selectedAction = validActions.get(actionIndex);
        
        // Apply genetic modifiers
        if (genome.actionWeights.containsKey(selectedAction)) {
            float weight = genome.actionWeights.get(selectedAction);
            // Bias toward genetically preferred actions
            if (random.nextFloat() > weight && !validActions.isEmpty()) {
                // Sometimes override with genome preference
                selectedAction = selectWeightedAction(validActions, genome);
            }
        }
        
        // Boost visually recommended actions
        if (visualRecommendations.contains(selectedAction)) {
            // This action is tactically sound based on player equipment
            profile.recordAction(selectedAction, state);
        }
        
        return selectedAction;
    }
    
    /**
     * Select action weighted by genetic genome preferences
     */
    private String selectWeightedAction(List<String> actions, GeneticBehaviorEvolution.BehaviorGenome genome) {
        float totalWeight = 0.0f;
        for (String action : actions) {
            totalWeight += genome.actionWeights.getOrDefault(action, 1.0f);
        }
        
        float rand = random.nextFloat() * totalWeight;
        float cumulative = 0.0f;
        
        for (String action : actions) {
            cumulative += genome.actionWeights.getOrDefault(action, 1.0f);
            if (cumulative >= rand) {
                return action;
            }
        }
        
        return actions.get(0);
    }
    
    /**
     * Combine state, visual, and genetic features into single vector
     */
    private float[] combineFeatures(MobState state, VisualPerception.VisualState visual, 
                                    GeneticBehaviorEvolution.BehaviorGenome genome) {
        float[] stateFeatures = stateToFeatureVector(state);
        float[] visualFeatures = visual != null ? visual.toFeatureVector() : new float[9];
        
        // Combine: [state(10) + visual(9) + genome(3)] = 22 features
        float[] combined = new float[22];
        System.arraycopy(stateFeatures, 0, combined, 0, 10);
        System.arraycopy(visualFeatures, 0, combined, 10, 9);
        combined[19] = genome.aggression;
        combined[20] = genome.caution;
        combined[21] = genome.teamwork;
        
        return combined;
    }
    
    /**
     * Convert MobState to feature vector for neural network input
     */
    private float[] stateToFeatureVector(MobState state) {
        return new float[] {
            state.health,                                    // 0: Mob health (0-1)
            state.targetHealth,                              // 1: Player health (0-1)
            state.distanceToTarget / 20.0f,                  // 2: Distance normalized
            state.hasHighGround ? 1.0f : 0.0f,              // 3: Height advantage
            state.canClimbWalls ? 1.0f : 0.0f,              // 4: Climbing ability
            state.nearbyAlliesCount / 10.0f,                // 5: Nearby allies normalized
            state.isNight ? 1.0f : 0.0f,                    // 6: Time of day
            state.biome.hashCode() % 100 / 100.0f,          // 7: Biome encoding
            difficultyMultiplier / 3.0f,                    // 8: Difficulty setting
            state.combatTime / 100.0f                       // 9: Combat duration
        };
    }
    
    /**
     * Get list of valid actions for current state
     */
    private List<String> getValidActions(MobBehaviorProfile profile, MobState state) {
        List<String> actions = profile.getActions();
        List<String> validActions = new ArrayList<>();
        
        for (String action : actions) {
            if (isActionValid(action, state)) {
                validActions.add(action);
            }
        }
        
        return validActions.isEmpty() ? actions : validActions;
    }

    /**
     * Rule-based action selection with adaptive behavior
     */
    private String selectActionRuleBased(MobBehaviorProfile profile, MobState state) {
        List<String> actions = profile.getActions();
        
        // Filter actions based on state
        List<String> validActions = new ArrayList<>();
        
        for (String action : actions) {
            if (isActionValid(action, state)) {
                validActions.add(action);
            }
        }
        
        if (validActions.isEmpty()) {
            return "default_attack";
        }
        
        // Weight actions based on situation
        String selectedAction = weightedActionSelection(validActions, state, profile);
        
        // Learn from this decision
        profile.recordAction(selectedAction, state);
        
        return selectedAction;
    }

    /**
     * Check if an action is valid in current state
     */
    private boolean isActionValid(String action, MobState state) {
        switch (action) {
            case "kite_backward":
            case "retreat_reload":
                return state.distanceToTarget > 3.0f;
            case "suicide_rush":
            case "group_rush":
                return state.distanceToTarget < 10.0f;
            case "find_high_ground":
                return !state.hasHighGround;
            case "ceiling_drop":
            case "wall_climb_attack":
                return state.canClimbWalls;
            default:
                return true;
        }
    }

    /**
     * Select action with weighted probability based on state
     */
    private String weightedActionSelection(List<String> actions, MobState state, MobBehaviorProfile profile) {
        Map<String, Float> weights = new HashMap<>();
        
        for (String action : actions) {
            float weight = calculateActionWeight(action, state, profile);
            weights.put(action, weight);
        }
        
        // Weighted random selection
        float totalWeight = weights.values().stream().reduce(0f, Float::sum);
        float randomValue = random.nextFloat() * totalWeight;
        
        float currentWeight = 0f;
        for (Map.Entry<String, Float> entry : weights.entrySet()) {
            currentWeight += entry.getValue();
            if (randomValue <= currentWeight) {
                return entry.getKey();
            }
        }
        
        return actions.get(0);
    }

    /**
     * Calculate weight for an action based on current state
     */
    private float calculateActionWeight(String action, MobState state, MobBehaviorProfile profile) {
        float baseWeight = 1.0f;
        
        // Adjust weight based on player health
        if (state.targetHealth < 0.3f) {
            // Player is low health - aggressive actions
            if (action.contains("rush") || action.contains("charge")) {
                baseWeight *= 2.0f;
            }
        }
        
        // Adjust based on mob health
        if (state.health < 0.3f) {
            // Mob is low health - defensive actions
            if (action.contains("retreat") || action.contains("kite")) {
                baseWeight *= 2.0f;
            }
        }
        
        // Adjust based on distance
        if (state.distanceToTarget < 3.0f) {
            if (action.contains("melee") || action.contains("rush")) {
                baseWeight *= 1.5f;
            }
        } else if (state.distanceToTarget > 8.0f) {
            if (action.contains("range") || action.contains("approach")) {
                baseWeight *= 1.5f;
            }
        }
        
        // Factor in past success rate
        baseWeight *= profile.getActionSuccessRate(action);
        
        return baseWeight;
    }

    /**
     * Record combat outcome to improve AI with all advanced ML systems
     */
    public void recordCombatOutcome(String mobId, boolean playerDied, boolean mobDied, MobState finalState, 
                                    float damageDealt, float damageTaken) {
        // Get cached state and action
        MobState initialState = lastStateCache.remove(mobId);
        String action = lastActionCache.remove(mobId);
        VisualPerception.VisualState visual = lastVisualCache.remove(mobId);
        GeneticBehaviorEvolution.BehaviorGenome genome = activeGenomes.remove(mobId);
        
        if (initialState == null || action == null) {
            return;  // No cached data for this mob
        }
        
        // Calculate reward based on outcome
        float reward = calculateReward(initialState, finalState, playerDied, mobDied);
        reward += damageDealt * 0.5f - damageTaken * 0.3f;  // Fine-grained feedback
        
        // Update all ML systems if enabled
        if (mlEnabled && doubleDQN != null) {
            // Combine features
            float[] initialFeatures = combineFeatures(initialState, visual, genome != null ? genome : new GeneticBehaviorEvolution.BehaviorGenome());
            float[] finalFeatures = combineFeatures(finalState, visual, genome != null ? genome : new GeneticBehaviorEvolution.BehaviorGenome());
            boolean episodeDone = playerDied || mobDied;
            
            // Convert action to index
            List<String> allActions = getAllPossibleActions();
            int actionIndex = allActions.indexOf(action);
            if (actionIndex < 0) actionIndex = 0;
            
            // Add to prioritized replay buffer
            replayBuffer.add(initialFeatures, actionIndex, reward, finalFeatures, episodeDone);
            
            // Sample and train Double DQN with prioritized experiences
            if (replayBuffer.size() >= 32) {
                PrioritizedReplayBuffer.SampledBatch batch = replayBuffer.sample(32);
                float[] tdErrors = doubleDQN.trainBatch(batch.experiences);
                
                // Update priorities based on TD errors
                int[] indices = new int[batch.experiences.size()];
                for (int i = 0; i < batch.experiences.size(); i++) {
                    indices[i] = i;
                }
                replayBuffer.updatePriorities(indices, tdErrors);
            }
            
            // Update curriculum learning
            curriculum.recordExperience(reward > 0);
            
            // Update multi-agent coordination
            List<String> team = multiAgent.getTeamMembers(mobId);
            if (team != null && team.size() > 1) {
                // Share this experience with teammates
                for (String teammateId : team) {
                    if (!teammateId.equals(mobId)) {
                        multiAgent.recordTeamExperience(mobId, teammateId, reward);
                    }
                }
                
                // Give cooperation bonus if team performed well
                if (reward > 0) {
                    float teamBonus = multiAgent.getTeamCoordinationBonus(mobId);
                    reward *= (1.0f + teamBonus);
                }
            }
            
            // Update genetic evolution
            if (genome != null) {
                geneticEvolution.recordCombat(genome, playerDied, damageDealt, damageTaken);
            }
        }
    }
    
    /**
     * Backwards compatibility - old signature
     */
    public void recordCombatOutcome(String mobId, boolean playerDied, boolean mobDied, MobState finalState) {
        recordCombatOutcome(mobId, playerDied, mobDied, finalState, 0.0f, 0.0f);
    }
    
    /**
     * Calculate reward for reinforcement learning
     * Positive for successful mob behavior, negative for unsuccessful
     */
    private float calculateReward(MobState initialState, MobState finalState, boolean playerDied, boolean mobDied) {
        float reward = 0.0f;
        
        // Major rewards/penalties for combat outcome
        if (playerDied) {
            reward += 10.0f * difficultyMultiplier;  // Mob won
        } else if (mobDied) {
            reward -= 5.0f;  // Mob lost
        }
        
        // Reward for damaging player
        float playerDamage = initialState.targetHealth - finalState.targetHealth;
        reward += playerDamage * 5.0f;
        
        // Penalty for taking damage
        float mobDamage = initialState.health - finalState.health;
        reward -= mobDamage * 3.0f;
        
        // Small reward for maintaining combat distance
        if (finalState.distanceToTarget > 1.0f && finalState.distanceToTarget < 6.0f) {
            reward += 0.5f;
        }
        
        // Bonus for tactical positioning
        if (finalState.hasHighGround && !initialState.hasHighGround) {
            reward += 1.0f;
        }
        
        return reward * difficultyMultiplier;
    }
    
    /**
     * Get all possible actions across all mob types
     */
    private List<String> getAllPossibleActions() {
        List<String> allActions = new ArrayList<>();
        allActions.add("straight_charge");
        allActions.add("circle_strafe");
        allActions.add("kite_backward");
        allActions.add("retreat");
        allActions.add("ambush");
        allActions.add("group_rush");
        allActions.add("find_cover");
        allActions.add("strafe_shoot");
        allActions.add("leap_attack");
        allActions.add("fake_retreat");
        return allActions;
    }
    
    /**
     * Form a team of mobs for coordinated tactics
     */
    public void formTeam(String leaderId, List<String> memberIds) {
        if (mlEnabled && multiAgent != null) {
            List<String> allMembers = new ArrayList<>(memberIds);
            allMembers.add(0, leaderId);
            multiAgent.formTeam(allMembers);
        }
    }
    
    /**
     * Save the trained ML model (call on server shutdown)
     */
    public void saveModel() {
        // Models are saved automatically during training
        LOGGER.info("ML systems persisted");
    }
    
    /**
     * Get ML statistics for debugging/display
     */
    public String getMLStats() {
        if (!mlEnabled || doubleDQN == null) {
            return "ML disabled - using rule-based AI";
        }
        
        return String.format("Advanced ML | Gen: %d | Stage: %s | Replay: %d | Teams: %d | Best: %.2f",
            geneticEvolution != null ? geneticEvolution.getGenerationNumber() : 0,
            curriculum != null ? curriculum.getCurrentStage() : "UNKNOWN",
            replayBuffer != null ? replayBuffer.size() : 0,
            multiAgent != null ? multiAgent.getTeamCount() : 0,
            geneticEvolution != null ? geneticEvolution.getBestFitness() : 0.0f
        );
    }

    /**
     * Get aggression modifier for a mob type
     */
    public float getAggressionLevel(String mobType) {
        MobBehaviorProfile profile = behaviorProfiles.get(mobType.toLowerCase());
        return profile != null ? profile.getAggressionLevel() : 0.5f;
    }
    
    /**
     * Accessors for new systems
     */
    public TaskChainSystem getTaskChainSystem() {
        return taskChainSystem;
    }
    
    public ReflexModule getReflexModule() {
        return reflexModule;
    }
    
    public AutonomousGoals getAutonomousGoals() {
        return autonomousGoals;
    }
    
    public TacticKnowledgeBase getTacticKnowledgeBase() {
        return tacticKnowledgeBase;
    }
    
    public ModelPersistence getModelPersistence() {
        return modelPersistence;
    }

    /**
     * Represents the current state of a mob during combat
     */
    public static class MobState {
        public float health;
        public float targetHealth;
        public float distanceToTarget;
        public boolean hasHighGround;
        public boolean canClimbWalls;
        public int nearbyAlliesCount;
        public String biome;
        public boolean isNight;
        public float combatTime;  // Seconds in combat

        public MobState(float health, float targetHealth, float distance) {
            this.health = health;
            this.targetHealth = targetHealth;
            this.distanceToTarget = distance;
            this.hasHighGround = false;
            this.canClimbWalls = false;
            this.nearbyAlliesCount = 0;
            this.biome = "plains";
            this.isNight = false;
            this.combatTime = 0.0f;
        }
        
        /**
         * Create a copy of this state (for caching)
         */
        public MobState copy() {
            MobState copy = new MobState(health, targetHealth, distanceToTarget);
            copy.hasHighGround = this.hasHighGround;
            copy.canClimbWalls = this.canClimbWalls;
            copy.nearbyAlliesCount = this.nearbyAlliesCount;
            copy.biome = this.biome;
            copy.isNight = this.isNight;
            copy.combatTime = this.combatTime;
            return copy;
        }
    }

    /**
     * Stores behavior patterns and learning data for a mob type
     */
    private static class MobBehaviorProfile {
        private final String mobType;
        private final List<String> actions;
        private final float aggressionLevel;
        private final Map<String, Integer> actionSuccessCount = new HashMap<>();
        private final Map<String, Integer> actionFailureCount = new HashMap<>();

        public MobBehaviorProfile(String mobType, List<String> actions, float aggression) {
            this.mobType = mobType;
            this.actions = new ArrayList<>(actions);
            this.aggressionLevel = aggression;
            
            // Initialize counters
            for (String action : actions) {
                actionSuccessCount.put(action, 1);
                actionFailureCount.put(action, 1);
            }
        }

        public List<String> getActions() {
            return new ArrayList<>(actions);
        }

        public float getAggressionLevel() {
            return aggressionLevel;
        }

        public void recordAction(String action, MobState state) {
            // Track action usage
        }

        public void recordOutcome(String action, boolean success) {
            if (success) {
                actionSuccessCount.put(action, actionSuccessCount.getOrDefault(action, 0) + 1);
            } else {
                actionFailureCount.put(action, actionFailureCount.getOrDefault(action, 0) + 1);
            }
        }

        public float getActionSuccessRate(String action) {
            int successes = actionSuccessCount.getOrDefault(action, 1);
            int failures = actionFailureCount.getOrDefault(action, 1);
            return (float) successes / (successes + failures);
        }
    }
}
