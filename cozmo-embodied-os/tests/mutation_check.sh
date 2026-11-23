#!/bin/bash
# Banco mutazionale: prova che i test di cozmo_os abbiano i DENTI.
#
# Metodo: copia isolata del progetto in /tmp/opencode, si rompe il SORGENTE
# nella copia e si eseguono i test. Ogni rottura deve far CADERE almeno un
# test per NOME. Il CONTROLLO (sorgente intatto) DEVE passare: un control
# rosso significa "sandbox non rappresentativa", non "guard rotto".
#
# Il vivo NON viene mai toccato.

set -u

PROJ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SB="/tmp/opencode/cozmo-mut"
PASS=0
FAIL=0

note() { printf '%s\n' "$*"; }

run_case() {
    local name="$1"; shift
    local expect="$1"; shift    # "control" | "mutazione"
    rm -rf "$SB"
    mkdir -p "$SB"
    cp -a "$PROJ/cozmo_os" "$SB/cozmo_os"
    cp -a "$PROJ/tests" "$SB/tests"

    # applica la rottura sulla COPIA (mai sul vivo)
    ( cd "$SB" && "$@" ) || { note "  [$name] la rottura non e' stata applicata"; FAIL=$((FAIL+1)); return; }

    local out rc
    out=$(cd "$SB" && timeout 120 python3 tests/test_cozmo_os.py 2>&1)
    rc=$?
    local failed
    failed=$(printf '%s\n' "$out" | grep -c '^  FAIL ')
    local names
    names=$(printf '%s\n' "$out" | grep '^  FAIL ' | sed 's/^  FAIL \([^ :]*\).*/\1/' | tr '\n' ',' | sed 's/,$//')

    if [ "$expect" = "control" ]; then
        if [ "$rc" -eq 0 ] && [ "$failed" -eq 0 ]; then
            note "  [$name] CONTROL ok (0 falliti)"
            PASS=$((PASS+1))
        else
            note "  [$name] CONTROL ROSSO (rc=$rc, falliti=$failed) -> sandbox non rappresentativa"
            note "$out" | tail -8
            FAIL=$((FAIL+1))
        fi
    else
        if [ "$rc" -ne 0 ] && [ "$failed" -gt 0 ]; then
            note "  [$name] MUTAZIONE MORSA (falliti: ${names:-?})"
            PASS=$((PASS+1))
        else
            note "  [$name] MUTAZIONE NON MORSA (rc=$rc falliti=$failed) -> il test NON bacia"
            FAIL=$((FAIL+1))
        fi
    fi
}

# ---- rotture applicate con python (ancora contata: se non c'e', non si applica)

mut_keyerror() {
python3 - <<'PY'
import pathlib
p = pathlib.Path("cozmo_os/run_sim.py")
s = p.read_text()
# reintroduce la lettura con [] che faceva KeyError sull'intent senza 'lift'
old = 'if intent.get("lift") is not None:'
assert s.count(old) == 1, f"ancora lift-intent: {s.count(old)}"
s = s.replace(old, 'if intent["lift"] is not None:')
p.write_text(s)
PY
}

mut_lift_up() {
python3 - <<'PY'
import pathlib
p = pathlib.Path("cozmo_os/brain/l1_reflex.py")
s = p.read_text()
old = 'lift = LIFT_HEIGHT_MIN if drive != 0 else self._lift_idle(robot_state)'
assert s.count(old) == 1, f"ancora lift: {s.count(old)}"
s = s.replace(old, 'lift = LIFT_HEIGHT_MAX if drive != 0 else self._lift_idle(robot_state)')
p.write_text(s)
PY
}

mut_lift_idle() {
python3 - <<'PY'
import pathlib
p = pathlib.Path("cozmo_os/brain/l1_reflex.py")
s = p.read_text()
old = '        return kin.clamp_lift(cur)'
assert s.count(old) == 1, f"ancora idle: {s.count(old)}"
s = s.replace(old, '        return kin.clamp_lift(LIFT_HEIGHT_MAX)')
p.write_text(s)
PY
}

