import argparse
import json
import os
from datetime import datetime, timezone
from typing import Dict, List

try:
    import requests
except ImportError:
    requests = None

try:
    import matplotlib.pyplot as plt
except ImportError:
    plt = None

import csv


def epoch_to_iso(ms: int) -> str:
    try:
        return datetime.fromtimestamp(ms / 1000.0, tz=timezone.utc).isoformat()
    except Exception:
        return ""


def fetch_github_directory(owner: str, repo: str, path: str = "federated-data") -> List[Dict]:
    if requests is None:
        raise RuntimeError("requests is required for GitHub source; install with `pip install requests`. ")
    url = f"https://api.github.com/repos/{owner}/{repo}/contents/{path}"
    r = requests.get(url, headers={"Accept": "application/vnd.github.v3+json", "User-Agent": "MCA-AI-Analysis"})
    r.raise_for_status()
    return r.json()


def fetch_github_file(owner: str, repo: str, path: str) -> Dict:
    if requests is None:
        raise RuntimeError("requests is required for GitHub source; install with `pip install requests`. ")
    url = f"https://api.github.com/repos/{owner}/{repo}/contents/{path}"
    r = requests.get(url, headers={"Accept": "application/vnd.github.v3+json", "User-Agent": "MCA-AI-Analysis"})
    r.raise_for_status()
    data = r.json()
    if "content" in data:
        import base64
        decoded = base64.b64decode(data["content"]).decode("utf-8")
        return json.loads(decoded)
    raise RuntimeError("Unexpected GitHub API response: missing content")


def load_local_files(path: str) -> List[str]:
    if not os.path.isdir(path):
        raise FileNotFoundError(f"Directory not found: {path}")
    return [os.path.join(path, f) for f in os.listdir(path) if f.endswith("-tactics.json")]


def parse_args():
    p = argparse.ArgumentParser(description="Analyze federated mob tactics data")
    p.add_argument("--source", choices=["github", "local"], required=True, help="Data source: GitHub or local directory")
    p.add_argument("--owner", help="GitHub owner (required if source=github)")
    p.add_argument("--repo", help="GitHub repo (required if source=github)")
    p.add_argument("--path", help="Local path to federated-data directory (required if source=local)")
    p.add_argument("--plots", action="store_true", help="Generate PNG plots (requires matplotlib)")
    return p.parse_args()


def summarize_mob(data: Dict) -> Dict:
    tactics = data.get("tactics", [])
    top = tactics[0] if tactics else None
    return {
        "mobType": data.get("mobType", ""),
        "submissions": data.get("submissions", 0),
        "syncedAt": epoch_to_iso(data.get("syncedAt", 0)) if data.get("syncedAt") else "",
        "lastUpdate": epoch_to_iso(data.get("lastUpdate", 0)) if data.get("lastUpdate") else "",
        "topAction": top.get("action") if top else "",
        "topAvgReward": round(top.get("avgReward", 0.0), 4) if top else 0.0,
        "topSuccessRate": round((top.get("successRate", 0.0) or 0.0), 4) if top else 0.0,
        "topCount": top.get("count", 0) if top else 0,
        "trend": (data.get("batchReport", {}) or {}).get("trend", "")
    }


def write_csv(rows: List[Dict], out_path: str):
    if not rows:
        print("No data to write.")
        return
    fieldnames = list(rows[0].keys())
    with open(out_path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)
    print(f"Wrote CSV summary: {out_path}")


def generate_plots(all_data: List[Dict], out_dir: str):
    if plt is None:
        print("matplotlib not available. Install with `pip install matplotlib` or omit --plots.")
        return
    os.makedirs(out_dir, exist_ok=True)
    for entry in all_data:
        mob = entry.get("mobType", "unknown")
        tactics = entry.get("tactics", [])
        if not tactics:
            continue
        xs = [t.get("avgReward", 0.0) for t in tactics]
        ys = [(t.get("successRate", 0.0) or 0.0) for t in tactics]
        labels = [t.get("action", "") for t in tactics]
        plt.figure(figsize=(6, 4))
        plt.scatter(xs, ys)
        for i, label in enumerate(labels):
            plt.annotate(label, (xs[i], ys[i]), fontsize=8, alpha=0.7)
        plt.xlabel("avgReward")
        plt.ylabel("successRate")
        plt.title(f"{mob}: Tactics Reward vs Success Rate")
        plt.grid(True, alpha=0.3)
        out_file = os.path.join(out_dir, f"{mob}_reward_vs_success.png")
        plt.tight_layout()
        plt.savefig(out_file)
        plt.close()
        print(f"Wrote plot: {out_file}")


def main():
    args = parse_args()

    loaded_data = []

    if args.source == "github":
        if not (args.owner and args.repo):
            raise SystemExit("--owner and --repo are required for source=github")
        try:
            items = fetch_github_directory(args.owner, args.repo, "federated-data")
        except Exception as e:
            raise SystemExit(f"Failed to list GitHub directory: {e}")
        json_paths = [i["path"] for i in items if i.get("type") == "file" and i.get("name", "").endswith("-tactics.json")]
        for path in json_paths:
            try:
                loaded = fetch_github_file(args.owner, args.repo, path)
                loaded_data.append(loaded)
            except Exception as e:
                print(f"Failed to fetch {path}: {e}")
    else:
        if not args.path:
            raise SystemExit("--path is required for source=local")
        for file_path in load_local_files(args.path):
            try:
                with open(file_path, "r", encoding="utf-8") as f:
                    loaded_data.append(json.load(f))
            except Exception as e:
                print(f"Failed to read {file_path}: {e}")

    # Write CSV summary of top tactics per mob
    summary_rows = [summarize_mob(d) for d in loaded_data]
    write_csv(summary_rows, os.path.join(os.path.dirname(__file__), "analysis_summary.csv"))

    # Optionally generate plots
    if args.plots:
        generate_plots(loaded_data, os.path.join(os.path.dirname(__file__), "plots"))


if __name__ == "__main__":
    main()
