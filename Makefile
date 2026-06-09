#!/usr/bin/make -f

.PHONY: generate detekt install-inputmethod

DERIVED_DATA_DIR ?= /tmp/RomaFlowDerivedData
INPUT_METHOD_PATH ?=
INPUT_METHOD_PRODUCT_NAME ?= RomaFlow.app
LEGACY_INPUT_METHOD_PRODUCT_NAME ?= RomaFlow.inputmethod
INPUT_METHOD_INSTALL_DIR ?= $(HOME)/Library/Input Methods

generate:
	xcodegen generate --spec macosApp/project.yml

detekt:
	./gradlew detekt --auto-correct --continue

install-inputmethod:
	@set -eu; \
	source_path="$(INPUT_METHOD_PATH)"; \
	if [ -z "$$source_path" ]; then \
		for candidate_path in \
			"$(DERIVED_DATA_DIR)/Build/Products/Debug/$(INPUT_METHOD_PRODUCT_NAME)" \
			/tmp/RomaFlowDerivedData*/Build/Products/Debug/$(INPUT_METHOD_PRODUCT_NAME) \
			"$$HOME/Library/Caches/Google"/AndroidStudio*/DerivedData/RomaFlowMacOS-*/Build/Products/Debug/$(INPUT_METHOD_PRODUCT_NAME) \
			"$$HOME/Library/Developer/Xcode"/DerivedData/RomaFlowMacOS-*/Build/Products/Debug/$(INPUT_METHOD_PRODUCT_NAME); do \
			if [ -d "$$candidate_path" ]; then \
				source_path="$$candidate_path"; \
				break; \
			fi; \
		done; \
	fi; \
	if [ -z "$$source_path" ] || [ ! -d "$$source_path" ]; then \
		echo "$(INPUT_METHOD_PRODUCT_NAME) was not found. Build RomaFlowInputMethod first."; \
		echo "Set DERIVED_DATA_DIR or INPUT_METHOD_PATH if the product is in a custom location."; \
		exit 1; \
	fi; \
	install_dir="$(INPUT_METHOD_INSTALL_DIR)"; \
	mkdir -p "$$install_dir"; \
	rm -rf "$$install_dir/$(INPUT_METHOD_PRODUCT_NAME)"; \
	rm -rf "$$install_dir/$(LEGACY_INPUT_METHOD_PRODUCT_NAME)"; \
	cp -R "$$source_path" "$$install_dir/"; \
	killall TextInputMenuAgent 2>/dev/null || true; \
	echo "Installed $$source_path"; \
	echo "Reloaded TextInputMenuAgent"
