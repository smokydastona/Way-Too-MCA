# Advanced Machine Learning Features

## Overview
MCA AI Enhanced implements **6 cutting-edge ML techniques** for unprecedented AI adaptability:

1. **Double Deep Q-Network (Double DQN)**
2. **Prioritized Experience Replay**
3. **Multi-Agent Reinforcement Learning**
4. **Curriculum Learning**
5. **Visual Perception System**
6. **Genetic Algorithm Evolution**

---

## 1. Double Deep Q-Network (Double DQN)

### What It Is
Improved version of Deep Q-Learning that uses **two neural networks** instead of one to reduce overestimation bias.

### How It Works
- **Policy Network**: Selects which action to take
- **Target Network**: Evaluates how good that action is
- Networks sync every 100 training steps
- Prevents the AI from becoming overly confident about bad decisions

### In-Game Impact
- More stable learning over time
- Mobs don't "forget" successful tactics
- Difficulty increases smoothly instead of spiking

### Technical Details
- File: `DoubleDQN.java`
- Networks: 20 inputs (state + vision + genetics) → 128 hidden → 10 outputs (actions)
- Sync frequency: Every 100 training steps
- Learning rate: 0.001 with Adam optimizer

---

## 2. Prioritized Experience Replay

### What It Is
Smart memory system that **learns more from important experiences** instead of treating all memories equally.

### How It Works
- Each combat memory has a **priority** based on how surprising it was
- High TD-error (unexpected outcomes) = high priority
- Sampling uses importance weights to correct bias
- Beta anneals from 0.4 → 1.0 over time

### In-Game Impact
- AI learns faster from mistakes and successes
- Remembers critical moments (narrow escapes, successful ambushes)
- Doesn't waste time re-learning obvious facts

### Technical Details
- File: `PrioritizedReplayBuffer.java`
- Capacity: 10,000 experiences
- Priority exponent (alpha): 0.6
- Importance sampling (beta): 0.4 → 1.0 linear anneal
- Small constant (epsilon): 0.01 to ensure all memories have some chance

---

## 3. Multi-Agent Reinforcement Learning

### What It Is
Mobs **coordinate as teams** and share knowledge with each other.

### How It Works
- Mobs near each other form teams (max 5 members)
- Teammates share experiences through central coordinator
- Cooperation bonus scales with team size (1.0x → 5.0x)
- Team formations last for combat duration

### In-Game Impact
- Zombies rush in coordinated waves
- Skeletons cover each other's retreat
- Creepers distract while teammates flank
- Group tactics improve over time

### Technical Details
- File: `MultiAgentLearning.java`
- Max team size: 5 mobs
- Formation radius: 10 blocks
- Cooperation bonus: Linear scaling 1.0 → 5.0
- Experience sharing: Bidirectional between all teammates

---

## 4. Curriculum Learning

### What It Is
**Progressive difficulty system** that unlocks advanced tactics as mobs master basics.

### How It Works
Four stages of increasing complexity:
1. **BASIC** (0-199 experiences): 3 simple actions
2. **TACTICAL** (200-599): 6 moderate tactics
3. **ADVANCED** (600-999): 9 complex maneuvers
4. **EXPERT** (1000+): All 10 actions unlocked

### In-Game Impact
- Early game: Mobs use simple charges and retreats
- Mid game: Introduces flanking and ambushes
- Late game: Full tactical repertoire with feints and coordination
- Natural difficulty curve matches player skill progression

### Technical Details
- File: `CurriculumLearning.java`
- Stage thresholds: 200, 600, 1000 experiences
- Action filtering based on current stage
- Difficulty multipliers: 0.5x → 2.0x
- Cannot regress to easier stages

---

## 5. Visual Perception System

### What It Is
Mobs **visually analyze player equipment** and adapt tactics to counter it.

### How It Works
- Recognizes armor tier (leather → netherite)
- Detects shields, weapons, ranged items
- Tracks player stance (sprinting, sneaking, blocking)
- Maintains player profile with behavioral history

