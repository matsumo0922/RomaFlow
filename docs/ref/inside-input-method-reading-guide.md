# inside-input-method 読解ガイド

## 目的

`inside-input-method-1.1.0.pdf` は RomaFlow の macOS IME 実装で参照する基礎資料として扱う。

この PDF は本文検索には使いやすいが、コード例や API 名のテキスト抽出が崩れる箇所がある。以後の開発では、抽出テキストだけを根拠にせず、ページ画像で視覚確認してから実装へ反映する。

## PDF の性質

- PDF は暗号化されておらず、ページ画像として全ページを表示できる。
- `pdftotext` による本文抽出はおおむね可能。
- 一部フォントに Unicode mapping がないため、Swift / XML / API 名の抽出結果は文字化けする。
- 図やスクリーンショットは画像として埋め込まれているため、テキスト抽出だけでは内容を読めない。
- PDF 本体と `docs/ref/inside-input-method/` は有料参考資料由来のローカル生成物として `.gitignore` 対象なので、本文の全文展開やページ画像は repository に commit しない。

## 標準手順

1. まずこのガイドの章対応表で、必要な章と論点を確認する。
2. ローカル作業用ファイルを `docs/ref/inside-input-method/` に生成する。
3. 抽出テキストは検索、章の把握、周辺文脈の確認に使う。
4. 実装へ反映する API 名、Info.plist key、Swift/XML コード例、図の内容は、必ずページ画像で見直す。
5. 抽出テキストからコードをそのままコピーしない。
6. Apple の公式ドキュメント、SDK header、既存実装で照合できるものは、PDF の記述と突き合わせる。
7. PR や設計メモに PDF 由来の判断を書く場合は、章番号またはページ番号を添える。

## ローカル抽出

初回または PDF 更新後に、metadata、font 情報、画像一覧、抽出テキストを生成する。

```sh
make reference-inside-input-method
```

特定ページを画像として確認したい場合は `PAGE` を指定する。

```sh
make reference-inside-input-method PAGE=11
```

複数ページを確認する場合は script を直接呼ぶ。

```sh
scripts/prepare_inside_input_method_reference.sh 11 25 55
```

生成先:

```text
docs/ref/inside-input-method/
  README.md
  meta/pdfinfo.txt
  meta/pdffonts.txt
  meta/pdfimages.txt
  text/layout.txt
  text/plain.txt
  pages/page-011.png
```

## AI に読ませる時のルール

AI に PDF の内容を参照させる場合は、次の扱いを前提にする。

```text
docs/ref/inside-input-method-1.1.0.pdf は macOS InputMethodKit 実装の参照資料。
抽出テキストは検索と概要把握だけに使う。
Swift、XML、Info.plist key、InputMethodKit API 名、図の解釈は、必ずレンダリング済みページ画像で確認する。
抽出テキスト上の英数字列は文字化けしている可能性があるため、そのまま実装へコピーしない。
```

## 章対応表

| 章 | 主題 | RomaFlow で見るタイミング |
|---|---|---|
| 第1章 | 入力メソッドの概要 | IME と通常アプリの責務差、入力メソッドが扱う情報の整理 |
| 第2章 | Input Method Kit による開発 | `IMKServer`、`IMKInputController`、Info.plist、sandbox、install flow |
| 第3章 | キー入力の処理 | `handle(_:client:)` 相当の key event 処理、client への text insert |
| 第4章 | 入力中状態の追加 | composing buffer、marked text、commit、状態遷移 |
| 第5章 | 変換候補ウインドウ | candidate 表示、候補選択状態、`IMKCandidates` の使い方 |
| 第6章 | 入力モードの追加 | input source / input mode、表示名、切り替え |
| 第7章 | 入力メニュー | 入力メニュー拡張、設定や操作導線 |
| 第8章 | 設定画面 | preference pane 相当の設定 UI、設定保存 |
| 第9章 | キーボード配列 | keyboard layout 取得、入力メソッド内での配列指定 |
| 第10章 | 既知の制約事項と回避方法 | `InputMethodKit` の不具合、アプリごとの差異、二重処理対策 |

## 実装前チェック

macOS IME 実装で PDF を参照した場合は、作業前に次を確認する。

- 参照した章とページは明確か。
- 抽出テキストではなくページ画像でコード例を確認したか。
- API 名と Info.plist key を Apple 公式資料または SDK header で照合したか。
- 第10章の制約事項に同じ問題が載っていないか。
- RomaFlow 側の責務が OS adapter、KMP core、UI、設定のどこに属するか分けたか。
