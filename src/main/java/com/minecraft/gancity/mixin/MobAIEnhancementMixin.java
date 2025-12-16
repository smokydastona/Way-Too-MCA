package com.minecraft.gancity.mixin;

import com.minecraft.gancity.ai.MobBehaviorAI;
import com.minecraft.gancity.GANCityMod;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.monster.*;
import net.minecraftforge.fml.ModList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.EnumSet;

/**
 * Mixin to enhance mob AI with adaptive behavior
 * Modifies attack patterns based on AI decisions
 * 
 * Compatibility:
 * - Ice and Fire: Skips dragons and mythical creatures (complex custom AI)
 */
@Mixin(Mob.class)
public abstract class MobAIEnhancementMixin {
    
    // Ice and Fire compatibility check
    private static final boolean ICE_AND_FIRE_LOADED = ModList.get().isLoaded("iceandfire");
    
    /**
     * Inject AI-enhanced behavior when mob registers goals
     * Now applies to ALL mobs, not just monsters - passive mobs learn evasion
     * MCA ENHANCED: MCA villagers get unique persistent tactical profiles
     * VANILLA ENHANCED: Vanilla villagers also get persistent profiles!
     * 
     * Compatibility: Skips Ice and Fire mobs (they have complex custom AI)
     */
    @Inject(method = "m_8099_", at = @At("TAIL"))
    private void onRegisterGoals(CallbackInfo ci) {
        Mob mob = (Mob)(Object)this;
        
        // Ice and Fire compatibility - skip their mobs entirely
        if (ICE_AND_FIRE_LOADED) {
            String entityId = mob.getType().toString();
            if (entityId.contains("iceandfire:")) {
                return; // Don't modify Ice and Fire entity AI
            }
        }
        
        // Check if this is ANY villager (MCA or vanilla)
        String className = mob.getClass().getName();
        boolean isMCAVillager = className.contains("mca.entity.VillagerEntityMCA") || 
                                className.contains("mca.entity.ai");
        boolean isVanillaVillager = mob instanceof net.minecraft.world.entity.npc.Villager;
        
        if (isMCAVillager) {
            // MCA villagers get unique combat AI (for guards, self-defense)
            // Lower priority (6) to not override MCA's core behavior
            // enableEnvironmental = false (no block breaking/pillaring)
            mob.goalSelector.addGoal(6, new AIEnhancedMeleeGoal(mob, 1.0, false, false, true));
            return;
        }
        
        if (isVanillaVillager) {
            // Vanilla villagers ALSO get persistent profiles! (works without MCA)
            // Priority 7 to not override vanilla profession behavior
            // enableEnvironmental = false (villagers don't break blocks)
            mob.goalSelector.addGoal(7, new AIEnhancedMeleeGoal(mob, 1.0, false, false, true));
            return;
        }
        
        // Add AI-enhanced combat/survival goal to ALL other mobs
        // Hostile mobs learn combat tactics, passive mobs learn evasion and survival
        if (mob instanceof Monster) {
            // Hostile mobs get aggressive AI with environmental tactics
            mob.goalSelector.addGoal(2, new AIEnhancedMeleeGoal(mob, 1.0, true, true, false));
        } else {
            // Neutral and passive mobs get survival/evasion AI (lower priority to not override core behavior)
            mob.goalSelector.addGoal(5, new AIEnhancedMeleeGoal(mob, 1.2, false, true, false));
        }
    }
    
    /**
     * AI-Enhanced Melee Attack Goal
     * Uses machine learning to select attack patterns
     * VILLAGER SUPPORT: Persistent individual profiles for MCA AND vanilla villagers
     * CRITICAL FIX: Tick throttling to prevent lag
     */
    private static class AIEnhancedMeleeGoal extends Goal {
        private final Mob mob;
        private final double speedModifier;
        private final boolean followingTargetEvenIfNotSeen;
        private final boolean enableEnvironmentalTactics;  // Block breaking, pillaring, etc.
        private final boolean isVillager;  // Persistent profile support (MCA or vanilla)
        private LivingEntity target;
        private int ticksUntilNextAction;
        private int ticksUntilNextAIUpdate;  // CRITICAL: Throttle AI decisions
        private String currentAction = "straight_charge";
        private final MobBehaviorAI behaviorAI;
        private final String mobId;  // Unique ID for this mob instance
        private String persistentProfile = null;  // Villager's permanent tactical profile (MCA or vanilla)
        private float initialMobHealth;
        private float initialTargetHealth;
        private int combatTicks = 0;
        
