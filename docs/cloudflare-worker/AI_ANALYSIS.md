# AI Analysis Endpoint (v3)

This repo includes an optional, **privacy-safe** analysis endpoint that performs advanced deterministic analytics and can optionally call **Cloudflare Workers AI** to produce a concise strategy summary.

## Endpoint

- `GET /api/analyze-tactics`

### Query params

- `mobType` (optional): analyze one mob type (e.g. `zombie`). If omitted, analyzes all.
- `ai` (optional): set to `0` to disable Workers AI summarization.
- `refresh` (optional): set to `1` to bypass KV cache and recompute.
- `includeEpisodes` (optional): currently reserved (defaults to on). No personal identifiers are ever processed.

## Privacy contract

- No player UUID/name/IP
- No server identifiers returned
- Only aggregated metrics + optional AI summary

## Caching (Free-tier friendly)

Results are cached in KV for 1 hour to reduce Workers AI calls.

The response includes:
- `X-Cache: HIT|MISS`
- `cache.ttlSeconds`

## Optional protection

If you set `ANALYSIS_TOKEN` as a Cloudflare secret, the endpoint requires:

- `Authorization: Bearer <ANALYSIS_TOKEN>`

This prevents random Internet traffic from consuming Workers AI quota.

## Quick test (PowerShell)

```powershell
$WORKER_URL = "https://mca-ai-tactics-api.mc-ai-datcol.workers.dev"
Invoke-RestMethod -Method Get -Uri "$WORKER_URL/api/analyze-tactics"

# per-mob
Invoke-RestMethod -Method Get -Uri "$WORKER_URL/api/analyze-tactics?mobType=zombie"

# deterministic only (no AI)
Invoke-RestMethod -Method Get -Uri "$WORKER_URL/api/analyze-tactics?ai=0"
```
