# Cloudflare Worker API Specification

## Overview
This document specifies the server-side merge logic required for the Cloudflare Worker to handle concurrent tactic uploads from multiple Minecraft servers.

## Conflict Resolution Strategy: Weighted Average

When multiple servers upload tactics for the same `mobType + action` combination, the Worker should **merge** the data instead of overwriting.

### Upload Payload Format

```json
{
  "mobType": "zombie",
  "action": "retreat",
  "reward": 0.75,
  "outcome": "success",
  "winRate": 0.65,
  "tier": "VETERAN",
  "timestamp": 1702234567890,
  "serverId": "a3f2e1b9",
  "sampleCount": 1,
  "mergeStrategy": "weighted_average"
}
```

### Merge Algorithm (Weighted Average)

When receiving a new tactic upload:

1. **Check if tactic exists** in storage (key: `{mobType}:{action}`)
2. **If exists**: Merge with weighted average
3. **If new**: Store as-is

#### Weighted Average Formula:

```javascript
// Existing data in storage
let existing = {
  winRate: 0.60,
  reward: 0.70,
  sampleCount: 5,
  lastUpdate: 1702230000000
};

// New data from server
let incoming = {
  winRate: 0.80,
  reward: 0.85,
  sampleCount: 1,
  timestamp: 1702234567890
};

// Calculate weighted average
let totalSamples = existing.sampleCount + incoming.sampleCount;
let newWinRate = (
  (existing.winRate * existing.sampleCount) + 
  (incoming.winRate * incoming.sampleCount)
) / totalSamples;

let newReward = (
  (existing.reward * existing.sampleCount) + 
  (incoming.reward * incoming.sampleCount)
) / totalSamples;

// Update storage
let merged = {
  winRate: newWinRate,        // 0.633 (weighted toward existing data)
  reward: newReward,          // 0.725
  sampleCount: totalSamples,  // 6
  lastUpdate: incoming.timestamp,
  contributingServers: [...existing.contributingServers, incoming.serverId]
};
```

### Pseudocode for Worker

```javascript
async function handleSubmitTactic(request) {
  let data = await request.json();
  let key = `${data.mobType}:${data.action}`;
  
  // Get existing data from KV storage
  let existing = await KV.get(key, { type: "json" });
  
  if (existing) {
    // Merge with weighted average
    let totalSamples = existing.sampleCount + data.sampleCount;
    
    let merged = {
      mobType: data.mobType,
      action: data.action,
      winRate: (
        (existing.winRate * existing.sampleCount) + 
        (data.winRate * data.sampleCount)
      ) / totalSamples,
      reward: (
        (existing.reward * existing.sampleCount) + 
        (data.reward * data.sampleCount)
      ) / totalSamples,
      tier: recalculateTier(mergedWinRate), // Recalculate based on merged winRate
      sampleCount: totalSamples,
      lastUpdate: data.timestamp,
      contributingServers: [
        ...(existing.contributingServers || []), 
        data.serverId
      ].slice(-10) // Keep last 10 server IDs
    };
    
    await KV.put(key, JSON.stringify(merged));
    
    return new Response(JSON.stringify({ 
      status: "merged",
      oldWinRate: existing.winRate,
      newWinRate: merged.winRate,
      totalSamples: totalSamples
    }), { status: 200 });
    
  } else {
    // New tactic - store as-is
    await KV.put(key, JSON.stringify({
      ...data,
      contributingServers: [data.serverId]
    }));
    
    return new Response(JSON.stringify({ 
      status: "created" 
    }), { status: 201 });
  }
}

function recalculateTier(winRate) {
  if (winRate >= 0.7) return "ELITE";
  if (winRate >= 0.5) return "VETERAN";
  return "ROOKIE";
}
```

## Download Endpoint

### GET /api/download-tactics

Returns aggregated tactics from all servers:

```json
{
  "version": "1.0.0",
  "timestamp": 1702234567890,
  "tactics": {
    "zombie": {
      "retreat": {
        "winRate": 0.633,
        "reward": 0.725,
        "tier": "VETERAN",
        "sampleCount": 6,
        "lastUpdate": 1702234567890,
        "contributingServers": ["a3f2e1b9", "b7c4d2e1", "f9a8e3d2"]
      },
      "circle_strafe": {
        "winRate": 0.78,
        "reward": 0.82,
        "tier": "ELITE",
        "sampleCount": 12,
        "lastUpdate": 1702234500000,
        "contributingServers": ["a3f2e1b9", "c5d3e2f1"]
      }
    },
    "skeleton": {
      // ... more tactics
    }
  }
}
```

