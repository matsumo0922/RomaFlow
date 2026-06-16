# RomaFlow エージェントガイド

このファイルには RomaFlow 固有の作業ルールだけをまとめる。プロジェクト概要・通常の検証コマンド・macOS bootstrap 手順は README.md を参照すること。

---

## macOS IME を調査・実装するとき

InputMethodKit、入力ソース、Info.plist、TIS、候補ウィンドウ、marked text、キーイベント処理、IME install まわりを触るときは、コードを読む前に reference を読む。RomaFlow の IME 実装は『Inside Input Method』を下敷きにしているので、先に該当箇所を押さえておかないと意図を読み違える。

reference は次の順で開く。

1. `docs/ref/inside-input-method-reading-guide.md`
2. `docs/ref/inside-input-method/notes/reading-notes.txt`
3. 必要な章・ページの `docs/ref/inside-input-method/pages/page-NNN.png`

読み方には決まりがある。`notes/reading-notes.txt` があれば AI 向けの page-by-page guide として真っ先に読み、`text/layout.txt` と `text/plain.txt` は検索用としてのみ使う。Swift / XML / Info.plist key / InputMethodKit API / TIS API や図の解釈は、抽出テキストではなく rendered page image で確認すること。図・コード・スクリーンショットが必要になったら `make reference-inside-input-method PAGE=<number>` で page image を render する。Apple 公式 document・SDK header・既存実装と照合できるものは、PDF の記述と突き合わせて裏を取る。

なお、有料 PDF を逐語的に全文転記してはいけない。また `docs/ref/inside-input-method-1.1.0.pdf` と `docs/ref/inside-input-method/` は commit しない。

## macOS IME 実装で特に気をつける点

generated Info.plist の source of truth は `macosApp/project.yml`。生成物である `macosApp/Generated/*/Info.plist` を直接編集すると次の生成で消える。以下を押さえておくこと。

- IME bundle id には dotted segment として `inputmethod` を含める（macOS が入力メソッドとして認識するために必要）。
- `RomaFlowInputMethod` は `InputMethodKit.framework` と `Carbon.framework` に依存する。
- Xcode pre-build script は `./gradlew --no-daemon --rerun-tasks :core:ime:embedSwiftExportForXcode` を実行する。
- `:core:ime` は arm64 のみ対応。CLI build 向けに `macosApp/project.yml` で `ARCHS` を `arm64` に固定している。
- generated Xcode project、`macosApp/Generated/`、`macosApp/build/`、local IME install artifact は commit しない。

## reference を更新するときの注意

reference は `make reference-inside-input-method` で metadata・font 情報・画像一覧・抽出テキストを生成し、`PAGE=--all-pages` を渡せば全 page image を生成できる。ただし `notes/reading-notes.txt` は script の自動生成物ではなく、Codex が page image を見て作る非逐語の読解ノートなので、生成コマンドで上書きしないこと。

PDF を差し替えたときは、`source_pages_total`・page block 数・`source_image` の存在・章対応を再確認する。

---

## Worktree 運用

実装を行う場合は、必ず worktree を作成し、デフォルトディレクトリを汚さない。read-only の調査やビルド・テストの実行はこの限りでない。

```bash
git worktree add ../OneNavi-<task-slug> -b <branch-name>
cp -p local.properties ../OneNavi-<task-slug>/local.properties
cd ../OneNavi-<task-slug>
```

- `local.properties` は git 管理外なので、`git worktree add` ではコピーされない。Android SDK
  の場所、API キーなどを BuildKonfig や Gradle
  が参照するため、worktree 作成直後に必ず元 checkout からコピーする。
- `local.properties` の内容は tracked file、コミットメッセージ、PR 本文、issue コメントへ
  転記しない。worktree ごとに必要な差分がある場合も、各 worktree 内の `local.properties`
  だけを編集する。

---

## その他のプロジェクト規約

コード規約・アーキテクチャ・ビルド手順は `CLAUDE.md` と `~/.claude/CLAUDE.md`（利用者の
グローバル設定）に従う。主要ポイント:

- Kotlin: trailing comma 必須、`data class` には KDoc + `@Stable` / `@Immutable`
- Compose: `modifier: Modifier = Modifier` 必須、`Spacer` に dp 直指定禁止
- Material3 を使用（`androidx.compose.material` は不可）
- ビルド: `./gradlew assembleDebug --no-configuration-cache`
- Lint: `make detekt`
- コミット prefix: `feat:` / `fix:` / `refactor:` / `test:` / `docs:` / `chore:` / `ci:` / `build:`
- コミットメッセージは英語、PR title は英語、PR description は日本語