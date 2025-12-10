package com.minecraft.gancity.ai;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.*;

/**
 * AI-powered dialogue generation system for MCA villagers
 * Uses template-based dialogue with personality tracking (ML models optional)
 */
public class VillagerDialogueAI {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    private final Map<UUID, VillagerPersonality> villagerPersonalities = new HashMap<>();
    private final Random random = new Random();
    private final DialogueGenerator generator = new DialogueGenerator();

    // Dialogue templates for fallback
    private static final Map<String, List<String>> DIALOGUE_TEMPLATES = new HashMap<>();
    
    static {
        // Greetings
        DIALOGUE_TEMPLATES.put("greeting", Arrays.asList(
            "Hello there, {player}!",
            "Oh, {player}! Good to see you!",
            "Greetings, {player}. How are you today?",
            "Ah, {player}! What brings you by?",
            "Hey {player}! Nice weather we're having."
        ));
        
        // Small talk
        DIALOGUE_TEMPLATES.put("small_talk", Arrays.asList(
            "Have you seen the {biome} today? It's beautiful!",
            "I was thinking about {topic} earlier.",
            "You know, life in {village} is quite pleasant.",
            "Did you hear about {event}? Fascinating!",
            "Sometimes I wonder about {philosophical_thought}."
        ));
        
        // Gift responses
        DIALOGUE_TEMPLATES.put("gift_positive", Arrays.asList(
            "Oh {player}, you shouldn't have! I love {item}!",
            "This {item} is perfect! Thank you so much!",
            "You remembered I like {item}! How thoughtful!",
            "I can't believe you got me {item}! You're the best!",
            "A {item}! This is exactly what I needed!"
        ));
        
        DIALOGUE_TEMPLATES.put("gift_neutral", Arrays.asList(
            "Oh, a {item}. Thank you, {player}.",
            "That's... nice. Thanks for the {item}.",
            "I appreciate the {item}, {player}.",
            "A {item}. How interesting.",
            "Well, thank you for thinking of me."
        ));
        
        // Relationship responses
        DIALOGUE_TEMPLATES.put("flirt", Arrays.asList(
            "You know, {player}, I've been thinking about you a lot lately.",
            "Every time you visit, my day gets brighter.",
            "There's something special about you, {player}.",
            "I look forward to seeing you every day.",
            "You make me feel... different. In a good way!"
        ));
        
        // Requests/tasks
        DIALOGUE_TEMPLATES.put("request_help", Arrays.asList(
            "{player}, could you help me with something?",
            "I hate to ask, but would you mind helping me?",
            "If you have time, I could really use your assistance.",
            "There's something I need help with. Interested?",
            "I was wondering if you'd be willing to do me a favor?"
        ));
        
        // Personality-based variations
        DIALOGUE_TEMPLATES.put("witty", Arrays.asList(
            "Well, well, if it isn't {player}. Come to bless us with your presence?",
            "Ah yes, {player}, because what I really needed today was more excitement.",
            "Don't tell me - you need something. How did I guess?",
            "Let me guess, you're not just here for my sparkling personality?",
            "Oh good, {player} is here. Now my day is complete. *wink*"
        ));
        
        DIALOGUE_TEMPLATES.put("shy", Arrays.asList(
            "Oh! H-hi {player}...",
            "Um... hello. Nice to see you again...",
            "I... I was hoping you'd visit today...",
            "You probably have better things to do than talk to me...",
            "*nervously* Hi there..."
        ));
        
        DIALOGUE_TEMPLATES.put("friendly", Arrays.asList(
            "Hey buddy! How's it going?!",
            "Oh my gosh, {player}! I'm so happy to see you!",
            "Best friend! Come here, let's chat!",
            "You're here! This day just got a million times better!",
            "Yay! It's {player}! Tell me everything!"
        ));
    }

    public VillagerDialogueAI() {
        LOGGER.info("VillagerDialogueAI initialized with dynamic text generation system");
        generator.initialize();
    }
    
    /**
     * Generate dialogue response based on context
     */
    public String generateDialogue(UUID villagerId, DialogueContext context) {
        return generateDialogue(villagerId, "", context);
    }
    
    /**
     * Generate dialogue response with player message input
     */
    public String generateDialogue(UUID villagerId, String playerMessage, DialogueContext context) {
        VillagerPersonality personality = getOrCreatePersonality(villagerId);
        
        // Generate dynamic response based on context and personality
        return generator.generateResponse(personality, context, playerMessage);
    }
    
