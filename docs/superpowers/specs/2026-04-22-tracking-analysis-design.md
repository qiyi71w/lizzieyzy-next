# Tracking Analysis Design

## Problem

Users want to monitor the analysis of a specific board point that KataGo doesn't include in its default top candidates — while keeping the main analysis running normally. The existing `allow`/`avoid` mechanism replaces the entire candidate set, which is not the desired behavior.

## Solution

Add a "Track Analysis" feature: a lightweight auxiliary KataGo process (JSON analysis mode) that analyzes user-specified points independently, while the main GTP engine continues unrestricted analysis. Results from both engines merge in the UI.

## Architecture

```
Main Engine (GTP, Leelaz.java)          Tracking Engine (JSON, TrackingEngine.java)
  kata-analyze (unrestricted)              { allowMoves: ["E3","F5"], ... }
         |                                           |
         v                                           v
  BoardData.bestMoves                    BoardData.bestMovesTracked
         |                                           |
         +-------------------------------------------+
                          |
                  BoardRenderer merges
                  (tracked points get special marker)
```

Two independent data channels. The main engine is never modified.

## Components

### TrackingEngine.java (new)

A new class in `featurecat.lizzie.analysis` that manages an auxiliary KataGo process in JSON analysis mode (`katago analysis`).

**Responsibilities:**
- Launch/manage a KataGo analysis-mode process (reusing the main engine's binary and model)
- Maintain the set of tracked coordinates (`trackedCoords`)
- Send JSON analysis requests with `allowMoves` restriction on position changes
- Parse streaming JSON responses and update `BoardData.bestMovesTracked`
- Trigger UI refresh

**JSON request format:**
```json
{
  "id": "track-1",
  "moves": [["B","Q16"],["W","D4"]],
  "initialStones": [],
  "rules": "chinese",
  "komi": 7.5,
  "boardXSize": 19,
  "boardYSize": 19,
  "maxVisits": 500,
  "reportDuringSearchEvery": 0.1,
  "allowMoves": [
    {"player": "B", "moves": ["E3","F5"]},
    {"player": "W", "moves": ["E3","F5"]}
  ]
}
```

Key parameters:
- `reportDuringSearchEvery: 0.1` — stream intermediate results every 100ms
- `allowMoves` — KataGo JSON protocol's whitelist (different from GTP `allow`)
- `maxVisits` — configurable, default 500, controls GPU usage
- Same `id` on a new request overrides the previous one (no explicit `stop` needed)

**Command line construction:** Extract KataGo executable path and model path from the main engine command, then build: `<katago-exe> analysis -model <model-path> -config <analysis-cfg> -quit-without-waiting`

### Lifecycle

| Event | Action |
|---|---|
| User adds first tracked point (preload=false) | Launch TrackingEngine, show status bar "Tracking engine starting..." |
| App startup (preload=true) | Launch TrackingEngine alongside main engine |
| All tracked points cleared | TrackingEngine stays alive (idle), no requests sent |
| User adds tracked point again | Immediately send request (no startup delay) |
| Main engine switched | Destroy and recreate TrackingEngine (new binary/model) |
| Engine switched to non-KataGo | Destroy TrackingEngine, clear all tracked points |
| App exit | Destroy TrackingEngine process |

### Data Model Changes

**BoardData.java** — add:
```java
public List<MoveData> bestMovesTracked;  // tracking engine results
```

**LizzieFrame.java** or new tracking state holder — add:
```java
public Set<String> trackedCoords;       // e.g. {"E3", "F5"}
public boolean isKeepTracking = false;  // persist tracked coords across moves
```

### Merge & Rendering (BoardRenderer.java)

1. Render `bestMoves` (main engine candidates) normally
2. For each entry in `bestMovesTracked`:
   - If the coordinate already appears in `bestMoves` → skip (main engine data is more accurate)
   - Otherwise → render with a special visual marker (dashed circle outline or distinct border color)
3. Info panel shows merged results sorted by winrate

**Tracked point visual marker:** Same circle as normal candidates, but with a dashed outer ring or a distinct border color (e.g., light purple) to distinguish user-added points from engine-chosen candidates.

**No PV display** on tracked points — only winrate, visits, and score values.

### Position Sync

When the user navigates the game tree (forward, backward, branch switch):
1. Main engine `ponder()` fires as usual
2. If `trackedCoords` is non-empty, TrackingEngine sends a new JSON request with the full move history of the new position + `allowMoves` for tracked points
3. Old request is overridden by the new one (same `id`)

### "Keep Tracking" (Persistent Mode)

- `isKeepTracking = false` (default): navigating to the next move automatically clears `trackedCoords`, restoring pure main-engine analysis
- `isKeepTracking = true`: `trackedCoords` persists across moves; TrackingEngine re-analyzes tracked points at each new position

### Right-Click Menu (RightClickMenu.java)

New menu items, visible only when the main engine is KataGo and not in game mode:

| Menu Item | Visible When | Behavior |
|---|---|---|
| "Track this point" / "追踪分析此点" | Engine is KataGo, point not in trackedCoords | Add point to trackedCoords, trigger TrackingEngine |
| "Untrack this point" / "取消追踪此点" | Point is in trackedCoords | Remove point from trackedCoords |
| "Clear all tracked" / "清除所有追踪点" | trackedCoords non-empty | Clear all tracked points |
| "Keep tracking" / "持续追踪" (checkbox) | trackedCoords non-empty | Toggle isKeepTracking |

These are **separate from** existing allow/avoid menu items. The existing "只分析此点", "增加分析此点", "不分析此点" etc. remain unchanged and operate on the main engine only.

### Configuration (Config.java / config.json)

New options under the `ui` section:

| Key | Type | Default | Description |
|---|---|---|---|
| `tracking-engine-preload` | boolean | false | Launch tracking engine at app startup (recommended for TRT/slow-loading engines) |
| `tracking-engine-max-visits` | int | 500 | Max visits per tracking analysis request |

### i18n (DisplayStrings*.properties)

New keys for all supported locales (zh_CN, zh_TW, zh_HK, en_US, ja_JP, ko):
- `RightClickMenu.trackPoint`
- `RightClickMenu.untrackPoint`
- `RightClickMenu.clearAllTracked`
- `RightClickMenu.keepTracking`
- `LizzieFrame.trackingEngineStarting`

## Boundary Conditions

1. **Non-KataGo engine**: tracking menu items hidden, TrackingEngine not started
2. **Tracked point already in main candidates**: tracking engine still analyzes it, but renderer uses main engine data (more visits = more accurate)
3. **Game mode (playing against AI)**: tracking menu items hidden
4. **TrackingEngine startup failure**: show error toast, main engine unaffected
5. **Simultaneous allow/avoid + tracking**: both work independently — allow/avoid restricts the main engine, tracking uses the auxiliary engine
6. **Engine switch to non-KataGo**: auto-clear tracked points, destroy TrackingEngine

## What This Design Does NOT Do

- No support for non-KataGo engines (Leela Zero, Sai)
- No per-point computation priority adjustment
- No PV (principal variation) display on tracked points
- No separate configuration for tracking engine command (reuses main engine binary/model)
