/**
 * Cloudflare Worker for MCA AI Enhanced - Federated Learning API
 * 
 * Endpoints:
 * - POST /api/submit-tactics - Submit learned tactics from game servers
 * - GET /api/download-tactics - Download aggregated global tactics
 * - GET /api/analyze-tactics - Get AI-powered analysis of tactics
 * - GET /api/stats - View submission statistics
 * 
 * This worker aggregates tactics from all Minecraft servers and uses
 * Cloudflare Workers AI to analyze patterns and provide recommendations.
 */

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    
    // CORS headers for Minecraft mod requests
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
      // Route requests
      if (url.pathname === '/api/submit-tactics' && request.method === 'POST') {
        return await handleSubmitTactics(request, env, corsHeaders);
      } else if (url.pathname === '/api/download-tactics' && request.method === 'GET') {
        return await handleDownloadTactics(request, env, corsHeaders);
      } else if (url.pathname === '/api/analyze-tactics' && request.method === 'GET') {
        return await handleAnalyzeTactics(request, env, corsHeaders);
      } else if (url.pathname === '/api/stats' && request.method === 'GET') {
        return await handleStats(request, env, corsHeaders);
      } else if (url.pathname === '/' || url.pathname === '/api') {
        return new Response(JSON.stringify({
          service: 'MCA AI Enhanced - Federated Learning API',
          version: '1.1.0',
          features: ['Cloudflare Workers AI', 'Pattern Analysis', 'Recommendations'],
          endpoints: {
            'POST /api/submit-tactics': 'Submit learned tactics',
            'GET /api/download-tactics': 'Download global tactics',
            'GET /api/analyze-tactics': 'Get AI-powered analysis',
            'GET /api/stats': 'View statistics'
          }
        }), {
          headers: { ...corsHeaders, 'Content-Type': 'application/json' }
        });
      }
      
      return new Response('Not Found', { status: 404, headers: corsHeaders });
      
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
 * Handle tactic submission from game servers
 * Expected payload: { mobType, state, action, reward, outcome, timestamp }
 */
async function handleSubmitTactics(request, env, corsHeaders) {
  try {
    const data = await request.json();
    
    // Validate submission
    if (!data.mobType || !data.action || data.reward === undefined) {
      return new Response(JSON.stringify({ 
        error: 'Missing required fields: mobType, action, reward' 
      }), {
        status: 400,
        headers: { ...corsHeaders, 'Content-Type': 'application/json' }
      });
    }
    
    // Validate mob type
    const validMobs = ['zombie', 'skeleton', 'creeper', 'spider'];
    if (!validMobs.includes(data.mobType)) {
      return new Response(JSON.stringify({ 
        error: `Invalid mobType. Must be one of: ${validMobs.join(', ')}` 
      }), {
        status: 400,
        headers: { ...corsHeaders, 'Content-Type': 'application/json' }
      });
    }
    
    // Store in KV (aggregate tactics)
    const key = `tactics:${data.mobType}`;
    const existing = await env.TACTICS_KV.get(key, { type: 'json' }) || {
      mobType: data.mobType,
      submissions: 0,
      lastUpdate: Date.now(),
      tactics: {}
    };
    
    // Aggregate this submission
    const tacticKey = `${data.action}`;
    if (!existing.tactics[tacticKey]) {
      existing.tactics[tacticKey] = {
        action: data.action,
        totalReward: 0,
        count: 0,
        avgReward: 0
      };
    }
    
    existing.tactics[tacticKey].totalReward += data.reward;
    existing.tactics[tacticKey].count += 1;
    existing.tactics[tacticKey].avgReward = 
      existing.tactics[tacticKey].totalReward / existing.tactics[tacticKey].count;
    
    existing.submissions += 1;
    existing.lastUpdate = Date.now();
    
    // Save back to KV
    await env.TACTICS_KV.put(key, JSON.stringify(existing));
    
    // Update global stats
    await incrementStats(env, 'totalSubmissions');
    
    // Schedule GitHub sync if enough new data (every 100 submissions)
    if (existing.submissions % 100 === 0) {
      // Note: GitHub sync would be triggered via scheduled worker or webhook
      console.log(`${data.mobType} reached ${existing.submissions} submissions - sync recommended`);
    }
    
    return new Response(JSON.stringify({
      success: true,
      mobType: data.mobType,
      totalSubmissions: existing.submissions,
      message: 'Tactics received and aggregated'
    }), {
      headers: { ...corsHeaders, 'Content-Type': 'application/json' }
    });
    
  } catch (error) {
    console.error('Submit error:', error);
    return new Response(JSON.stringify({ error: 'Invalid request format' }), {
      status: 400,
      headers: { ...corsHeaders, 'Content-Type': 'application/json' }
    });
  }
}

/**
 * Handle tactic download requests
 * Returns aggregated tactics for all mob types
 */