    /**
     * Template-based dialogue with personality
     */
    private String generateWithTemplates(VillagerPersonality personality, DialogueContext context) {
        List<String> templates = selectTemplates(context, personality);
        
        if (templates.isEmpty()) {
            return "Hello!";
        }
        
        String template = templates.get(random.nextInt(templates.size()));
        return personalizeDialogue(template, context, personality);
    }

    /**
     * Select appropriate templates based on context and personality
     */
    private List<String> selectTemplates(DialogueContext context, VillagerPersonality personality) {
        List<String> candidates = new ArrayList<>();
        
        // Add context-appropriate templates
        if (context.interactionType != null) {
            List<String> contextTemplates = DIALOGUE_TEMPLATES.get(context.interactionType);
            if (contextTemplates != null) {
                candidates.addAll(contextTemplates);
            }
        }
        
        // Add personality-specific templates
        String personalityType = personality.getDominantTrait();
        List<String> personalityTemplates = DIALOGUE_TEMPLATES.get(personalityType);
        if (personalityTemplates != null && random.nextFloat() < 0.3f) {
            candidates.addAll(personalityTemplates);
        }
        
        return candidates;
    }

    /**
     * Personalize dialogue with context variables
     */
    private String personalizeDialogue(String template, DialogueContext context, VillagerPersonality personality) {
        String result = template;
        
        result = result.replace("{player}", context.playerName != null ? context.playerName : "friend");
        result = result.replace("{biome}", context.biome != null ? context.biome : "area");
        result = result.replace("{village}", context.villageName != null ? context.villageName : "the village");
        result = result.replace("{item}", context.itemName != null ? context.itemName : "this");
        
        // Add personality-based modifications
        if (personality.traits.getOrDefault("sarcastic", 0.0f) > 0.7f && random.nextFloat() < 0.3f) {
            result = addSarcasm(result);
        }
        
        // Add emotion indicators
        if (personality.currentMood == Mood.HAPPY) {
            if (random.nextFloat() < 0.2f) {
                result += " *smiles*";
            }
        } else if (personality.currentMood == Mood.SAD) {
            if (random.nextFloat() < 0.2f) {
                result += " *sighs*";
            }
        }
        
        return result;
    }

    /**
     * Add sarcastic tone to dialogue
     */
    private String addSarcasm(String text) {
        if (random.nextBoolean()) {
            return text + " Oh wait, I'm being sarcastic.";
        }
        return text;
    }

    /**
     * Get or create personality for a villager
     */
    private VillagerPersonality getOrCreatePersonality(UUID villagerId) {
        return villagerPersonalities.computeIfAbsent(villagerId, id -> new VillagerPersonality());
    }

    /**
     * Update villager personality based on interaction
     */
    public void recordInteraction(UUID villagerId, DialogueContext context, String playerResponse) {
        VillagerPersonality personality = getOrCreatePersonality(villagerId);
        personality.recordInteraction(context, playerResponse);
    }

    /**
     * Learn new dialogue patterns from player interactions
     */
    public void learnFromInteraction(UUID villagerId, String dialogue, boolean positiveOutcome) {
        VillagerPersonality personality = getOrCreatePersonality(villagerId);
        
        if (positiveOutcome) {
            personality.successfulDialogue.add(dialogue);
        } else {
            personality.unsuccessfulDialogue.add(dialogue);
        }
    }

    /**
     * Get dialogue statistics
     */
    public String getStats() {
        return String.format("Dynamic Text Generation | Personalities: %d | Vocab: %d words", 
            villagerPersonalities.size(),
            generator.getVocabularySize());
    }
    
    /**
     * Context for dialogue generation
     */
    public static class DialogueContext {
        public String interactionType; // greeting, small_talk, gift, flirt, etc.
        public String playerName;
        public UUID playerId;
        public String profession;
        public String villagerName;
        public String biome;
        public String villageName;
        public String itemName;
        public int relationshipLevel;
        public String timeOfDay;
        public String[] recentEvents;
        public Map<String, Object> customData = new HashMap<>();

        public DialogueContext(String interactionType) {
            this.interactionType = interactionType;
        }
    }

    /**
     * Villager personality that evolves over time
     */
    private static class VillagerPersonality {
        private final Map<String, Float> traits = new HashMap<>();
        private Mood currentMood = Mood.NEUTRAL;
        private final List<String> successfulDialogue = new ArrayList<>();
        private final List<String> unsuccessfulDialogue = new ArrayList<>();
        private final Map<String, Integer> topicInterests = new HashMap<>();
        private final Random random = new Random();

