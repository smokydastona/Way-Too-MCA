package com.minecraft.gancity.ml;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Genetic Algorithm for evolving mob behaviors over generations
 * Breeds successful behavior patterns to create increasingly effective AI
 */
public class GeneticBehaviorEvolution {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    private static final int POPULATION_SIZE = 20;
    private static final int ELITE_SIZE = 4;
    private static final float MUTATION_RATE = 0.15f;
    private static final int GENERATION_SIZE = 50;  // Combats per generation
    
    private List<BehaviorGenome> population = new ArrayList<>();
    private int generationNumber = 0;
    private int combatsInGeneration = 0;
    
    public GeneticBehaviorEvolution() {
        initializePopulation();
    }
    
    /**
     * Initialize random population of behavior genomes
     */
    private void initializePopulation() {
        for (int i = 0; i < POPULATION_SIZE; i++) {
            population.add(new BehaviorGenome());
        }
        LOGGER.info("Initialized genetic population with {} genomes", POPULATION_SIZE);
    }
    
    /**
     * Select genome for current combat
     */
    public BehaviorGenome selectGenome() {
        // Tournament selection
        Random rand = new Random();
        BehaviorGenome best = population.get(rand.nextInt(population.size()));
        
        for (int i = 0; i < 3; i++) {
            BehaviorGenome competitor = population.get(rand.nextInt(population.size()));
            if (competitor.fitness > best.fitness) {
                best = competitor;
            }
        }
        
        return best;
    }
    
    /**
     * Record combat outcome and evolve if generation complete
     */
    public void recordCombat(BehaviorGenome genome, boolean victory, float damageDealt, float damageTaken) {
        // Update genome fitness
        float fitness = 0.0f;
        if (victory) fitness += 10.0f;
        fitness += damageDealt * 2.0f;
        fitness -= damageTaken;
        
        genome.fitness += fitness;
        genome.combatCount++;
        
        combatsInGeneration++;
        
        // Evolve new generation
        if (combatsInGeneration >= GENERATION_SIZE) {
            evolveGeneration();
            combatsInGeneration = 0;
        }
    }
    
    /**
     * Evolve new generation using selection, crossover, and mutation
     */
    private void evolveGeneration() {
        generationNumber++;
        
        // Sort by fitness
        population.sort((a, b) -> Float.compare(b.fitness, a.fitness));
        
        // Log best genome
        float bestFitness = population.get(0).fitness / population.get(0).combatCount;
        float avgFitness = (float) population.stream()
            .mapToDouble(g -> g.fitness / Math.max(1, g.combatCount))
            .average().orElse(0.0);
        
        LOGGER.info("Generation {} complete - Best: {:.2f}, Avg: {:.2f}", 
            generationNumber, bestFitness, avgFitness);
        
        List<BehaviorGenome> newPopulation = new ArrayList<>();
        
        // Elitism - keep best genomes
        for (int i = 0; i < ELITE_SIZE; i++) {
            newPopulation.add(population.get(i).clone());
        }
        
        // Breed new genomes
        while (newPopulation.size() < POPULATION_SIZE) {
            BehaviorGenome parent1 = selectParent();
            BehaviorGenome parent2 = selectParent();
            BehaviorGenome child = crossover(parent1, parent2);
            mutate(child);
            newPopulation.add(child);
        }
        
        population = newPopulation;
    }
    
    /**
     * Select parent using fitness-proportional selection
     */
    private BehaviorGenome selectParent() {
        float totalFitness = (float) population.stream()
            .mapToDouble(g -> Math.max(0, g.fitness))
            .sum();
        
        float rand = new Random().nextFloat() * totalFitness;
        float cumulative = 0.0f;
        
        for (BehaviorGenome genome : population) {
            cumulative += Math.max(0, genome.fitness);
            if (cumulative >= rand) {
                return genome;
            }
        }
        
        return population.get(0);
    }
    