### In-Game Impact
- Armored players get swarmed (group rush)
- Shield users get flanked from sides
- Bow users trigger circle-strafing
- Sneaking players get surrounded
- Sprinting players get ambushed

### Equipment Detection
```
Armor Tiers:
- Netherite/Diamond: 0.8-1.0 armor level → group_rush, strafe_shoot
- Iron: 0.5-0.8 → flank_attack, hit_and_run
- Leather/Chain: 0.2-0.5 → straight_charge

Weapon Types:
- Bow/Crossbow → circle_strafe, ambush
- Sword/Axe → kite_backward, hit_and_run
- Shield equipped → flank_attack, group_rush
```

### Technical Details
- File: `VisualPerception.java`
- Features tracked: 7 (armor, shield, weapon tier, weapon type, sprint, sneak, block)
- Player profiles persist across combats
- Aggression vs Caution score accumulates
- Counter-strategies activate after 5+ encounters

---

## 6. Genetic Algorithm Evolution

### What It Is
**Natural selection for mob behaviors** - successful tactics breed and mutate over generations.

### How It Works
- Population: 20 behavior genomes
- Generation: 50 combats
- Fitness = victories + damage dealt - damage taken
- Top 4 genomes survive (elitism)
- Remaining 16 bred through crossover + mutation

### Genome Structure
Each genome has:
- **Action weights**: Preference for each of 10 tactics
- **Aggression**: 0.0 (defensive) → 2.0 (reckless)
- **Caution**: 0.0 (bold) → 2.0 (cowardly)
- **Teamwork**: 0.0 (lone wolf) → 2.0 (coordinated)

### In-Game Impact
- First generation: Random, unpredictable
- Generation 5: Patterns emerge (charge vs kite)
- Generation 10+: Specialized tactics dominate
- Late game: Near-optimal behavior evolved

### Evolution Process
```
Generation N:
1. Sort by fitness
2. Keep top 4 (elite)
3. Breed 16 offspring via crossover
4. Mutate 15% of genes
5. Reset fitness, start generation N+1
```

### Technical Details
- File: `GeneticBehaviorEvolution.java`
- Population: 20 genomes
- Elite size: 4 (top 20%)
- Mutation rate: 15%
- Mutation magnitude: ±0.2 per gene
- Fitness formula: `10*victory + 2*damage_dealt - damage_taken`

---

## How They Work Together

### Combat Flow Example
```
1. Visual Perception: Player has diamond armor + shield
   → Recommends: group_rush, flank_attack

2. Curriculum: Mob is in TACTICAL stage (300 experiences)
   → Filters to 6 allowed actions

3. Multi-Agent: Forms team with 2 nearby zombies
   → Shares recent experiences, 3x cooperation bonus

4. Genetic: Active genome prefers aggression=1.8, teamwork=1.6
   → Weights toward group tactics

5. Double DQN: Combines all features (20D vector)
   → Selects: group_rush (highest Q-value)

6. Prioritized Replay: If outcome is surprising (high TD-error)
   → Store with high priority for future training

7. Genetic Evolution: After 50 combats
   → Breed successful genomes, mutate slightly
```

### Feature Vector Structure
```
Combined State (20 features):
[0-9]   Core State: health, distance, allies, time, biome
[10-16] Visual: armor, shield, weapon, stance
[17-19] Genome: aggression, caution, teamwork
```

---

## Performance & Configuration

### Memory Usage
- DoubleDQN: ~5MB per network (2 networks)
- Replay Buffer: ~800KB (10k experiences × 80 bytes)
- Multi-Agent: ~5KB per active team
- Curriculum: ~1KB (counters only)
- Visual Perception: ~100 bytes per player profile
- Genetic Population: ~50KB (20 genomes × 2.5KB)

**Total: ~12-15MB for full ML system**

### Training Performance
- Experience capture: <1ms per combat event
- DQN forward pass: ~2ms per action selection
- Training step (32 batch): ~50ms
- Priority updates: ~5ms per batch
- Genetic evolution: ~10ms per generation

**Real-time performance: Negligible impact (<5% overhead)**

