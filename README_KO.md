<p align="center">
  <img src="assets/hero-korean.svg" alt="LizzieYzy Next" width="100%" />
</p>

<p align="center">
  <a href="https://github.com/wimi321/lizzieyzy-next/releases"><img src="https://img.shields.io/github/v/release/wimi321/lizzieyzy-next?display_name=tag&label=Release&color=111111" alt="Release"></a>
  <a href="https://github.com/wimi321/lizzieyzy-next/stargazers"><img src="https://img.shields.io/github/stars/wimi321/lizzieyzy-next?style=flat&color=444444" alt="Stars"></a>
  <a href="https://github.com/wimi321/lizzieyzy-next/releases"><img src="https://img.shields.io/github/downloads/wimi321/lizzieyzy-next/total?label=Downloads&color=666666" alt="Downloads"></a>
  <a href="https://goagent.top/"><img src="https://img.shields.io/badge/Website-goagent.top-0b6b3a" alt="공식 웹사이트"></a>
  <img src="https://img.shields.io/badge/Platforms-Windows%20%7C%20macOS%20%7C%20Linux-888888" alt="Platforms">
</p>

<p align="center">
  <a href="README.md">简体中文</a> · <a href="README_ZH_TW.md">繁體中文</a> · <a href="README_EN.md">English</a> · <a href="README_JA.md">日本語</a> · 한국어 · <a href="README_TH.md">ภาษาไทย</a>
</p>

<p align="center">
  <strong>LizzieYzy Next 는 KataGo 로 대국을 복기하는 사용자를 위해 유지보수되는 lizzieyzy 브랜치입니다.</strong><br/>
  Fox 닉네임 기보 가져오기, 빠른 전판 분석, 새 승률 그래프와 하단 빠른 개요를 제공하며 Windows, macOS, Linux 버전을 배포합니다.
</p>

<p align="center">
  <a href="https://goagent.top/"><strong>공식 웹사이트</strong></a>
  ·
  <a href="https://goagent.top/download/"><strong>안정판 다운로드</strong></a>
  ·
  <a href="https://pan.baidu.com/s/1wthaL8YwGMxy_u0U7Mabpw?pwd=3i8w"><strong>Baidu 다운로드</strong></a>
  ·
  <a href="docs/INSTALL_KO.md"><strong>설치 가이드</strong></a>
  ·
  <a href="docs/TROUBLESHOOTING_EN.md"><strong>문제 해결</strong></a>
</p>