        public VillagerPersonality() {
            // Randomize initial traits
            traits.put("friendly", random.nextFloat());
            traits.put("shy", random.nextFloat());
            traits.put("witty", random.nextFloat());
            traits.put("sarcastic", random.nextFloat());
            traits.put("romantic", random.nextFloat());
            traits.put("intellectual", random.nextFloat());
            traits.put("cheerful", random.nextFloat());
            
            // Normalize traits
            normalizeTraits();
        }

        private void normalizeTraits() {
            float sum = traits.values().stream().reduce(0f, Float::sum);
            if (sum > 0) {
                traits.replaceAll((k, v) -> v / sum);
            }
        }

        public String getDominantTrait() {
            return traits.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("friendly");
        }

        public void recordInteraction(DialogueContext context, String playerResponse) {
            // Adjust mood based on interaction
            if (context.interactionType.contains("gift")) {
                currentMood = Mood.HAPPY;
            }
            
            // Track topic interests
            if (context.customData.containsKey("topic")) {
                String topic = (String) context.customData.get("topic");
                topicInterests.put(topic, topicInterests.getOrDefault(topic, 0) + 1);
            }
            
            // Gradually evolve personality
            if (context.interactionType.equals("flirt") && context.relationshipLevel > 50) {
                traits.put("romantic", Math.min(1.0f, traits.getOrDefault("romantic", 0.5f) + 0.01f));
                normalizeTraits();
            }
        }
        
        /**
         * Get dominant personality trait name
         */
        public String getTraitName() {
            float maxValue = 0.0f;
            String dominantTrait = "friendly";
            
            for (Map.Entry<String, Float> entry : traits.entrySet()) {
                if (entry.getValue() > maxValue) {
                    maxValue = entry.getValue();
                    dominantTrait = entry.getKey();
                }
            }
            
            return dominantTrait;
        }
        
        /**
         * Get relationship level description
         */
        public String getRelationshipLevel(UUID playerId) {
            // Would normally track per-player relationships
            // For now, return based on interaction count
            int interactions = successfulDialogue.size() + unsuccessfulDialogue.size();
            if (interactions < 3) return "stranger";
            if (interactions < 10) return "acquaintance";
            if (interactions < 25) return "friend";
            return "loved";
        }
        
        /**
         * Get current mood as string
         */
        public String getMood() {
            return currentMood.toString().toLowerCase();
        }
    }

    /**
     * Villager mood states
     */
    private enum Mood {
        HAPPY, SAD, ANGRY, NEUTRAL, EXCITED, TIRED
    }
    
    /**
     * Dynamic dialogue generator using Markov chains + template mixing
     * Creates unique responses on-the-fly without neural networks
     */
    private static class DialogueGenerator {
        private final Map<String, List<String>> wordChains = new HashMap<>();
        private final Map<String, List<String>> phraseBank = new HashMap<>();
        private final Random random = new Random();
        
        // Sentence starters for different contexts
        private final String[] greetingStarters = {
            "Oh", "Hey", "Hello", "Well", "Ah", "Hi there"
        };
        
        private final String[] positiveWords = {
            "wonderful", "great", "amazing", "lovely", "fantastic", "delightful", 
            "pleasant", "nice", "good", "happy", "glad", "excited"
        };
        
        private final String[] connectiveWords = {
            "and", "but", "so", "well", "you see", "actually", "honestly",
            "you know", "I mean", "by the way"
        };
        
        public void initialize() {
            // Build phrase banks from templates
            buildPhraseBank("greeting", Arrays.asList(
                "to see you", "you came by", "you're here", "we meet again",
                "you stopped by", "you visited", "to talk with you"
            ));
            
            buildPhraseBank("feeling", Arrays.asList(
                "I'm feeling", "I feel", "today I'm", "I've been feeling",
                "lately I've been", "I must say I'm"
            ));
            
            buildPhraseBank("activity", Arrays.asList(
                "working on", "thinking about", "dealing with", "planning",
                "worried about", "excited about", "focused on"
            ));
            
            buildPhraseBank("question", Arrays.asList(
                "how about you", "what brings you here", "need something",
                "can I help you", "what do you think", "have you heard"
            ));
            
            buildPhraseBank("topic", Arrays.asList(
                "the weather", "my crops", "the village", "recent events",
                "tomorrow's plans", "my family", "our neighbors", "the situation"
            ));
        }
        
