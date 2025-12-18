/**
 * GitHub Logger - Observability Layer (Side Effect Only)
 * 
 * CRITICAL: This NEVER affects federation logic.
 * All writes are wrapped in try/catch - failures are silent.
 * GitHub is a read-only flight recorder of what already happened.
 * 
 * Purpose: Debug federation rounds, track history, visualize activity
 * 
 * Storage Strategy (Historical Records vs Quick Access):
 *
 * APPEND-ONLY (Historical Records - NEVER Overwritten):
 * - rounds/round-NNNNNN.json: Round metadata + FULL global model snapshot (one file per round)
 * - episodes/YYYY-MM-DD.jsonl: Combat episode logs (appends daily)
 * - uploads/YYYY-MM-DD.jsonl: Upload event tracking (appends daily)
 * - aggregates/YYYY-MM-DD.jsonl: Aggregate statistics (appends daily)
 * - status/history/YYYY-MM-DD.jsonl: Status snapshots (appends daily)
 *
 * OVERWRITES (Quick Access):
 * - status/latest.json: Current federation status (for monitoring dashboards)
 */

export class GitHubLogger {
  constructor(token, repo) {
    this.token = token;
    this.repo = repo; // Format: "owner/repo"
    this.baseUrl = `https://api.github.com/repos/${repo}/contents`;
  }

  async fileExists(path) {
    try {
      const response = await fetch(`${this.baseUrl}/${path}`, {
        headers: {
          'Authorization': `Bearer ${this.token}`,
          'Accept': 'application/vnd.github.v3+json',
          'User-Agent': 'MCA-AI-Federation'
        }
      });
      return response.ok;
    } catch {
      return false;
    }
  }

  /**
   * Log a completed federation round
   * Called AFTER aggregation completes (side effect only)
   * Uses unique filenames per round - NEVER overwrites previous rounds
   */
  async logRound(roundData) {
    const timestamp = new Date().toISOString();
    const roundNumber = String(roundData.round).padStart(6, '0');
    const filename = `rounds/round-${roundNumber}.json`;

    // Avoid rewriting round artifacts (keeps GitHub history clean and prevents spam).
    // If the file already exists, treat this as success.
    const exists = await this.fileExists(filename);
    if (exists) {
      console.log(`📝 GitHub: Round ${roundData.round} already logged (${filename}), skipping`);
      return { skipped: true, filename };
    }

    const tactics = roundData.tactics || {};
    const mobTypeSummary = this.#buildMobTypeSummary(tactics);

    const contributors = this.#normalizeContributors(roundData.contributors);

    const content = JSON.stringify({
      schema: {
        name: 'mca-ai-enhanced.federation.round',
        version: 2,
        description: 'One file per federation round: metadata + full aggregated tactics snapshot (privacy-safe)'
      },
      privacy: {
        personalData: false,
        notes: [
          'No player identifiers (UUID/name/IP) are stored.',
          'No server identifiers are stored; only aggregate counts.'
        ]
      },
      semantics: {
        contributors: {
          meaning: 'Aggregate counts only (no identifiers). `submissions` reflects distinct serverId+mobType contributions for the round; `servers` reflects distinct server contributors for the round.'
        },
        reward: {
          meaning: 'Average tactical reward signal emitted by the mod. This is an internal, unitless score used for ranking/pruning tactics.',
          normalized: false,
          units: 'arbitrary',
          notes: [
            'Reward is computed client-side and may change across mod versions.',
            'Do not assume a fixed numeric range; treat as relative within the same schema/version.'
          ]
        },
        success: {
          meaning: 'Binomial outcome aggregated across contributing samples: successes / attempts.',
          notes: [
            'For low attempts, rates have low confidence; prefer using (successes, attempts) over rate alone.'
          ]
        }
      },
      round: roundData.round,
      timestamp: roundData.timestamp || timestamp,
      contributors,
      mobTypes: roundData.mobTypes || [],
      modelStats: this.#normalizeModelStats(roundData.modelStats || {}),
      aggregation: {
        method: roundData.aggregationMethod || 'FedAvg',
        workerVersion: '3.0.0'
      },
      // Full global model snapshot (this is the important data)
      tactics: this.#normalizeTacticsSnapshot(tactics),
      // Compact summary for quick human scanning
      mobTypeSummary,
      metadata: {
        loggedAt: timestamp,
        source: 'federation-coordinator',
        version: '3.0.0'
      }
    }, null, 2);

    try {
      // This creates a NEW file per round - never overwrites
      await this.writeFile(filename, content, `Federation round ${roundData.round} completed`);
      console.log(`📝 GitHub: Logged round ${roundData.round} to ${filename}`);
      return { skipped: false, filename };
    } catch (error) {
      // IMPORTANT: allow callers (Durable Object backlog) to retry by propagating failures.
      console.warn(`⚠️ GitHub round logging failed (non-critical): ${error?.message || error}`);
      throw error;
    }
  }

