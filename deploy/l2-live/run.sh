#!/bin/sh
set -eu
workspace=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$workspace"
root="$workspace/var/l2-live"
action=${1:-status}
if [ "$#" -gt 0 ]; then shift; fi
if [ "$action" = stop ]; then
    mkdir -p "$root"
    printf 'stop\n' > "$root/stop.request"
    exit 0
fi
if [ "$action" = start ]; then action=session; fi
if [ ! -f "$root/java-path.txt" ] || [ ! -f "$root/runtime-classpath.txt" ]; then
    echo 'Run ./gradlew writeL2LiveRuntime once before using this launcher.' >&2
    exit 2
fi
java_path=$(cat "$root/java-path.txt")
classpath=$(cat "$root/runtime-classpath.txt")
exec "$java_path" -Xms128m -Xmx1g -cp "$classpath" \
    com.zoutrankil.data.l2live.bootstrap.AliyunL2LiveMain "$action" --root "$root" "$@"
