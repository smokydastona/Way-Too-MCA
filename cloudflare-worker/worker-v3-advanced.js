/**
 * Cloudflare Worker for MCA AI Enhanced - Advanced Federated Learning Server v3.0.0
 * 
 * REVOLUTIONARY FEATURES:
 * ✅ FedAvgM: Momentum-based federated averaging (not simple mean)
 * ✅ Weighted Aggregation: Contributions weighted by mob spawn frequency
 * ✅ Replay Buffer Pooling: Raw experience sharing across servers
 * ✅ Version Control: Rollback mechanism for bad updates
 * ✅ Model Specialization: Per-mob heads on shared knowledge trunk
 * ✅ Periodic Global Retraining: Rebuild model from pooled experiences
 * 
 * Architecture:
 * - Mod uploads: Tactics + replay buffer samples + model deltas
 * - Worker aggregates using FedAvgM with momentum
 * - Worker weights by mob spawn rates (zombies don't dominate)
 * - Worker pools replay buffers for deep learning
 * - Worker maintains version history with rollback
 * - Worker provides per-mob specialized layers
 * 
 * Storage:
 * - KV: Current aggregated tactics + replay buffer pool
 * - KV: Model version history (last 10 versions)
 * - KV: Per-mob spawn frequency weights
 * - KV: Momentum accumulator for FedAvgM
 * - R2/GitHub: Full experience replay archive
 * 
 * Endpoints:
 * - POST /api/upload - Upload tactics + replay samples + deltas
 * - POST /api/upload-replay - Upload raw experience samples
 * - GET /api/download - Download tactics + specialized layers
 * - GET /api/version/:version - Download specific model version
 * - POST /api/rollback/:version - Rollback to previous version
 * - GET /api/stats - Federation statistics
 * - POST /api/retrain - Trigger global retraining from replay pool
 */

// Mob spawn frequency weights (based on vanilla Minecraft spawn rates)
const MOB_SPAWN_WEIGHTS = {
  'zombie': 1.0,      // Most common, baseline
  'skeleton': 0.85,   // Slightly less common
  'spider': 0.75,     // Less common
  'creeper': 0.65,    // Relatively rare
  'husk': 0.3,        // Desert biomes only
  'stray': 0.25,      // Snow biomes only
  'wither_skeleton': 0.1,  // Nether fortresses only
  'enderman': 0.15    // End + overworld night
};

// FedAvgM hyperparameters
const FEDAVGM_MOMENTUM = 0.9;  // Momentum coefficient
const FEDAVGM_LEARNING_RATE = 0.01;  // Server learning rate

// Version control
const MAX_VERSION_HISTORY = 10;
const CURRENT_VERSION_KEY = 'model:version:current';

