/**
 * Durable Object: Federation Round Coordinator
 * 
 * THE SINGLE SOURCE OF TRUTH for federated learning.
 * 
 * Responsibilities:
 * - Track current federation round
 * - Collect models from contributors
 * - Perform FedAvg aggregation when round closes
 * - Publish single global model
 * - Reject late/duplicate submissions
 */

import { GitHubLogger } from './GitHubLogger.js';

export class FederationCoordinator {
  constructor(state, env) {
    this.state = state;
    this.env = env;
    
    // In-memory cache (backed by durable storage)
    this.currentRound = null;
    this.contributors = null;
    this.models = null;
    this.globalModel = null;
    this.lastAggregation = null;
    
    // GitHub logger (observability only - never blocks federation)
    this.logger = null;
  }

  async fetch(request) {
    // Initialize state on first access
    await this.initialize();

    const url = new URL(request.url);
    const path = url.pathname;

    // Route handling
    if (path === '/coordinator/upload' && request.method === 'POST') {
      return await this.handleUpload(request);
    }
    
    if (path === '/coordinator/global' && request.method === 'GET') {
      return await this.handleGetGlobal(request);
    }
    
    if (path === '/coordinator/status' && request.method === 'GET') {
      return await this.handleStatus(request);
    }
    
    if (path === '/coordinator/heartbeat' && request.method === 'POST') {
      return await this.handleHeartbeat(request);
    }
    
    // Tier progression routes
    if (path === '/coordinator/tiers/upload' && request.method === 'POST') {
      return await this.handleTierUpload(request);
    }
    
    if (path === '/coordinator/tiers/download' && request.method === 'GET') {
      return await this.handleTierDownload(request);
    }
    
    // Tactical episode routes
    if (path === '/coordinator/episodes/upload' && request.method === 'POST') {
      return await this.handleEpisodeUpload(request);
    }
    
    if (path === '/coordinator/tactical-weights' && request.method === 'GET') {
      return await this.handleTacticalWeightsDownload(request);
    }
    
    if (path === '/coordinator/tactical-stats' && request.method === 'GET') {
      return await this.handleTacticalStats(request);
    }

    return new Response('Not Found', { status: 404 });
  }

  /**
   * Initialize coordinator state from durable storage
   */
  async initialize() {
    if (this.currentRound !== null) return; // Already initialized

    this.currentRound = await this.state.storage.get('currentRound') || 1;
    this.contributors = await this.state.storage.get('contributors') || new Map();
    this.models = await this.state.storage.get('models') || new Map();
    this.globalModel = await this.state.storage.get('globalModel') || null;
    this.lastAggregation = await this.state.storage.get('lastAggregation') || Date.now();

    // Initialize GitHub logger if token is available
    if (this.env.GITHUB_TOKEN && this.env.GITHUB_REPO) {
      this.logger = new GitHubLogger(this.env.GITHUB_TOKEN, this.env.GITHUB_REPO);
      console.log(`📝 GitHub logging enabled: ${this.env.GITHUB_REPO}`);
    } else {
      console.log(`⚠️ GitHub logging disabled (no token/repo configured)`);
    }

    console.log(`🎯 Coordinator initialized: Round ${this.currentRound}, ${this.contributors.size} contributors`);
  }

