package com.minecraft.gancity.event;

import com.minecraft.gancity.GANCityMod;
import com.minecraft.gancity.ai.TacticTier;
import com.mojang.logging.LogUtils;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.*;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;

/**
 * Assigns tactic tiers to mobs on spawn
 * Creates natural difficulty variation with elite, veteran, and rookie mobs
 * 
 * Compatibility:
 * - Ice and Fire: Skips dragons and mythical creatures (they have custom AI)
 * - PMMO: Reduces stat modifiers to avoid conflicts with PMMO scaling
 * - Livestock/Pet Overhaul: Skips animals (not hostile mobs)
 */
@Mod.EventBusSubscriber(modid = GANCityMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class MobTierAssignmentHandler {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Random RANDOM = new Random();
    
    // Mod compatibility detection
    private static final boolean ICE_AND_FIRE_LOADED = ModList.get().isLoaded("iceandfire");
    private static final boolean PMMO_LOADED = ModList.get().isLoaded("pmmo");
    private static final boolean LIVESTOCK_OVERHAUL_LOADED = ModList.get().isLoaded("livestock_overhaul");
    private static final boolean PET_OVERHAUL_LOADED = ModList.get().isLoaded("petoverhaul");
    
    private static final String TIER_TAG = "AdaptiveMobAI_Tier";
    private static final String TIER_ASSIGNED_TAG = "AdaptiveMobAI_TierAssigned";
    
    // Log compatibility status on first access
    static {
        if (ICE_AND_FIRE_LOADED) {
            LOGGER.info("[Tier System] Ice and Fire detected - skipping dragons and mythical creatures");
        }
        if (PMMO_LOADED) {
            LOGGER.info("[Tier System] PMMO detected - reducing stat modifiers to avoid conflicts");
        }
        if (LIVESTOCK_OVERHAUL_LOADED || PET_OVERHAUL_LOADED) {
            LOGGER.info("[Tier System] Animal overhaul mod detected - skipping non-hostile entities");
        }
    }
    
    /**
     * Assign tactic tier when mob spawns
     */
    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        Entity entity = event.getEntity();
        
        // Only process on server side
        if (event.getLevel().isClientSide()) {
            return;
        }
        
        // Only process supported mob types
        if (!isSupportedMob(entity)) {
            return;
        }
        
        Mob mob = (Mob) entity;
        
        // Use entity's own NBT data (visible in F3) instead of PersistentData
        CompoundTag entityData = new CompoundTag();
        mob.saveWithoutId(entityData);
        
        // Check if tier already assigned (prevent reassignment on world reload)
        if (entityData.contains(TIER_TAG)) {
            return;
        }
        
        // Assign random tier based on weights
        TacticTier tier = TacticTier.selectRandomTier(RANDOM);
        
        // Store tier in entity NBT (visible in F3 screen)
        entityData.putString(TIER_TAG, tier.getName());
        entityData.putBoolean(TIER_ASSIGNED_TAG, true);
        mob.load(entityData);
        
        // Also store in PersistentData for code access
        CompoundTag persistentData = mob.getPersistentData();
        persistentData.putString(TIER_TAG, tier.getName());
        persistentData.putBoolean(TIER_ASSIGNED_TAG, true);
        
        // Apply difficulty multiplier to mob stats
        applyTierModifiers(mob, tier);
        
        LOGGER.info("[Tier System] Assigned {} tier to {} (UUID: {}) - Health: {}/{}", 
            tier.getName().toUpperCase(), 
            mob.getType().getDescription().getString(),
            mob.getUUID(),
            mob.getHealth(),
            mob.getMaxHealth());
    }
    
    /**
     * Check if mob type is supported by adaptive AI
     * Now supports ALL hostile mobs, with compatibility checks for other mods
     */
    private static boolean isSupportedMob(Entity entity) {
        // Only support Enemy type mobs (hostile mobs)
        if (!(entity instanceof Enemy)) {
            return false;
        }
        
        // Ice and Fire compatibility - skip dragons and mythical creatures
        if (ICE_AND_FIRE_LOADED) {
            String entityId = entity.getType().toString();
            // Skip Ice and Fire entities (they have complex custom AI)
            if (entityId.contains("iceandfire:")) {
                return false;
            }
        }
        
        // Livestock/Pet Overhaul compatibility - these mods only affect animals, not hostile mobs
        // No additional checks needed since we already filter for Enemy type
        
        return true;
    }
    
    /**
     * Apply stat modifiers based on tier
     * PMMO compatibility: Reduce multipliers when PMMO is loaded to avoid conflicts
     */
    private static void applyTierModifiers(Mob mob, TacticTier tier) {
        float multiplier = tier.getDifficultyMultiplier();
        
        // PMMO compatibility - reduce our stat modifiers to avoid stacking conflicts
        float healthMultiplier = 1.2f;
        float speedMultiplier = 1.1f;
        float weakHealthMultiplier = 0.8f;
        float weakSpeedMultiplier = 0.9f;
        
        if (PMMO_LOADED) {
            // Reduce modifiers by half when PMMO is present
            healthMultiplier = 1.1f;      // 20% -> 10%
            speedMultiplier = 1.05f;      // 10% -> 5%
            weakHealthMultiplier = 0.9f;  // -20% -> -10%
            weakSpeedMultiplier = 0.95f;  // -10% -> -5%
            
            LOGGER.debug("[Tier System] PMMO compatibility: Using reduced stat modifiers");
        }
        
        // Get base stats for logging
        float originalMaxHealth = mob.getMaxHealth();
        float originalSpeed = (float) mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED).getBaseValue();
        
        // Elite mobs are tougher
        if (tier == TacticTier.ELITE) {
            // More max health (scaled based on PMMO presence)
            float newMaxHealth = originalMaxHealth * healthMultiplier;
            mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH)
                .setBaseValue(newMaxHealth);
            mob.setHealth(newMaxHealth);
            
            // Faster movement (scaled based on PMMO presence)
            float newSpeed = originalSpeed * speedMultiplier;
            mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED)
                .setBaseValue(newSpeed);
            
            // Elite mobs form teams - add pack coordination AI
            addEliteTeamBehavior(mob);
            
            // Visual indicator handled by particle effects (no glow)
        }
        // Rookie mobs are weaker
        else if (tier == TacticTier.ROOKIE) {
            // Less max health (scaled based on PMMO presence)
            float newMaxHealth = originalMaxHealth * weakHealthMultiplier;
            mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH)
                .setBaseValue(newMaxHealth);
            mob.setHealth(newMaxHealth);
            
            // Slower movement (scaled based on PMMO presence)
            float newSpeed = originalSpeed * weakSpeedMultiplier;
            mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED)
                .setBaseValue(newSpeed);
            
            // No visual indicator for rookies (makes them blend in)
        }
        // Veteran mobs keep default stats
        
        // Log speed modifier (if changed)
        float finalSpeed = (float) mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED).getBaseValue();
        if (Math.abs(finalSpeed - originalSpeed) > 0.001f) {
            float percentChange = (finalSpeed / originalSpeed - 1.0f) * 100.0f;
            LOGGER.info("[Tier System] Speed modifier applied: {} -> {} ({}{}%)",
                    String.format("%.3f", originalSpeed),
                    String.format("%.3f", finalSpeed),
                    finalSpeed > originalSpeed ? "+" : "",
                    String.format("%.1f", percentChange));
        }
    }
    
    /**
     * Get tier from mob entity
     */
    public static TacticTier getTierFromMob(Mob mob) {
        CompoundTag data = mob.getPersistentData();
        
        if (data.contains(TIER_TAG)) {
            String tierName = data.getString(TIER_TAG);
            return TacticTier.fromName(tierName);
        }
        
        return TacticTier.VETERAN; // default
    }
    
    /**
     * Add team coordination behavior to elite mobs
     * Elite mobs will follow nearby allies and coordinate attacks
     */
    private static void addEliteTeamBehavior(Mob mob) {
        // Add pack coordination goal (priority 2 - high priority)
        mob.goalSelector.addGoal(2, new ElitePackCoordinationGoal(mob));
    }
    
    /**
     * AI Goal that makes elite mobs coordinate with nearby allies
     * Elite mobs will stick together and focus fire on targets
     * Player-aware: packs form around the closest player target
     */
    static class ElitePackCoordinationGoal extends Goal {
        private final Mob mob;
        private Mob packLeader;
        private LivingEntity sharedTarget;
        private Player targetPlayer; // Track which player this pack is focused on
        private int coordinationCooldown = 0;
        
        public ElitePackCoordinationGoal(Mob mob) {
            this.mob = mob;
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.TARGET));
        }
        
        @Override
        public boolean canUse() {
            // Only coordinate every 20 ticks (1 second) for performance
            if (coordinationCooldown-- > 0) {
                return false;
            }
            coordinationCooldown = 20;
            
            // First, determine our current target player (if any)
            LivingEntity currentTarget = mob.getTarget();
            Player focusPlayer = null;
            
            if (currentTarget instanceof Player) {
                focusPlayer = (Player) currentTarget;
            } else {
                // If we don't have a player target, find the nearest player
                focusPlayer = mob.level().getNearestPlayer(mob, 16.0);
            }
            
            // If no player nearby, don't coordinate
            if (focusPlayer == null) {
                return false;
            }
            
            final Player finalFocusPlayer = focusPlayer;
            
            // Find nearby elite allies within 16 blocks that are also focused on the same player
            // or don't have a target yet (can join our pack)
            List<Mob> nearbyAllies = mob.level().getEntitiesOfClass(
                Mob.class,
                mob.getBoundingBox().inflate(16.0),
                ally -> {
                    if (ally == mob || !(ally instanceof Enemy) || !hasTier(ally) || 
                        getTierFromMob(ally) != TacticTier.ELITE || ally.isDeadOrDying()) {
                        return false;
                    }
                    
                    // Only form pack with allies focused on same player or without target
                    LivingEntity allyTarget = ally.getTarget();
                    return allyTarget == null || 
                           allyTarget == finalFocusPlayer ||
                           (allyTarget instanceof Player && 
                            ally.distanceToSqr(finalFocusPlayer) < ally.distanceToSqr(allyTarget));
                }
            );
            
            if (nearbyAllies.isEmpty()) {
                return false;
            }
            
            // Find the strongest nearby elite as pack leader (must be targeting same player)
            packLeader = nearbyAllies.stream()
                .filter(ally -> ally.getTarget() == finalFocusPlayer)
                .max(Comparator.comparingDouble(Mob::getHealth))
                .orElse(null);
            
            // If no pack leader has the target yet, become the leader yourself
            if (packLeader == null && mob.getTarget() == finalFocusPlayer) {
                // Don't join a pack, let others follow us
                return false;
            }
            
            // Share target with pack leader if they have one
            if (packLeader != null && packLeader.getTarget() instanceof Player) {
                sharedTarget = packLeader.getTarget();
                targetPlayer = (Player) sharedTarget;
                return true;
            }
            
            return false;
        }
        
        @Override
        public boolean canContinueToUse() {
            // Stop coordinating if target player changed, died, or is too far
            return sharedTarget != null && 
                   sharedTarget.isAlive() && 
                   packLeader != null && 
                   packLeader.isAlive() &&
                   packLeader.getTarget() == sharedTarget && // Leader still targeting same player
                   mob.distanceToSqr(packLeader) < 256.0 && // Within 16 blocks of leader
                   mob.distanceToSqr(sharedTarget) < 576.0; // Within 24 blocks of target player
        }
        
        @Override
        public void start() {
            // Adopt pack leader's target for coordinated attack
            // Only if it's a player (prevent non-player target confusion)
            if (sharedTarget instanceof Player && mob.getTarget() != sharedTarget) {
                mob.setTarget(sharedTarget);
            }
        }
        
        @Override
        public void tick() {
            // Verify we're still focused on the same player
            if (targetPlayer != null && !targetPlayer.isAlive()) {
                this.stop();
                return;
            }
            
            // If pack leader switched to a different player, break formation
            if (packLeader != null && packLeader.getTarget() != sharedTarget) {
                this.stop();
                return;
            }
            
            // Stay near pack leader (within 8 blocks)
            if (packLeader != null && mob.distanceToSqr(packLeader) > 64.0) {
                mob.getNavigation().moveTo(packLeader, 1.0);
            }
            
            // Keep attacking shared target (only if still the same player)
            if (sharedTarget instanceof Player && mob.getTarget() != sharedTarget) {
                mob.setTarget(sharedTarget);
            }
        }
        
        @Override
        public void stop() {
            packLeader = null;
            sharedTarget = null;
            targetPlayer = null;
        }
    }
    
    /**
     * Check if mob has been assigned a tier
     */
    public static boolean hasTier(Mob mob) {
        return mob.getPersistentData().getBoolean(TIER_ASSIGNED_TAG);
    }
    
    /**
     * Spawn particles around elite mobs every few ticks
     */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        
        // Only check every 10 ticks (0.5 seconds) for performance
        if (event.getServer().getTickCount() % 10 != 0) {
            return;
        }
        
        // Spawn particles for all elite mobs in all dimensions
        for (ServerLevel level : event.getServer().getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof Mob mob && hasTier(mob)) {
                    TacticTier tier = getTierFromMob(mob);
                    
                    if (tier == TacticTier.ELITE) {
                        // Spawn red flame particles in a circle around elite mobs
                        double radius = 0.5;
                        for (int i = 0; i < 3; i++) {
                            double angle = RANDOM.nextDouble() * Math.PI * 2;
                            double offsetX = Math.cos(angle) * radius;
                            double offsetZ = Math.sin(angle) * radius;
                            double offsetY = RANDOM.nextDouble() * mob.getBbHeight();
                            
                            level.sendParticles(
                                ParticleTypes.FLAME,
                                mob.getX() + offsetX,
                                mob.getY() + offsetY,
                                mob.getZ() + offsetZ,
                                1, // particle count
                                0.0, 0.05, 0.0, // velocity spread
                                0.01 // speed
                            );
                        }
                    }
                }
            }
        }
    }
}
