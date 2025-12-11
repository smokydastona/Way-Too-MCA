# Federated Tactics Data

This folder contains tools to analyze the GitHub-backed federated tactics JSON files committed by the Cloudflare Worker into the repository `smokydastona/Minecraft-machine-learned-collected` under `federated-data/`.

## Data Schema
Each file is named `<mobType>-tactics.json` and contains:

- `mobType`: e.g., `zombie`, `skeleton`
- `submissions`: total submissions aggregated in KV
- `lastUpdate`: epoch millis of last KV update
- `syncedAt`: epoch millis when Worker synced to GitHub
- `batchReport`: optional object with validation and trend context
  - `batchId`: processing batch identifier
  - `submissionRange`: range of submission indices processed
  - `validationQuality`: qualitative label from AI validation
  - `trend`: `improving`, `stable`, or `declining`
  - `processedAt`: epoch millis of batch processing
- `tactics`: array of tactic entries, sorted by `avgReward` descending
  - `action`: tactic name (e.g., `retreat`, `circle_strafe`)
  - `avgReward`: average reward over submissions
  - `count`: number of observations
  - `successRate`: success ratio (0.0–1.0)
  - `successCount`: successes observed
  - `failureCount`: failures observed
  - `lastUpdate`: epoch millis of last tactic update

## Quick Analysis
Use `analyze_tactics.py` to:

- Load all JSON files from GitHub (or local clone)
- Summarize top tactics per mob
- Plot success rates and avgReward trends
- Export CSV for spreadsheet analysis

## Running Locally
Clone or download the GitHub backup repo, then run:

```powershell
# From this workspace (or any location)
python .\analysis\analyze_tactics.py --source github --owner smokydastona --repo Minecraft-machine-learned-collected

# Or analyze local files from a cloned repo
python .\analysis\analyze_tactics.py --source local --path "C:\\path\\to\\Minecraft-machine-learned-collected\\federated-data"
```

## Output
- `analysis_summary.csv`: per-mob top tactics and stats
- `plots/`: PNG charts of successRate vs avgReward per mob

## Notes
- If a mob has no data yet, it will be skipped
- Epoch millis are converted to ISO timestamps for readability
- The script is dependency-light and avoids heavy ML libraries for portability
