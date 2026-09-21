# Windows regression verification — 2026-09-22

Base: `6ec5df985be570c6b03f65b00b1b08944d9bb1e6` (includes PRs #519 and #522).
Host: Windows, JDK 21.0.12, Maven 3.9.10, NVIDIA RTX 5090.
Tests use isolated data directories; the user's downloaded installation is not modified.

## Fixed regressions

- A failed engine startup can leave the primary engine null. Board import, placement,
  pass, history navigation, komi and transformations must continue to work offline.
- Transform replay incorrectly skipped the first real move after the root sentinel
  was removed from exported move lists. Replay now starts from the actual sentinel.
- Pass replay must retain the recorded color, including leading White passes and
  color exchange. Offline clearing must preserve komi.
- The standalone acceptance-evidence validator used the review host's path syntax.
  It now accepts absolute POSIX or Windows evidence paths while rejecting relative
  and drive-relative paths. It does not resolve or trust another host's file bytes.

New coverage: six missing-engine rules tests, twelve transform cases, and a real
desktop offline-board acceptance probe. The latter is required by desktop CI.

## Completed local verification

- Full Maven verify: 4,176 unit cases, zero failures/errors, 66 explicitly skipped;
  integration phase: six cases, five skipped, shaded logging smoke passed.
- Native desktop: configuration navigation/persistence, engine startup and six
  process-failure scenarios, five repeated search-navigation probes, and Chinese/
  English physical-key search probes passed. The physical-key probes used the
  documented opt-in ASCII IME toggle on this host.
- Native offline-board acceptance and all eighteen new rules cases passed.
- TensorRT repair UI: two locales, fixture-based cancellation/completion scenarios
  passed. These are not proof of actual TensorRT GPU execution.
- Actual bundled CUDA engine and B11 model: GTP move-focus probe passed; native
  move-focus acceptance passed; both quick-analysis/pause-state scenarios passed.
- Windows release-script suite: 170 cases, zero failures, three skips.
- CI-plan/provenance regression suite after adding the desktop requirement:
  51 cases, zero failures, three skips.
- Final combined Windows desktop/rules rerun: 28 cases, zero failures or skips.
- Fix PR #524 hosted CI: all nine checks passed, including full Java on Windows
  and Linux, Linux desktop smoke, and native Windows/Linux engine-process gates.
- Verified Windows Eigen CPU archive and executable hashes, then ran the real
  B11 move-focus protocol probe: PASS, increasing root visits 11/21/31/41/52.
- Isolated portable launcher in a Chinese/spaced path: startup, model-ready display,
  placement, history navigation, analysis pause/resume and exit were exercised.

## Boundaries

This is source/staged-runtime regression evidence, not final release-asset acceptance.
Final packages must be tested separately against their exact provenance and hashes.
Windows native open-dialog display was observed, but automation could not safely
target its owned window; native chooser completion is not claimed. The existing
Linux JFileChooser acceptance test is not a Windows native FileDialog test.
Portable shell/symlink checks require the Linux CI environment. No native macOS,
AMD/Intel GPU, or actual TensorRT certification is claimed by this Windows run.

## Recently merged PR review

### Native acceptance-tool follow-up

While preparing the exact previous CPU portable product as an upgrade baseline,
`Start` returned before asynchronous KataGo startup. The subsequent read-only
`Status` correctly rejected the new, unrecorded PID, even though the application
was analyzing normally. The acceptance runner now waits for exactly one owned
process matching the packaged engine before freezing its strict process baseline.
This is process-start evidence, not inference certification. PID/incarnation,
component hashes and JVM ownership checks remain unchanged.

The same real previous product then passed Prepare/Start/Status/Stop. All 26
Windows acceptance-tool tests passed natively, without skips; the delayed-engine
fixture is included. The suite now supports native Windows as well as WSL and is
required by Windows script CI. This is a test-tool-only follow-up: final products
remain bound to their own exact release SHA and provenance, not the tool revision.

PR #519's deferred startup recovery and strict bundled-engine provenance are included
in the full suite and native engine/TensorRT-fixture checks above. PR #522's distribution
evidence introduced the cross-host absolute-path bug fixed here. Fixture/hosted CI
success remains distinct from real final-product acceptance, as those PRs document.
