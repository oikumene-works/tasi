#!/usr/bin/env bash
set -euo pipefail

mode=${1:-all}
ant_bin=${ANT_BIN:-ant}
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
project_root=$(cd -- "$script_dir/.." && pwd)
local_dataexplorer_root="$project_root/local/deps/dataexplorer-4.0.7"
local_xvfb_bin="$project_root/local/tools/xvfb/usr/bin"

dataexplorer_root=${DATAEXPLORER_ROOT:-$local_dataexplorer_root}

if [[ -n ${XVFB_RUN_BIN:-} ]]; then
    xvfb_run_bin=$XVFB_RUN_BIN
    xvfb_path=$PATH
elif [[ -x $local_xvfb_bin/xvfb-run ]]; then
    xvfb_run_bin="$local_xvfb_bin/xvfb-run"
    xvfb_path="$local_xvfb_bin:$PATH"
else
    xvfb_run_bin=xvfb-run
    xvfb_path=$PATH
fi

schema="$dataexplorer_root/DataExplorer/src/resource/DeviceProperties_V50.xsd"
if [[ ! -f $schema ]]; then
    printf 'DataExplorer 4.0.7 schema not found: %s\n' "$schema" >&2
    printf 'Populate local/deps or set DATAEXPLORER_ROOT.\n' >&2
    exit 2
fi

ant_args=(-Ddataexplorer.root="$dataexplorer_root")
if [[ -n ${DATAEXPLORER_JAR:-} ]]; then
    if [[ ! -f $DATAEXPLORER_JAR ]]; then
        printf 'DataExplorer core JAR not found: %s\n' "$DATAEXPLORER_JAR" >&2
        exit 2
    fi
    ant_args+=(-Ddataexplorer.jar="$DATAEXPLORER_JAR")
elif [[ ! -f $dataexplorer_root/DataExplorer/build/DataExplorer.jar && ! -f build/core/DataExplorer.jar ]]; then
    "$ant_bin" "${ant_args[@]}" prepare-core
fi

case "$mode" in
    package)
        "$ant_bin" "${ant_args[@]}" package
        ;;
    protocol)
        "$ant_bin" "${ant_args[@]}" package test-protocol
        ;;
    all)
        PATH="$xvfb_path" "$xvfb_run_bin" -a "$ant_bin" "${ant_args[@]}" test
        ;;
    *)
        printf 'Usage: %s {package|protocol|all}\n' "$0" >&2
        exit 2
        ;;
esac
