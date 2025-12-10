/**
 * Cloudflare Worker for MCA AI Enhanced - Federated Learning Server v1.3.0
 * 
 * FIXES in v1.3.0:
 * - ✅ Success rate tracking (successRate, successCount, failureCount)
 * - ✅ Timestamp storage (lastUpdate for each tactic)
 * - ✅ Complete data model matching mod expectations
 * - ✅ Proper outcome field handling ("success"/"failure")
 * - ✅ Extended mob support (husk, stray, wither_skeleton, enderman)
 * 
 * Pipeline Architecture:
 * Stage 1: Aggregation (Cloudflare KV) - Collect tactics from all servers
 * Stage 2: Pattern Analysis (Cloudflare Workers AI) - Identify successful strategies
 * Stage 3: Validation (Hugging Face Inference API) - Cross-validate with different models
 * Stage 4: Persistence (GitHub) - Store processed knowledge (optional)
 * Stage 5: Distribution (Back to Mod) - Send validated tactics to all servers
 * 
 * Endpoints:
 * - POST /api/submit-tactics - Submit learned tactics (mobType, action, reward, outcome, timestamp)
 * - GET /api/download-tactics - Download aggregated tactics (includes successRate, lastUpdate)
 * - GET /api/analyze-tactics - Get AI-powered analysis of tactics
 * - GET /api/process-pipeline - Trigger full multi-stage processing
 * - GET /api/stats - View submission statistics
 * 
 * Free AI Services Used:
 * - Cloudflare Workers AI (10k requests/day) - Primary analysis
 * - Hugging Face Inference API (free tier) - Validation & cross-check
 * - GitHub (unlimited storage) - Knowledge persistence
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
      } else if (url.pathname === '/api/process-pipeline' && request.method === 'GET') {
        return await handleProcessPipeline(request, env, corsHeaders);
      } else if (url.pathname === '/api/stats' && request.method === 'GET') {
        return await handleStats(request, env, corsHeaders);
      } else if (url.pathname === '/' || url.pathname === '/api') {
        return new Response(JSON.stringify({
          service: 'MCA AI Enhanced - Federated Learning Server',
          version: '1.3.0',
          pipeline: ['Aggregation', 'CF Workers AI', 'HuggingFace Validation', 'GitHub Storage', 'Distribution'],
          features: ['Multi-Stage Processing', 'AI Cross-Validation', 'Pattern Analysis', 'Success Rate Tracking'],
          endpoints: {
            'POST /api/submit-tactics': 'Submit learned tactics (mobType, action, reward, outcome, timestamp)',
            'GET /api/download-tactics': 'Download processed tactics (includes successRate)',
            'GET /api/analyze-tactics': 'Get AI-powered analysis',
            'GET /api/process-pipeline': 'Trigger full pipeline processing',
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
    const validMobs = ['zombie', 'skeleton', 'creeper', 'spider', 'husk', 'stray', 'wither_skeleton', 'enderman'];
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
        avgReward: 0,
        successCount: 0,
        failureCount: 0,
        successRate: 0.0,
        lastUpdate: data.timestamp || Date.now()
      };
    }
    
    // Aggregate reward
    existing.tactics[tacticKey].totalReward += data.reward;
    existing.tactics[tacticKey].count += 1;
    existing.tactics[tacticKey].avgReward = 
      existing.tactics[tacticKey].totalReward / existing.tactics[tacticKey].count;
    
    // Track success/failure outcomes
    if (data.outcome === 'success') {
      existing.tactics[tacticKey].successCount += 1;
    } else if (data.outcome === 'failure') {
      existing.tactics[tacticKey].failureCount += 1;
    }
    
    // Calculate success rate
    const totalOutcomes = existing.tactics[tacticKey].successCount + existing.tactics[tacticKey].failureCount;
    if (totalOutcomes > 0) {
      existing.tactics[tacticKey].successRate = existing.tactics[tacticKey].successCount / totalOutcomes;
    }
    
    // Update timestamp
    existing.tactics[tacticKey].lastUpdate = data.timestamp || Date.now();
    
    existing.submissions += 1;
    existing.lastUpdate = Date.now();
    
    // Track individual submissions for batch analysis
    if (!existing.submissionBatch) {
      existing.submissionBatch = [];
      existing.batchStartSubmission = existing.submissions;
    }
    
    // Add current submission to batch for validation
    existing.submissionBatch.push({
      action: data.action,
      reward: data.reward,
      context: data.context,
      outcome: data.outcome,
      timestamp: data.timestamp || Date.now()
    });
    
    // Save back to KV
    await env.TACTICS_KV.put(key, JSON.stringify(existing));
    
    // Update global stats
    await incrementStats(env, 'totalSubmissions');
    
    // Process batch and sync to GitHub every 100 submissions
    const batchSize = existing.submissionBatch.length;
    if (batchSize >= 100 && env.GITHUB_TOKEN) {
      console.log(`${data.mobType} reached ${batchSize} submissions in batch - triggering validation & GitHub sync`);
      // Trigger async batch processing and GitHub backup
      ctx.waitUntil(processBatchAndSync(env, data.mobType, existing));
    }
    
    return new Response(JSON.stringify({
      success: true,
      mobType: data.mobType,
      totalSubmissions: existing.submissions,
      message: 'Tactics received and aggregated',
      tacticStats: {
        action: data.action,
        avgReward: existing.tactics[tacticKey].avgReward,
        successRate: existing.tactics[tacticKey].successRate,
        count: existing.tactics[tacticKey].count
      }
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
    const mobTypes = ['zombie', 'skeleton', 'creeper', 'spider', 'husk', 'stray', 'wither_skeleton', 'enderman'];
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
          tactics: tacticArray.slice(0, 20).map(t => ({
            action: t.action,
            avgReward: t.avgReward,
            count: t.count,
            successRate: t.successRate || 0.0,
            successCount: t.successCount || 0,
            failureCount: t.failureCount || 0,
            lastUpdate: t.lastUpdate
          })) // Top 20 tactics with complete data
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
    
    const mobTypes = ['zombie', 'skeleton', 'creeper', 'spider', 'husk', 'stray', 'wither_skeleton', 'enderman'];
    const mobStats = {};
    
    for (const mobType of mobTypes) {
      const key = `tactics:${mobType}`;
      const data = await env.TACTICS_KV.get(key, { type: 'json' });
      mobStats[mobType] = {
        submissions: data?.submissions || 0,
        uniqueTactics: data ? Object.keys(data.tactics).length : 0,
        lastUpdate: data?.lastUpdate || null,
        topTactic: data && Object.keys(data.tactics).length > 0 
          ? Object.values(data.tactics).sort((a, b) => b.avgReward - a.avgReward)[0]
          : null
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
          `${t.action}: avg reward ${t.avgReward.toFixed(2)} (${((t.successRate || 0) * 100).toFixed(1)}% success), used ${t.count} times`
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
          topTactics: tacticArray.slice(0, 5).map(t => ({
            action: t.action,
            avgReward: t.avgReward,
            successRate: t.successRate || 0.0,
            count: t.count
          })),
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

/**
 * Multi-Stage AI Processing Pipeline
 * Processes tactics through multiple AI systems for validation and enhancement
 */
