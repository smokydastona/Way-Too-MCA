# Advanced ML Features Guide - v2.0.0

## Overview
Cloudflare Worker v2.0.0 implements **server-side advanced ML** to minimize client load while adding sophisticated AI capabilities:

- **Meta-Learning**: Cross-mob tactic transfer using embeddings
- **Sequence Analysis**: LSTM-style multi-step combat pattern tracking
- **Transformer Insights**: AI-powered strategy explanations via Llama-2

**Zero Client Overhead**: All advanced ML runs on Cloudflare's infrastructure using Workers AI (free tier: 10,000 requests/day)

---

## Architecture

### 8-Stage Pipeline

1. **Aggregation** (Cloudflare KV)
   - Collect combat data from all players
   - Store per-mob-type tactics with success/failure tracking

2. **Embeddings** (Workers AI: @cf/baai/bge-base-en-v1.5)
   - Generate semantic representations of tactics
   - Enable similarity matching across different mobs

3. **Meta-Learning** (Cross-Mob Transfer)
   - Compare embeddings to find similar successful tactics
   - Example: If zombies learn "flank_left" works (0.8 success), suggest to skeletons

4. **Sequence Analysis** (LSTM-Style)
   - Track multi-step action chains: "charge → retreat → flank"
   - Identify patterns that work better together than individually

5. **Transformer Analysis** (Workers AI: @cf/meta/llama-2-7b-chat-int8)
   - Natural language explanations of successful strategies
   - Stored for 7 days with auto-expiration

6. **Validation** (HuggingFace Integration)
   - Cross-reference tactics with external ML model (future enhancement)
   - Detect statistical anomalies and outliers

7. **Persistence** (GitHub Backup)
   - Auto-commit aggregated data to GitHub repo
   - Version control for all learned tactics

8. **Enhanced Distribution**
   - Return tactics + meta-learning recommendations
   - Include successful sequence patterns
   - Provide transformer insights when available

---

## API Endpoints

### 1. Submit Combat Data (Existing, Enhanced)
```http
POST /api/submit
Content-Type: application/json

{
  "mobType": "zombie",
  "action": "flank_left",
  "reward": 15.0,
  "success": true,
  "mobId": "uuid-here"
}
```

**Response includes**:
- Basic acknowledgment
- Similar successful sequences (if sequence tracking enabled)

---

### 2. Submit Combat Sequence (NEW)
```http
POST /api/submit-sequence
Content-Type: application/json

{
  "mobType": "zombie",
  "sequence": [
    {"action": "charge_forward", "reward": 5.0},
    {"action": "retreat", "reward": 3.0},
    {"action": "flank_left", "reward": 15.0}
  ],
  "finalOutcome": "success",
  "duration": 12000,
  "mobId": "uuid-here"
}
```

**Features**:
- Stores multi-step combat sequences
- Generates embeddings for similarity matching
- Triggers async transformer analysis for successful 3+ step sequences
- Returns similar successful sequences

**Response**:
```json
{
  "status": "success",
  "sequenceLength": 3,
  "similarSequences": [
    {"sequence": ["charge", "retreat", "flank"], "outcome": "success"}
  ],
  "recommendation": "Found 4 similar successful sequences"
}
```

---

### 3. Get Meta-Learning Insights (NEW)
```http
GET /api/meta-learning?mobType=skeleton
```

**Features**:
- Generates embeddings for ALL successful tactics across all mobs
- Computes cosine similarity between embeddings
- Finds tactics from other mobs that could transfer to target mob
- Similarity threshold: 0.80+ (80%+ semantic similarity)

**Response**:
```json
{
  "status": "success",
  "metaLearning": {
    "totalTacticsAnalyzed": 45,
    "crossMobComparisons": 990,
    "transferOpportunities": 12,
    "recommendations": [
      {
        "sourceMob": "zombie",
        "sourceAction": "flank_left",
        "sourceSuccessRate": 0.82,
        "targetMob": "skeleton",
        "targetAction": "circle_strafe",
        "similarity": 0.87,
        "confidence": 0.35,
        "recommendation": "skeleton could benefit from learning flank_left (similar to their successful circle_strafe)"
      }
    ]
  }
}
```

**Parameters**:
- `mobType` (optional): Filter recommendations for specific mob

**Use Case**: Client downloads recommendations, adds suggested tactics to exploration pool with confidence weighting

---

### 4. Get Sequence Patterns (NEW)
```http
GET /api/sequence-patterns?mobType=zombie&minLength=2
```

**Features**:
- Returns successful combat sequences
- Calculates success rates for repeated patterns
- Identifies most effective multi-step strategies

**Response**:
```json
{
  "status": "success",
  "mobType": "zombie",
  "recentSuccessful": [
    {
      "sequence": ["charge", "retreat", "flank"],
      "duration": 8500,
      "timestamp": 1234567890
    }
  ],
  "topPatterns": [
    {
      "pattern": "charge→retreat→flank",
      "successRate": 0.85,
      "occurrences": 15,
      "successes": 13,
      "failures": 2
    }
  ],
  "stats": {
    "totalSequences": 156,
    "successfulSequences": 98,
    "uniquePatterns": 34
  }
}
```

