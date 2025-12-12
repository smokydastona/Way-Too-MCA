# Cloudflare Worker v2.0.0 - Deployment Checklist

## Pre-Deployment

- [ ] **Verify wrangler.toml** configuration
  - [ ] KV namespace ID correct
  - [ ] AI binding present: `[ai]` with `binding = "AI"`
  - [ ] GitHub credentials set (token, repo, owner)
  
- [ ] **Test locally** (optional)
  ```bash
  npx wrangler dev
  ```

- [ ] **Run syntax check**
  ```bash
  node -c worker.js
  ```

## Deployment

- [ ] **Deploy to Cloudflare**
  ```bash
  npx wrangler deploy
  ```

- [ ] **Verify deployment**
  - [ ] Visit `https://your-worker.workers.dev/`
  - [ ] Should see: `{"message": "MCA AI Enhanced - Federated Learning API v2.0.0"}`

## Post-Deployment Testing

### 1. Health Check
```bash
curl https://your-worker.workers.dev/
```
Expected: `v2.0.0` in response

### 2. Stats Endpoint (should show advanced ML)
```bash
curl https://your-worker.workers.dev/api/stats
```
Expected: `advancedML` object with `metaLearning`, `sequenceAnalysis`, `transformerInsights`

### 3. Submit Test Sequence
```bash
curl -X POST https://your-worker.workers.dev/api/submit-sequence \
  -H "Content-Type: application/json" \
  -d '{
    "mobType": "zombie",
    "sequence": [
      {"action": "charge", "reward": 5},
      {"action": "retreat", "reward": 3},
      {"action": "flank", "reward": 15}
    ],
    "finalOutcome": "success",
    "duration": 8500,
    "mobId": "test-deployment"
  }'
```
Expected: `{"status": "success", "sequenceLength": 3, ...}`

### 4. Test Meta-Learning
```bash
curl https://your-worker.workers.dev/api/meta-learning?mobType=zombie
```
Expected: Initially empty (no data), after some submissions should show recommendations

### 5. Test Sequence Patterns
```bash
curl https://your-worker.workers.dev/api/sequence-patterns?mobType=zombie
```
Expected: Shows recorded sequences (empty initially)

### 6. Test Enhanced Download
```bash
curl "https://your-worker.workers.dev/api/download?mobType=zombie&includeRecommendations=true"
```
Expected: `version: "2.0.0"`, `features: ["meta-learning", "sequence-patterns"]`

## Monitoring

- [ ] **Check Cloudflare Dashboard**
  - [ ] Workers → adaptive-mob-ai-v2
  - [ ] View requests/day (should stay under 10k)
  - [ ] Check error rate

- [ ] **Monitor KV Storage**
  - [ ] KV Namespaces → TACTICS_KV
  - [ ] Verify new keys: `sequences:*`, `meta-learning:cross-mob`, `analysis:*`

- [ ] **Check GitHub Backup**
  - [ ] Navigate to your GitHub repo
  - [ ] Verify `federated-learning/` directory has recent commits
  - [ ] Check file: `tactics_aggregated.json`

## Client Integration

- [ ] **Update mod configuration**
  - [ ] `adaptivemobai-common.toml` → `cloudflareWorkerUrl`
  - [ ] Set to: `https://your-worker.workers.dev`

- [ ] **Test in-game**
  - [ ] Start Minecraft with mod
  - [ ] Run `/amai info` - should show ML enabled
  - [ ] After combat, check logs for "Submitting tactics to Cloudflare"
  - [ ] Run `/amai stats` - should show federated submissions count

- [ ] **Verify advanced features** (future update)
  - [ ] Sequence submission after combat
  - [ ] Meta-learning recommendations in action selection
  - [ ] Sequence pattern learning

## Rollback Plan

If issues occur:

1. **Quick rollback**:
   ```bash
   npx wrangler rollback
   ```

2. **Deploy previous version**:
   ```bash
   git checkout v1.3.0
   npx wrangler deploy
   ```

3. **Disable advanced ML** (keep v2.0.0 but disable features):
   - Clients use: `/api/download?includeRecommendations=false`
   - Stats will still show advancedML but won't affect clients

## Success Criteria

- ✅ Worker responds to all 6 endpoints
- ✅ Stats show `version: "2.0.0"`
- ✅ Sequences can be submitted and retrieved
- ✅ Meta-learning generates embeddings (after data accumulates)
- ✅ GitHub backup continues working
- ✅ No increase in error rate
- ✅ Requests stay under 10k/day limit

## Notes

- **Data Migration**: v1.3.0 data is fully compatible with v2.0.0
- **Breaking Changes**: None - all new features are additive
- **Backward Compatibility**: Old clients (v1.0.110) work with v2.0.0 worker
- **Performance**: Response times should remain ~200-500ms (embeddings add ~100ms)

## Support

If you encounter issues:

1. Check worker logs: `npx wrangler tail`
2. Verify KV data: `npx wrangler kv:key get --binding=TACTICS_KV "tactics:zombie"`
3. Test Workers AI: https://developers.cloudflare.com/workers-ai/
4. Review error messages in `/api/stats` response

---

**Deployment Date**: ___________  
**Deployed By**: ___________  
**Worker URL**: https://___________
