# Performance plan: integration acceptance, 2026-09-23

This change set preserves the model, search budget, numerical precision, search
policy and external protocols. It does not merge a pull request, publish a
release, change the daily installation, or automatically apply measured settings.

## Delivery and dependencies

| PR | Scope | Base / dependency |
| --- | --- | --- |
| [#540](https://github.com/wimi321/lizzieyzy-next/pull/540) | Reproducible real-engine/application measurements | Main `2e1e08c9` |
| [#541](https://github.com/wimi321/lizzieyzy-next/pull/541) | Immutable background model catalog | Existing regression fix #537 |
| [#542](https://github.com/wimi321/lizzieyzy-next/pull/542) | Coalesced notifications and bounded per-client state delivery | Main `2e1e08c9` |
| [#543](https://github.com/wimi321/lizzieyzy-next/pull/543) | Owned save dialogs, consistent snapshots and atomic background writes | Existing regression fix #537 |
| [#546](https://github.com/wimi321/lizzieyzy-next/pull/546) | Reviewed scene-specific tuning and read-only evidence export | Main `2e1e08c9`; measurements produced by #540 |

Each PR can be reviewed and reverted independently. Catalog and tuning both
touch the setup dialog: their shared task gate must retain both the catalog
refresh worker and measured-review worker. The integration branch resolves and
tests this overlap without adding a sixth feature PR. Catalog rendering continues
to use cached identities, including HumanSL and quick-analysis labels.

Main and #537 were rechecked on September 23: main was still `2e1e08c9`, and
#537 remained open at `2d4787e6`. Model-upgrade PR #534 is not included in the
performance comparison; all formal measurements use the pinned B11 model.

## Verified behavior

- Catalog: one identity read per candidate during background scanning, immutable
  rendering snapshots, stale-generation rejection, damaged/missing models,
  same-name/same-size/same-timestamp replacement, and a fresh worker preflight
  before persisting a model selection. Scan failure permits retry without
  enabling unverified model actions.
- Web updates: burst coalescing, full-state priority, board/epoch guards, the
  existing 10 Hz analysis limit, close behavior, and real slow-TCP-client tests.
  Per-client state slots are bounded; control events retain their existing
  ordered delivery and are not silently dropped or described as bounded state.
- Saving: ordinary, raw, commented-raw and current-branch saves share one owner
  and coordinator. Tests cover stable topology/metadata/payload snapshots,
  edits during writing, engine replacement, cancel, extension normalization,
  overwrite refusal, failed writes and success-only current-file updates.
  An unsupported atomic replacement fails safely instead of overwriting an
  existing file non-atomically. Serialization still occurs from the detached
  snapshot on the event thread; disk writes do not.
- Tuning: separate live/whole-game overlays, complete-budget 3/5-round gates,
  latency/memory limits, content and command fingerprints, recursive includes,
  stale/closed-dialog rejection, target-engine binding, persistence failure,
  legacy configuration compatibility and restoration. Remote/reused-engine
  whole-game modes cannot use an independently measured local-process report.

## Completed validation

| Check | Measured/tested revision | Result |
| --- | --- | --- |
| Windows integration Maven `verify` | `067b146a` (all production changes) | 4,323 unit tests; 0 failures/errors, 66 platform/opt-in skips. Failsafe: 7 cases, 0 failures/errors, 6 opt-in skips |
| Windows native desktop and real CUDA | `f032c3b0` | 51 tests, 0 failures/errors/skips: persistence, 45 setup-layout cases, native move focus, offline saving, and 3 quick-analysis engine cases |
| Tuning focused regression after shared busy-gate changes | PR #546 `437e3b6c` | 202 tests, 0 failures/errors; 10 Linux-only skips on Windows |
| Catalog final fixture / completion-hook regressions | PR #541 `b7da149c` | 71 tests, 0 failures/errors; 3 expected skips |
| Save focused regressions | PR #543 | 176 tests, 0 failures/errors; standard-chooser field selector adds 2 passing tests |
| Web queue / slow-client regressions | PR #542 `55ac71af` | 81 tests, 0 failures/errors |
| Measurement v2 preparation / runner | PR #540 `b7ba99e5` | 8 Java and 7 Python tests pass; 6 real-CUDA functional smoke scenarios meet their 512-visit budgets |
| Evidence exporter / CI registration | PR #546 | 28 exporter tests pass; 25 CI-runner tests, 2 Windows/no-Bash skips |
| Localized GPU/RAM confirmation qualification | PR #546 `b29d2765` | 4 localization tests pass, including consistent memory-warning paragraphs in all 8 bundles |
| Formal Windows application/bare-engine matrix | Probe `b7ba99e5` | 48 successful measurements: 12 cold and 36 warm; all positions reach the fixed budget |
| Linux real CPU and native SGF input | Integration `5c1c685d` | [Workflow 35816005146](https://github.com/wimi321/lizzieyzy-next/actions/runs/35816005146) passes, including real Robot text entry, save/reopen and CPU engine analysis |
| Linux save PR in isolation | PR #543 `9298e061` | [Workflow 35815837137](https://github.com/wimi321/lizzieyzy-next/actions/runs/35815837137) passes |

The unit-suite skips are not reported as executed desktop or real-engine tests;
those require the separate opt-in runs above. Windows native validation preceded
the final shared busy-gate patch; focused regressions cover that patch. Later
measurement preparation and Linux test-selector/assertion changes do not change
production behavior. Post-measurement integration revalidation is recorded with
its exact revision and counts in the PR description; it supplements these
earlier checks rather than changing their provenance.

The 45 setup-layout cases exercise layout helpers, not a complete native window
containing the new measured-import/restore controls. Their busy/close/identity
behavior has focused Swing regression coverage, but native full-window layout,
real report-filename entry, and the new confirmation dialog still require
interactive acceptance. The current session lacks the Computer Use JavaScript
runtime needed to perform those actions. This limitation must not be presented
as an executed or passing test; PR #546 remains draft pending that acceptance.

Two acceptance-test defects were repaired rather than bypassed: Linux SGF input
had referenced a removed static filename widget, and a Linux engine-startup test
had required a redundant thread-count GTP command after the same override was
already correctly applied at launch. The chooser test now locates the unique
visible editable filename field and still uses actual typing/clicks. The engine
test checks the actual startup argument and absence of a duplicate override.

## Windows actual-input file dialogs

On September 22, isolated integration build `3876f367` was also exercised with
actual keyboard text and clicks. A black stone at D16 was saved through all four
menu/shortcut paths. Chinese filenames with uppercase `.SGF` were accepted
without a duplicate extension, output files contained `;B[dd]`, and focus
returned to the main window with the board unchanged. Ordinary output was 95
bytes; each raw/branch fixture was 62 bytes. This manual fixture did not contain
comment text; preservation of nonempty comments is covered by semantic tests.
The later snapshot-lock change did not alter these dialogs. No production
behavior was changed to accommodate desktop automation.

## Performance evidence and decision

See [the portable measurement record](performance/2026-09-23/README.md) for
fixed-budget, cold/warm, application/bare-engine results and input hashes.
Only measurement-contract v2 evidence is eligible for report export. Earlier
`app-final-opening` results had asynchronous restore timing errors and are
explicitly excluded; their raw diagnostic files remain local.

The compared profiles are experimental controls, not the application's shipped
default. A numeric pass for these fixtures does not authorize a global default
change. Benchmark caps, output/PV settings and other non-tuning semantics remain
part of the binding: mismatched daily commands fail review rather than being
rewritten to force a match. No tuning has been applied to the daily configuration.
Broader whole-game workloads and other GPUs remain future measurement work.
The midgame candidate also has materially higher end-of-run Java used-heap
snapshots. Their GC timing is uncontrolled and they do not establish peak/RSS
or causation. This unresolved RAM signal is an additional reason to withhold an
unconditional tuning recommendation despite passing the numeric GPU/latency
gates. See the measurement record for every observed value.
