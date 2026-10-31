# 実装状況と検証結果

最終更新: 2026-10-31

凡例:
- ✅ 実装済み・自動テストで検証済み
- 🟡 実装済み・**実機未検証**（コンパイルと静的確認のみ）
- ⬜ 未実装

> 実機（iPhone + Android）での動作確認は、まだ一度も行っていません。下表で「実機未検証」としたものは、
> 動くことを確認したものではありません。実機で確認したら、この表の「実機結果」欄を更新してください。

## 事前検証（参考実装と Android API の対応）

| 項目 | 参考実装 (gousb/libusb) | Android での対応 | 判断 |
|---|---|---|---|
| Apple 機器の識別 | VID 0x05AC + usbmux 構成 (Class 0xFF / Subclass 0xFE) | `UsbDevice.vendorId` + `getConfiguration()` / `getInterface()` | 可能。Vendor ID だけで判断せず構成も確認する |
| 隠し構成の有効化 | `Control(0x40, 0x52, 0x0000, 0x0002)` | `UsbDeviceConnection.controlTransfer(0x40, 0x52, 0, 2, null, 0, timeout)` | 可能（API 12+） |
| 再認識 | 切断→再接続でデバイスが入れ替わる | `ACTION_USB_DEVICE_DETACHED/ATTACHED` が発生し、新しい `UsbDevice` になる。**権限は再取得が必要** | 対応済み（再認識待ち＋ポーリング＋権限再要求） |
| 構成の切り替え | `usbDevice.Config(n)` | `UsbDeviceConnection.setConfiguration()` (API 21+) | 可能。結果と `GET_CONFIGURATION` の値を診断に記録 |
| AV インターフェース | Class 0xFF / Subclass 0x2A、Bulk IN/OUT | `claimInterface(iface, force=true)` | 可能。番号は固定せず Descriptor から判定 |
| HALT 解除 | `Control(0x02, 0x01, 0, ep)` | `controlTransfer(0x02, 0x01, 0, ep, ...)` | 可能 |
| Bulk 受信 | `inEndpoint.NewStream(4096, 5)` | `UsbRequest.queue(ByteBuffer)` ×8 + `requestWait(timeout)` (API 26+) | 可能。minSdk を 26 にした理由 |
| Bulk 送信 | `outEndpoint.Write` | `bulkTransfer(ep, buf, off, len, timeout)` | 可能 |
| root / ADB | 不要 | 標準の USB 権限ダイアログのみ | 可能 |

未確定のリスク: Android カーネルが iPhone の再接続時にどの構成を選ぶか、`setConfiguration()` がすべての端末で成功するか、
iOS 17/18 での挙動。いずれも実機でしか確認できないため、診断情報に結果を記録するようにした。

## Phase 1: Android プロジェクト構築

| 項目 | 状態 | 備考 |
|---|---|---|
| Gradle プロジェクト (AGP 8.7.3 / Kotlin 2.0.21 / compileSdk 35 / minSdk 26) | ✅ | `./gradlew assembleDebug` 成功 |
| AndroidManifest（USB Host feature、USB_DEVICE_ATTACHED フィルタ、ネットワーク権限なし） | ✅ | |
| 基本 UI（タイトル・USB 状態・iPhone 認識状態・接続開始・エラー表示） | 🟡 | ビルドのみ確認。画面での確認は未実施 |
| GitHub Actions（push/PR でテスト・Lint・Debug APK Artifact） | ✅ | `.github/workflows/android.yml` |
| Release ワークフロー（v* タグ、Secrets 必須、署名検証） | 🟡 | Secrets 未設定のため未実行 |
| Debug 署名鍵の固定（`app/debug-shared.keystore`） | ✅ | 環境が変わっても Debug APK を上書き更新できる |

## Phase 2: USB 接続

| 項目 | 状態 | 実機結果 |
|---|---|---|
| Apple 機器の検出（VID + usbmux/QT 構成） | ✅ 判定ロジックは単体テスト済み (`AppleUsbTest`) / 🟡 Android 上 | 未 |
| Descriptor（構成・インターフェース・エンドポイント）取得と診断表示 | 🟡 | 未 |
| USB アクセス許可（標準ダイアログ、再認識後の再要求） | 🟡 | 未 |
| QuickTime 構成の有効化（制御転送）と再認識待ち（8 秒、1 回再試行） | 🟡 | 未 |
| `setConfiguration()` による構成切り替え | 🟡 | 未 |
| AV インターフェースの Claim と HALT 解除 | 🟡 | 未 |
| Bulk IN（UsbRequest ×8 常時キュー）/ Bulk OUT | 🟡 | 未 |
| 切断検知 | 🟡 | 未 |
| 停止時に QuickTime 構成を無効化し usbmux 構成へ戻す | 🟡 | 未 |

## Phase 3: 映像プロトコル

