#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/.." && pwd)"

xcodebuild_command="${XCODEBUILD:-xcodebuild}"
formatter_command="${XCBEAUTIFY:-xcbeautify}"

xcodeproj="${repo_root}/macosApp/RomaFlowMacOS.xcodeproj"
built_app="${repo_root}/macosApp/build/Debug/RomaFlow.app"
ime_install_dir="${HOME}/Library/Input Methods"
ime_app="${ime_install_dir}/RomaFlow.app"
lsregister="/System/Library/Frameworks/CoreServices.framework/Versions/A/Frameworks/LaunchServices.framework/Versions/A/Support/lsregister"

log_step() {
  printf '==> %s\n' "$1"
}

fail() {
  printf 'error: %s\n' "$1" >&2
  exit 1
}

run_quietly() {
  local description="$1"
  local command_log

  shift
  command_log="$(mktemp -t romaflow-command)"

  if "$@" >"${command_log}" 2>&1; then
    rm -f "${command_log}"
    return
  fi

  printf 'error: %s failed.\n' "${description}" >&2
  printf 'error: Raw log: %s\n' "${command_log}" >&2
  cat "${command_log}" >&2
  exit 1
}

require_command() {
  local command_name="$1"

  if ! command -v "${command_name}" >/dev/null 2>&1; then
    fail "${command_name} が見つかりません。README.md の macOS セットアップ手順に従ってインストールしてください。"
  fi
}

build_input_method() {
  local build_log

  build_log="$(mktemp -t romaflow-xcodebuild)"

  log_step "Building RomaFlowInputMethod"

  if ! "${xcodebuild_command}" \
    -project "${xcodeproj}" \
    -target RomaFlowInputMethod \
    -configuration Debug \
    build \
    2>&1 | tee "${build_log}" | "${formatter_command}" --disable-logging; then
    printf 'error: xcodebuild failed. Raw log: %s\n' "${build_log}" >&2
    exit 1
  fi

  rm -f "${build_log}"
}

install_input_method() {
  log_step "Stopping running RomaFlow process"
  pkill -x RomaFlow >/dev/null 2>&1 || true

  log_step "Installing RomaFlow.app"
  mkdir -p "${ime_install_dir}"
  rm -rf "${ime_app}"
  cp -R "${built_app}" "${ime_install_dir}/"

  log_step "Signing installed app"
  run_quietly "codesign" codesign --force --sign - --deep "${ime_app}"

  log_step "Registering input source"
  run_quietly "input source registration" "${ime_app}/Contents/MacOS/RomaFlow" --register-input-source

  log_step "Enabling input source"
  run_quietly "input source enabling" "${ime_app}/Contents/MacOS/RomaFlow" --enable-input-source

  # LaunchServices 登録がないと入力ソースの選択が永続化されず、メニューバーにも反映されない。
  log_step "Registering app with LaunchServices"
  run_quietly "LaunchServices registration" "${lsregister}" -f "${ime_app}"

  log_step "Refreshing input menu agent"
  killall TextInputMenuAgent >/dev/null 2>&1 || true
}

require_command "${xcodebuild_command}"
require_command "${formatter_command}"
require_command codesign
require_command tee

build_input_method
install_input_method

log_step "Installed ${ime_app}"
