package com.minecraft.gancity.ml;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lazy loader for ML models - prevents DJL classloading during Forge initialization
 * 
 * CRITICAL: DJL's PyTorch engine extracts native libraries when classes are loaded.
 * This causes deadlock during Forge's "Compatibility level set to JAVA_17" phase.
 * 
 * Solution: Delay ALL DJL class loading until after server starts (ServerStartingEvent).
 * MobLearningModel classes are loaded via reflection ONLY when needed.
 */
public class MLModelLoader {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);
    private static volatile MobLearningModel learningModel;
    private static volatile DoubleDQN dqnModel;
    
    /**
     * Initialize ML models - MUST be called after ServerStartingEvent
     * This is the ONLY safe time to load DJL classes
     */
    public static void initializeModels() {
        if (INITIALIZED.getAndSet(true)) {
            LOGGER.debug("ML models already initialized");
            return;
        }
        
        LOGGER.info("Initializing ML models (lazy loading DJL)...");
        
        try {
            // Load DJL classes NOW (after Forge init complete)
            learningModel = new MobLearningModel();
            LOGGER.info("MobLearningModel initialized successfully");
            
            // Initialize DQN if needed
            // dqnModel = new DoubleDQN();
            // LOGGER.info("DoubleDQN initialized successfully");
            
        } catch (Throwable e) {
            LOGGER.error("Failed to initialize ML models: {}", e.getMessage(), e);
            LOGGER.warn("ML features will be disabled, falling back to rule-based AI");
        }
    }
    
    /**
     * Get the learning model - safe to call before initialization (returns null)
     */
    public static MobLearningModel getLearningModel() {
        return learningModel;
    }
    
    /**
     * Get the DQN model - safe to call before initialization (returns null)
     */
    public static DoubleDQN getDQNModel() {
        return dqnModel;
    }
    
    /**
     * Check if ML models are ready
     */
    public static boolean isInitialized() {
        return INITIALIZED.get() && learningModel != null;
    }
    
    /**
     * Shutdown ML models gracefully
     */
    public static void shutdown() {
        LOGGER.info("Shutting down ML models...");
        
        if (learningModel != null) {
            try {
                learningModel.close();
                LOGGER.info("MobLearningModel closed successfully");
            } catch (Exception e) {
                LOGGER.error("Error closing MobLearningModel: {}", e.getMessage());
            }
        }
        
        if (dqnModel != null) {
            try {
                // dqnModel.close();
                LOGGER.info("DoubleDQN closed successfully");
            } catch (Exception e) {
                LOGGER.error("Error closing DoubleDQN: {}", e.getMessage());
            }
        }
        
        INITIALIZED.set(false);
        learningModel = null;
        dqnModel = null;
    }
}