**Parameters**:
- `mobType`: Target mob type (required)
- `minLength`: Minimum sequence length (default: 2)

---

### 5. Download Tactics (Enhanced)
```http
GET /api/download?mobType=zombie&includeRecommendations=true
```

**New Fields**:
```json
{
  "version": "2.0.0",
  "tactics": {
    "zombie": {
      "tactics": [...],
      "metaLearning": {
        "crossMobRecommendations": [
          {
            "sourceMob": "skeleton",
            "sourceAction": "retreat_when_low",
            "similarity": 0.84,
            "confidence": 0.20
          }
        ],
        "message": "3 tactics from other mobs may work for zombie"
      },
      "sequencePatterns": {
        "recentSuccessful": [
          {"sequence": "charge → retreat → flank", "duration": 8500}
        ],
        "totalRecorded": 156
      }
    }
  },
  "features": ["meta-learning", "sequence-patterns"]
}
```

**Parameters**:
- `mobType` (optional): Download specific mob only
- `includeRecommendations` (default: true): Include meta-learning/sequences

---

### 6. Statistics (Enhanced)
```http
GET /api/stats
```

**New Fields**:
```json
{
  "version": "2.0.0",
  "global": {...},
  "perMob": {...},
  "advancedML": {
    "metaLearning": {
      "lastUpdate": 1234567890,
      "totalComparisons": 990,
      "transferOpportunities": 12,
      "enabled": true
    },
    "sequenceAnalysis": {
      "totalSequences": 842,
      "successfulSequences": 531,
      "successRate": 0.63,
      "enabled": true
    },
    "transformerInsights": {
      "enabled": true,
      "note": "AI-powered strategy explanations available for successful sequences"
    }
  }
}
```

---

## Implementation Details

### Meta-Learning Algorithm

1. **Embedding Generation**:
   ```javascript
   const tacticDescription = `${mobType} mob uses ${action} tactic with ${successRate} success rate`;
   const embedding = await env.AI.run('@cf/baai/bge-base-en-v1.5', { text: tacticDescription });
   // Returns 768-dimensional vector
   ```

2. **Similarity Calculation**:
   ```javascript
   function cosineSimilarity(vec1, vec2) {
     dotProduct = sum(vec1[i] * vec2[i])
     magnitude1 = sqrt(sum(vec1[i]²))
     magnitude2 = sqrt(sum(vec2[i]²))
     return dotProduct / (magnitude1 * magnitude2)
   }
   ```

3. **Transfer Logic**:
   - Compare all tactics across different mob types
   - Similarity > 0.80 = potential transfer
   - Confidence = (similarity - 0.80) / 0.20 (normalized 0-1)
   - Store top 50, return top 20 per request

### Sequence Analysis

1. **Storage**:
   - Last 200 sequences per mob type
   - Includes: actions, rewards, final outcome, duration, timestamp

2. **Pattern Recognition**:
   - Convert sequence to string: "charge→retreat→flank"
   - Track success/failure counts per pattern
   - Require 3+ occurrences for statistical significance

3. **Embedding Comparison**:
   - Generate embedding for new sequence
   - Find similar patterns using cosine similarity
   - Recommend successful variants

### Transformer Analysis

