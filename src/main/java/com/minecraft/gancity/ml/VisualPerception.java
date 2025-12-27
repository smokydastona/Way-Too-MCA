package com.minecraft.gancity.ml;

import com.minecraft.gancity.compat.CuriosIntegration;
import com.minecraft.gancity.compat.EpicFightIntegration;
import com.minecraft.gancity.compat.ModCompatibility;
import net.minecraft.world.entity.player.Player;
import java.util.UUID;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.EquipmentSlot;

import java.util.*;

/**
 * Visual perception system - mobs recognize player equipment and tactics
 * Adapts strategy based on what player is wearing/holding
 */
@SuppressWarnings({"null", "unused"})
public class VisualPerception {
    
    private final Map<String, PlayerProfile> playerProfiles = new HashMap<>();
    private final Map<UUID, CachedVisualState> visualCache = new HashMap<>();
    private static final long CACHE_DURATION_MS = 500; // Cache for 500ms
    private static final int MAX_CACHE_SIZE = 100; // Prevent memory bloat
    
    /**
     * Analyze player visual state (with caching)
     */
    public VisualState analyzePlayer(Player player) {
        UUID playerId = player.getUUID();
        long currentTime = System.currentTimeMillis();
        
        // Check cache first
        CachedVisualState cached = visualCache.get(playerId);
        if (cached != null && (currentTime - cached.timestamp) < CACHE_DURATION_MS) {
            return cached.state;
        }
        
        // Evict old cache entries if needed
        if (visualCache.size() > MAX_CACHE_SIZE) {
            visualCache.entrySet().removeIf(entry -> 
                (currentTime - entry.getValue().timestamp) > CACHE_DURATION_MS * 2
            );
        }
        
        VisualState state = new VisualState();
        
        // Armor analysis
        ItemStack helmet = player.getItemBySlot(EquipmentSlot.HEAD);
        ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
        ItemStack legs = player.getItemBySlot(EquipmentSlot.LEGS);
        ItemStack boots = player.getItemBySlot(EquipmentSlot.FEET);
        
        state.armorLevel = calculateArmorLevel(helmet, chest, legs, boots);
        state.hasShield = player.getOffhandItem().is(Items.SHIELD);
        
        // Curios API integration - check for trinkets/baubles
        if (ModCompatibility.isCuriosLoaded()) {
            state.hasMagicalTrinkets = CuriosIntegration.hasMagicalTrinkets(player);
            state.curioEnhancement = CuriosIntegration.getCurioEnhancementFactor(player);
            
            // Adjust armor level if player has protective curios
            float curioProtection = CuriosIntegration.getTotalProtectionWithCurios(player);
            state.armorLevel = Math.max(state.armorLevel, curioProtection / 20.0f);
        }
        
        // Weapon analysis
        ItemStack mainHand = player.getMainHandItem();
        state.weaponType = determineWeaponType(mainHand);
        state.weaponTier = determineWeaponTier(mainHand);
        state.hasRangedWeapon = isRangedWeapon(mainHand);
        
        // Tactical indicators
        state.isSprinting = player.isSprinting();
        state.isSneaking = player.isCrouching();
        state.isBlocking = player.isBlocking();

        // Epic Fight integration (soft dependency)
        if (ModCompatibility.isEpicFightLoaded()) {
            EpicFightIntegration.CombatState ef = EpicFightIntegration.getCombatState(player);
            if (ef != null) {
                state.epicFightDetected = true;
                state.epicFightMode = ef.epicFightMode();
                state.epicFightStaminaRatio = ef.staminaRatio();
                state.epicFightHoldingSkill = ef.holdingAny();
                state.epicFightChargeRatio = ef.chargeRatio();
                state.epicFightTicksSinceLastAction = ef.ticksSinceLastAction();
            }
        }
        
        // Cache result
        visualCache.put(playerId, new CachedVisualState(state, currentTime));
        
        return state;
    }
    
    /**
     * Get tactical recommendations based on visual analysis
     */
    public List<String> getRecommendedActions(VisualState visual) {
        List<String> recommendations = new ArrayList<>();
        
        // Counter heavily armored players
        if (visual.armorLevel > 0.8f) {
            recommendations.add("group_rush");  // Overwhelm with numbers
            recommendations.add("strafe_shoot"); // Wear down from range
        }
        
        // Counter ranged weapons
        if (visual.hasRangedWeapon) {
            recommendations.add("circle_strafe");  // Harder to hit
            recommendations.add("ambush");         // Close distance quickly
        }
        
        // Counter shield users
        if (visual.hasShield) {
            recommendations.add("flank_attack");   // Attack from sides
            recommendations.add("group_rush");     // Can't block multiple
        }
        
        // Counter melee weapons
        if (visual.weaponType.equals("melee")) {
            recommendations.add("kite_backward");  // Maintain distance
            recommendations.add("hit_and_run");    // Quick strikes
        }
        
        // Exploit vulnerable states
        if (visual.isSprinting) {
            recommendations.add("ambush");         // Catch off guard
        }
        
        if (visual.isSneaking) {
            recommendations.add("surround");       // Can't see behind
        }

        // Epic Fight: if the target is in Epic Fight mode and actively charging/holding,
        // bias toward spacing and punishing low stamina.
        if (visual.epicFightDetected && visual.epicFightMode) {
            if (visual.epicFightHoldingSkill || visual.epicFightChargeRatio > 0.2f) {
                recommendations.add("hit_and_run");
                recommendations.add("kite_backward");
            }

            if (visual.epicFightStaminaRatio >= 0.0f && visual.epicFightStaminaRatio < 0.25f) {
                recommendations.add("group_rush");
            }
        }
        
        return recommendations;
    }
    
