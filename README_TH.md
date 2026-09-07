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
  <a href="https://pan.baidu.com/s/1wthaL8YwGMxy_u0U7Mabpw?pwd=3i8w"><strong>ดาวน์โหลดจาก Baidu</strong></a>
  ·
  <a href="docs/INSTALL.md"><strong>คู่มือติดตั้ง</strong></a>
  ·
  <a href="docs/TROUBLESHOOTING.md"><strong>คำถามที่พบบ่อย</strong></a>
</p>

> [!NOTE]
> แนะนำให้ผู้ใช้ในจีนแผ่นดินใหญ่ดาวน์โหลดเวอร์ชันเสถียรจาก [หน้าดาวน์โหลดอย่างเป็นทางการ](https://goagent.top/download/) ส่วน installer, แพ็กเกจ Linux และเวอร์ชันเก่าสามารถดาวน์โหลดได้จาก [GitHub Releases](https://github.com/wimi321/lizzieyzy-next/releases)
>
> ผู้ใช้ในจีนแผ่นดินใหญ่สามารถดาวน์โหลดจาก Baidu Netdisk สาธารณะได้เช่นกัน:
> [https://pan.baidu.com/s/1wthaL8YwGMxy_u0U7Mabpw?pwd=3i8w](https://pan.baidu.com/s/1wthaL8YwGMxy_u0U7Mabpw?pwd=3i8w)
> รหัสแตกไฟล์: `3i8w`

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
| ลดผลกระทบของกราฟด่วนต่อเอนจินหลัก | ดาวน์โหลดโมเดลขนาดเล็กอย่างเป็นทางการ 38 MB ได้ตามต้องการจาก `KataGo การตั้งค่าอัตโนมัติ -> จัดการโมเดล` โมเดลจะทำงานเฉพาะตอนเติมช่วงที่ขาดในกราฟเกมและคืน GPU ก่อนการวิเคราะห์หลัก |
| การตั้งค่าน้อย | แพ็กเกจแนะนำมาพร้อม KataGo, น้ำหนักเริ่มต้น และ auto setup ครั้งแรก |
| ไม่อยากติดตั้ง | Windows แนะนำแพ็กเกจ `portable.zip` ก่อน |
| ซิงก์กระดาน | แพ็กเกจหลักสำหรับ Windows มี `readboard.exe` แบบเนทีฟมาให้ |
| ต้องการพลังประมวลผลมากกว่าเครื่องนี้ | เปิด `การตั้งค่า -> คอมพิวเตอร์ระยะไกล` เข้าสู่ระบบ Zhizi Cloud Compute แล้วสร้างเอนจิน KataGo ระยะไกล |

คอมพิวเตอร์ระยะไกลใช้ “VIP รายเดือน” (`--gpu-type vip-share`) เป็นค่าเริ่มต้น ผู้ใช้ที่ไม่ใช่ VIP เลือกแบบคิดตามการใช้งาน 1x / 3x / 6x ได้ในการตั้งค่าขั้นสูง พรีเซ็ตเริ่มต้นใช้โมเดล Zhizi 28B ส่วน TensorRT และ CUDA คือแบ็กเอนด์ของเอนจินบนคลาวด์ ไม่ใช่ชื่อแพ็กเกจค่าบริการ

ข้อมูลเข้าสู่ระบบที่บันทึกไว้ได้รับการป้องกันด้วย Windows DPAPI, macOS Keychain หรือ Linux Secret Service และไม่เขียนลงการตั้งค่าทั่วไป หากใช้ที่เก็บข้อมูลปลอดภัยไม่ได้ ข้อมูลจะอยู่จนกว่าโปรแกรมจะปิดเท่านั้น ระบบจะเชื่อมต่อใหม่หลังการตัดการเชื่อมต่อ และสลับกลับไปใช้เอนจินในเครื่องได้ทุกเมื่อ

หากมีเซิร์ฟเวอร์ Linux x86_64 ที่ใช้ NVIDIA GPU แต่ยังไม่มีลิงก์ `WSS` ให้ใช้ [KataGo Remote One-Click](https://github.com/wimi321/katago-remote-one-click) เพียงเรียกคำสั่งเดียวบนเซิร์ฟเวอร์เพื่อสร้างลิงก์เข้ารหัสและคิวอาร์โค้ด จากนั้นวางหรือนำเข้าใน `คอมพิวเตอร์ระยะไกล -> คอมพิวเตอร์ที่ตั้งค่าเอง` โดยไม่ต้องเปิดพอร์ตรับสาธารณะ

## เลือกดาวน์โหลดตัวไหน

แนะนำให้ผู้ใช้ในจีนแผ่นดินใหญ่เลือกเวอร์ชันเสถียรที่ใช้บ่อยจาก [หน้าดาวน์โหลดอย่างเป็นทางการ](https://goagent.top/download/) ส่วน installer, แพ็กเกจ Linux และเวอร์ชันเก่าสามารถดาวน์โหลดได้จาก [GitHub Releases](https://github.com/wimi321/lizzieyzy-next/releases)

<p align="center">
  <img src="assets/package-guide.svg" alt="คู่มือเลือกแพ็กเกจ LizzieYzy Next" width="100%" />
</p>

| สถานการณ์ของคุณ | คีย์เวิร์ดไฟล์ที่ควรหา |
| --- | --- |
| Windows, การ์ดจอ NVIDIA RTX 20/30/40/50, แนะนำ, portable | `*windows64.nvidia.portable.zip` |
| Windows, การ์ดจอ NVIDIA RTX 20/30/40/50, ต้องการตัวติดตั้ง | `*windows64.nvidia.installer.exe` |
| Windows, การ์ดจอ AMD / Intel / NVIDIA รุ่นเก่า, portable | `*windows64.opencl.portable.zip` |
| Windows, การ์ดจอ AMD / Intel / NVIDIA รุ่นเก่า, ต้องการตัวติดตั้ง | `*windows64.opencl.installer.exe` |
| Windows, ไม่มี GPU ที่เหมาะสมหรือเวอร์ชัน GPU เริ่มไม่ได้, ใช้ CPU | `*windows64.with-katago.portable.zip` |
| Windows, ใช้ CPU, ต้องการตัวติดตั้ง | `*windows64.with-katago.installer.exe` |
| Windows, RTX 30 หรือต่ำกว่า, เลือกติดตั้ง TensorRT | เริ่มจากแพ็กเกจ NVIDIA แล้วติดตั้ง TensorRT ใน `KataGo การตั้งค่าอัตโนมัติ` |
| Windows, GPU ที่รองรับ DirectX 12, ทดสอบ DirectML | `*windows64.experimental.directml.portable.zip` |
| Windows, Intel GPU/NPU, ทดสอบ OpenVINO | `*windows64.experimental.openvino.portable.zip` |
| Windows, AMD GPU ที่รองรับ, ทดสอบ ROCm | เลือก `*windows64.experimental.rocm.*.portable.zip` ที่ตรงกัน |
| Windows, ตั้งค่าเอนจินเอง, portable | `*windows64.without.engine.portable.zip` |
| Windows, ตั้งค่าเอนจินเอง, ต้องการตัวติดตั้ง | `*windows64.without.engine.installer.exe` |
| macOS Apple Silicon | `*mac-apple-silicon.with-katago.dmg` |
| macOS Intel | `*mac-intel.with-katago.dmg` |
| Linux | `*linux64.with-katago.zip` |

แพ็กเกจฉบับเต็มสำหรับแบ็กเอนด์ CPU, OpenCL, CUDA, TensorRT และ Metal รวมถึงแพ็กเกจ Linux ใช้ KataGo `v1.18.1` ส่วน Linux NVIDIA ยังคงใช้ CUDA 12.1 เพื่อความเข้ากันได้ของสภาพแวดล้อม

แพ็กเกจฉบับเต็มที่แนะนำมีโมเดลเรือธง B11 อย่างเป็นทางการ `b11c768h12nbt3tflrs-fson-silu.bin.gz` (ประมาณ 202 MiB) การเปรียบเทียบบน RTX 3070 วัด throughput การค้นหาต่ำกว่า B10 ประมาณ 40% หากต้องการความเร็วสามารถเปลี่ยนเป็น B10 ใน `KataGo การตั้งค่าอัตโนมัติ -> จัดการโมเดล`

หมายเหตุ NVIDIA และ TensorRT:

- `KataGo การตั้งค่าอัตโนมัติ` ตรวจหา NVIDIA GPU และ Compute Capability แล้วแนะนำว่า TensorRT เหมาะหรือไม่ หากตรวจหาไม่สำเร็จยังติดตั้งด้วยตนเองได้
- RTX 40/50 ใช้ CUDA เป็นค่าเริ่มต้น ส่วน TensorRT เป็นตัวเลือกสำหรับ RTX 30 หรือต่ำกว่า
- ไดรเวอร์ NVIDIA `570.65` ขึ้นไปโหลดได้โดยตรง รุ่น `528.33–570.64` จะทดสอบ inference ขนาดเล็กหนึ่งครั้งเมื่อเปิดครั้งแรก ส่วนรุ่นเก่ากว่าจะแสดงสถานะการแก้ไข
- การ์ด GTX 10 หรือต่ำกว่าควรใช้ OpenCL หาก OpenCL ไม่เสถียรให้เปลี่ยนเป็น `*windows64.with-katago.portable.zip`

## เริ่มต้นใน 3 ขั้นตอน

1. ไปที่ [ดาวน์โหลดเวอร์ชันเสถียร](https://goagent.top/download/) และเลือกแพ็กเกจที่เหมาะกับระบบของคุณ ส่วน installer, Linux และเวอร์ชันเก่าให้ใช้ GitHub Releases
2. เปิด `Fox Kifu` แล้วใส่ชื่อเล่น Fox
3. หลังจากดึงเกมแล้ว ให้รันการวิเคราะห์ทั้งกระดานรวดเร็ว ใช้กราฟอัตราชนะและภาพรวมด้านล่างเพื่อหาจังหวะสำคัญ

<p align="center">
  <a href="assets/fox-id-demo.gif">
    <img src="assets/fox-id-demo-cover.png" alt="ตัวอย่างการดึงเกมด้วยชื่อเล่น Fox ใน LizzieYzy Next" width="100%" />
  </a>
</p>

<p align="center">
  หาก GIF บน GitHub เล่นช้า ให้คลิกรูปด้านบนเพื่อเปิดภาพเคลื่อนไหวทั้งหมด
</p>

## ตัวอย่างหน้าจอ

<p align="center">
  <img src="assets/interface-overview-2026-04.png" alt="หน้าจอ LizzieYzy Next" width="100%" />
</p>

กราฟหลักและภาพรวมด่วนแสดงข้อมูลต่อไปนี้:

<p align="center">
  <img src="assets/winrate-quick-overview-2026-04.png" alt="กราฟอัตราชนะและภาพรวมด่วนของ LizzieYzy Next" width="46%" />
</p>

- เส้นสีน้ำเงิน / สีม่วง: แนวโน้มอัตราชนะของทั้งสองฝ่าย
- เส้นสีเขียว: การเปลี่ยนแปลงของคะแนนนำ
- แถบ heatmap ด้านล่าง: ตำแหน่งที่มีความผิดพลาดใหญ่ตลอดทั้งเกม
- เส้นแนวตั้ง: ตำแหน่งหมากปัจจุบันหรือหมากที่ชี้อยู่

## แตกต่างจาก lizzieyzy เดิมอย่างไร

| หัวข้อเปรียบเทียบ | `lizzieyzy` เดิม | `LizzieYzy Next` |
| --- | --- | --- |
| สถานะปัจจุบัน | โปรเจกต์เดิมที่ผู้ใช้จำนวนมากยังจำได้ แต่ไม่มีการดูแลต่อเนื่องในทางปฏิบัติ | สาขาที่ดูแลอยู่ในปัจจุบัน เน้นการใช้งานและการเผยแพร่ |
| การดึงเกม Fox | ขั้นตอนเดิมใช้งานไม่ได้ในหลายกรณี | กู้คืนขั้นตอนที่ใช้บ่อยและรองรับการใส่ชื่อเล่น |
| วิธีกรอกข้อมูล | มักต้องรู้หมายเลขบัญชีก่อน | ใส่ชื่อเล่น Fox แล้วโปรแกรมจับคู่บัญชีให้ |
| การตั้งค่า KataGo | มักต้องเตรียมสภาพแวดล้อมและทรัพยากรเอง | แพ็กเกจแนะนำมาพร้อม KataGo และโมเดลเริ่มต้น |
| การเลือกดาวน์โหลดบน Windows | ผู้ใช้ต้องตัดสินใจเองหลายอย่าง | แยกแพ็กเกจ portable ตามฮาร์ดแวร์อย่างชัดเจน |
| การซิงก์กระดาน | มักต้องประกอบสภาพแวดล้อมเอง | แพ็กเกจหลักของ Windows มี `readboard.exe` แบบเนทีฟ |

## การเปิดครั้งแรกบน macOS

เลือกแพ็กเกจที่ตรงกับ Mac ของคุณ เปิด DMG แล้วลาก `LizzieYzy Next` ไปยัง Applications จากนั้นนำดิสก์ติดตั้งออกและเปิดโปรแกรมจากโฟลเดอร์ Applications ใน Finder เวอร์ชันทางการผ่านการลงนามและ notarization แล้ว หาก macOS ยังปิดกั้นโปรแกรม ให้ทำตาม [คู่มือติดตั้ง](docs/INSTALL.md)

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