1. **Trigger Conditions**:
   - Sequence length >= 3 steps
   - Final outcome = success
   - Runs asynchronously (doesn't block response)

2. **Prompt**:
   ```
   Analyze this Minecraft {mobType} combat strategy:
   Sequence: charge → retreat → flank
   Outcome: success
   
   Explain why this sequence is effective and what makes it successful.
   ```

3. **Storage**:
   - Key: `analysis:{mobType}:{timestamp}`
   - TTL: 7 days (auto-delete)
   - Includes: sequence, outcome, AI explanation

---

## Client Integration

### 1. Enable Sequence Tracking
```java
// In MobBehaviorAI.java
private List<ActionRecord> currentSequence = new ArrayList<>();

public void trackAction(String action, double reward) {
    currentSequence.add(new ActionRecord(action, reward));
}

public void submitSequence(String outcome) {
    if (currentSequence.size() >= 2) {
        cloudflareClient.submitSequence(mobType, currentSequence, outcome, duration, mobId);
        currentSequence.clear();
    }
}
```

### 2. Download Enhanced Tactics
```java
// CloudflareAPIClient.java
public TacticsResponse downloadTactics(String mobType, boolean includeRecommendations) {
    String url = baseUrl + "/api/download?mobType=" + mobType + 
                 "&includeRecommendations=" + includeRecommendations;
    
    TacticsResponse response = httpClient.get(url);
    
    // Apply meta-learning recommendations
    if (response.hasMetaLearning()) {
        for (Recommendation rec : response.getMetaLearning()) {
            if (rec.getConfidence() > 0.3) {
                explorationPool.add(rec.getSourceAction(), rec.getConfidence());
            }
        }
    }
    
    // Learn from sequence patterns
    if (response.hasSequencePatterns()) {
        for (Pattern pattern : response.getTopPatterns()) {
            if (pattern.getSuccessRate() > 0.7) {
                sequenceMemory.add(pattern);
            }
        }
    }
    
    return response;
}
```

### 3. Use Meta-Learning in Action Selection
```java
public String selectAction(MobState state) {
    // Check if current state matches a successful sequence pattern
    String sequenceRecommendation = checkSequencePatterns(state, lastActions);
    if (sequenceRecommendation != null) {
        return sequenceRecommendation; // Continue successful pattern
    }
    
    // Standard DQN + exploration with meta-learning boost
    if (random.nextFloat() < epsilon) {
        // Weighted exploration using meta-learning confidence
        return selectFromExplorationPool(); // Includes cross-mob recommendations
    }
    
    return dqn.selectAction(state);
}
```

---

## Performance Considerations

### Free Tier Limits
- **Workers AI Requests**: 10,000/day
- **KV Reads**: 100,000/day
- **KV Writes**: 1,000/day

### Optimization Strategies

1. **Batching**:
   - Submit sequences only after combat ends
   - Download tactics every 5 minutes (client-side cache)

2. **Caching**:
   - Meta-learning results cached in KV (updated hourly)
   - Download endpoint has 5-minute cache header

3. **Async Processing**:
   - Transformer analysis runs asynchronously
   - Doesn't block sequence submission response

4. **Storage Management**:
   - Sequences: last 200 per mob type
   - Transformer analyses: 7-day TTL
   - Meta-learning recommendations: top 50 stored

### Estimated Usage (1000 active players)
- **Sequence submissions**: ~5000/day (5 per player)
- **Meta-learning requests**: ~100/day (hourly batch update)
- **Downloads**: ~2000/day (client cache reduces load)
- **Total Workers AI**: ~7100/day (well under 10k limit)

---

## Deployment

### 1. Update `wrangler.toml`
```toml
name = "adaptive-mob-ai-v2"
main = "worker.js"
compatibility_date = "2024-01-15"

[ai]
binding = "AI"

[[kv_namespaces]]
binding = "TACTICS_KV"
id = "your-kv-namespace-id"

[vars]
GITHUB_TOKEN = "your-github-token"
GITHUB_REPO = "your-repo"
GITHUB_OWNER = "your-username"
```

### 2. Deploy
```bash
npx wrangler deploy
```

### 3. Test Advanced ML
```bash
# Test meta-learning
curl "https://your-worker.workers.dev/api/meta-learning?mobType=zombie"

# Test sequence patterns
curl "https://your-worker.workers.dev/api/sequence-patterns?mobType=zombie&minLength=2"

# Test enhanced download
curl "https://your-worker.workers.dev/api/download?mobType=zombie&includeRecommendations=true"

# Submit test sequence
curl -X POST "https://your-worker.workers.dev/api/submit-sequence" \
  -H "Content-Type: application/json" \
  -d '{
    "mobType": "zombie",
    "sequence": [
      {"action": "charge", "reward": 5},
      {"action": "retreat", "reward": 3},
      {"action": "flank", "reward": 15}
    ],
    "finalOutcome": "success",
    "duration": 8500,
    "mobId": "test-mob-1"
  }'
```

---

## Benefits vs Client-Side ML

| Feature | Client-Side (DJL) | Server-Side (Workers AI) |
|---------|------------------|--------------------------|
| **Client Size** | +20MB | 240KB |
| **Initialization** | 30-60s | Instant |
| **Memory Usage** | ~500MB | Minimal |
| **Advanced ML** | Limited by client | Full transformer access |
| **Cross-Player Learning** | Manual sync | Automatic |
| **Model Updates** | Requires mod update | Instant deployment |
| **Cost** | None | Free (10k req/day) |

---

## Future Enhancements

1. **Multi-Modal Learning**:
   - Combine embeddings with numerical features (health, distance)
   - Vision transformers for terrain analysis

2. **Real-Time Recommendations**:
   - WebSocket connection for live tactic suggestions
   - Push notifications for high-confidence transfers

3. **Hierarchical Learning**:
   - Mob family groupings (undead, arthropods)
   - Transfer learning within families first

4. **Explanation UI**:
   - Web dashboard showing transformer insights
   - Visualize embedding spaces with t-SNE

5. **A/B Testing**:
   - Test meta-learning recommendations vs baseline
   - Measure improvement in win rates

---

## Troubleshooting

### Meta-Learning Returns Empty
- **Cause**: Not enough successful tactics across mobs
- **Solution**: Wait for more combat data (requires 5+ successful tactics per mob)

### Sequence Patterns Not Found
- **Cause**: No multi-step sequences recorded yet
- **Solution**: Ensure client calls `submitSequence()` after combat

### Transformer Analysis Missing
- **Cause**: Async analysis failed or expired
- **Solution**: Check worker logs, increase TTL if needed

### Workers AI Rate Limit
- **Cause**: Exceeded 10k requests/day
- **Solution**: Implement client-side rate limiting, batch requests

---

## License
MIT License - Same as main mod