    /**
     * Track player combat patterns over time
     */
    public void updatePlayerProfile(String playerId, VisualState state, String playerAction) {
        PlayerProfile profile = playerProfiles.computeIfAbsent(playerId, k -> new PlayerProfile());
        
        profile.encounterCount++;
        profile.preferredWeapon = state.weaponType;
        profile.commonActions.merge(playerAction, 1, Integer::sum);
        
        // Detect patterns
        if (state.isSprinting) {
            profile.aggressiveStyle++;
        } else if (state.isSneaking) {
            profile.cautiousStyle++;
        }
    }
    
    /**
     * Get player-specific counter-strategy
     */
    public String getCounterStrategy(String playerId) {
        PlayerProfile profile = playerProfiles.get(playerId);
        if (profile == null || profile.encounterCount < 5) {
            return "adaptive";  // Not enough data
        }
        
        // Counter aggressive players
        if (profile.aggressiveStyle > profile.cautiousStyle * 2) {
            return "defensive_trap";
        }
        
        // Counter cautious players
        if (profile.cautiousStyle > profile.aggressiveStyle * 2) {
            return "aggressive_rush";
        }
        
        return "balanced";
    }
    
    private float calculateArmorLevel(ItemStack... armor) {
        int totalProtection = 0;
        int maxProtection = 20;  // Full diamond/netherite
        
        for (ItemStack piece : armor) {
            if (piece.isEmpty()) continue;
            
            if (piece.is(Items.NETHERITE_HELMET) || piece.is(Items.DIAMOND_HELMET)) totalProtection += 3;
            else if (piece.is(Items.IRON_HELMET) || piece.is(Items.GOLDEN_HELMET)) totalProtection += 2;
            
            if (piece.is(Items.NETHERITE_CHESTPLATE) || piece.is(Items.DIAMOND_CHESTPLATE)) totalProtection += 8;
            else if (piece.is(Items.IRON_CHESTPLATE)) totalProtection += 6;
            
            if (piece.is(Items.NETHERITE_LEGGINGS) || piece.is(Items.DIAMOND_LEGGINGS)) totalProtection += 6;
            else if (piece.is(Items.IRON_LEGGINGS)) totalProtection += 5;
            
            if (piece.is(Items.NETHERITE_BOOTS) || piece.is(Items.DIAMOND_BOOTS)) totalProtection += 3;
            else if (piece.is(Items.IRON_BOOTS)) totalProtection += 2;
        }
        
        return (float) totalProtection / maxProtection;
    }
    
    private String determineWeaponType(ItemStack weapon) {
        if (weapon.is(Items.BOW) || weapon.is(Items.CROSSBOW)) return "ranged";
        if (weapon.is(Items.TRIDENT)) return "throwable";
        if (weapon.getItem().toString().contains("sword") || 
            weapon.getItem().toString().contains("axe")) return "melee";
        return "unarmed";
    }
    
    private int determineWeaponTier(ItemStack weapon) {
        String item = weapon.getItem().toString();
        if (item.contains("netherite")) return 5;
        if (item.contains("diamond")) return 4;
        if (item.contains("iron")) return 3;
        if (item.contains("stone")) return 2;
        if (item.contains("wood")) return 1;
        return 0;
    }
    
    private boolean isRangedWeapon(ItemStack weapon) {
        return weapon.is(Items.BOW) || weapon.is(Items.CROSSBOW);
    }
    
    public static class VisualState {
        public float armorLevel = 0.0f;
        public boolean hasShield = false;
        public String weaponType = "unarmed";
        public int weaponTier = 0;
        public boolean hasRangedWeapon = false;
        public boolean isSprinting = false;
        public boolean isSneaking = false;
        public boolean isBlocking = false;
        
        // Curios integration fields
        public boolean hasMagicalTrinkets = false;
        public float curioEnhancement = 1.0f;

        // Epic Fight integration fields (do not change ML feature vector size)
        public boolean epicFightDetected = false;
        public boolean epicFightMode = false;
        public boolean epicFightHoldingSkill = false;
        public float epicFightStaminaRatio = -1.0f;
        public float epicFightChargeRatio = 0.0f;
        public int epicFightTicksSinceLastAction = 0;
        
        public float[] toFeatureVector() {
            return new float[] {
                armorLevel,
                hasShield ? 1.0f : 0.0f,
                weaponTier / 5.0f,
                hasRangedWeapon ? 1.0f : 0.0f,
                isSprinting ? 1.0f : 0.0f,
                isSneaking ? 1.0f : 0.0f,
                isBlocking ? 1.0f : 0.0f,
                hasMagicalTrinkets ? 1.0f : 0.0f,
                (curioEnhancement - 1.0f) / 0.3f  // Normalize 1.0-1.3 to 0.0-1.0
            };
        }
    }
    
    private static class PlayerProfile {
        int encounterCount = 0;
        @SuppressWarnings("unused")
        String preferredWeapon = "unknown";
        Map<String, Integer> commonActions = new HashMap<>();
        int aggressiveStyle = 0;
        int cautiousStyle = 0;
    }
    
    private static class CachedVisualState {
        final VisualState state;
        final long timestamp;
        
        CachedVisualState(VisualState state, long timestamp) {
            this.state = state;
            this.timestamp = timestamp;
        }
    }
}
