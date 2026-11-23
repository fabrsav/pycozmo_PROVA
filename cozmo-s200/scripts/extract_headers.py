"""Extract unchanged JPEG tables and license from pinned PyCozmo source, without importing NumPy."""
import ast
import pathlib
import shutil
import subprocess
import hashlib

root = pathlib.Path(__file__).resolve().parents[1]
upstream = root / 'upstream/pycozmo'
assets = root / 'assets'
if not upstream.is_dir():
    for line in (assets / 'SHA256SUMS').read_text().splitlines():
        expected, filename = line.split(maxsplit=1)
        actual = hashlib.sha256((assets / filename).read_bytes()).hexdigest()
        if actual != expected:
            raise SystemExit('Asset hash mismatch: ' + filename)
    print('Bundled camera assets verified (offline build)')
    raise SystemExit(0)
pin = '821a0ed6d8b30efc7ea83c20e5874cdff969aa75'
actual = subprocess.check_output(['git', '-C', str(upstream), 'rev-parse', 'HEAD'], text=True).strip()
if actual != pin:
    raise SystemExit('Unexpected upstream revision: ' + actual)
tree = ast.parse((upstream / 'pycozmo/camera.py').read_text())
assets.mkdir(exist_ok=True)
for function, variable, filename in [('minigray_to_jpeg', 'header50', 'jpeg-gray.bin'), ('minicolor_to_jpeg', 'header', 'jpeg-color.bin')]:
    fn = next(n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name == function)
    assignment = next(n for n in fn.body if isinstance(n, ast.Assign) and any(isinstance(t, ast.Name) and t.id == variable for t in n.targets))
    values = ast.literal_eval(assignment.value.args[0])
    (assets / filename).write_bytes(bytes(values))
shutil.copyfile(upstream / 'LICENSE.md', assets / 'PYCOZMO-LICENSE.txt')
