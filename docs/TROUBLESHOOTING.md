# Troubleshooting Guide

## Common Installation Issues

### 1. Game Crashes at "Compatibility level set to JAVA_17"

**Symptom**: Game hangs during Forge initialization, log shows:
```
[main/INFO]: Compatibility level set to JAVA_17
```
Then freezes with no crash report.

**Cause**: Static `ModList.get().isLoaded()` calls during class initialization (before Forge ready).

**Fix**: **RESOLVED in v1.1.80+**
- Upgraded to lazy-loaded ModList detection
- If you're on v1.1.79 or earlier, update to v1.1.80+

**Manual workaround** (if stuck on old version):
1. Remove `Adaptive-Mob-Ai-*.jar` from mods folder
2. Download v1.1.80+ from releases
3. Place in mods folder and restart

---

### 2. Missing DJL/PyTorch Libraries

**Symptom**: Console shows:
```
java.lang.NoClassDefFoundError: ai/djl/ndarray/NDArray
```

**Cause**: Deep Java Library (DJL) not bundled in JAR.

**Fix**: **RESOLVED in v1.0.50+**
- DJL dependencies now bundled via Shadow plugin
- Mod is self-contained, no external libraries needed

**If still occurring**:
1. Check you're using `-all.jar` version, not plain `.jar`
   - ✅ `Adaptive-Mob-Ai-1.1.80-all.jar`
   - ❌ `Adaptive-Mob-Ai-1.1.80.jar`
2. Delete plain `.jar` if both exist in mods folder

---

### 3. OutOfMemoryError: Java heap space

**Symptom**:
```
java.lang.OutOfMemoryError: Java heap space
    at com.minecraft.gancity.ai.MobBehaviorAI...
```

**Causes**:
- Insufficient JVM memory allocation
- Replay buffer unbounded growth (old bug, fixed)
- Too many mobs with ML enabled

**Fixes**:

**A. Increase JVM Memory** (recommended):
Edit your launcher profile:
```
-Xmx4G -Xms4G
```
Change `4G` to `6G` or `8G` if you have RAM available.

**B. Reduce Mob AI Load** (if limited RAM):
Edit `config/adaptivemobai-common.toml`:
```toml
[general]
enableMobAI = true
maxActiveMobs = 50  # Reduce from default 100
```

**C. Disable specific mob types**:
```toml
[mob_types]
zombieAI = true
skeletonAI = true
creeperAI = false  # Disable creeper AI
spiderAI = false   # Disable spider AI
```

**D. Verify Object Pooling** (dev only):
Check `PerformanceOptimizer.java` has:
```java
private static final int MAX_REPLAY_SIZE = 10000;  // Fixed size
```

---

### 4. Federated Learning API Timeouts

**Symptom**:
```
[FederatedLearning] Failed to sync with API: timeout
```

**Causes**:
- Cloudflare Worker overloaded
- Network connectivity issues
- Rate limiting (>100 requests/min)

**Fixes**:

**A. Graceful Degradation** (automatic):
Mod continues working with local-only learning. No action needed.

**B. Check API Status**:
```
/mcaai federation status
```
If shows "❌ Offline", API is down. Wait for fix.

**C. Manual Retry**:
```
/mcaai federation sync
```

**D. Disable Federation** (if persistent issues):
Edit `config/adaptivemobai-common.toml`:
```toml
[federation]
enabled = false
```

---

### 5. Mod Compatibility Issues

#### Ice and Fire: Dragons
**Symptom**: Dragons behave strangely or crash when attacked.

**Fix**: **HANDLED AUTOMATICALLY**
- Dragons are skipped by tier assignment system
- Check detection working:
  ```
  /mcaai info
  ```
  Should show: `Ice and Fire detected - skipping dragons`

#### PMMO (Project MMO)
**Symptom**: Mobs become too strong (stacking modifiers).

**Fix**: **HANDLED AUTOMATICALLY**
- Stat modifiers reduced 50% when PMMO detected
- Verify:
  ```
  /mcaai info
  ```
  Should show: `PMMO detected - reducing stat modifiers`

#### MCA Reborn
**Symptom**: `NoClassDefFoundError: mca.entity.VillagerEntityMCA`

**Cause**: MCA Reborn not installed (soft dependency).

**Fix**:
1. Download MCA Reborn from CurseForge
2. Place in mods folder
3. Restart game

**Note**: Mod works WITHOUT MCA, just disables dialogue AI features.

---

### 6. Performance Lag with Many Mobs

**Symptom**: TPS drops below 18 with 100+ hostile mobs.

**Causes**:
- Background training thread overwhelmed
- Cache not working efficiently
- Too many active combat episodes

**Fixes**:

**A. Verify Cache Working**:
```
/mcaai debug performance
```
Expected output:
```
Cache hit rate: >75%
Training overhead: <2% TPS
```

If cache hit rate <50%, report bug.

