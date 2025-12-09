# MCA AI Enhanced - Copilot Instructions

## Workflow After Every Code Change

**After ANY code change, you MUST follow this complete workflow:**

1. **Scan all files first** - Run error checking across the entire codebase
2. **Fix all errors systematically** - Address every error found, not just one file
3. **Re-validate after each fix** - Ensure no new errors were introduced
4. **Explain every change** - What was wrong, what you changed, and why
5. **Push to GitHub Actions** - Commit and push ONLY (no tagging)
6. **Only stop when 100% validated** - Continue until all files are completely correct and compile without errors

## Compilation Workflow

**NEVER build locally.** Always use GitHub Actions:

```bash
git add -A
git commit -m "descriptive message"
git push
```

**STOP HERE.** Do NOT run `git tag` - the user will create tags manually when ready.

This triggers GitHub Actions to compile the mod. The workspace must stay clean - no `build/` or `.gradle/` directories.

## Project Overview
This is a **Forge 1.20.1 mod** that uses **real machine learning** (Deep Q-Network) to enhance Minecraft mob AI. The mod works standalone - MCA Reborn integration is optional and detected at runtime.

**Dual Codebase Structure:**
- **Legacy (inactive)**: Python GAN training scripts (`*.py` files, `data/`) - original city generation concept
- **Active**: Forge mod in `src/main/java/com/minecraft/gancity/` - AI behavior enhancement mod

## Architecture & Key Components

### Core Systems (src/main/java/com/minecraft/gancity/)

1. **AI Behavior Layer** (`ai/`)
   - `MobBehaviorAI.java`: Rule-based + learning system for mob combat tactics
   - `VillagerDialogueAI.java`: Template-based dialogue with personality/mood tracking
   - Both support optional ML model loading from `models/` directory (fallback to rule-based)

2. **Mixin Integration** (`mixin/`)
   - `MobAIEnhancementMixin.java`: Injects custom AI goals into vanilla `Mob.registerGoals()`
   - Pattern: Inner class `AIEnhancedMeleeGoal` replaces default melee behavior
   - Registered in `gancity.mixins.json` under `"server"` array

3. **MCA Integration** (`mca/`)
   - `MCAIntegration.java`: Reflection-based soft dependency on MCA Reborn mod
   - Uses `ModList.get().isLoaded("mca")` for runtime detection
   - Spawns villagers and assigns homes via reflection to avoid hard dep

4. **Entry Point**
   - `GANCityMod.java`: Main mod class, initializes AI systems in `commonSetup()`
   - Static accessors: `getMobBehaviorAI()`, `getVillagerDialogueAI()`

### Critical Patterns

**AI Decision Flow:**
```java
// 1. Build state from game context
MobBehaviorAI.MobState state = new MobBehaviorAI.MobState(health, targetHealth, distance);
// 2. AI selects action using weighted probability + learning
String action = behaviorAI.selectMobAction("zombie", state);
// 3. Execute in mixin's AIEnhancedMeleeGoal.executeAction()
```

**Personality Evolution:**
```java
// Villagers track success/failure of dialogue choices
dialogueAI.learnFromInteraction(villagerId, dialogue, positiveOutcome);
// Traits adjust over time based on interaction types
personality.recordInteraction(context, playerResponse);
```

**Soft Dependency Pattern:**
```java
// Check mod presence
boolean mcaLoaded = ModList.get().isLoaded("mca");
MCAIntegration.setMCALoaded(mcaLoaded);
// Use reflection for optional features
Class<?> villagerClass = Class.forName("mca.entity.VillagerEntityMCA");
```

## Build & Development

### Commands (PowerShell)
```powershell
# Build mod JAR
.\gradlew build           # Output: build/libs/mca-ai-enhanced-1.0.0.jar

# Run development client
.\gradlew runClient       # Launches MC 1.20.1 with mod loaded

# Run development server
.\gradlew runServer

# Clean build artifacts
.\gradlew clean

# Generate IDE project files
.\gradlew eclipse         # For Eclipse
.\gradlew idea            # For IntelliJ (auto-detected usually)
```

### Dependencies (build.gradle)
- **Forge 1.20.1-47.2.0**: Minecraft modding framework
- **MCA Reborn** (soft dep): From CurseMaven, optional at runtime
- **Deep Java Library (DJL)**: PyTorch engine for ML inference
  - `ai.djl:api`, `ai.djl.pytorch:pytorch-engine`, `ai.djl.huggingface:tokenizers`
  - Models loaded from `models/` directory if present, graceful fallback

### Configuration
- Config file: `src/main/resources/mca-ai-enhanced-common.toml`
- Runtime location: `config/mca-ai-enhanced-common.toml` in MC instance
- Settings: `enableMobAI`, `aiDifficulty`, mob-specific toggles, dialogue variations