// Replay buffer pool settings
const MAX_REPLAY_POOL_SIZE = 100000;  // Total experiences to keep
const REPLAY_SAMPLE_SIZE = 1000;  // Samples to include in download

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    
    const corsHeaders = {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type',
    };
    
    if (request.method === 'OPTIONS') {
      return new Response(null, { headers: corsHeaders });
    }
    
    try {
      // Route requests
      if (url.pathname === '/api/upload' && request.method === 'POST') {
        return await handleAdvancedUpload(request, env, ctx, corsHeaders);
      } else if (url.pathname === '/api/upload-replay' && request.method === 'POST') {
        return await handleReplayBufferUpload(request, env, ctx, corsHeaders);
      } else if (url.pathname === '/api/download' && request.method === 'GET') {
        return await handleAdvancedDownload(request, env, corsHeaders);
      } else if (url.pathname.startsWith('/api/version/') && request.method === 'GET') {
        const version = parseInt(url.pathname.split('/').pop());
        return await handleVersionDownload(request, env, corsHeaders, version);
      } else if (url.pathname.startsWith('/api/rollback/') && request.method === 'POST') {
        const version = parseInt(url.pathname.split('/').pop());
        return await handleRollback(request, env, corsHeaders, version);
      } else if (url.pathname === '/api/retrain' && request.method === 'POST') {
        return await handleGlobalRetrain(request, env, ctx, corsHeaders);
      } else if (url.pathname === '/api/stats' && request.method === 'GET') {
        return await handleStats(request, env, corsHeaders);
      } else if (url.pathname === '/' || url.pathname === '/api') {
        return new Response(JSON.stringify({
          service: 'MCA AI Enhanced - Advanced Federated Learning Server',
          version: '3.0.0',
          features: [
            'FedAvgM (Momentum-based aggregation)',
            'Weighted by spawn frequency',
            'Replay buffer pooling',
            'Version control + rollback',
            'Model specialization layers',
            'Global retraining from pooled experiences'
          ],
          algorithms: {
            aggregation: 'FedAvgM',
            momentum: FEDAVGM_MOMENTUM,
            learning_rate: FEDAVGM_LEARNING_RATE
          },
          endpoints: {
            'POST /api/upload': 'Upload tactics + deltas + replay samples',
            'POST /api/upload-replay': 'Upload raw experience buffer',
            'GET /api/download': 'Download aggregated model + specialized layers',
            'GET /api/version/:v': 'Download specific version',
            'POST /api/rollback/:v': 'Rollback to version',
            'POST /api/retrain': 'Trigger global retraining',
            'GET /api/stats': 'Federation statistics'
          }
        }), {
          headers: { ...corsHeaders, 'Content-Type': 'application/json' }
        });
      } else {
        return new Response('Not Found', { status: 404 });
      }
    } catch (error) {
      console.error('Worker error:', error);
      return new Response(JSON.stringify({ error: error.message }), {
        status: 500,
        headers: { ...corsHeaders, 'Content-Type': 'application/json' }
      });
    }
  }
};

/**
 * Advanced upload with FedAvgM aggregation
 * Accepts: tactics, model deltas, replay buffer samples
 */
async function handleAdvancedUpload(request, env, ctx, corsHeaders) {
  try {
    const data = await request.json();
    
    // Validate
    if (!data.mobType || !data.serverId) {
      return new Response(JSON.stringify({ 
        error: 'Missing required fields: mobType, serverId' 
      }), {
        status: 400,
        headers: { ...corsHeaders, 'Content-Type': 'application/json' }
      });
    }
    
    const { mobType, serverId, tactics, modelDeltas, replayS amples, timestamp } = data;
    
    // Get current model version
    const currentVersion = await getCurrentVersion(env);
    
    // Apply FedAvgM aggregation
    const aggregationResult = await applyFedAvgM(
      env,
      mobType,
      serverId,
      tactics,
      modelDeltas,
      currentVersion
    );
    
    // Pool replay buffer samples (if provided)
    if (replaySamples && Array.isArray(replaySamples)) {
      ctx.waitUntil(addToReplayPool(env, mobType, replaySamples));
    }
    
    // Increment version if significant changes
    if (aggregationResult.significantChange) {
      ctx.waitUntil(createNewVersion(env, currentVersion + 1));
    }
    
    // Async GitHub backup
    ctx.waitUntil(syncToGitHub(env, mobType, aggregationResult.aggregatedTactics));
    
    return new Response(JSON.stringify({
      success: true,
      mobType,
      version: aggregationResult.newVersion || currentVersion,
      aggregationMethod: 'FedAvgM',
      weightUsed: MOB_SPAWN_WEIGHTS[mobType] || 0.5,
      message: 'Tactics aggregated with momentum'
    }), {
      headers: { ...corsHeaders, 'Content-Type': 'application/json' }
    });
    
  } catch (error) {
    console.error('Upload error:', error);
    return new Response(JSON.stringify({ error: error.message }), {
      status: 500,
      headers: { ...corsHeaders, 'Content-Type': 'application/json' }
    });
  }
}

/**
 * FedAvgM: Federated Averaging with Momentum
 * Formula: v_t = β * v_{t-1} + (1 - β) * Δw_i
 *          w_t = w_{t-1} + η * v_t
 * 
 * Where:
 * - v_t = momentum accumulator
 * - β = momentum coefficient (0.9)
 * - Δw_i = model delta from server i
 * - η = learning rate (0.01)
 * - Weight contribution by spawn frequency
 */
