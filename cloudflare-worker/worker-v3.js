/**
 * Cloudflare Worker for MCA AI Enhanced - Federated Learning Server v3.0.0
 * 
 * ARCHITECTURAL REWRITE - Durable Object Coordinator
 * 
 * The Problem We Solved:
 * Previous versions had no single source of truth for federation rounds.
 * Multiple servers could upload simultaneously with no coordination.
 * No atomic aggregation step.
 * Result: Theoretical federation that never actually worked.
 * 
 * The Solution:
 * - Durable Object = Single coordinator for ALL federation
 * - Tracks rounds, contributors, models
 * - Performs FedAvg aggregation atomically
 * - Publishes single global model per round
 * - Rejects late/duplicate submissions
 * 
 * New Endpoints:
 * - POST /api/upload          - Upload model to coordinator
 * - GET /api/global           - Get current global model
 * - POST /api/heartbeat       - Keep-alive ping from servers
 * - GET /health               - Worker health check
 * - GET /status               - Federation status (round, contributors, etc.)
 * 
 * Critical Client Requirements:
 * 1. Forced startup pull (GET /api/global) - NO CONDITIONS
 * 2. First-encounter upload (POST /api/upload with bootstrap=true)
 * 3. Heartbeat every 5-10 minutes (POST /api/heartbeat)
 */

import { FederationCoordinator } from './src/FederationCoordinator.js';