## Code Conventions

### Package Structure
```
com.minecraft.gancity/
├── ai/          # ML/AI logic (model-agnostic)
├── command/     # Brigadier commands (/mcaai)
├── mca/         # MCA Reborn integration (reflection-based)
├── mixin/       # SpongePowered mixins for vanilla injection
├── ml/          # Legacy GAN code (deprecated, kept for reference)
└── worldgen/    # Legacy structure building (deprecated)
```

### Naming Patterns
- AI systems: `*AI.java` (MobBehaviorAI, VillagerDialogueAI)
- Mixins: `*Mixin.java` with `@Mixin(TargetClass.class)` annotation
- Commands: `*Command.java` with static `register(CommandDispatcher)` method
- Inner classes: Descriptive names (e.g., `AIEnhancedMeleeGoal`, `MobBehaviorProfile`)

### Logging
```java
private static final Logger LOGGER = LogUtils.getLogger();
LOGGER.info("MCA AI Enhanced - System initialized");
LOGGER.warn("MCA Reborn not found. Dialogue features disabled.");
```

## Testing & Debugging

### In-Game Commands (requires OP level 2)
```
/mcaai info                      # Show mod status, MCA detection
/mcaai stats                     # View AI statistics, active features
/mcaai test dialogue <type>      # Test dialogue generation
  Types: greeting, small_talk, gift_positive, flirt, request_help
```

### Debug Workflow
1. Run `.\gradlew runClient` - launches dev environment
2. Enable debug logging in `run/logs/latest.log`
3. Search for "MCA AI Enhanced" log entries
4. Test mob AI: Spawn zombies/skeletons, observe tactics (circle strafe, retreat, etc.)
5. Test dialogue: Talk to MCA villagers, check personality/mood evolution

## Integration Points

### MCA Reborn Communication
- **No direct API dependency** - uses reflection exclusively
- Detection: `ModList.get().isLoaded("mca")` in `GANCityMod.commonSetup()`
- Villager spawning: Reflect `VillagerEntityMCA` class, call `setHome(BlockPos)`
- Dialogue hooks: Extend MCA's interaction system via events (future enhancement)

### Minecraft/Forge Hooks
- **Mixin injection**: `@Inject` at `Mob.registerGoals()` tail to add AI goals
- **Event subscriptions**: `@SubscribeEvent` for commands, server lifecycle
- **Mod lifecycle**: `FMLCommonSetupEvent` for initialization, `ServerStartingEvent` for runtime

### External ML Models (Optional)
- **Location**: `models/mob_behavior/` and `models/villager_dialogue/`
- **Format**: PyTorch SavedModel (DJL compatible)
- **Fallback**: Rule-based systems if models not found
- **Training**: External Python scripts (not integrated with mod build)

## Common Tasks

### Adding a New Mob Behavior
1. Add profile in `MobBehaviorAI.initializeDefaultProfiles()`:
   ```java
   behaviorProfiles.put("enderman", new MobBehaviorProfile(
       "enderman", Arrays.asList("teleport_strike", "block_grab"), 0.6f
   ));
   ```
2. Implement action in `MobAIEnhancementMixin.AIEnhancedMeleeGoal.executeAction()`:
   ```java
   case "teleport_strike":
       if (random.nextFloat() < 0.3f) {
           teleportNearTarget();
       }
       break;
   ```

### Adding Dialogue Templates
1. Add to `VillagerDialogueAI.DIALOGUE_TEMPLATES` static map:
   ```java
   DIALOGUE_TEMPLATES.put("category_name", Arrays.asList(
       "Template with {player} variable",
       "Another template with {item}"
   ));
   ```
2. Templates support: `{player}`, `{biome}`, `{village}`, `{item}`, `{topic}`

### Modifying AI Difficulty
- Config: `mca-ai-enhanced-common.toml` → `aiDifficulty = 1.0`
- Code: Affects weight calculation in `MobBehaviorAI.calculateActionWeight()`
- Range: 0.5 (easy) to 3.0 (very hard)

## Documentation Files
- **AI_MOD_README.md**: Full user-facing documentation (features, commands, config)
- **FORGE_MOD_README.md**: Outdated city generation docs (deprecated)
- **README.md**: Original Python GAN project docs (legacy)
- **README_MCA_ADDON.md**: Intermediate addon version (superseded by AI_MOD_README.md)

## Important Notes
- **Mod ID**: `gancity` (historical, kept for compatibility)
- **Display name**: "MCA AI Enhanced"
- **Target version**: Minecraft 1.20.1, Forge 47.2.0+
- **Java version**: 17 (see `build.gradle` toolchain)
- **Mixin config**: `gancity.mixins.json` must be referenced in `mods.toml` (implicit via package)
- **MCA is optional**: Mod provides mob AI without MCA, dialogue features require it
