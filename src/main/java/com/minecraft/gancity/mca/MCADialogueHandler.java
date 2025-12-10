package com.minecraft.gancity.mca;

import com.minecraft.gancity.GANCityMod;
import com.minecraft.gancity.ai.VillagerDialogueAI;
import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Handles MCA villager dialogue interactions
 * Hooks into MCA's dialogue system to inject our AI-generated responses
 */
@Mod.EventBusSubscriber(modid = GANCityMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class MCADialogueHandler {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    /**
     * Intercept player interactions with MCA villagers
     * This fires when player right-clicks an entity
     */
    @SubscribeEvent
    public static void onPlayerInteractEntity(PlayerInteractEvent.EntityInteract event) {
        if (!MCAIntegration.isMCALoaded()) {
            return;
        }
        
        Entity target = event.getTarget();
        
        // Check if target is an MCA villager using reflection
        if (isMCAVillager(target)) {
            try {
                // Get villager UUID
                UUID villagerId = target.getUUID();
                
                // Create dialogue context
                VillagerDialogueAI.DialogueContext context = new VillagerDialogueAI.DialogueContext("greeting");
                context.playerName = event.getEntity().getName().getString();
                context.playerId = event.getEntity().getUUID();
                
                // Try to get villager name via reflection
                try {
                    Method getNameMethod = target.getClass().getMethod("getVillagerName");
                    Object name = getNameMethod.invoke(target);
                    if (name != null) {
                        context.villagerName = name.toString();
                    }
                } catch (Exception ignored) {
                    context.villagerName = "Villager";
                }
                
                // Generate AI dialogue
                VillagerDialogueAI dialogueAI = GANCityMod.getVillagerDialogueAI();
                String dialogue = dialogueAI.generateDialogue(villagerId, context);
                
                // Try to inject dialogue into MCA's system via reflection
                injectDialogueIntoMCA(target, dialogue);
                
                LOGGER.debug("Generated dialogue for MCA villager {}: {}", villagerId, dialogue);
                
            } catch (Exception e) {
                LOGGER.warn("Failed to generate dialogue for MCA villager: {}", e.getMessage());
            }
        }
    }
    
    /**
     * Check if entity is an MCA villager using reflection
     */
    private static boolean isMCAVillager(Entity entity) {
        try {
            Class<?> mcaVillagerClass = Class.forName("mca.entity.VillagerEntityMCA");
            return mcaVillagerClass.isInstance(entity);
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
    
    /**
     * Inject our dialogue into MCA's dialogue system using reflection
     * This attempts to override MCA's dialogue text before it's displayed
     */
    private static void injectDialogueIntoMCA(Entity villager, String dialogue) {
        try {
            // MCA 7.5+ uses a dialogue manager system
            // Try to find and call the setDialogue or similar method
            
            // Attempt 1: Direct dialogue field
            try {
                var field = villager.getClass().getDeclaredField("currentDialogue");
                field.setAccessible(true);
                field.set(villager, dialogue);
                LOGGER.debug("Injected dialogue via currentDialogue field");
                return;
            } catch (NoSuchFieldException ignored) {}
            
            // Attempt 2: Dialogue manager
            try {
                var managerField = villager.getClass().getDeclaredField("dialogueManager");
                managerField.setAccessible(true);
                Object manager = managerField.get(villager);
                
                Method setMethod = manager.getClass().getMethod("setDialogue", String.class);
                setMethod.invoke(manager, dialogue);
                LOGGER.debug("Injected dialogue via dialogueManager");
                return;
            } catch (Exception ignored) {}
            
            // Attempt 3: Set via method
            try {
                Method setDialogueMethod = villager.getClass().getMethod("setDialogue", String.class);
                setDialogueMethod.invoke(villager, dialogue);
                LOGGER.debug("Injected dialogue via setDialogue method");
                return;
            } catch (NoSuchMethodException ignored) {}
            
            LOGGER.debug("Could not inject dialogue - MCA API may have changed");
            
        } catch (Exception e) {
            LOGGER.debug("Dialogue injection failed: {}", e.getMessage());
        }
    }
}
