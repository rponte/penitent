#!/usr/bin/env bash
#
# Builds or serves the documentation site, with the tools pinned in requirements-docs.txt.
#
#   scripts/docs.sh                # serves the site on http://localhost:8000/penitent/, rebuilding on each change
#   scripts/docs.sh serve -a localhost:8001
#   scripts/docs.sh build          # builds into site/, failing on a broken link or anchor, as the CI does
#
# The first run creates a Python virtual environment in .venv/, ignored by git, and installs the tools there; a
# change to requirements-docs.txt reinstalls them. Nothing is installed outside the repository.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
venv="$root/.venv"
requirements="$root/requirements-docs.txt"

if [[ ! -x "$venv/bin/zensical" ]] || ! cmp -s "$requirements" "$venv/requirements-docs.txt"; then
	echo "Installing the documentation tools into $venv"
	python3 -m venv "$venv"
	"$venv/bin/pip" install --quiet --disable-pip-version-check -r "$requirements"
	cp "$requirements" "$venv/requirements-docs.txt"
fi

cd "$root"
command="${1:-serve}"
shift || true
case "$command" in
	serve) exec "$venv/bin/zensical" serve "$@" ;;
	build) exec "$venv/bin/zensical" build --strict --clean "$@" ;;
	*) echo "usage: scripts/docs.sh [serve|build] [options]" >&2; exit 2 ;;
esac
