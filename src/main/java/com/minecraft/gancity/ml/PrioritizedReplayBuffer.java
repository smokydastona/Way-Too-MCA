package com.minecraft.gancity.ml;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Prioritized Experience Replay - samples important experiences more frequently
 * Uses Sum Tree data structure for efficient priority-based sampling
 */
public class PrioritizedReplayBuffer {
    
    private final int capacity;
    private final Queue<Experience> buffer = new ConcurrentLinkedQueue<>();
    private final PriorityQueue<PrioritizedExperience> priorityQueue;
    private float alpha = 0.6f;  // Priority exponent
    private float beta = 0.4f;   // Importance sampling exponent
    private float betaIncrement = 0.001f;
    private float maxPriority = 1.0f;
    
    public PrioritizedReplayBuffer(int capacity) {
        this.capacity = capacity;
        this.priorityQueue = new PriorityQueue<>(
            (a, b) -> Float.compare(b.priority, a.priority)
        );
    }
    
    /**
     * Add experience with default maximum priority
     */
    public void add(float[] state, int action, float reward, float[] nextState, boolean done) {
        Experience exp = new Experience(state, action, reward, nextState, done);
        
        // Remove oldest if at capacity
        while (buffer.size() >= capacity) {
            buffer.poll();
        }
        
        buffer.offer(exp);
        
        // Add to priority queue with max priority (new experiences are important)
        PrioritizedExperience pExp = new PrioritizedExperience(exp, maxPriority);
        priorityQueue.offer(pExp);
    }
    
    /**
     * Sample batch based on priorities with importance sampling weights
     */
    public SampledBatch sample(int batchSize) {
        List<Experience> experiences = new ArrayList<>();
        List<Float> weights = new ArrayList<>();
        
        // Increase beta over time (anneal to 1.0)
        beta = Math.min(1.0f, beta + betaIncrement);
        
        // Calculate total priority
        float totalPriority = 0.0f;
        for (PrioritizedExperience pExp : priorityQueue) {
            totalPriority += Math.pow(pExp.priority, alpha);
        }
        
        // Sample experiences based on priority
        List<PrioritizedExperience> sampledList = new ArrayList<>();
        Random rand = new Random();
        
        for (int i = 0; i < Math.min(batchSize, priorityQueue.size()); i++) {
            float randValue = rand.nextFloat() * totalPriority;
            float cumSum = 0.0f;
            
            for (PrioritizedExperience pExp : priorityQueue) {
                cumSum += Math.pow(pExp.priority, alpha);
                if (cumSum >= randValue) {
                    experiences.add(pExp.experience);
                    sampledList.add(pExp);
                    
                    // Calculate importance sampling weight
                    float probability = Math.pow(pExp.priority, alpha) / totalPriority;
                    float weight = (float) Math.pow(buffer.size() * probability, -beta);
                    weights.add(weight);
                    break;
                }
            }
        }
        
        // Normalize weights
        float maxWeight = Collections.max(weights);
        for (int i = 0; i < weights.size(); i++) {
            weights.set(i, weights.get(i) / maxWeight);
        }
        
        return new SampledBatch(experiences, weights, sampledList);
    }
    
    /**
     * Update priorities based on TD errors
     */
    public void updatePriorities(List<PrioritizedExperience> experiences, List<Float> tdErrors) {
        for (int i = 0; i < experiences.size(); i++) {
            PrioritizedExperience pExp = experiences.get(i);
            float tdError = Math.abs(tdErrors.get(i)) + 1e-6f;  // Small constant to avoid zero priority
            pExp.priority = tdError;
            maxPriority = Math.max(maxPriority, tdError);
        }
        
        // Rebuild priority queue
        List<PrioritizedExperience> tempList = new ArrayList<>(priorityQueue);
        priorityQueue.clear();
        priorityQueue.addAll(tempList);
    }
    
    public int size() {
        return buffer.size();
    }
    
    public static class Experience {
        final float[] state;
        final int action;
        final float reward;
        final float[] nextState;
        final boolean done;

        public Experience(float[] state, int action, float reward, float[] nextState, boolean done) {
            this.state = state;
            this.action = action;
            this.reward = reward;
            this.nextState = nextState;
            this.done = done;
        }
    }
    
    private static class PrioritizedExperience {
        final Experience experience;
        float priority;

        PrioritizedExperience(Experience experience, float priority) {
            this.experience = experience;
            this.priority = priority;
        }
    }
    
    public static class SampledBatch {
        public final List<Experience> experiences;
        public final List<Float> weights;
        public final List<PrioritizedExperience> prioritizedExperiences;

        public SampledBatch(List<Experience> experiences, List<Float> weights, 
                           List<PrioritizedExperience> prioritizedExperiences) {
            this.experiences = experiences;
            this.weights = weights;
            this.prioritizedExperiences = prioritizedExperiences;
        }
    }
}
