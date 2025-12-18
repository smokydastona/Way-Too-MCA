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

    // GitHub observability backlog (persisted)
    // Ensures every completed round is eventually written to GitHub, even if GitHub has transient failures.
    this.pendingRoundLogs = null;
    this.lastGitHubLogError = null;
    
    // GitHub logger (observability only - never blocks federation)
    this.logger = null;
  }

  getBrainConfig() {
    const momentum = Number.parseFloat(this.env?.BRAIN_MOMENTUM ?? '0.25');
    const priorA = Number.parseFloat(this.env?.BRAIN_PRIOR_A ?? '2');
    const priorB = Number.parseFloat(this.env?.BRAIN_PRIOR_B ?? '2');
    const iqrK = Number.parseFloat(this.env?.BRAIN_OUTLIER_IQR_K ?? '2.5');
    const weightBlend = Number.parseFloat(this.env?.BRAIN_WEIGHT_BLEND ?? '0.35');
    const weightLearningRate = Number.parseFloat(this.env?.BRAIN_WEIGHT_LR ?? '0.08');
    const softmaxTemp = Number.parseFloat(this.env?.BRAIN_SOFTMAX_TEMP ?? '0.85');
    const maxActions = Number.parseInt(this.env?.BRAIN_MAX_ACTIONS ?? '64', 10);

    return {
      momentum: Number.isFinite(momentum) ? Math.min(0.95, Math.max(0, momentum)) : 0.25,
      priorA: Number.isFinite(priorA) ? Math.min(25, Math.max(0, priorA)) : 2,
      priorB: Number.isFinite(priorB) ? Math.min(25, Math.max(0, priorB)) : 2,
      iqrK: Number.isFinite(iqrK) ? Math.min(10, Math.max(0, iqrK)) : 2.5,
      weightBlend: Number.isFinite(weightBlend) ? Math.min(1, Math.max(0, weightBlend)) : 0.35,
      weightLearningRate: Number.isFinite(weightLearningRate) ? Math.min(0.5, Math.max(0.001, weightLearningRate)) : 0.08,
      softmaxTemp: Number.isFinite(softmaxTemp) ? Math.min(3, Math.max(0.05, softmaxTemp)) : 0.85,
      maxActions: Number.isFinite(maxActions) ? Math.min(256, Math.max(8, maxActions)) : 64
    };
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

    // Internal-only: flush pending GitHub logs (invoked by Worker scheduled handler)
    if (path === '/coordinator/flush-github' && request.method === 'POST') {
      await this.flushPendingGitHubRoundLogs();
      return new Response(JSON.stringify({
        success: true,
        pendingRoundLogs: this.pendingRoundLogs?.length || 0,
        lastGitHubLogError: this.lastGitHubLogError || null
      }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' }
      });
    }

    // Internal-only: backfill the current global model round snapshot to GitHub.
    // Useful if GitHub logging missed earlier rounds due to transient failures.
    if (path === '/coordinator/backfill-current-global' && request.method === 'POST') {
      await this.backfillCurrentGlobalModelRound();
      return new Response(JSON.stringify({
        success: true,
        globalModelRound: this.globalModel?.round || null,
        pendingRoundLogs: this.pendingRoundLogs?.length || 0,
        lastGitHubLogError: this.lastGitHubLogError || null
      }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' }
      });
    }

    // Admin routes (require bearer token)
    if (path === '/coordinator/admin/reset-round' && request.method === 'POST') {
      return await this.handleAdminResetRound(request);
    }

    if (path === '/coordinator/admin/backfill-current-global' && request.method === 'POST') {
      return await this.handleAdminBackfillCurrentGlobal(request);
    }

    if (path === '/coordinator/admin/mark-missing-round' && request.method === 'POST') {
      return await this.handleAdminMarkMissingRound(request);
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

  async handleAdminResetRound(request) {
    if (!this.env.ADMIN_TOKEN) {
      return new Response(JSON.stringify({
        error: 'Admin reset not configured',
        message: 'Set ADMIN_TOKEN as a Cloudflare secret to enable admin reset operations'
      }), {
        status: 503,
        headers: { 'Content-Type': 'application/json' }
      });
    }

    const auth = request.headers.get('Authorization') || '';
    const token = auth.startsWith('Bearer ') ? auth.slice('Bearer '.length) : '';
    if (!token || token !== this.env.ADMIN_TOKEN) {
      return new Response(JSON.stringify({
        error: 'Unauthorized',
        message: 'Missing or invalid Authorization bearer token'
      }), {
        status: 401,
        headers: { 'Content-Type': 'application/json' }
      });
    }

    let body = {};
    try {
      body = await request.json();
    } catch {
      body = {};
    }

    const requestedStartRound = typeof body?.startRound === 'number' ? body.startRound : 1;
    const startRound = Number.isFinite(requestedStartRound) ? Math.floor(requestedStartRound) : 1;
    if (startRound < 1) {
      return new Response(JSON.stringify({
        error: 'Invalid startRound',
        message: 'startRound must be an integer >= 1'
      }), {
        status: 400,
        headers: { 'Content-Type': 'application/json' }
      });
    }

    const before = {
      currentRound: this.currentRound,
      modelsInCurrentRound: this.models?.size || 0,
      contributorsTracked: this.contributors?.size || 0,
      hasGlobalModel: !!this.globalModel
    };

    await this.resetFederationState(startRound);

    const after = {
      currentRound: this.currentRound,
      modelsInCurrentRound: this.models?.size || 0,
      contributorsTracked: this.contributors?.size || 0,
      hasGlobalModel: !!this.globalModel
    };

    return new Response(JSON.stringify({
      success: true,
      message: `Federation state reset; now on round ${this.currentRound}`,
      before,
      after
    }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' }
    });
  }

  async handleAdminBackfillCurrentGlobal(request) {
    if (!this.env.ADMIN_TOKEN) {
      return new Response(JSON.stringify({
        error: 'Admin backfill not configured',
        message: 'Set ADMIN_TOKEN as a Cloudflare secret to enable admin operations'
      }), {
        status: 503,
        headers: { 'Content-Type': 'application/json' }
      });
    }

    const auth = request.headers.get('Authorization') || '';
    const token = auth.startsWith('Bearer ') ? auth.slice('Bearer '.length) : '';
    if (!token || token !== this.env.ADMIN_TOKEN) {
      return new Response(JSON.stringify({
        error: 'Unauthorized',
        message: 'Missing or invalid Authorization bearer token'
      }), {
        status: 401,
        headers: { 'Content-Type': 'application/json' }
      });
    }

    await this.backfillCurrentGlobalModelRound();

    return new Response(JSON.stringify({
      success: true,
      globalModelRound: this.globalModel?.round || null,
      pendingRoundLogs: this.pendingRoundLogs?.length || 0,
      lastGitHubLogError: this.lastGitHubLogError || null
    }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' }
    });
  }

  async handleAdminMarkMissingRound(request) {
    if (!this.env.ADMIN_TOKEN) {
      return new Response(JSON.stringify({
        error: 'Admin ops not configured',
        message: 'Set ADMIN_TOKEN as a Cloudflare secret to enable admin operations'
      }), {
        status: 503,
        headers: { 'Content-Type': 'application/json' }
      });
    }

    const auth = request.headers.get('Authorization') || '';
    const token = auth.startsWith('Bearer ') ? auth.slice('Bearer '.length) : '';
    if (!token || token !== this.env.ADMIN_TOKEN) {
      return new Response(JSON.stringify({
        error: 'Unauthorized',
        message: 'Missing or invalid Authorization bearer token'
      }), {
        status: 401,
        headers: { 'Content-Type': 'application/json' }
      });
    }

    if (!this.logger) {
      return new Response(JSON.stringify({
        error: 'GitHub logging not configured',
        message: 'Set GITHUB_TOKEN and GITHUB_REPO to enable GitHub observability'
      }), {
        status: 503,
        headers: { 'Content-Type': 'application/json' }
      });
    }

    let body = {};
    try {
      body = await request.json();
    } catch {
      body = {};
    }

    const round = typeof body?.round === 'number' ? Math.floor(body.round) : null;
    if (!round || round < 1) {
      return new Response(JSON.stringify({
        error: 'Invalid round',
        message: 'Body must include { round: number } with round >= 1'
      }), {
        status: 400,
        headers: { 'Content-Type': 'application/json' }
      });
    }

    const reason = typeof body?.reason === 'string' ? body.reason : undefined;
    const notes = Array.isArray(body?.notes) ? body.notes : undefined;

    const result = await this.logger.logMissingRound(round, { reason, notes });
    return new Response(JSON.stringify({
      success: true,
      round,
      ...result
    }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' }
    });
  }

  buildRoundPayloadFromGlobalModel() {
    if (!this.globalModel) return null;

    const aggregated = this.globalModel.tactics || {};

    // Privacy-safe contributor stats (counts only)
    const uniqueServers = new Set();
    for (const key of this.contributors?.keys?.() || []) {
      const serverIdPart = String(key).split(':')[0];
      if (serverIdPart) uniqueServers.add(serverIdPart);
    }

    const mobTypes = Object.keys(aggregated);
    const modelStats = {};
    for (const [mobType, tactics] of Object.entries(aggregated)) {
      modelStats[mobType] = {
        distinctActionsObserved: Object.keys(tactics || {}).length,
        totalExperiences: Object.values(tactics || {}).reduce((sum, t) => sum + (t?.count || 0), 0)
      };
    }

    return {
      round: this.globalModel.round,
      timestamp: new Date(this.globalModel.timestamp || Date.now()).toISOString(),
      contributors: {
        servers: uniqueServers.size,
        submissions: typeof this.globalModel.contributors === 'number' ? this.globalModel.contributors : undefined
      },
      mobTypes,
      modelStats,
      tactics: aggregated
    };
  }

  async backfillCurrentGlobalModelRound() {
    if (!this.logger) return;
    const payload = this.buildRoundPayloadFromGlobalModel();
    if (!payload) return;
    await this.enqueueRoundLog(payload);
    await this.flushPendingGitHubRoundLogs();
  }

  async resetFederationState(startRound) {
    // Clear durable storage first to avoid stale keys
    if (typeof this.state?.storage?.deleteAll === 'function') {
      await this.state.storage.deleteAll();
    } else {
      // Fallback: clear known keys
      await this.state.storage.delete('currentRound');
      await this.state.storage.delete('contributors');
      await this.state.storage.delete('models');
      await this.state.storage.delete('globalModel');
      await this.state.storage.delete('lastAggregation');
      await this.state.storage.delete('tacticalData');
      await this.state.storage.delete('tierData');
      await this.state.storage.delete('pendingRoundLogs');
      await this.state.storage.delete('lastGitHubLogError');
    }

    // Reset in-memory state
    this.currentRound = startRound;
    this.contributors = new Map();
    this.models = new Map();
    this.globalModel = null;
    this.lastAggregation = Date.now();

    await this.persistState();

    console.log(`🧹 Admin reset: federation state cleared, round set to ${this.currentRound}`);
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

    this.pendingRoundLogs = await this.state.storage.get('pendingRoundLogs') || [];
    this.lastGitHubLogError = await this.state.storage.get('lastGitHubLogError') || null;

    // Initialize GitHub logger if token is available
    if (this.env.GITHUB_TOKEN && this.env.GITHUB_REPO) {
      this.logger = new GitHubLogger(this.env.GITHUB_TOKEN, this.env.GITHUB_REPO);
      console.log(`📝 GitHub logging enabled: ${this.env.GITHUB_REPO}`);

      // Best-effort: flush any backlog without ever blocking federation.
      this.flushPendingGitHubRoundLogs().catch(() => {});
    } else {
      console.log(`⚠️ GitHub logging disabled (no token/repo configured)`);
    }

    console.log(`🎯 Coordinator initialized: Round ${this.currentRound}, ${this.contributors.size} contributors`);
  }

  async persistGitHubLogState() {
    await this.state.storage.put('pendingRoundLogs', this.pendingRoundLogs || []);
    await this.state.storage.put('lastGitHubLogError', this.lastGitHubLogError || null);
  }

  async enqueueRoundLog(payload) {
    if (!payload || typeof payload.round !== 'number') return;
    if (!Array.isArray(this.pendingRoundLogs)) this.pendingRoundLogs = [];

    const existingIdx = this.pendingRoundLogs.findIndex((x) => x && x.round === payload.round);
    if (existingIdx >= 0) {
      this.pendingRoundLogs[existingIdx] = payload;
    } else {
      this.pendingRoundLogs.push(payload);
      // Bound backlog growth
      if (this.pendingRoundLogs.length > 250) {
        this.pendingRoundLogs = this.pendingRoundLogs.slice(this.pendingRoundLogs.length - 250);
      }
    }

    await this.persistGitHubLogState();
  }

  async flushPendingGitHubRoundLogs() {
    if (!this.logger) return;
    if (!Array.isArray(this.pendingRoundLogs) || this.pendingRoundLogs.length === 0) return;

    // Process in ascending round order
    this.pendingRoundLogs.sort((a, b) => (a?.round ?? 0) - (b?.round ?? 0));

    const stillPending = [];
    for (const payload of this.pendingRoundLogs) {
      if (!payload || typeof payload.round !== 'number') continue;

      try {
        await this.logger.logRound(payload);
      } catch (e) {
        const message = e?.message || String(e);
        this.lastGitHubLogError = {
          timestamp: new Date().toISOString(),
          message
        };
        stillPending.push(payload);
        // Stop early to avoid burning CPU/time if GitHub is down.
        break;
      }
    }

    // Carry over any not-attempted entries after the first failure
    if (stillPending.length > 0) {
      const firstPendingRound = stillPending[0]?.round;
      for (const payload of this.pendingRoundLogs) {
        if (!payload || typeof payload.round !== 'number') continue;
        if (payload.round >= firstPendingRound && !stillPending.some((x) => x.round === payload.round)) {
          stillPending.push(payload);
        }
      }
    }

    this.pendingRoundLogs = stillPending;
    await this.persistGitHubLogState();
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

      // GitHub observability (non-blocking)
      if (this.logger) {
        this.logger.logUpload({
          mobType,
          round: this.currentRound,
          bootstrap: true
        }).catch(() => {
          // Silent failure - GitHubLogger already warns
        });
      }

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

    // GitHub observability (non-blocking)
    if (this.logger) {
      this.logger.logUpload({
        mobType,
        round: this.currentRound,
        bootstrap: false
      }).catch(() => {
        // Silent failure - GitHubLogger already warns
      });
    }

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

    const brain = this.getBrainConfig();

    // Aggregate each mob type separately
    const aggregated = {};
    for (const [mobType, tacticsList] of modelsByMob.entries()) {
      const previous = this.globalModel?.tactics?.[mobType] || null;
      aggregated[mobType] = this.aggregateMobTactics(tacticsList, previous, brain);
    }

    // Store as new global model
    this.globalModel = {
      round: this.currentRound,
      timestamp: Date.now(),
      contributors: this.models.size,
      tactics: aggregated
    };

    this.lastAggregation = Date.now();

    // Update tactical weights using the newly aggregated global model.
    // This reduces client-side compute: clients can pull /api/tactical-weights and use them directly.
    await this.updateTacticalWeightsFromGlobalModel(aggregated, brain);

    // Persist to GitHub via KV
    await this.publishGlobalModel();

    // LOG TO GITHUB (side effect only - never blocks federation)
    // Keep GitHub layout clean: one file per round that includes the global model snapshot.
    if (this.logger) {
      // Privacy-safe contributor stats (counts only, no identifiers persisted)
      const uniqueServers = new Set();
      for (const key of this.models.keys()) {
        // contributorKey format: `${serverId}:${mobType}`
        const serverIdPart = key.split(':')[0];
        if (serverIdPart) uniqueServers.add(serverIdPart);
      }

      // Extract mob types and stats for the log
      const mobTypes = Object.keys(aggregated);
      const modelStats = {};
      for (const [mobType, tactics] of Object.entries(aggregated)) {
        modelStats[mobType] = {
          distinctActionsObserved: Object.keys(tactics).length,
          totalExperiences: Object.values(tactics).reduce((sum, t) => sum + (t.count || 0), 0)
        };
      }

      const payload = {
        round: this.currentRound,
        timestamp: new Date(this.globalModel.timestamp).toISOString(),
        contributors: {
          servers: uniqueServers.size,
          submissions: this.models.size
        },
        mobTypes,
        modelStats,
        tactics: aggregated
      };

      // Persist locally first (Durable Object storage) so this round has a record even if GitHub is down.
      await this.enqueueRoundLog(payload);
      this.flushPendingGitHubRoundLogs().catch(() => {
        // Silent failure - backlog will retry later
      });
    }

    // Increment round and clear models
    this.currentRound++;
    this.models.clear();

    await this.persistState();

    console.log(`✅ Aggregation complete. Now on Round ${this.currentRound}`);
  }

  safeNumber(value, fallback = 0) {
    const n = typeof value === 'number' ? value : Number.parseFloat(value);
    return Number.isFinite(n) ? n : fallback;
  }

  clamp01(x) {
    if (!Number.isFinite(x)) return 0;
    return Math.min(1, Math.max(0, x));
  }

  percentile(sortedValues, p) {
    if (!sortedValues.length) return 0;
    const idx = (sortedValues.length - 1) * p;
    const lower = Math.floor(idx);
    const upper = Math.ceil(idx);
    if (lower === upper) return sortedValues[lower];
    const weight = idx - lower;
    return sortedValues[lower] * (1 - weight) + sortedValues[upper] * weight;
  }

  getIqrFence(values, k) {
    if (values.length < 4) {
      return { low: -Infinity, high: Infinity };
    }
    const sorted = values.slice().sort((a, b) => a - b);
    const q1 = this.percentile(sorted, 0.25);
    const q3 = this.percentile(sorted, 0.75);
    const iqr = q3 - q1;
    if (!Number.isFinite(iqr) || iqr === 0) {
      return { low: -Infinity, high: Infinity };
    }
    return { low: q1 - k * iqr, high: q3 + k * iqr };
  }

  blend(prev, next, momentum) {
    if (!Number.isFinite(next)) return prev;
    if (!Number.isFinite(prev)) return next;
    return prev * momentum + next * (1 - momentum);
  }

  // Robust, privacy-safe aggregation with:
  // - outlier trimming (IQR)
  // - Bayesian-smoothed success rate
  // - momentum blending with previous global model
  aggregateMobTactics(tacticsList, previousMobTactics, brain) {
    const aggregated = {};

    const allActions = new Set();
    for (const tactics of tacticsList) {
      if (!tactics || typeof tactics !== 'object') continue;
      for (const action in tactics) {
        allActions.add(action);
      }
    }

    // Avoid unbounded memory/CPU if a client spams actions.
    const actions = Array.from(allActions).slice(0, brain.maxActions);

    for (const action of actions) {
      const observations = [];

      for (const tactics of tacticsList) {
        const t = tactics?.[action];
        if (!t || typeof t !== 'object') continue;

        const count = Math.max(0, Math.floor(this.safeNumber(t.count, 0)));
        if (count <= 0) continue;

        const avgReward = this.safeNumber(t.avgReward, 0);
        const successCount = Math.max(0, Math.floor(this.safeNumber(t.successCount, 0)));
        const failureCount = Math.max(0, Math.floor(this.safeNumber(t.failureCount, 0)));
        const totalCount = Math.max(count, successCount + failureCount, 1);
        const successRateRaw = this.clamp01(this.safeNumber(t.successRate, successCount / totalCount));

        // Weight is sqrt(count) to reduce dominance from a single server.
        const weight = Math.max(1, Math.sqrt(totalCount));

        observations.push({
          count: totalCount,
          avgReward,
          successCount,
          successRateRaw,
          weight
        });
      }

      if (!observations.length) continue;

      const rewardFence = this.getIqrFence(observations.map(o => o.avgReward), brain.iqrK);
      const successFence = this.getIqrFence(observations.map(o => o.successRateRaw), brain.iqrK);

      const filtered = observations.filter(o =>
        o.avgReward >= rewardFence.low && o.avgReward <= rewardFence.high &&
        o.successRateRaw >= successFence.low && o.successRateRaw <= successFence.high
      );

      // If trimming removed everything (degenerate), fall back to raw.
      const used = filtered.length ? filtered : observations;

      let weightedRewardSum = 0;
      let weightSum = 0;
      let totalCount = 0;
      let totalSuccesses = 0;

      for (const o of used) {
        weightedRewardSum += o.avgReward * o.weight;
        weightSum += o.weight;
        totalCount += o.count;
        totalSuccesses += o.successCount;
      }

      const avgReward = weightSum > 0 ? weightedRewardSum / weightSum : 0;
      const smoothedSuccessRate = (totalSuccesses + brain.priorA) / (totalCount + brain.priorA + brain.priorB);

      const prev = previousMobTactics?.[action];
      const prevAvgReward = prev ? this.safeNumber(prev.avgReward, avgReward) : avgReward;
      const prevSuccessRate = prev ? this.safeNumber(prev.successRate, smoothedSuccessRate) : smoothedSuccessRate;

      const blendedReward = this.blend(prevAvgReward, avgReward, brain.momentum);
      const blendedSuccessRate = this.clamp01(this.blend(prevSuccessRate, smoothedSuccessRate, brain.momentum));

      aggregated[action] = {
        avgReward: blendedReward,
        count: totalCount,
        successCount: totalSuccesses,
        successRate: blendedSuccessRate
      };
    }

    return aggregated;
  }

  softmax(scores, temperature) {
    const temp = Math.max(0.01, temperature);
    const values = Object.values(scores);
    if (!values.length) return {};

    // Normalize with max trick to avoid overflow.
    const maxScore = Math.max(...values);
    const expScores = {};
    let sum = 0;
    for (const [k, v] of Object.entries(scores)) {
      const z = (v - maxScore) / temp;
      // Clamp exponent input for safety.
      const e = Math.exp(Math.max(-50, Math.min(50, z)));
      expScores[k] = e;
      sum += e;
    }
    if (sum <= 0) return {};
    const out = {};
    for (const [k, e] of Object.entries(expScores)) {
      out[k] = e / sum;
    }
    return out;
  }

  // Derive a stable, globally useful weight distribution from aggregated tactics.
  // This is designed to be cheap for clients: they just download weights.
  deriveWeightsFromAggregatedTactics(mobTactics) {
    const scores = {};
    if (!mobTactics || typeof mobTactics !== 'object') return { scores, weights: {} };

    for (const [action, t] of Object.entries(mobTactics)) {
      if (!t || typeof t !== 'object') continue;

      const avgReward = this.safeNumber(t.avgReward, 0);
      const successRate = this.clamp01(this.safeNumber(t.successRate, 0.5));
      const count = Math.max(0, Math.floor(this.safeNumber(t.count, 0)));

      // Score heuristic:
      // - successRate centered at 0.5
      // - avgReward contributes but is squashed
      // - count adds confidence via log
      const rewardSquash = Math.tanh(avgReward / 8);
      const successCentered = (successRate - 0.5) * 2; // [-1, +1]
      const confidence = Math.log1p(count);
      const score = (0.55 * successCentered + 0.45 * rewardSquash) * confidence;
      scores[action] = score;
    }

    return { scores, weights: {} };
  }

  async updateTacticalWeightsFromGlobalModel(aggregatedByMob, brain) {
    try {
      const tacticalData = (await this.state.storage.get('tacticalData')) || {
        episodes: [],
        weights: {},
        stats: { totalEpisodes: 0, totalSamples: 0 }
      };

      const weights = tacticalData.weights || {};

      for (const [mobType, mobTactics] of Object.entries(aggregatedByMob || {})) {
        const derived = this.deriveWeightsFromAggregatedTactics(mobTactics);
        const soft = this.softmax(derived.scores, brain.softmaxTemp);

        if (!weights[mobType]) weights[mobType] = {};

        // Blend:
        // - existing weights may come from episode EMA (recent, local-ish)
        // - derived weights come from aggregated global tactics (stable)
        // Final weight becomes a smoothed mix so clients can rely on it.
        for (const [action, derivedWeight] of Object.entries(soft)) {
          const current = this.safeNumber(weights[mobType][action], 0);
          const mixed = current * (1 - brain.weightBlend) + derivedWeight * brain.weightBlend;
          weights[mobType][action] = this.blend(current, mixed, 1 - brain.weightLearningRate);
        }

        // Keep weights bounded and deterministic.
        for (const action of Object.keys(weights[mobType])) {
          const w = this.safeNumber(weights[mobType][action], 0);
          if (!Number.isFinite(w)) {
            delete weights[mobType][action];
            continue;
          }
          // Allow negative weights (episode learning uses negatives), but cap extremes.
          weights[mobType][action] = Math.max(-1, Math.min(1, w));
        }
      }

      tacticalData.weights = weights;
      await this.state.storage.put('tacticalData', tacticalData);
    } catch (error) {
      // Never fail federation if weights update fails.
      console.error('Tactical weight update failed (non-critical):', error?.message || error);
    }
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
      globalModelRound: this.globalModel?.round || null,
      github: {
        enabled: !!this.logger,
        pendingRoundLogs: Array.isArray(this.pendingRoundLogs) ? this.pendingRoundLogs.length : 0,
        lastError: this.lastGitHubLogError || null
      }
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
          totalSamples: 0
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
        timestamp: timestamp || Date.now()
      };
      
      tacticalData.episodes.push(episodeRecord);
      if (tacticalData.episodes.length > 1000) {
        tacticalData.episodes.shift(); // Remove oldest
      }
      
      // Update statistics
      tacticalData.stats.totalEpisodes++;
      tacticalData.stats.totalSamples += sampleCount;
      
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
            contributors: null,
            
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
          totalSamples: 0
        },
        weights: {}
      };
      
      const stats = {
        totalEpisodes: tacticalData.stats.totalEpisodes,
        totalSamples: tacticalData.stats.totalSamples,
        contributors: null,
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
