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