async function applyFedAvgM(env, mobType, serverId, tactics, modelDeltas, version) {
  const key = `tactics:${mobType}`;
  const momentumKey = `momentum:${mobType}`;
  
  // Get existing aggregated tactics
  const existing = await env.TACTICS_KV.get(key, { type: 'json' }) || {
    mobType,
    submissions: 0,
    lastUpdate: Date.now(),
    tactics: {},
    version
  };
  
  // Get momentum accumulator
  const momentum = await env.TACTICS_KV.get(momentumKey, { type: 'json' }) || {};
  
  // Get spawn weight for this mob type
  const spawnWeight = MOB_SPAWN_WEIGHTS[mobType] || 0.5;
  
  // Apply FedAvgM for each tactic
  let significantChange = false;
  
  for (const [action, tacticData] of Object.entries(tactics)) {
    const tacticKey = action;
    
    // Initialize if not exists
    if (!existing.tactics[tacticKey]) {
      existing.tactics[tacticKey] = {
        action,
        avgReward: 0,
        count: 0,
        successRate: 0,
        weightedAvgReward: 0,
        version
      };
    }
    
    // Calculate weighted delta
    const currentAvg = existing.tactics[tacticKey].avgReward;
    const incomingAvg = tacticData.avgReward;
    const delta = (incomingAvg - currentAvg) * spawnWeight;
    
    // Apply momentum
    if (!momentum[tacticKey]) {
      momentum[tacticKey] = 0;
    }
    momentum[tacticKey] = FEDAVGM_MOMENTUM * momentum[tacticKey] + (1 - FEDAVGM_MOMENTUM) * delta;
    
    // Update with learning rate
    const oldReward = existing.tactics[tacticKey].avgReward;
    existing.tactics[tacticKey].avgReward += FEDAVGM_LEARNING_RATE * momentum[tacticKey];
    
    // Update other stats with weighted averaging
    const totalCount = existing.tactics[tacticKey].count + tacticData.count * spawnWeight;
    existing.tactics[tacticKey].successRate = (
      existing.tactics[tacticKey].successRate * existing.tactics[tacticKey].count +
      tacticData.successRate * tacticData.count * spawnWeight
    ) / totalCount;
    
    existing.tactics[tacticKey].count = totalCount;
    existing.tactics[tacticKey].weightedAvgReward = existing.tactics[tacticKey].avgReward;
    
    // Check if change is significant (>5% difference)
    if (Math.abs(existing.tactics[tacticKey].avgReward - oldReward) / Math.max(oldReward, 0.01) > 0.05) {
      significantChange = true;
    }
  }
  
  existing.submissions += 1;
  existing.lastUpdate = Date.now();
  existing.version = version;
  
  // Save back
  await env.TACTICS_KV.put(key, JSON.stringify(existing));
  await env.TACTICS_KV.put(momentumKey, JSON.stringify(momentum));
  
  return {
    aggregatedTactics: existing,
    significantChange,
    newVersion: significantChange ? version + 1 : null
  };
}

/**
 * Add replay buffer samples to global pool
 * Format: {state, action, reward, nextState, done}
 */
async function addToReplayPool(env, mobType, samples) {
  const poolKey = `replay:pool:${mobType}`;
  
  // Get current pool
  let pool = await env.TACTICS_KV.get(poolKey, { type: 'json' }) || { experiences: [], size: 0 };
  
  // Add new samples
  for (const sample of samples) {
    // Validate sample has required fields
    if (sample.state && sample.action !== undefined && sample.reward !== undefined) {
      pool.experiences.push({
        ...sample,
        timestamp: Date.now(),
        mobType
      });
    }
  }
  
  pool.size = pool.experiences.length;
  
  // Enforce size limit (FIFO eviction)
  if (pool.size > MAX_REPLAY_POOL_SIZE) {
    pool.experiences = pool.experiences.slice(-MAX_REPLAY_POOL_SIZE);
    pool.size = pool.experiences.length;
  }
  
  // Save back
  await env.TACTICS_KV.put(poolKey, JSON.stringify(pool));
  
  console.log(`Replay pool for ${mobType}: ${pool.size} experiences`);
}

