#!/usr/bin/env bash
#
# Builds or serves the documentation site, with the tools pinned in requirements-docs.txt, and the aggregated
# Javadoc of the modules at /api.
#
#   scripts/docs.sh                # serves the site on http://localhost:8000/penitent/, rebuilding on each change
#   scripts/docs.sh serve -a localhost:8001
#   scripts/docs.sh build          # builds into site/, failing on a broken link or anchor, as the CI does
#   scripts/docs.sh api            # regenerates the Javadoc into docs/api/, after a change to the code
#
# The first run creates a Python virtual environment in .venv/, ignored by git, and installs the tools there; a
# change to requirements-docs.txt reinstalls them. Nothing is installed outside the repository. The Javadoc needs
# Java 21, as the build does, but not Docker: it compiles the modules without running the tests.
# Run one server at a time: a second serve, or a build while one serves, clears site/ under the first one.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
venv="$root/.venv"
requirements="$root/requirements-docs.txt"
api="$root/docs/api"

if [[ ! -x "$venv/bin/zensical" ]] || ! cmp -s "$requirements" "$venv/requirements-docs.txt"; then
	echo "Installing the documentation tools into $venv"
	python3 -m venv "$venv"
	"$venv/bin/pip" install --quiet --disable-pip-version-check -r "$requirements"
	cp "$requirements" "$venv/requirements-docs.txt"
fi

# The Javadoc of every module, without the internal packages. The previous output goes first: javadoc:aggregate adds
# to it, so a class that moved to an internal package would stay published.
generate_api() {
	echo "Generating the Javadoc into docs/api/"
	rm -rf "$root/target/reports/apidocs"
	(cd "$root" && ./mvnw -q -B -DskipTests javadoc:aggregate)
	rm -rf "$api"
	mkdir -p "$api"
	cp -R "$root/target/reports/apidocs/." "$api/"
}

cd "$root"
command="${1:-serve}"
shift || true
case "$command" in
	api)
		generate_api
		;;
	serve)
		[[ -f "$api/index.html" ]] || generate_api
		exec "$venv/bin/zensical" serve "$@"
		;;
	build)
		generate_api
		"$venv/bin/zensical" build --strict --clean "$@"
		# the strict build checks the pages' links, but not the ones to the Javadoc, which is not Markdown
		if [[ ! -f site/api/index.html ]]; then
			echo "site/api/index.html is missing: the Javadoc did not reach the site" >&2
			exit 1
		fi
		;;
	*)
		echo "usage: scripts/docs.sh [serve|build|api] [options]" >&2
		exit 2
		;;
esac
