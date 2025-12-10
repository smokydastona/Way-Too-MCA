package com.minecraft.gancity;

import com.minecraft.gancity.ai.MobBehaviorAI;
import com.minecraft.gancity.ai.VillagerDialogueAI;
import com.minecraft.gancity.command.GANCityCommand;
import com.minecraft.gancity.compat.ModCompatibility;
import com.minecraft.gancity.compat.CuriosIntegration;
import com.minecraft.gancity.compat.FTBTeamsIntegration;
import com.minecraft.gancity.mca.MCAIntegration;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(GANCityMod.MODID)
@Mod.EventBusSubscriber(modid = GANCityMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.DEDICATED_SERVER)
public class GANCityMod {
    public static final String MODID = "gancity";
    private static final Logger LOGGER = LogUtils.getLogger();
    
    private static MobBehaviorAI mobBehaviorAI;
    private static VillagerDialogueAI villagerDialogueAI;
    
    // Auto-save tracking (10 minutes = 12000 ticks)
    private static final int AUTO_SAVE_INTERVAL_TICKS = 12000;
    private static int tickCounter = 0;
    private static long lastSaveTime = 0;

    public GANCityMod() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::commonSetup);
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("MCA AI Enhanced - Initializing AI systems (SERVER-ONLY)...");
        
        // Initialize mod compatibility system (async for performance)
        event.enqueueWork(() -> {
            ModCompatibility.init();
            CuriosIntegration.init();
            FTBTeamsIntegration.init();
        });
        
        // Initialize AI systems (lazy - only when first needed)
        // mobBehaviorAI and villagerDialogueAI initialized on first access
        
        // Check if MCA Reborn is loaded
        boolean mcaLoaded = ModList.get().isLoaded("mca");
        MCAIntegration.setMCALoaded(mcaLoaded);
        
        if (mcaLoaded) {
            LOGGER.info("MCA AI Enhanced - MCA Reborn detected! Enhanced villager AI enabled.");
        } else {
            LOGGER.warn("MCA AI Enhanced - MCA Reborn not found. Villager dialogue features disabled.");
        }
        
        LOGGER.info("MCA AI Enhanced - Mob behavior AI initialized with {} mob types", 
            mobBehaviorAI != null ? "multiple" : "0");
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("MCA AI Enhanced - Server starting with AI enhancements");
        
        // Load config and initialize federated learning if enabled
        initializeFederatedLearning();
    }
    
    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        LOGGER.info("MCA AI Enhanced - Server stopping, saving ML models...");
        if (mobBehaviorAI != null) {
            mobBehaviorAI.saveModel();
        }
    }
    
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        
        tickCounter++;
        
        // Auto-save every 10 minutes (12000 ticks)
        if (tickCounter >= AUTO_SAVE_INTERVAL_TICKS) {
            tickCounter = 0;
            performAutoSave();
        }
    }
    
    private void performAutoSave() {
        if (mobBehaviorAI == null) {
            return;
        }
        
        long currentTime = System.currentTimeMillis();
        long timeSinceLastSave = (currentTime - lastSaveTime) / 1000; // seconds
        
        LOGGER.info("═══════════════════════════════════════════════════════");
        LOGGER.info("[AUTO-SAVE] Starting periodic save (last save: {}s ago)", timeSinceLastSave);
        LOGGER.info("═══════════════════════════════════════════════════════");
        
        try {
            // 1. Save models locally
            LOGGER.info("[AUTO-SAVE] Step 1/2: Saving ML models locally...");
            mobBehaviorAI.saveModel();
            LOGGER.info("[AUTO-SAVE] ✓ Local models saved");
            
            // 2. Sync with Cloudflare (upload + download)
            LOGGER.info("[AUTO-SAVE] Step 2/2: Syncing with Cloudflare...");
            mobBehaviorAI.syncWithCloudflare();
            LOGGER.info("[AUTO-SAVE] ✓ Cloudflare sync completed");
            
            lastSaveTime = currentTime;
            LOGGER.info("═══════════════════════════════════════════════════════");
            LOGGER.info("[AUTO-SAVE] ✓ All operations completed successfully!");
            LOGGER.info("═══════════════════════════════════════════════════════");
        } catch (Exception e) {
            LOGGER.error("═══════════════════════════════════════════════════════");
            LOGGER.error("[AUTO-SAVE] ✗ Failed: {}", e.getMessage());
            LOGGER.error("═══════════════════════════════════════════════════════");
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        GANCityCommand.register(event.getDispatcher());
        LOGGER.info("MCA AI Enhanced - Commands registered");
    }

    public static MobBehaviorAI getMobBehaviorAI() {
        if (mobBehaviorAI == null) {
            synchronized (GANCityMod.class) {
                if (mobBehaviorAI == null) {
                    LOGGER.info("Lazy-initializing MobBehaviorAI...");
                    mobBehaviorAI = new MobBehaviorAI();
                    
                    // Load cross-mob learning configuration (default enabled)
                    try {
                        // Default: cross-mob learning enabled with 3x reward multiplier
                        boolean crossMobEnabled = true;
                        float crossMobMultiplier = 3.0f;
                        mobBehaviorAI.setCrossMobLearning(crossMobEnabled, crossMobMultiplier);
                        
                        LOGGER.info("✓ Cross-Mob Emergent Learning ENABLED - Mobs can learn tactics from any species!");
                        LOGGER.info("  Reward multiplier for borrowed tactics: {}x", crossMobMultiplier);
                    } catch (Exception e) {
                        LOGGER.warn("Could not enable cross-mob learning: {}", e.getMessage());
                    }
                    
                    // Load contextual difficulty configuration (default enabled)
                    try {
                        // Default: contextual difficulty enabled
                        boolean contextualEnabled = true;
                        mobBehaviorAI.setContextualDifficulty(contextualEnabled);
                        
                        if (contextualEnabled) {
                            LOGGER.info("✓ Contextual AI Difficulty ENABLED - Harder at night/storms/structures/dimensions");
                            LOGGER.info("  Night: +30% | Storms: +20% | Thunderstorms: +50%");
                            LOGGER.info("  Nether: +60% | End: +100% | Near Structures: +40%");
                        }
                    } catch (Exception e) {
                        LOGGER.warn("Could not enable contextual difficulty: {}", e.getMessage());
                    }
                }
            }
        }
        return mobBehaviorAI;
    }
    
    public static VillagerDialogueAI getVillagerDialogueAI() {
        if (villagerDialogueAI == null) {
            synchronized (GANCityMod.class) {
                if (villagerDialogueAI == null) {
                    LOGGER.info("Lazy-initializing VillagerDialogueAI...");
                    villagerDialogueAI = new VillagerDialogueAI();
                }
            }
        }
        return villagerDialogueAI;
    }

    public static ResourceLocation id(String path) {
        return new ResourceLocation(MODID, path);
    }
    
    /**
     * Initialize federated learning from config
     */
    private void initializeFederatedLearning() {
        try {
            // Read config (simple properties-based for now)
            java.nio.file.Path configPath = java.nio.file.Paths.get("config", "mca-ai-enhanced-common.toml");
            
            if (!java.nio.file.Files.exists(configPath)) {
                LOGGER.info("Config file not found, creating default config...");
                createDefaultConfig(configPath);
            }
            
            // Parse TOML config
            java.util.List<String> lines = java.nio.file.Files.readAllLines(configPath);
            boolean federatedEnabled = true; // Default enabled with hardcoded repo
            String repoUrl = "https://github.com/smokydastona/Mob-Knowledge.git"; // Hardcoded default
            String apiEndpoint = "";
            String apiKey = "";
            
            for (String line : lines) {
                line = line.trim();
                if (line.contains("enableFederatedLearning") && line.contains("true")) {
                    federatedEnabled = true;
                } else if (line.contains("federatedRepoUrl")) {
                    String[] parts = line.split("=");
                    if (parts.length > 1) {
                        repoUrl = parts[1].trim().replace("\"", "").replace("'", "");
                    }
                } else if (line.contains("cloudApiEndpoint")) {
                    String[] parts = line.split("=");
                    if (parts.length > 1) {
                        apiEndpoint = parts[1].trim().replace("\"", "").replace("'", "");
                    }
                } else if (line.contains("cloudApiKey")) {
                    String[] parts = line.split("=");
                    if (parts.length > 1) {
                        apiKey = parts[1].trim().replace("\"", "").replace("'", "");
                    }
                }
            }
            
            if (federatedEnabled && (!repoUrl.isEmpty() || !apiEndpoint.isEmpty())) {
                LOGGER.info("Enabling federated learning...");
                LOGGER.info("  Repository: {}", repoUrl.isEmpty() ? "None" : repoUrl);
                LOGGER.info("  Cloud API: {}", apiEndpoint.isEmpty() ? "None" : apiEndpoint);
                
                MobBehaviorAI ai = getMobBehaviorAI();
                ai.enableFederatedLearning(
                    repoUrl.isEmpty() ? null : repoUrl,
                    apiEndpoint.isEmpty() ? null : apiEndpoint,
                    apiKey.isEmpty() ? null : apiKey
                );
                
                // Test Cloudflare connection
                if (!apiEndpoint.isEmpty()) {
                    LOGGER.info("Testing Cloudflare Worker connection...");
                    boolean connected = ai.testCloudflareConnection();
                    if (connected) {
                        LOGGER.info("✓ Cloudflare Worker connected successfully!");
                    } else {
                        LOGGER.warn("⚠ Cloudflare Worker connection failed - running in offline mode");
                    }
                }
                
                LOGGER.info("✓ Federated learning enabled - Global AI knowledge sharing active!");
            } else {
                LOGGER.info("Federated learning disabled in config");
            }
            
        } catch (Exception e) {
            LOGGER.error("Failed to initialize federated learning: {}", e.getMessage());
        }
    }
    
    /**
     * Create default config file
     */
    private void createDefaultConfig(java.nio.file.Path configPath) throws java.io.IOException {
        // Create config directory if it doesn't exist
        java.nio.file.Files.createDirectories(configPath.getParent());
        
        // Copy from resources to config directory
        try (java.io.InputStream inputStream = getClass().getResourceAsStream("/mca-ai-enhanced-common.toml")) {
            if (inputStream != null) {
                java.nio.file.Files.copy(inputStream, configPath);
                LOGGER.info("Created default config at {}", configPath);
            } else {
                LOGGER.error("Could not find default config in resources");
            }
        }
    }
}
