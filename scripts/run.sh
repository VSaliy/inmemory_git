#!/usr/bin/env sh
set -eu

cd "$(dirname "$0")/.."
task="${1:-all}"
case "$task" in test|demo|all) ;; *) echo 'Usage: sh scripts/run.sh [test|demo|all]' >&2; exit 2 ;; esac
case "$task" in
    test) exec sh ./mvnw test ;;
    demo) exec sh ./mvnw compile exec:java ;;
    all) exec sh ./mvnw test exec:java ;;
esac
