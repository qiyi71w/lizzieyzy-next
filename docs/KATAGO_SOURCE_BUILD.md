# Pinned KataGo source build acceptance

This tooling is a prerequisite for the move-focus pre-release, not permission to publish it.
The production package builders and stable/R2 channels are unchanged until all release gates pass.

## Source identity

- Repository: <https://github.com/lightvector/KataGo>
- Merged commit: `47aadc08518b3e121f22539796c911002f699584`
- Upstream change: <https://github.com/lightvector/KataGo/pull/1252>
- This source reports **KataGo v1.18.2**. Preserve its real version output; a version string alone
  cannot identify focus support.

The builder rejects dirty or different source checkouts, implicit dependency auto-fetching,
cross-host claims and reused output directories. It records the real compiler, CMake options,
binary size/hash and source revision. A build receipt deliberately leaves packaging, dependency
closure and hardware acceptance as `NOT_RUN`. It does not certify SDK versions or packaged DLLs.

## Build

Prepare the exact clean upstream checkout and separately verified SDK/dependency prefixes first.
The builder does not install packages, update GPU drivers or modify the source checkout.

```sh
python3 scripts/build_katago_source.py \
  --source /path/to/clean/KataGo \
  --output /path/to/new/build-directory \
  --target macos-arm64 \
  --sdk CMAKE_PREFIX_PATH=/path/to/verified/dependencies
```

Use Python 3.11 or later. Every invocation needs a new output directory; a failed configure or
compile must never leave a previous executable looking like a successful new build.

## Real protocol acceptance

```sh
python3 scripts/probe_katago_focus.py \
  --engine /path/to/self-built/katago \
  --model /path/to/model.bin.gz \
  --evidence /path/to/new/evidence-directory
```

The probe uses one real process, numbered acknowledgements and the engine's **root** visit count.
It checks ordinary analysis, one focused point, two points, removal, clearing and clean shutdown.
Focused points use equal weights and probability `0.5`. Candidate visit totals are not substituted
for root visits. Evidence directories cannot be overwritten. Rejection, exit, timeout and a reset
search tree fail the probe; none are reclassified as unsupported hardware.

## Required package matrix

| Platform | Backends |
| --- | --- |
| Windows x64 | Eigen, OpenCL, CUDA, TensorRT, DirectML, OpenVINO |
| Windows x64 experimental ROCm | gfx103x, gfx110x, gfx1151, gfx120x |
| Linux x64 | Eigen, OpenCL, CUDA |
| macOS | Apple Silicon Metal, Intel Metal |

All **15** targets require compilation, packaging and dependency-closure audit. Hardware acceptance
may be `PENDING_HARDWARE` only when corresponding hardware is unavailable, never after an actual
test failure, and must include an explicit reason. Eigen CPU targets must execute, not claim a
missing GPU. Receipts must identify the correct backend and executable size/digest. A receipt
completeness check does not replace checking the final package bytes,
signed macOS bundles or public download hashes.

## Integration gates still required

- Lock and fetch build SDKs and dependency archives by version and digest without changing the
  existing CUDA/cuDNN, TensorRT, ROCm and ONNX execution-provider runtime choices.
- Build and audit every target in CI, then feed those exact verified artifacts into full packages.
- Publish trusted self-built engine catalogs for repair and on-demand installation; do not let a
  repair silently replace the new engine with an old official release.
- Complete #449 runtime probing, legacy single-engine `allow` fallback and GUI/SGF regression.
- Collect all final assets in Draft and audit them before any pre-release publication.

The current stable release, official download catalog and R2 assets must remain untouched.