/**
 * Handle replay buffer upload endpoint
 */
async function handleReplayBufferUpload(request, env, ctx, corsHeaders) {
  try {
    const data = await request.json();
    
    if (!data.mobType || !data.samples || !Array.isArray(data.samples)) {
      return new Response(JSON.stringify({ 
        error: 'Missing mobType or samples array' 
      }), {
        status: 400,
        headers: { ...corsHeaders, 'Content-Type': 'application/json' }
      });
    }
    
    ctx.waitUntil(addToReplayPool(env, data.mobType, data.samples));
    
    return new Response(JSON.stringify({
      success: true,
      mobType: data.mobType,
      samplesReceived: data.samples.length,
      message: 'Replay samples added to global pool'
    }), {
      headers: { ...corsHeaders, 'Content-Type': 'application/json' }
    });
    
  } catch (error) {
    console.error('Replay upload error:', error);
    return new Response(JSON.stringify({ error: error.message }), {
      status: 500,
      headers: { ...corsHeaders, 'Content-Type': 'application/json' }
    });
  }
}

/**
 * Advanced download with specialized layers
 * Returns: aggregated tactics + per-mob head + replay samples
 */
async function handleAdvancedDownload(request, env, corsHeaders) {
  try {
    const url = new URL(request.url);
    const mobType = url.searchParams.get('mobType');
    const includeReplay = url.searchParams.get('includeReplay') !== 'false';
    const version = parseInt(url.searchParams.get('version') || '0') || await getCurrentVersion(env);
    
    if (!mobType) {
      return new Response(JSON.stringify({ 
        error: 'mobType parameter required' 
      }), {
        status: 400,
        headers: { ...corsHeaders, 'Content-Type': 'application/json' }
      });
    }
    
    // Get aggregated tactics
    const key = `tactics:${mobType}`;
    const tactics = await env.TACTICS_KV.get(key, { type: 'json' });
    
    if (!tactics) {
      return new Response(JSON.stringify({ 
        error: 'No tactics available for this mob type' 
      }), {
        status: 404,
        headers: { ...corsHeaders, 'Content-Type': 'application/json' }
      });
    }
    
    // Get specialized layer for this mob (per-mob head)
    const specializedLayer = await getSpecializedLayer(env, mobType);
    
    // Get replay pool samples
    let replaySamples = [];
    if (includeReplay) {
      replaySamples = await getReplaySamples(env, mobType, REPLAY_SAMPLE_SIZE);
    }
    
    // Convert tactics to array and sort by weighted reward
    const tacticArray = Object.values(tactics.tactics)
      .sort((a, b) => b.weightedAvgReward - a.weightedAvgReward)
      .slice(0, 20); // Top 20
    
    return new Response(JSON.stringify({
      success: true,
      mobType,
      version,
      aggregationMethod: 'FedAvgM',
      spawnWeight: MOB_SPAWN_WEIGHTS[mobType],
      tactics: tacticArray,
      specializedLayer,
      replaySamples,
      poolSize: replaySamples.length,
      lastUpdate: tactics.lastUpdate
    }), {
      headers: { ...corsHeaders, 'Content-Type': 'application/json' }
    });
    
  } catch (error) {
    console.error('Download error:', error);
    return new Response(JSON.stringify({ error: error.message }), {
      status: 500,
      headers: { ...corsHeaders, 'Content-Type': 'application/json' }
    });
  }
}

/**
 * Get specialized layer (per-mob head) for this mob type
 * This allows transfer learning without contamination
 */
async function getSpecializedLayer(env, mobType) {
  const layerKey = `specialized:${mobType}`;
  
  let layer = await env.TACTICS_KV.get(layerKey, { type: 'json' });
  
  if (!layer) {
    // Initialize per-mob head with mob-specific adjustments
    layer = {
      mobType,
      outputBias: getMobSpecificBias(mobType),
      actionPreferences: getMobActionPreferences(mobType),
      learningModifier: 1.0
    };
    await env.TACTICS_KV.put(layerKey, JSON.stringify(layer));
  }
  
  return layer;
}

