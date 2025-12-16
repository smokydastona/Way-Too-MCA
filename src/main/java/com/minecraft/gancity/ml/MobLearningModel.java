package com.minecraft.gancity.ml;

import ai.djl.Model;
import ai.djl.nn.Block;
import ai.djl.nn.SequentialBlock;
import ai.djl.nn.core.Linear;
import ai.djl.training.DefaultTrainingConfig;
import ai.djl.training.Trainer;
import ai.djl.training.dataset.ArrayDataset;
import ai.djl.training.evaluator.Accuracy;
import ai.djl.training.listener.TrainingListener;
import ai.djl.training.loss.Loss;
import ai.djl.training.optimizer.Optimizer;
import ai.djl.training.tracker.Tracker;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;

/**
 * Neural Network for mob behavior learning using Q-Learning approach
 * Learns optimal actions based on combat state and outcomes
 * 
 * CRITICAL FIX: Training now runs in background thread to prevent tick lag
 */
public class MobLearningModel {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    // Background training executor - NEVER train on main thread
    private static final ExecutorService TRAINING_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MobLearning-Trainer");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.setUncaughtExceptionHandler((thread, throwable) -> {
            LOGGER.error("Uncaught exception in MobLearning training thread: {}", throwable.getMessage());
        });
        return t;
    });
    
    // Network architecture
    private static final int INPUT_SIZE = 10;  // State features: health, distance, time, etc.
    private static final int HIDDEN_SIZE = 64;
    private static final int OUTPUT_SIZE = 10; // Q-values for different actions
    
    // Training parameters
    private static final float LEARNING_RATE = 0.001f;
    private static final float DISCOUNT_FACTOR = 0.95f;  // Gamma for Q-learning
    private static final int BATCH_SIZE = 32;
    private static final int REPLAY_BUFFER_SIZE = 10000;
    
    private Model model;
    private NDManager manager;
    private Trainer trainer;
    private final Queue<Experience> replayBuffer = new ConcurrentLinkedQueue<>();
    private final Map<String, Integer> actionIndexMap = new HashMap<>();
    private final List<String> indexToAction = new ArrayList<>();
    
    private int trainingSteps = 0;
    private float epsilon = 1.0f;  // Exploration rate
    private static final float EPSILON_DECAY = 0.995f;
    private static final float MIN_EPSILON = 0.1f;
    
    public MobLearningModel() {
        initializeActionMapping();
        createModel();
    }

    /**
     * Initialize mapping between actions and network outputs
     */
    private void initializeActionMapping() {
        String[] actions = {
            "straight_charge", "circle_strafe", "kite_backward", "retreat",
            "ambush", "group_rush", "find_cover", "strafe_shoot",
            "leap_attack", "fake_retreat"
        };
        
        for (int i = 0; i < actions.length; i++) {
            actionIndexMap.put(actions[i], i);
            indexToAction.add(actions[i]);
        }
    }

    /**
     * Create the neural network model
     */
    private void createModel() {
        try {
            manager = NDManager.newBaseManager();
            model = Model.newInstance("mob-behavior-q-network");
            
            // Build neural network: Input -> Hidden -> Hidden -> Output (Q-values)
            Block net = new SequentialBlock()
                .add(Linear.builder().setUnits(HIDDEN_SIZE).build())  // Input layer
                .add(ai.djl.nn.Activation::relu)
                .add(Linear.builder().setUnits(HIDDEN_SIZE).build())  // Hidden layer
                .add(ai.djl.nn.Activation::relu)
                .add(Linear.builder().setUnits(OUTPUT_SIZE).build()); // Output Q-values
            
            model.setBlock(net);
            
            // Try to load existing model
            Path modelPath = Paths.get("config", "mca-ai-models", "mob_behavior.params");
            if (Files.exists(modelPath)) {
                try {
                    model.load(modelPath.getParent());
                    LOGGER.info("Loaded existing mob learning model from {}", modelPath);
                } catch (Exception e) {
                    LOGGER.warn("Failed to load model, starting fresh: {}", e.getMessage());
                    initializeTrainer();
                }
            } else {
                initializeTrainer();
            }
            
        } catch (Exception e) {
            LOGGER.error("Failed to create learning model", e);
        }
    }

    /**
     * Initialize the trainer for the model
     */
    private void initializeTrainer() {
        DefaultTrainingConfig config = new DefaultTrainingConfig(Loss.l2Loss())
            .optOptimizer(Optimizer.adam().optLearningRateTracker(Tracker.fixed(LEARNING_RATE)).build())
            .addTrainingListeners(TrainingListener.Defaults.logging());
        
        trainer = model.newTrainer(config);
        trainer.initialize(new Shape(1, INPUT_SIZE));
        
        LOGGER.info("Initialized new mob learning model");
    }

    /**
     * Select action using epsilon-greedy policy
     * CRITICAL: NaN protection - validates input state
     * @param state Current combat state
     * @param validActions List of valid actions in this context
     * @return Selected action name
     */
    public String selectAction(float[] state, List<String> validActions) {
        // NaN Protection: sanitize state vector
        state = sanitizeState(state);
        
        // Exploration: random action
        if (Math.random() < epsilon) {
            return validActions.get(new Random().nextInt(validActions.size()));
        }
        
        // Exploitation: use Q-network
        try (NDManager subManager = manager.newSubManager()) {
            NDArray stateArray = subManager.create(state).reshape(1, INPUT_SIZE);
            
            // Forward pass through network
            NDArray qValues = model.getBlock().forward(
                new ai.djl.training.ParameterStore(subManager, false),
                new NDList(stateArray),
                false
            ).singletonOrThrow();
            
            // Find best valid action
            float[] qValuesArray = qValues.toFloatArray();
            float maxQ = Float.NEGATIVE_INFINITY;
            String bestAction = validActions.get(0);
            
            for (String action : validActions) {
                Integer idx = actionIndexMap.get(action);
                if (idx != null && idx < qValuesArray.length) {
                    if (qValuesArray[idx] > maxQ) {
                        maxQ = qValuesArray[idx];
                        bestAction = action;
                    }
                }
            }
            
            return bestAction;
            
        } catch (Exception e) {
            LOGGER.error("Error in action selection", e);
            return validActions.get(0);
        }
    }

    /**
     * Store experience for replay learning
     * CRITICAL: Sanitizes state vectors to prevent NaN corruption
     */
    public void addExperience(float[] state, String action, float reward, float[] nextState, boolean done) {
        Integer actionIdx = actionIndexMap.get(action);
        if (actionIdx == null) return;
        
        // NaN Protection: sanitize both states
        state = sanitizeState(state);
        nextState = sanitizeState(nextState);
        
        // Sanitize reward
        if (Float.isNaN(reward) || Float.isInfinite(reward)) {
            reward = 0.0f;
        }
        
        Experience exp = new Experience(state, actionIdx, reward, nextState, done);
        
        // Add to replay buffer
        replayBuffer.offer(exp);
        
        // Limit buffer size
        while (replayBuffer.size() > REPLAY_BUFFER_SIZE) {
            replayBuffer.poll();
        }
        
        // Queue training on background thread (CRITICAL: never block main thread)
        if (replayBuffer.size() >= BATCH_SIZE && trainingSteps % 4 == 0) {
            TRAINING_EXECUTOR.submit(() -> {
                try {
                    trainOnBatch();
                } catch (Exception e) {
                    LOGGER.error("Training error in background thread", e);
                }
            });
        }
        
        trainingSteps++;
    }

    /**
     * Train the network on a batch of experiences
     */
    private void trainOnBatch() {
        if (trainer == null) return;
        
        try (NDManager batchManager = manager.newSubManager()) {
            // Sample random batch from replay buffer
            List<Experience> batch = sampleBatch();
            if (batch.size() < BATCH_SIZE) return;
            
            // Prepare batch data
            float[][] states = new float[BATCH_SIZE][INPUT_SIZE];
            float[][] targets = new float[BATCH_SIZE][OUTPUT_SIZE];
            
            for (int i = 0; i < BATCH_SIZE; i++) {
                Experience exp = batch.get(i);
                states[i] = exp.state;
                
                // Calculate target Q-values
                NDArray currentQ = predictQValues(batchManager, exp.state);
                float[] qValues = currentQ.toFloatArray();
                
                // Q-learning update: Q(s,a) = r + gamma * max(Q(s',a'))
                if (exp.done) {
                    qValues[exp.action] = exp.reward;
                } else {
                    NDArray nextQ = predictQValues(batchManager, exp.nextState);
                    float maxNextQ = nextQ.max().getFloat();
                    qValues[exp.action] = exp.reward + DISCOUNT_FACTOR * maxNextQ;
                }
                
                targets[i] = qValues;
            }
            
            // Convert to NDArrays
            NDArray stateArray = batchManager.create(states);
            NDArray targetArray = batchManager.create(targets);
            
            // Training step
            try {
                NDList data = new NDList(stateArray);
                NDList labels = new NDList(targetArray);
                
                trainer.step();
                
                // Decay epsilon
                epsilon = Math.max(MIN_EPSILON, epsilon * EPSILON_DECAY);
                
            } catch (Exception e) {
                LOGGER.error("Error during training step", e);
            }
            
        } catch (Exception e) {
            LOGGER.error("Error in batch training", e);
        }
    }

    /**
     * Predict Q-values for a state
     */
    private NDArray predictQValues(NDManager localManager, float[] state) {
        NDArray stateArray = localManager.create(state).reshape(1, INPUT_SIZE);
        
        return model.getBlock().forward(
            new ai.djl.training.ParameterStore(localManager, false),
            new NDList(stateArray),
            false
        ).singletonOrThrow();
    }

    /**
     * Sample a random batch from replay buffer
     */
    private List<Experience> sampleBatch() {
        List<Experience> buffer = new ArrayList<>(replayBuffer);
        Collections.shuffle(buffer);
        return buffer.subList(0, Math.min(BATCH_SIZE, buffer.size()));
    }
    
    /**
     * Sanitize state vector to prevent NaN/Inf corruption
     * CRITICAL: All state vectors must pass through this
     */
    private float[] sanitizeState(float[] state) {
        if (state == null || state.length != INPUT_SIZE) {
            return new float[INPUT_SIZE]; // Return zeros
        }
        
        float[] clean = new float[state.length];
        for (int i = 0; i < state.length; i++) {
            float v = state[i];
            // Replace NaN/Inf with 0
            if (Float.isNaN(v) || Float.isInfinite(v)) {
                clean[i] = 0.0f;
            } else {
                // Clamp to reasonable range [-10, 10]
                clean[i] = Math.max(-10.0f, Math.min(10.0f, v));
            }
        }
        return clean;
    }

    /**
     * Save the trained model
     */
    public void saveModel() {
        try {
            Path modelDir = Paths.get("config", "mca-ai-models");
            Files.createDirectories(modelDir);
            
            model.save(modelDir, "mob_behavior");
            LOGGER.info("Saved mob learning model (training steps: {}, epsilon: {})", 
                trainingSteps, epsilon);
            
        } catch (IOException e) {
            LOGGER.error("Failed to save model", e);
        }
    }

    /**
     * Get current exploration rate
     */
    public float getEpsilon() {
        return epsilon;
    }

    /**
     * Get total training steps
     */
    public int getTrainingSteps() {
        return trainingSteps;
    }

    /**
     * Get replay buffer size
     */
    public int getExperienceCount() {
        return replayBuffer.size();
    }

    /**
     * Clean up resources
     */
    public void close() {
        // Shutdown training executor gracefully
        TRAINING_EXECUTOR.shutdown();
        try {
            if (!TRAINING_EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)) {
                TRAINING_EXECUTOR.shutdownNow();
            }
        } catch (InterruptedException e) {
            TRAINING_EXECUTOR.shutdownNow();
        }
        
        if (trainer != null) {
            trainer.close();
        }
        if (model != null) {
            model.close();
        }
        if (manager != null) {
            manager.close();
        }
    }

    /**
     * Experience tuple for replay learning
     */
    private static class Experience {
        final float[] state;
        final int action;
        final float reward;
        final float[] nextState;
        final boolean done;

        Experience(float[] state, int action, float reward, float[] nextState, boolean done) {
            this.state = state;
            this.action = action;
            this.reward = reward;
            this.nextState = nextState;
            this.done = done;
        }
    }
}
