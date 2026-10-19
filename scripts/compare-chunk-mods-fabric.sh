#!/usr/bin/env bash
# Times one Chunky pregeneration on an installed Fabric server with a given set of chunk-generation mods:
# scripts/compare-chunk-mods.sh for Fabric (macOS and Linux as well as Windows).
#
# Every configuration runs on the same server installation, seed (0), settings and JVM heap, on a fresh
# world, with Fabric API, ScalableLux and Chunky installed and Chunky as the driver:
#   vanilla          nothing else
#   tellurium        this mod's built jar for that Minecraft version
#   c2me             Concurrent Chunk Management Engine
#   c2me-zfastnoise  C2ME plus zfastnoise (and the zconfig library it needs)
# The time is taken from the server log: Chunky's "Task started" line to its "Task finished" line.
#
# The server is build/installed-fabric-<version>: Fabric's server launcher jar, which fetches the Minecraft
# server and its libraries on first start.  The mods come from build/test-mods (fabric-api-<version>/, and
# fabric-<digits>/{vanilla,chunky,c2me,zfastnoise}/).
#
# Usage: scripts/compare-chunk-mods-fabric.sh <configuration> [radius in blocks] [extra JVM arguments]
#   e.g. scripts/compare-chunk-mods-fabric.sh c2me 1000 "-Dchunky.maxWorkingCount=1024"
#   FABRIC_MC=<Minecraft version> (default 26.1.2), JDK=<Java home>, HEAP=<-Xmx value> (default 6G).
set -u
cd "$(dirname "$0")/.."
config="${1:?configuration}"
radius="${2:-1000}"
jvm="${3:-}"
mc="${FABRIC_MC:-26.1.2}"
digits=$(echo "$mc" | tr -d .)
server="build/installed-fabric-$mc"
mods="build/test-mods/fabric-$digits"
heap="${HEAP:-6G}"
[ -n "${JDK:-}" ] && export PATH="$JDK/bin:$PATH"
[ -f "$server/fabric-server-launch.jar" ] || { echo "no Fabric server launcher in $server"; exit 2; }
PYTHON=$(command -v python || command -v python3)

stash=build/_to_delete/compare-worlds-fabric
mkdir -p "$stash" "$server/mods"
mv "$server"/mods/*.jar "$stash/" 2>/dev/null
cp "build/test-mods/fabric-api-$mc"/*.jar "$mods"/vanilla/*.jar "$mods"/chunky/*.jar "$server/mods/"
case "$config" in
  vanilla) ;;
  tellurium) cp "${TELLURIUM_JAR:-$(ls -t mod/targets/fabric-"$digits"/build/libs/tellurium-fabric-"$mc"-*.jar | grep -v sources | head -1)}" "$server/mods/" ;;
  c2me) cp "$mods"/c2me/*.jar "$server/mods/" ;;
  c2me-zfastnoise) cp "$mods"/c2me/*.jar "$mods"/zfastnoise/*.jar "$server/mods/" ;;
  *) echo "unknown configuration $config"; exit 2 ;;
esac

[ -d "$server/world" ] && mv "$server/world" "$stash/world-$config-$(date +%Y%m%d-%H%M%S)"
rm -f "$server/logs/latest.log"
printf 'eula=true\n' > "$server/eula.txt"
printf 'level-seed=0\nonline-mode=false\nserver-ip=127.0.0.1\nserver-port=25595\nspawn-protection=0\nmax-tick-time=-1\n' > "$server/server.properties"

cd "$server"
{
  for _ in $(seq 1 900); do sleep 1; grep -q "Done (" logs/latest.log 2>/dev/null && break; grep -qE "Failed to start the minecraft server|Exception in thread \"main\"" logs/latest.log console.out 2>/dev/null && { echo stop; exit; }; done
  if [ "$config" = tellurium ]; then
    for _ in $(seq 1 600); do sleep 1; grep -qE "the_end: .*(on the GPU|vanilla code)|GPU terrain generation is unavailable" logs/latest.log && break; done
  fi
  sleep 5
  echo "chunky radius $radius"
  echo "chunky start"
  for _ in $(seq 1 7200); do sleep 1; grep -q "Task finished" logs/latest.log && break; done
  sleep 2
  echo "stop"
  # shellcheck disable=SC2086
} | java "-Xmx$heap" $jvm -jar fabric-server-launch.jar nogui > console.out 2>&1
cd - >/dev/null

log="$server/logs/latest.log"
"$PYTHON" - "$log" "$config" "$radius" "$jvm" <<'PY'
import re, sys
from datetime import datetime
log, config, radius, jvm = sys.argv[1:5]
text = open(log, errors="replace").read()
def stamp(pattern):
    m = re.search(r"^\[(?:\d+\w+\d+ )?(\d\d:\d\d:\d\d(?:\.\d+)?)\][^\n]*" + pattern, text, re.M)
    if not m:
        return None
    return datetime.strptime(m.group(1), "%H:%M:%S.%f" if "." in m.group(1) else "%H:%M:%S")
start, end = stamp(r"\[Chunky\] Task started"), stamp(r"\[Chunky\] Task finished")
finished = re.search(r"Task finished[^\n]*Processed: (\d+) chunks[^\n]*Total time: ([\d:]+)", text)
errors = sum(1 for line in text.splitlines() if "/ERROR]" in line or "/FATAL]" in line)
if not (start and end and finished):
    print(f"{config}: did not finish (errors in log: {errors})")
    sys.exit(1)
seconds = (end - start).total_seconds()
if seconds < 0:
    seconds += 86400
chunks = int(finished.group(1))
print(f"{config} radius={radius} {jvm}: {chunks} chunks in {seconds:.1f} s = {chunks / seconds:.0f} chunks/s (Chunky's total {finished.group(2)}; error lines {errors})")
PY
status=$?
cp "$log" "build/_to_delete/compare-worlds-fabric/latest-$config.log" 2>/dev/null
mv "$server"/mods/*.jar "$stash/" 2>/dev/null
exit $status
