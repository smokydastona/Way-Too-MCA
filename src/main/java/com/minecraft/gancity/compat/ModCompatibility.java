package com.minecraft.gancity.compat;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

import java.util.*;

/**
 * Mod compatibility manager - detects and integrates with popular mods
 * Provides soft dependencies and enhanced features when compatible mods are present
 */
public class ModCompatibility {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    // Compatibility flags
    private static boolean curiosLoaded = false;
    private static boolean ftbTeamsLoaded = false;
    private static boolean jeiLoaded = false;
    private static boolean epicFightLoaded = false;
    private static boolean playerAnimatorLoaded = false;
    private static boolean sophisticatedBackpacksLoaded = false;
    private static boolean alexsMobsLoaded = false;
    
    // Mod IDs
    public static final String CURIOS_ID = "curios";
    public static final String FTB_TEAMS_ID = "ftbteams";
    public static final String JEI_ID = "jei";
    public static final String EPIC_FIGHT_ID = "epicfight";
    public static final String PLAYER_ANIMATOR_ID = "playeranimator";
    public static final String SOPHISTICATED_BACKPACKS_ID = "sophisticatedbackpacks";
    public static final String ALEXS_MOBS_ID = "alexsmobs";
    
    /**
     * Initialize mod compatibility checks
     */
    public static void init() {
        LOGGER.info("Checking for compatible mods...");
        
        curiosLoaded = checkMod(CURIOS_ID, "Curios API");
        ftbTeamsLoaded = checkMod(FTB_TEAMS_ID, "FTB Teams");
        jeiLoaded = checkMod(JEI_ID, "Just Enough Items");
        epicFightLoaded = checkMod(EPIC_FIGHT_ID, "Epic Fight");
        playerAnimatorLoaded = checkMod(PLAYER_ANIMATOR_ID, "Player Animator");
        sophisticatedBackpacksLoaded = checkMod(SOPHISTICATED_BACKPACKS_ID, "Sophisticated Backpacks");
        alexsMobsLoaded = checkMod(ALEXS_MOBS_ID, "Alex's Mobs");

        // Initialize integrations (soft dependencies)
        if (curiosLoaded) {
            CuriosIntegration.init();
        }
        if (ftbTeamsLoaded) {
            FTBTeamsIntegration.init();
        }
        if (epicFightLoaded) {
            EpicFightIntegration.init();
        }
        
        logCompatibilitySummary();
    }
    
    /**
     * Check if a mod is loaded
     */
    private static boolean checkMod(String modId, String modName) {
        boolean loaded = ModList.get().isLoaded(modId);
        if (loaded) {
            LOGGER.info("✓ {} detected", modName);
        }
        return loaded;
    }
    
    /**
     * Log compatibility summary
     */
    private static void logCompatibilitySummary() {
        int compatibleMods = 0;
        if (curiosLoaded) compatibleMods++;
        if (ftbTeamsLoaded) compatibleMods++;
        if (jeiLoaded) compatibleMods++;
        if (epicFightLoaded) compatibleMods++;
        if (playerAnimatorLoaded) compatibleMods++;
        if (sophisticatedBackpacksLoaded) compatibleMods++;
        if (alexsMobsLoaded) compatibleMods++;
        
        LOGGER.info("Mod compatibility: {}/7 compatible mods detected", compatibleMods);
        
        if (compatibleMods == 0) {
            LOGGER.info("Running standalone - all AI features available");
        } else {
            LOGGER.info("Enhanced mode - additional features enabled through mod integration");
        }
    }
    
    // Getters for compatibility checks
    
    public static boolean isCuriosLoaded() {
        return curiosLoaded;
    }
    
    public static boolean isFTBTeamsLoaded() {
        return ftbTeamsLoaded;
    }
    
    public static boolean isJEILoaded() {
        return jeiLoaded;
    }
    
    public static boolean isEpicFightLoaded() {
        return epicFightLoaded;
    }
    
    public static boolean isPlayerAnimatorLoaded() {
        return playerAnimatorLoaded;
    }
    
    public static boolean isSophisticatedBackpacksLoaded() {
        return sophisticatedBackpacksLoaded;
    }
    
    public static boolean isAlexsMobsLoaded() {
        return alexsMobsLoaded;
    }
    
    /**
     * Get list of all detected compatible mods
     */
    public static List<String> getCompatibleMods() {
        List<String> mods = new ArrayList<>();
        if (curiosLoaded) mods.add("Curios API");
        if (ftbTeamsLoaded) mods.add("FTB Teams");
        if (jeiLoaded) mods.add("Just Enough Items");
        if (epicFightLoaded) mods.add("Epic Fight");
        if (playerAnimatorLoaded) mods.add("Player Animator");
        if (sophisticatedBackpacksLoaded) mods.add("Sophisticated Backpacks");
        if (alexsMobsLoaded) mods.add("Alex's Mobs");
        return mods;
    }
    
    /**
     * Get compatibility report for debugging
     */
    public static String getCompatibilityReport() {
        StringBuilder report = new StringBuilder();
        report.append("§b=== Mod Compatibility Report ===§r\n");
        report.append("\n");
        
        appendModStatus(report, "Curios API", curiosLoaded, 
            "Enhanced equipment detection in visual perception system");
        appendModStatus(report, "FTB Teams", ftbTeamsLoaded,
            "Multi-player team coordination for mob AI");
        appendModStatus(report, "Just Enough Items", jeiLoaded,
            "Recipe integration for villager dialogue context");
        appendModStatus(report, "Epic Fight", epicFightLoaded,
            "Basic integration: combat mode + stamina/charging detection");
        appendModStatus(report, "Player Animator", playerAnimatorLoaded,
            "Enhanced player action detection");
        appendModStatus(report, "Sophisticated Backpacks", sophisticatedBackpacksLoaded,
            "Inventory-aware mob tactics");
        appendModStatus(report, "Alex's Mobs", alexsMobsLoaded,
            "Extended mob behavior patterns");
        
        return report.toString();
    }
    
    private static void appendModStatus(StringBuilder report, String modName, boolean loaded, String feature) {
        String status = loaded ? "§a✓ Enabled§r" : "§7○ Not Installed§r";
        report.append(String.format("§e%s:§r %s\n", modName, status));
        if (loaded) {
            report.append(String.format("  → %s\n", feature));
        }
        report.append("\n");
    }
}
