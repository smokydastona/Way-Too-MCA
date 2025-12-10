package com.minecraft.gancity.event;

import com.minecraft.gancity.GANCityMod;
import com.minecraft.gancity.ai.TacticTier;
import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.entity.monster.Zombie;
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
            
            // Subtle visual indicator - persistent glowing effect
            mob.setGlowingTag(false); // Don't make them glow (too obvious)
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
        }
        // Veteran mobs keep default stats
        
        // Log speed modifier (if changed)
        float finalSpeed = (float) mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED).getBaseValue();
        if (Math.abs(finalSpeed - originalSpeed) > 0.001f) {
            LOGGER.info("[Tier System] Speed modifier applied: {} -> {} ({}{:.1f}%)",
                    String.format("%.3f", originalSpeed),
                    String.format("%.3f", finalSpeed),
                    finalSpeed > originalSpeed ? "+" : "",
                    ((finalSpeed / originalSpeed - 1.0f) * 100.0f));
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
}