| 項目 | 状態 | 備考 |
|---|---|---|
| ストリームパーサー（分割・連結・異常 Length・破損からの再同期） | ✅ | `PacketFramerTest`（1 バイトずつ / 16KB / ランダム分割 / ゴミ混入 / 異常長） |
| PING 受信・返信 | ✅ | |
| SYNC: CWPA / AFMT / CVRP / CLOK / TIME / SKEW / OG / STOP と RPLY | ✅ | 実機キャプチャ（参考実装 fixtures）と返信バイト列が完全一致 |
| ASYN: HPD1 / HPA1 / HPD0 / HPA0 / NEED の生成 | ✅ | fixtures と完全一致 |
| ASYN: FEED / EAT / SPRP / SRAT / TBAS / TJMP / RELS の解析 | ✅ | |
| ClockRef（CWPA+1000, CVRP+0x1000AF, CLOK+0x10000）・Correlation ID | ✅ | 参考実装と同じ値 |
| CMSampleBuffer / FormatDescription / Dictionary の解析 | ✅ | 未知のアトムはエラーにせずスキップ（参考実装より寛容） |
| 未知の SYNC には空 RPLY を返して継続 | ✅ | 参考実装は停止する。デバイスが応答待ちで止まらないようにするための独自判断（実機未検証） |
| 停止手順（HPA0/HPD0 → RELS 待ち） | ✅ | |
| 実機の iPhone との通信 | 🟡 | **未検証** |

## Phase 4: 映像表示

| 項目 | 状態 | 備考 |
|---|---|---|
| avcC から SPS/PPS 抽出 | ✅ | 参考実装は SPS/PPS の名前が逆だった点を修正 |
| SPS 解析（解像度・プロファイル） | ✅ | iPhone の SPS で 1126×2436 を正しく算出 |
| 低遅延用 SPS 書き換え（max_num_reorder_frames=0） | ✅ | 実機キャプチャの SPS に bitstream_restriction が無いことを確認し、書き換え後の再解析で検証 |
| AVCC → Annex-B 変換、キーフレーム検出 | ✅ | |
| フォーマット変化検出（解像度変更・縦横切替） | ✅ ロジック / 🟡 MediaCodec 再生成 | |
| MediaCodec（非同期、低遅延キー、HW デコーダ優先、設定失敗時のフォールバック） | 🟡 | |
| SurfaceView 描画・縦横比維持・全画面・回転追従 | 🟡 | |
| 画面スリープ抑止 | 🟡 | |
| バックグラウンド時のダミー出力（ImageReader）と復帰 | 🟡 | |
| 切断・再接続（プラグ&プレイ自動開始） | 🟡 | |
| HEVC（hvcC）経路 | 🟡 | 参考実装でも未観測 |

## Phase 5: 品質向上

| 項目 | 状態 |
|---|---|
| エラー種別ごとの表示（未接続 / 権限拒否 / 有効化失敗 / 応答なし / 転送開始失敗 / 切断 / USB・プロトコル・デコードエラー） | 🟡 |
| 診断情報（テキスト表示・共有・保存・コピー、シリアル非取得） | 🟡 |
| デコーダ滞留時の破棄（遅延の蓄積防止）・統計（fps / デコード遅延） | 🟡 |
| 音声再生 | ⬜ 後続機能 |

## 自動テスト結果

`./gradlew testDebugUnitTest`（2026-10-31、ローカル）: **67 件成功 / 0 件失敗**

| テストクラス | 件数 | 内容 |
|---|---|---|
| `PacketBuildersTest` | 10 | 送信パケット（NEED/HPD1/HPA1/HPD0/HPA0/PING）と辞書シリアライズが実機キャプチャと一致 |
| `SyncPacketsTest` | 10 | SYNC 各種の解析と返信バイト列、ASYN 情報パケット |
| `CoreMediaTest` | 13 | Dictionary / FormatDescription / CMSampleBuffer の解析、破損データ |
| `QuickTimeProtocolTest` | 8 | セッション確立の送受信順序、FEED→NEED、破損 FEED、停止手順 |
| `PacketFramerTest` | 8 | 分割・連結・再同期 |
| `H264Test` / `H264IphoneSpsTest` | 9 | SPS 解析・低遅延書き換え・エミュレーション防止・Annex-B 変換 |
| `VideoStreamProcessorTest` | 3 | フォーマット通知とキーフレーム待ち |
| `AppleUsbTest` | 6 | iPhone の構成判定（有効化前後）、非 Apple 機器、診断出力にシリアルが含まれないこと |

`./gradlew lintDebug`: エラー 0（警告のみ）。`./gradlew assembleRelease`: 成功（Secrets が無い環境では未署名 APK になり、CI では公開しない）。

## 実機検証で確認が必要な項目

1. USB-C ⇔ USB-C / USB-C ⇔ Lightning で Android が USB ホストになり iPhone を検出できるか
2. 制御転送 0x40/0x52 後に iPhone が再接続され、Subclass 0x2A のインターフェースを持つ構成が見えるか
3. 再接続後の権限ダイアログの出方（「C2Mirror を開く」を既定にした場合は不要になるか）
4. `setConfiguration()` が成功するか（診断の「アクティブ構成」の before/after）
5. PING → CWPA → AFMT → CVRP → CLOK → TIME → FEED の順に進むか
6. MediaCodec で映像が表示されるか、解像度・fps・デコード遅延の実測値
7. iPhone の回転、Android の回転、アプリのバックグラウンド→復帰
8. 「切断」後と、ケーブル抜き差し後の自動再開
9. 長時間（1 時間以上）の連続表示での安定性・メモリ
10. iOS 17 / 18 / 26 での互換性