**B. Reduce Training Frequency**:
Edit `PerformanceOptimizer.java` (requires recompile):
```java
private static final int TRAINING_INTERVAL_TICKS = 40;  // From 20 to 40
```
Reduces training from 1/sec to 1/2sec.

**C. Disable ML for Specific Biomes** (config):
```toml
[performance]
disableAIInBiomes = ["minecraft:mushroom_fields", "minecraft:deep_dark"]
```

**D. Emergency Fallback**:
Disable ML entirely, use rule-based only:
```toml
[general]
enableMobAI = false
enableRuleBasedFallback = true
```

---

### 7. Models Not Saving Between Sessions

**Symptom**: Mobs "forget" learned tactics after server restart.

**Causes**:
- World save folder not writable
- JSON serialization error
- Corrupt model file

**Fixes**:

**A. Check Save Location**:
Models saved to:
```
world/data/adaptivemobai/
  ├── tactics_zombie.json
  ├── tactics_skeleton.json
  └── global_knowledge.json
```

Verify folder exists and is writable.

**B. Permissions** (Linux servers):
```bash
chmod -R 755 world/data/adaptivemobai/
```

**C. Validate JSON** (if suspect corruption):
```
/mcaai debug validate-saves
```
Checks all tactic files for corruption.

**D. Manual Backup**:
Copy `world/data/adaptivemobai/` to safe location before updates.

---

### 8. Debug Commands Not Working

**Symptom**: `/mcaai` command not recognized.

**Causes**:
- Insufficient permissions
- Mod not loaded
- Command registration failed

**Fixes**:

**A. Check Permissions**:
Requires OP level 2:
```
/op <player>
```

**B. Verify Mod Loaded**:
```
/mods
```
Search for "MCA AI Enhanced" or "gancity" in list.

**C. Check Logs**:
```
logs/latest.log
```
Search for:
```
[MCA AI Enhanced] Commands registered
```

If missing, report bug with full log.

---

## Development Issues

### 1. Build Fails: "Cannot resolve symbol ai.djl"

**Cause**: Gradle didn't download DJL dependencies.

**Fix**:
```bash
.\gradlew clean build --refresh-dependencies
```

### 2. Mixin Not Applied

**Symptom**: Custom AI not working, vanilla behavior only.

**Cause**: Mixin config not loaded.

**Fixes**:

**A. Verify `mods.toml`**:
```toml
[[mixins]]
config = "adaptivemobai.mixins.json"
```

**B. Check `adaptivemobai.mixins.json`**:
```json
{
  "package": "com.minecraft.gancity.mixin",
  "server": [
    "MobAIEnhancementMixin"
  ]
}
```

**C. Enable Mixin Debug**:
Add to JVM args:
```
-Dmixin.debug.verbose=true
```

### 3. Git Push Rejected: "fetch first"

**Cause**: Remote has commits you don't have locally.

**Fix** (from copilot-instructions workflow):
```bash
git pull origin main --rebase
# Resolve any conflicts in gradle.properties
git add gradle.properties
git rebase --continue
git push origin main
```

---

## When to Report a Bug

Report issues on GitHub if:
1. ✅ You've tried troubleshooting steps above
2. ✅ You're using latest version (check releases)
3. ✅ You can reproduce the issue consistently
4. ✅ You've checked existing issues for duplicates

**Include in bug report**:
- Minecraft version
- Forge version
- Mod version (`/mcaai info`)
- Full crash report or latest.log
- List of other mods installed
- Steps to reproduce

---

## Performance Benchmarks

Expected performance with optimized config:

| Mob Count | TPS | CPU Usage | RAM Usage |
|-----------|-----|-----------|-----------|
| 50 mobs | 20.0 | 15-20% | 2.5 GB |
| 100 mobs | 19.8 | 18-22% | 3.0 GB |
| 200 mobs | 18.5 | 25-30% | 3.8 GB |
| 500 mobs | 17.0 | 40-45% | 5.2 GB |

If your performance is worse than above, check:
1. JVM args (use `-Xmx6G` minimum)
2. Other mods causing conflicts
3. Chunk loading lag (unrelated to AI mod)
4. Disk I/O bottleneck (SSD recommended)

---

## FAQ

**Q: Does this mod work on servers?**
A: Yes, server-side only. Clients don't need it installed.

**Q: Can I disable AI for specific mob types?**
A: Yes, edit `config/adaptivemobai-common.toml` → `[mob_types]` section.

**Q: Does federation share my player data?**
A: No. Only anonymized combat statistics (mob type, action, reward).

**Q: Can I use this with other AI mods?**
A: Depends. Test in creative first. Ice and Fire, MCA, PMMO are compatible.

**Q: How do I reset learned tactics?**
A: Delete `world/data/adaptivemobai/` folder while server stopped.

**Q: Does this work with Fabric?**
A: Not yet. Planned for v1.3.0.

**Q: Can mobs learn from creative mode testing?**
A: No. Learning disabled in creative/peaceful modes.
