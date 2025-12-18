package com.minecraft.gancity.ai;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * Lazy-loading wrapper for ML classes to prevent classloading crash during Forge initialization.
 * 
 * Problem: Importing ml.* at top of MobBehaviorAI triggers DJL classloading during @Mod scanning,
 * which crashes before any code runs because DJL native libs fail to load early.
 * 
 * Solution: Load ML classes via reflection ONLY when actually needed (after server starts).
 */
public class MLClassLoader {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    // Lazy-loaded ML system instances
    private static Object doubleDQN;
    private static Object replayBuffer;
    private static Object multiAgent;
    private static Object curriculum;
    private static Object visualPerception;
    private static Object geneticEvolution;
    private static Object taskChainSystem;
    private static Object reflexModule;
    private static Object autonomousGoals;
    private static Object tacticKnowledgeBase;
    private static Object modelPersistence;
    private static Object xgboost;
    private static Object randomForest;
    private static Object federatedLearning;
    private static Object performanceOptimizer;
    private static Object tacticalAggregator;
    
    private static boolean initialized = false;
    private static boolean mlAvailable = false;
    
    /**
     * Initialize all ML systems via reflection.
     * Called AFTER Forge initialization completes (during server start).
     */
    public static boolean initializeMLSystems() {
        if (initialized) {
            return mlAvailable;
        }
        
        initialized = true;
        
        try {
            // Test if DJL is available
            Class.forName("ai.djl.nn.Block");
            
            // Load ML classes
            doubleDQN = loadClass("com.minecraft.gancity.ml.DoubleDQN");
            replayBuffer = loadClass("com.minecraft.gancity.ml.PrioritizedReplayBuffer", 10000);
            multiAgent = loadClass("com.minecraft.gancity.ml.MultiAgentLearning");
            curriculum = loadClass("com.minecraft.gancity.ml.CurriculumLearning");
            visualPerception = loadClass("com.minecraft.gancity.ml.VisualPerception");
            geneticEvolution = loadClass("com.minecraft.gancity.ml.GeneticBehaviorEvolution");
            taskChainSystem = loadClass("com.minecraft.gancity.ml.TaskChainSystem");
            reflexModule = loadClass("com.minecraft.gancity.ml.ReflexModule");
            autonomousGoals = loadClass("com.minecraft.gancity.ml.AutonomousGoals");
            tacticKnowledgeBase = loadClass("com.minecraft.gancity.ml.TacticKnowledgeBase");
            modelPersistence = loadClass("com.minecraft.gancity.ml.ModelPersistence");
            xgboost = loadClass("com.minecraft.gancity.ml.XGBoostTacticPredictor");
            randomForest = loadClass("com.minecraft.gancity.ml.SmileRandomForest");
            performanceOptimizer = loadClass("com.minecraft.gancity.ml.PerformanceOptimizer");
            tacticalAggregator = loadClass("com.minecraft.gancity.ml.TacticalWeightAggregator");
            
            mlAvailable = true;
            LOGGER.info("✅ ML systems initialized successfully via lazy loading");
            return true;
        } catch (Throwable t) {
            LOGGER.warn("❌ ML systems not available: {}", t.getMessage());
            mlAvailable = false;
            return false;
        }
    }
    
    private static Object loadClass(String className) throws Exception {
        Class<?> clazz = Class.forName(className);
        return clazz.getDeclaredConstructor().newInstance();
    }
    
    private static Object loadClass(String className, int param) throws Exception {
        Class<?> clazz = Class.forName(className);
        return clazz.getDeclaredConstructor(int.class).newInstance(param);
    }
    
    public static boolean isMLAvailable() {
        return mlAvailable;
    }
    
    // Getters for ML systems
    public static Object getDoubleDQN() { return doubleDQN; }
    public static Object getReplayBuffer() { return replayBuffer; }
    public static Object getMultiAgent() { return multiAgent; }
    public static Object getCurriculum() { return curriculum; }
    public static Object getVisualPerception() { return visualPerception; }
    public static Object getGeneticEvolution() { return geneticEvolution; }
    public static Object getTaskChainSystem() { return taskChainSystem; }
    public static Object getReflexModule() { return reflexModule; }
    public static Object getAutonomousGoals() { return autonomousGoals; }
    public static Object getTacticKnowledgeBase() { return tacticKnowledgeBase; }
    public static Object getModelPersistence() { return modelPersistence; }
    public static Object getXgboost() { return xgboost; }
    public static Object getRandomForest() { return randomForest; }
    public static Object getFederatedLearning() { return federatedLearning; }
    public static Object getPerformanceOptimizer() { return performanceOptimizer; }
    public static Object getTacticalAggregator() { return tacticalAggregator; }
    
    public static void setFederatedLearning(Object fl) { federatedLearning = fl; }
}