export { FederationCoordinator };

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    
    // CORS headers
    const corsHeaders = {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type',
    };
    
    // Handle CORS preflight
    if (request.method === 'OPTIONS') {
      return new Response(null, { headers: corsHeaders });
    }
    
    try {
      const coordinatorId = env.FEDERATION_COORDINATOR.idFromName('global');
      const coordinator = env.FEDERATION_COORDINATOR.get(coordinatorId);

      // Health check endpoint
      if (url.pathname === '/health') {
        return new Response(JSON.stringify({
          status: 'healthy',
          service: 'MCA AI Enhanced - Federated Learning',
          version: '3.0.0',
          timestamp: new Date().toISOString(),
          architecture: 'Durable Object Coordinator'
        }), {
          status: 200,
          headers: { ...corsHeaders, 'Content-Type': 'application/json' }
        });
      }

      // Status endpoint - shows federation state
      if (url.pathname === '/status') {
        // Forward to coordinator
        const coordinatorReq = new Request('https://coordinator/coordinator/status', {
          method: 'GET'
        });
        
        const response = await coordinator.fetch(coordinatorReq);
        const status = await response.json();
        
        return new Response(JSON.stringify({
          ...status,
          worker: 'healthy',
          version: '3.0.0'
        }), {
          status: 200,
          headers: { ...corsHeaders, 'Content-Type': 'application/json' }
        });
      }

      // Upload endpoint - route to coordinator
      if (url.pathname === '/api/upload' && request.method === 'POST') {
        console.log("📥 MODEL UPLOAD");
        
        // Forward request to coordinator
        const coordinatorReq = new Request('https://coordinator/coordinator/upload', {
          method: 'POST',
          headers: request.headers,
          body: request.body
        });
        
        const response = await coordinator.fetch(coordinatorReq);
        const result = await response.json();
        
        return new Response(JSON.stringify(result), {
          status: response.status,
          headers: { ...corsHeaders, 'Content-Type': 'application/json' }
        });
      }

      // Download global model
      if (url.pathname === '/api/global' && request.method === 'GET') {
        const mobType = url.searchParams.get('mobType');
        const coordinatorUrl = mobType 
          ? `https://coordinator/coordinator/global?mobType=${mobType}`
          : 'https://coordinator/coordinator/global';
        
        const coordinatorReq = new Request(coordinatorUrl, {
          method: 'GET'
        });
        
        const response = await coordinator.fetch(coordinatorReq);
        const result = await response.json();
        
        return new Response(JSON.stringify(result), {
          status: response.status,
          headers: { ...corsHeaders, 'Content-Type': 'application/json' }
        });
      }

      // Heartbeat endpoint
      if (url.pathname === '/api/heartbeat' && request.method === 'POST') {
        const coordinatorReq = new Request('https://coordinator/coordinator/heartbeat', {
          method: 'POST',
          headers: request.headers,
          body: request.body
        });
        
        const response = await coordinator.fetch(coordinatorReq);
        const result = await response.json();
        
        return new Response(JSON.stringify(result), {
          status: response.status,
          headers: { ...corsHeaders, 'Content-Type': 'application/json' }
        });
      }

      // Root endpoint - API info
      if (url.pathname === '/' || url.pathname === '/api') {
        return new Response(JSON.stringify({
          service: 'MCA AI Enhanced - Federated Learning Server',
          version: '3.0.0',
          architecture: 'Durable Object Coordinator',
          features: [
            'Single source of truth for federation rounds',
            'Atomic FedAvg aggregation',
            'Duplicate submission prevention',
            'Forced bootstrap support',
            'Heartbeat keep-alive',
            'Round-based model versioning',
            'GitHub observability logging'
          ],
          endpoints: {
            'GET /health': 'Worker health check',
            'GET /status': 'Federation status (round, contributors, models)',
            'POST /api/upload': 'Upload model (include bootstrap=true for first upload)',
            'GET /api/global': 'Download global model (optionally filter by mobType)',
            'POST /api/heartbeat': 'Heartbeat ping (serverId + activeMobs)',
            'GET /api/analyze-tactics': 'Deep tactical analysis (deterministic + optional Workers AI)',
            'POST /admin/init-github': 'Test GitHub logging (admin only)',
            'POST /admin/reset-round': 'Reset federation round/state (admin only)'
          },
          clientRequirements: {
            startupPull: 'MUST call GET /api/global on server start (unconditional)',
            firstEncounter: 'MUST call POST /api/upload with bootstrap=true on first combat',
            heartbeat: 'MUST call POST /api/heartbeat every 5-10 minutes'
          },
          observability: {
            github: env.GITHUB_TOKEN ? 'enabled' : 'disabled',
            repo: env.GITHUB_REPO || 'not configured'
          }
        }), {
          status: 200,
          headers: { ...corsHeaders, 'Content-Type': 'application/json' }
        });
      }

      // Admin endpoint - initialize GitHub logging
      if (url.pathname === '/admin/init-github' && request.method === 'POST') {
        if (!env.GITHUB_TOKEN || !env.GITHUB_REPO) {
          return new Response(JSON.stringify({
            error: 'GitHub not configured',
            message: 'Set GITHUB_TOKEN secret and GITHUB_REPO var in wrangler.toml'
          }), {
            status: 503,
            headers: { ...corsHeaders, 'Content-Type': 'application/json' }
          });
        }

        try {
          const { GitHubLogger } = await import('./src/GitHubLogger.js');
          const logger = new GitHubLogger(env.GITHUB_TOKEN, env.GITHUB_REPO);
          
          // Test write
          await logger.logStatus({
            test: true,
            message: 'GitHub logging initialized',
            timestamp: new Date().toISOString(),
            worker: 'healthy',
            version: '3.0.0'
          });

          return new Response(JSON.stringify({
            success: true,
            message: 'GitHub logging test successful',
            repo: env.GITHUB_REPO,
            file: 'status/latest.json'
          }), {
            status: 200,
            headers: { ...corsHeaders, 'Content-Type': 'application/json' }
          });
        } catch (error) {
          return new Response(JSON.stringify({
            error: 'GitHub logging test failed',
            message: error.message,
            note: 'Check GITHUB_TOKEN permissions and GITHUB_REPO exists'
          }), {
            status: 500,
            headers: { ...corsHeaders, 'Content-Type': 'application/json' }
          });
        }
      }

      // Admin endpoint - reset federation round/state
      // Requires ADMIN_TOKEN secret set in Cloudflare (Authorization: Bearer <token>)
      if (url.pathname === '/admin/reset-round' && request.method === 'POST') {
        if (!env.ADMIN_TOKEN) {
          return new Response(JSON.stringify({
            error: 'Admin reset not configured',
            message: 'Set ADMIN_TOKEN as a Cloudflare secret to enable admin reset operations'
          }), {
            status: 503,
            headers: { ...corsHeaders, 'Content-Type': 'application/json' }
          });
        }

        const auth = request.headers.get('Authorization') || '';
        const token = auth.startsWith('Bearer ') ? auth.slice('Bearer '.length) : '';
        if (!token || token !== env.ADMIN_TOKEN) {
          return new Response(JSON.stringify({
            error: 'Unauthorized',
            message: 'Missing or invalid Authorization bearer token'
          }), {
            status: 401,
            headers: { ...corsHeaders, 'Content-Type': 'application/json' }
          });
        }

        // Forward to coordinator (single global coordinator)
        const coordinatorId = env.FEDERATION_COORDINATOR.idFromName('global');
        const coordinator = env.FEDERATION_COORDINATOR.get(coordinatorId);

        const coordinatorReq = new Request('https://coordinator/coordinator/admin/reset-round', {
          method: 'POST',
          headers: request.headers,
          body: request.body
        });

        const response = await coordinator.fetch(coordinatorReq);
        const result = await response.json();

        return new Response(JSON.stringify(result), {
          status: response.status,
          headers: { ...corsHeaders, 'Content-Type': 'application/json' }
        });
      }

      // Deep analysis endpoint (privacy-safe)
      // Uses deterministic analytics + optional Workers AI summarization.
      if (url.pathname === '/api/analyze-tactics' && request.method === 'GET') {
        const mobType = url.searchParams.get('mobType');
        const useAI = url.searchParams.get('ai') !== '0';
        const refresh = url.searchParams.get('refresh') === '1';
        const includeEpisodes = url.searchParams.get('includeEpisodes') !== '0';

        // Optional protection: if ANALYSIS_TOKEN is set, require it.
        if (env.ANALYSIS_TOKEN) {
          const auth = request.headers.get('Authorization') || '';
          const token = auth.startsWith('Bearer ') ? auth.slice('Bearer '.length) : '';
          if (!token || token !== env.ANALYSIS_TOKEN) {
            return new Response(JSON.stringify({
              error: 'Unauthorized',
              message: 'Missing or invalid Authorization bearer token'
            }), {
              status: 401,
              headers: { ...corsHeaders, 'Content-Type': 'application/json' }
            });
          }
        }

        // Pull core federation state
        const statusResp = await coordinator.fetch(new Request('https://coordinator/coordinator/status', { method: 'GET' }));
        const statusJson = await statusResp.json();

        const globalUrl = mobType
          ? `https://coordinator/coordinator/global?mobType=${mobType}`
          : 'https://coordinator/coordinator/global';

        const globalResp = await coordinator.fetch(new Request(globalUrl, { method: 'GET' }));
        if (!globalResp.ok) {
          const err = await globalResp.json().catch(() => ({}));
          return new Response(JSON.stringify({
            error: 'Global model not available',
            status: globalResp.status,
            details: err
          }), {
            status: globalResp.status,
            headers: { ...corsHeaders, 'Content-Type': 'application/json' }
          });
        }
        const globalJson = await globalResp.json();

        // Pull high-level tactical learning signals
        const weightsResp = await coordinator.fetch(new Request('https://coordinator/coordinator/tactical-weights', { method: 'GET' }));
        const weightsJson = await weightsResp.json();

        const statsResp = await coordinator.fetch(new Request('https://coordinator/coordinator/tactical-stats', { method: 'GET' }));
        const statsJson = await statsResp.json();

        const cacheKey = `analysis:v1:${mobType || 'all'}:globalRound:${globalJson?.round || 0}:episodes:${includeEpisodes ? 1 : 0}`;
        if (!refresh) {
          const cached = await env.TACTICS_KV.get(cacheKey);
          if (cached) {
            return new Response(cached, {
              status: 200,
              headers: { ...corsHeaders, 'Content-Type': 'application/json', 'X-Cache': 'HIT' }
            });
          }
        }

        const deterministic = buildDeterministicAnalysis({
          status: statusJson,
          global: globalJson,
          tacticalWeights: weightsJson,
          tacticalStats: statsJson,
          mobType,
          includeEpisodes
        });

        let ai = null;
        if (useAI && env.AI) {
          const prompt = buildAIPrompt(deterministic);
          const aiResp = await env.AI.run('@cf/meta/llama-3.1-8b-instruct', {
            messages: [
              {
                role: 'system',
                content: 'You are an expert combat-tactics analyst for Minecraft mobs. Be precise, avoid speculation, and do not request or infer any personal data. Output only actionable tactical insights and concise recommendations.'
              },
              { role: 'user', content: prompt }
            ],
            max_tokens: 700
          });

          ai = {
            model: '@cf/meta/llama-3.1-8b-instruct',
            insights: aiResp?.response || null
          };
        }

        const result = {
          schema: {
            name: 'mca-ai-enhanced.analysis',
            version: 1
          },
          generatedAt: new Date().toISOString(),
          cache: {
            key: cacheKey,
            ttlSeconds: 3600
          },
          privacy: {
            personalData: false,
            notes: [
              'This endpoint analyzes aggregate federation state only.',
              'No player UUID/name/IP; no server identifiers are returned.'
            ]
          },
          deterministic,
          ai
        };

        const serialized = JSON.stringify(result, null, 2);
        ctx.waitUntil(env.TACTICS_KV.put(cacheKey, serialized, { expirationTtl: 3600 }));

        return new Response(serialized, {
          status: 200,
          headers: { ...corsHeaders, 'Content-Type': 'application/json', 'X-Cache': 'MISS' }
        });
      }

      // Tier progression endpoints (HNN-inspired)
      if (url.pathname === '/api/tiers' && request.method === 'POST') {
        const coordinatorReq = new Request('https://coordinator/coordinator/tiers/upload', {
          method: 'POST',
          body: await request.text(),
          headers: { 'Content-Type': 'application/json' }
        });
        
        return await coordinator.fetch(coordinatorReq);
      }
      
      if (url.pathname === '/api/tiers' && request.method === 'GET') {
        const coordinatorReq = new Request('https://coordinator/coordinator/tiers/download', {
          method: 'GET'
        });
        
        return await coordinator.fetch(coordinatorReq);
      }
      
      // Tactical episode endpoints (NEW - High-level tactical learning)
      if (url.pathname === '/api/episodes' && request.method === 'POST') {
        const coordinatorReq = new Request('https://coordinator/coordinator/episodes/upload', {
          method: 'POST',
          body: await request.text(),
          headers: { 'Content-Type': 'application/json' }
        });
        
        return await coordinator.fetch(coordinatorReq);
      }
      
      if (url.pathname === '/api/tactical-weights' && request.method === 'GET') {
        const coordinatorReq = new Request('https://coordinator/coordinator/tactical-weights', {
          method: 'GET'
        });
        
        return await coordinator.fetch(coordinatorReq);
      }
      
      if (url.pathname === '/api/tactical-stats' && request.method === 'GET') {
        const coordinatorReq = new Request('https://coordinator/coordinator/tactical-stats', {
          method: 'GET'
        });
        
        return await coordinator.fetch(coordinatorReq);
      }

      // 404 for unknown paths
      return new Response(JSON.stringify({
        error: 'Not Found',
        availableEndpoints: [
          '/health', 
          '/status', 
          '/api/upload', 
          '/api/global', 
          '/api/heartbeat', 
          '/api/tiers',
          '/api/episodes',
          '/api/tactical-weights',
          '/api/tactical-stats'
        ]
      }), {
        status: 404,
        headers: { ...corsHeaders, 'Content-Type': 'application/json' }
      });

    } catch (error) {
      console.error('Worker error:', error);
      return new Response(JSON.stringify({
        error: 'Internal Server Error',
        message: error.message
      }), {
        status: 500,
        headers: { ...corsHeaders, 'Content-Type': 'application/json' }
      });
    }
  }
};