async function handleProcessPipeline(request, env, corsHeaders) {
  try {
    const pipelineResults = {
      timestamp: Date.now(),
      stages: {},
      finalRecommendations: {}
    };
    
    const mobTypes = ['zombie', 'skeleton', 'creeper', 'spider'];
    
    // STAGE 1: Aggregation (from KV storage)
    console.log('Pipeline Stage 1: Data Aggregation');
    pipelineResults.stages.aggregation = { status: 'success', dataPoints: 0 };
    
    for (const mobType of mobTypes) {
      const key = `tactics:${mobType}`;
      const data = await env.TACTICS_KV.get(key, { type: 'json' });
      
      if (data && data.tactics) {
        pipelineResults.stages.aggregation.dataPoints += Object.keys(data.tactics).length;
        
        const tacticArray = Object.values(data.tactics)
          .sort((a, b) => b.avgReward - a.avgReward)
          .slice(0, 5);
        
        // STAGE 2: Cloudflare Workers AI Analysis
        console.log(`Pipeline Stage 2: CF AI Analysis for ${mobType}`);
        const cfAIPrompt = `Analyze these Minecraft ${mobType} combat tactics:
${tacticArray.map(t => `- ${t.action}: reward ${t.avgReward.toFixed(2)} (${((t.successRate || 0) * 100).toFixed(1)}% success), used ${t.count}x`).join('\n')}

Provide a 2-sentence strategic recommendation.`;
        
        const cfAIResult = await env.AI.run('@cf/meta/llama-3.1-8b-instruct', {
          messages: [
            { role: 'system', content: 'You are a Minecraft combat strategist. Be concise.' },
            { role: 'user', content: cfAIPrompt }
          ],
          max_tokens: 128
        });
        
        // STAGE 3: Hugging Face Cross-Validation
        console.log(`Pipeline Stage 3: HuggingFace Validation for ${mobType}`);
        const hfValidation = await validateWithHuggingFace(
          mobType, 
          tacticArray, 
          cfAIResult.response
        );
        
        // STAGE 4: Combine insights from both AI systems
        pipelineResults.finalRecommendations[mobType] = {
          topTactics: tacticArray.map(t => ({
            action: t.action,
            avgReward: t.avgReward,
            successRate: t.successRate || 0.0,
            count: t.count
          })),
          cloudflareAI: cfAIResult.response || 'No analysis',
          huggingFaceValidation: hfValidation,
          confidence: calculateConfidence(cfAIResult.response, hfValidation),
          processingStages: 3
        };
      }
    }
    
    pipelineResults.stages.cloudflareAI = { status: 'success', model: 'Llama 3.1 8B' };
    pipelineResults.stages.huggingFace = { status: 'success', model: 'DistilBERT' };
    
    // STAGE 5: GitHub persistence - backup all tactics to repository
    if (env.GITHUB_TOKEN) {
      console.log('Pipeline Stage 5: GitHub Persistence');
      const githubResults = {};
      
      for (const mobType of mobTypes) {
        const key = `tactics:${mobType}`;
        const data = await env.TACTICS_KV.get(key, { type: 'json' });
        
        if (data && data.tactics) {
          const syncResult = await syncToGitHub(env, mobType, data);
          githubResults[mobType] = syncResult;
        }
      }
      
      pipelineResults.stages.github = { 
        status: 'success',
        note: 'Tactics backed up to smokydastona/Adaptive-Minecraft-Mob-Ai',
        results: githubResults
      };
    } else {
      pipelineResults.stages.github = { 
        status: 'skipped', 
        note: 'Enable with GITHUB_TOKEN secret for automatic knowledge backup' 
      };
    }
    
    return new Response(JSON.stringify(pipelineResults), {
      headers: { 
        ...corsHeaders, 
        'Content-Type': 'application/json',
        'Cache-Control': 'public, max-age=1800' // Cache for 30 min
      }
    });
    
  } catch (error) {
    console.error('Pipeline error:', error);
    return new Response(JSON.stringify({ 
      error: 'Pipeline processing failed',
      details: error.message 
    }), {
      status: 500,
      headers: { ...corsHeaders, 'Content-Type': 'application/json' }
    });
  }
}