  /**
   * Handle model upload from a server
   */
  async handleUpload(request) {
    const data = await request.json();
    const { serverId, mobType, tactics, bootstrap } = data;

    if (!serverId || !mobType || !tactics) {
      return new Response(JSON.stringify({ error: 'Missing required fields' }), {
        status: 400,
        headers: { 'Content-Type': 'application/json' }
      });
    }

    // Check if this is first-ever upload (bootstrap)
    if (bootstrap === true) {
      console.log(`🚀 BOOTSTRAP UPLOAD from ${serverId} (${mobType})`);
      
      // Accept bootstrap uploads even if round is "closed"
      // This ensures fresh servers can always contribute
      const contributorKey = `${serverId}:${mobType}`;
      
      if (!this.contributors.has(contributorKey)) {
        this.contributors.set(contributorKey, {
          serverId,
          mobType,
          firstSeen: Date.now(),
          lastUpload: Date.now(),
          uploadCount: 1
        });
      }

      // Store model for this contributor
      this.models.set(contributorKey, {
        tactics,
        timestamp: Date.now(),
        round: this.currentRound
      });

      await this.persistState();

      // If we have enough models, trigger aggregation
      if (this.models.size >= 3) {
        console.log(`📊 Triggering aggregation: ${this.models.size} models ready`);
        await this.aggregate();
      }

      return new Response(JSON.stringify({
        success: true,
        round: this.currentRound,
        contributors: this.contributors.size,
        message: 'Bootstrap upload accepted'
      }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' }
      });
    }

    // Normal upload (not bootstrap)
    const contributorKey = `${serverId}:${mobType}`;
    
    // Check if already contributed to this round
    const existingModel = this.models.get(contributorKey);
    if (existingModel && existingModel.round === this.currentRound) {
      return new Response(JSON.stringify({
        error: 'Already contributed to this round',
        round: this.currentRound,
        nextRound: this.currentRound + 1
      }), {
        status: 409,
        headers: { 'Content-Type': 'application/json' }
      });
    }

    // Accept upload
    if (!this.contributors.has(contributorKey)) {
      this.contributors.set(contributorKey, {
        serverId,
        mobType,
        firstSeen: Date.now(),
        lastUpload: Date.now(),
        uploadCount: 1
      });
    } else {
      const contributor = this.contributors.get(contributorKey);
      contributor.lastUpload = Date.now();
      contributor.uploadCount++;
    }

    this.models.set(contributorKey, {
      tactics,
      timestamp: Date.now(),
      round: this.currentRound
    });

    await this.persistState();

    console.log(`✅ Upload accepted from ${serverId} (${mobType}), Round ${this.currentRound}`);

    // Auto-aggregate if we have enough models and enough time has passed
    const timeSinceLastAgg = Date.now() - this.lastAggregation;
    if (this.models.size >= 3 && timeSinceLastAgg > 300000) { // 5 minutes
      console.log(`⏰ Auto-triggering aggregation (5min elapsed, ${this.models.size} models)`);
      await this.aggregate();
    }

    return new Response(JSON.stringify({
      success: true,
      round: this.currentRound,
      contributors: this.contributors.size,
      modelsInRound: this.models.size
    }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' }
    });
  }

  /**
   * FedAvg aggregation: Combine all models into one global model
   */
  async aggregate() {
    if (this.models.size === 0) {
      console.log('⚠️ No models to aggregate');
      return;
    }

    console.log(`📊 AGGREGATING ${this.models.size} models for Round ${this.currentRound}`);

    // Group models by mob type
    const modelsByMob = new Map();
    for (const [key, modelData] of this.models.entries()) {
      const mobType = key.split(':')[1];
      if (!modelsByMob.has(mobType)) {
        modelsByMob.set(mobType, []);
      }
      modelsByMob.get(mobType).push(modelData.tactics);
    }

    // Aggregate each mob type separately
    const aggregated = {};
    for (const [mobType, tacticsList] of modelsByMob.entries()) {
      aggregated[mobType] = this.fedAvg(tacticsList);
    }

    // Store as new global model
    this.globalModel = {
      round: this.currentRound,
      timestamp: Date.now(),
      contributors: this.models.size,
      tactics: aggregated
    };

    this.lastAggregation = Date.now();

    // Persist to GitHub via KV
    await this.publishGlobalModel();

    // LOG TO GITHUB (side effect only - never blocks federation)
    // Keep GitHub layout clean: one file per round that includes the global model snapshot.
    if (this.logger) {
      // Extract mob types and stats for the log
      const mobTypes = Object.keys(aggregated);
      const modelStats = {};
      for (const [mobType, tactics] of Object.entries(aggregated)) {
        modelStats[mobType] = {
          actionCount: Object.keys(tactics).length,
          totalExperiences: Object.values(tactics).reduce((sum, t) => sum + (t.count || 0), 0)
        };
      }

      // Single write per round (async, non-blocking)
      this.logger.logRound({
        round: this.currentRound,
        timestamp: new Date(this.globalModel.timestamp).toISOString(),
        contributors: this.models.size,
        mobTypes,
        modelStats,
        tactics: aggregated
      }).catch(() => {
        // Silent failure - already logged in GitHubLogger
      });
    }

    // Increment round and clear models
    this.currentRound++;
    this.models.clear();

    await this.persistState();

    console.log(`✅ Aggregation complete. Now on Round ${this.currentRound}`);
  }

