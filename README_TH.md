<p align="center">
  <img src="assets/hero-english.svg" alt="LizzieYzy Next" width="100%" />
</p>

<p align="center">
  <a href="https://github.com/wimi321/lizzieyzy-next/releases"><img src="https://img.shields.io/github/v/release/wimi321/lizzieyzy-next?display_name=tag&label=Release&color=111111" alt="Release"></a>
  <a href="https://github.com/wimi321/lizzieyzy-next/stargazers"><img src="https://img.shields.io/github/stars/wimi321/lizzieyzy-next?style=flat&color=444444" alt="Stars"></a>
  <a href="https://github.com/wimi321/lizzieyzy-next/releases"><img src="https://img.shields.io/github/downloads/wimi321/lizzieyzy-next/total?label=Downloads&color=666666" alt="Downloads"></a>
  <a href="https://goagent.top/"><img src="https://img.shields.io/badge/Website-goagent.top-0b6b3a" alt="เว็บไซต์ทางการ"></a>
  <img src="https://img.shields.io/badge/Platforms-Windows%20%7C%20macOS%20%7C%20Linux-888888" alt="Platforms">
</p>

<p align="center">
  <a href="README.md">简体中文</a> · <a href="README_ZH_TW.md">繁體中文</a> · <a href="README_EN.md">English</a> · <a href="README_JA.md">日本語</a> · <a href="README_KO.md">한국어</a> · ภาษาไทย
</p>

<p align="center">
  <strong>LizzieYzy Next คือสาขา lizzieyzy ที่ยังได้รับการดูแล สำหรับผู้เล่นที่ใช้ KataGo ทบทวนเกมโกะ</strong><br/>
  รองรับการดึงเกมด้วยชื่อเล่น Fox การวิเคราะห์ทั้งกระดานอย่างรวดเร็ว กราฟอัตราชนะใหม่ และภาพรวมด้านล่าง พร้อมเวอร์ชันสำหรับ Windows, macOS และ Linux
</p>

<p align="center">
  <a href="https://goagent.top/"><strong>เว็บไซต์ทางการ</strong></a>
  ·
  <a href="https://goagent.top/download/"><strong>ดาวน์โหลดเวอร์ชันเสถียร</strong></a>
  ·
  <a href="docs/INSTALL.md"><strong>คู่มือติดตั้ง</strong></a>
  ·
  <a href="docs/TROUBLESHOOTING.md"><strong>คำถามที่พบบ่อย</strong></a>
</p>

