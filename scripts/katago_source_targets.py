"""Dependency-free source identity shared by builds, probes and the release publisher."""

SOURCE_COMMIT = "47aadc08518b3e121f22539796c911002f699584"

TARGETS = {
    "windows-cpu": ("Windows", "x86_64", "EIGEN", False),
    "windows-opencl": ("Windows", "x86_64", "OPENCL", False),
    "windows-nvidia": ("Windows", "x86_64", "CUDA", False),
    "windows-tensorrt": ("Windows", "x86_64", "TENSORRT", False),
    "windows-directml": ("Windows", "x86_64", "ONNX", True),
    "windows-openvino": ("Windows", "x86_64", "ONNX", True),
    "windows-rocm-gfx103x": ("Windows", "x86_64", "ROCM", True),
    "windows-rocm-gfx110x": ("Windows", "x86_64", "ROCM", True),
    "windows-rocm-gfx1151": ("Windows", "x86_64", "ROCM", True),
    "windows-rocm-gfx120x": ("Windows", "x86_64", "ROCM", True),
    "linux-cpu": ("Linux", "x86_64", "EIGEN", False),
    "linux-opencl": ("Linux", "x86_64", "OPENCL", False),
    "linux-nvidia": ("Linux", "x86_64", "CUDA", False),
    "macos-arm64": ("Darwin", "arm64", "METAL", False),
    "macos-amd64": ("Darwin", "x86_64", "METAL", False),
}
