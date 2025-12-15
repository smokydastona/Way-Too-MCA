# Changelog

All notable changes to MCA AI Enhanced will be documented in this file.

## [1.1.17] - 2025-12-15

### Added
- **AI Tier Progression System** (Inspired by Hostile Neural Networks)
  - 5-tier intelligence system: UNTRAINED → LEARNING → TRAINED → EXPERT → MASTER
  - Mobs gain combat experience and level up their AI over time
  - Experience persists across mob deaths (global per-mob-type tracking)
  - Accuracy-based decision making (30% → 95% tactical success rate)
  - Dynamic think intervals (smarter mobs react faster)
  - Visual tier indicators with colored particles (green/blue/purple)
  - Tier-up celebration effects with enchantment glints
  - Federation sync for tier data (servers share AI progression globally)
  - Configuration options: `enableTierProgression`, `visualTierIndicators`, `experienceRateMultiplier`

- **Variant Family Grouping System**
  - 12 mob families for cross-learning (zombie family, skeleton family, etc.)
  - Variant mobs share 50% experience with family members
  - Drowned learns from zombies, stray learns from skeletons, etc.

- **Mob-Specific Think Costs**
  - Base intervals per mob type (zombies: 15t, skeletons: 20t, bosses: 30t)
  - Tier-based speed modifiers (MASTER mobs think 40% faster)
  - Performance optimized for complex mob AI

- **Cloudflare Worker Tier Endpoints**
  - `/api/tiers` POST/GET for tier data upload/download
  - FederationCoordinator stores global tier progression in Durable Object
  - Merge strategy: keeps maximum experience per mob type

### Changed
- AI decision making now includes accuracy checks (lower tiers make tactical mistakes)
- Think intervals now dynamic based on mob type and intelligence tier
- Experience system tracks total combat outcomes per mob type globally

### Technical
- New `AITier` enum with 5 progression levels and tier metadata
- `TierVisualIndicators` utility class for particle effects
- Tier data sync methods in `FederatedLearning` and `CloudflareAPIClient`
- Config loading enhanced to read tier progression settings
- All systems maintain full backwards compatibility

## [1.1.13] - 2025-12-14

### Fixed
- **Critical**: Mob freezing during federation uploads (blocking HTTP on server thread)
  - All uploads now use `CompletableFuture.runAsync()` fire-and-forget pattern
  - Dedicated HTTP executor ensures complete isolation from server thread
  - Data copying prevents concurrent modification during async upload

## [1.1.8] - 2025-12-14

### Added
- **Federated Learning v3.0.0**: Complete rewrite with Durable Objects
  - Decisive round finality (10 min OR 10 contributors)
  - Forced startup pull ensures bootstrap traffic
  - GitHub flight recorder for debugging
  - Proof-of-life `/status` endpoint
  - Single source of truth coordinator

### Changed
- **Client behavior**: Now uses v3 API endpoints (`/api/global`, `/api/upload`)
- **Round closure**: Hard limits prevent indefinite rounds
- **Startup flow**: Guaranteed download on server start
- **Bootstrap uploads**: First encounter triggers immediate upload

### Fixed
- No guaranteed bootstrap traffic (forced startup pull added)
- Rounds never closing (time/contributor limits enforced)
- No federation visibility (enhanced status endpoint)
- Rate limit risk (logging once per round)

## [1.1.7] - 2025-12-14

### Added
- GitHub logging system for federation debugging
- GitHubLogger utility (async, non-blocking, safe)
- Round finality rules in FederationCoordinator
- Enhanced status endpoint with proof-of-life data

### Fixed
- Federation rounds staying open indefinitely
- Lack of visibility into federation status

## [1.1.6] - 2025-12-12

### Added
- Per-mob learning statistics in `/amai stats` command
- Detailed breakdown of tactics by mob type

### Changed
- Enhanced stats display with mob-specific data

## [1.1.5] - 2025-12-10

### Added
- Forced bootstrap uploads on first mob encounter
- Initial upload system to establish federation connection
- Heartbeat sync mechanism

### Fixed
- Servers not uploading data (bootstrap issue)
- Federation connection not establishing reliably

## [1.1.4] - 2025-12-09

### Changed
- Restored single JAR build (all dependencies bundled)
- Simplified deployment (one file instead of two)

### Fixed
- Dependency management issues with split JARs

## [1.1.3] - 2025-12-08

### Added
- Auto-increment version system for local builds
- Build automation improvements

## [1.1.2] - 2025-12-07

### Added
- Enhanced logging for federation operations
- Better error messages

## [1.1.0] - 2025-12-05

### Added
- **Federated Learning System v2.0**
  - Global model aggregation via Cloudflare Workers
  - Real-time tactic sharing across all servers
  - FedAvg algorithm for model updates
  - Automatic sync every 10 minutes

### Changed
- Moved from local-only learning to distributed system
- Cloudflare Worker backend for coordination
- Enhanced mob AI with global tactics

## [1.0.0] - 2025-12-01

### Added
- **Initial Release**
- Deep Q-Network for mob learning
- Rule-based + learning hybrid AI
- Support for 8 mob types:
  - Zombie, Skeleton, Creeper, Spider
  - Husk, Stray, Wither Skeleton, Enderman
- MCA Reborn integration (optional)
  - Villager personality system
  - Mood-based dialogue
  - 40+ dialogue templates
- Configuration system
  - Per-mob toggles
  - AI difficulty scaling (0.5 - 3.0)
  - Federation enable/disable
- Commands:
  - `/mcaai info` - Mod status
  - `/mcaai stats` - AI statistics
  - `/mcaai federation` - Federation status
  - `/mcaai test dialogue` - Dialogue testing

---

## Version Format

Format: `[MAJOR.MINOR.PATCH]`

- **MAJOR**: Breaking changes, architecture rewrites
- **MINOR**: New features, API changes
- **PATCH**: Bug fixes, minor improvements

## Links

- **Repository**: https://github.com/smokydastona/Adaptive-Minecraft-Mob-Ai
- **Issues**: https://github.com/smokydastona/Adaptive-Minecraft-Mob-Ai/issues
- **Worker Status**: https://mca-ai-tactics-api.mc-ai-datcol.workers.dev/status
- **Federation Logs**: https://github.com/smokydastona/adaptive-ai-federation-logs
