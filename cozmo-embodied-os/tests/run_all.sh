#!/bin/bash
# Esecuzione COMPLETA del progetto cozmo-embodied-os.
#
# Un comando solo per giudicare lo stato del progetto:
#   1. le suite funzionali (modulo, supervisor, cliff e2e, cliff RUNTIME);
#   2. il banco mutazionale esistente (cozmo_os);
#   3. il banco mutazionale della SICUREZZA (cliff/safety).
#
# Perche' due banchi e non uno: il banco storico copre lift/navigazione/
# grounding/velocita', quello nuovo copre i tre difetti di SICUREZZA
# (veto-first, no-write, latch, no-report). Un solo banco non avrebbe
# denti su tutto.
#
# "Un guard senza trigger non e' una barriera": questo file e' il trigger
# unico. Ogni suite deve uscire 0; se una fallisce, si vede QUALE e
# perché (non solo un codice).

set -u

PROJ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJ" || exit 1

PASS=0
FAIL=0
FAILED_SUITES=""

note() { printf '%s\n' "$*"; }

run_suite() {
    local name="$1"; shift
    local out rc
    out=$(timeout 600 python3 "$@" 2>&1)
    rc=$?
    local last
    last=$(printf '%s\n' "$out" | grep -E '[0-9]+ ok, [0-9]+ falliti' | tail -1)
    if [ "$rc" -eq 0 ]; then
        note "  ok   $name ${last:+($last)}"
        PASS=$((PASS+1))
    else
        note "  FAIL $name rc=$rc ${last:+($last)}"
        printf '%s\n' "$out" | grep '^  FAIL ' | head -10
        FAIL=$((FAIL+1))
        FAILED_SUITES="$FAILED_SUITES $name"
    fi
}

run_bank() {
    local name="$1"; shift
    local rc
    mkdir -p /tmp/opencode
    "$@" > /tmp/opencode/cozmo-bank.log 2>&1
    rc=$?
    if [ "$rc" -eq 0 ]; then
        note "  ok   $name"
        PASS=$((PASS+1))
    else
        note "  FAIL $name rc=$rc"
        tail -12 /tmp/opencode/cozmo-bank.log
        FAIL=$((FAIL+1))
        FAILED_SUITES="$FAILED_SUITES $name"
    fi
}

note "== suite completa cozmo-embodied-os =="

run_suite "modulo"        tests/test_cozmo_os.py
run_suite "supervisor"    tests/test_cozmo_safety_affect.py
run_suite "cliff-e2e"     tests/test_cozmo_cliff_e2e.py
run_suite "cliff-runtime" tests/test_cozmo_cliff_runtime.py

note ""
note "== banchi mutazionali =="
run_bank "banco-modulo"  bash tests/mutation_check.sh
run_bank "banco-sicurezza" python3 tests/mutate_cliff_safety.py

note ""
if [ "$FAIL" -eq 0 ]; then
    note "TUTTO VERDE: $PASS/$((PASS+FAIL))"
    exit 0
fi
note "ROSSO: $FAIL suite/i fallite ($FAILED_SUITES) — $PASS/$((PASS+FAIL)) verdi"
exit 1