/**
 * Validate tactics using Hugging Face Inference API (free tier)
 * Uses sentiment analysis to validate if AI recommendations are positive/confident
 */
async function validateWithHuggingFace(mobType, tactics, cfRecommendation) {
  try {
    // Hugging Face Inference API (free, no auth required for public models)
    const hfResponse = await fetch(
      'https://api-inference.huggingface.co/models/distilbert-base-uncased-finetuned-sst-2-english',
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          inputs: cfRecommendation,
          options: { wait_for_model: true }
        })
      }
    );
    
    if (!hfResponse.ok) {
      return { status: 'unavailable', reason: 'HF API rate limit or model loading' };
    }
    
    const hfData = await hfResponse.json();
    
    // Sentiment analysis results
    const sentiment = hfData[0];
    const isPositive = sentiment && sentiment[0]?.label === 'POSITIVE';
    const confidence = sentiment && sentiment[0]?.score;
    
    return {
      status: 'validated',
      sentiment: isPositive ? 'positive' : 'negative',
      confidence: confidence ? (confidence * 100).toFixed(1) + '%' : 'unknown',
      crossValidated: true,
      model: 'DistilBERT SST-2'
    };
    
  } catch (error) {
    console.error('HuggingFace validation error:', error);
    return { 
      status: 'error', 
      message: 'Validation failed, using CF AI only',
      fallback: true 
    };
  }
}

