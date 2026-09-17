# Pinned-source prerelease acceptance

## Identity and scope

- Upstream: <https://github.com/lightvector/KataGo/commit/47aadc08518b3e121f22539796c911002f699584>.
- Actual binary version: `v1.18.2`; these are project builds, not official release binaries.
- All 15 archives are sealed by the reviewed `katago-assets.json`. Each archive contains its source, compiler, dependency inventory, executable digest and acceptance status in `source-release.json`.
- The B11 default model and existing CUDA, cuDNN, TensorRT and ROCm runtime versions are unchanged.
- This candidate does not promote the stable website or stable update channel.

## Native evidence

- Windows CPU, OpenCL, CUDA, TensorRT, DirectML, OpenVINO and all four ROCm families compiled and passed relocated PE/dependency audits. The four ROCm builds passed in [run 35191171000](https://github.com/wimi321/lizzieyzy-next/actions/runs/35191171000).
- Windows/Linux CPU and local Apple Silicon Metal executed ordinary analysis, one point, multiple points, removal and clearing against the exact source. Every transition produced positive root visits.
- Linux assets preserve the audited official runtime ABI ceilings. Ubuntu 22.04 and 24.04 loader/version checks passed; CPU inference is separately tested.
- DirectML and OpenVINO executed ONNX unit tests and CPU inference. This is not GPU/NPU inference evidence.
- Native Swing checks include same-tree focus and SGF root/edge persistence, quick-curve completion and pause preservation, five seconds per move, whole-game start/pause/resume/stop/completion, and HumanSL end/restore.
- The independent-analysis thread-alias conflict found by the real whole-game run was fixed in [PR #492](https://github.com/wimi321/lizzieyzy-next/pull/492). The same previously failing configuration then completed all five tested positions at 500 visits.
- A subsequent default-B11 repeat exposed two foreground handoff races, fixed in [PR #495](https://github.com/wimi321/lizzieyzy-next/pull/495): closing/reopening a terminal window must not discard its completion callback, and temporary idleness during snapshot restoration must not be mistaken for a user pause. Both cases have deterministic red/green regressions. The fixed native start/pause/resume/stop/reopen/complete sequence passed again without adding sleeps or changing analysis budgets. The final fix passed all 59 local gates, including 4,131 JUnit tests with zero failures/errors and 64 conditional skips.
- Missing CUDA, TensorRT, OpenCL, ROCm, Intel Metal and DirectML/OpenVINO GPU/NPU hardware remains `PENDING_HARDWARE`, not PASS. Hosted Windows Server 2022 checks do not certify Windows 10/11 or a user's GPU.

## Exact ROCm license inventory

The original successful workflow's upload step omitted one hidden license:
`licenses/dependencies/rocm/libhipcxx/.upstream-tests/LICENSE.TXT`.
The native build receipts included that file and its hash. Staging restored its
exact bytes from the independently hash-verified official AMD SDK, then verified
the complete original inventory again. No executable, runtime, receipt or test
result was changed. The uploader now explicitly includes hidden files to prevent
recurrence. Any other missing file or digest difference is a hard failure.

## Publication gate

The signed `.3` Apple Silicon package passed digest, Gatekeeper, notarization,
stapling, native launch/analysis/exit and post-launch signature checks. Its real
whole-game replay exposed a misleading startup-benchmark failure dialog when
foreground analysis reclaimed compute. [PR #500](https://github.com/wimi321/lizzieyzy-next/pull/500)
classifies this expected preemption without hiding genuine engine failures.
The corrected native replay completed five positions at 500 visits, restored
increasing foreground visits and verified the error dialog was absent. All 59
local gates passed (4,134 JUnit tests, zero failures/errors, 64 conditional skips).
The `.3` tag remains unchanged and unpublished.

The signed `.4` package passed signature and native-launch checks, but native
whole-game testing then exposed concurrent HTML comment mutation.
[PR #502](https://github.com/wimi321/lizzieyzy-next/pull/502) serializes both comment
display paths on the EDT. Two native whole-game repeats passed without an
uncaught exception. [PR #503](https://github.com/wimi321/lizzieyzy-next/pull/503)
removes the conflicting GTP thread alias from the transient HumanSL launch
profile; the original file remains unchanged. The actual B11/HumanSL process
completed its turn and restored the primary engine.

[PR #504](https://github.com/wimi321/lizzieyzy-next/pull/504) prevents delayed
startup tuning from stealing compute after a game is loaded or a foreground
task starts. Native five-second automatic analysis, five-position whole-game
analysis at 500 visits and HumanSL all passed against the signed package with
the reviewed class overlay. All 59 final local gates passed: 4,138 JUnit tests,
zero failures/errors, 64 conditional skips. An earlier stress run with concurrent
Maven load hit the ten-second HumanSL deadline; serialized acceptance passed.

All `.4` Windows application packages built, but their final audit still required
the official binary's `z.dll`. The pinned project CUDA build statically links
zlib and libzip and passed its actual PE dependency audit. The origin-aware check
retains the official DLL requirement and checks the complete reviewed source
inventory in both staging and the final application image. A shell fixture
rejects missing official DLLs and failed source audits. No old DLL is added.

The `.4` tag remains unchanged and unpublished. The final signed `.5` Apple
Silicon package passed signature, Gatekeeper, notarization, stapling, layout,
native jpackage-launcher startup/analysis/exit and post-launch signature checks.
Its unmodified JAR and engine passed the automatic and whole-game harnesses
under the external test JDK 21. The internal signed Java CLI is not a supported
test entry point; the real app launcher carries the required JIT entitlements
and was separately tested with its bundled JVM.

Final `.5` HumanSL testing failed even without concurrent benchmarking or Maven
load. Its first 64-visit search could overrun a ten-second move clock because
the client did not send an engine-side time limit. This supersedes the earlier
assumption that only concurrent test load caused the timeout. Publication was
stopped rather than repeatedly rerunning the failed package until it passed.
[PR #506](https://github.com/wimi321/lizzieyzy-next/pull/506) sets the supported
per-request `overrideSettings.maxTime`, reserves output/delivery time, and uses
actual root visits rather than treating requested visits as completed work.
Only searched/quality-checked candidates remain eligible. Two native Metal +
B11 + HumanSL repeats passed AI opening, human move, AI reply, end and primary
analysis restoration with the fix. Observed verification replies took 5.2-8.6
seconds; time-limited responses retained their actual 10-24 root visits rather
than claiming 64. This is functional smoke evidence, not a playing-strength
benchmark.

The `.5` tag remains unchanged and unpublished. Its complete Windows build,
all package audits, app-image smoke tests and MSI upgrade/configuration repair
passed in [run 35227799699](https://github.com/wimi321/lizzieyzy-next/actions/runs/35227799699).
This verifies the packaging repair, not the HumanSL application fix.

Further upstream source review found that root visits omit HumanSL weightless
playouts, so a root count below the request limit cannot prove time exhaustion.
The private `.6` pipeline was cancelled. [PR #508](https://github.com/wimi321/lizzieyzy-next/pull/508)
removes that inference, estimates affordable deeper work from elapsed time and
the prior request limit, and retains the previous response if a later bounded
search has fewer searched child visits. Child visits include weightless work.
Two regressions failed before the fix; the targeted suite passed 42 tests,
including accepting more child evidence despite fewer root visits. The move
clock, quality filters and candidate legality are unchanged.

The `.6` tag also remains unchanged and unpublished. The final signed `.7` Apple
Silicon package passed digest, Gatekeeper, notarization, stapling, 85-dylib audit,
layout, real bundled-JVM launch/analysis/exit and post-exit signature verification.
Its unmodified application JAR and engine passed five-second automatic analysis,
five-position whole-game analysis, HumanSL end/restore and native focus protocol
checks. Windows build, app-image smoke and MSI upgrade gates also passed, but
[run 35237475347](https://github.com/wimi321/lizzieyzy-next/actions/runs/35237475347)
failed uploading an installer with HTTP 500. No `.7` asset is replaced or published.

[PR #510](https://github.com/wimi321/lizzieyzy-next/pull/510) fixes a synchronization
race found during full regression: failure and response quiescence must be read
as one state, not separately. A deterministic delayed-listener test failed before
the fix and passed afterwards for both authority and mirror. All 59 local gates
passed with 4,147 JUnit tests, zero failures/errors and 64 conditional skips; exact
Windows/Linux CI, native process checks and desktop smoke also passed.

[PR #511](https://github.com/wimi321/lizzieyzy-next/pull/511) uploads one asset per
bounded retry, reconciles lost responses by SHA-256 and never overwrites completed
files. Unknown partial objects and mismatched digests fail closed. Its 60 local
gates and exact Windows/Linux CI passed. The new `next-2026-09-18.1` candidate must
rebuild all application packages and repeat final signed-package acceptance;
previous candidate tests or class overlays cannot substitute for that acceptance.

The final signed `next-2026-09-18.1` Apple Silicon package passed digest, signing,
Gatekeeper, notarization, stapling, native launch/analysis/exit and focus checks.
Its unmodified JAR and engine passed automatic analysis, whole-game analysis and
HumanSL. Linux and both macOS builds passed. Windows build, package/dependency
audits, application smoke and MSI upgrade tests also passed, but its upload
failed with repeated HTTP 500/timeouts. This candidate remains unpublished.

[PR #514](https://github.com/wimi321/lizzieyzy-next/pull/514) retains the tested
Windows packages for one day and separates upload into an Ubuntu job. A retry
rehashes the original build inventory and never replaces completed release
assets. It also joins test terminal workers before disposing their global
fixtures. All 62 local gates passed (4,147 JUnit tests, zero failures/errors,
64 conditional skips); the 89-test synchronization class passed ten consecutive
runs, and Windows/Linux CI plus native process and desktop smoke passed.
The `next-2026-09-18.2` candidate must rebuild all application packages and repeat
final signed-package checks before publishing. Stable assets remain unchanged.

The unpublished `.2` candidate exposed two application-packaging defects.
[PR #497](https://github.com/wimi321/lizzieyzy-next/pull/497) restores the exact
reviewed macOS engine files after jpackage's implicit ad-hoc signing, before
Developer ID signing. A real JDK 21 reproduction verified all 120 inventory
entries, 85 dylibs, engine version and the app's strict signature after repair.
[PR #498](https://github.com/wimi321/lizzieyzy-next/pull/498) makes Windows engine
staging idempotent and gives each installer a fresh input directory, preventing
duplicated engine/license paths. Both fixes passed all 59 local gates and 4,131
JUnit tests (zero failures/errors; 64 conditional skips). New final packages
are still required; the `.2` tag is not moved or republished with different bytes.

For project-built engines, a release-request push now builds and verifies the
complete candidate but leaves it as Draft. After inspecting final signed
packages, dispatch `publish-requested-pre-release.yml` with the existing request
and `publish_after_verification=true`. This reuses the immutable tag and successful
builds, rechecks exact-commit CI, all asset digests/provenance and canonical notes,
then publishes as pre-release, not Latest. The test-channel pointer is only
updated after publication, never after the Draft preparation stage.

Final application builds, macOS signing/notarization/stapling and public-download
verification are still required. This document is source and native-functional
evidence, not a claim that an unpublished installer has already passed.