  /**
   * Federated Averaging (FedAvg) algorithm
   * Average the rewards, counts, and success rates across all contributors
   */
  fedAvg(tacticsList) {
    const aggregated = {};

    // Collect all unique actions
    const allActions = new Set();
    for (const tactics of tacticsList) {
      for (const action in tactics) {
        allActions.add(action);
      }
    }

    // For each action, compute weighted average
    for (const action of allActions) {
      let totalReward = 0;
      let totalCount = 0;
      let totalSuccesses = 0;

      for (const tactics of tacticsList) {
        if (tactics[action]) {
          const t = tactics[action];
          totalReward += (t.avgReward || 0) * (t.count || 0);
          totalCount += t.count || 0;
          totalSuccesses += t.successCount || 0;
        }
      }

      if (totalCount > 0) {
        aggregated[action] = {
          avgReward: totalReward / totalCount,
          count: totalCount,
          successCount: totalSuccesses,
          successRate: totalSuccesses / totalCount
        };
      }
    }

    return aggregated;
  }

  /**
   * Publish global model to GitHub (via KV + Worker)
   */
  async publishGlobalModel() {
    if (!this.globalModel) return;

    // Store in KV for quick access
    for (const [mobType, tactics] of Object.entries(this.globalModel.tactics)) {
      const key = `global:${mobType}`;
      await this.env.TACTICS_KV.put(key, JSON.stringify({
        round: this.globalModel.round,
        timestamp: this.globalModel.timestamp,
        contributors: this.globalModel.contributors,
        tactics
      }));
    }

    console.log(`📤 Published global model (Round ${this.globalModel.round}) to KV`);
  }

  /**
   * Get current global model
   */
  async handleGetGlobal(request) {
    const url = new URL(request.url);
    const mobType = url.searchParams.get('mobType');

    if (!this.globalModel) {
      return new Response(JSON.stringify({
        error: 'No global model available yet',
        round: this.currentRound,
        message: 'Waiting for first aggregation'
      }), {
        status: 404,
        headers: { 'Content-Type': 'application/json' }
      });
    }

    if (mobType) {
      // Return specific mob type
      const tactics = this.globalModel.tactics[mobType];
      if (!tactics) {
        return new Response(JSON.stringify({
          error: `No data for ${mobType}`,
          availableTypes: Object.keys(this.globalModel.tactics)
        }), {
          status: 404,
          headers: { 'Content-Type': 'application/json' }
        });
      }

      return new Response(JSON.stringify({
        round: this.globalModel.round,
        timestamp: this.globalModel.timestamp,
        mobType,
        tactics
      }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' }
      });
    }

    // Return full global model
    return new Response(JSON.stringify(this.globalModel), {
      status: 200,
      headers: { 'Content-Type': 'application/json' }
    });
  }

  /**
   * Get coordinator status
   */
  async handleStatus(request) {
    return new Response(JSON.stringify({
      round: this.currentRound,
      contributors: this.contributors.size,
      modelsInCurrentRound: this.models.size,
      lastAggregation: new Date(this.lastAggregation).toISOString(),
      hasGlobalModel: this.globalModel !== null,
      globalModelRound: this.globalModel?.round || null
    }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' }
    });
  }

  /**
   * Handle heartbeat ping from client
   */
  async handleHeartbeat(request) {
    const data = await request.json();
    const { serverId, activeMobs } = data;

    if (!serverId) {
      return new Response(JSON.stringify({ error: 'Missing serverId' }), {
        status: 400,
        headers: { 'Content-Type': 'application/json' }
      });
    }

    // Update last seen time for all active mobs
    let updated = 0;
    for (const mobType of activeMobs || []) {
      const contributorKey = `${serverId}:${mobType}`;
      if (this.contributors.has(contributorKey)) {
        const contributor = this.contributors.get(contributorKey);
        contributor.lastUpload = Date.now();
        updated++;
      }
    }

    if (updated > 0) {
      await this.persistState();
    }

    console.log(`💓 Heartbeat from ${serverId} (${activeMobs?.length || 0} active mobs)`);

    return new Response(JSON.stringify({
      success: true,
      round: this.currentRound,
      message: `Heartbeat received for ${updated} contributors`
    }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' }
    });
  }