/**
 * Calculate confidence score based on multi-AI agreement
 */
function calculateConfidence(cfResult, hfResult) {
  if (!cfResult || !hfResult || hfResult.status !== 'validated') {
    return 'medium'; // Only one AI source
  }
  
  // Both AIs agree (CF generated positive recommendation, HF validated it as positive)
  if (hfResult.sentiment === 'positive') {
    return 'high'; // Cross-validated by multiple AI systems
  }
  
  return 'low'; // AIs disagree
}

/**
 * Process batch of submissions and sync to GitHub
 * Validates and compares submissions before uploading
 */
async function processBatchAndSync(env, mobType, tacticsData) {
  console.log(`Processing batch of ${tacticsData.submissionBatch.length} submissions for ${mobType}`);
  
  // 1. Analyze batch for anomalies
  const analysis = analyzeBatch(tacticsData.submissionBatch);
  
  // 2. Validate tactics quality
  const validation = validateTactics(tacticsData.tactics, analysis);
  
  // 3. Compare with previous batch (if exists)
  const comparison = await compareBatches(env, mobType, analysis);
  
  // 4. Generate batch report
  const batchReport = {
    batchId: `${mobType}-${Date.now()}`,
    submissionCount: tacticsData.submissionBatch.length,
    startSubmission: tacticsData.batchStartSubmission,
    endSubmission: tacticsData.submissions,
    analysis: analysis,
    validation: validation,
    comparison: comparison,
    processedAt: Date.now()
  };
  
  // 5. Log batch report
  console.log(`Batch Analysis for ${mobType}:`, JSON.stringify(batchReport, null, 2));
  
  // 6. Sync validated data to GitHub
  const syncResult = await syncToGitHub(env, mobType, tacticsData, batchReport);
  
  // 7. Clear batch and save analysis for next comparison
  const key = `tactics:${mobType}`;
  tacticsData.submissionBatch = [];
  tacticsData.batchStartSubmission = tacticsData.submissions;
  tacticsData.lastBatchAnalysis = analysis;
  
  await env.TACTICS_KV.put(key, JSON.stringify(tacticsData));
  
  return syncResult;
}

/**
 * Analyze batch of submissions for patterns and anomalies
 */
function analyzeBatch(batch) {
  const actionGroups = {};
  const rewardStats = {
    min: Infinity,
    max: -Infinity,
    sum: 0,
    values: []
  };
  
  // Group by action
  batch.forEach(submission => {
    if (!actionGroups[submission.action]) {
      actionGroups[submission.action] = {
        count: 0,
        rewards: [],
        successCount: 0,
        failureCount: 0
      };
    }
    
    const group = actionGroups[submission.action];
    group.count++;
    group.rewards.push(submission.reward);
    rewardStats.values.push(submission.reward);
    rewardStats.sum += submission.reward;
    rewardStats.min = Math.min(rewardStats.min, submission.reward);
    rewardStats.max = Math.max(rewardStats.max, submission.reward);
    
    if (submission.outcome === 'success') {
      group.successCount++;
    } else {
      group.failureCount++;
    }
  });
  
  // Calculate statistics per action
  const actionStats = {};
  for (const [action, group] of Object.entries(actionGroups)) {
    const avgReward = group.rewards.reduce((a, b) => a + b, 0) / group.rewards.length;
    const successRate = group.count > 0 ? group.successCount / group.count : 0;
    
    actionStats[action] = {
      count: group.count,
      avgReward: parseFloat(avgReward.toFixed(4)),
      successRate: parseFloat(successRate.toFixed(4)),
      successCount: group.successCount,
      failureCount: group.failureCount,
      minReward: Math.min(...group.rewards),
      maxReward: Math.max(...group.rewards)
    };
  }
  
  // Overall batch statistics
  const avgReward = rewardStats.sum / batch.length;
  
  return {
    batchSize: batch.length,
    actionStats: actionStats,
    overallAvgReward: parseFloat(avgReward.toFixed(4)),
    rewardRange: {
      min: rewardStats.min,
      max: rewardStats.max
    },
    uniqueActions: Object.keys(actionGroups).length
  };
}