        private static final int AI_UPDATE_INTERVAL = 20; // AI updates every 20 ticks (1 second)
        
        public AIEnhancedMeleeGoal(Mob mob, double speedModifier, boolean followEvenIfNotSeen, 
                                   boolean enableEnvironmental, boolean isVillager) {
            this.mob = mob;
            this.speedModifier = speedModifier;
            this.followingTargetEvenIfNotSeen = followEvenIfNotSeen;
            this.enableEnvironmentalTactics = enableEnvironmental;
            this.isVillager = isVillager;
            this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
            this.behaviorAI = GANCityMod.getMobBehaviorAI();
            this.mobId = mob.getUUID().toString();
            
            // VILLAGERS: Assign permanent tactical profile on creation (MCA or vanilla)
            if (isVillager) {
                this.persistentProfile = loadOrCreatePersistentProfile();
            }
        }
        
        /**
         * Load or create a permanent tactical profile for this villager
         * Works for BOTH MCA and vanilla villagers!
         * Profile stored in mob's persistent data, survives restarts/updates
         */
        private String loadOrCreatePersistentProfile() {
            try {
                // Try to load existing profile from persistent data
                net.minecraft.nbt.CompoundTag persistentData = mob.getPersistentData();
                if (persistentData.contains("MCA_AI_Profile")) {
                    return persistentData.getString("MCA_AI_Profile");
                }
                
                // Create new random profile (one-time assignment)
                String[] profiles = {"aggressive_guard", "defensive_guard", "tactical_guard", 
                                    "cautious_defender", "berserker_guard", "strategic_defender"};
                String newProfile = profiles[mob.getRandom().nextInt(profiles.length)];
                
                // Save to persistent data (survives world reload, mod updates)
                persistentData.putString("MCA_AI_Profile", newProfile);
                
                return newProfile;
            } catch (Exception e) {
                return "defensive_guard";  // Fallback
            }
        }
        
        @Override
        public boolean canUse() {
            LivingEntity target = this.mob.getTarget();
            if (target == null) {
                return false;
            } else if (!target.isAlive()) {
                return false;
            } else {
                this.target = target;
                return true;
            }
        }
        
        @Override
        public boolean canContinueToUse() {
            LivingEntity target = this.mob.getTarget();
            if (target == null) {
                return false;
            } else if (!target.isAlive()) {
                return false;
            } else if (!this.followingTargetEvenIfNotSeen) {
                return !this.mob.getNavigation().isDone();
            } else {
                return this.mob.isWithinRestriction(target.blockPosition());
            }
        }
        
        @Override
        public void start() {
            this.mob.getNavigation().moveTo(this.target, this.speedModifier);
            this.ticksUntilNextAction = 0;
            this.ticksUntilNextAIUpdate = AI_UPDATE_INTERVAL;
            this.combatTicks = 0;
            this.initialMobHealth = mob.getHealth() / mob.getMaxHealth();
            this.initialTargetHealth = target.getHealth() / target.getMaxHealth();
            
            // Start sequence tracking for advanced ML (old system)
            behaviorAI.startCombatSequence(mobId);
            
            // Start tactical episode tracking (NEW SYSTEM)
            String mobType = mob.getType().getDescription().getString().toLowerCase();
            behaviorAI.startCombatEpisode(mobId, mobType, mob.tickCount);
            
            selectNextAction();
        }
        
        @Override
        public void stop() {
            // Combat ended - record outcome for learning
            if (this.target != null) {
                recordCombatOutcome();
                
                // End sequence tracking and submit to Cloudflare (old system)
                String mobType = mob.getType().getDescription().getString().toLowerCase();
                String outcome = determineOutcome();
                behaviorAI.endCombatSequence(mobId, mobType, outcome);
                
                // End tactical episode (NEW SYSTEM)
                boolean mobKilled = !mob.isAlive();
                boolean targetKilled = !target.isAlive();
                String playerId = (target instanceof net.minecraft.world.entity.player.Player) 
                    ? target.getUUID().toString() 
                    : "npc";
                behaviorAI.endCombatEpisode(mobId, targetKilled, mobKilled, mob.tickCount, playerId);
            }
            
            this.target = null;
            this.mob.getNavigation().stop();
            this.combatTicks = 0;
        }
        
