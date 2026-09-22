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

The first newly required hosted fixture run exposed two additional tooling defects:
empty-PID cleanup masked the original startup exception, and the fixture's ANSI
`LoadLibrary` call could not load a DLL under Chinese paths on English Windows.
Cleanup now preserves the original failure, and the fixture uses `LoadLibraryW`.
The positive process-identity case also includes a supplementary Unicode character;
the provenance Python subprocess explicitly uses UTF-8 so redirected diagnostics
cannot fail under a legacy Windows code page. An immediately exiting launcher has
a separate cleanup regression case. These changes do not modify application binaries.

PR #519's deferred startup recovery and strict bundled-engine provenance are included
in the full suite and native engine/TensorRT-fixture checks above. PR #522's distribution
evidence introduced the cross-host absolute-path bug fixed here. Fixture/hosted CI
success remains distinct from real final-product acceptance, as those PRs document.

## Final source integration for next-2026-09-22.2

Application integration includes PR #530 (merge `a0135a8a6fa57606141326fb2f92b230fddaea6a`)
and PR #531 (reviewed head `028b8b0f`, merged with the release request at `9cc51e3b`).
The earlier `.1` candidate was never published and is superseded; its binaries and
acceptance results must not be substituted for the `.2` final product.

- Reviewed #527 (bounded weight-header identity), #528 (persistent ownership of
  renamed bundled profiles), #529 (ordinary batch ownership/start/continuation),
  and #530 (both-participant runtime komi confirmation, pause/cancel/failure).
- Reviewed #531 (six rule choices, shared rule-family labels, unchanged legacy
  preset/custom JSON preservation). All 22 focused Windows JVM cases passed
  with zero skips; this is not manual native custom-editor acceptance.
- Full Maven verify repeated on the integrated release tree `9cc51e3b`: 4,225
  unit cases, zero failures/errors, 66 explicit skips; shaded logging integration
  passed (six other integration cases conditionally skipped in the default run).
- Resolved changelog conflicts without dropping either side. Fixed a Windows
  test race in #529 by waiting for the actual continuation timer, not two assumed
  EDT turns; all pre/post-synchronization assertions remain required.
- Reproduced an offline komi-button NullPointerException in the native desktop
  probe. Guarded ordinary komi edits and game-info apply with no engine, and fixed
  the same missing-engine dereference in score-graph rendering. Required desktop
  regression now exercises +/- buttons, typed komi, game-info apply and both
  KataGo/non-KataGo graph paths.
- Full Maven verify at `750530649d31787e9c5dacfa75f8540cfbaccc76`: 4,221 unit
  cases, zero failures/errors, 66 explicit skips. Integration default: seven
  cases, six conditional skips, shaded logging smoke passed.
- Native Windows focused engine-game/offline tests: 206 passed, zero skips.
- Final native desktop/rules rerun: 28 passed, zero failures/errors/skips,
  including Chinese/English physical-key search, settings persistence, engine
  startup, offline editing and transforms.
- Real pinned CUDA+B11 integration: four passed, zero skips (move focus plus
  three quick-analysis cases including ordinary batch continuation/cancel/retry).
  These execute the final source against a verified engine, not `.2` app binaries.
- PR #530 all ten hosted checks passed: CI run `35669122132` and native-focus
  run `35669122138`. PRs #524, #526 and #529 were also merged only after their
  full required checks passed.
- Windows-compatible release metadata tests: 178 cases, zero errors/failures,
  four explicit platform skips. Linux shell inventory checks are exercised by
  hosted Linux CI, not represented as Windows passes.

The final `.2` packages require fresh provenance/hash, launch/process cleanup,
real CPU/CUDA protocol and core-update preservation acceptance before publication.
Manual native chooser completion and final-package manual UI automation remain
unverified: computer-use approval timed out, and no alternative UI automation
was used to bypass it. Automated application desktop tests above are separate
development test evidence. Hardware boundaries in the earlier section still apply.
