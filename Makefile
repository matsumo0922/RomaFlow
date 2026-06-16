#!/usr/bin/make -f

.PHONY: generate detekt setup-wanakana reference-inside-input-method install-ime uninstall-ime

XCODEPROJ := macosApp/RomaFlowMacOS.xcodeproj
IME_INSTALL_DIR := $(HOME)/Library/Input Methods
IME_APP := $(IME_INSTALL_DIR)/RomaFlow.app

generate:
	@printf '==> Generating Xcode project\n'
	@xcodegen generate --spec macosApp/project.yml
	@printf '==> Generated %s\n' "$(XCODEPROJ)"

detekt:
	./gradlew detekt --auto-correct --continue

# WanaKana の macosArm64 対応 fork を兄弟ディレクトリに clone/更新し、Maven Local へ publish する。
# upstream PR (GreatTusk/wanakana-kmp#1) がマージ・リリースされるまでの暫定セットアップ。
setup-wanakana:
	scripts/build_wanakana_fork.sh

reference-inside-input-method:
	@if [ -n "$(PAGE)" ]; then \
		scripts/prepare_inside_input_method_reference.sh "$(PAGE)"; \
	else \
		scripts/prepare_inside_input_method_reference.sh; \
	fi

# IME をビルドして ~/Library/Input Methods に配置し、入力ソースとして登録する。
# 2回目以降の更新は再ログインなしで反映されるが、初回インストール時のみ再ログインが必要になることがある。
install-ime: generate
	@scripts/install_macos_ime.sh

uninstall-ime:
	@pkill -x RomaFlow >/dev/null 2>&1 || true
	@rm -rf "$(IME_APP)"
