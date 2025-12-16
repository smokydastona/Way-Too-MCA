package com.minecraft.gancity.ai;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.*;

/**
 * Reflex Module - Lightning-fast reactive behaviors
 * Inspired by AI-Player's reflex system
 * 
 * Handles instant reactions:
 * - Dodge projectiles
 * - Block incoming attacks
 * - Counter-attack windows
 * - Jump-crit timing
 */
public class ReflexModule {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    // Reflex timing thresholds (milliseconds)
    private static final long DODGE_WINDOW = 300;      // 300ms to dodge
    private static final long BLOCK_WINDOW = 150;      // 150ms to raise shield
    private static final long COUNTER_WINDOW = 500;    // 500ms after being hit
    private static final long CRIT_WINDOW = 100;       // 100ms jump timing
    
    // Reflex success rates (0.0 - 1.0)
    private static final float BASE_DODGE_CHANCE = 0.3f;
    private static final float BASE_BLOCK_CHANCE = 0.5f;
    private static final float BASE_COUNTER_CHANCE = 0.7f;
    
    private final Map<UUID, ReflexState> reflexStates = new HashMap<>();
    private final Random random = new Random();
    
    /**
     * Check if mob should dodge incoming projectile
     */
    public boolean shouldDodge(Mob mob, Projectile projectile) {
        if (projectile == null || !mob.hasLineOfSight(projectile)) {
            return false;
        }
        
        // Calculate projectile trajectory
        Vec3 trajectory = calculateTrajectory(projectile);
        Vec3 mobPos = mob.position();
        
        // Predict if projectile will hit
        boolean willHit = predictHit(trajectory, projectile.position(), mobPos, mob.getBbWidth());
        
        if (!willHit) {
            return false;
        }
        
        // Get reflex state
        ReflexState state = getOrCreateState(mob);
        
        // Check if in dodge cooldown
        if (System.currentTimeMillis() - state.lastDodgeTime < DODGE_WINDOW * 2) {
            return false;
        }
        
        // Roll for dodge success
        float dodgeChance = BASE_DODGE_CHANCE * state.reflexMultiplier;
        boolean success = random.nextFloat() < dodgeChance;
        
        if (success) {
            state.lastDodgeTime = System.currentTimeMillis();
            state.successfulDodges++;
            LOGGER.debug("Mob {} dodging projectile!", mob.getType().getDescriptionId());
        }
        
        return success;
    }
    
    /**
     * Execute dodge movement
     */
    public Vec3 getDodgeDirection(Mob mob, Projectile projectile) {
        Vec3 trajectory = calculateTrajectory(projectile);
        
        // Get perpendicular direction to projectile path
        Vec3 perpendicular = new Vec3(-trajectory.z, 0, trajectory.x).normalize();
        
        // Randomly choose left or right
        if (random.nextBoolean()) {
            perpendicular = perpendicular.reverse();
        }
        
        // Add slight upward component for jump-dodge
        return perpendicular.add(0, 0.3, 0);
    }
    
    /**
     * Check if mob should block incoming attack
     */
    public boolean shouldBlock(Mob mob, LivingEntity attacker) {
        try {
            // NULL CHECK: Validate inputs
            if (mob == null || attacker == null || !mob.isAlive() || !attacker.isAlive()) {
                return false;
            }
            
            if (!mob.hasLineOfSight(attacker)) {
                return false;
            }
            
            ReflexState state = getOrCreateState(mob);
            if (state == null) {
                return false;
            }
            
            // Check if in block cooldown
            if (System.currentTimeMillis() - state.lastBlockTime < BLOCK_WINDOW * 3) {
                return false;
            }
            
            // Check distance - only block if attacker is close
            double distance = mob.distanceTo(attacker);
        if (distance > 3.0) {
            return false;
        }
        
        // Check if attacker is winding up attack (velocity check)
        boolean attackIncoming = attacker.getDeltaMovement().length() > 0.1;
        
        if (!attackIncoming) {
            return false;
        }
        
        // Roll for block success
        float blockChance = BASE_BLOCK_CHANCE * state.reflexMultiplier;
        boolean success = random.nextFloat() < blockChance;
        
        if (success) {
            state.lastBlockTime = System.currentTimeMillis();
            state.successfulBlocks++;
            LOGGER.debug("Mob {} raising shield to block!", mob.getType().getDescriptionId());
        }
        
        return success;
        } catch (Exception e) {
            return false; // Safe fallback on any exception
        }
    }
    
    /**
     * Check if mob should counter-attack after being hit
     */
    public boolean shouldCounterAttack(Mob mob, LivingEntity attacker) {
        try {
            // NULL CHECK: Validate inputs
            if (mob == null || attacker == null || !mob.isAlive() || !attacker.isAlive()) {
                return false;
            }
            
            if (!mob.hasLineOfSight(attacker)) {
                return false;
            }
        
        ReflexState state = getOrCreateState(mob);
        
        // Check if within counter window after last hit
        long timeSinceHit = System.currentTimeMillis() - state.lastHitTime;
        if (timeSinceHit > COUNTER_WINDOW) {
            return false;
        }
        
        // Can't counter if already in counter cooldown
        if (state.lastCounterTime > state.lastHitTime) {
            return false;
        }
        
        // Roll for counter success
        float counterChance = BASE_COUNTER_CHANCE * state.reflexMultiplier;
        boolean success = random.nextFloat() < counterChance;
        
        if (success) {
            state.lastCounterTime = System.currentTimeMillis();
            state.successfulCounters++;
            LOGGER.debug("Mob {} executing counter-attack!", mob.getType().getDescriptionId());
        }
        
        return success;
        } catch (Exception e) {
            return false; // Safe fallback
        }
    }
    
