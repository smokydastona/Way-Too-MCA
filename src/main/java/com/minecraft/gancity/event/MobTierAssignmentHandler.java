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
        CompoundTag data = mob.getPersistentData();
        
        // Check if tier already assigned (prevent reassignment on world reload)
        if (data.getBoolean(TIER_ASSIGNED_TAG)) {
            return;
        }
        
        // Assign random tier based on weights
        TacticTier tier = TacticTier.selectRandomTier(RANDOM);
        
        // Store tier in NBT
        data.putString(TIER_TAG, tier.getName());
        data.putBoolean(TIER_ASSIGNED_TAG, true);
        
        // Apply difficulty multiplier to mob stats
        applyTierModifiers(mob, tier);
        
        LOGGER.debug("Assigned {} tier to {} (ID: {})", 
            tier.getName().toUpperCase(), 
            mob.getType().getDescription().getString(),
            mob.getUUID());
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
        
        // Elite mobs are tougher
        if (tier == TacticTier.ELITE) {
            // 20% more health
            mob.setHealth(mob.getMaxHealth() * 1.2f);
            
            // Subtle visual indicator - persistent glowing effect
            mob.setGlowingTag(false); // Don't make them glow (too obvious)
        }
        // Rookie mobs are weaker
        else if (tier == TacticTier.ROOKIE) {
            // 20% less health
            mob.setHealth(mob.getMaxHealth() * 0.8f);
        }
        
        // Movement speed adjustment (subtle)
        mob.setSpeed(mob.getSpeed() * (0.9f + (multiplier * 0.1f)));
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
