#!/usr/bin/make -f

.PHONY: generate detekt

generate:
	xcodegen generate --spec macosApp/project.yml

detekt:
	./gradlew detekt --auto-correct --continue