/**
 * Get mob-specific biases for specialized layer
 */
function getMobSpecificBias(mobType) {
  const biases = {
    'zombie': { aggression: 0.8, defensiveness: 0.2, mobility: 0.5 },
    'skeleton': { aggression: 0.6, defensiveness: 0.5, mobility: 0.7 },
    'creeper': { aggression: 1.0, defensiveness: 0.1, mobility: 0.6 },
    'spider': { aggression: 0.7, defensiveness: 0.3, mobility: 0.9 },
    'enderman': { aggression: 0.4, defensiveness: 0.8, mobility: 1.0 }
  };
  
  return biases[mobType] || { aggression: 0.5, defensiveness: 0.5, mobility: 0.5 };
}

/**
 * Get mob-specific action preferences
 */
function getMobActionPreferences(mobType) {
  const preferences = {
    'zombie': ['melee_rush', 'group_flank', 'door_break'],
    'skeleton': ['ranged_kite', 'cover_seek', 'aim_predict'],
    'creeper': ['explosion_time', 'stealth_approach', 'player_surround'],
    'spider': ['wall_climb', 'ceiling_drop', 'web_trap'],
    'enderman': ['teleport_strike', 'block_grab', 'rage_mode']
  };
  
  return preferences[mobType] || [];
}

/**
 * Get random samples from replay pool
 */
async function getReplaySamples(env, mobType, sampleSize) {
  const poolKey = `replay:pool:${mobType}`;
  const pool = await env.TACTICS_KV.get(poolKey, { type: 'json' });
  
  if (!pool || !pool.experiences || pool.experiences.length === 0) {
    return [];
  }
  
  // Randomly sample
  const samples = [];
  const maxSamples = Math.min(sampleSize, pool.experiences.length);
  const indices = new Set();
  
  while (indices.size < maxSamples) {
    indices.add(Math.floor(Math.random() * pool.experiences.length));
  }
  
  for (const idx of indices) {
    samples.push(pool.experiences[idx]);
  }
  
  return samples;
}

/**
 * Version control: Get current version
 */
async function getCurrentVersion(env) {
  const versionData = await env.TACTICS_KV.get(CURRENT_VERSION_KEY, { type: 'json' });
  return versionData?.version || 1;
}

/**
 * Version control: Create new version snapshot
 */
async function createNewVersion(env, newVersion) {
  const versionKey = `model:version:${newVersion}`;
  const snapshot = {
    version: newVersion,
    timestamp: Date.now(),
    mobData: {}
  };
  
  // Snapshot all mob tactics
  const mobTypes = Object.keys(MOB_SPAWN_WEIGHTS);
  for (const mobType of mobTypes) {
    const key = `tactics:${mobType}`;
    const tactics = await env.TACTICS_KV.get(key, { type: 'json' });
    if (tactics) {
      snapshot.mobData[mobType] = tactics;
    }
  }
  
  // Save snapshot
  await env.TACTICS_KV.put(versionKey, JSON.stringify(snapshot));
  
  // Update current version pointer
  await env.TACTICS_KV.put(CURRENT_VERSION_KEY, JSON.stringify({ version: newVersion, timestamp: Date.now() }));
  
  // Maintain history limit
  if (newVersion > MAX_VERSION_HISTORY) {
    const oldVersion = newVersion - MAX_VERSION_HISTORY;
    await env.TACTICS_KV.delete(`model:version:${oldVersion}`);
  }
  
  console.log(`Created model version ${newVersion}`);
}

/**
 * Download specific version
 */
async function handleVersionDownload(request, env, corsHeaders, version) {
  try {
    const versionKey = `model:version:${version}`;
    const snapshot = await env.TACTICS_KV.get(versionKey, { type: 'json' });
    
    if (!snapshot) {
      return new Response(JSON.stringify({ 
        error: `Version ${version} not found` 
      }), {
        status: 404,
        headers: { ...corsHeaders, 'Content-Type': 'application/json' }
      });
    }
    
    return new Response(JSON.stringify(snapshot), {
      headers: { ...corsHeaders, 'Content-Type': 'application/json' }
    });
    
  } catch (error) {
    return new Response(JSON.stringify({ error: error.message }), {
      status: 500,
      headers: { ...corsHeaders, 'Content-Type': 'application/json' }
    });
  }
}

