# RomaFlow エージェントガイド

このファイルには、README.md に書かれていない RomaFlow 固有の作業ルールだけを書く。プロジェクト概要、通常の検証コマンド、macOS bootstrap 手順は README.md を参照する。

## macOS IME 調査の最初の手順

macOS IME、InputMethodKit、入力ソース、Info.plist、TIS、候補ウィンドウ、marked text、キーイベント処理、IME install 周りを調査・実装する時は、コードを読む前に reference を読む。

最初に読む順番:

1. `docs/ref/inside-input-method-reading-guide.md`
2. `docs/ref/inside-input-method/notes/reading-notes.txt`
3. 必要な章・ページの `docs/ref/inside-input-method/pages/page-NNN.png`

判断ルール:

- `notes/reading-notes.txt` が存在する場合は、AI friendly な page-by-page guide として最初に読む。
- `text/layout.txt` と `text/plain.txt` は検索用としてのみ使う。
- Swift、XML、Info.plist key、InputMethodKit API、TIS API、図の解釈は rendered page image で確認する。
- 図、コード、スクリーンショットが重要な場合は `make reference-inside-input-method PAGE=<number>` で page image を render する。
- Apple 公式 document、SDK header、既存実装で照合できるものは、PDF の記述と突き合わせる。
- 有料 PDF の逐語的な全文転記は作らない。
- `docs/ref/inside-input-method-1.1.0.pdf` と `docs/ref/inside-input-method/` は commit しない。

## macOS IME 実装で特に見る点

- `macosApp/project.yml` が generated Info.plist の source of truth。`macosApp/Generated/*/Info.plist` は直接編集しない。
- IME bundle id には dotted segment として `inputmethod` を含める。macOS が入力メソッドとして認識するために必要。
- `RomaFlowInputMethod` は `InputMethodKit.framework` と `Carbon.framework` に依存する。
- Xcode pre-build script は `./gradlew --no-daemon --rerun-tasks :core:ime:embedSwiftExportForXcode` を実行する。
- `:core:ime` は arm64 のみ対応。`macosApp/project.yml` では CLI build 向けに `ARCHS` を `arm64` に固定している。
- generated Xcode project、`macosApp/Generated/`、`macosApp/build/`、local IME install artifact は commit しない。

## reference 更新時の注意

- `make reference-inside-input-method` は metadata、font 情報、画像一覧、抽出テキストを生成する。
- `make reference-inside-input-method PAGE=--all-pages` は全 page image を生成する。
- `notes/reading-notes.txt` は script の自動生成物ではない。Codex が page image を見て作る非逐語の読解ノートとして扱う。
- PDF を差し替えた場合は、`source_pages_total`、page block 数、`source_image` の存在、章対応を再確認する。