  /**
   * Persist state to durable storage
   */
  async persistState() {
    await this.state.storage.put('currentRound', this.currentRound);
    await this.state.storage.put('contributors', this.contributors);
    await this.state.storage.put('models', this.models);
    await this.state.storage.put('globalModel', this.globalModel);
    await this.state.storage.put('lastAggregation', this.lastAggregation);
  }
  
  // ==================== TIER PROGRESSION ENDPOINTS (HNN-INSPIRED) ====================
  
  /**
   * Handle tier data upload
   */
  async handleTierUpload(request) {
    try {
      const data = await request.json();
      const { experience, tiers } = data;
      
      if (!experience || !tiers) {
        return new Response(JSON.stringify({ error: 'Missing tier data' }), {
          status: 400,
          headers: { 'Content-Type': 'application/json' }
        });
      }
      
      // Get current tier data
      let globalTierData = await this.state.storage.get('tierData') || { experience: {}, tiers: {} };
      
      // Merge experience (keep maximum for each mob type)
      for (const [mobType, exp] of Object.entries(experience)) {
        const currentExp = globalTierData.experience[mobType] || 0;
        if (exp > currentExp) {
          globalTierData.experience[mobType] = exp;
          globalTierData.tiers[mobType] = tiers[mobType] || 'UNTRAINED';
        }
      }
      
      // Save updated tier data
      await this.state.storage.put('tierData', globalTierData);
      
      console.log(`📊 Tier data updated: ${Object.keys(experience).length} mob types`);
      
      return new Response(JSON.stringify({
        success: true,
        message: 'Tier data updated'
      }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' }
      });
      
    } catch (error) {
      console.error('Tier upload error:', error);
      return new Response(JSON.stringify({
        error: 'Failed to process tier data',
        message: error.message
      }), {
        status: 500,
        headers: { 'Content-Type': 'application/json' }
      });
    }
  }
  
  /**
   * Handle tier data download
   */
  async handleTierDownload(request) {
    try {
      const tierData = await this.state.storage.get('tierData') || { experience: {}, tiers: {} };
      
      return new Response(JSON.stringify(tierData), {
        status: 200,
        headers: { 'Content-Type': 'application/json' }
      });
      
    } catch (error) {
      console.error('Tier download error:', error);
      return new Response(JSON.stringify({
        error: 'Failed to retrieve tier data',
        message: error.message
      }), {
        status: 500,
        headers: { 'Content-Type': 'application/json' }
      });
    }
  }
  
  // ==================== END TIER PROGRESSION ENDPOINTS ====================
  
  // ==================== TACTICAL EPISODE FEDERATION ====================
  
