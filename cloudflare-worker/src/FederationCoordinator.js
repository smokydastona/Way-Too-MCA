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
}
