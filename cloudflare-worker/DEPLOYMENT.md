# MCA AI Enhanced - Cloudflare Worker Deployment Guide

## Overview
This Cloudflare Worker provides a free, serverless API for federated learning in the MCA AI Enhanced mod. It handles tactic submissions from game servers and serves aggregated global tactics.

## Prerequisites
- GitHub account (for authentication)
- Cloudflare account (free tier is sufficient)
- Node.js 16+ installed

## Setup Steps

### 1. Install Wrangler CLI
```bash
npm install -g wrangler
```

### 2. Login to Cloudflare
```bash
wrangler login
```
This opens a browser window for authentication.

### 3. Create KV Namespace
```bash
cd cloudflare-worker
wrangler kv:namespace create TACTICS_KV
```

**Important:** Copy the namespace ID from the output. It looks like:
```
{ binding = "TACTICS_KV", id = "abc123def456..." }
```

### 4. Update wrangler.toml
Edit `wrangler.toml` and replace `YOUR_KV_NAMESPACE_ID` with the ID from step 3:
```toml
[[kv_namespaces]]
binding = "TACTICS_KV"
id = "abc123def456..."  # Your actual ID here
```

### 5. Set GitHub Token (Optional - for auto-sync)
If you want automatic GitHub commits, create a Personal Access Token:
1. Go to GitHub Settings → Developer settings → Personal access tokens
2. Generate new token (classic) with `repo` scope
3. Copy the token (starts with `ghp_`)
4. Set as secret:
```bash
wrangler secret put GITHUB_TOKEN
# Paste your token when prompted
```

### 6. Test Locally
```bash
npm install
npm run dev
```
Worker runs at `http://localhost:8787`

Test it:
```bash
# Check status
curl http://localhost:8787/api

# Submit test tactic
curl -X POST http://localhost:8787/api/submit-tactics \
  -H "Content-Type: application/json" \
  -d '{"mobType":"zombie","action":"retreat","reward":5.2}'

# Download tactics
curl http://localhost:8787/api/download-tactics
```

### 7. Deploy to Cloudflare
```bash
npm run deploy
```

**Your API URL will be:** `https://mca-ai-tactics-api.YOUR-SUBDOMAIN.workers.dev`

Copy this URL - you'll need it for the mod configuration!

### 8. Optional: Custom Domain
In Cloudflare dashboard:
1. Workers & Pages → mca-ai-tactics-api → Settings → Triggers
2. Add Custom Domain (e.g., `tactics.yourdomain.com`)

## API Endpoints

### POST /api/submit-tactics
Submit learned tactics from game server.

**Request:**
```json
{
  "mobType": "zombie",
  "action": "retreat",
  "reward": 5.2,
  "outcome": "success",
  "timestamp": 1234567890
}
```

**Response:**
```json
{
  "success": true,
  "mobType": "zombie",
  "totalSubmissions": 42,
  "message": "Tactics received and aggregated"
}
```

### GET /api/download-tactics
Download aggregated global tactics.

**Response:**
```json
{
  "version": "1.0.0",
  "timestamp": 1234567890,
  "tactics": {
    "zombie": {
      "submissions": 1523,
      "lastUpdate": 1234567890,
      "tactics": [
        { "action": "retreat", "avgReward": 6.8, "count": 245 },
        { "action": "circle_strafe", "avgReward": 5.3, "count": 189 }
      ]
    }
  }
}
```

### GET /api/stats
View submission statistics.

## Monitoring

### View Logs
```bash
wrangler tail
```

### View Analytics
Cloudflare Dashboard → Workers & Pages → mca-ai-tactics-api → Analytics

### Check KV Storage
```bash
# List keys
wrangler kv:key list --binding TACTICS_KV

# Get specific key
wrangler kv:key get "tactics:zombie" --binding TACTICS_KV
```

## Cost Estimate
**Cloudflare Free Tier:**
- 100,000 requests/day
- Unlimited bandwidth
- 1 GB KV storage
- 1,000 KV writes/day
- 100,000 KV reads/day

**Expected usage:**
- 1,000 game servers × 12 submissions/hour = 12,000 requests/day
- Well within free tier limits! ✅

## Troubleshooting

### "Namespace not found"
Make sure you updated `wrangler.toml` with the correct KV namespace ID from step 3.

### "Authentication failed"
Run `wrangler login` again.

### "Module not found"
Run `npm install` in the `cloudflare-worker` directory.

### Rate limiting
If you exceed 100k requests/day, upgrade to Workers Paid ($5/month for 10M requests).

## GitHub Auto-Sync (Optional)

To automatically commit aggregated data to GitHub:

1. Uncomment the cron trigger in `wrangler.toml`
2. Add GitHub sync code to `worker.js` (scheduled handler)
3. Set GITHUB_TOKEN secret (see step 5)

The worker will commit aggregated tactics to your repository every hour.

## Security Notes

- API is public (no authentication required for submissions)
- Rate limiting is handled by Cloudflare
- Spam protection: only valid mob types/actions accepted
- KV storage has built-in redundancy and backups

## Next Steps

After deployment:
1. Copy your worker URL
2. Update mod config: `cloudApiEndpoint = "https://your-worker.workers.dev/api"`
3. Build and release new mod version
4. All players automatically sync through your API! 🎉