  /**
   * Create a placeholder artifact for an unrecoverable round.
   * This keeps the round index contiguous while being explicit that the
   * original snapshot is missing.
   */
  async logMissingRound(roundNumber, details = {}) {
    const round = Number.isFinite(roundNumber) ? Math.floor(roundNumber) : null;
    if (!round || round < 1) throw new Error('Invalid round number');

    const padded = String(round).padStart(6, '0');
    const filename = `rounds/round-${padded}.json`;

    const exists = await this.fileExists(filename);
    if (exists) {
      return { skipped: true, filename };
    }

    const now = new Date().toISOString();
    const content = JSON.stringify({
      schema: {
        name: 'mca-ai-enhanced.federation.round',
        version: 2,
        description: 'Placeholder artifact for an unrecoverable federation round snapshot'
      },
      privacy: {
        personalData: false
      },
      round,
      timestamp: now,
      missing: {
        value: true,
        reason: typeof details?.reason === 'string' ? details.reason : 'Snapshot not recorded at the time and cannot be reconstructed from current Worker state',
        notes: Array.isArray(details?.notes) ? details.notes.slice(0, 20) : undefined
      },
      tactics: {},
      metadata: {
        loggedAt: now,
        source: 'admin',
        version: '3.0.0'
      }
    }, null, 2);

    await this.writeFile(filename, content, `Mark round ${round} as missing`);
    return { skipped: false, filename };
  }

