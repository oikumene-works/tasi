#!/usr/bin/env bash
set -euo pipefail

mode=${1:-all}
ant_bin=${ANT_BIN:-ant}
xvfb_run_bin=${XVFB_RUN_BIN:-xvfb-run}

if [[ -z ${DATAEXPLORER_ROOT:-} ]]; then
    printf 'Set DATAEXPLORER_ROOT to the DataExplorer 4.0.7 source directory.\n' >&2
    exit 2
fi

schema="$DATAEXPLORER_ROOT/DataExplorer/src/resource/DeviceProperties_V50.xsd"
if [[ ! -f $schema ]]; then
    printf 'DataExplorer 4.0.7 schema not found: %s\n' "$schema" >&2
    exit 2
fi

ant_args=(-Ddataexplorer.root="$DATAEXPLORER_ROOT")
if [[ -n ${DATAEXPLORER_JAR:-} ]]; then
    if [[ ! -f $DATAEXPLORER_JAR ]]; then
        printf 'DataExplorer core JAR not found: %s\n' "$DATAEXPLORER_JAR" >&2
        exit 2
    fi
    ant_args+=(-Ddataexplorer.jar="$DATAEXPLORER_JAR")
elif [[ ! -f $DATAEXPLORER_ROOT/DataExplorer/build/DataExplorer.jar && ! -f build/core/DataExplorer.jar ]]; then
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
        "$xvfb_run_bin" -a "$ant_bin" "${ant_args[@]}" test
        ;;
    *)
        printf 'Usage: %s {package|protocol|all}\n' "$0" >&2
        exit 2
        ;;
esac
