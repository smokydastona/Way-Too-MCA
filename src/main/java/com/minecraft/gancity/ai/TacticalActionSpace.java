package com.minecraft.gancity.ai;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.*;

/**
 * High-level tactical action space - abstracts away low-level Minecraft operations
 * Instead of "move left" or "swing", we have "strafe aggressively" or "rush when shield down"
 * 
 * This is what federation should aggregate - tactics, not button presses.
 */
public class TacticalActionSpace {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    /**
     * Tactical actions - each represents a complex behavior pattern
     */
    public enum TacticalAction {
        // Aggressive tactics
        RUSH_PLAYER("rush_player", "Close distance aggressively, ignore damage"),
        STRAFE_AGGRESSIVE("strafe_aggressive", "Circle strafe while maintaining pressure"),
        OVERHEAD_COMBO("overhead_combo", "Jump attacks followed by quick retreat"),
        PUNISH_SHIELD_DROP("punish_shield_drop", "Wait for shield to drop, then burst"),
        
        // Defensive tactics
        RETREAT_AND_HEAL("retreat_and_heal", "Fall back when low HP, find cover"),
        DODGE_WEAVE("dodge_weave", "Unpredictable movement to avoid hits"),
        COUNTER_ATTACK("counter_attack", "Block/dodge then immediately strike"),
        FEINT_RETREAT("feint_retreat", "Fake retreat to bait player forward"),
        
        // Cooperative tactics (require 2+ allies)
        COORDINATE_FLANK("coordinate_flank", "Allies attack from different angles simultaneously"),
        PINCER_ATTACK("pincer_attack", "Surround player with coordinated positioning"),
        DISTRACT_AND_STRIKE("distract_and_strike", "One ally distracts while others attack"),
        ROTATING_PRESSURE("rotating_pressure", "Take turns engaging to maintain constant pressure"),
        PROTECT_WEAK_ALLY("protect_weak_ally", "Shield low-health allies from player"),
        
        // Cross-species tactics (mixed mob coordination)
        RANGED_MELEE_COMBO("ranged_melee_combo", "Ranged mobs provide cover fire while melee advances"),
        SACRIFICE_PLAY("sacrifice_play", "Expendable mobs draw attention for assassins"),
        AMBUSH_SETUP("ambush_setup", "Fast mobs herd player toward heavy hitters"),
        LAYERED_DEFENSE("layered_defense", "Tanks front, ranged behind, casters supporting"),
        
        // Utility tactics
        USE_TERRAIN("use_terrain", "Leverage environmental obstacles"),
        WAIT_FOR_OPENING("wait_for_opening", "Patient, defensive positioning"),
        CALL_REINFORCEMENTS("call_reinforcements", "Group up with nearby mobs"),
        EXPLOIT_WEAKNESS("exploit_weakness", "Target player vulnerabilities"),
        
        // Default fallback
        DEFAULT_MELEE("default_melee", "Standard Minecraft melee behavior");
        
        public final String id;
        public final String description;
        
        TacticalAction(String id, String description) {
            this.id = id;
            this.description = description;
        }
        
        public static TacticalAction fromString(String id) {
            for (TacticalAction action : values()) {
                if (action.id.equals(id)) {
                    return action;
                }
            }
            return DEFAULT_MELEE;
        }
    }
    
    /**
     * Tactical state - high-level combat situation
     */
    public static class TacticalState {
        public final float healthRatio;          // 0-1
        public final float targetHealthRatio;    // 0-1
        public final float distanceToTarget;     // blocks
        public final boolean targetHasShield;
        public final boolean targetLowHealth;    // < 30%
        public final boolean selfLowHealth;      // < 30%
        public final int nearbyAllies;           // other mobs nearby (within 8 blocks)
        public final int alliesAttackingTarget;  // allies currently fighting same target
        public final boolean allyNeedsHelp;      // nearby ally at low health
        public final boolean targetInCooldown;   // shield/weapon on cooldown
        public final boolean hasTerrainCover;    // obstacles nearby
        public final boolean playerSurrounded;   // allies on multiple sides of player
        