  #buildMobTypeSummary(tacticsByMobType) {
    const summary = {};
    for (const [mobType, tactics] of Object.entries(tacticsByMobType || {})) {
      const actions = Object.entries(tactics || {}).map(([action, t]) => {
        const avgReward = typeof t?.avgReward === 'number' ? t.avgReward : 0;
        const count = typeof t?.count === 'number' ? t.count : 0;
        const successRate = typeof t?.successRate === 'number'
          ? t.successRate
          : (typeof t?.successCount === 'number' && count > 0 ? (t.successCount / count) : 0);

        return { action, avgReward, count, successRate };
      });

      actions.sort((a, b) => (b.avgReward - a.avgReward) || (b.successRate - a.successRate) || (b.count - a.count));

      summary[mobType] = {
        distinctActionsObserved: actions.length,
        topActions: actions.slice(0, 10)
      };
    }
    return summary;
  }

  /**
   * Log an upload event (optional, for detailed tracking)
   */
  async logUpload(uploadData) {
    try {
      const date = new Date().toISOString().split('T')[0];
      const filename = `uploads/${date}.jsonl`;
      
      // Append to daily log file (JSONL format)
      const line = JSON.stringify({
        timestamp: new Date().toISOString(),
        mobType: uploadData.mobType,
        round: uploadData.round,
        bootstrap: uploadData.bootstrap || false
      });

      await this.appendToFile(filename, line + '\n');
      console.log('📝 GitHub: Logged upload event');
    } catch (error) {
      console.warn(`⚠️ GitHub upload logging failed (non-critical): ${error.message}`);
    }
  }

  #normalizeContributors(contributors) {
    if (contributors && typeof contributors === 'object') {
      return {
        servers: typeof contributors.servers === 'number' ? contributors.servers : undefined,
        submissions: typeof contributors.submissions === 'number' ? contributors.submissions : undefined
      };
    }

    // Back-compat: older callers passed a single number (models.size)
    if (typeof contributors === 'number') {
      return { submissions: contributors };
    }

    return {};
  }

  #normalizeModelStats(modelStatsByMobType) {
    const normalized = {};
    for (const [mobType, stats] of Object.entries(modelStatsByMobType || {})) {
      const distinctActionsObserved = typeof stats?.distinctActionsObserved === 'number'
        ? stats.distinctActionsObserved
        : (typeof stats?.actionCount === 'number' ? stats.actionCount : undefined);

      normalized[mobType] = {
        distinctActionsObserved,
        totalExperiences: typeof stats?.totalExperiences === 'number' ? stats.totalExperiences : undefined,
        legacy: {
          actionCount: typeof stats?.actionCount === 'number' ? stats.actionCount : undefined
        }
      };
    }
    return normalized;
  }

  #normalizeTacticsSnapshot(tacticsByMobType) {
    const normalized = {};

    for (const [mobType, tactics] of Object.entries(tacticsByMobType || {})) {
      const mobTactics = {};

      for (const [action, t] of Object.entries(tactics || {})) {
        const count = typeof t?.count === 'number' ? t.count : 0;
        const successCount = typeof t?.successCount === 'number' ? t.successCount : 0;
        const successRate = typeof t?.successRate === 'number'
          ? t.successRate
          : (count > 0 ? (successCount / count) : 0);

        mobTactics[action] = {
          avgReward: typeof t?.avgReward === 'number' ? t.avgReward : 0,
          count,
          successCount,
          failureCount: typeof t?.failureCount === 'number' ? t.failureCount : undefined,
          successRate,
          success: {
            successes: successCount,
            attempts: count,
            rate: successRate
          },
          legacy: {
            weightedAvgReward: typeof t?.weightedAvgReward === 'number' ? t.weightedAvgReward : undefined
          }
        };
      }

      normalized[mobType] = mobTactics;
    }

    return normalized;
  }

  /**
   * Log current status snapshot (for monitoring)
   * Maintains both latest.json for quick access AND historical snapshots
   */
  async logStatus(statusData) {
    try {
      const timestamp = new Date().toISOString();
      const enrichedStatus = {
        ...statusData,
        lastUpdated: timestamp
      };
      const content = JSON.stringify(enrichedStatus, null, 2);
      
      // Write to latest.json for quick access (overwrites)
      await this.writeFile('status/latest.json', content, 'Update federation status');
      
      // ALSO append to historical log (JSONL format - never overwrites)
      const historyLine = JSON.stringify({
        timestamp,
        round: statusData.round,
        contributors: statusData.contributors,
        totalEpisodes: statusData.totalEpisodes,
        mobTypes: statusData.mobTypes || [],
        metrics: statusData.metrics || {}
      });
      
      const date = timestamp.split('T')[0];
      await this.appendToFile(`status/history/${date}.jsonl`, historyLine + '\n');
      
      console.log(`📝 GitHub: Updated status snapshot + history`);
    } catch (error) {
      console.warn(`⚠️ GitHub status logging failed (non-critical): ${error.message}`);
    }
  }

  /**
   * Write a file to GitHub (create or update)
   */
  async writeFile(path, content, message) {
    const url = `${this.baseUrl}/${path}`;
    
    // Check if file exists (to get SHA for update)
    let sha = null;
    try {
      const existingResponse = await fetch(url, {
        headers: {
          'Authorization': `Bearer ${this.token}`,
          'Accept': 'application/vnd.github.v3+json',
          'User-Agent': 'MCA-AI-Federation'
        }
      });
      
      if (existingResponse.ok) {
        const existing = await existingResponse.json();
        sha = existing.sha;
      }
    } catch (error) {
      // File doesn't exist yet, that's fine
    }

    // Write file
    const body = {
      message,
      content: btoa(content), // Base64 encode
      ...(sha && { sha }) // Include SHA if updating
    };

    const response = await fetch(url, {
      method: 'PUT',
      headers: {
        'Authorization': `Bearer ${this.token}`,
        'Accept': 'application/vnd.github.v3+json',
        'Content-Type': 'application/json',
        'User-Agent': 'MCA-AI-Federation'
      },
      body: JSON.stringify(body)
    });

    if (!response.ok) {
      const error = await response.text();
      throw new Error(`GitHub API error: ${response.status} - ${error}`);
    }

    return await response.json();
  }

  /**
   * Append to a file (for JSONL logs)
   * Note: GitHub doesn't support true append, so we read+write
   */
  async appendToFile(path, newContent) {
    const url = `${this.baseUrl}/${path}`;
    
    // Read existing content
    let existingContent = '';
    let sha = null;
    
    try {
      const response = await fetch(url, {
        headers: {
          'Authorization': `Bearer ${this.token}`,
          'Accept': 'application/vnd.github.v3+json',
          'User-Agent': 'MCA-AI-Federation'
        }
      });
      
      if (response.ok) {
        const data = await response.json();
        existingContent = atob(data.content);
        sha = data.sha;
      }
    } catch (error) {
      // File doesn't exist, start fresh
    }

    // Append new content
    const updatedContent = existingContent + newContent;

    // Write back
    const body = {
      message: `Append federation log`,
      content: btoa(updatedContent),
      ...(sha && { sha })
    };

    const response = await fetch(url, {
      method: 'PUT',
      headers: {
        'Authorization': `Bearer ${this.token}`,
        'Accept': 'application/vnd.github.v3+json',
        'Content-Type': 'application/json',
        'User-Agent': 'MCA-AI-Federation'
      },
      body: JSON.stringify(body)
    });

    if (!response.ok) {
      throw new Error(`GitHub append failed: ${response.status}`);
    }

    return await response.json();
  }
  
  /**
   * Log a combat episode (TACTICAL SYSTEM - NEW)
   * This is the dense data we actually want to analyze
   */
  async logEpisode(episodeData) {
    try {
      const date = new Date().toISOString().split('T')[0];
      const filename = `episodes/${date}.jsonl`;
      
      // JSONL format for easy analysis
      const line = JSON.stringify({
        timestamp: episodeData.timestamp || new Date().toISOString(),
        round: episodeData.round,
        episode: episodeData.episode,
        
        // Core episode data
        mobType: episodeData.mobType,
        sampleCount: episodeData.sampleCount,
        episodeReward: episodeData.episodeReward,
        wasSuccessful: episodeData.wasSuccessful,
        
        // Combat metrics
        damageDealt: episodeData.damageDealt,
        damageTaken: episodeData.damageTaken,
        damageEfficiency: episodeData.damageEfficiency,
        durationSeconds: episodeData.durationSeconds,
        
        // Tactical analysis
        tacticsUsed: episodeData.tacticsUsed,
        dominantTactic: episodeData.dominantTactic,
        currentWeights: episodeData.currentWeights,

        // Meta (aggregate-only)
        totalEpisodesToDate: episodeData.totalEpisodesToDate,
        totalSamplesToDate: episodeData.totalSamplesToDate
      });

      await this.appendToFile(filename, line + '\n');
      console.log(`📝 GitHub: Logged episode ${episodeData.episode} (${episodeData.mobType})`);
    } catch (error) {
      console.warn(`⚠️ GitHub episode logging failed (non-critical): ${error.message}`);
    }
  }
  
  /**
   * Log aggregate statistics (every 10 episodes)
   */
  async logAggregate(aggregateData) {
    try {
      const date = new Date().toISOString().split('T')[0];
      const filename = `aggregates/${date}.jsonl`;
      
      const line = JSON.stringify({
        timestamp: aggregateData.timestamp || new Date().toISOString(),
        round: aggregateData.round,
        summary: aggregateData.summary,
        
        totalEpisodes: aggregateData.totalEpisodes,
        totalSamples: aggregateData.totalSamples,
        avgSamplesPerEpisode: aggregateData.avgSamplesPerEpisode,
        contributors: aggregateData.contributors,
        
        mobTypeLearning: aggregateData.mobTypeLearning
      });

      await this.appendToFile(filename, line + '\n');
      console.log(`📝 GitHub: Logged aggregate stats (${aggregateData.totalEpisodes} episodes)`);
    } catch (error) {
      console.warn(`⚠️ GitHub aggregate logging failed (non-critical): ${error.message}`);
    }
  }
  
  /**
   * Log global model tactics (CRITICAL: This tracks AI evolution over time)
   * Creates timestamped snapshots of the aggregated global model
   * NEVER overwrites - each snapshot is a unique historical record
   */
  async logGlobalModel(modelData) {
    // Backwards-compatible shim: keep legacy callers safe, but write into the
    // single per-round artifact to avoid a messy GitHub layout.
    return this.logRound({
      round: modelData.round,
      timestamp: modelData.timestamp,
      contributors: modelData.contributors,
      tactics: modelData.tactics
    });
  }
}
