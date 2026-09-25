# Windows Integration Acceptance: 2026-09-25

## Candidate and Scope

- Repository: `wimi321/lizzieyzy-next` (Java Swing application).
- Tested code: `5aad75550e3e4a4877dd560905d704c1353fec86`, integration PR #547.
- Reviewed contributions: #534, #537, #538, #539, #540, #541, #542, #543, #545, #546.
- Windows 11 build 22631; NVIDIA GeForce RTX 3070 Laptop GPU, 8 GiB; driver 560.76.
- Build JDK 21.0.2, Maven 3.9.6, Python 3.12.14.
- Isolated copy of `next-2026-09-22.2` NVIDIA portable. Real launcher:
  `LizzieYzy Next NVIDIA.exe`. Replaced only the test copy's JAR and default model.
- Loaded JVM module verified inside the portable's `runtime/bin/server/jvm.dll`.
- KataGo v1.18.2, CUDA; executable SHA-256:
  `9a87f2e40233bb5694332546f9cad0a6248f4593341ccafb29225fbf025a6ef6`.
- New B11: `kata1-tf3-b11c768-s11750M-d6216M.bin.gz`, 211575408 bytes,
  SHA-256 `5266903ce3156f208562d6d3495869f2becb4c36fc9bf587faee77e4c642378b`.
- Candidate shaded JAR SHA-256:
  `448396b89cb7a2ef205f23eb512827fe92d8db4bc2cea9cfc2a190fae828176c`.
- Existing dirty checkout, installed applications and real user-data were not changed.

## Fixes Found During Acceptance

1. Explicit Python overrides were ignored by a Windows acceptance helper. Use the
   selected executable and fail closed for invalid overrides. Correct Windows path,
   permission and Git Bash expectations in portable script tests (`c58919c4`).
2. The performance action's first native layout used width 148 although its final
   text needed width 204. Resolve text and dimensions before layout, revalidate
   later changes, and apply the existing button sizing to import/restore (`b6eeeef6`).
3. Measured-report confirmation text grew after JOptionPane packed on Windows at
   150% JVM scale, placing OK/Cancel outside the dialog. Bound and scroll the text
   viewport, and test physical import/cancel/apply/restore (`5aad7555`).
4. Extend the existing SGF desktop test to use the actual Windows AWT open dialog,
   physical keyboard input and Swing save dialog. Keep the test frame in front of
   Explorer windows opened by diagnostics; production save semantics are unchanged.

## Automated Evidence

| Gate | Actual result |
| --- | --- |
| Local CI `-Profile All` | PASS, 65/65 steps, 605.703 s |
| Maven unit tests | 4384 tests, 0 failures, 0 errors, 92 conditional skips |
| Maven integration tests | 7 tests, 0 failures, 0 errors, 6 conditional skips |
| Combined JUnit summary | 4391 tests, 397 suites, 0 failures, 0 errors, 98 skips |
| Python checks in All log | 615 tests in 44 executions |
| Required Windows desktop gate | 10 tests, 0 failures, 0 errors, 0 skips |
| Required engine-process gate | 7 tests, 0 failures, 0 errors, 0 skips |
| Real CUDA quick-analysis acceptance | 3 tests, 0 failures, 0 errors, 0 skips |
| Producer snapshot sampling | 10 tests, 0 failures, 0 skips |
| Java producer-trace bridge and game lifecycle | 134 tests, 0 failures, 0 errors, 0 skips |
| Diagnostics layout | 5 tests, 0 failures, 0 errors, 0 skips |
| Native engine startup diagnostics | 2 tests, 0 failures, 0 errors, 0 skips |
| Final affected desktop retest | 7 tests, 0 failures, 0 errors, 0 skips |

Full verification included packaging and LoggingProviderSmokeIT. Conditional skips
are not counted as executions. Additional native and real-engine runs above are
reported separately, not added to the full-suite total.

## Real Windows Workflows

- The isolated EXE starts using its bundled runtime, loads the new B11, and produces
  actual CUDA analysis visits. Repeat startup and normal exit leave no test engine.
- Ordinary SGF opening displayed the populated overview and returned foreground
  analysis. Clicking suggested moves placed stones and continued analysis.
- The real CUDA test imports a four-move SGF with a one-visit quick-analysis budget:
  foreground visits resumed about 0.68 s after import in the recorded run. This is
  a lifecycle result, not a general performance promise or a 50-move benchmark.
- Explicit pause remains authoritative after quick-analysis cleanup. Single and
  multiple-file ordinary analysis, load failure/retry, and flash batch complete.
- Alt+O opens the bundled readboard v3.0.6 visible window. Closing it leaves the main
  application responsive. This does not certify live Fox recognition on all clients.
- Actual Windows SGF open/save, overwrite cancellation, keyboard branch navigation,
  controlled-engine analysis and exit/reopen preserve the expected tree.
- Performance actions tested with Chinese, Traditional Chinese, English, Japanese,
  Korean and Thai at JVM scales 1/1.5/2. Glyph coverage and label fit are asserted;
  smaller work areas may use vertical scrolling. OS display scaling was not changed.
- Measured-settings tests use explicit `SYNTHETIC-UI-ONLY-not-a-benchmark.json`
  fixtures. They verify review, cancellation, confirmation, persistence and restore;
  they do not establish measured speed improvements on this computer.

## Reproducing the Trace Bridge

Producer source is fixed to `qiyi71w/readboard` commit
`4f2c4ccffb26111f13850968a26adb568cb204e0`. Run its original tests with the
official, SHA-512-verified portable .NET SDK 10.0.401:

```powershell
$env:READBOARD_SNAPSHOT_TRACE_DIR = '<isolated-output>'
dotnet test tests/Readboard.VerificationTests/Readboard.VerificationTests.csproj --filter FullyQualifiedName~StableSnapshotSamplingTests
mvn -B -Dfmt.skip=true -Djava.awt.headless=true -Dtest=EngineGameModuleContractTest,ReadBoardSyncDecisionTest -Dreadboard.snapshotTraceDir=<isolated-output> test
```

The exported four trace files are produced by the real coordinator tests, not
handwritten replacements. All seven previously conditional Java bridge cases ran.

## Evidence Locations and Limits

Under the isolated QA checkout:

- `target/local-ci-final/local-ci-summary.json`
- `target/local-ci-desktop-final/local-ci-summary.json`
- `target/local-ci-engine-final/local-ci-summary.json`
- `target/real-cuda-quick-final/`, `target/producer-bridge-final/`
- `target/readboard-producer-results/stable-snapshot.trx`
- `target/desktop-smoke/probes/` for native screenshots, results and phase timelines
- `target/windows-manual-evidence/` for visible EXE and readboard screenshots

No claim is made for a new native macOS, RTX 50, AMD/Intel GPU, TensorRT, public
weak-network/mobile client load test, paid cloud session, or screen-reader auditory
test on this machine. Hosted CI and controlled protocol peers are identified as
such, not substituted for those hardware or service-specific acceptances. Publication
still requires the separate multi-platform packaging, signing and provenance gates.