        public TacticalState(float healthRatio, float targetHealthRatio, float distanceToTarget,
                           boolean targetHasShield, boolean targetLowHealth, boolean selfLowHealth,
                           int nearbyAllies, int alliesAttackingTarget, boolean allyNeedsHelp,
                           boolean targetInCooldown, boolean hasTerrainCover, boolean playerSurrounded) {
            this.healthRatio = healthRatio;
            this.targetHealthRatio = targetHealthRatio;
            this.distanceToTarget = distanceToTarget;
            this.targetHasShield = targetHasShield;
            this.targetLowHealth = targetLowHealth;
            this.selfLowHealth = selfLowHealth;
            this.nearbyAllies = nearbyAllies;
            this.alliesAttackingTarget = alliesAttackingTarget;
            this.allyNeedsHelp = allyNeedsHelp;
            this.targetInCooldown = targetInCooldown;
            this.hasTerrainCover = hasTerrainCover;
            this.playerSurrounded = playerSurrounded;
        }
        
        /**
         * Build tactical state from Minecraft game state
         */
        public static TacticalState fromGameState(Mob mob, Player target) {
            // NULL CHECK: Validate inputs
            if (mob == null || target == null || !mob.isAlive() || !target.isAlive()) {
                // Return safe default state
                return new TacticalState(1.0f, 1.0f, 10.0f, false, false, false, 0, 0, false, false, false, false);
            }
            
            float healthRatio = mob.getHealth() / mob.getMaxHealth();
            float targetHealthRatio = target.getHealth() / target.getMaxHealth();
            float distance = (float) mob.distanceTo(target);
            
            boolean targetHasShield = target.isBlocking();
            boolean targetLowHealth = targetHealthRatio < 0.3f;
            boolean selfLowHealth = healthRatio < 0.3f;
            
            // Get all nearby allies (8 block radius)
            List<Mob> allyList = mob.level().getEntitiesOfClass(
                Mob.class,
                mob.getBoundingBox().inflate(8.0),
                m -> m != mob && !m.isDeadOrDying() && m.getType() == mob.getType()
            );
            int nearbyAllies = allyList.size();
            
            // Count allies actively fighting the same target
            int alliesAttackingTarget = (int) allyList.stream()
                .filter(ally -> ally.getTarget() == target)
                .count();
            
            // Check if any ally needs help (low health)
            boolean allyNeedsHelp = allyList.stream()
                .anyMatch(ally -> ally.getHealth() / ally.getMaxHealth() < 0.3f);
            
            // Check if player is surrounded (allies on opposite sides)
            boolean playerSurrounded = false;
            if (nearbyAllies >= 2) {
                Vec3 mobToPlayer = target.position().subtract(mob.position()).normalize();
                playerSurrounded = allyList.stream()
                    .anyMatch(ally -> {
                        Vec3 allyToPlayer = target.position().subtract(ally.position()).normalize();
                        double angle = mobToPlayer.dot(allyToPlayer);
                        return angle < -0.5; // Roughly opposite sides (>120 degrees)
                    });
            }
            
            // Simple cooldown detection (if player hasn't attacked in 0.5s)
            boolean targetInCooldown = target.getAttackStrengthScale(0.5f) < 0.9f;
            
            // Check for terrain cover (blocks within 3 blocks)
            boolean hasTerrainCover = hasNearbyObstacles(mob);
            
            return new TacticalState(
                healthRatio, targetHealthRatio, distance,
                targetHasShield, targetLowHealth, selfLowHealth,
                nearbyAllies, alliesAttackingTarget, allyNeedsHelp,
                targetInCooldown, hasTerrainCover, playerSurrounded
            );
        }
        