        /**
         * Determine combat outcome for sequence analysis
         */
        private String determineOutcome() {
            if (!mob.isAlive()) {
                return "died";
            } else if (target != null && !target.isAlive()) {
                return "success";
            } else {
                return "disengaged";
            }
        }
        
        @Override
        public void tick() {
            if (this.target == null) {
                return;
            }
            
            combatTicks++;
            
            this.mob.getLookControl().setLookAt(this.target, 30.0F, 30.0F);
            
            // TACTICAL EPISODE: Sample every 10 ticks (0.5s)
            if (combatTicks % 10 == 0 && target instanceof net.minecraft.world.entity.player.Player) {
                behaviorAI.recordTacticalSample(mobId, mob, (net.minecraft.world.entity.player.Player) target, 0);
            }
            
            // CRITICAL: Throttle AI updates to every AI_UPDATE_INTERVAL ticks
            if (--this.ticksUntilNextAIUpdate <= 0) {
                // Re-evaluate action periodically
                if (--this.ticksUntilNextAction <= 0) {
                    selectNextAction();
                    this.ticksUntilNextAction = 20 + mob.getRandom().nextInt(20); // 1-2 seconds
                }
                
                this.ticksUntilNextAIUpdate = AI_UPDATE_INTERVAL;
            }
            
            // Execute current action (lightweight, can run every tick)
            executeAction();
        }
        
        /**
         * Record combat outcome for ML learning
         */
        private void recordCombatOutcome() {
            if (behaviorAI == null || target == null) return;
            
            // Build final state
            MobBehaviorAI.MobState finalState = new MobBehaviorAI.MobState(
                mob.getHealth() / mob.getMaxHealth(),
                target.getHealth() / target.getMaxHealth(),
                (float) mob.distanceTo(target)
            );
            finalState.combatTime = combatTicks / 20.0f;  // Convert to seconds
            finalState.isNight = !mob.level().isDay();
            finalState.biome = mob.level().getBiome(mob.blockPosition()).toString();
            
            // Check outcomes
            boolean mobDied = !mob.isAlive();
            boolean playerDied = !target.isAlive();
            
            // Record for learning with mob entity for attribute correlation tracking
            behaviorAI.recordCombatOutcome(mobId, playerDied, mobDied, finalState, 0.0f, 0.0f, mob);
        }
        
        /**
         * Select next action using AI with mob instance tracking
         * VILLAGERS: Use persistent profile for consistent behavior (MCA or vanilla)
         */
        private void selectNextAction() {
            if (target == null) return;
            
            // Build current state
            MobBehaviorAI.MobState state = new MobBehaviorAI.MobState(
                mob.getHealth() / mob.getMaxHealth(),
                target.getHealth() / target.getMaxHealth(),
                (float) mob.distanceTo(target)
            );
            
            state.isNight = !mob.level().isDay();
            state.biome = mob.level().getBiome(mob.blockPosition()).toString();
            state.combatTime = combatTicks / 20.0f;
            
            // Special abilities
            if (mob instanceof Spider) {
                state.canClimbWalls = true;
            }
            
            // Get mob type
            String mobType;
            if (isVillager && persistentProfile != null) {
                // Villagers (MCA or vanilla) use their permanent tactical profile
                mobType = persistentProfile;
            } else {
                // Regular mobs use class-based type
                mobType = mob.getClass().getSimpleName().toLowerCase();
            }
            
            // AI selects action with contextual difficulty (pass mob entity for environmental context)
            String previousAction = currentAction;
            currentAction = behaviorAI.selectMobActionWithEntity(mobType, state, mobId, mob);
            
            // Track action in sequence (calculate reward based on health changes)
            if (previousAction != null && !previousAction.equals(currentAction)) {
                double reward = calculateActionReward();
                behaviorAI.trackActionInSequence(mobId, previousAction, reward);
            }
        }
        
        /**
         * Calculate reward for the action just completed
         */
        private double calculateActionReward() {
            double reward = 0.0;
            
            // Positive reward for damaging target
            if (target != null) {
                float currentTargetHealth = target.getHealth() / target.getMaxHealth();
                if (currentTargetHealth < initialTargetHealth) {
                    reward += (initialTargetHealth - currentTargetHealth) * 10.0;
                    initialTargetHealth = currentTargetHealth;
                }
            }
            
            // Negative reward for taking damage
            float currentMobHealth = mob.getHealth() / mob.getMaxHealth();
            if (currentMobHealth < initialMobHealth) {
                reward -= (initialMobHealth - currentMobHealth) * 5.0;
                initialMobHealth = currentMobHealth;
            }
            
            // Small reward for survival
            reward += 0.1;
            
            return reward;
        }
        