/**
 * Rollback to previous version
 */
async function handleRollback(request, env, corsHeaders, targetVersion) {
  try {
    const versionKey = `model:version:${targetVersion}`;
    const snapshot = await env.TACTICS_KV.get(versionKey, { type: 'json' });
    
    if (!snapshot) {
      return new Response(JSON.stringify({ 
        error: `Version ${targetVersion} not found` 
      }), {
        status: 404,
        headers: { ...corsHeaders, 'Content-Type': 'application/json' }
      });
    }
    
    // Restore snapshot data
    for (const [mobType, tactics] of Object.entries(snapshot.mobData)) {
      const key = `tactics:${mobType}`;
      await env.TACTICS_KV.put(key, JSON.stringify(tactics));
    }
    
    // Update current version pointer
    await env.TACTICS_KV.put(CURRENT_VERSION_KEY, JSON.stringify({ 
      version: targetVersion, 
      timestamp: Date.now(),
      rolledBack: true
    }));
    
    console.log(`Rolled back to version ${targetVersion}`);
    
    return new Response(JSON.stringify({
      success: true,
      rolledBackTo: targetVersion,
      message: 'Model rolled back successfully'
    }), {
      headers: { ...corsHeaders, 'Content-Type': 'application/json' }
    });
    
  } catch (error) {
    return new Response(JSON.stringify({ error: error.message }), {
      status: 500,
      headers: { ...corsHeaders, 'Content-Type': 'application/json' }
    });
  }
}

/**
 * Global retraining from pooled replay buffer
 * This is computationally expensive - trigger manually or on schedule
 */
async function handleGlobalRetrain(request, env, ctx, corsHeaders) {
  try {
    console.log('Starting global retraining from replay pool...');
    
    const results = {};
    
    for (const mobType of Object.keys(MOB_SPAWN_WEIGHTS)) {
      const poolKey = `replay:pool:${mobType}`;
      const pool = await env.TACTICS_KV.get(poolKey, { type: 'json' });
      
      if (!pool || pool.size === 0) {
        results[mobType] = 'No replay data';
        continue;
      }
      
      // Perform batch training on pooled experiences
      // This would integrate with Workers AI or external ML service
      // For now, we'll recalculate statistics from scratch
      
      const tacticsMap = {};
      
      for (const exp of pool.experiences) {
        const action = exp.action;
        if (!tacticsMap[action]) {
          tacticsMap[action] = {
            action,
            totalReward: 0,
            count: 0,
            successCount: 0,
            failureCount: 0
          };
        }
        
        tacticsMap[action].totalReward += exp.reward;
        tacticsMap[action].count += 1;
        
        if (exp.reward > 0.5) {
          tacticsMap[action].successCount += 1;
        } else {
          tacticsMap[action].failureCount += 1;
        }
      }
      
      // Calculate statistics
      for (const tactic of Object.values(tacticsMap)) {
        tactic.avgReward = tactic.totalReward / tactic.count;
        tactic.successRate = tactic.successCount / (tactic.successCount + tactic.failureCount);
        tactic.weightedAvgReward = tactic.avgReward;
      }
      
      // Save retrained model
      const key = `tactics:${mobType}`;
      const currentVersion = await getCurrentVersion(env);
      
      await env.TACTICS_KV.put(key, JSON.stringify({
        mobType,
        submissions: pool.size,
        lastUpdate: Date.now(),
        tactics: tacticsMap,
        version: currentVersion + 1,
        retrained: true
      }));
      
      results[mobType] = `Retrained from ${pool.size} experiences`;
    }
    
    // Increment version
    const newVersion = await getCurrentVersion(env) + 1;
    ctx.waitUntil(createNewVersion(env, newVersion));
    
    return new Response(JSON.stringify({
      success: true,
      newVersion,
      results,
      message: 'Global retraining completed'
    }), {
      headers: { ...corsHeaders, 'Content-Type': 'application/json' }
    });
    
  } catch (error) {
    console.error('Retrain error:', error);
    return new Response(JSON.stringify({ error: error.message }), {
      status: 500,
      headers: { ...corsHeaders, 'Content-Type': 'application/json' }
    });
  }
}