        private static boolean hasNearbyObstacles(Mob mob) {
            // Quick check for solid blocks nearby (simplified)
            Vec3 pos = mob.position();
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    if (mob.level().getBlockState(mob.blockPosition().offset(dx, 0, dz)).isSolidRender(mob.level(), mob.blockPosition().offset(dx, 0, dz))) {
                        return true;
                    }
                }
            }
            return false;
        }
        
        /**
         * Convert to feature vector for ML (if needed)
         */
        public float[] toFeatureVector() {
            return new float[] {
                healthRatio,
                targetHealthRatio,
                Math.min(distanceToTarget / 20.0f, 1.0f),  // normalize to 0-1
                targetHasShield ? 1.0f : 0.0f,
                targetLowHealth ? 1.0f : 0.0f,
                selfLowHealth ? 1.0f : 0.0f,
                Math.min(nearbyAllies / 5.0f, 1.0f),  // normalize
                targetInCooldown ? 1.0f : 0.0f,
                hasTerrainCover ? 1.0f : 0.0f
            };
        }
    }
    
    /**
     * Execute a tactical action - translate high-level intent to Minecraft operations
     * This is where the "many vanilla operations per tactical action" happens
     */
    public static void executeTacticalAction(Mob mob, Player target, TacticalAction action) {
        switch (action) {
            case RUSH_PLAYER:
                // Set navigation directly to player, high speed
                mob.getNavigation().moveTo(target, 1.3);
                break;
                
            case STRAFE_AGGRESSIVE:
                // Circle movement around player
                Vec3 toPlayer = target.position().subtract(mob.position()).normalize();
                Vec3 perpendicular = new Vec3(-toPlayer.z, 0, toPlayer.x);
                Vec3 strafeTarget = target.position().add(perpendicular.scale(3));
                mob.getNavigation().moveTo(strafeTarget.x, strafeTarget.y, strafeTarget.z, 1.1);
                break;
                
            case OVERHEAD_COMBO:
                // Jump and attack (mob will naturally swing when in range)
                if (mob.onGround() && mob.distanceTo(target) < 4) {
                    mob.setDeltaMovement(mob.getDeltaMovement().add(0, 0.4, 0));
                }
                mob.getNavigation().moveTo(target, 1.2);
                break;
                
            case PUNISH_SHIELD_DROP:
                if (target.isBlocking()) {
                    // Wait just outside range
                    if (mob.distanceTo(target) < 3) {
                        Vec3 awayFromPlayer = mob.position().subtract(target.position()).normalize();
                        Vec3 retreatPos = mob.position().add(awayFromPlayer.scale(2));
                        mob.getNavigation().moveTo(retreatPos.x, retreatPos.y, retreatPos.z, 1.0);
                    }
                } else {
                    // Rush in fast
                    mob.getNavigation().moveTo(target, 1.5);
                }
                break;
                
            case RETREAT_AND_HEAL:
                // Move away from player
                Vec3 awayFromPlayer = mob.position().subtract(target.position()).normalize();
                Vec3 retreatPos = mob.position().add(awayFromPlayer.scale(5));
                mob.getNavigation().moveTo(retreatPos.x, retreatPos.y, retreatPos.z, 1.2);
                break;
                
            case DODGE_WEAVE:
                // Random zigzag movement
                Random rand = new Random();
                double angle = rand.nextDouble() * Math.PI * 2;
                Vec3 randomOffset = new Vec3(Math.cos(angle) * 3, 0, Math.sin(angle) * 3);
                Vec3 dodgeTarget = mob.position().add(randomOffset);
                mob.getNavigation().moveTo(dodgeTarget.x, dodgeTarget.y, dodgeTarget.z, 1.3);
                break;
                
            case COUNTER_ATTACK:
                // Wait for player to swing, then close in
                if (target.getAttackStrengthScale(0.5f) < 0.5f) {
                    mob.getNavigation().moveTo(target, 1.4);
                } else {
                    mob.getNavigation().stop();
                }
                break;
                
            case FEINT_RETREAT:
                // Quick back-and-forth to confuse
                if (mob.tickCount % 40 < 20) {
                    Vec3 away = mob.position().subtract(target.position()).normalize();
                    Vec3 retreatTarget = mob.position().add(away.scale(4));
                    mob.getNavigation().moveTo(retreatTarget.x, retreatTarget.y, retreatTarget.z, 1.3);
                } else {
                    mob.getNavigation().moveTo(target, 1.3);
                }
                break;
                
            case USE_TERRAIN:
                // Pathfind using obstacles (Minecraft AI handles this naturally)
                mob.getNavigation().moveTo(target, 1.1);
                break;
                
            case WAIT_FOR_OPENING:
                // Stay at medium range, observe
                float dist = mob.distanceTo(target);
                if (dist < 4) {
                    Vec3 away = mob.position().subtract(target.position()).normalize();
                    Vec3 backoffTarget = mob.position().add(away.scale(2));
                    mob.getNavigation().moveTo(backoffTarget.x, backoffTarget.y, backoffTarget.z, 0.9);
                } else if (dist > 8) {
                    mob.getNavigation().moveTo(target, 0.9);
                }
                break;
                
            case CALL_REINFORCEMENTS:
                // Move toward nearest ally while maintaining distance from player
                List<Mob> nearbyMobs = mob.level().getEntitiesOfClass(
                    Mob.class,
                    mob.getBoundingBox().inflate(16.0),
                    m -> m != mob && !m.isDeadOrDying()
                );
                if (!nearbyMobs.isEmpty()) {
                    Mob nearest = nearbyMobs.get(0);
                    Vec3 toAlly = nearest.position().subtract(mob.position()).normalize();
                    Vec3 groupUpPos = mob.position().add(toAlly.scale(3));
                    mob.getNavigation().moveTo(groupUpPos.x, groupUpPos.y, groupUpPos.z, 1.1);
                }
                break;
                
            case EXPLOIT_WEAKNESS:
                // Aggressive when target is weak, otherwise cautious
                if (target.getHealth() < target.getMaxHealth() * 0.4f) {
                    mob.getNavigation().moveTo(target, 1.4);
                } else {
                    mob.getNavigation().moveTo(target, 1.0);
                }
                break;
            
            // ==================== COOPERATIVE TACTICS ====================
            
            case COORDINATE_FLANK:
                // Approach from angles relative to allies
                List<Mob> allies = getNearbyAllies(mob);
                if (!allies.isEmpty()) {
                    // Get ally positions relative to target
                    Vec3 targetPos = target.position();
                    Vec3 avgAllyAngle = Vec3.ZERO;
                    for (Mob ally : allies) {
                        Vec3 allyToTarget = targetPos.subtract(ally.position()).normalize();
                        avgAllyAngle = avgAllyAngle.add(allyToTarget);
                    }
                    avgAllyAngle = avgAllyAngle.normalize();
                    
                    // Approach from perpendicular angle to allies
                    Vec3 flankDirection = new Vec3(-avgAllyAngle.z, 0, avgAllyAngle.x);
                    Vec3 flankPos = targetPos.add(flankDirection.scale(3));
                    mob.getNavigation().moveTo(flankPos.x, flankPos.y, flankPos.z, 1.3);
                } else {
                    mob.getNavigation().moveTo(target, 1.2);
                }
                break;
                
            case PINCER_ATTACK:
                // Surround player with coordinated positioning
                List<Mob> pincerAllies = getNearbyAllies(mob);
                if (pincerAllies.size() >= 1) {
                    // Divide circle around player based on ally count
                    int totalMobs = pincerAllies.size() + 1;
                    int myIndex = 0; // This mob's position in formation
                    double angleStep = (Math.PI * 2) / totalMobs;
                    double myAngle = angleStep * myIndex;
                    
                    Vec3 targetPos = target.position();
                    Vec3 offset = new Vec3(Math.cos(myAngle) * 3, 0, Math.sin(myAngle) * 3);
                    Vec3 pincerPos = targetPos.add(offset);
                    mob.getNavigation().moveTo(pincerPos.x, pincerPos.y, pincerPos.z, 1.2);
                } else {
                    mob.getNavigation().moveTo(target, 1.2);
                }
                break;
                
            case DISTRACT_AND_STRIKE:
                // One distracts, others attack (alternate roles every 5 seconds)
                List<Mob> teamAllies = getNearbyAllies(mob);
                if (!teamAllies.isEmpty()) {
                    boolean isDistractor = (mob.tickCount / 100) % 2 == 0; // Swap roles every 5s
                    if (isDistractor) {
                        // Distract: Stay in front, dodge a lot
                        Vec3 frontPos = target.position().add(target.getLookAngle().scale(-3));
                        mob.getNavigation().moveTo(frontPos.x, frontPos.y, frontPos.z, 1.4);
                    } else {
                        // Attacker: Approach from sides/back
                        Vec3 behindPlayer = target.position().subtract(target.getLookAngle().scale(2));
                        mob.getNavigation().moveTo(behindPlayer.x, behindPlayer.y, behindPlayer.z, 1.3);
                    }
                } else {
                    mob.getNavigation().moveTo(target, 1.2);
                }
                break;
                
            case ROTATING_PRESSURE:
                // Take turns engaging to maintain constant pressure
                List<Mob> rotatingAllies = getNearbyAllies(mob);
                if (!rotatingAllies.isEmpty()) {
                    // Each mob attacks for 3 seconds, then retreats for 2 seconds
                    int cycleTime = 5 * 20; // 5 seconds in ticks
                    int myTurn = mob.getId() % rotatingAllies.size();
                    int currentPhase = (mob.tickCount / cycleTime) % rotatingAllies.size();
                    
                    if (myTurn == currentPhase) {
                        // My turn - aggressive
                        mob.getNavigation().moveTo(target, 1.5);
                    } else {
                        // Not my turn - maintain distance
                        float rotatingDist = mob.distanceTo(target);
                        if (rotatingDist < 6) {
                            Vec3 away = mob.position().subtract(target.position()).normalize();
                            Vec3 waitPos = mob.position().add(away.scale(2));
                            mob.getNavigation().moveTo(waitPos.x, waitPos.y, waitPos.z, 0.9);
                        }
                    }
                } else {
                    mob.getNavigation().moveTo(target, 1.2);
                }
                break;
                
            case PROTECT_WEAK_ALLY:
                // Shield low-health allies from player
                List<Mob> protectAllies = getNearbyAllies(mob);
                Mob weakAlly = null;
                for (Mob ally : protectAllies) {
                    if (ally.getHealth() / ally.getMaxHealth() < 0.3f) {
                        weakAlly = ally;
                        break;
                    }
                }
                
                if (weakAlly != null) {
                    // Position between player and weak ally
                    Vec3 playerToWeakAlly = weakAlly.position().subtract(target.position()).normalize();
                    Vec3 shieldPos = target.position().add(playerToWeakAlly.scale(2));
                    mob.getNavigation().moveTo(shieldPos.x, shieldPos.y, shieldPos.z, 1.3);
                } else {
                    mob.getNavigation().moveTo(target, 1.1);
                }
                break;
                
            // CROSS-SPECIES TACTICS
            case RANGED_MELEE_COMBO:
                // Melee mobs advance aggressively if ranged allies are providing cover fire
                // Ranged mobs maintain distance and shoot while melee allies engage
                List<Mob> comboAllies = getNearbyAllies(mob);
                boolean hasRangedAlly = comboAllies.stream().anyMatch(m -> isRangedMob(m));
                boolean hasMeleeAlly = comboAllies.stream().anyMatch(m -> isMeleeMob(m));
                
                if (isRangedMob(mob)) {
                    // I'm ranged - keep distance, provide suppressing fire
                    float comboDist = mob.distanceTo(target);
                    if (comboDist < 8 && hasMeleeAlly) {
                        // Allies engaged, I retreat to optimal range
                        Vec3 back = mob.position().subtract(target.position()).normalize();
                        Vec3 rangedRetreatPos = mob.position().add(back.scale(3));
                        mob.getNavigation().moveTo(rangedRetreatPos.x, rangedRetreatPos.y, rangedRetreatPos.z, 1.2);
                    } else if (comboDist > 12) {
                        // Too far, close to shooting range
                        mob.getNavigation().moveTo(target, 1.0);
                    }
                    // Else stay in position and shoot
                } else {
                    // I'm melee - advance aggressively under covering fire
                    if (hasRangedAlly) {
                        mob.getNavigation().moveTo(target, 1.5);  // Faster approach with cover
                    } else {
                        mob.getNavigation().moveTo(target, 1.2);  // No cover, be careful
                    }
                }
                break;
                
            case SACRIFICE_PLAY:
                // Expendable mobs (creepers, low-health) draw player attention for assassins
                List<Mob> sacrificeAllies = getNearbyAllies(mob);
                boolean hasAssassin = sacrificeAllies.stream().anyMatch(m -> 
                    isFastMob(m) || m.getHealth() > mob.getHealth() * 1.5f
                );
                
                if (hasAssassin) {
                    // I'm the distraction - be loud and obvious
                    mob.getNavigation().moveTo(target, 1.8);  // Aggressive rush
                    if (isExpendableMob(mob) && mob.distanceTo(target) < 3) {
                        // Prime explosion as distraction (vanilla behavior handles this)
                    }
                } else {
                    // No one to capitalize on sacrifice, fight normally
                    mob.getNavigation().moveTo(target, 1.0);
                }
                break;
                
            case AMBUSH_SETUP:
                // Fast mobs herd player toward slower heavy hitters
                List<Mob> ambushAllies = getNearbyAllies(mob);
                Mob heavyHitter = null;
                for (Mob ally : ambushAllies) {
                    if (isTankMob(ally) && 
                        ally.distanceTo(target) > 8) {
                        heavyHitter = ally;
                        break;
                    }
                }
                
                if (heavyHitter != null && isFastMob(mob)) {
                    // I'm fast - position to push player toward heavy ally
                    Vec3 playerToHeavy = heavyHitter.position().subtract(target.position()).normalize();
                    Vec3 herdPos = target.position().subtract(playerToHeavy.scale(3));  // Get on opposite side
                    mob.getNavigation().moveTo(herdPos.x, herdPos.y, herdPos.z, 1.4);
                } else {
                    mob.getNavigation().moveTo(target, 1.2);
                }
                break;
                
            case LAYERED_DEFENSE:
                // Form tactical layers: tanks front, ranged back, support middle
                List<Mob> layerAllies = getNearbyAllies(mob);
                boolean isTank = isTankMob(mob);
                boolean isRanged = isRangedMob(mob);
                boolean isSupport = isSupportMob(mob);
                
                float playerDist = mob.distanceTo(target);
                
                if (isTank) {
                    // Frontline - engage directly
                    if (playerDist > 3) {
                        mob.getNavigation().moveTo(target, 1.3);
                    } else {
                        // In melee range, hold position
                    }
                } else if (isRanged) {
                    // Backline - maintain 8-12 block range
                    if (playerDist < 8) {
                        Vec3 retreat = mob.position().subtract(target.position()).normalize();
                        Vec3 backlinePos = mob.position().add(retreat.scale(2));
                        mob.getNavigation().moveTo(backlinePos.x, backlinePos.y, backlinePos.z, 1.1);
                    } else if (playerDist > 12) {
                        mob.getNavigation().moveTo(target, 0.9);
                    }
                } else if (isSupport) {
                    // Mid-range support - flank and harass at 5-8 blocks
                    if (playerDist < 5) {
                        Vec3 flankLeft = new Vec3(-target.getLookAngle().z, 0, target.getLookAngle().x).normalize();
                        Vec3 flankPos = target.position().add(flankLeft.scale(6));
                        mob.getNavigation().moveTo(flankPos.x, flankPos.y, flankPos.z, 1.2);
                    } else if (playerDist > 8) {
                        mob.getNavigation().moveTo(target, 1.1);
                    }
                } else {
                    // Unknown role, default to mid-range
                    mob.getNavigation().moveTo(target, 1.0);
                }
                break;
                
            case DEFAULT_MELEE:
            default:
                // Standard Minecraft melee
                mob.getNavigation().moveTo(target, 1.0);
                break;
        }
    }
    
    /**
     * Get nearby allies - now supports cross-species hostile mob coordination
     * Zombies, skeletons, spiders, creepers, etc. can work together
     */
    private static List<Mob> getNearbyAllies(Mob mob) {
        return mob.level().getEntitiesOfClass(
            Mob.class,
            mob.getBoundingBox().inflate(8.0),
            m -> m != mob && !m.isDeadOrDying() && isHostileMob(m) && isHostileMob(mob)
        );
    }
    
    /**
     * Check if a mob is a hostile monster (can coordinate with other hostiles)
     * Uses string matching to avoid ClassNotFoundException issues
     */
    private static boolean isHostileMob(Mob mob) {
        String mobType = mob.getType().toString().toLowerCase();
        return mobType.contains("zombie") || 
               mobType.contains("skeleton") || 
               mobType.contains("spider") || 
               mobType.contains("creeper") || 
               mobType.contains("witch") || 
               mobType.contains("vindicator") || 
               mobType.contains("pillager") || 
               mobType.contains("evoker") ||
               mobType.contains("ravager") ||
               mobType.contains("zoglin");
    }
    
    /**
     * Check if mob is ranged (for cross-species tactics)
     */
    private static boolean isRangedMob(Mob mob) {
        String mobType = mob.getType().toString().toLowerCase();
        return mobType.contains("skeleton") || mobType.contains("witch") || 
               mobType.contains("pillager") || mobType.contains("evoker");
    }
    
    /**
     * Check if mob is melee (for cross-species tactics)
     */
    private static boolean isMeleeMob(Mob mob) {
        String mobType = mob.getType().toString().toLowerCase();
        return mobType.contains("zombie") || mobType.contains("spider");
    }
    
    /**
     * Check if mob is fast/agile (for ambush tactics)
     */
    private static boolean isFastMob(Mob mob) {
        String mobType = mob.getType().toString().toLowerCase();
        return mobType.contains("spider") || mobType.contains("creeper");
    }
    
    /**
     * Check if mob is tank/heavy (for ambush tactics)
     */
    private static boolean isTankMob(Mob mob) {
        String mobType = mob.getType().toString().toLowerCase();
        return mobType.contains("zombie") || mobType.contains("ravager");
    }
    
    /**
     * Check if mob is expendable (for sacrifice tactics)
     */
    private static boolean isExpendableMob(Mob mob) {
        String mobType = mob.getType().toString().toLowerCase();
        return mobType.contains("creeper");
    }
    
    /**
     * Check if mob is support role (for layered defense)
     */
    private static boolean isSupportMob(Mob mob) {
        String mobType = mob.getType().toString().toLowerCase();
        return mobType.contains("spider") || mobType.contains("creeper");
    }
    
    /**
     * Get tactical actions available to a mob type
     * Different mobs have different tactical repertoires
     */
    public static List<TacticalAction> getAvailableActions(String mobType) {
        List<TacticalAction> common = Arrays.asList(
            TacticalAction.RUSH_PLAYER,
            TacticalAction.STRAFE_AGGRESSIVE,
            TacticalAction.RETREAT_AND_HEAL,
            TacticalAction.WAIT_FOR_OPENING,
            TacticalAction.DEFAULT_MELEE
        );
        
        // Mob-specific tactics
        switch (mobType.toLowerCase()) {
            case "zombie":
            case "husk":
            case "drowned":
                return Arrays.asList(
                    TacticalAction.RUSH_PLAYER,
                    TacticalAction.CALL_REINFORCEMENTS,
                    TacticalAction.OVERHEAD_COMBO,
                    TacticalAction.EXPLOIT_WEAKNESS,
                    TacticalAction.COORDINATE_FLANK,      // Cooperative
                    TacticalAction.PINCER_ATTACK,         // Cooperative
                    TacticalAction.ROTATING_PRESSURE,     // Cooperative
                    TacticalAction.RANGED_MELEE_COMBO,    // Cross-species (advance under cover fire)
                    TacticalAction.LAYERED_DEFENSE,       // Cross-species (frontline tank)
                    TacticalAction.DEFAULT_MELEE
                );
                
            case "skeleton":
            case "stray":
                return Arrays.asList(
                    TacticalAction.WAIT_FOR_OPENING,
                    TacticalAction.DODGE_WEAVE,
                    TacticalAction.USE_TERRAIN,
                    TacticalAction.RETREAT_AND_HEAL,
                    TacticalAction.DISTRACT_AND_STRIKE,   // Cooperative (one distracts, others shoot)
                    TacticalAction.PROTECT_WEAK_ALLY,     // Cooperative (cover retreating allies)
                    TacticalAction.RANGED_MELEE_COMBO,    // Cross-species (provide cover fire)
                    TacticalAction.LAYERED_DEFENSE,       // Cross-species (ranged backline)
                    TacticalAction.DEFAULT_MELEE
                );
                
            case "creeper":
                return Arrays.asList(
                    TacticalAction.RUSH_PLAYER,
                    TacticalAction.FEINT_RETREAT,
                    TacticalAction.USE_TERRAIN,
                    TacticalAction.WAIT_FOR_OPENING,
                    TacticalAction.SACRIFICE_PLAY,        // Cross-species (distraction/kamikaze)
                    TacticalAction.AMBUSH_SETUP,          // Cross-species (explosive surprise)
                    TacticalAction.DEFAULT_MELEE
                );
                
            case "spider":
            case "cave_spider":
                return Arrays.asList(
                    TacticalAction.STRAFE_AGGRESSIVE,
                    TacticalAction.OVERHEAD_COMBO,
                    TacticalAction.DODGE_WEAVE,
                    TacticalAction.PUNISH_SHIELD_DROP,
                    TacticalAction.AMBUSH_SETUP,          // Cross-species (herd player with speed)
                    TacticalAction.LAYERED_DEFENSE,       // Cross-species (harass flanks)
                    TacticalAction.DEFAULT_MELEE
                );
                
            case "enderman":
                return Arrays.asList(
                    TacticalAction.COUNTER_ATTACK,
                    TacticalAction.DODGE_WEAVE,
                    TacticalAction.PUNISH_SHIELD_DROP,
                    TacticalAction.EXPLOIT_WEAKNESS,
                    TacticalAction.DEFAULT_MELEE
                );
                
            default:
                return common;
        }
    }
}
