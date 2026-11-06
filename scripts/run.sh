#!/usr/bin/env sh
set -eu

cd "$(dirname "$0")/.."
task="${1:-all}"
case "$task" in test|demo|fsck|all) ;; *) echo 'Usage: sh scripts/run.sh [test|demo|fsck|all]' >&2; exit 2 ;; esac
case "$task" in
    test) exec sh ./mvnw test ;;
    demo) exec sh ./mvnw compile exec:java ;;
    fsck) exec sh ./mvnw -Dexec.mainClass=io.github.inmemorygit.Cli -Dexec.args=fsck exec:java ;;
    all) sh ./mvnw test exec:java && exec sh ./mvnw -Dexec.mainClass=io.github.inmemorygit.Cli -Dexec.args=fsck exec:java ;;
esac
