# Pinned KataGo source build acceptance

This tooling is a prerequisite for the move-focus pre-release, not permission to publish it.
The production package builders and stable/R2 channels are unchanged until all release gates pass.

## Windows CUDA evidence build

`windows-nvidia` builds the pinned source using the existing MSVC 14.44 toolset,
CUDA 12.8.0 components (NVCC/NVRTC 12.8.61) and cuDNN 9.8.0.87. The exact archives,
sizes and SHA-256 values are in
[katago_cuda_dependencies.json](../scripts/katago_cuda_dependencies.json).
These are the same runtime versions selected by the production NVIDIA packager;
this does not upgrade CUDA, cuDNN or the model. NVIDIA's compiler header explicitly
accepts MSVC 19.44; no unsupported-compiler override is used.

The common static SDK is extended in place only after input verification. Compiler,
headers and import libraries are constrained to that sealed prefix. Environment
overrides cannot select a different CUDA toolkit. The upstream CUDA architecture
selection remains unchanged, including the pre-RTX and RTX 50 targets.

The portable evidence artifact contains the 24 declared runtime DLLs, including
cuDNN graph/attention, NVRTC compiler/builtins and its alternate DLL, nvJitLink and the same MSVC DLLs as
the existing official package. It never copies the old engine or its unrelated
OpenSSL/zlib shared libraries. Every DLL is audited, and the relocated engine must
report its version with the developer SDK removed from `PATH`. NVIDIA's display
driver is a system prerequisite, not a bundled DLL. GPU inference, throughput and
Windows 10/11 final application acceptance remain **NOT_RUN** on the hosted runner.

```powershell
python scripts/build_katago_cuda_dependencies.py --output C:/build/cuda-sdk --jobs 3
python scripts/build_katago_source.py --source C:/src/KataGo --output C:/build/cuda-engine `
  --target windows-nvidia --windows-sdk C:/build/cuda-sdk/prefix --jobs 3
python scripts/package_katago_source_windows.py --build C:/build/cuda-engine `
  --sdk C:/build/cuda-sdk/prefix --output C:/build/cuda-evidence
```

## Windows OpenVINO evidence build

`windows-openvino` uses the same pinned KataGo source and MSVC/static protobuf
toolchain, with [its own dependency lock](../scripts/katago_openvino_dependencies.json).
It preserves the shipped OpenVINO 2026.2.1 runtime. Every OpenVINO and oneTBB DLL
must match both Intel's SHA-verified toolkit and the current official KataGo
1.18.1 bundle. ORT headers and notices use the exact upstream build revision
`7e76a52398ebf966bcbe4a10e552f438059edfce`, not floating latest headers.

The import library is generated only after verifying the actual ORT C exports.
The artifact contains all thirty-one required runtime DLLs, including GPU/NPU
plugins, along with ORT, OpenVINO and oneTBB notices. The old executable and
unrelated OpenSSL/shared protobuf DLLs are not copied. The Intel GPU plugin's
OpenCL loader comes from the pinned common SDK rather than an incidental driver
installation on the build host. Dependency closure and
relocated startup are audited just like the other Windows source targets.

CI runs upstream unit tests and real inference/focus protocol using the explicit
**ONNX CPU provider**. The packaged provider configuration remains `openvino`.
Intel GPU/NPU device selection, drivers and performance remain **NOT_RUN**;
CPU-provider CI evidence does not certify those devices. This is an artifact-only
build and does not change production downloads or publish a release.

## Windows DirectML evidence build

The source matrix also builds `windows-directml` at the same pinned KataGo commit.
It uses the common MSVC 14.44 SDK plus static protobuf 3.21.12. The additional
lock is [katago_directml_dependencies.json](../scripts/katago_directml_dependencies.json).
ONNX Runtime 1.24.4 and DirectML 1.15.4 come from Microsoft's NuGet packages;
their DLLs must be byte-identical to those in the already-shipped official
KataGo 1.18.1 DirectML asset. Only the existing MSVC redistributables are copied
from that SHA-verified asset, never its old `katago.exe` or unrelated DLLs.

The sealed SDK covers headers, import libraries, static libraries, notices and
every runtime file. Packaging requires all eleven runtime DLLs, audits x64 PE
imports (including delay imports), and runs the relocated engine with developer
SDK paths removed. The example provider configuration remains `directml`.

Windows CI runs upstream unit tests, tiny real ONNX inference, and the same-tree
focus protocol using the explicitly recorded **CPU execution provider**. This
is protocol/model execution evidence, **not DirectML GPU acceptance**. DirectML
adapter selection, actual GPU throughput and Windows 10 hardware checks remain
pending; this artifact-only workflow neither publishes a release nor upgrades
the production package catalog.

## Source identity