function buildDeterministicAnalysis({ status, global, tacticalWeights, tacticalStats, mobType, includeEpisodes }) {
  const globalTactics = global?.tactics || (global?.mobType && global?.tactics ? { [global.mobType]: global.tactics } : null);
  const byMob = globalTactics && typeof globalTactics === 'object' ? globalTactics : {};
  const mobTypes = mobType ? [mobType] : Object.keys(byMob);

  const analysisByMob = {};

  for (const m of mobTypes) {
    const tactics = byMob[m] || (global?.mobType === m ? global?.tactics : null) || {};
    const actions = Object.entries(tactics).map(([action, t]) => {
      const count = typeof t?.count === 'number' ? t.count : 0;
      const successCount = typeof t?.successCount === 'number' ? t.successCount : 0;
      const successRate = typeof t?.successRate === 'number' ? t.successRate : (count > 0 ? (successCount / count) : 0);
      const avgReward = typeof t?.avgReward === 'number' ? t.avgReward : 0;
      return { action, count, successCount, successRate, avgReward };
    });

    actions.sort((a, b) => (b.avgReward - a.avgReward) || (b.successRate - a.successRate) || (b.count - a.count));

    const totalCount = actions.reduce((sum, a) => sum + a.count, 0);
    const entropy = totalCount > 0
      ? -actions.reduce((sum, a) => {
          const p = a.count / totalCount;
          return p > 0 ? sum + p * Math.log2(p) : sum;
        }, 0)
      : 0;

    const top = actions.slice(0, 10);
    const weights = (tacticalWeights && tacticalWeights[m]) ? tacticalWeights[m] : {};

    analysisByMob[m] = {
      distinctActionsObserved: actions.length,
      totalExperiences: totalCount,
      policyEntropyBits: Number(entropy.toFixed(3)),
      topActions: top,
      tacticalWeightsTop: Object.entries(weights)
        .sort((a, b) => b[1] - a[1])
        .slice(0, 10)
        .map(([tactic, weight]) => ({ tactic, weight: Number((+weight).toFixed(4)) }))
    };
  }

  // Cross-mob similarity (cosine over tactical weights)
  const similarity = [];
  const weightVectors = {};
  for (const [m, w] of Object.entries(tacticalWeights || {})) {
    if (!w || typeof w !== 'object') continue;
    weightVectors[m] = w;
  }

  const mobs = Object.keys(weightVectors);
  for (let i = 0; i < mobs.length; i++) {
    for (let j = i + 1; j < mobs.length; j++) {
      const a = mobs[i];
      const b = mobs[j];
      const sim = cosineSimilarity(weightVectors[a], weightVectors[b]);
      if (Number.isFinite(sim)) {
        similarity.push({ a, b, similarity: Number(sim.toFixed(4)) });
      }
    }
  }
  similarity.sort((x, y) => y.similarity - x.similarity);

  return {
    federation: {
      round: status?.round,
      globalModelRound: status?.globalModelRound,
      modelsInCurrentRound: status?.modelsInCurrentRound,
      hasGlobalModel: status?.hasGlobalModel
    },
    tacticalSignals: {
      stats: tacticalStats,
      includeEpisodes
    },
    perMob: analysisByMob,
    crossMob: {
      mostSimilarPairs: similarity.slice(0, 20)
    }
  };
}

