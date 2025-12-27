package com.minecraft.gancity.event;

import com.minecraft.gancity.GANCityMod;
import com.minecraft.gancity.ai.GenericRangedWeaponGoal;
import com.minecraft.gancity.ai.TacticTier;
import com.mojang.logging.LogUtils;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
@SuppressWarnings({"null", "unused"})
public class MobTierAssignmentHandler {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Random RANDOM = new Random();
    
    // Mod compatibility detection - lazy loaded to avoid classloading deadlock during mixin discovery
    private static Boolean iceAndFireLoaded = null;
    private static Boolean pmmoLoaded = null;
    private static Boolean livestockOverhaulLoaded = null;
    private static Boolean petOverhaulLoaded = null;
    
    private static boolean isIceAndFireLoaded() {
        if (iceAndFireLoaded == null) {
            iceAndFireLoaded = ModList.get().isLoaded("iceandfire");
        }
        return iceAndFireLoaded;
    }
    
    private static boolean isPmmoLoaded() {
        if (pmmoLoaded == null) {
            pmmoLoaded = ModList.get().isLoaded("pmmo");
        }
        return pmmoLoaded;
    }
    
    private static boolean isLivestockOverhaulLoaded() {
        if (livestockOverhaulLoaded == null) {
            livestockOverhaulLoaded = ModList.get().isLoaded("livestock_overhaul");
        }
        return livestockOverhaulLoaded;
    }
    
    private static boolean isPetOverhaulLoaded() {
        if (petOverhaulLoaded == null) {
            petOverhaulLoaded = ModList.get().isLoaded("petoverhaul");
        }
        return petOverhaulLoaded;
    }
    
    private static final String TIER_TAG = "AdaptiveMobAI_Tier";
    private static final String TIER_ASSIGNED_TAG = "AdaptiveMobAI_TierAssigned";
    private static final String UNIVERSAL_WEAPONS_TAG = "AdaptiveMobAI_UniversalWeapons";
    private static final String GENERIC_RANGED_GOAL_TAG = "AdaptiveMobAI_GenericRangedGoal";
    
    // Compatibility status logged on first use (lazy initialization prevents classloading deadlock)
    
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

        // Ensure the generic ranged-weapon goal exists for non-ranged hostile mobs.
        // This is Forge-side (not mixin) so it works even in reduced-feature mode.
        ensureGenericRangedWeaponGoal(mob);
        
        // Use entity's own NBT data (visible in F3) instead of PersistentData
        CompoundTag entityData = new CompoundTag();
        mob.saveWithoutId(entityData);
        
        // Check if tier already assigned (prevent reassignment on world reload)
        if (entityData.contains(TIER_TAG)) {
            // Even if tier is already assigned, we still want to apply universal weapon capability
            // exactly once for older mobs that predate the feature.
            applyUniversalWeaponRulesOnce(mob);
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

        // Universal weapon capability: allow hostiles to use and pick up non-native weapons.
        // This also assigns a simple weapon loadout so you actually see variety (e.g., zombies with bows).
        applyUniversalWeaponRulesOnce(mob);
        
        LOGGER.debug("[Tier System] Assigned {} tier to {} (UUID: {}) - Health: {}/{}", 
            tier.getName().toUpperCase(), 
            mob.getType().getDescription().getString(),
            mob.getUUID(),
            mob.getHealth(),
            mob.getMaxHealth());
    }

    private static void ensureGenericRangedWeaponGoal(Mob mob) {
        if (!(mob instanceof Monster)) {
            return;
        }
        if (mob instanceof RangedAttackMob) {
            return;
        }

        CompoundTag persistentData = mob.getPersistentData();
        if (persistentData.getBoolean(GENERIC_RANGED_GOAL_TAG)) {
            return;
        }

        // Priority 0 so it preempts vanilla melee goals when a ranged weapon is held.
        mob.goalSelector.addGoal(0, new GenericRangedWeaponGoal(mob, 1.0));
        persistentData.putBoolean(GENERIC_RANGED_GOAL_TAG, true);
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
        if (isIceAndFireLoaded()) {
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
     * Give hostile mobs the ability to use any held weapon, and provide a basic randomized loadout.
     *
     * Notes:
     * - Picking up loot is required so mobs can swap to weapons they find.
     * - We assign a bow or sword so the feature is visible immediately.
     */
    private static void applyUniversalWeaponRulesOnce(Mob mob) {
        if (!(mob instanceof Enemy)) {
            return;
        }

        try {
            mob.setCanPickUpLoot(true);

            // Avoid re-randomizing equipment every time the entity loads.
            CompoundTag persistentData = mob.getPersistentData();
            if (persistentData.getBoolean(UNIVERSAL_WEAPONS_TAG)) {
                return;
            }

            // Randomly give a melee or ranged weapon.
            // Keep it simple and visible: bow/crossbow/trident/sword.
            int roll = RANDOM.nextInt(4);
            ItemStack weapon;
            if (roll == 0) {
                weapon = new ItemStack(Items.BOW);
            } else if (roll == 1) {
                weapon = new ItemStack(Items.CROSSBOW);
            } else if (roll == 2) {
                weapon = new ItemStack(Items.TRIDENT);
            } else {
                weapon = new ItemStack(Items.STONE_SWORD);
            }

            mob.setItemSlot(EquipmentSlot.MAINHAND, weapon);

            // Mark as applied (persistent)
            persistentData.putBoolean(UNIVERSAL_WEAPONS_TAG, true);

            // Also store in entity NBT so it shows up in F3 like the tier tags.
            CompoundTag entityData = new CompoundTag();
            mob.saveWithoutId(entityData);
            entityData.putBoolean(UNIVERSAL_WEAPONS_TAG, true);
            mob.load(entityData);
        } catch (Exception e) {
            // Non-critical; skip if a mob type rejects equipment changes.
            LOGGER.debug("[Universal Weapons] Could not assign weapon to {}: {}",
                mob.getType().toString(), e.getMessage());
        }
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
        
        if (isPmmoLoaded()) {
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
            LOGGER.debug("[Tier System] Speed modifier applied: {} -> {} ({}{}%)",
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
