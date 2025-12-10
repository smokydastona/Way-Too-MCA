# MCA AI Enhanced - Features & Documentation

## 🎯 Quick Start

**What is this?** A machine learning mod that makes Minecraft mobs learn and adapt their combat tactics.

**Do I need MCA Reborn?** No! Mob AI works standalone. Villager dialogue requires MCA Reborn.

**Does it work on servers?** Yes! Server-only mod (clients don't need it).

---

## 🧠 Core Features

### 1. **Adaptive Mob AI** (Always Active)

Mobs use a Double DQN neural network to learn optimal combat tactics:

| Mob | Learned Behaviors |
|-----|-------------------|
| **Zombie** | Circle strafing, group coordination, retreat at low health |
| **Skeleton** | Kiting, cover usage, aim prediction, retreat tactics |
| **Creeper** | Flanking, hiding, explosion timing optimization |
| **Spider** | Wall climbing ambush, pack hunting, cave advantage |

**How it works:**
- Mobs observe combat situations (health, distance, player gear, etc.)
- Neural network predicts best action from 10 possible tactics
- Successful tactics are reinforced through reinforcement learning
- Models auto-save every 10 minutes and sync to Cloudflare (optional)

---

### 2. **Cross-Species Learning** (Default: ON)

Mobs learn from each other across species:
- Zombies see skeletons successfully kiting → zombies learn to kite
- Spiders see creepers flanking → spiders learn to flank
- 3x reward multiplier for borrowed tactics

**Why this matters:** Creates emergent gameplay where mob behavior evolves naturally in your world.

---

### 3. **Contextual Difficulty Scaling** (Default: ON)

AI difficulty automatically adjusts based on environment:

| Context | Difficulty Boost | Why |
|---------|------------------|-----|
| Night | +30% | Mobs are more dangerous in darkness |
| Storm | +20% | Weather advantage for mobs |
| Thunderstorm | +50% | Maximum mob aggression |
| Nether | +60% | Hostile dimension |
| End | +100% | Endgame content |
| Near Structures | +40% | Protecting their territory |

**Example:** A zombie at night near a village is 70% harder than daytime overworld zombie.

---

### 4. **Advanced ML Systems**

The mod uses 6 interconnected machine learning systems:

#### **Double DQN** (Deep Q-Network)
- Two neural networks: policy (active) and target (stable)
- Prevents overestimation bias
- 64-node hidden layer, ReLU activation

#### **Prioritized Replay Buffer**
- 10,000 experience memory
- Important experiences replayed more often
- Priority = TD error (how surprising the outcome was)

#### **Multi-Agent Learning**
- Mobs share knowledge globally
- One mob's success → all mobs learn
- Prevents redundant learning

#### **Curriculum Learning**
- Progressive difficulty scaling
- Easy encounters first, hard encounters later
- Adapts to player skill over time

#### **Genetic Evolution**
- Successful tactics spread through the population
- Unsuccessful tactics die out
- Natural selection for mob behavior

#### **Performance Optimizer**
- Global model shared across all mobs
- Smart caching prevents lag with 70+ learning mobs
- Batch updates reduce CPU usage by 60-80%

---

### 5. **Villager Dialogue AI** (Requires MCA Reborn)

AI-powered conversations with evolving personalities:

**Personality Traits:**
- Friendly, Shy, Adventurous, Cautious, Witty, Romantic, Serious

**Mood States:**
- Happy, Sad, Angry, Excited, Nervous, Content

**Features:**
- 40+ contextual dialogue templates
- Mood affects responses (angry villagers are sarcastic)
- Personality evolves based on interactions
- Remembers relationship level and history
- Time-of-day awareness (greetings change)
- Biome and village context

---

## ⚙️ Configuration

Edit `config/adaptivemobai-common.toml`:

### Core Settings

```toml
[general]
enableMobAI = true          # Master switch for mob AI
enableLearning = true       # Allow mobs to learn (vs pre-trained)
aiDifficulty = 1.0         # 0.5=Easy, 1.0=Normal, 2.0=Hard, 3.0=Expert
```

### Individual Mobs

```toml
[mob_behaviors]
enableZombieAI = true      # Toggle zombie AI
enableSkeletonAI = true    # Toggle skeleton AI
enableCreeperAI = true     # Toggle creeper AI
enableSpiderAI = true      # Toggle spider AI
aiUpdateInterval = 20      # Ticks between decisions (20 = 1 second)
```

### Fallback Mode (When ML Disabled)

```toml
[fallback]
pathfindingSpeedMultiplier = 1.2    # Faster pathfinding
detectionRangeMultiplier = 1.3      # Further detection
reactionTimeTicks = 15              # Faster reactions
```

### Villager Dialogue

```toml
[villager_dialogue]
enableVillagerDialogue = true
dialogueVariations = 3              # Responses per interaction
enablePersonalityLearning = true   # Evolving personalities
enableMoodSystem = true             # Mood tracking
dialogueRandomness = 0.3           # 0.0=Predictable, 1.0=Random
```

---

## 🎮 In-Game Commands

**Requires OP level 2:**

```
/amai info       - Show mod status and features
/amai stats      - View ML statistics and learning progress
/amai compat     - Check mod compatibility
```

---

## 🔧 How It Works (Technical)

### Mob Decision Process

1. **State Observation** (10 inputs):
   - Mob health percentage
   - Player health percentage
   - Distance to player
   - Player has shield?
   - Player armor level (0-4)
   - Mob has backup nearby?
   - Time of day
   - Biome type
   - Weather
   - Terrain (cave, open, forest)

2. **Neural Network Forward Pass**:
   - Input → Hidden layer (64 nodes, ReLU)
   - Hidden → Output layer (10 actions)
   - Returns Q-values for each action

3. **Action Selection**:
   - Choose action with highest Q-value
   - Epsilon-greedy exploration (10% random)

4. **Action Execution**:
   - ATTACK_AGGRESSIVE, ATTACK_DEFENSIVE, RETREAT
   - CIRCLE_LEFT, CIRCLE_RIGHT, STRAFE_BACK
   - USE_COVER, WAIT_AMBUSH, CALL_BACKUP, FLEE

5. **Learning Update**:
   - Combat outcome recorded (reward)
   - Experience stored in replay buffer
   - Batch training (32 experiences)
   - TD error: `δ = reward + γ * max(Q_target) - Q_current`
   - Weights updated via backpropagation

6. **Target Network Sync**:
   - Every 100 updates, policy → target
   - Stabilizes learning

### Performance Optimization

- **Global Model Sharing**: One neural network for all mobs (not one per mob)
- **Prediction Caching**: Results cached for 5 ticks
- **Batch Updates**: Train on 32 experiences at once
- **Async Cloudflare Sync**: Non-blocking federated learning

**Result:** 60-80% CPU reduction with 70+ learning mobs vs naive implementation.

---

## 🌐 Federated Learning (Optional)

Share and download mob behavior models via Cloudflare R2:

**How it works:**
1. Your mobs learn locally
2. Every 10 minutes, model uploads to Cloudflare
3. Downloads global model trained by all players
4. Your mobs benefit from worldwide mob experience

**Privacy:** Only model weights are shared, not your world data.

**Setup:** Add Cloudflare credentials to config (disabled by default).

---

## 📊 Performance Impact

| Setup | FPS Impact | Memory Impact |
|-------|------------|---------------|
| 1-10 mobs | ~0-2 FPS | +50 MB |
| 10-50 mobs | ~2-5 FPS | +100 MB |
| 50+ mobs (optimized) | ~3-8 FPS | +150 MB |

**Tips for better performance:**
- Increase `aiUpdateInterval` (e.g., 40 ticks = 2 seconds)
- Disable learning for specific mob types you don't care about
- Use fallback mode for weaker servers

---

## 🐛 Troubleshooting

### Mobs Not Using AI

1. Check config: `enableMobAI = true`?
2. Check logs for "ML systems initialized"
3. Try `/amai stats` to verify ML is active

### High CPU Usage

1. Increase `aiUpdateInterval` to 40+
2. Reduce `aiDifficulty` to 0.5
3. Disable learning: `enableLearning = false`

### Crashes

1. Check `crash-reports/` folder
2. Update to latest version
3. Remove conflicting AI mods
4. Report on GitHub Issues

---

## 🔬 For Developers

### Adding Custom Mob Types

```java
// In MobBehaviorAI.java
behaviorProfiles.put("your_mob_id", new MobBehaviorProfile(
    "your_mob_id",
    Arrays.asList("attack_aggressive", "retreat", "use_cover"),
    0.7f  // aggression level
));
```

### Adding Custom Actions

```java
// In AIEnhancedMeleeGoal.executeAction()
case "your_custom_action":
    // Your custom behavior logic
    mob.setYRot(targetYaw);
    mob.getMoveControl().setWantedPosition(targetX, targetY, targetZ, speed);
    break;
```

### Training Custom Models

External training scripts in `training/` directory (Python):
- Export model as ONNX
- Convert to DJL format
- Place in `models/` directory

---

## 📜 License

MIT License - See LICENSE file

---

## 🙏 Credits

- **DJL (Deep Java Library)**: ML framework
- **MCA Reborn**: Villager system integration
- **Forge**: Modding framework

---

## 📝 Changelog

See [CHANGELOG.md](CHANGELOG.md) for version history.
