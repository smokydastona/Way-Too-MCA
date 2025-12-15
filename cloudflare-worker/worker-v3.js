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
        // Get coordinator instance
        const coordinatorId = env.FEDERATION_COORDINATOR.idFromName('global');
        const coordinator = env.FEDERATION_COORDINATOR.get(coordinatorId);
        
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
        console.log("📥 MODEL UPLOAD FROM", request.headers.get("cf-connecting-ip"));
        
        // Get coordinator instance (single global coordinator)
        const coordinatorId = env.FEDERATION_COORDINATOR.idFromName('global');
        const coordinator = env.FEDERATION_COORDINATOR.get(coordinatorId);
        
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
        const coordinatorId = env.FEDERATION_COORDINATOR.idFromName('global');
        const coordinator = env.FEDERATION_COORDINATOR.get(coordinatorId);
        
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
        const coordinatorId = env.FEDERATION_COORDINATOR.idFromName('global');
        const coordinator = env.FEDERATION_COORDINATOR.get(coordinatorId);
        
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
            'POST /admin/init-github': 'Test GitHub logging (admin only)'
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

      // Tier progression endpoints (HNN-inspired)
      if (url.pathname === '/api/tiers' && request.method === 'POST') {
        const coordinatorId = env.FEDERATION_COORDINATOR.idFromName('global');
        const coordinator = env.FEDERATION_COORDINATOR.get(coordinatorId);
        
        const coordinatorReq = new Request('https://coordinator/coordinator/tiers/upload', {
          method: 'POST',
          body: await request.text(),
          headers: { 'Content-Type': 'application/json' }
        });
        
        return await coordinator.fetch(coordinatorReq);
      }
      
      if (url.pathname === '/api/tiers' && request.method === 'GET') {
        const coordinatorId = env.FEDERATION_COORDINATOR.idFromName('global');
        const coordinator = env.FEDERATION_COORDINATOR.get(coordinatorId);
        
        const coordinatorReq = new Request('https://coordinator/coordinator/tiers/download', {
          method: 'GET'
        });
        
        return await coordinator.fetch(coordinatorReq);
      }

      // 404 for unknown paths
      return new Response(JSON.stringify({
        error: 'Not Found',
        availableEndpoints: ['/health', '/status', '/api/upload', '/api/global', '/api/heartbeat', '/api/tiers']
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
