#!/usr/bin/env bash
#
# WanaKana の macosArm64 対応 fork を clone/更新し、Maven Local へ publish する。
#
# upstream (GreatTusk/wanakana-kmp) は macosArm64 target を宣言しておらず、
# :core:ime (macosArm64 のみ) から publish 済み artifact を解決できない。
# macosArm64 target 追加の PR (GreatTusk/wanakana-kmp#1) を出しており、
# それがマージ・リリースされるまでの暫定セットアップとして fork を Maven Local に置く。
#
# 注意:
# - Maven Central には署名済みの io.github.greattusk:wanakana-common:1.0.1 が存在し、
#   それには macosArm64 が含まれない。衝突を避けるため、publish 時だけ distinct な
#   SNAPSHOT 版数へ上書きする。SNAPSHOT は vanniktech が GPG 署名を要求しないため、
#   ローカルで鍵なしに publishToMavenLocal が通る。
# - version の上書きは clone の working tree に対してのみ行い、fork の PR ブランチ
#   (macosArm64() 追加のみ) は clean に保つ。

set -euo pipefail

REPO_URL="${WANAKANA_FORK_URL:-https://github.com/matsumo0922/wanakana-kmp.git}"
BRANCH="${WANAKANA_FORK_BRANCH:-feat/macos-arm64-target}"
VERSION="${WANAKANA_FORK_VERSION:-1.1.1-romaflow-SNAPSHOT}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
CLONE_DIR="${WANAKANA_FORK_DIR:-$(cd "$REPO_ROOT/.." && pwd)/wanakana-kmp}"

# REPO_URL から owner/repo slug を取り出す (https / ssh どちらの形式でも動く)。
EXPECTED_SLUG="$(printf '%s' "$REPO_URL" | sed -E 's#^.*[/:]([^/]+/[^/]+)$#\1#; s#\.git$##')"

if [ -d "$CLONE_DIR/.git" ]; then
  echo "==> reusing existing clone: $CLONE_DIR"

  # 別リポジトリを誤って操作しないよう remote を確認する。
  actual_remote="$(git -C "$CLONE_DIR" remote get-url origin 2>/dev/null || true)"
  case "$actual_remote" in
    *"$EXPECTED_SLUG"*) : ;;
    *)
      echo "error: $CLONE_DIR の origin ($actual_remote) が $EXPECTED_SLUG ではありません。中断します。" >&2
      exit 1
      ;;
  esac

  # 本スクリプトが publish 時に書き換える version override だけは戻す (再実行の冪等性のため)。
  git -C "$CLONE_DIR" checkout -- wanakana-core/build.gradle.kts 2>/dev/null || true

  # それ以外に未コミット変更があれば、利用者の作業を reset --hard で消さないよう中断する。
  if [ -n "$(git -C "$CLONE_DIR" status --porcelain)" ]; then
    echo "error: $CLONE_DIR に未コミットの変更があります。reset --hard で消さないよう中断します。" >&2
    git -C "$CLONE_DIR" status --short >&2
    exit 1
  fi

  git -C "$CLONE_DIR" fetch origin "$BRANCH"
  git -C "$CLONE_DIR" checkout "$BRANCH"
  git -C "$CLONE_DIR" reset --hard "origin/$BRANCH"
else
  echo "==> cloning $REPO_URL ($BRANCH) into $CLONE_DIR"
  git clone --branch "$BRANCH" "$REPO_URL" "$CLONE_DIR"
fi

echo "==> overriding version to $VERSION (clone working tree only)"
/usr/bin/sed -i '' -E "s/^version = \".*\"/version = \"$VERSION\"/" "$CLONE_DIR/wanakana-core/build.gradle.kts"

echo "==> publishing to Maven Local"
(
  cd "$CLONE_DIR"
  ./gradlew --no-daemon \
    :wanakana-core:publishKotlinMultiplatformPublicationToMavenLocal \
    :wanakana-core:publishMacosArm64PublicationToMavenLocal \
    :wanakana-core:publishAndroidReleasePublicationToMavenLocal \
    :wanakana-core:publishJvmPublicationToMavenLocal
)

echo "==> done: io.github.greattusk:wanakana-common:$VERSION published to ~/.m2"