> [!NOTE]
> 중국 본토 사용자는 안정판을 [공식 다운로드 페이지](https://goagent.top/download/)에서 받는 것을 권장합니다. 설치형, Linux 패키지, 이전 버전은 [GitHub Releases](https://github.com/wimi321/lizzieyzy-next/releases)에서 받을 수 있습니다.
>
> 중국 본토 사용자라면 공개 Baidu Netdisk 다운로드도 바로 사용할 수 있습니다:
> [https://pan.baidu.com/s/1wthaL8YwGMxy_u0U7Mabpw?pwd=3i8w](https://pan.baidu.com/s/1wthaL8YwGMxy_u0U7Mabpw?pwd=3i8w)
> 추출 코드: `3i8w`

> [!TIP]
> [중국어 QQ 그룹: 299419120](https://qm.qq.com/q/JZoeojjteg)
>
> 일상적인 사용 질문, 버그 제보, 기능 요청을 가장 빠르게 주고받는 곳입니다.

## 실행하자마자 할 수 있는 것

| 하고 싶은 일 | 지금 이 프로젝트에서 어떻게 해결되는가 |
| --- | --- |
| 최근 공개 Fox 기보 가져오기 | Fox 닉네임을 입력하면 앱이 맞는 계정을 자동으로 찾습니다 |
| 전판 흐름을 빨리 보기 | 한 수씩 수동으로 넘기지 않아도 빠른 전판 분석을 사용할 수 있습니다 |
| 문제수를 빨리 찾기 | 새 메인 승률 그래프와 하단 열지도 개요로 큰 손해 구간을 더 쉽게 찾습니다 |
| 설정을 덜 건드리기 | 추천 패키지에 KataGo, 기본 가중치, 첫 실행 자동 설정이 들어 있습니다 |
| 설치 없이 쓰기 | Windows 에서는 `portable.zip` 을 우선 고를 수 있습니다 |
| 바둑판 동기화도 쓰기 | Windows 주요 배포판에 네이티브 `readboard.exe` 가 포함됩니다 |
| 빠른 곡선 작업이 주 엔진을 차지하지 않게 하기 | `KataGo 자동 설정 -> 가중치 관리` 에서 38 MB 공식 경량 모델을 필요할 때 다운로드할 수 있습니다. 기보 곡선의 빈 구간을 채울 때만 실행하고 주 분석 전에 GPU 를 해제합니다 |
| 이 PC보다 더 많은 연산 성능 사용하기 | `설정 -> 원격 컴퓨팅` 에서 Zhizi 클라우드 컴퓨팅에 로그인하고 원격 KataGo 엔진을 만들 수 있습니다 |

원격 컴퓨팅의 기본값은 “VIP 월정액”(`--gpu-type vip-share`)입니다. 비 VIP 사용자는 고급 설정에서 종량제 1x / 3x / 6x 단계를 선택할 수 있습니다. 기본 프리셋은 Zhizi 28B 모델을 사용합니다. TensorRT 와 CUDA 는 클라우드 엔진 백엔드 이름이며 요금제 이름이 아닙니다.

저장한 로그인 정보는 Windows DPAPI, macOS Keychain 또는 Linux Secret Service 로 보호되며 일반 설정에 기록되지 않습니다. 보안 저장소를 사용할 수 없으면 인증 정보는 앱을 종료할 때까지만 유지됩니다. 연결이 끊기면 자동으로 다시 연결하며 언제든 로컬 엔진으로 돌아갈 수 있습니다.

## 먼저 무엇을 다운로드할까

중국 본토 사용자는 일반 안정판을 [공식 다운로드 페이지](https://goagent.top/download/)에서 선택하는 것을 권장합니다. 설치형, Linux 패키지, 이전 버전은 [GitHub Releases](https://github.com/wimi321/lizzieyzy-next/releases)에서 받을 수 있습니다.

<p align="center">
  <img src="assets/package-guide.svg" alt="LizzieYzy Next package guide" width="100%" />
</p>

| 내 환경 | Releases 에서 이 키워드를 포함하는 파일 찾기 |
| --- | --- |
| Windows, RTX 20/30/40/50 NVIDIA GPU, 추천, 무설치 | `*windows64.nvidia.portable.zip` |
| Windows, RTX 20/30/40/50 NVIDIA GPU, 설치형 | `*windows64.nvidia.installer.exe` |
| Windows, AMD / Intel / 구형 NVIDIA GPU, 무설치 | `*windows64.opencl.portable.zip` |
| Windows, AMD / Intel / 구형 NVIDIA GPU, 설치형 | `*windows64.opencl.installer.exe` |
| Windows, 적합한 GPU 가 없거나 GPU 버전을 시작할 수 없음, CPU 대안 | `*windows64.with-katago.portable.zip` |
| Windows, CPU 대안, 설치형 | `*windows64.with-katago.installer.exe` |
| Windows, RTX 30 시리즈 이하, TensorRT 선택 설치 | NVIDIA 패키지로 시작한 뒤 `KataGo 자동 설정` 에서 TensorRT 설치 |
| Windows, DirectX 12 GPU, DirectML 테스트 | `*windows64.experimental.directml.portable.zip` |
| Windows, Intel GPU/NPU, OpenVINO 테스트 | `*windows64.experimental.openvino.portable.zip` |
| Windows, 지원되는 AMD GPU, ROCm 테스트 | 해당 `*windows64.experimental.rocm.*.portable.zip` 선택 |
| Windows, 내 엔진 사용, 무설치 | `*windows64.without.engine.portable.zip` |
| Windows, 내 엔진 사용, 설치형 | `*windows64.without.engine.installer.exe` |
| macOS Apple Silicon | `*mac-apple-silicon.with-katago.dmg` |
| macOS Intel | `*mac-intel.with-katago.dmg` |
| Linux | `*linux64.with-katago.zip` |

CPU, OpenCL, CUDA, TensorRT, Metal 백엔드용 전체 패키지와 Linux 패키지는 KataGo `v1.18.1` 을 사용합니다. Linux NVIDIA 버전은 실행 환경 호환성을 위해 CUDA 12.1 을 유지합니다.

권장 전체 패키지에는 공식 플래그십 B11 모델 `b11c768h12nbt3tflrs-fson-silu.bin.gz`(약 202 MiB)이 포함됩니다. RTX 3070 비교에서는 탐색 처리량이 B10 보다 약 40% 낮았으며, 속도를 우선하면 `KataGo 자동 설정 -> 가중치` 에서 B10 으로 바꿀 수 있습니다.

NVIDIA 및 TensorRT:

- `KataGo 자동 설정` 은 NVIDIA GPU 와 Compute Capability 를 감지해 TensorRT 사용 여부를 안내합니다. 감지에 실패해도 수동으로 계속할 수 있습니다.
- RTX 40/50 은 기본적으로 CUDA 를 사용합니다. TensorRT 는 RTX 30 시리즈 이하에서 선택할 수 있습니다.
- NVIDIA 드라이버 `570.65` 이상은 바로 로드합니다. `528.33–570.64` 는 첫 실행 때 가벼운 추론 테스트를 한 번 수행하고, 그보다 오래된 드라이버는 복구 상태를 표시합니다.
- GTX 10 시리즈 이전 카드는 OpenCL 을 사용하세요. OpenCL 이 불안정하면 `*windows64.with-katago.portable.zip` 으로 전환합니다.

## 3단계로 시작

1. [안정판 다운로드](https://goagent.top/download/)에서 환경에 맞는 패키지를 받고, 설치형, Linux 또는 이전 버전은 GitHub Releases를 사용합니다.
2. `Fox Kifu` 를 열고 Fox 닉네임을 입력합니다.
3. 기보를 가져온 뒤 빠른 전판 분석을 돌리고, 그래프와 개요에서 중요한 수로 바로 이동합니다.

<p align="center">
  <a href="assets/fox-id-demo.gif">
    <img src="assets/fox-id-demo-cover.png" alt="LizzieYzy Next Fox nickname demo" width="100%" />
  </a>
</p>

<p align="center">
  GitHub 에서 GIF 재생이 느리면 위 이미지를 눌러 전체 애니메이션을 열 수 있습니다.
</p>

## 화면 미리보기

<p align="center">
  <img src="assets/interface-overview-2026-04.png" alt="LizzieYzy Next 화면 미리보기" width="100%" />
</p>

메인 승률 그래프와 하단 빠른 개요에는 다음 정보가 표시됩니다.

<p align="center">
  <img src="assets/winrate-quick-overview-2026-04.png" alt="LizzieYzy Next winrate graph and quick overview" width="46%" />
</p>

- 파란선 / 자홍선: 양쪽 승률 흐름
- 초록선: 집 차이 변화
- 아래 열지도 막대: 전판에서 큰 문제수가 몰린 구간
- 세로 가이드선: 현재 수나 마우스를 올린 수의 위치

## 원래 lizzieyzy 와의 차이

| 비교 항목 | 원래 `lizzieyzy` | `LizzieYzy Next` |
| --- | --- | --- |
| 현재 상태 | 많은 사용자가 기억하는 원 프로젝트지만 실사용 유지보수는 약함 | 사용성과 배포 경험을 계속 다듬는 현재 유지보수 브랜치 |
| Fox 기보 가져오기 | 예전 흐름은 깨진 환경이 많음 | 자주 쓰는 가져오기 흐름을 복구했고 닉네임 입력 지원 |
| 입력 방식 | 숫자 계정 번호를 먼저 알아야 하는 경우가 많음 | Fox 닉네임을 넣으면 앱이 계정을 자동으로 찾음 |
| KataGo 사용 장벽 | 직접 환경이나 누락 리소스를 채워야 하는 경우가 많음 | 추천 패키지에 KataGo 와 기본 가중치가 이미 포함됨 |
| Windows 다운로드 경험 | 사용자가 직접 판단해야 할 부분이 더 많음 | `portable.zip` 우선 추천으로 더 명확함 |
| 동기화 도구 | 사용자가 직접 조합해야 하는 경우가 많음 | Windows 주요 배포판에 네이티브 `readboard.exe` 포함 |

## macOS 첫 실행

Mac 에 맞는 패키지를 선택하고 DMG 를 연 다음 `LizzieYzy Next` 를 응용 프로그램으로 드래그합니다. 설치 디스크를 꺼낸 뒤 Finder 의 응용 프로그램 폴더에서 실행하세요. 공식 release 는 서명과 공증을 거칩니다. macOS 가 계속 차단하면 [설치 가이드](docs/INSTALL_KO.md) 를 확인하세요.

## 문서와 참여

- [지원 가이드](SUPPORT.md)
- [설치 가이드](docs/INSTALL_KO.md)
- [패키지 안내 (English)](docs/PACKAGES_EN.md)
- [문제 해결 (English)](docs/TROUBLESHOOTING_EN.md)
- [검증된 플랫폼 (English)](docs/TESTED_PLATFORMS.md)
- [GitHub Releases](https://github.com/wimi321/lizzieyzy-next/releases)
- [GitHub Discussions](https://github.com/wimi321/lizzieyzy-next/discussions)
- [중국어 QQ 그룹: 299419120](https://qm.qq.com/q/JZoeojjteg)
- [Roadmap](ROADMAP.md)
- [Contributing](CONTRIBUTING.md)
- [Changelog](CHANGELOG.md)

## Credits

- Original project: [yzyray/lizzieyzy](https://github.com/yzyray/lizzieyzy)
- KataGo: [lightvector/KataGo](https://github.com/lightvector/KataGo)
- 바둑판 동기화 도구: [qiyi71w/readboard](https://github.com/qiyi71w/readboard)

readboard 를 계속 유지보수하고 개선하는 [qiyi71w](https://github.com/qiyi71w) 님께 감사드립니다.

모든 기여자에게 감사드립니다:

<p align="left">
  <a href="https://github.com/wimi321/lizzieyzy-next/graphs/contributors">
    <img src="https://contrib.rocks/image?repo=wimi321/lizzieyzy-next" alt="LizzieYzy Next 기여자" />
  </a>
</p>

Fox 기보 가져오기 참고 자료:

- [yzyray/FoxRequest](https://github.com/yzyray/FoxRequest)
- [FuckUbuntu/Lizzieyzy-Helper](https://github.com/FuckUbuntu/Lizzieyzy-Helper)

## 번역 참여

README 번역 Pull Request 를 환영합니다. Translations are welcome; please submit a Pull Request.