async function handleDownloadTactics(request, env, corsHeaders) {
  try {
    const mobTypes = ['zombie', 'skeleton', 'creeper', 'spider'];
    const tactics = {};
    
    for (const mobType of mobTypes) {
      const key = `tactics:${mobType}`;
      const data = await env.TACTICS_KV.get(key, { type: 'json' });
      
      if (data) {
        // Convert tactics object to sorted array by avgReward
        const tacticArray = Object.values(data.tactics)
          .sort((a, b) => b.avgReward - a.avgReward);
        
        tactics[mobType] = {
          submissions: data.submissions,
          lastUpdate: data.lastUpdate,
          tactics: tacticArray.slice(0, 20) // Top 20 tactics
        };
      } else {
        tactics[mobType] = {
          submissions: 0,
          lastUpdate: null,
          tactics: []
        };
      }
    }
    
    return new Response(JSON.stringify({
      version: '1.0.0',
      timestamp: Date.now(),
      tactics: tactics
    }), {
      headers: { 
        ...corsHeaders, 
        'Content-Type': 'application/json',
        'Cache-Control': 'public, max-age=300' // Cache for 5 minutes
      }
    });
    
  } catch (error) {
    console.error('Download error:', error);
    return new Response(JSON.stringify({ error: 'Failed to retrieve tactics' }), {
      status: 500,
      headers: { ...corsHeaders, 'Content-Type': 'application/json' }
    });
  }
}

/**
 * Handle statistics requests
 */
async function handleStats(request, env, corsHeaders) {
  try {
    const stats = await env.TACTICS_KV.get('global:stats', { type: 'json' }) || {
      totalSubmissions: 0,
      startTime: Date.now()
    };
    
    const mobTypes = ['zombie', 'skeleton', 'creeper', 'spider'];
    const mobStats = {};
    
    for (const mobType of mobTypes) {
      const key = `tactics:${mobType}`;
      const data = await env.TACTICS_KV.get(key, { type: 'json' });
      mobStats[mobType] = {
        submissions: data?.submissions || 0,
        uniqueTactics: data ? Object.keys(data.tactics).length : 0,
        lastUpdate: data?.lastUpdate || null
      };
    }
    
    return new Response(JSON.stringify({
      global: stats,
      perMob: mobStats
    }), {
      headers: { ...corsHeaders, 'Content-Type': 'application/json' }
    });
    
  } catch (error) {
    console.error('Stats error:', error);
    return new Response(JSON.stringify({ error: 'Failed to retrieve stats' }), {
      status: 500,
      headers: { ...corsHeaders, 'Content-Type': 'application/json' }
    });
  }
}

/**
 * Helper: Increment global statistics
 */
async function incrementStats(env, field) {
  try {
    const stats = await env.TACTICS_KV.get('global:stats', { type: 'json' }) || {
      totalSubmissions: 0,
      startTime: Date.now()
    };
    
    stats[field] = (stats[field] || 0) + 1;
    await env.TACTICS_KV.put('global:stats', JSON.stringify(stats));
  } catch (error) {
    console.error('Stats increment error:', error);
  }
}

/**
 * Handle AI-powered tactic analysis using Cloudflare Workers AI
 */
async function handleAnalyzeTactics(request, env, corsHeaders) {
  try {
    const mobTypes = ['zombie', 'skeleton', 'creeper', 'spider'];
    const analyses = {};
    
    for (const mobType of mobTypes) {
      const key = `tactics:${mobType}`;
      const data = await env.TACTICS_KV.get(key, { type: 'json' });
      
      if (data && data.tactics && Object.keys(data.tactics).length > 0) {
        // Get top tactics
        const tacticArray = Object.values(data.tactics)
          .sort((a, b) => b.avgReward - a.avgReward)
          .slice(0, 10);
        
        // Build prompt for AI analysis
        const tacticSummary = tacticArray.map(t => 
          `${t.action}: avg reward ${t.avgReward.toFixed(2)}, used ${t.count} times`
        ).join('\n');
        
        const prompt = `Analyze these Minecraft mob tactics for ${mobType}s and provide strategic insights:

${tacticSummary}

Provide:
1. Most effective tactic and why
2. Pattern in successful tactics
3. Recommendation for improvement
Keep response under 200 words.`;

        // Call Cloudflare Workers AI
        const aiResponse = await env.AI.run('@cf/meta/llama-3.1-8b-instruct', {
          messages: [
            { role: 'system', content: 'You are an AI analyzing combat tactics in Minecraft. Provide concise, actionable insights.' },
            { role: 'user', content: prompt }
          ],
          max_tokens: 256
        });
        
        analyses[mobType] = {
          submissions: data.submissions,
          topTactics: tacticArray.slice(0, 5),
          aiInsights: aiResponse.response || 'Analysis unavailable'
        };
      } else {
        analyses[mobType] = {
          submissions: 0,
          topTactics: [],
          aiInsights: 'Insufficient data for analysis'
        };
      }
    }
    
    return new Response(JSON.stringify({
      timestamp: Date.now(),
      analyses: analyses,
      model: 'Llama 3.1 8B Instruct'
    }), {
      headers: { 
        ...corsHeaders, 
        'Content-Type': 'application/json',
        'Cache-Control': 'public, max-age=3600' // Cache for 1 hour
      }
    });
    
  } catch (error) {
    console.error('Analysis error:', error);
    return new Response(JSON.stringify({ 
      error: 'Failed to analyze tactics',
      details: error.message 
    }), {
      status: 500,
      headers: { ...corsHeaders, 'Content-Type': 'application/json' }
    });
  }
}
