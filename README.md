# ✂️ ClipScout

**スクリーンショット → スカウトDM 自動生成 Androidアプリ**

Instagram等でスクリーンショットを撮影すると、バックグラウンドで自動検知し、Gemini 1.5 Flash APIを用いてスカウトDM文を生成、自動的にクリップボードへコピーします。

---

## 📋 機能

- 📷 **スクリーンショット自動検知** — バックグラウンドで常時監視（Foreground Service）
- 🤖 **Gemini 1.5 Flash 連携** — 画像＋プロンプトでスカウトDM文を生成
- 📋 **自動クリップボードコピー** — Android 10以降のバックグラウンド制限を回避して自動コピー
- 🔒 **安全なAPIキー保管** — EncryptedSharedPreferences（AES256-GCM）
- ⚡ **端末起動時の自動再開** — BroadcastReceiverでサービスを自動スタート
- 📱 **ON/OFFスイッチ** — アプリ内のトグルで監視を即座に切り替え

---

## 🛠️ セットアップ

### 必要な権限（初回起動時に設定画面から付与）

| 権限 | 用途 |
|------|------|
| メディア読み取り | スクリーンショット画像の読み込み |
| 通知の表示 | 処理完了・エラーの通知 |
| **他のアプリの上に表示** | ⚠️ クリップボード書き込みに必須 |

### Gemini APIキーの取得

1. [Google AI Studio](https://aistudio.google.com/) にアクセス
2. 「Get API Key」から新しいAPIキーを作成
3. アプリの設定画面にAPIキーを貼り付けて「設定を保存」

---

## 📦 インストール方法（GitHub Actions経由）

### 1. GitHubにプッシュしてビルドを実行

```bash
git init
git remote add origin https://github.com/YOUR_USERNAME/ClipScout.git
git add .
git commit -m "Initial commit"
git push origin main
```

### 2. APKをダウンロード

1. GitHubリポジトリの「Actions」タブを開く
2. 最新のワークフロー実行を選択
3. 「Artifacts」セクションから `ClipScout-debug-XXX` をダウンロード
4. ZIPを解凍して `app-debug.apk` を取得

### 3. Androidへインストール

**方法A: USB経由（ADB）**
```bash
adb install app-debug.apk
```

**方法B: 直接インストール（提供元不明アプリの許可が必要）**
1. 端末の「設定」→「セキュリティ」→「提供元不明のアプリ」を許可
2. APKファイルをAndroidへ転送してタップでインストール

---

## ⚙️ 技術仕様

### アーキテクチャ

```
MainActivity (設定UI)
    ↕ ON/OFF
ScreenshotDetectionService (Foreground Service)
    ↓ MediaStore ContentObserver
    ↓ GeminiApiClient (OkHttp + Coroutines)
    ↓
ClipboardHelperActivity (透過Activity)
    → ClipboardManager.setPrimaryClip()
    → NotificationManager (コピー完了通知)
```

### クリップボードのバックグラウンド制限回避（重要）

Android 10以降、バックグラウンドからのクリップボード書き込みはブロックされます。
本アプリでは以下の手法で回避しています：

1. **SYSTEM_ALERT_WINDOW権限** を保持することで、バックグラウンドからのActivity起動制限を回避
2. **透過ClipboardHelperActivity**（1px・透明）をバックグラウンドから起動
3. `onWindowFocusChanged(hasFocus=true)` のタイミング（フォアグラウンド確保後）でクリップボード書き込み
4. 即座に `finish()` でActivityを終了

### 技術スタック

| 項目 | 内容 |
|------|------|
| 言語 | Kotlin |
| ターゲット | Android 13 (API 33) 以上 |
| ビルド | Android Gradle Plugin 8.3.2 |
| HTTP | OkHttp 4.12.0 |
| 非同期 | Kotlin Coroutines 1.8.1 |
| セキュリティ | Jetpack Security Crypto 1.1.0-alpha06 |
| UI | Material Design 3 |
| CI/CD | GitHub Actions |

---

## 📁 ファイル構成

```
ClipScout/
├── .github/workflows/android.yml          # CI/CD
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/clipscout/app/
│       │   ├── MainActivity.kt             # 設定UI
│       │   ├── ScreenshotDetectionService.kt # バックグラウンド監視
│       │   ├── GeminiApiClient.kt          # Gemini API通信
│       │   ├── ClipboardHelperActivity.kt  # クリップボード書き込み
│       │   ├── BootReceiver.kt             # 起動時自動スタート
│       │   └── PreferencesHelper.kt        # 暗号化設定管理
│       └── res/
│           ├── layout/activity_main.xml
│           ├── layout/activity_clipboard_helper.xml
│           └── values/ (strings, colors, themes)
├── build.gradle.kts
├── settings.gradle.kts
├── gradlew / gradlew.bat
└── gradle/wrapper/gradle-wrapper.properties
```

---

## ⚠️ 注意事項

- `SYSTEM_ALERT_WINDOW`（他のアプリの上に表示）の権限付与が必須です
- デバッグAPKのインストールには端末側で「提供元不明のアプリ」の許可が必要です
- APIキーはデバイスローカルにAES256-GCM暗号化で保存されます（外部送信なし）
- スクリーンショットの画像データはGemini APIへの通信にのみ使用されます

---

## 📜 ライセンス

MIT License
