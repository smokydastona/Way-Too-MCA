# MCA AI Enhanced - Machine Learning Mob Behavior

A Minecraft 1.20.1 Forge mod that uses **actual machine learning** to make ALL vanilla mobs progressively smarter over time. Mobs learn from every interaction using a Deep Q-Network and evolve their behavior to create an ever-changing, adaptive world.

## Features

### 🧠 Real Machine Learning
- **Deep Q-Network** with experience replay
- **Progressive evolution** - mobs genuinely learn and improve
- **Online training** - neural network trains during gameplay
- **Model persistence** - learned behavior saves across sessions
- **World-adaptive** - discovers tactics that work in YOUR world
- **70+ mob types** - Every vanilla Minecraft mob can learn and evolve

### 🎮 Universal Mob AI Enhancement
- **Hostile Mobs** (40+): Zombies, skeletons, creepers, spiders, endermen, blazes, ghasts, phantoms, guardians, pillagers, witches, wardens, and more
- **Neutral Mobs** (15+): Wolves, polar bears, bees, iron golems, llamas, pandas, dolphins, piglins, and more  
- **Passive Mobs** (25+): Villagers, animals, fish - learn evasion, survival, and group tactics
- **Boss Mobs** (3): Ender Dragon, Wither, Warden - adaptive boss fights that learn from your strategies
- **500+ tactical behaviors**: Coordinated attacks, ambush tactics, terrain usage, pack hunting, evasion patterns, and more

### 🌍 Living, Evolving World
- **Hostile mobs** learn combat tactics and adapt to your playstyle
- **Passive mobs** learn survival behaviors - fleeing, hiding, group defense
- **Neutral mobs** develop sophisticated hunting and defense patterns
- **Aquatic life** learns ocean navigation and predator evasion
- **Every creature evolves** based on their experiences in your world

### 💬 MCA Reborn Integration (Optional)
- AI-powered villager dialogue generation
- Evolving personalities and moods
- 40+ context-aware dialogue templates
- Soft dependency - mob AI works without MCA

## Quick Start

### Requirements
- Minecraft 1.20.1
- Forge 47.4.0+
- Java 17

### Installation

#### Option 1: Full ML Features (Recommended)
1. Download **both** JARs from releases:
   - `Adaptive-Mob-Ai-{version}.jar` (~500KB - core mod)
   - `Adaptive-Mob-Ai-ML-Libraries-{version}.jar` (~50MB - neural network libraries)
2. Place **both** JARs in `mods/` folder
3. Launch Minecraft with Forge
4. Mobs will use real neural networks for learning

#### Option 2: Lightweight Rule-Based AI
1. Download **only** `Adaptive-Mob-Ai-{version}.jar`
2. Place in `mods/` folder
3. Launch Minecraft with Forge
4. Mobs will use advanced rule-based AI (no neural network)

> **Note**: The ML Libraries JAR contains DJL (Deep Java Library) and PyTorch. If you only want to try the mod without the large download, use Option 2 first. You can add the ML Libraries JAR later to enable true machine learning.

## How It Works

ALL mobs use a **neural network** trained via **reinforcement learning**:

1. **Observe** - Mobs analyze their environment (health, nearby entities, terrain, biome, time)
2. **Predict** - Neural network calculates Q-values for each possible action
3. **Execute** - Highest-value action (90% exploitation) or random exploration (10%)
4. **Experience** - Record outcome with rewards/penalties based on survival and success
5. **Learn** - Train network using experience replay to improve decision-making
6. **Evolve** - Over time, entire ecosystems develop emergent adaptive behaviors

### Mob-Specific Learning Examples
- **Zombies** learn to coordinate group attacks and flank players
- **Skeletons** discover optimal firing positions and kiting patterns  
- **Creepers** develop stealth approaches and explosion timing
- **Endermen** master teleportation tactics to avoid damage
- **Villagers** learn to recognize danger, hide effectively, and alert others
- **Wolves** evolve pack hunting strategies
- **Bees** coordinate swarm defense of their hives
- **Passive animals** develop predator evasion and safe grazing routes
- **Guardians** perfect laser focus timing and temple defense
- **Warden** adapts sonic boom usage based on player movement patterns