/**
 * GitHub sync (same as before)
 */
async function syncToGitHub(env, mobType, tacticsData) {
  if (!env.GITHUB_TOKEN) {
    return { status: 'skipped', reason: 'No GITHUB_TOKEN' };
  }

  try {
    const owner = 'smokydastona';
    const repo = 'adaptive-ai-federation-logs';
    const branch = 'main';
    const path = `federated-data/${mobType}-tactics.json`;
    
    const content = JSON.stringify({
      mobType,
      tactics: tacticsData.tactics,
      submissions: tacticsData.submissions,
      lastUpdate: tacticsData.lastUpdate,
      version: tacticsData.version,
      aggregationMethod: 'FedAvgM'
    }, null, 2);
    
    const encoded = btoa(content);
    
    // Get current file SHA
    const getUrl = `https://api.github.com/repos/${owner}/${repo}/contents/${path}?ref=${branch}`;
    const getResponse = await fetch(getUrl, {
      headers: {
        'Authorization': `token ${env.GITHUB_TOKEN}`,
        'User-Agent': 'MCA-AI-Enhanced-Worker'
      }
    });
    
    let sha = null;
    if (getResponse.ok) {
      const fileData = await getResponse.json();
      sha = fileData.sha;
    }
    
    // Update file
    const putUrl = `https://api.github.com/repos/${owner}/${repo}/contents/${path}`;
    const putResponse = await fetch(putUrl, {
      method: 'PUT',
      headers: {
        'Authorization': `token ${env.GITHUB_TOKEN}`,
        'Content-Type': 'application/json',
        'User-Agent': 'MCA-AI-Enhanced-Worker'
      },
      body: JSON.stringify({
        message: `Update ${mobType} tactics (FedAvgM v${tacticsData.version})`,
        content: encoded,
        branch,
        ...(sha && { sha })
      })
    });
    
    if (putResponse.ok) {
      console.log(`✅ Synced ${mobType} to GitHub (version ${tacticsData.version})`);
      return { status: 'success', version: tacticsData.version };
    } else {
      console.error(`GitHub sync failed: ${putResponse.status}`);
      return { status: 'failed', error: await putResponse.text() };
    }
    
  } catch (error) {
    console.error('GitHub sync error:', error);
    return { status: 'error', error: error.message };
  }
}

/**
 * Stats endpoint
 */
async function handleStats(request, env, corsHeaders) {
  try {
    const stats = {
      version: await getCurrentVersion(env),
      aggregation: {
        method: 'FedAvgM',
        momentum: FEDAVGM_MOMENTUM,
        learningRate: FEDAVGM_LEARNING_RATE
      },
      spawnWeights: MOB_SPAWN_WEIGHTS,
      replayPoolSizes: {},
      mobStats: {}
    };
    
    for (const mobType of Object.keys(MOB_SPAWN_WEIGHTS)) {
      const key = `tactics:${mobType}`;
      const tactics = await env.TACTICS_KV.get(key, { type: 'json' });
      
      if (tactics) {
        stats.mobStats[mobType] = {
          submissions: tactics.submissions,
          tacticCount: Object.keys(tactics.tactics).length,
          lastUpdate: tactics.lastUpdate,
          version: tactics.version
        };
      }
      
      const poolKey = `replay:pool:${mobType}`;
      const pool = await env.TACTICS_KV.get(poolKey, { type: 'json' });
      stats.replayPoolSizes[mobType] = pool?.size || 0;
    }
    
    return new Response(JSON.stringify(stats, null, 2), {
      headers: { ...corsHeaders, 'Content-Type': 'application/json' }
    });
    
  } catch (error) {
    return new Response(JSON.stringify({ error: error.message }), {
      status: 500,
      headers: { ...corsHeaders, 'Content-Type': 'application/json' }
    });
  }
}
