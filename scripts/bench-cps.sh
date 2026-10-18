#!/usr/bin/env bash
# Fresh-world wall-clock chunk throughput run: scripts/bench-cps.ps1 for hosts without PowerShell (macOS,
# Linux).  Same parameters, same run directory and report, same RUNDIR/REPORT lines, same verdict: each
# invocation uses a new run directory and world, so measured chunks are never previously generated, and the
# run counts only when the report says PASS and the game exited normally after writing it.
#
# Usage: scripts/bench-cps.sh [-Status NOISE] [-RadiusChunks 45] [-WarmupRadiusChunks 5] [-InFlight 1024]
#            [-Seed 0] [-Label vanilla] [-MaxHeap 16G] [-Jfr file] [-ModsDir dir] [-ReopenRunDir dir]
#            [-Properties 'a=1;b=2'] [-JavaHome dir]
#   MODULE=<target> picks the build (default neoforge-1211, as bench-cps.ps1).  BENCH_MAX_HEAP and
#   BENCH_IN_FLIGHT change the defaults of -MaxHeap and -InFlight (for hosts with less memory).
set -u
repo_root=$(cd "$(dirname "$0")/.." && pwd)
status=NOISE radius=45 warmup=5 in_flight="${BENCH_IN_FLIGHT:-1024}" seed=0 label=vanilla
max_heap="${BENCH_MAX_HEAP:-16G}" jfr='' mods_dir='' reopen='' properties='' java_home="${JAVA_HOME:-}"
while [ $# -gt 0 ]; do
    case "$1" in
        -Status) status="$2" ;;
        -RadiusChunks) radius="$2" ;;
        -WarmupRadiusChunks) warmup="$2" ;;
        -InFlight) in_flight="$2" ;;
        -Seed) seed="$2" ;;
        -Label) label="$2" ;;
        -MaxHeap) max_heap="$2" ;;
        -Jfr) jfr="$2" ;;
        -ModsDir) mods_dir="$2" ;;
        -ReopenRunDir) reopen="$2" ;;
        -Properties) properties="$2" ;;
        -JavaHome) java_home="$2" ;;
        *) echo "BENCH FAIL: unknown argument $1"; exit 2 ;;
    esac
    shift 2 || { echo "BENCH FAIL: $1 needs a value"; exit 2; }
done
case "$status" in BIOMES|NOISE|SURFACE|CARVERS|FEATURES|FULL) ;; *) echo "BENCH FAIL: -Status $status"; exit 2 ;; esac
status_lc=$(echo "$status" | tr '[:upper:]' '[:lower:]')
stamp=$(date -u +%Y%m%d-%H%M%S)
run_dir="$repo_root/build/run/bench-$label-$status_lc-$stamp"
report="$repo_root/build/bench/$label-$status_lc-$stamp.json"
if [ -n "$reopen" ]; then
    run_dir=$(cd "$repo_root" && cd "$reopen" && pwd) || { echo "BENCH FAIL: no run directory $reopen"; exit 1; }
elif [ -e "$run_dir" ]; then
    echo "BENCH FAIL: refusing to reuse $run_dir"; exit 1
fi
mkdir -p "$repo_root/build/bench"
if [ -n "$mods_dir" ]; then
    mkdir -p "$run_dir/mods"
    for jar in "$repo_root/$mods_dir"/*.jar; do [ -f "$jar" ] && cp "$jar" "$run_dir/mods/"; done
fi
[ -n "$java_home" ] && export JAVA_HOME="$java_home"
module="${MODULE:-neoforge-1211}"
args=(":${module}:runServer" --no-daemon --console=plain
    "-Dtellurium.candidate.runDir=$run_dir"
    "-Dtellurium.candidate.seed=$seed"
    "-Dtellurium.run.maxHeap=$max_heap"
    -Dtellurium.bench.autorun=true
    "-Dtellurium.bench.status=$status"
    "-Dtellurium.bench.radiusChunks=$radius"
    "-Dtellurium.bench.warmupRadiusChunks=$warmup"
    "-Dtellurium.bench.inFlight=$in_flight"
    "-Dtellurium.bench.output=$report")
[ -n "$reopen" ] && args+=(-Dtellurium.prototype.resume=true)
echo "RUNDIR $run_dir"
# The report this invocation is to write; a caller that compares runs takes its files from here, not from
# whatever is newest in the folder.
echo "REPORT $report"
[ -n "$jfr" ] && args+=("-Dtellurium.run.jfr=$jfr")
# (macOS's bash 3.2 counts an empty array as unset under set -u, hence the guard.)
if [ -n "$properties" ]; then
    IFS=';' read -r -a extra <<< "$properties"
    for p in "${extra[@]}"; do [ -n "$p" ] && args+=("-D$p"); done
fi
(cd "$repo_root" && bash ./gradlew "${args[@]}" 2>&1)
gradle_exit=$?
if [ ! -f "$report" ]; then
    echo "BENCH FAIL: no benchmark report written (Gradle exit $gradle_exit); see $run_dir/logs/latest.log"
    exit 1
fi
cat "$report"
echo
# A report alone is not a finished run: it has to say so, and the game has to have exited normally after it.
report_status=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8")).get("status"))' "$report")
if [ "$report_status" != "PASS" ]; then
    echo "BENCH FAIL: the report's status is '$report_status'"
    exit 1
fi
if [ "$gradle_exit" -ne 0 ]; then
    echo "BENCH FAIL: Gradle exited with $gradle_exit after the report was written; see $run_dir/logs/latest.log"
    exit 1
fi
