package com.minecraft.gancity.event;

import com.minecraft.gancity.GANCityMod;
import com.minecraft.gancity.ai.TacticTier;
import com.mojang.logging.LogUtils;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.util.Random;

/**
 * Assigns tactic tiers to mobs on spawn
 * Creates natural difficulty variation with elite, veteran, and rookie mobs
 */
@Mod.EventBusSubscriber(modid = GANCityMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class MobTierAssignmentHandler {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Random RANDOM = new Random();
    
    private static final String TIER_TAG = "AdaptiveMobAI_Tier";
    private static final String TIER_ASSIGNED_TAG = "AdaptiveMobAI_TierAssigned";
    
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
     */
    private static boolean isSupportedMob(Entity entity) {
        return entity instanceof Zombie || 
               entity instanceof Skeleton || 
               entity instanceof Creeper || 
               entity instanceof Spider;
    }
    
    /**
     * Apply stat modifiers based on tier
     */
    private static void applyTierModifiers(Mob mob, TacticTier tier) {
        float multiplier = tier.getDifficultyMultiplier();
        
        // Get base stats for logging
        float originalMaxHealth = mob.getMaxHealth();
        float originalSpeed = (float) mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED).getBaseValue();
        
        // Elite mobs are tougher
        if (tier == TacticTier.ELITE) {
            // 20% more max health (must set attribute, not just current health)
            float newMaxHealth = originalMaxHealth * 1.2f;
            mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH)
                .setBaseValue(newMaxHealth);
            mob.setHealth(newMaxHealth);
            
            // 10% faster movement
            float newSpeed = originalSpeed * 1.1f;
            mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED)
                .setBaseValue(newSpeed);
            
            // Visual indicator - subtle red glow
            mob.setGlowingTag(true);
        }
        // Rookie mobs are weaker
        else if (tier == TacticTier.ROOKIE) {
            // 20% less max health
            float newMaxHealth = originalMaxHealth * 0.8f;
            mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH)
                .setBaseValue(newMaxHealth);
            mob.setHealth(newMaxHealth);
            
            // 10% slower movement
            float newSpeed = originalSpeed * 0.9f;
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
