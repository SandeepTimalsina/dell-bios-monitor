#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
manifest="$project_dir/packaging/flatpak/manifest.yml"
output_dir="$project_dir/target/flatpak"
app_id="io.github.redmoon.dellbiosmonitor"

if ! command -v flatpak-builder >/dev/null 2>&1; then
  echo "flatpak-builder is required. Install Flatpak Builder and the Flathub remote, then rerun this script." >&2
  exit 1
fi

mvn -f "$project_dir/pom.xml" clean package -DskipTests
mkdir -p "$output_dir"
flatpak remote-add --user --if-not-exists flathub https://dl.flathub.org/repo/flathub.flatpakrepo
flatpak-builder --user --force-clean --install-deps-from=flathub \
  --default-branch=stable --repo="$output_dir/repo" "$output_dir/build" "$manifest"
flatpak build-bundle --runtime-repo=https://dl.flathub.org/repo/flathub.flatpakrepo \
  "$output_dir/repo" "$output_dir/dell-bios-monitor.flatpak" "$app_id" stable
echo "Created $output_dir/dell-bios-monitor.flatpak"
