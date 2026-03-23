# インストールガイド

このガイドは、`LizzieYzy Next` をできるだけ早く使い始めたい人向けです。

## まず覚えること

1. Windows 利用者の多くは `windows64.with-katago.installer.exe` を選べば大丈夫です。
2. `with-katago` パッケージは KataGo と既定の重みを含みます。
3. 野狐棋譜を取得するときは、いまは **野狐のニックネーム** を入力します。

## ダウンロードするもの

| 環境 | 推奨パッケージ |
| --- | --- |
| Windows x64 | `<date>-windows64.with-katago.installer.exe` |
| Windows x64、インストーラ不要 | `<date>-windows64.with-katago.portable.zip` |
| Windows x64、自分でエンジン設定 | `<date>-windows64.without.engine.portable.zip` |
| macOS Apple Silicon | `<date>-mac-arm64.with-katago.dmg` |
| macOS Intel | `<date>-mac-amd64.with-katago.dmg` |
| Linux x64 | `<date>-linux64.with-katago.zip` |

## Windows

1. `windows64.with-katago.installer.exe` をダウンロードします。
2. インストーラを実行します。
3. インストール完了後、`LizzieYzy Next.exe` を起動します。

## macOS

1. Apple Silicon か Intel かを確認します。
2. 対応する `.dmg` を開きます。
3. `LizzieYzy Next.app` を `Applications` にドラッグします。
4. 初回にブロックされた場合は、`システム設定 -> プライバシーとセキュリティ` から `このまま開く` を選びます。

## Linux

```bash
chmod +x start-linux64.sh
./start-linux64.sh
```

## 初回起動で自動で行うこと

- 内蔵 KataGo の検出
- 既定の重みの確認
- 利用可能な既定設定の作成

## 野狐棋譜の取得

1. アプリを起動します。
2. **野狐棋譜（ニックネームで取得）** を開きます。
3. 野狐のニックネームを入力します。
4. アプリが対応するアカウントを見つけて、最近の公開棋譜を取得します。

ニックネームが違う場合や公開棋譜がない場合は、結果が空になることがあります。