        /**
         * Execute the selected action
         */
        private void executeAction() {
            if (target == null) return;
            
            double distance = mob.distanceTo(target);
            double baseSpeed = speedModifier;
            
            switch (currentAction) {
                case "straight_charge":
                    // Direct charge at target
                    mob.getNavigation().moveTo(target, baseSpeed * 1.2);
                    break;
                    
                case "circle_strafe":
                    // Circle around target
                    circleAroundTarget(baseSpeed);
                    break;
                    
                case "kite_backward":
                    // Move away while attacking (for ranged)
                    if (distance < 8.0) {
                        retreatFromTarget(baseSpeed * 1.1);
                    }
                    break;
                    
                case "ambush":
                    // Wait for player to get closer
                    if (distance > 5.0) {
                        mob.getNavigation().stop();
                    } else {
                        mob.getNavigation().moveTo(target, baseSpeed * 1.5);
                    }
                    break;
                    
                case "group_rush":
                case "suicide_rush":
                    // Aggressive charge
                    mob.getNavigation().moveTo(target, baseSpeed * 1.3);
                    break;
                    
                case "retreat_reload":
                case "fake_retreat":
                    // Tactical retreat
                    if (mob.getRandom().nextFloat() < 0.7f) {
                        retreatFromTarget(baseSpeed);
                    }
                    break;
                    
                // === ENVIRONMENTAL INTERACTION TACTICS (Mob Control inspired) ===
                // MCA VILLAGERS SKIP THESE (no block breaking/placing)
                case "break_cover":
                    if (enableEnvironmentalTactics) {
                        // Break blocks between mob and target
                        breakBlocksToTarget();
                    }
                    mob.getNavigation().moveTo(target, baseSpeed);
                    break;
                    
                case "pillar_up":
                    if (enableEnvironmentalTactics) {
                        // Build dirt/blocks upward when low health
                        if (mob.getHealth() / mob.getMaxHealth() < 0.3f) {
                            pillarUpEscape();
                        }
                    }
                    mob.getNavigation().moveTo(target, baseSpeed);
                    break;
                    
                case "use_terrain":
                    if (enableEnvironmentalTactics) {
                        // Climb walls, swim strategically
                        if (mob instanceof net.minecraft.world.entity.monster.Spider) {
                            // Spiders climb to vantage points
                            climbToAdvantage();
                        }
                    }
                    mob.getNavigation().moveTo(target, baseSpeed * 1.1);
                    break;
                    
                case "block_path":
                    if (enableEnvironmentalTactics) {
                        // Place blocks to obstruct player
                        if (distance > 3.0 && distance < 8.0) {
                            blockPlayerPath();
                        }
                    }
                    mob.getNavigation().moveTo(target, baseSpeed);
                    break;
                    
                default:
                    // Default behavior
                    mob.getNavigation().moveTo(target, baseSpeed);
                    break;
            }
            
            // Attack if in range
            if (distance <= mob.getBbWidth() * 2.0F + target.getBbWidth()) {
                mob.doHurtTarget(target);
            }
        }
        
        /**
         * Circle around the target
         */
        private void circleAroundTarget(double speed) {
            if (target == null) return;
            
            double angle = Math.atan2(mob.getZ() - target.getZ(), mob.getX() - target.getX());
            double circleAngle = angle + Math.PI / 4; // 45 degree offset
            
            double distance = 4.0; // Desired distance
            double targetX = target.getX() + Math.cos(circleAngle) * distance;
            double targetZ = target.getZ() + Math.sin(circleAngle) * distance;
            
            mob.getNavigation().moveTo(targetX, target.getY(), targetZ, speed);
        }
        
        /**
         * Retreat from target
         */
        private void retreatFromTarget(double speed) {
            if (target == null) return;
            
            double angle = Math.atan2(target.getZ() - mob.getZ(), target.getX() - mob.getX());
            
            double distance = 5.0;
            double targetX = mob.getX() - Math.cos(angle) * distance;
            double targetZ = mob.getZ() - Math.sin(angle) * distance;
            
            mob.getNavigation().moveTo(targetX, mob.getY(), targetZ, speed);
        }
        