    /**
     * Crossover two parent genomes
     */
    private BehaviorGenome crossover(BehaviorGenome parent1, BehaviorGenome parent2) {
        BehaviorGenome child = new BehaviorGenome();
        Random rand = new Random();
        
        // Crossover action weights
        for (String action : parent1.actionWeights.keySet()) {
            child.actionWeights.put(action, 
                rand.nextBoolean() ? parent1.actionWeights.get(action) : parent2.actionWeights.get(action));
        }
        
        // Crossover traits
        child.aggression = rand.nextBoolean() ? parent1.aggression : parent2.aggression;
        child.caution = rand.nextBoolean() ? parent1.caution : parent2.caution;
        child.teamwork = rand.nextBoolean() ? parent1.teamwork : parent2.teamwork;
        
        return child;
    }
    
    /**
     * Apply random mutations
     */
    private void mutate(BehaviorGenome genome) {
        Random rand = new Random();
        
        // Mutate action weights
        for (String action : genome.actionWeights.keySet()) {
            if (rand.nextFloat() < MUTATION_RATE) {
                float delta = (rand.nextFloat() - 0.5f) * 0.4f;
                float newWeight = Math.max(0.0f, Math.min(2.0f, genome.actionWeights.get(action) + delta));
                genome.actionWeights.put(action, newWeight);
            }
        }
        
        // Mutate traits
        if (rand.nextFloat() < MUTATION_RATE) {
            genome.aggression += (rand.nextFloat() - 0.5f) * 0.4f;
            genome.aggression = Math.max(0.0f, Math.min(2.0f, genome.aggression));
        }
        
        if (rand.nextFloat() < MUTATION_RATE) {
            genome.caution += (rand.nextFloat() - 0.5f) * 0.4f;
            genome.caution = Math.max(0.0f, Math.min(2.0f, genome.caution));
        }
        
        if (rand.nextFloat() < MUTATION_RATE) {
            genome.teamwork += (rand.nextFloat() - 0.5f) * 0.4f;
            genome.teamwork = Math.max(0.0f, Math.min(2.0f, genome.teamwork));
        }
    }
    
    public int getGenerationNumber() {
        return generationNumber;
    }
    
    public float getBestFitness() {
        if (population.isEmpty()) return 0.0f;
        BehaviorGenome best = population.stream()
            .max(Comparator.comparing(g -> g.fitness / Math.max(1, g.combatCount)))
            .orElse(population.get(0));
        return best.fitness / Math.max(1, best.combatCount);
    }
    
    /**
     * Genome representing behavior parameters
     */
    public static class BehaviorGenome implements Cloneable {
        public Map<String, Float> actionWeights = new HashMap<>();
        public float aggression = 1.0f;
        public float caution = 1.0f;
        public float teamwork = 1.0f;
        public float fitness = 0.0f;
        public int combatCount = 0;
        
        public BehaviorGenome() {
            Random rand = new Random();
            
            // Initialize random action weights
            String[] actions = {
                "straight_charge", "circle_strafe", "kite_backward", "retreat",
                "ambush", "group_rush", "find_cover", "strafe_shoot",
                "leap_attack", "fake_retreat"
            };
            
            for (String action : actions) {
                actionWeights.put(action, rand.nextFloat() * 2.0f);
            }
            
            aggression = rand.nextFloat() * 2.0f;
            caution = rand.nextFloat() * 2.0f;
            teamwork = rand.nextFloat() * 2.0f;
        }
        
        @Override
        public BehaviorGenome clone() {
            BehaviorGenome copy = new BehaviorGenome();
            copy.actionWeights = new HashMap<>(this.actionWeights);
            copy.aggression = this.aggression;
            copy.caution = this.caution;
            copy.teamwork = this.teamwork;
            copy.fitness = 0.0f;  // Reset fitness for new generation
            copy.combatCount = 0;
            return copy;
        }
    }
}
