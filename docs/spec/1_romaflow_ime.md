# RomaFlow IME 調査メモと展望

## 背景

RomaFlow IME は、ローマ字入力を起点に、入力中の文脈を AI で逐次日本語へ変換していく IME である。

従来の日本語入力は、入力中に `romaji -> かな -> 漢字候補選択 -> 確定` を繰り返す。RomaFlow ではこの逐次候補選択をユーザーに強制せず、macOS のライブ変換に近い体験として、入力バッファを継続的に変換候補へ反映する。ユーザーは必要な箇所だけ候補を修正し、最後に確定する。

この考え方は、F太さんが紹介した「romaji記法」や、Gemini API と Raycast を組み合わせた自作変換、Sumibi のようなモードレス IME 実験と近い方向性にある。ただし RomaFlow は、入力後に一括変換する補助ツールではなく、IME として入力中に変換結果を更新し続けることを目指す。

## コンセプト

キーワードは「変換をしない」だが、RomaFlow では「ユーザーが変換操作をしない」と解釈する。

ユーザーはローマ字で入力し続ける。RomaFlow はバックグラウンドで入力バッファを読み、AI provider によるライブ変換、形態素分析、単語ごとの候補生成、候補修正、確定という流れを管理する。

```text
romaji input
  -> live AI conversion
  -> morphological segmentation
  -> per-word candidate correction
  -> commit
```

## 調査で分かったこと

### 話題の中心

- IME の候補選択を細かく行わず、ローマ字のまま文章を入力する。
- LLM が誤字、句読点、漢字、かな混じり文を文脈込みで整える。
- Markdown、URL、コード、CLI のように IME のオン・オフが邪魔になりやすい場面と相性が良い。
- 一括変換だけでなく、IME として成立させるにはライブ変換、候補修正、確定操作が必要になる。

### 既存の近い実装

- F太さんの romaji 記法は、AI チャットへの入力効率化として注目されている。
- Gemini API と Raycast を組み合わせる運用では、選択中のローマ字テキストを Gemini に渡して貼り戻す。
- Sumibi は Emacs 上で、ローマ字列を LLM によって日本語へ変換するモードレス IME 的な実装を進めている。
- macOS のライブ変換は、ユーザーが変換キーを押さずに変換結果が更新される体験として参考になる。

## プロダクト方針

RomaFlow は「変換キーを押して一括変換するツール」ではなく、「入力中に AI がライブ変換し、必要な箇所だけ人間が直す IME」を目指す。

最初の価値は、思考を止めずに入力し続けられること。ユーザーが細かい漢字候補を毎回選ばなくても、文脈に合った変換結果が随時提示される状態を目標にする。

重視すること:

- ローマ字で書き続けられること
- 変換キーなしで候補が更新されること
- AI の変換結果を単語単位で修正できること
- 修正内容が以降の変換へ反映されること
- Markdown、URL、コードを壊さないこと
- secure input ではクラウド変換を無効化すること
- on-device AI を優先候補にし、プライバシーと速度を両立すること

やらないこと:

- Gemini 固定の IME にしない
- 入力全文を無制限にクラウド送信しない
- パスワード、認証コード、カード番号などを AI provider に送らない
- LLM の言い換えを無制限に許容しない
- ユーザーが修正した変換をすぐ上書きしない

## macOS first 方針

最初の公開対象は Android ではなく macOS を優先する。

理由は、macOS では物理キーボード入力を前提にでき、Android のようにソフトウェアキーボード UI を最初から作り込む必要がないためである。RomaFlow の本質はキー配列やキーボード UI ではなく、ライブ変換エンジン、変換候補の分割、候補修正、確定までの状態管理にある。macOS から着手すれば、KMP shared core の変換モデルを先に固められる。

ただし macOS IME が簡単という意味ではない。`InputMethodKit` は歴史が長い API で、アプリごとの preedit 挙動、候補ウィンドウ、確定タイミング、Swift / Objective-C / Kotlin/Native の橋渡し、配布と notarization に癖がある。難しさはあるが、最初に検証すべき難しさが「変換エンジンと IME としての状態管理」に寄るため、Android より初期コストを制御しやすい。

macOS first の狙い:

