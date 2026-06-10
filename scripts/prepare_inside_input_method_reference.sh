#!/usr/bin/env bash
set -euo pipefail

source_pdf="${SOURCE_PDF:-docs/ref/inside-input-method-1.1.0.pdf}"
output_dir="${OUTPUT_DIR:-docs/ref/inside-input-method}"
render_dpi="${RENDER_DPI:-180}"

usage() {
  cat <<'USAGE'
Usage:
  scripts/prepare_inside_input_method_reference.sh [PAGE...]
  scripts/prepare_inside_input_method_reference.sh --all-pages

Environment:
  SOURCE_PDF   PDF path. Default: docs/ref/inside-input-method-1.1.0.pdf
  OUTPUT_DIR   Output directory. Default: docs/ref/inside-input-method
  RENDER_DPI   PNG render DPI. Default: 180

Examples:
  scripts/prepare_inside_input_method_reference.sh
  scripts/prepare_inside_input_method_reference.sh 11
  scripts/prepare_inside_input_method_reference.sh 11 25 55
USAGE
}

render_all_pages=false
requested_pages=()

for requested_argument in "$@"; do
  case "${requested_argument}" in
    --help | -h)
      usage
      exit 0
      ;;
    --all-pages)
      render_all_pages=true
      ;;
    *)
      requested_pages+=("${requested_argument}")
      ;;
  esac
done

missing_tools=()

for required_tool in pdfinfo pdffonts pdftotext pdfimages pdftoppm awk; do
  if ! command -v "${required_tool}" >/dev/null 2>&1; then
    missing_tools+=("${required_tool}")
  fi
done

if [[ "${#missing_tools[@]}" -gt 0 ]]; then
  printf 'Missing required tools: %s\n' "${missing_tools[*]}" >&2
  printf 'Install Poppler tools before preparing the reference files.\n' >&2
  exit 1
fi

if [[ ! -f "${source_pdf}" ]]; then
  printf 'Reference PDF was not found: %s\n' "${source_pdf}" >&2
  exit 1
fi

mkdir -p \
  "${output_dir}/meta" \
  "${output_dir}/notes" \
  "${output_dir}/pages" \
  "${output_dir}/text"

pdfinfo "${source_pdf}" >"${output_dir}/meta/pdfinfo.txt"
pdffonts "${source_pdf}" >"${output_dir}/meta/pdffonts.txt"
pdfimages -list "${source_pdf}" >"${output_dir}/meta/pdfimages.txt"
pdftotext -layout -enc UTF-8 "${source_pdf}" "${output_dir}/text/layout.txt"
pdftotext -enc UTF-8 "${source_pdf}" "${output_dir}/text/plain.txt"

page_count="$(awk '/^Pages:/ { print $2 }' "${output_dir}/meta/pdfinfo.txt")"

render_page() {
  local page_number="$1"
  local page_label

  if [[ ! "${page_number}" =~ ^[0-9]+$ ]]; then
    printf 'Page must be a positive number: %s\n' "${page_number}" >&2
    exit 1
  fi

  if [[ "${page_number}" -lt 1 || "${page_number}" -gt "${page_count}" ]]; then
    printf 'Page %s is outside the PDF range 1-%s.\n' "${page_number}" "${page_count}" >&2
    exit 1
  fi

  page_label="$(printf '%03d' "${page_number}")"
  pdftoppm \
    -png \
    -r "${render_dpi}" \
    -f "${page_number}" \
    -l "${page_number}" \
    -singlefile \
    "${source_pdf}" \
    "${output_dir}/pages/page-${page_label}"
}

if [[ "${render_all_pages}" == true ]]; then
  for page_number in $(seq 1 "${page_count}"); do
    render_page "${page_number}"
  done
else
  for requested_page in "${requested_pages[@]}"; do
    render_page "${requested_page}"
  done
fi

cat >"${output_dir}/README.md" <<README
# inside-input-method local reference

Generated from: ${source_pdf}

Use these files as local working artifacts only. Do not commit extracted text or rendered pages.

- meta/pdfinfo.txt: PDF metadata and page count.
- meta/pdffonts.txt: Font embedding and Unicode mapping status.
- meta/pdfimages.txt: Embedded image list.
- notes/reading-notes.txt: Manually maintained, non-verbatim page notes when available.
- text/layout.txt: Layout-preserving extracted text for search.
- text/plain.txt: Plain extracted text for search.
- pages/page-NNN.png: Rendered page images for code, API names, and diagrams.

Important:

- Extracted text is useful for search and context.
- Extracted Swift, XML, API names, and Info.plist keys can be corrupted.
- Treat notes/reading-notes.txt as the first AI-readable guide when it exists.
- Do not create or commit a verbatim full-text transcription of the paid PDF.
- Verify implementation details against rendered page images and official SDK documentation.
README

printf 'Prepared reference files in %s\n' "${output_dir}"