function cosineSimilarity(vecA, vecB) {
  let dot = 0;
  let normA = 0;
  let normB = 0;

  const keys = new Set([...Object.keys(vecA || {}), ...Object.keys(vecB || {})]);
  for (const k of keys) {
    const a = Number(vecA?.[k] || 0);
    const b = Number(vecB?.[k] || 0);
    dot += a * b;
    normA += a * a;
    normB += b * b;
  }

  if (normA === 0 || normB === 0) return 0;
  return dot / (Math.sqrt(normA) * Math.sqrt(normB));
}

function buildAIPrompt(deterministic) {
  const compact = {
    federation: deterministic?.federation,
    tacticalSignals: deterministic?.tacticalSignals,
    perMob: deterministic?.perMob,
    crossMob: deterministic?.crossMob
  };

  return [
    'Analyze the following aggregated Minecraft mob tactics data and produce:',
    '1) Key findings (3-6 bullets)',
    '2) Recommendations per mobType (only if data exists)',
    '3) Cross-mob transfer opportunities (if any)',
    '4) Risks/uncertainties (low sample sizes, noisy rewards)',
    '',
    'Constraints:',
    '- No personal data. No server identifiers. No IPs.',
    '- Focus on actionable, testable ideas.',
    '- Be concise.',
    '',
    'DATA:',
    JSON.stringify(compact)
  ].join('\n');
}