- Repository: <https://github.com/lightvector/KataGo>
- Merged commit: `47aadc08518b3e121f22539796c911002f699584`
- Upstream change: <https://github.com/lightvector/KataGo/pull/1252>
- This source reports **KataGo v1.18.2**. Preserve its real version output; a version string alone
cannot identify focus support.

Both macOS targets explicitly use deployment target `15.0`, matching the current release's
Mach-O load command. The builder checks the actual executable, rather than relying on the CMake
argument. Building on macOS 26 must not silently raise the release's minimum system version.
The bundle auditor checks every non-system dylib too. A library requiring macOS 15.1 or 26
is rejected even when the executable itself supports 15.0.

The builder rejects dirty or different source checkouts, implicit dependency auto-fetching,
cross-host claims and reused output directories. It records the real compiler, CMake options,
binary size/hash and source revision. A build receipt deliberately leaves packaging, dependency
closure and hardware acceptance as `NOT_RUN`. It does not certify packaged DLLs.

## Build

Prepare the exact clean upstream checkout and separately verified SDK/dependency prefixes first.
The builder does not install packages, update GPU drivers or modify the source checkout.

```sh
python3 scripts/build_katago_macos_dependencies.py \
  --output /path/to/new/sdk-build \
  --arch arm64

python3 scripts/build_katago_source.py \
  --source /path/to/clean/KataGo \
  --output /path/to/new/build-directory \
  --target macos-arm64 \
  --macos-sdk /path/to/new/sdk-build/prefix
```

Use Python 3.12 or later for dependency extraction. Every invocation needs a new output directory; a failed configure or
compile must never leave a previous executable looking like a successful new build.

`katago_macos_dependencies.json` pins the source archives and SHA-256 for protobuf, abseil,
libzip, xz and zstd. All are built for macOS 15.0 into an isolated prefix, without Homebrew
library discovery. Redistributable licenses and a verified file inventory accompany the SDK.
KataGo builds refuse a changed SDK, a different architecture, or an older lock receipt.
The system SDK supplies zlib and platform frameworks. Build tools may come from Homebrew;
its runtime libraries must not leak into the artifact.

The dependency lock keeps the protobuf/abseil/libzip versions found in the reference package.
It additionally pins previously floating optional compression dependencies. This does not
upgrade CUDA, cuDNN, TensorRT or the bundled model. Intel builds use `--arch x86_64` and
`--target macos-amd64` on a native Intel host.

Package a build with its pinned SDK and upstream/third-party licenses:

```sh
python3 scripts/package_katago_source_macos.py \
  --build /path/to/new/build-directory \
  --sdk /path/to/new/sdk-build/prefix \
  --output /path/to/new/portable-engine
```

This verifies the compiled executable's hash before copying, rewrites and verifies the entire
dylib closure, checks minimum macOS versions and records the final rewritten file hashes.
Linkers reserve Mach-O header space for portable dependency paths. Both architectures build in
`katago-source-macos.yml`; these are CI artifacts, not public releases. Hardware acceptance
remains `NOT_RUN` until separately tested. Developer ID signing, notarization and all 15 final
application packages remain separate release gates.

## Linux CPU and OpenCL evidence builds

`katago-source-linux.yml` builds three native x86_64 evidence targets on Ubuntu 22.04.
It pins and verifies Eigen 3.4.0, zlib 1.3.1, libzip 1.11.4 and the Khronos
2024.10.24 OpenCL headers/ICD loader. The loader is not a GPU driver; no vendor
driver or CUDA runtime is installed or changed. OpenCL GPU execution remains unverified.

```sh
python3 scripts/build_katago_linux_dependencies.py --output /path/to/new/linux-sdk
python3 scripts/build_katago_source.py \
  --source /path/to/clean/KataGo --output /path/to/new/linux-build \
  --target linux-cpu --linux-sdk /path/to/new/linux-sdk/prefix
python3 scripts/package_katago_source_linux.py \
  --build /path/to/new/linux-build --sdk /path/to/new/linux-sdk/prefix \
  --output /path/to/new/linux-package
```

Use `linux-opencl` for the second target. Build receipts must match the SDK inventory
and lock digest. Library discovery is restricted to that prefix; compiler flags and
loader environment from the caller cannot select unrelated host libraries. Both
targets avoid AVX2-only instructions. The OpenCL artifact includes its verified ICD
loader and resolves it relative to the executable, not to the build machine.

The ELF audit checks architecture, all direct dependencies, runtime paths and a
glibc ceiling of 2.35. **This build-host check is not production ABI approval.** The
existing official Linux binary is an AppImage; its extracted payload and supported
distribution behavior must be compared before replacing it. Therefore Linux receipts
carry `productionAbiAcceptanceStatus=NOT_RUN`, and the full-release gate rejects them
until that separate compatibility check passes. This change does not increase the
minimum requirements of any released package.