        // === ENVIRONMENTAL INTERACTION METHODS (Mob Control inspired) ===
        
        /**
         * Break blocks between mob and target (destroys cover)
         * MCA PROTECTION: Avoids breaking valuable/structure blocks
         */
        private void breakBlocksToTarget() {
            if (target == null || !(mob.level() instanceof net.minecraft.server.level.ServerLevel)) return;
            
            net.minecraft.server.level.ServerLevel serverLevel = (net.minecraft.server.level.ServerLevel) mob.level();
            net.minecraft.core.BlockPos mobPos = mob.blockPosition();
            net.minecraft.core.BlockPos targetPos = target.blockPosition();
            
            // Find blocks in line of sight
            net.minecraft.world.phys.Vec3 start = mob.position();
            net.minecraft.world.phys.Vec3 end = target.position();
            net.minecraft.world.phys.BlockHitResult hit = serverLevel.clip(
                new net.minecraft.world.level.ClipContext(start, end, 
                    net.minecraft.world.level.ClipContext.Block.OUTLINE,
                    net.minecraft.world.level.ClipContext.Fluid.NONE, mob)
            );
            
            if (hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
                net.minecraft.core.BlockPos blockPos = hit.getBlockPos();
                net.minecraft.world.level.block.state.BlockState blockState = serverLevel.getBlockState(blockPos);
                
                // MCA PROTECTION: Don't break valuable blocks (chests, beds, crafting tables, etc.)
                net.minecraft.world.level.block.Block block = blockState.getBlock();
                String blockName = block.getDescriptionId();
                if (blockName.contains("chest") || blockName.contains("bed") || 
                    blockName.contains("crafting") || blockName.contains("furnace") ||
                    blockName.contains("door") || blockName.contains("_log") || 
                    blockName.contains("planks")) {
                    return; // Don't break village infrastructure
                }
                
                // Only break weak disposable blocks (dirt, grass, glass, leaves, etc.)
                if (!blockState.isAir() && blockState.getDestroySpeed(serverLevel, blockPos) < 5.0f) {
                    serverLevel.destroyBlock(blockPos, true, mob);
                }
            }
        }
        
        /**
         * Pillar up using blocks (escape tactic)
         */
        private void pillarUpEscape() {
            if (!(mob.level() instanceof net.minecraft.server.level.ServerLevel)) return;
            
            net.minecraft.server.level.ServerLevel serverLevel = (net.minecraft.server.level.ServerLevel) mob.level();
            net.minecraft.core.BlockPos below = mob.blockPosition().below();
            net.minecraft.world.level.block.state.BlockState belowState = serverLevel.getBlockState(below);
            
            // Place dirt block below to pillar up
            if (belowState.isAir() && mob.getRandom().nextFloat() < 0.1f) {
                serverLevel.setBlock(below, net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState(), 3);
            }
        }
        
        /**
         * Climb to advantageous position (spiders)
         */
        private void climbToAdvantage() {
            if (target == null) return;
            
            // Find higher ground near target
            net.minecraft.core.BlockPos targetPos = target.blockPosition().above(3);
            mob.getNavigation().moveTo(targetPos.getX(), targetPos.getY(), targetPos.getZ(), speedModifier * 1.2);
        }
        
        /**
         * Block player's path with obstacles
         */
        private void blockPlayerPath() {
            if (target == null || !(mob.level() instanceof net.minecraft.server.level.ServerLevel)) return;
            
            net.minecraft.server.level.ServerLevel serverLevel = (net.minecraft.server.level.ServerLevel) mob.level();
            
            // Calculate position between mob and player
            double midX = (mob.getX() + target.getX()) / 2;
            double midZ = (mob.getZ() + target.getZ()) / 2;
            net.minecraft.core.BlockPos midPos = new net.minecraft.core.BlockPos((int)midX, (int)target.getY(), (int)midZ);
            
            // Place cobblestone if air
            if (serverLevel.getBlockState(midPos).isAir() && mob.getRandom().nextFloat() < 0.05f) {
                serverLevel.setBlock(midPos, net.minecraft.world.level.block.Blocks.COBBLESTONE.defaultBlockState(), 3);
            }
        }
    }
}