## Rate Limiting

### Client-Side (Implemented)
- ✅ Download throttler: 3 requests per minute with random jitter (0-5s)
- ✅ 5-minute cache TTL reduces redundant requests
- ✅ Exponential backoff on failures

### Server-Side (Recommended)
- Cloudflare Worker should implement rate limiting per IP:
  - **Uploads**: 100 requests/hour per IP
  - **Downloads**: 60 requests/hour per IP
- Use Cloudflare Rate Limiting API or custom KV-based tracking

## Conflict Edge Cases

### Case 1: Simultaneous Uploads (Race Condition)
**Problem**: Two servers upload different winRates within milliseconds

**Solution**: Cloudflare Workers are single-threaded per request. KV storage provides eventual consistency. Both writes succeed, second one wins but includes first's data in the merge.

### Case 2: Stale Cache During Upload
**Problem**: Server A downloads (caches), Server B uploads new data, Server A's cache is stale for 5 minutes

**Solution**: Acceptable - 5-minute staleness is tolerable for gradual learning. Cache prevents rate limit exhaustion.

### Case 3: GitHub Rate Limit Hit
**Problem**: 10 servers restart simultaneously, all download from GitHub

**Solution**: 
- Client-side throttler adds 0-5s jitter (spreads requests)
- Cache reduces subsequent requests
- Fallback to empty tactics if rate limited (graceful degradation)

## Monitoring & Debugging

Add logging to Worker:

```javascript
console.log({
  event: "tactic_merged",
  mobType: data.mobType,
  action: data.action,
  serverId: data.serverId,
  oldWinRate: existing?.winRate,
  newWinRate: merged.winRate,
  sampleCount: merged.sampleCount
});
```

This helps track:
- Which servers contribute most data
- Win rate convergence over time
- Potential data quality issues

## Migration Plan

1. **Phase 1**: Deploy Worker with merge logic (doesn't break existing clients)
2. **Phase 2**: Minecraft mod already includes merge metadata (shipped in this commit)
3. **Phase 3**: Monitor merge statistics, tune cache TTL if needed
4. **Phase 4**: Optional - implement server-side rate limiting if GitHub limits hit

## Testing

Test cases for Worker:

```javascript
// Test 1: First upload (create)
POST { mobType: "zombie", action: "retreat", winRate: 0.6, sampleCount: 1 }
Expected: { status: "created" }

// Test 2: Second upload (merge)
POST { mobType: "zombie", action: "retreat", winRate: 0.8, sampleCount: 1 }
Expected: { status: "merged", newWinRate: 0.7, totalSamples: 2 }

// Test 3: Simultaneous uploads (eventual consistency)
Concurrent POST requests with different winRates
Expected: Both merge, final result is weighted average of all contributions

// Test 4: Download reflects merged data
GET /api/download-tactics
Expected: Returns merged tactics with correct sampleCount and contributingServers
```

## Security Considerations

- **Server ID uniqueness**: Hash-based IDs prevent collision but aren't cryptographically secure
- **Data validation**: Worker should validate winRate (0.0-1.0), sampleCount > 0
- **Spam prevention**: Rate limiting prevents malicious servers from polluting data
- **No authentication**: Public API - anyone can contribute (by design for ease of use)

## Performance Impact

**Client-Side:**
- Added 3 JSON fields per upload: ~50 bytes
- Throttler adds 0-5s jitter per download (prevents rate limits, worth the delay)
- Server ID generation: one-time cost at initialization

**Server-Side:**
- KV read + write per upload (minor cost, KV is fast)
- Weighted average calculation: O(1) math operations
- contributingServers array: capped at 10 entries (prevents unbounded growth)

## Future Enhancements

1. **Tier-specific merging**: Elite tactics weight more heavily than rookie tactics
2. **Time-based decay**: Older tactics weight less (meta shifts over time)  
3. **Outlier detection**: Reject tactics with suspiciously high/low winRates
4. **Server reputation**: Track and weight servers by historical accuracy