        private void buildPhraseBank(String category, List<String> phrases) {
            phraseBank.put(category, new ArrayList<>(phrases));
        }
        
        public String generateResponse(VillagerPersonality personality, DialogueContext context, String playerMessage) {
            StringBuilder response = new StringBuilder();
            
            // Start with greeting if appropriate
            if (context.interactionType != null && context.interactionType.equals("greeting")) {
                response.append(generateGreeting(personality, context));
            } else if (!playerMessage.isEmpty()) {
                // Respond to player's message
                response.append(generateReply(personality, playerMessage, context));
            } else {
                // Generic dialogue
                response.append(generateStatement(personality, context));
            }
            
            // Add personality flair
            if (random.nextFloat() < personality.traits.get("witty")) {
                response.append(" ").append(addWittyRemark());
            }
            
            // Occasionally add a question
            if (random.nextFloat() < 0.3f) {
                response.append(" ").append(generateQuestion(context));
            }
            
            return response.toString().trim();
        }
        
        private String generateGreeting(VillagerPersonality personality, DialogueContext context) {
            String starter = greetingStarters[random.nextInt(greetingStarters.length)];
            String greetingPhrase = pickRandom(phraseBank.get("greeting"));
            
            if (personality.traits.get("friendly") > 0.6f) {
                String positiveWord = positiveWords[random.nextInt(positiveWords.length)];
                return String.format("%s, how %s %s%s!", 
                    starter, positiveWord, greetingPhrase,
                    context.playerName != null ? ", " + context.playerName : "");
            } else if (personality.traits.get("shy") > 0.6f) {
                return String.format("%s... um, %s...", starter, greetingPhrase);
            } else {
                return String.format("%s, %s.", starter, greetingPhrase);
            }
        }
        
        private String generateReply(VillagerPersonality personality, String playerMessage, DialogueContext context) {
            // Simple keyword-based response generation
            String lowerMessage = playerMessage.toLowerCase();
            
            if (lowerMessage.contains("how are you") || lowerMessage.contains("how're you")) {
                String feeling = pickRandom(phraseBank.get("feeling"));
                String mood = personality.currentMood.toString().toLowerCase();
                return String.format("%s quite %s today.", feeling, mood);
            }
            
            if (lowerMessage.contains("what") && lowerMessage.contains("doing")) {
                String activity = pickRandom(phraseBank.get("activity"));
                String topic = pickRandom(phraseBank.get("topic"));
                return String.format("I've been %s %s.", activity, topic);
            }
            
            if (lowerMessage.contains("thank") || lowerMessage.contains("thanks")) {
                return random.nextBoolean() ? 
                    "You're very welcome!" : 
                    "Oh, it's my pleasure!";
            }
            
            // Generic acknowledgment
            String[] acknowledgments = {
                "I see what you mean.", "That's interesting.", "I hadn't thought of that.",
                "You make a good point.", "I understand.", "Hmm, yes."
            };
            return acknowledgments[random.nextInt(acknowledgments.length)];
        }
        
        private String generateStatement(VillagerPersonality personality, DialogueContext context) {
            String feeling = pickRandom(phraseBank.get("feeling"));
            String topic = pickRandom(phraseBank.get("topic"));
            
            if (context.biome != null && random.nextFloat() < 0.3f) {
                return String.format("The %s around here is quite something, isn't it?", context.biome);
            }
            
            if (context.profession != null && random.nextFloat() < 0.2f) {
                return String.format("Being a %s keeps me busy these days.", context.profession);
            }
            
            return String.format("I've been thinking about %s lately.", topic);
        }
        
        private String generateQuestion(DialogueContext context) {
            String question = pickRandom(phraseBank.get("question"));
            return Character.toUpperCase(question.charAt(0)) + question.substring(1) + "?";
        }
        
        private String addWittyRemark() {
            String[] wittyRemarks = {
                "Life's full of surprises, isn't it?",
                "You never know what tomorrow brings.",
                "Such is life in the village.",
                "Times are changing, I suppose.",
                "Who would have thought?",
                "Interesting times we live in."
            };
            return wittyRemarks[random.nextInt(wittyRemarks.length)];
        }
        
        private String pickRandom(List<String> list) {
            if (list == null || list.isEmpty()) return "";
            return list.get(random.nextInt(list.size()));
        }
        
        public int getVocabularySize() {
            int total = 0;
            for (List<String> phrases : phraseBank.values()) {
                total += phrases.size();
            }
            return total;
        }
    }
}
