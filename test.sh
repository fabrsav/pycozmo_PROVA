#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p build/test
python3 scripts/golden.py
javac --release 11 -encoding UTF-8 -d build/test src/dev/s200/cozmo/core/*.java tests/CoreTest.java tests/HandshakeRetryTest.java tests/GeminiLiveTest.java
java -cp build/test CoreTest
java -cp build/test HandshakeRetryTest
java -cp build/test GeminiLiveTest
python3 - <<'PY'
import importlib,sys,types,pathlib,struct
root=pathlib.Path.cwd()
pkg=types.ModuleType('pycozmo');pkg.__path__=[str(root/'upstream/pycozmo/pycozmo')];sys.modules['pycozmo']=pkg
decoder=importlib.import_module('pycozmo.image_encoder').ImageDecoder
b=(root/'build/fixtures/display-java.bin').read_bytes()
assert struct.unpack('<H',b[:2])[0]==len(b)-2
actual=decoder(b[2:]).decode()
expected=bytes(int(20<=x<45 and 5<=y<25) for y in range(32) for x in range(128))
assert actual==expected
print('PASS Java display decoded by original PyCozmo (4096 pixels)')
PY