    /**
     * Check if mob should jump for critical hit
     */
    public boolean shouldJumpCrit(Mob mob, LivingEntity target) {
        try {
            // NULL CHECK: Validate inputs
            if (mob == null || target == null || !mob.isAlive() || !target.isAlive()) {
                return false;
            }
        
        ReflexState state = getOrCreateState(mob);
        
        // Check distance - optimal crit range
        double distance = mob.distanceTo(target);
        if (distance < 2.0 || distance > 4.0) {
            return false;
        }
        
        // Check if in crit cooldown
        if (System.currentTimeMillis() - state.lastCritTime < CRIT_WINDOW * 10) {
            return false;
        }
        
        // Only attempt if mob is on ground
        if (!mob.onGround()) {
            return false;
        }
        
        // Higher success rate for skilled mobs
        float critChance = 0.6f * state.reflexMultiplier;
        boolean success = random.nextFloat() < critChance;
        
        if (success) {
            state.lastCritTime = System.currentTimeMillis();
            state.successfulCrits++;
            LOGGER.debug("Mob {} timing jump crit!", mob.getType().getDescriptionId());
        }
        
        return success;
        } catch (Exception e) {
            return false; // Safe fallback
        }
    }
    
    /**
     * Register that mob was hit (for counter-attack tracking)
     */
    public void registerHit(Mob mob) {
        try {
            if (mob == null) return;
            
            ReflexState state = getOrCreateState(mob);
            if (state == null) return;
            state.lastHitTime = System.currentTimeMillis();
            state.totalHitsTaken++;
        } catch (Exception e) {
            // Silent fail - not critical
        }
    }
    
    /**
     * Improve reflexes over time (learning)
     */
    public void improveReflexes(UUID mobId, float improvement) {
        ReflexState state = reflexStates.get(mobId);
        if (state != null) {
            state.reflexMultiplier = Math.min(2.0f, state.reflexMultiplier + improvement);
            LOGGER.debug("Mob reflexes improved to {}x", state.reflexMultiplier);
        }
    }
    
    /**
     * Get reflex statistics
     */
    public Map<String, Integer> getReflexStats(UUID mobId) {
        ReflexState state = reflexStates.get(mobId);
        if (state == null) {
            return Collections.emptyMap();
        }
        
        Map<String, Integer> stats = new HashMap<>();
        stats.put("dodges", state.successfulDodges);
        stats.put("blocks", state.successfulBlocks);
        stats.put("counters", state.successfulCounters);
        stats.put("crits", state.successfulCrits);
        stats.put("hits_taken", state.totalHitsTaken);
        
        return stats;
    }
    
    /**
     * Calculate projectile trajectory vector
     */
    private Vec3 calculateTrajectory(Projectile projectile) {
        Vec3 velocity = projectile.getDeltaMovement();
        return velocity.normalize();
    }
    
    /**
     * Predict if projectile will hit mob
     */
    private boolean predictHit(Vec3 trajectory, Vec3 projectilePos, Vec3 mobPos, float mobWidth) {
        // Calculate closest point on trajectory to mob
        Vec3 toMob = mobPos.subtract(projectilePos);
        double projection = toMob.dot(trajectory);
        
        if (projection < 0) {
            return false; // Projectile moving away
        }
        
        Vec3 closestPoint = projectilePos.add(trajectory.scale(projection));
        double distance = closestPoint.distanceTo(mobPos);
        
        // Hit if within mob's bounding box
        return distance <= (mobWidth / 2.0 + 0.3); // +0.3 for safety margin
    }
    
    /**
     * Get or create reflex state for mob
     */
    private ReflexState getOrCreateState(Mob mob) {
        return reflexStates.computeIfAbsent(mob.getUUID(), k -> new ReflexState());
    }
    
    /**
     * Cleanup dead mobs
     */
    public void cleanup() {
        reflexStates.entrySet().removeIf(entry -> {
            long timeSinceActivity = System.currentTimeMillis() - entry.getValue().lastHitTime;
            return timeSinceActivity > 60000; // Remove after 1 minute of inactivity
        });
    }
    
    /**
     * Reflex state tracking for individual mob
     */
    private static class ReflexState {
        long lastDodgeTime = 0;
        long lastBlockTime = 0;
        long lastCounterTime = 0;
        long lastCritTime = 0;
        long lastHitTime = 0;
        
        int successfulDodges = 0;
        int successfulBlocks = 0;
        int successfulCounters = 0;
        int successfulCrits = 0;
        int totalHitsTaken = 0;
        
        float reflexMultiplier = 1.0f; // Improves with experience
    }
}
