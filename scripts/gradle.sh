#!/bin/bash
# backend の Gradle を、Java と Docker の場所を補ってから実行する。
#
# - asdf を遅延初期化している環境では gradlew が java を見つけられないため、JAVA_HOME を補う。
# - OrbStack など既定以外の Docker では Testcontainers がソケットを見つけられないため、
#   現在の docker context の接続先を DOCKER_HOST に渡す。
#
# 使い方: scripts/gradle.sh <gradle の引数...>
set -euo pipefail

main() {
	local root
	root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

	if [[ -z "${JAVA_HOME:-}" ]] && command -v asdf >/dev/null 2>&1; then
		local asdf_java
		if asdf_java="$(cd "$root" && asdf where java 2>/dev/null)" && [[ -d "$asdf_java" ]]; then
			export JAVA_HOME="$asdf_java"
		fi
	fi

	if [[ -z "${DOCKER_HOST:-}" ]] && command -v docker >/dev/null 2>&1; then
		local endpoint
		if endpoint="$(docker context inspect --format '{{.Endpoints.docker.Host}}' 2>/dev/null)" &&
			[[ -n "$endpoint" ]]; then
			export DOCKER_HOST="$endpoint"
		fi
	fi

	cd "$root/backend"
	exec ./gradlew "$@"
}

main "$@"