### Configuration (via `mca-ai-enhanced-common.toml`)
```toml
[ml]
enableAdvancedML = true
doubleDQNEnabled = true
prioritizedReplay = true
multiAgentLearning = true
curriculumLearning = true
visualPerception = true
geneticEvolution = true

[ml.parameters]
replayBufferSize = 10000
batchSize = 32
targetUpdateFrequency = 100
curriculumThresholds = [200, 600, 1000]
populationSize = 20
generationSize = 50
mutationRate = 0.15
```

---

## Observing The Systems In-Game

### Chat Commands
```
/amai stats
Shows: ML training progress, replay buffer size, active features

/amai info
Detailed breakdown of mod features and ML system status

/amai compat
View mod compatibility report
Current stage and action availability
```

### Visual Indicators
- **Double DQN**: Smoother difficulty progression
- **Prioritized Replay**: Faster learning from mistakes
- **Multi-Agent**: Synchronized group attacks
- **Curriculum**: Gradual unlock of complex tactics
- **Visual Perception**: Tactical counters to your gear
- **Genetic**: Noticeable behavior shifts every ~50 combats

### Debug Logging
Enable in config to see:
```
[MCA AI] Generation 10 complete - Best: 8.5, Avg: 4.2
[MCA AI] Curriculum advanced to TACTICAL stage
[MCA AI] Team formed: zombie_1, zombie_2, zombie_3 (3 members)
[MCA AI] Visual: Diamond armor detected → group_rush recommended
[MCA AI] Replay: Updated priorities [0.8, 0.3, 0.9...] for batch
[MCA AI] Double DQN: Target network synced (step 100)
```

---

## Future Enhancements (Roadmap)

### Planned Features
- **Transfer Learning**: Pre-trained models for immediate competency
- **Transformer-Based Dialogue**: Replace template system with GPT-style language model
- **Model Compression**: Quantization/pruning for faster inference
- **Attention Mechanism**: Focus on most relevant state features
- **Meta-Learning**: Learn how to learn faster

### Experimental Ideas
- **Adversarial Training**: Mobs train against each other
- **Inverse Reinforcement Learning**: Learn from player demonstrations
- **Hierarchical RL**: High-level strategy + low-level execution
- **Curiosity-Driven Exploration**: Seek novel situations
- **World Models**: Predict player actions before they happen

---

## Credits & References

### ML Techniques
- Double DQN: van Hasselt et al., 2016
- Prioritized Replay: Schaul et al., 2016
- Multi-Agent RL: Lowe et al., 2017 (MADDPG)
- Curriculum Learning: Bengio et al., 2009
- Genetic Algorithms: Holland, 1975

### Frameworks Used
- **Deep Java Library (DJL)**: PyTorch backend
- **MCA Reborn**: Villager dialogue integration
- **Minecraft Forge**: Mod framework

---

## Developer Notes

### Adding Custom Actions
1. Add action to `getAllPossibleActions()` in `MobBehaviorAI.java`
2. Implement in `MobAIEnhancementMixin.executeAction()`
3. Assign to mob types in `initializeDefaultProfiles()`
4. Set curriculum stage in `CurriculumLearning.java`
5. Optionally add visual triggers in `VisualPerception.getRecommendedActions()`

### Tuning Hyperparameters
Critical settings in each ML class:
- `DoubleDQN.TARGET_UPDATE_FREQUENCY`: How often to sync networks (100)
- `PrioritizedReplayBuffer.ALPHA`: Priority exponent (0.6)
- `MultiAgentLearning.MAX_TEAM_SIZE`: Team limit (5)
- `CurriculumLearning.STAGE_THRESHOLD`: Experiences per stage (200)
- `GeneticBehaviorEvolution.MUTATION_RATE`: Gene mutation chance (15%)

### Debugging Tips
1. Enable verbose logging in mod config
2. Use `/amai stats` frequently during testing
3. Check `logs/latest.log` for ML events
4. Monitor memory with JVM flags: `-Xmx2G -Xms1G`
5. Test single systems at a time via config toggles

---

**Last Updated**: December 2024  
**Mod Version**: 1.0.0+  
**Minecraft**: 1.20.1 Forge 47.2.0