> [!NOTE]
> แนะนำให้ผู้ใช้ในจีนแผ่นดินใหญ่ดาวน์โหลดเวอร์ชันเสถียรจาก [หน้าดาวน์โหลดอย่างเป็นทางการ](https://goagent.top/download/) ส่วน installer, แพ็กเกจ Linux และเวอร์ชันเก่าสามารถดาวน์โหลดได้จาก [GitHub Releases](https://github.com/wimi321/lizzieyzy-next/releases)

> [!TIP]
> [กลุ่ม QQ ภาษาจีน: 299419120](https://qm.qq.com/q/JZoeojjteg)
>
> ใช้สำหรับถามวิธีใช้งาน รายงานบั๊ก และเสนอฟีเจอร์

## เปิดแล้วทำอะไรได้ทันที

| คุณต้องการทำอะไร | โปรเจกต์นี้จัดการอย่างไร |
| --- | --- |
| ดึงเกม Fox สาธารณะล่าสุด | ใส่ชื่อเล่น Fox โดยตรง โปรแกรมจะจับคู่บัญชีและดึงเกมให้ |
| ดูแนวโน้มทั้งกระดานอย่างรวดเร็ว | การวิเคราะห์ทั้งกระดานรวดเร็ว ไม่ต้องคลิกทีละหมาก |
| ค้นหาจังหวะสำคัญอย่างรวดเร็ว | กราฟอัตราชนะใหม่และภาพรวม heatmap ด้านล่าง เห็นปัญหาใหญ่ได้ทันที |
| การตั้งค่าน้อย | แพ็กเกจแนะนำมาพร้อม KataGo, น้ำหนักเริ่มต้น และ auto setup ครั้งแรก |
| ไม่อยากติดตั้ง | Windows แนะนำแพ็กเกจ `portable.zip` ก่อน |
| ซิงก์กระดาน | แพ็กเกจหลักสำหรับ Windows มี `readboard.exe` แบบเนทีฟมาให้ |

## เลือกดาวน์โหลดตัวไหน

แนะนำให้ผู้ใช้ในจีนแผ่นดินใหญ่เลือกเวอร์ชันเสถียรที่ใช้บ่อยจาก [หน้าดาวน์โหลดอย่างเป็นทางการ](https://goagent.top/download/) ส่วน installer, แพ็กเกจ Linux และเวอร์ชันเก่าสามารถดาวน์โหลดได้จาก [GitHub Releases](https://github.com/wimi321/lizzieyzy-next/releases)

| สถานการณ์ของคุณ | คีย์เวิร์ดไฟล์ที่ควรหา |
| --- | --- |
| ผู้ใช้ Windows ส่วนใหญ่ (แนะนำ, portable) | `*windows64.opencl.portable.zip` |
| Windows, OpenCL, ต้องการตัวติดตั้ง | `*windows64.opencl.installer.exe` |
| Windows, OpenCL ไม่เสถียร, ใช้ CPU, portable | `*windows64.with-katago.portable.zip` |
| Windows, การ์ดจอ NVIDIA, ต้องการความเร็ว, portable | `*windows64.nvidia.portable.zip` |
| Windows, ตั้งค่าเอนจินเอง, portable | `*windows64.without.engine.portable.zip` |
| macOS Apple Silicon | `*mac-apple-silicon.with-katago.dmg` |
| macOS Intel | `*mac-intel.with-katago.dmg` |
| Linux | `*linux64.with-katago.zip` |

## เริ่มต้นใน 3 ขั้นตอน

1. ไปที่ [ดาวน์โหลดเวอร์ชันเสถียร](https://goagent.top/download/) และเลือกแพ็กเกจที่เหมาะกับระบบของคุณ ส่วน installer, Linux และเวอร์ชันเก่าให้ใช้ GitHub Releases
2. เปิดโปรแกรมแล้วคลิก `Fox` เพื่อใส่ชื่อเล่น Fox
3. หลังจากดึงเกมแล้ว ให้รันการวิเคราะห์ทั้งกระดานรวดเร็ว ใช้กราฟอัตราชนะและภาพรวมด้านล่างเพื่อหาจังหวะสำคัญ

## เอกสารและการมีส่วนร่วม

- [ขอความช่วยเหลือ](SUPPORT.md)
- [คู่มือติดตั้ง](docs/INSTALL.md)
- [รายละเอียดแพ็กเกจ](docs/PACKAGES.md)
- [คำถามที่พบบ่อยและการแก้ปัญหา](docs/TROUBLESHOOTING.md)
- [แพลตฟอร์มที่ทดสอบแล้ว](docs/TESTED_PLATFORMS.md)
- [GitHub Releases](https://github.com/wimi321/lizzieyzy-next/releases)
- [GitHub Discussions](https://github.com/wimi321/lizzieyzy-next/discussions)
- [กลุ่ม QQ ภาษาจีน: 299419120](https://qm.qq.com/q/JZoeojjteg)
- [แผนงานโครงการ](ROADMAP.md)
- [ร่วมพัฒนา](CONTRIBUTING.md)
- [บันทึกการเปลี่ยนแปลง](CHANGELOG.md)

## เครดิต

- โปรเจกต์ดั้งเดิม: [yzyray/lizzieyzy](https://github.com/yzyray/lizzieyzy)
- KataGo: [lightvector/KataGo](https://github.com/lightvector/KataGo)
- เครื่องมือซิงก์กระดาน: [qiyi71w/readboard](https://github.com/qiyi71w/readboard)

ขอบคุณ [qiyi71w](https://github.com/qiyi71w) ที่ดูแลและปรับปรุง readboard อย่างต่อเนื่อง

ขอบคุณผู้ร่วมพัฒนาทุกคน:

<p align="left">
  <a href="https://github.com/wimi321/lizzieyzy-next/graphs/contributors">
    <img src="https://contrib.rocks/image?repo=wimi321/lizzieyzy-next" alt="ผู้ร่วมพัฒนา LizzieYzy Next" />
  </a>
</p>

ข้อมูลอ้างอิงการดึงเกม Fox:

- [yzyray/FoxRequest](https://github.com/yzyray/FoxRequest)
- [FuckUbuntu/Lizzieyzy-Helper](https://github.com/FuckUbuntu/Lizzieyzy-Helper)

## การแปล

ยินดีรับ Pull Request สำหรับการแปล README นี้ Translations are welcome; please submit a Pull Request.