### Progression
- **Early world** (0-100 interactions): Random exploration, learning basics
- **Developing world** (100-500): Behavioral patterns emerge across all mob types
- **Mature ecosystem** (500-2000): Optimized behaviors, predator-prey dynamics evolve
- **Advanced world** (2000+): Complex emergent behaviors, mob societies develop adaptive strategies

## Documentation

- **[PERFORMANCE_OPTIMIZATIONS.md](PERFORMANCE_OPTIMIZATIONS.md)** - Critical performance fixes for lag-free gameplay
- **[ML_IMPLEMENTATION.md](ML_IMPLEMENTATION.md)** - Deep dive into neural network architecture
- **[QUICK_START_ML.md](QUICK_START_ML.md)** - Setup and testing guide
- **[AI_MOD_README.md](AI_MOD_README.md)** - User-facing features
- **[.github/copilot-instructions.md](.github/copilot-instructions.md)** - Developer guide

## Building from Source

### Prerequisites
```bash
# If gradlew is missing, copy from another Forge 1.20.1 project
# or generate with: gradle wrapper --gradle-version=8.1.1
```

### Build
```bash
.\gradlew build          # Creates TWO JARs in build/libs/:
                         # 1. Adaptive-Mob-Ai-{version}.jar (~500KB)
                         # 2. Adaptive-Mob-Ai-ML-Libraries-{version}.jar (~50MB)

.\gradlew runClient      # Test in development environment
```

> **Build Output**: The build process creates both JARs automatically. Distribute both for full ML features, or just the main JAR for rule-based AI.

## Configuration

Edit `config/mca-ai-enhanced-common.toml`:

```toml
# AI difficulty multiplier (affects learning speed and exploration rate)
aiDifficulty = 1.0  # Range: 0.5 (easy) to 3.0 (very hard)

# Master toggle for mob AI system
enableMobAI = true

# Individual mob type toggles (70+ types supported)
# Hostile mobs
zombieAI = true
skeletonAI = true
creeperAI = true
spiderAI = true
endermanAI = true
# ... and 65+ more mob types!

# Passive mob learning (evasion and survival tactics)
passiveMobLearning = true

# Boss AI enhancement
bossMobAI = true
```

## Technical Details

### Architecture
- **Framework**: Deep Java Library (DJL) with PyTorch
- **Algorithm**: Deep Q-Learning with experience replay
- **Network**: 22 inputs → 64 hidden → 64 hidden → 10+ Q-values per mob type
- **Training**: Adam optimizer, batch size 32, learning rate 0.001
- **Exploration**: Epsilon-greedy (1.0 → 0.1 decay over 1000 experiences)
- **Coverage**: 70+ vanilla mob types with unique behavior profiles
- **Actions**: 500+ unique tactical behaviors across all mob types

### Performance (Optimized for 100+ Mobs)
- **Memory**: ~30MB total (capped, no memory leaks)
- **CPU**: <1ms per mob action (80% cached), background training never blocks game
- **Storage**: ~200KB saved model file
- **TPS**: Stable 20 TPS with 100+ learning mobs
- **Features**: Background training thread, output caching, object pooling, rate limiting

## Credits

**Algorithm inspiration:**
- DeepMind's DQN (Deep Q-Network)
- Sutton & Barto - Reinforcement Learning textbook
- OpenAI Gym environment patterns

**Dependencies:**
- Deep Java Library (DJL) 0.25.0
- PyTorch engine
- MCA Reborn (optional, for villager dialogue)

## License

See [LICENSE](LICENSE) file.

## Support

For issues, questions, or suggestions, please open a GitHub issue.

---

**This is REAL machine learning.** Mobs use gradient descent to optimize a neural network through reinforcement learning, not scripted difficulty scaling.
