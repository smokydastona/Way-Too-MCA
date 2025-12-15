package com.minecraft.gancity.ai;

import com.minecraft.gancity.ai.MobBehaviorAI.AITier;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Visual indicators for AI tier progression
 * Inspired by Hostile Neural Networks color-coded tiers
 */
public class TierVisualIndicators {
    
    /**
     * Spawn tier particle effects around a mob
     * Called periodically (every few seconds) to show mob intelligence level
     */
    public static void spawnTierParticles(Mob mob, AITier tier, ServerLevel level) {
        if (mob == null || tier == null || level == null) {
            return;
        }
        
        // Only spawn particles for TRAINED and above (avoid spam for weak mobs)
        if (tier == AITier.UNTRAINED || tier == AITier.LEARNING) {
            return;
        }
        
        Vec3 pos = mob.position().add(0, mob.getBbHeight() / 2.0, 0);
        DustParticleOptions particleData = getTierParticleColor(tier);
        
        // Number of particles based on tier
        int particleCount = getParticleCount(tier);
        
        // Spawn particles in a ring around the mob
        for (int i = 0; i < particleCount; i++) {
            double angle = (2 * Math.PI * i) / particleCount;
            double radius = 0.5;
            
            double xOffset = Math.cos(angle) * radius;
            double zOffset = Math.sin(angle) * radius;
            double yOffset = (Math.random() - 0.5) * 0.3;
            
            level.sendParticles(
                particleData,
                pos.x + xOffset,
                pos.y + yOffset,
                pos.z + zOffset,
                1,  // count
                0, 0, 0,  // offset
                0.02  // speed
            );
        }
        
        // MASTER tier gets extra glow effect
        if (tier == AITier.MASTER) {
            spawnGlowEffect(mob, level);
        }
    }
    
    /**
     * Get particle color based on tier
     */
    private static DustParticleOptions getTierParticleColor(AITier tier) {
        Vector3f color;
        float size = 1.0f;
        
        switch (tier) {
            case UNTRAINED:
                color = new Vector3f(0.5f, 0.5f, 0.5f);  // Gray
                size = 0.5f;
                break;
            case LEARNING:
                color = new Vector3f(1.0f, 1.0f, 1.0f);  // White
                size = 0.7f;
                break;
            case TRAINED:
                color = new Vector3f(0.2f, 1.0f, 0.2f);  // Green
                size = 0.9f;
                break;
            case EXPERT:
                color = new Vector3f(0.3f, 0.5f, 1.0f);  // Blue
                size = 1.1f;
                break;
            case MASTER:
                color = new Vector3f(0.8f, 0.2f, 1.0f);  // Purple
                size = 1.3f;
                break;
            default:
                color = new Vector3f(0.5f, 0.5f, 0.5f);
                break;
        }
        
        return new DustParticleOptions(color, size);
    }
    
    /**
     * Get number of particles based on tier
     */
    private static int getParticleCount(AITier tier) {
        switch (tier) {
            case UNTRAINED:
                return 0;
            case LEARNING:
                return 0;
            case TRAINED:
                return 3;
            case EXPERT:
                return 5;
            case MASTER:
                return 8;
            default:
                return 0;
        }
    }
    
    /**
     * Spawn special glow effect for MASTER tier mobs
     */
    private static void spawnGlowEffect(Mob mob, ServerLevel level) {
        Vec3 pos = mob.position().add(0, mob.getBbHeight() / 2.0, 0);
        
        // Spawn enchantment glint particles
        for (int i = 0; i < 2; i++) {
            double xOffset = (Math.random() - 0.5) * mob.getBbWidth();
            double yOffset = (Math.random() - 0.5) * mob.getBbHeight();
            double zOffset = (Math.random() - 0.5) * mob.getBbWidth();
            
            level.sendParticles(
                ParticleTypes.ENCHANT,
                pos.x + xOffset,
                pos.y + yOffset,
                pos.z + zOffset,
                1,
                0, 0.1, 0,
                0.5
            );
        }
    }
    
    /**
     * Spawn celebration particles when a mob tiers up
     */
    public static void spawnTierUpEffect(Mob mob, AITier newTier, ServerLevel level) {
        if (mob == null || newTier == null || level == null) {
            return;
        }
        
        Vec3 pos = mob.position().add(0, mob.getBbHeight() / 2.0, 0);
        DustParticleOptions particleData = getTierParticleColor(newTier);
        
        // Burst of particles
        for (int i = 0; i < 20; i++) {
            double xVel = (Math.random() - 0.5) * 0.3;
            double yVel = Math.random() * 0.3;
            double zVel = (Math.random() - 0.5) * 0.3;
            
            level.sendParticles(
                particleData,
                pos.x,
                pos.y,
                pos.z,
                1,
                xVel, yVel, zVel,
                0.1
            );
        }
        
        // Add enchantment particles for extra flair
        for (int i = 0; i < 10; i++) {
            double xVel = (Math.random() - 0.5) * 0.2;
            double yVel = Math.random() * 0.2 + 0.1;
            double zVel = (Math.random() - 0.5) * 0.2;
            
            level.sendParticles(
                ParticleTypes.ENCHANT,
                pos.x,
                pos.y,
                pos.z,
                1,
                xVel, yVel, zVel,
                0.1
            );
        }
    }
    
    /**
     * Get display name with color for tier
     */
    public static String getTierDisplayName(AITier tier) {
        String colorCode;
        switch (tier) {
            case UNTRAINED:
                colorCode = "§7";  // Gray
                break;
            case LEARNING:
                colorCode = "§f";  // White
                break;
            case TRAINED:
                colorCode = "§a";  // Green
                break;
            case EXPERT:
                colorCode = "§9";  // Blue
                break;
            case MASTER:
                colorCode = "§d";  // Purple
                break;
            default:
                colorCode = "§7";
                break;
        }
        return colorCode + tier.name();
    }
}