  /**
   * Handle combat episode upload - THIS IS THE NEW SIGNAL
   * Aggregates high-level tactical patterns and logs to GitHub for analysis
   */
  async handleEpisodeUpload(request) {
    try {
      const episode = await request.json();
      const {
        mobType,
        sampleCount,
        episodeReward,
        wasSuccessful,
        damageDealt,
        damageTaken,
        durationTicks,
        tacticsUsed,
        playerId,
        timestamp
      } = episode;
      
      if (!mobType || !sampleCount || episodeReward === undefined) {
        return new Response(JSON.stringify({ error: 'Missing episode data' }), {
          status: 400,
          headers: { 'Content-Type': 'application/json' }
        });
      }
      
      // Get current tactical data
      let tacticalData = await this.state.storage.get('tacticalData') || {
        episodes: [],
        weights: {},
        stats: {
          totalEpisodes: 0,
          totalSamples: 0,
          contributors: new Set()
        }
      };
      
      // Add episode to history (keep last 1000 episodes)
      const episodeRecord = {
        mobType,
        sampleCount,
        episodeReward,
        wasSuccessful,
        damageDealt,
        damageTaken,
        durationTicks,
        tacticsUsed,
        playerId: playerId || 'unknown',
        timestamp: timestamp || Date.now()
      };
      
      tacticalData.episodes.push(episodeRecord);
      if (tacticalData.episodes.length > 1000) {
        tacticalData.episodes.shift(); // Remove oldest
      }
      
      // Update statistics
      tacticalData.stats.totalEpisodes++;
      tacticalData.stats.totalSamples += sampleCount;
      if (playerId) {
        tacticalData.stats.contributors.add(playerId);
      }
      
      // Aggregate into tactical weights using exponential moving average
      const LEARNING_RATE = 0.05;
      const successMultiplier = wasSuccessful ? 1.0 : -0.5;
      
      if (!tacticalData.weights[mobType]) {
        tacticalData.weights[mobType] = {};
      }
      
      // Update weights for each tactic used in this episode
      if (tacticsUsed && typeof tacticsUsed === 'object') {
        const totalTactics = Object.values(tacticsUsed).reduce((sum, count) => sum + count, 0);
        
        for (const [tactic, count] of Object.entries(tacticsUsed)) {
          const tacticWeight = (count / totalTactics) * successMultiplier;
          const currentWeight = tacticalData.weights[mobType][tactic] || 0;
          
          // Exponential moving average
          tacticalData.weights[mobType][tactic] = 
            currentWeight * (1 - LEARNING_RATE) + tacticWeight * LEARNING_RATE;
        }
      }
      
      // Save updated tactical data
      await this.state.storage.put('tacticalData', tacticalData);
      
      // LOG TO GITHUB - Comprehensive episode recording
      if (this.logger) {
        // Build detailed episode log
        const logData = {
          round: this.currentRound,
          episode: tacticalData.stats.totalEpisodes,
          timestamp: new Date(timestamp || Date.now()).toISOString(),
          
          // Episode details
          mobType,
          sampleCount,
          episodeReward: parseFloat(episodeReward.toFixed(2)),
          wasSuccessful,
          
          // Combat metrics
          damageDealt: parseFloat((damageDealt || 0).toFixed(1)),
          damageTaken: parseFloat((damageTaken || 0).toFixed(1)),
          damageEfficiency: damageTaken > 0 ? parseFloat((damageDealt / damageTaken).toFixed(2)) : 0,
          durationSeconds: parseFloat((durationTicks / 20).toFixed(1)),
          
          // Tactical analysis
          tacticsUsed: tacticsUsed || {},
          dominantTactic: this.findDominantTactic(tacticsUsed),
          
          // Current weights for this mob type (post-update)
          currentWeights: this.getTopTactics(tacticalData.weights[mobType], 5),

          // Meta information (privacy-safe)
          contributorCount: tacticalData.stats.contributors.size,
          totalEpisodesToDate: tacticalData.stats.totalEpisodes,
          totalSamplesToDate: tacticalData.stats.totalSamples
        };
        
        // Log to GitHub (non-blocking)
        this.logger.logEpisode(logData).catch(err => {
          console.error('GitHub logging failed (non-critical):', err.message);
        });
        
        // Log aggregated stats every 10 episodes
        if (tacticalData.stats.totalEpisodes % 10 === 0) {
          const aggregateLog = {
            round: this.currentRound,
            timestamp: new Date().toISOString(),
            summary: 'AGGREGATE_STATS',
            
            totalEpisodes: tacticalData.stats.totalEpisodes,
            totalSamples: tacticalData.stats.totalSamples,
            avgSamplesPerEpisode: parseFloat((tacticalData.stats.totalSamples / tacticalData.stats.totalEpisodes).toFixed(1)),
            contributors: tacticalData.stats.contributors.size,
            
            // Tactical learning progress per mob type
            mobTypeLearning: Object.keys(tacticalData.weights).map(mob => ({
              mobType: mob,
              topTactics: this.getTopTactics(tacticalData.weights[mob], 3),
              deltaMagnitude: this.calculateDeltaMagnitude(tacticalData.weights[mob])
            }))
          };
          
          this.logger.logAggregate(aggregateLog).catch(err => {
            console.error('Aggregate logging failed:', err.message);
          });
        }
      }
      
      console.log(`🎯 Episode aggregated: ${mobType} (${sampleCount} samples, reward: ${episodeReward.toFixed(1)})`);
      
      return new Response(JSON.stringify({
        success: true,
        episodeNumber: tacticalData.stats.totalEpisodes,
        totalSamples: tacticalData.stats.totalSamples,
        message: 'Episode aggregated successfully'
      }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' }
      });
      
    } catch (error) {
      console.error('Episode upload error:', error);
      return new Response(JSON.stringify({
        error: 'Failed to process episode',
        message: error.message
      }), {
        status: 500,
        headers: { 'Content-Type': 'application/json' }
      });
    }
  }
  