- ライブ変換の UX を物理キーボードで検証する。
- KMP shared core の `InputBuffer` / `ConversionDraft` / `Segment` を先に安定させる。
- 候補修正、segment lock、確定処理を OS の IME ライフサイクル上で確認する。
- provider は最初から Gemini に固定せず、fake provider、cloud provider、local provider を差し替えられる構造にする。
- Android のソフトウェアキーボード UI は、変換エンジンの挙動が固まってから着手する。

## 変換モデル

RomaFlow の変換状態は、入力文字列と変換結果の単純なペアではなく、セグメント列として扱う。

```text
InputBuffer
  rawRomaji: "kyounokaigiha..."
  contextBefore: ...
  contextAfter: ...

ConversionDraft
  segments:
    - raw: "kyou"
      reading: "きょう"
      text: "今日"
      candidates: ["今日", "きょう", "京"]
      confidence: 0.92
      locked: false
    - raw: "kaigi"
      reading: "かいぎ"
      text: "会議"
      candidates: ["会議", "会議は", "懐疑"]
      confidence: 0.88
      locked: false
```

重要な状態:

- `raw`: ユーザーが実際に入力したローマ字片
- `reading`: かな読み
- `text`: 現在表示する変換結果
- `candidates`: 単語ごとの修正候補
- `confidence`: AI または辞書変換の信頼度
- `locked`: ユーザーが明示的に選んだため自動更新しない候補

## ライブ変換フロー

基本フローは次の通り。

1. ユーザーが RomaFlow でローマ字を入力する。
2. OS 固有層が composing buffer を更新する。
3. shared core が debounce と context window を管理する。
4. AI provider または fake provider が現在のバッファをライブ変換する。
5. 形態素分析器が変換結果を単語単位に分割する。
6. 辞書変換または AI によって単語ごとの候補を生成する。
7. IME UI が変換済みテキストと候補を表示する。
8. ユーザーが必要な単語だけ候補を修正する。
9. 修正された segment は `locked` になり、次回のライブ変換で保護される。
10. ユーザーが確定操作を行い、OS の text input client へ commit する。

クラウド provider の場合は、毎キー入力で送信しない。入力のまとまり、句読点、一定時間の停止、文節境界などをトリガーに debounce する。on-device provider の場合は、より短い間隔でライブ変換を試せる。

## 形態素分析と候補修正

AI の変換結果はそのまま確定しない。変換結果を形態素分析し、単語または文節単位で候補を出せるようにする。

必要な機能:

- 変換済み文の tokenization
- raw romaji と変換済み token の alignment
- 単語ごとの候補生成
- 候補選択時の segment lock
- 以降のライブ変換で lock 済み segment を保持
- ユーザー辞書への学習

候補生成の情報源:

- 既存 IME / 日本語変換ライブラリ
- 形態素解析辞書
- AI provider からの n-best 候補
- ユーザー辞書
- 過去の修正履歴

形態素解析器や辞書変換は、既存ライブラリを優先して調査する。自前で日本語 tokenizer や辞書変換器を作らない。

## AI Provider 方針

AI provider は差し替え可能にする。Gemini は候補の一つであり、RomaFlow の中核を Gemini 固定にしない。

provider 候補:

- fake provider
- rule-based provider
- Cloud Gemini API
- その他の cloud LLM API
- macOS local model
- Android の on-device AI runtime
- Gemma 系などのローカルモデル

provider に求める能力:

- romaji から自然な日本語への変換
- 入力途中の不完全な文への追従
- 低レイテンシ
- n-best または候補生成
- Markdown / URL / code block の保持
- lock 済み segment の尊重

最初の provider は fake provider にする。IME ライフサイクル、preedit 更新、candidate 表示、segment lock が安定するまでは、AI の品質問題と IME の状態管理問題を混ぜない。

実 provider は段階的に追加する。開発初期の品質確認には Cloud Gemini API が使えるが、プロダクトの本命 provider としては on-device AI を優先評価する。特に Android では、端末上で動く Gemma 系モデルや OS / SDK が提供する on-device AI を評価する。具体的なモデル名、実行ランタイム、対応端末、配布サイズ、速度は別途調査して決める。

## macOS での実現性

macOS は `InputMethodKit` によってカスタム入力メソッドを提供できる。

RomaFlow の macOS 実装では、以下を macOS 固有層として扱う。

