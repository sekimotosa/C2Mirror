# C2Mirror

USB Type-C ケーブル 1 本で iPhone と Android 端末を直接つなぎ、iPhone の画面を Android 上にリアルタイム表示するオープンソースの Android アプリです。

- iPhone 側へのアプリのインストールは不要
- Wi-Fi・モバイル通信・インターネットは使用しない（アプリはネットワーク権限を持っていません）
- USB キャプチャ機器・PC・ADB・root は不要
- アカウント登録・課金・表示時間の制限はありません
- **アプリを開いた状態で iPhone をケーブルで接続するだけで、自動的にミラーリングが始まります（プラグ&プレイ）**

> **現在の状態（重要）**
> プロトコル処理・映像デコード・UI・CI は実装済みで、参考実装の実機キャプチャデータを使った単体テストに合格しています。
> ただし **iPhone ↔ Android の実機での動作確認はまだ行っていません**。実機での結果は
> [docs/IMPLEMENTATION_STATUS.md](docs/IMPLEMENTATION_STATUS.md) に記録します。うまく動かない場合は、アプリの「診断情報」をエクスポートして Issue で共有してください。

---

## 1. 概要

macOS の QuickTime Player は、USB で接続された iPhone の画面を録画できます。この機能は、iPhone の USB 上の隠し構成
（QuickTime 構成）を使う独自プロトコルで実現されています。
C2Mirror は、このプロトコルを解析したオープンソース実装 [quicktime_video_hack](https://github.com/danielpaulus/quicktime_video_hack)（Go 言語、MIT License）を Kotlin に移植したものです。Android の USB Host API で iPhone と直接通信し、送られてくる H.264 映像を MediaCodec でデコードして表示します。

```
iPhone ──USB-C──▶ Android USB Host API
                    └ UsbDeviceManager   接続検出・アクセス許可・切断検知
                    └ UsbTransport       Control / Bulk 転送（Bulk IN は複数 UsbRequest を常時キュー）
                    └ PacketFramer       バイト列 → パケット分割（分割・連結・破損からの再同期）
                    └ QuickTimeProtocol  PING / SYNC(CWPA,AFMT,CVRP,CLOK,TIME,SKEW,OG,STOP) / ASYN(FEED,EAT,NEED,HPD1/0,HPA1/0,RELS...)
                    └ VideoStreamProcessor  CMSampleBuffer → H.264 NAL・SPS/PPS 管理・解像度変更検出
                    └ VideoDecoder       MediaCodec（非同期・低遅延設定）→ SurfaceView
                    └ MirrorSession      状態遷移・プラグ&プレイ・ライフサイクル
```

### 低遅延化のための設計

映像の遅延をできる限り小さくするため、次の工夫をしています。

| 箇所 | 内容 |
|---|---|
| USB 受信 | `UsbRequest` を 8 本同時にキューし、USB 転送が途切れないようにする（1 転送ごとの往復待ちを隠蔽） |
| パケット分割 | 受信バッファ上でそのままパケットを切り出す（不要なコピーをしない）。大きな IDR フレームは必要量を一度に確保 |
| NEED 応答 | FEED（映像フレーム）を受け取ったら、デコード処理より **先に** NEED を返し、次フレームの転送を並行して開始させる |
| デコーダ投入 | 受信スレッドから直接 MediaCodec の入力バッファへ書き込む（中間キューを通さない）。スレッドは高優先度 |
| SPS 書き換え | iPhone の SPS には `bitstream_restriction` が無いため、Android のデコーダが表示順並べ替え用にフレームを溜め込むことがあります。`max_num_reorder_frames=0` を追加した SPS をデコーダに渡し、溜め込みを防ぎます（単体テストで検証済み） |
| MediaCodec 設定 | `KEY_LOW_LATENCY`（対応デコーダのみ）、`KEY_PRIORITY=0`（リアルタイム）、Qualcomm / Exynos / Amlogic / MediaTek のベンダー低遅延キー |
| 描画 | タイムスタンプによる待ち合わせをせず、デコード完了次第すぐに描画。古いフレームは描画側で破棄 |
| 滞留対策 | デコーダが追いつかず入力待ちが溜まった場合は破棄してキーフレームから再開し、遅延を蓄積させない |

実行中は画面上部をタップすると、表示 fps と「デコード遅延（デコーダへの投入から出力まで）」が表示されます。

## 2. 対応環境

| 項目 | 条件 |
|---|---|
| Android | Android 8.0 (API 26) 以上、**USB Host（OTG）対応端末** |
| iPhone | Lightning / USB-C の iPhone（参考実装で動作報告のある iOS のバージョン。iOS 17 / 18 以降の動作は未確認） |
| デコード | 端末の H.264 ハードウェアデコーダ（初期目標 720p〜1080p / 30fps 程度。実際の解像度・fps は iPhone の送出と端末性能に依存） |

USB Host 非対応の端末でもインストールはできますが、起動時に「USB Host: 非対応」と表示されます。

## 3. 必要な機材

- Android 端末（USB-C、USB Host 対応）
- iPhone
- ケーブル（どちらか）
  - iPhone 15 以降（USB-C）: **USB-C ⇔ USB-C ケーブル**（データ通信対応のもの。充電専用ケーブルは不可）
  - iPhone 14 以前（Lightning）: **USB-C ⇔ Lightning ケーブル**（データ通信対応）

USB-C ⇔ USB-C の場合、どちらが USB ホストになるかは端末同士の役割交渉で決まります。Android 側がホストにならない場合は「トラブルシューティング」を参照してください。

## 4. APK のダウンロード方法

### 正式版（推奨）
[GitHub Releases](https://github.com/sekimotosa/C2Mirror/releases) から `C2Mirror-<バージョン>.apk` をダウンロードします。署名済みの Release APK です。同じページの `SHA256SUMS.txt` でファイルの整合性を確認できます。

### 開発版（最新の main ブランチ）
1. GitHub の [Actions](https://github.com/sekimotosa/C2Mirror/actions) タブ →「Android CI」ワークフローを開く
2. 成功（緑）した実行を選ぶ
3. 下部の **Artifacts** から `C2Mirror-debug-apk` をダウンロード（GitHub へのログインが必要）
4. ダウンロードした zip を展開すると `C2Mirror-debug-xxxxxxx.apk` が入っています

開発版は applicationId が `io.github.sekimotosa.c2mirror.debug` で、正式版と別アプリとして同時にインストールできます。開発版はリポジトリに同梱した **公開 Debug 鍵**（`app/debug-shared.keystore`）で署名しているため、どの環境・どの CI 実行でビルドしても同じ署名になり、上書き更新が可能です（この鍵は秘密ではなく、正式版には絶対に使いません）。

## 5. APK のインストール方法

1. APK ファイルを Android 端末に保存します（端末のブラウザで直接ダウンロードするのが簡単です）
2. ファイルマネージャーやブラウザのダウンロード一覧から APK をタップします
3. 「**不明なアプリのインストール**」の許可を求められたら、APK を開いたアプリ（ブラウザ／ファイルマネージャー）に対して許可します
   - 設定 → アプリ → 特別なアプリアクセス → 不明なアプリのインストール からも設定できます（メーカーにより表記が異なります）
4. 「インストール」をタップします

開発者モードや ADB は **不要** です。

開発者向けの任意の手段として、PC から `adb install -r C2Mirror-xxx.apk` でもインストールできます。

## 6. iPhone と Android の接続手順

1. Android で **C2Mirror を起動** します（「iPhone を USB-C ケーブルで接続してください」と表示されます）
2. iPhone のロックを解除し、ケーブルで Android と接続します
3. Android に USB アクセスの許可ダイアログが表示されたら「OK」をタップします
   - 「**このデバイスが接続されたら C2Mirror を開く**」（または「常に使用」）にチェックを入れておくと、次回以降はダイアログなしで開始できます
4. iPhone が画面転送モードに切り替わる際に一度 USB の再接続が発生します。もう一度許可ダイアログが出た場合は「OK」をタップします
5. iPhone に「このコンピュータを信頼しますか？」が表示された場合は「信頼」をタップします
6. 自動的にミラーリングが始まります

## 7. アプリの操作方法

| 画面 | 操作 |
|---|---|
| 初期画面 | USB Host の対応状況、iPhone の認識状態、接続処理の進行状況、エラーが表示されます |
| 接続開始 | 自動開始をオフにしている場合や、エラー後に再試行する場合に押します |
| 自動でミラーリング開始 | オン（既定）の場合、アプリを開いたまま iPhone を接続すると自動で開始します |
| ミラーリング中 | 映像を縦横比を保ったまま全画面表示します。Android 端末の回転・iPhone 側の縦横切り替えに追従します |
| 画面をタップ | 上部に状態（解像度・fps・デコード遅延）と「切断」「診断情報」ボタンを表示します（3 秒で消えます） |
| 切断 | iPhone に停止を通知し、通常の USB 構成に戻して初期画面へ戻ります。ケーブルを一度抜くか「接続開始」で再開できます |
| 診断情報 | 下記の診断項目をテキストで表示し、共有・ファイル保存・コピーができます |

ケーブルを抜くと初期画面に戻り、再び接続すると自動で再開します。ミラーリング中は画面スリープを抑止します。

### 診断情報で確認できる項目
USB Host 対応 / iPhone 検出 / Vendor ID・Product ID / USB Configuration・Interface・Endpoint 一覧 / USB アクセス許可の状態 / QuickTime 構成の有効化結果 / 再認識の結果 / アクティブ構成 / AV セッション状態 / PING・クロック交換 / 最初の映像パケット受信 / 映像フォーマット / MediaCodec の初期化・デコード統計 / 最後のエラー / 時刻付きログ

シリアル番号（UDID）は取得すらしていません。映像データ・認証情報も保存・出力しません。診断情報がネットワークへ送信されることはありません（共有は利用者が明示的に操作した場合のみ）。

## 8. ビルド手順

必要なもの: JDK 17 以上、Android SDK（compileSdk 35）

```bash
git clone https://github.com/sekimotosa/C2Mirror.git
cd C2Mirror
echo "sdk.dir=/path/to/Android/sdk" > local.properties   # Android Studio を使う場合は不要
./gradlew testDebugUnitTest   # 単体テスト
./gradlew assembleDebug       # → app/build/outputs/apk/debug/app-debug.apk
```

Android Studio で開いてビルドすることもできます。

## 9. GitHub Actions の説明

| ワークフロー | トリガー | 内容 |
|---|---|---|
| `Android CI` (`.github/workflows/android.yml`) | `main` への push、Pull Request、手動 | 単体テスト → Lint → Debug APK ビルド → Artifact `C2Mirror-debug-apk` として保存。いずれかが失敗するとワークフローは失敗します |
| `Release` (`.github/workflows/release.yml`) | `v*` タグの push（例: `v0.1.0`） | 署名用 Secrets を確認 → 単体テスト → 署名済み Release APK をビルド → `apksigner verify` で署名を検証 → GitHub Release を作成して APK と SHA256 を添付 |

Release ワークフローは Secrets が 1 つでも未設定ならその時点で失敗し、**未署名の APK を公開することはありません**。バージョンはタグから決まります（`v1.2.3` → versionName `1.2.3`、versionCode `10203`）。

## 10. Release 署名の設定方法

1. 署名鍵を作成します（一度だけ。**この鍵とパスワードは厳重に保管してください。紛失すると同じアプリとして更新できなくなります**）
   ```bash
   keytool -genkeypair -v -keystore c2mirror-release.jks -alias c2mirror \
     -keyalg RSA -keysize 4096 -validity 10000
   ```
2. keystore を Base64 にします
   ```bash
   base64 -w0 c2mirror-release.jks > keystore.b64     # macOS: base64 -i c2mirror-release.jks > keystore.b64
   ```
3. GitHub のリポジトリ → Settings → Secrets and variables → Actions → New repository secret で次の 4 つを登録します

   | Secret 名 | 値 |
   |---|---|
   | `RELEASE_KEYSTORE_BASE64` | `keystore.b64` の中身 |
   | `RELEASE_KEYSTORE_PASSWORD` | keystore のパスワード |
   | `RELEASE_KEY_ALIAS` | 鍵のエイリアス（上の例では `c2mirror`） |
   | `RELEASE_KEY_PASSWORD` | 鍵のパスワード |

4. タグを push するとリリースされます
   ```bash
   git tag v0.1.0
   git push origin v0.1.0
   ```

keystore やパスワードはリポジトリにコミットしないでください（`.gitignore` で `*.jks` と `keystore.properties` を除外しています）。
ローカルで Release ビルドする場合は、コミットしない `keystore.properties`（`storeFile` / `storePassword` / `keyAlias` / `keyPassword`）をプロジェクト直下に置くか、環境変数 `C2M_RELEASE_STORE_FILE` などを設定します。

## 11. トラブルシューティング

| 症状 | 確認すること |
|---|---|
| 「USB Host: 非対応」 | その端末は USB ホスト機能（OTG）を持たないため利用できません |
| 「iPhone: 未接続」のまま | データ通信対応ケーブルか確認。USB-C ⇔ USB-C では Android がホスト側にならない場合があります。ケーブルの向き（両端）を入れ替える、Android の「USB の設定」で「このデバイスで USB を制御」を選ぶ、Lightning の場合は USB-C ⇔ Lightning ケーブルを使う、を試してください |
| USB 権限が拒否されました | もう一度接続して、ダイアログで「OK」をタップしてください |
| 画面転送モードの有効化に失敗 | iPhone のロックを解除し、「このコンピュータを信頼」が表示されたら「信頼」をタップ。ケーブルを抜き差しして再試行 |
| iPhone からの応答なし / 映像転送の開始に失敗 | iPhone のロックを解除して再接続。iPhone の再起動。診断情報の「インターフェース Claim」「アクティブ構成」を確認 |
| 映像が出ない（ミラーリング中なのに黒い） | 診断情報の「デコーダ」「統計（rendered）」を確認。iPhone の画面を少し動かす（静止画面ではフレームが送られないことがあります） |
| 遅延が大きい | 画面タップで表示される「デコード遅延」を確認。数百 ms 以上なら端末のデコーダ性能の可能性があります。診断情報を Issue で共有してください |
| 充電されない | iPhone ↔ Android 間の給電は端末同士の交渉に依存し、アプリでは制御できません |

問題を報告するときは、診断情報を「保存」または「共有」して Issue に添付してください（シリアル番号や映像は含まれません）。

## 12. 既知の制限事項

- **実機での動作は未検証です**（詳細は [docs/IMPLEMENTATION_STATUS.md](docs/IMPLEMENTATION_STATUS.md)）
- 音声は転送の初期化のみ行い、再生はしません（後続機能）
- iPhone のタッチ操作を Android から行う機能はありません（仕様上不要）
- 新しい iOS（17 / 18 以降）での互換性は不明です。参考実装にも iOS 18 で動作しないという報告（[issue #159](https://github.com/danielpaulus/quicktime_video_hack/issues/159)、macOS + libusb 環境での USB 初期化エラー）があります。Android の USB スタックは libusb を使わないため同じ問題が起きるとは限りませんが、確認が必要です
- iPhone が HEVC で送ってきた場合のデコード経路は実装していますが、参考実装では H.264（avc1）しか観測されておらず未検証です
- アプリがバックグラウンドに移ると表示は止まります（接続は維持し、戻ると再開します）。端末のデコーダによっては、復帰後に次のキーフレームまで映像が止まることがあります
- 「切断」後、iPhone の USB 再接続に 4 秒以上かかると自動開始が再度動く場合があります

## 13. 使用した OSS とライセンス

C2Mirror は MIT License です（[LICENSE](LICENSE)）。

| OSS | 用途 | ライセンス |
|---|---|---|
| [quicktime_video_hack](https://github.com/danielpaulus/quicktime_video_hack) © 2019 danielpaulus | QuickTime USB AV プロトコルの仕様・実装を Kotlin に移植。単体テストのフィクスチャ（実機キャプチャ）をそのまま使用 | MIT |
| AndroidX Core | 互換 API（BroadcastReceiver 登録、全画面表示） | Apache 2.0 |
| Kotlin 標準ライブラリ | — | Apache 2.0 |
| JUnit 4 | 単体テストのみ（APK には含まれません） | EPL 1.0 |

著作権表示とライセンス全文は [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) にあります。

本プロジェクトは公開された技術資料と上記 OSS のみに基づいて独自に実装しています。他社製アプリのコードや APK の解析結果は一切使用していません。Apple、iPhone、QuickTime は Apple Inc. の商標です。C2Mirror は Apple Inc. とは関係ありません。