  /**
   * Download aggregated tactical weights
   */
  async handleTacticalWeightsDownload(request) {
    try {
      const tacticalData = await this.state.storage.get('tacticalData') || { weights: {} };
      
      return new Response(JSON.stringify(tacticalData.weights), {
        status: 200,
        headers: { 'Content-Type': 'application/json' }
      });
      
    } catch (error) {
      console.error('Tactical weights download error:', error);
      return new Response(JSON.stringify({
        error: 'Failed to retrieve tactical weights',
        message: error.message
      }), {
        status: 500,
        headers: { 'Content-Type': 'application/json' }
      });
    }
  }
  
  /**
   * Get tactical statistics
   */
  async handleTacticalStats(request) {
    try {
      const tacticalData = await this.state.storage.get('tacticalData') || {
        stats: {
          totalEpisodes: 0,
          totalSamples: 0,
          contributors: new Set()
        },
        weights: {}
      };
      
      const stats = {
        totalEpisodes: tacticalData.stats.totalEpisodes,
        totalSamples: tacticalData.stats.totalSamples,
        contributors: tacticalData.stats.contributors.size,
        avgSamplesPerEpisode: tacticalData.stats.totalEpisodes > 0 
          ? parseFloat((tacticalData.stats.totalSamples / tacticalData.stats.totalEpisodes).toFixed(1))
          : 0,
        mobTypesLearned: Object.keys(tacticalData.weights).length,
        
        // Top tactics per mob type
        topTactics: Object.keys(tacticalData.weights).reduce((acc, mobType) => {
          acc[mobType] = this.getTopTactics(tacticalData.weights[mobType], 3);
          return acc;
        }, {})
      };
      
      return new Response(JSON.stringify(stats), {
        status: 200,
        headers: { 'Content-Type': 'application/json' }
      });
      
    } catch (error) {
      console.error('Tactical stats error:', error);
      return new Response(JSON.stringify({
        error: 'Failed to retrieve tactical statistics',
        message: error.message
      }), {
        status: 500,
        headers: { 'Content-Type': 'application/json' }
      });
    }
  }
  
  /**
   * Helper: Find dominant tactic in episode
   */
  findDominantTactic(tacticsUsed) {
    if (!tacticsUsed || typeof tacticsUsed !== 'object') {
      return 'unknown';
    }
    
    let maxTactic = null;
    let maxCount = 0;
    
    for (const [tactic, count] of Object.entries(tacticsUsed)) {
      if (count > maxCount) {
        maxCount = count;
        maxTactic = tactic;
      }
    }
    
    return maxTactic || 'unknown';
  }
  
  /**
   * Helper: Get top N tactics by weight
   */
  getTopTactics(weights, topN) {
    if (!weights || typeof weights !== 'object') {
      return [];
    }
    
    return Object.entries(weights)
      .sort((a, b) => b[1] - a[1])
      .slice(0, topN)
      .map(([tactic, weight]) => ({
        tactic,
        weight: parseFloat(weight.toFixed(3))
      }));
  }
  
  /**
   * Helper: Calculate delta magnitude (learning activity indicator)
   */
  calculateDeltaMagnitude(weights) {
    if (!weights || typeof weights !== 'object') {
      return 0;
    }
    
    const values = Object.values(weights);
    if (values.length === 0) return 0;
    
    const sum = values.reduce((acc, val) => acc + Math.abs(val), 0);
    return parseFloat((sum / values.length).toFixed(3));
  }
  
  // ==================== END TACTICAL EPISODE FEDERATION ====================
}
