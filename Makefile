#!/usr/bin/make -f

.PHONY: generate detekt install-ime uninstall-ime

XCODEPROJ := macosApp/RomaFlowMacOS.xcodeproj
IME_INSTALL_DIR := $(HOME)/Library/Input Methods
IME_APP := $(IME_INSTALL_DIR)/RomaFlow.app

generate:
	xcodegen generate --spec macosApp/project.yml

detekt:
	./gradlew detekt --auto-correct --continue

# IME をビルドして ~/Library/Input Methods に配置し、再ログインなしで入力ソースに登録する
install-ime: generate
	xcodebuild -project $(XCODEPROJ) -target RomaFlowInputMethod -configuration Debug build
	-pkill -x RomaFlow
	mkdir -p "$(IME_INSTALL_DIR)"
	rm -rf "$(IME_APP)"
	cp -R macosApp/build/Debug/RomaFlow.app "$(IME_INSTALL_DIR)/"
	codesign --force --sign - --deep "$(IME_APP)"
	"$(IME_APP)/Contents/MacOS/RomaFlow" --register-input-source
	"$(IME_APP)/Contents/MacOS/RomaFlow" --enable-input-source

uninstall-ime:
	-pkill -x RomaFlow
	rm -rf "$(IME_APP)"
