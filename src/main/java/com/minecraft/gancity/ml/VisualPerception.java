package com.minecraft.gancity.ml;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.EquipmentSlot;
import org.slf4j.Logger;

import java.util.*;

/**
 * Visual perception system - mobs recognize player equipment and tactics
 * Adapts strategy based on what player is wearing/holding
 */
public class VisualPerception {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    private final Map<String, PlayerProfile> playerProfiles = new HashMap<>();
    
    /**
     * Analyze player visual state
     */
    public VisualState analyzePlayer(Player player) {
        VisualState state = new VisualState();
        
        // Armor analysis
        ItemStack helmet = player.getItemBySlot(EquipmentSlot.HEAD);
        ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
        ItemStack legs = player.getItemBySlot(EquipmentSlot.LEGS);
        ItemStack boots = player.getItemBySlot(EquipmentSlot.FEET);
        
        state.armorLevel = calculateArmorLevel(helmet, chest, legs, boots);
        state.hasShield = player.getOffhandItem().is(Items.SHIELD);
        
        // Weapon analysis
        ItemStack mainHand = player.getMainHandItem();
        state.weaponType = determineWeaponType(mainHand);
        state.weaponTier = determineWeaponTier(mainHand);
        state.hasRangedWeapon = isRangedWeapon(mainHand);
        
        // Tactical indicators
        state.isSprinting = player.isSprinting();
        state.isSneaking = player.isCrouching();
        state.isBlocking = player.isBlocking();
        
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
        
        public float[] toFeatureVector() {
            return new float[] {
                armorLevel,
                hasShield ? 1.0f : 0.0f,
                weaponTier / 5.0f,
                hasRangedWeapon ? 1.0f : 0.0f,
                isSprinting ? 1.0f : 0.0f,
                isSneaking ? 1.0f : 0.0f,
                isBlocking ? 1.0f : 0.0f
            };
        }
    }
    
    private static class PlayerProfile {
        int encounterCount = 0;
        String preferredWeapon = "unknown";
        Map<String, Integer> commonActions = new HashMap<>();
        int aggressiveStyle = 0;
        int cautiousStyle = 0;
    }
}
