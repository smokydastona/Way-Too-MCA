package com.minecraft.gancity.mca;

import com.minecraft.gancity.GANCityMod;
import com.minecraft.gancity.ai.VillagerDialogueAI;
import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Handles chat-based conversations with MCA villagers
 * Players can type messages and nearby villagers respond
 */
@Mod.EventBusSubscriber(modid = GANCityMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class VillagerChatHandler {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    // Track which player is talking to which villager
    private static final Map<UUID, UUID> activeConversations = new HashMap<>();
    private static final Map<UUID, Long> lastInteractionTime = new HashMap<>();
    
    private static final long CONVERSATION_TIMEOUT = 30000; // 30 seconds
    private static final double HEARING_RANGE = 8.0; // blocks
    
    /**
     * Listen for chat messages and check if player is talking to a villager
     */
    @SubscribeEvent
    public static void onServerChat(ServerChatEvent event) {
        if (!MCAIntegration.isMCALoaded()) {
            return;
        }
        
        ServerPlayer player = event.getPlayer();
        String message = event.getMessage().getString();
        UUID playerId = player.getUUID();
        
        // Check if player has an active conversation
        UUID activeVillager = activeConversations.get(playerId);
        
        if (activeVillager != null) {
            // Check if conversation timed out
            Long lastTime = lastInteractionTime.get(playerId);
            if (lastTime != null && System.currentTimeMillis() - lastTime > CONVERSATION_TIMEOUT) {
                activeConversations.remove(playerId);
                player.sendSystemMessage(Component.literal("§7[The villager stopped listening]"));
                return;
            }
            
            // Find the villager entity
            Entity villagerEntity = player.serverLevel().getEntity(activeVillager);
            if (villagerEntity != null && villagerEntity.distanceTo(player) <= HEARING_RANGE) {
                // Generate response
                respondToPlayer(player, villagerEntity, message);
                lastInteractionTime.put(playerId, System.currentTimeMillis());
                
                // Cancel the chat event so message doesn't broadcast
                event.setCanceled(true);
                
                // Show what player said
                player.sendSystemMessage(Component.literal("§e" + player.getName().getString() + ": §f" + message));
                return;
            } else {
                activeConversations.remove(playerId);
                player.sendSystemMessage(Component.literal("§7[The villager is too far away]"));
            }
        }
        
        // Check if player is starting a conversation with nearby villager
        if (message.toLowerCase().startsWith("@villager") || message.toLowerCase().startsWith("hey villager")) {
            Entity nearestVillager = findNearestMCAVillager(player);
            if (nearestVillager != null) {
                // Start conversation
                activeConversations.put(playerId, nearestVillager.getUUID());
                lastInteractionTime.put(playerId, System.currentTimeMillis());
                
                player.sendSystemMessage(Component.literal("§a[Now talking to " + getVillagerName(nearestVillager) + "]"));
                player.sendSystemMessage(Component.literal("§7(Type 'bye' to end conversation)"));
                
                // Remove the @villager prefix and respond
                String actualMessage = message.replaceFirst("(?i)@villager\\s*", "")
                                              .replaceFirst("(?i)hey villager\\s*", "");
                
                if (!actualMessage.isEmpty()) {
                    respondToPlayer(player, nearestVillager, actualMessage);
                }
                
                event.setCanceled(true);
                return;
            } else {
                player.sendSystemMessage(Component.literal("§c[No villager nearby]"));
            }
        }
        
        // Check for bye/goodbye to end conversation
        if (activeVillager != null && (message.equalsIgnoreCase("bye") || message.equalsIgnoreCase("goodbye"))) {
            activeConversations.remove(playerId);
            lastInteractionTime.remove(playerId);
            player.sendSystemMessage(Component.literal("§7[Conversation ended]"));
            event.setCanceled(true);
        }
    }
    
    /**
     * Generate and send villager's response
     */
    private static void respondToPlayer(ServerPlayer player, Entity villager, String playerMessage) {
        try {
            // Create dialogue context
            VillagerDialogueAI.DialogueContext context = new VillagerDialogueAI.DialogueContext("conversation");
            context.playerName = player.getName().getString();
            context.playerId = player.getUUID();
            context.villagerName = getVillagerName(villager);
            
            // Get profession if available
            try {
                var professionMethod = villager.getClass().getMethod("getProfession");
                Object profession = professionMethod.invoke(villager);
                if (profession != null) {
                    context.profession = profession.toString();
                }
            } catch (Exception ignored) {}
            
            // Generate response
            VillagerDialogueAI dialogueAI = GANCityMod.getVillagerDialogueAI();
            String response = dialogueAI.generateDialogue(villager.getUUID(), playerMessage, context);
            
            // Send response
            player.sendSystemMessage(Component.literal("§b" + context.villagerName + ": §f" + response));
            
            LOGGER.debug("Villager {} responded to {}: {}", villager.getUUID(), player.getName().getString(), response);
            
        } catch (Exception e) {
            LOGGER.warn("Failed to generate villager response: {}", e.getMessage());
            player.sendSystemMessage(Component.literal("§b" + getVillagerName(villager) + ": §f*confused silence*"));
        }
    }
    
    /**
     * Find nearest MCA villager within hearing range
     */
    private static Entity findNearestMCAVillager(ServerPlayer player) {
        AABB searchBox = player.getBoundingBox().inflate(HEARING_RANGE);
        List<Entity> nearbyEntities = player.serverLevel().getEntities(player, searchBox);
        
        Entity nearest = null;
        double nearestDistance = HEARING_RANGE + 1;
        
        for (Entity entity : nearbyEntities) {
            if (isMCAVillager(entity)) {
                double distance = entity.distanceTo(player);
                if (distance < nearestDistance) {
                    nearest = entity;
                    nearestDistance = distance;
                }
            }
        }
        
        return nearest;
    }
    
    /**
     * Check if entity is MCA villager using reflection
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
     * Get villager name via reflection
     */
    private static String getVillagerName(Entity villager) {
        try {
            var method = villager.getClass().getMethod("getVillagerName");
            Object name = method.invoke(villager);
            return name != null ? name.toString() : "Villager";
        } catch (Exception e) {
            return "Villager";
        }
    }
    
    /**
     * End all conversations for a player (called on logout)
     */
    public static void endConversation(UUID playerId) {
        activeConversations.remove(playerId);
        lastInteractionTime.remove(playerId);
    }
}