- `IMKServer`
- `IMKInputController`
- key event の受け取り
- composing buffer の管理
- ライブ変換の preedit 更新
- `IMKCandidates` または独自 candidate UI による候補表示
- 確定処理
- 設定画面
- Keychain への API key 保存
- macOS local model との接続
- secure input 時の cloud provider 停止
- notarization / 配布形式の調整

変換エンジンは Kotlin/Native の framework として出力し、Swift または Objective-C から呼び出す想定とする。最初は native 側を薄くし、入力イベント、preedit、candidate、commit だけを OS adapter として実装する。

macOS 先行で検証すること:

- `InputMethodKit` 上でローマ字入力を preedit として維持できるか。
- shared core から返る `ConversionDraft` を preedit 表示へ反映できるか。
- candidate 表示と segment lock の操作感が成立するか。
- アプリごとの text input client 差異に耐えられるか。
- cloud provider を使う場合に secure input と privacy policy を適切に扱えるか。

## Android での実現性

Android は `InputMethodService` によって自作 IME を提供できる。

ただし Android は macOS より初期 UI コストが高い。物理キーボードだけでなく、ソフトウェアキーボード、candidate strip、設定画面、各種入力欄への対応を作る必要がある。RomaFlow では、shared core と macOS IME でライブ変換の挙動を固めてから Android に展開する。

RomaFlow の Android 実装では、以下を Android 固有層として扱う。

- `InputMethodService`
- `InputConnection`
- composing text の管理
- keyboard UI
- candidate strip
- secure input field の検知
- IME 設定画面
- on-device AI runtime との接続
- Play Store 向けの privacy / data safety 表記

共有できる変換ロジックは Kotlin Multiplatform 側に置く。

## アーキテクチャ方針

基本ロジックは Kotlin Multiplatform に置き、OS 準拠部分だけを各プラットフォームのネイティブ実装にする。

```text
shared core
  - input buffer
  - romaji tokenizer
  - live conversion scheduler
  - context window builder
  - conversion draft model
  - segment alignment
  - candidate model
  - segment lock policy
  - privacy guard
  - provider abstraction
  - user dictionary
  - correction history

morphology layer
  - tokenizer adapter
  - dictionary conversion adapter
  - candidate generator

macOS app
  - InputMethodKit host
  - Swift or Objective-C bridge
  - preedit / candidate UI
  - Keychain settings
  - macOS distribution

android app
  - InputMethodService
  - InputConnection bridge
  - keyboard UI
  - candidate strip
  - Android settings
  - secure field detection
  - on-device AI bridge
```

## Prompt / Provider Request 方針

Cloud LLM を使う場合でも、provider request は単純な全文変換にしない。

守るべき制約:

- 入力内容を説明しない
- 意味を勝手に増やさない
- 入力途中の未確定部分として扱う
- lock 済み segment を変更しない
- Markdown 記法を保持する
- URL を変更しない
- コードブロックを変換しない
- 固有名詞は推測で置き換えない
- 不確かな語は候補として返す
- 可能なら segment と candidate を構造化して返す

理想の provider response は、単なる変換済み文字列ではなく、segment 配列と候補を含む structured output とする。

## セキュリティとプライバシー

RomaFlow は IME であるため、通常アプリよりも高い信頼設計が必要になる。

必須事項:

- 初回起動時に、クラウド AI を使う場合は入力内容を外部 API に送る可能性を明示する。
- default provider は on-device または local を優先する。
- cloud provider は opt-in にする。
- secure input field では cloud provider を無効にする。
- cloud provider 利用時は context window を最小化する。
- 送信対象範囲を設定で制御できるようにする。
- API key はユーザー管理または自前 backend proxy 管理にする。
- Gemini など cloud provider ごとのデータ利用条件を説明する。
- macOS 配布では notarization と privacy 説明を整える。
- Play Store の Data Safety と privacy policy を正確に書く。

## MVP

macOS MVP では、ライブ変換 IME として成立する最小構成に絞る。

- macOS `InputMethodKit` host
- Swift または Objective-C から Kotlin/Native framework を呼ぶ bridge
- KMP shared core の input buffer
- conversion draft / segment model
- live conversion scheduler
- fake provider
- preedit のライブ更新
- `IMKCandidates` または最小 native UI による候補表示
- 単語または文節単位の候補選択
- segment lock
- secure input での cloud provider 無効化
- provider abstraction

