"""Select a visible Android control by label, with correct quoting across adb shell."""
import argparse
import shlex
import subprocess

p = argparse.ArgumentParser()
p.add_argument('text')
p.add_argument('--serial', default='100.94.42.57:5555')
p.add_argument('--checked', choices=['true', 'false'])
p.add_argument('--probe', action='store_true')
a = p.parse_args()
model = subprocess.check_output(['adb', '-s', a.serial, 'shell', 'getprop', 'ro.product.model'], text=True).strip()
if model != 'S200':
    raise SystemExit('Wrong physical target: ' + model)
args = ['am', 'instrument', '-w', '-e', 'text', a.text]
if a.checked:
    args += ['-e', 'checked', a.checked]
if a.probe:
    args += ['-e', 'probe', 'true']
args += ['dev.s200.cozmo.checks/dev.s200.cozmo.checks.DeviceChecks']
r = subprocess.run(['adb', '-s', a.serial, 'shell', shlex.join(args)], capture_output=True, text=True, timeout=45)
print(r.stdout)
if r.stderr:
    print(r.stderr)
if r.returncode or 'INSTRUMENTATION_CODE: 0' not in r.stdout:
    raise SystemExit(1)