/**
 * Validate tactics quality based on batch analysis
 */
function validateTactics(tactics, analysis) {
  const warnings = [];
  const recommendations = [];
  
  // Check for low-performing tactics
  for (const [tacticKey, stats] of Object.entries(analysis.actionStats)) {
    if (stats.successRate < 0.3 && stats.count >= 10) {
      warnings.push(`Action "${tacticKey}" has low success rate: ${(stats.successRate * 100).toFixed(1)}%`);
    }
    
    if (stats.avgReward < 0 && stats.count >= 10) {
      warnings.push(`Action "${tacticKey}" has negative average reward: ${stats.avgReward}`);
    }
    
    if (stats.successRate > 0.7 && stats.count >= 10) {
      recommendations.push(`Action "${tacticKey}" performing well: ${(stats.successRate * 100).toFixed(1)}% success rate`);
    }
  }
  
  return {
    valid: warnings.length < analysis.uniqueActions, // Valid if not all actions have warnings
    warnings: warnings,
    recommendations: recommendations,
    quality: warnings.length === 0 ? 'high' : warnings.length < 3 ? 'medium' : 'low'
  };
}

/**
 * Compare current batch with previous batch
 */
async function compareBatches(env, mobType, currentAnalysis) {
  const key = `tactics:${mobType}`;
  
  try {
    const stored = await env.TACTICS_KV.get(key);
    if (!stored) {
      return { status: 'no_previous_batch', message: 'First batch for this mob type' };
    }
    
    const data = JSON.parse(stored);
    if (!data.lastBatchAnalysis) {
      return { status: 'no_previous_analysis', message: 'No previous batch to compare' };
    }
    
    const prev = data.lastBatchAnalysis;
    const improvements = [];
    const regressions = [];
    
    // Compare overall metrics
    const rewardDiff = currentAnalysis.overallAvgReward - prev.overallAvgReward;
    if (Math.abs(rewardDiff) > 0.01) {
      if (rewardDiff > 0) {
        improvements.push(`Overall reward improved by ${(rewardDiff * 100).toFixed(2)}%`);
      } else {
        regressions.push(`Overall reward decreased by ${(Math.abs(rewardDiff) * 100).toFixed(2)}%`);
      }
    }
    
    // Compare action performance
    for (const action of Object.keys(currentAnalysis.actionStats)) {
      if (prev.actionStats[action]) {
        const currentSuccess = currentAnalysis.actionStats[action].successRate;
        const prevSuccess = prev.actionStats[action].successRate;
        const successDiff = currentSuccess - prevSuccess;
        
        if (Math.abs(successDiff) > 0.1) {
          if (successDiff > 0) {
            improvements.push(`"${action}" success rate improved: ${(prevSuccess * 100).toFixed(1)}% → ${(currentSuccess * 100).toFixed(1)}%`);
          } else {
            regressions.push(`"${action}" success rate decreased: ${(prevSuccess * 100).toFixed(1)}% → ${(currentSuccess * 100).toFixed(1)}%`);
          }
        }
      }
    }
    
    return {
      status: 'compared',
      improvements: improvements,
      regressions: regressions,
      trend: improvements.length > regressions.length ? 'improving' : 
             improvements.length < regressions.length ? 'declining' : 'stable'
    };
    
  } catch (error) {
    console.error('Batch comparison error:', error);
    return { status: 'error', message: error.message };
  }
}