最初の provider は fake provider にする。次に cloud Gemini provider または macOS local provider を試作し、IME としての状態管理が壊れないことを確認する。

MVP では以下を後回しにする。

- Android ソフトウェアキーボード UI
- 高度なユーザー辞書同期
- 複数 provider の自動切り替え
- 文体プリセット
- 課金
- 全アプリでの高度な入力欄別 policy

## ロードマップ

### Phase 1: macOS IME shell

- `InputMethodKit` host を作る。
- `IMKServer` と `IMKInputController` を用意する。
- 物理キーボードの key event を受け取る。
- composing buffer を shared core に渡す bridge を作る。
- fake provider を使って `ConversionDraft` を返す。
- 変換結果を preedit として更新する。
- `IMKCandidates` または最小 candidate UI を表示する。
- 確定操作で text input client に commit する。

### Phase 2: KMP live conversion core

- `InputBuffer`、`ConversionDraft`、`Segment` を実装する。
- debounce 付き live conversion scheduler を実装する。
- context window builder を実装する。
- raw romaji と変換済み text の alignment model を作る。
- candidate model を定義する。
- segment lock policy を実装する。
- privacy guard を shared core に入れる。

### Phase 3: 形態素分析と候補修正

- 形態素解析ライブラリを調査する。
- 変換結果を単語または文節単位に分割する。
- raw romaji と変換済み token の alignment を作る。
- 単語ごとの候補表示を実装する。
- 候補選択時に segment lock する。
- lock 済み segment を provider request に反映する。

### Phase 4: AI provider

- provider abstraction を実装する。
- Cloud Gemini API provider を試作する。
- macOS local provider を調査・試作する。
- Android on-device AI provider を調査する。
- provider ごとの latency / quality / privacy を比較する。
- structured response の形式を決める。

### Phase 5: macOS 公開準備

- notarization または Mac App Store 配布方針を決める。
- IME の install / enable 手順を整理する。
- secure input 時の provider 停止を検証する。
- 主要アプリで preedit / candidate / commit の挙動を検証する。
- privacy policy を作る。
- cloud provider の opt-in UX を作る。
- API key と Keychain の扱いを実装する。
- crash / analytics の扱いを決める。

### Phase 6: Android 展開

- Android `InputMethodService` を追加する。
- `InputConnection` bridge を作る。
- ローマ字入力用の keyboard UI を作る。
- candidate strip を表示する。
- Android on-device AI provider を試作する。
- Play Store Data Safety を整理する。
- release keystore と CI を整備する。

## 主要リスク

- ライブ変換のレイテンシが IME 体験を壊す。
- AI が入力途中の文を過剰に補完する。
- 形態素分析と AI 変換結果の alignment がずれる。
- ユーザーが修正した segment を AI が上書きしてしまう。
- `InputMethodKit` が古く、アプリごとの preedit / candidate 挙動に差が出る。
- Swift / Objective-C と Kotlin/Native framework の非同期 bridge が複雑になる。
- macOS の IME install、notarization、配布導線がユーザーにとって重い。
- cloud provider 利用時のプライバシー説明が重くなる。
- on-device model の端末要件、配布サイズ、速度が実用に届かない可能性がある。
- Android のソフトウェアキーボード UI と Play Store 審査対応が後続フェーズの大きなコストになる。

## 参考リンク

- F太さんの romaji 記法: https://note.com/fta7/n/nb8ccb733c425
- Gemini API を利用したローマ字変換例: https://note.com/kikyujin/n/n7db0887939f8
- Sumibi: https://github.com/kiyoka/Sumibi
- Android InputMethodService: https://developer.android.com/reference/android/inputmethodservice/InputMethodService
- Android IME 作成ガイド: https://developer.android.com/develop/ui/views/touch-and-input/creating-input-method
- Apple InputMethodKit: https://developer.apple.com/documentation/inputmethodkit
- Apple IMKServer: https://developer.apple.com/documentation/inputmethodkit/imkserver
- Apple IMKCandidates: https://developer.apple.com/documentation/inputmethodkit/imkcandidates
- Gemini API Terms: https://ai.google.dev/gemini-api/terms
