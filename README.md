# Adaptive Mob AI

**Real machine learning for Minecraft mob behavior.**  
Mobs learn tactics and adapt in real-time using Double DQN reinforcement learning. Optional federated learning shares knowledge across all servers globally.

[![Minecraft Version](https://img.shields.io/badge/Minecraft-1.20.1-brightgreen.svg)](https://minecraft.net)
[![Forge Version](https://img.shields.io/badge/Forge-47.4.0+-orange.svg)](https://files.minecraftforge.net)
[![Java Version](https://img.shields.io/badge/Java-17-blue.svg)](https://openjdk.org)
[![Latest Release](https://img.shields.io/github/v/release/smokydastona/Adaptive-Minecraft-Mob-Ai)](https://github.com/smokydastona/Adaptive-Minecraft-Mob-Ai/releases)

---

## 🚀 Quick Start

1. **Download**: Get `Adaptive-Mob-Ai-1.1.174-all.jar` from [releases](https://github.com/smokydastona/Adaptive-Minecraft-Mob-Ai/releases)
2. **Install**: Drop in your server's `mods/` folder (server-side only, no client required)
3. **Play**: ML starts learning immediately, federation syncs automatically

That's it. Mobs adapt to player tactics and optionally share knowledge globally.

---

## 🎯 Core Features

### 🧠 Machine Learning AI
- **Double DQN**: Reinforcement learning with separate policy/target networks  
- **Real-time adaptation**: Mobs adjust tactics based on combat outcomes  
- **70+ mob types supported**: All hostile mobs (zombies, skeletons, creepers, endermen, etc.)  
- **Tier system**: ELITE, VETERAN, and ROOKIE mobs with different skill levels  
- **Performance optimized**: <2% TPS overhead with 100+ mobs (background training, 80% cache hit rate)

### 🌍 Optional Federated Learning
- **Global knowledge sharing**: All servers contribute to shared tactics database  
- **Cloudflare Worker backend**: Zero-config, auto-sync every 5-10 minutes  
- **Privacy-safe**: Only aggregated tactics (no player data)  
- **Graceful offline mode**: Works without federation if network unavailable  
- **Live status**: https://mca-ai-tactics-api.mc-ai-datcol.workers.dev/status

### 🎯 Advanced Tactics
- **10 combat actions**: Circle strafe, tactical retreat, aggressive rush, ambush, etc.  
- **Environmental awareness**: Biome temperature, light level, nearby entities  
- **Equipment detection**: Adapts to player armor/weapons via Curios API  
- **Curriculum learning**: Progressive difficulty increase as mobs improve

### 🔧 Server Admin Tools
```
/mcaai info              # System status and active features
/mcaai stats <mob>       # Learning progress per mob type
/mcaai federation        # Global sync status and contribution stats
/mcaai debug qvalues     # Q-value visualization (dev)
/mcaai debug training    # Training metrics (dev)
```

### 🔌 Mod Compatibility
- ✅ **Ice and Fire**: Skips dragons (have custom AI)  
- ✅ **MCA Reborn**: Optional dialogue system (soft dependency)  
- ✅ **PMMO**: Reduces stat modifiers to avoid conflicts  
- ✅ **Curios API**: Equipment-aware tactics  
- ✅ **FTB Teams**: Team-based coordination

---

## 📦 Installation

### Requirements
- **Minecraft 1.20.1**  
- **Forge 47.4.0+** (or compatible)  
- **Java 17**  
- **4-6GB RAM recommended** (for ML training)

### Steps
1. Download latest release `-all.jar` file  
2. Place in server `mods/` folder  
3. Start server (DJL/PyTorch libraries bundled)  
4. Configure `config/adaptivemobai-common.toml` (optional)

**Note**: Server-side only. Clients don't need the mod installed.

---

## ⚙️ Configuration

Edit `config/adaptivemobai-common.toml`:

```toml
[general]
enableMobAI = true
aiDifficulty = 1.0  # 0.5 (easy) to 3.0 (very hard)

[federation]
enabled = true
cloudApiEndpoint = "https://mca-ai-tactics-api.mc-ai-datcol.workers.dev"

[mob_types]
zombieAI = true
skeletonAI = true
creeperAI = true
# ... per-mob toggles

[performance]
maxActiveMobs = 100  # Reduce if server struggles
cacheLifetimeTicks = 10  # AI decision cache duration
```

---

## 📊 Performance

**Benchmarks** (measured on dedicated server, i7-9700K):

| Mob Count | TPS | CPU | RAM | Decision Latency |
|-----------|-----|-----|-----|------------------|
| 50 mobs | 20.0 | 18% | 2.5 GB | <1ms (cached) |
| 100 mobs | 19.8 | 19% | 3.0 GB | 2.4ms (ML) |
| 200 mobs | 18.5 | 28% | 3.8 GB | 2.6ms |
| 500 mobs | 17.0 | 42% | 5.2 GB | 3.1ms |

**Optimizations**:
- Background training (never blocks game thread)  
- 80% cache hit rate (10-tick TTL)  
- Shared global model (one per server, not per mob)  
- Object pooling (1000 pre-allocated experiences)  
- Fixed replay buffer (10K max, ring buffer)

See [docs/PERFORMANCE_AND_SAFEGUARDS.md](docs/PERFORMANCE_AND_SAFEGUARDS.md) for details.

---

## 📚 Documentation

| Guide | Description |
|-------|-------------|
| [AI_MOD_README.md](docs/AI_MOD_README.md) | Complete feature reference |
| [INSTALLATION.md](docs/INSTALLATION.md) | Detailed setup guide |
| [TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) | Common issues and fixes |
| [PERFORMANCE_AND_SAFEGUARDS.md](docs/PERFORMANCE_AND_SAFEGUARDS.md) | Performance benchmarks, safety mechanisms |
| [FEDERATED_LEARNING.md](docs/FEDERATED_LEARNING.md) | How global learning works |
| [SETUP_FEDERATED_LEARNING.md](docs/SETUP_FEDERATED_LEARNING.md) | Deploy your own Cloudflare Worker |
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | Code structure and design |
| [MOD_COMPATIBILITY.md](docs/MOD_COMPATIBILITY.md) | Other mod integrations |
| [SUPPORTED_MOBS.md](docs/SUPPORTED_MOBS.md) | Full mob type list |

---

## 🔧 Development

### Building from Source
```bash
git clone https://github.com/smokydastona/Adaptive-Minecraft-Mob-Ai.git
cd Adaptive-Minecraft-Mob-Ai
.\gradlew build
# Output: build/libs/Adaptive-Mob-Ai-X.X.X-all.jar
```

### Running Dev Environment
```bash
.\gradlew runClient  # Test client
.\gradlew runServer  # Test server
```

### Contributing
See [.github/copilot-instructions.md](.github/copilot-instructions.md) for architecture and workflow.

---

## 🌍 Federation Status

**Live API**: https://mca-ai-tactics-api.mc-ai-datcol.workers.dev/status  
**GitHub Logs**: https://github.com/smokydastona/adaptive-ai-federation-logs  
**In-game**: `/mcaai federation`

---

## ⚠️ Known Issues

- **v1.1.79 and earlier**: Classloading deadlock at startup (✅ fixed in v1.1.80)  
- **Pre-1.1.148**: Some launchers/modpacks could exit during early Mixin init (“silent crash”). Update to v1.1.148+.
- **Pre-1.1.174**: Some modpacks could log mixin refmap/injection issues that prevented AI goal injection. Update to v1.1.174+.
- **Fabric support**: Not yet available (planned v1.3.0)

See [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) for solutions.

---

## 📜 License

MIT License - See [LICENSE](LICENSE)

---

## 🙏 Credits

- **Machine Learning**: Deep Java Library (DJL) + PyTorch 0.25.0  
- **MCA Integration**: MCA Reborn by WildBamaBoy/Frydae  
- **Federated Backend**: Cloudflare Workers  
- **Development**: smokydastona

---

## 🔗 Links

- [GitHub Issues](https://github.com/smokydastona/Adaptive-Minecraft-Mob-Ai/issues)  
- [Releases](https://github.com/smokydastona/Adaptive-Minecraft-Mob-Ai/releases)  
- [MCA Reborn](https://www.curseforge.com/minecraft/mc-mods/minecraft-comes-alive-reborn)