mut_nav_open_loop() {
python3 - <<'PY'
import pathlib
p = pathlib.Path("cozmo_os/run_sim.py")
s = p.read_text()
old = '        if abs(bearing) > 0.25:\n            v, omega = 0.0, max(-1.6, min(1.6, bearing * 2.0))\n        else:\n            v = max(20.0, min(65.0, dist * 0.6))\n            omega = max(-1.2, min(1.2, bearing * 2.0))'
assert s.count(old) == 1, f"ancora nav: {s.count(old)}"
new = '        v = max(20.0, min(65.0, dist * 0.6))\n        omega = max(-1.2, min(1.2, bearing * 2.0))'
s = s.replace(old, new)
p.write_text(s)
PY
}

mut_nav_sign() {
python3 - <<'PY'
import pathlib
p = pathlib.Path("cozmo_os/run_sim.py")
s = p.read_text()
old = '        vl = max(-WHEEL_SPEED_HARD_MAX, min(WHEEL_SPEED_HARD_MAX, v - half))\n        vr = max(-WHEEL_SPEED_HARD_MAX, min(WHEEL_SPEED_HARD_MAX, v + half))'
assert s.count(old) == 1, f"ancora segno: {s.count(old)}"
new = '        vl = max(-WHEEL_SPEED_HARD_MAX, min(WHEEL_SPEED_HARD_MAX, v + half))\n        vr = max(-WHEEL_SPEED_HARD_MAX, min(WHEEL_SPEED_HARD_MAX, v - half))'
s = s.replace(old, new)
p.write_text(s)
PY
}

mut_nav_no_arrive() {
python3 - <<'PY'
import pathlib
p = pathlib.Path("cozmo_os/run_sim.py")
s = p.read_text()
old = '        if dist <= 35.0:                       # arrivato (soglia del braccio)\n            self._nav_goal = None\n            return 0.0, 0.0'
assert s.count(old) == 1, f"ancora arrivo: {s.count(old)}"
new = '        if dist <= 5.0:\n            self._nav_goal = None\n            return 0.0, 0.0'
s = s.replace(old, new)
p.write_text(s)
PY
}

mut_grounding_loose() {
python3 - <<'PY'
import pathlib
p = pathlib.Path("cozmo_os/brain/l3_cortex.py")
s = p.read_text()
# rende il grounding permissivo: accetta un oggetto NON osservato
old = '''                if kind in observation.lower():
                    return GroundingResult(True, kind, 0.9)
                return GroundingResult(False, None, 0.0,
                                       f"'{token}' citato ma non osservato")'''
assert s.count(old) == 1, f"ancora grounding: {s.count(old)}"
new = '''                return GroundingResult(True, kind, 0.9)'''
s = s.replace(old, new)
p.write_text(s)
PY
}

mut_speed_limit() {
python3 - <<'PY'
import pathlib
p = pathlib.Path("cozmo_os/kinematics.py")
s = p.read_text()
# rimuove il clamp cheVINCOLA davvero (il range fluido): e' quello che
# un bug di navigazione puo' davvero violare.
# NOTA: il clamp HARD (200) e' irraggiungibile dietro quello fluido
# (150): rimuoverlo e' una mutazione semanticamente NEUTRA (codice
# morto), quindi non e' una prova del test -> la prova del vincolo
# e' sul clamp EFFETTIVO.
old = '''    def _c(v: float) -> float:
        smooth = clamp(v, -WHEEL_SPEED_SMOOTH_MAX, WHEEL_SPEED_SMOOTH_MAX)
        return clamp(smooth, -WHEEL_SPEED_HARD_MAX, WHEEL_SPEED_HARD_MAX)'''
assert s.count(old) == 1, f"ancora clamp_wheels: {s.count(old)}"
new = '''    def _c(v: float) -> float:
        return clamp(v, -WHEEL_SPEED_HARD_MAX, WHEEL_SPEED_HARD_MAX)'''
s = s.replace(old, new)
p.write_text(s)
PY
}

note "== banco mutazionale cozmo_os =="

run_case "control"         control    true
run_case "keyerror-lift"   mutazione  mut_keyerror
run_case "lift-invertito"  mutazione  mut_lift_up
run_case "lift-idle-max"   mutazione  mut_lift_idle
run_case "nav-open-loop"   mutazione  mut_nav_open_loop
run_case "nav-segno"       mutazione  mut_nav_sign
run_case "nav-no-arrive"   mutazione  mut_nav_no_arrive
run_case "grounding-loose" mutazione  mut_grounding_loose
run_case "speed-limit"     mutazione  mut_speed_limit

note ""
note "mutazioni: $PASS ok, $FAIL falliti"
[ "$FAIL" -eq 0 ] || exit 1
exit 0