The CPU job must run ordinary/single/multiple/remove/clear focus with the pinned
upstream b6 test model. It preserves raw GTP evidence and fails on timeout, rejection
or tree reset. A green compile-only job is not CPU acceptance.

### Linux CUDA evidence

The `linux-nvidia` target separately locks CUDA 12.1.1 (NVCC 12.1.105) and cuDNN
9.8.0.87 in `scripts/katago_linux_cuda_dependencies.json`. NVIDIA's seven archives
are checked for both byte size and SHA-256 before installation into an isolated SDK.
The existing CUDA 12.1 architecture range is unchanged; no driver is installed.

```sh
python3 scripts/build_katago_linux_cuda_dependencies.py --output /path/to/new/cuda-sdk
python3 scripts/build_katago_source.py \
  --source /path/to/clean/KataGo --output /path/to/new/cuda-build \
  --target linux-nvidia --linux-sdk /path/to/new/cuda-sdk/prefix
python3 scripts/package_katago_source_linux_cuda.py \
  --build /path/to/new/cuda-build --sdk /path/to/new/cuda-sdk/prefix \
  --output /path/to/new/cuda-package
```

Like the existing official Linux CUDA AppImage, this evidence package requires external
CUDA/cuDNN libraries. It does not silently add vendor runtime gigabytes to user packages.
The receipt records their hashes, ELF dependencies and the real relocated engine's
`version` output using the locked external SDK. It rejects build-host RPATHs, missing
runtime components, and requirements above GLIBC 2.34 / GLIBCXX 3.4.30 / CXXABI 1.3.13,
the ceilings observed in the previous official CUDA payload. This is not GPU inference
or final distribution certification: both hardware and production ABI acceptance remain
`NOT_RUN`, and the final release gate still refuses incomplete acceptance.

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

`--timeout` bounds each version/GTP operation (default 120 seconds, allowed 1-600), with no
automatic retries. Evidence records the version-check elapsed time, including OS startup security
scanning. Downloaded ad-hoc-signed CI artifacts can incur a macOS first-launch scan; they are not
the final Developer ID signed/notarized application. Preserve any failed attempt and use a new
evidence directory for retests. A warm successful run does not replace final-package cold-start
acceptance.

## Required package matrix

The independent `Pinned KataGo Windows Source` workflow covers CPU and OpenCL evidence builds
on Windows Server 2022 using the MSVC 14.44 toolset. Every dependency archive is hash-locked in
`scripts/katago_windows_dependencies.json`; compiler/SDK versions and file identities are recorded.
It uses a static C runtime, disables AVX2, and rejects unknown PE imports. Only the verified
Khronos OpenCL loader is bundled for OpenCL, not a GPU vendor driver. Relocated binaries must
report the same source identity with the developer SDK removed from `PATH`. CPU additionally
executes the real same-tree focus probe. No unsigned evidence executable is published as a user
download. Windows 10/11 final application and GPU acceptance remain separate requirements.

```powershell
python scripts/build_katago_windows_dependencies.py --output C:/build/locked-sdk --jobs 3
python scripts/build_katago_source.py --source C:/src/KataGo --output C:/build/katago `
  --target windows-cpu --windows-sdk C:/build/locked-sdk/prefix --jobs 3
python scripts/package_katago_source_windows.py --build C:/build/katago `
  --sdk C:/build/locked-sdk/prefix --output C:/build/portable-evidence
```

Run these commands from the selected x64 developer environment; an unverified SDK, wrong compiler
toolset, stale output, missing DLL, or failed engine process is a hard failure, not a fallback to
an older KataGo executable. DirectML and OpenVINO use the separately locked SDK extensions
described above, as does Windows CUDA. TensorRT and ROCm builds are not yet implemented
by this workflow; Linux CUDA remains a separate pending target.

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

Linux CUDA packages also carry the hash-locked SDK's `libz.so.1`, required dynamically by
cuDNN. CUDA/cuDNN remain external; zlib must not be silently resolved from the build host.

- Lock and fetch build SDKs and dependency archives by version and digest without changing the
  existing CUDA/cuDNN, TensorRT, ROCm and ONNX execution-provider runtime choices.
- Build and audit every target in CI, then feed those exact verified artifacts into full packages.
- Publish trusted self-built engine catalogs for repair and on-demand installation; do not let a
  repair silently replace the new engine with an old official release.
- Complete #449 runtime probing, legacy single-engine `allow` fallback and GUI/SGF regression.
- Collect all final assets in Draft and audit them before any pre-release publication.

The current stable release, official download catalog and R2 assets must remain untouched.