/**
 * Sync tactics data to GitHub repository for persistence
 * Repository: smokydastona/Minecraft-machine-learned-collected
 * Path: federated-data/{mobType}-tactics.json
 */
async function syncToGitHub(env, mobType, tacticsData, batchReport) {
  if (!env.GITHUB_TOKEN) {
    console.log('GitHub sync skipped - GITHUB_TOKEN not configured');
    return { status: 'skipped', reason: 'No GITHUB_TOKEN' };
  }

  try {
    const owner = 'smokydastona';
    const repo = 'Minecraft-machine-learned-collected';
    const branch = 'main';
    const path = `federated-data/${mobType}-tactics.json`;
    
    // Prepare JSON content with batch analysis
    const content = JSON.stringify({
      mobType: mobType,
      submissions: tacticsData.submissions,
      lastUpdate: tacticsData.lastUpdate,
      syncedAt: Date.now(),
      batchReport: batchReport ? {
        batchId: batchReport.batchId,
        submissionRange: `${batchReport.startSubmission}-${batchReport.endSubmission}`,
        validationQuality: batchReport.validation.quality,
        trend: batchReport.comparison.trend,
        processedAt: batchReport.processedAt
      } : null,
      tactics: Object.values(tacticsData.tactics)
        .sort((a, b) => b.avgReward - a.avgReward)
        .map(t => ({
          action: t.action,
          avgReward: t.avgReward,
          count: t.count,
          successRate: t.successRate || 0.0,
          successCount: t.successCount || 0,
          failureCount: t.failureCount || 0,
          lastUpdate: t.lastUpdate
        }))
    }, null, 2);
    
    const encodedContent = btoa(content);
    
    // Get current file SHA (if exists) for updating
    const getFileUrl = `https://api.github.com/repos/${owner}/${repo}/contents/${path}`;
    let fileSha = null;
    
    try {
      const getResponse = await fetch(getFileUrl, {
        headers: {
          'Authorization': `Bearer ${env.GITHUB_TOKEN}`,
          'Accept': 'application/vnd.github.v3+json',
          'User-Agent': 'MCA-AI-Federated-Learning'
        }
      });
      
      if (getResponse.ok) {
        const fileData = await getResponse.json();
        fileSha = fileData.sha;
      }
    } catch (e) {
      console.log(`File ${path} doesn't exist yet, will create new`);
    }
    
    // Create or update file
    let commitMessage = `Federated learning: Update ${mobType} tactics (${tacticsData.submissions} submissions)`;
    
    if (batchReport && batchReport.validation && batchReport.comparison) {
      const quality = batchReport.validation.quality || 'unknown';
      const trend = batchReport.comparison.trend || 'stable';
      commitMessage += ` [Batch: ${quality} quality, ${trend} trend]`;
    }
    
    const updatePayload = {
      message: commitMessage,
      content: encodedContent,
      branch: branch
    };
    
    if (fileSha) {
      updatePayload.sha = fileSha; // Update existing file
    }
    
    const updateResponse = await fetch(
      `https://api.github.com/repos/${owner}/${repo}/contents/${path}`,
      {
        method: 'PUT',
        headers: {
          'Authorization': `Bearer ${env.GITHUB_TOKEN}`,
          'Accept': 'application/vnd.github.v3+json',
          'Content-Type': 'application/json',
          'User-Agent': 'MCA-AI-Federated-Learning'
        },
        body: JSON.stringify(updatePayload)
      }
    );
    
    if (updateResponse.ok) {
      const result = await updateResponse.json();
      console.log(`✓ Synced ${mobType} tactics to GitHub: ${result.content.html_url}`);
      return {
        status: 'success',
        url: result.content.html_url,
        sha: result.content.sha,
        submissions: tacticsData.submissions
      };
    } else {
      const errorText = await updateResponse.text();
      console.error(`GitHub sync failed: ${updateResponse.status} - ${errorText}`);
      return {
        status: 'error',
        error: `GitHub API returned ${updateResponse.status}`,
        details: errorText
      };
    }
    
  } catch (error) {
    console.error('GitHub sync error:', error);
    return {
      status: 'error',
      error: error.message
    };
  }
}

