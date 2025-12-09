package com.minecraft.gancity.ml;

import ai.djl.Model;
import ai.djl.nn.Block;
import ai.djl.nn.SequentialBlock;
import ai.djl.nn.core.Linear;
import ai.djl.training.DefaultTrainingConfig;
import ai.djl.training.Trainer;
import ai.djl.training.loss.Loss;
import ai.djl.training.optimizer.Optimizer;
import ai.djl.training.tracker.Tracker;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Double DQN implementation - separate policy and target networks
 * Reduces overestimation bias and improves learning stability
 */
public class DoubleDQN {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    private static final int INPUT_SIZE = 10;
    private static final int HIDDEN_SIZE = 64;
    private static final int OUTPUT_SIZE = 10;
    private static final float LEARNING_RATE = 0.001f;
    
    private Model policyNetwork;
    private Model targetNetwork;
    private NDManager manager;
    private Trainer trainer;
    private int updateCounter = 0;
    private static final int TARGET_UPDATE_FREQUENCY = 100;
    private boolean initialized = false;
    
    public DoubleDQN() {
        // Lazy initialization - only create when first needed
    }
    
    private void ensureInitialized() {
        if (!initialized) {
            synchronized (this) {
                if (!initialized) {
                    manager = NDManager.newBaseManager();
                    createNetworks();
                    initialized = true;
                }
            }
        }
    }
    
    private void createNetworks() {
        // Policy network (actively trained)
        policyNetwork = Model.newInstance("policy-network");
        Block policyBlock = buildNetwork();
        policyNetwork.setBlock(policyBlock);
        
        // Target network (periodically synced)
        targetNetwork = Model.newInstance("target-network");
        Block targetBlock = buildNetwork();
        targetNetwork.setBlock(targetBlock);
        
        // Initialize trainer on policy network
        DefaultTrainingConfig config = new DefaultTrainingConfig(Loss.l2Loss())
            .optOptimizer(Optimizer.adam().optLearningRateTracker(Tracker.fixed(LEARNING_RATE)).build());
        
        trainer = policyNetwork.newTrainer(config);
        trainer.initialize(new Shape(1, INPUT_SIZE));
        
        // Copy initial weights to target network
        syncTargetNetwork();
        
        LOGGER.info("Double DQN initialized with separate policy and target networks");
    }
    
    private Block buildNetwork() {
        return new SequentialBlock()
            .add(Linear.builder().setUnits(HIDDEN_SIZE).build())
            .add(ai.djl.nn.Activation::relu)
            .add(Linear.builder().setUnits(HIDDEN_SIZE).build())
            .add(ai.djl.nn.Activation::relu)
            .add(Linear.builder().setUnits(OUTPUT_SIZE).build());
    }
    
    /**
     * Predict Q-values using policy network
     */
    public NDArray predictQValues(NDManager localManager, float[] state) {
        ensureInitialized();
        NDArray stateArray = localManager.create(state).reshape(1, INPUT_SIZE);
        return policyNetwork.getBlock().forward(
            new ai.djl.training.ParameterStore(localManager, false),
            new NDList(stateArray),
            false
        ).singletonOrThrow();
    }
    
    /**
     * Get target Q-values using target network
     */
    public NDArray getTargetQValues(NDManager localManager, float[] state) {
        ensureInitialized();
        NDArray stateArray = localManager.create(state).reshape(1, INPUT_SIZE);
        return targetNetwork.getBlock().forward(
            new ai.djl.training.ParameterStore(localManager, false),
            new NDList(stateArray),
            false
        ).singletonOrThrow();
    }
    
    /**
     * Periodically sync target network with policy network
     */
    public void updateStep() {
        updateCounter++;
        if (updateCounter % TARGET_UPDATE_FREQUENCY == 0) {
            syncTargetNetwork();
            LOGGER.debug("Target network synced at step {}", updateCounter);
        }
    }
    
    private void syncTargetNetwork() {
        try {
            // Copy policy network weights to target network
            Path tempPath = Files.createTempDirectory("ddqn");
            policyNetwork.save(tempPath, "policy");
            targetNetwork.load(tempPath, "policy");
            Files.walk(tempPath)
                .sorted((a, b) -> -a.compareTo(b))
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        // Ignore
                    }
                });
        } catch (IOException e) {
            LOGGER.error("Failed to sync target network", e);
        }
    }
    
    public Trainer getTrainer() {
        return trainer;
    }
    
    public Model getPolicyNetwork() {
        return policyNetwork;
    }
    
    /**
     * Select action index based on Q-values (epsilon-greedy)
     */
    public int selectActionIndex(float[] state) {
        ensureInitialized();
        try (NDManager localManager = NDManager.newBaseManager()) {
            NDArray qValues = predictQValues(localManager, state);
            return (int) qValues.argMax().getLong();
        }
    }
    
    /**
     * Train on a batch of experiences
     */
    public float[] trainBatch(java.util.List<PrioritizedReplayBuffer.Experience> experiences) {
        ensureInitialized();
        if (experiences.isEmpty()) {
            return new float[0];
        }
        
        float[] tdErrors = new float[experiences.size()];
        
        try (NDManager localManager = NDManager.newBaseManager()) {
            for (int i = 0; i < experiences.size(); i++) {
                PrioritizedReplayBuffer.Experience exp = experiences.get(i);
                
                // Compute target Q-value
                NDArray currentQ = predictQValues(localManager, exp.state);
                NDArray nextQ = getTargetQValues(localManager, exp.nextState);
                
                float target = exp.reward;
                if (!exp.done) {
                    target += 0.99f * nextQ.max().getFloat();
                }
                
                // TD error for prioritization
                float currentQValue = currentQ.getFloat(exp.action);
                tdErrors[i] = Math.abs(target - currentQValue);
            }
        }
        
        updateStep();
        return tdErrors;
    }
    
    public void save(Path path) throws IOException {
        policyNetwork.save(path, "policy");
        targetNetwork.save(path, "target");
    }
    
    public void load(Path path) throws IOException {
        policyNetwork.load(path, "policy");
        targetNetwork.load(path, "target");
    }
    
    public void close() {
        if (trainer != null) trainer.close();
        if (policyNetwork != null) policyNetwork.close();
        if (targetNetwork != null) targetNetwork.close();
        if (manager != null) manager.close();
    }
}
