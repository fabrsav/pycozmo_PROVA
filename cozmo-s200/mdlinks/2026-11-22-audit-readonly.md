# Log completo — audit sola lettura (22 novembre 2026)

Directory: `/home/vpsuser/Projects/cozmo-s200`
Tipo turno: SOLA LETTURA. Nessun file modificato, nessuna build, nessuna installazione, nessun invio dati al robot.

## 1. Struttura del progetto e conteggio righe

```text
$ find . -maxdepth 4 -not -path './obj/*' -not -path './build/*' -not -path './.gradle/*' | sort
.
./.gitignore
./.private
./.private/debug.keystore
./AGENTS.md
./AndroidManifest.xml
./HANDOFF_ANTIGRAVITY.md
./HANDOFF_BATTERIA_2026-11-22.md
./HANDOFF_TELEMETRIA_MANCANTE.md
./HANDOFF_TELEMETRIA_RISOLTA.md
./README.md
./THIRD_PARTY.md
./VERIFICA.md
./assets
./assets/PYCOZMO-LICENSE.txt
./assets/SHA256SUMS
./assets/jpeg-color.bin
./assets/jpeg-gray.bin
./build
./build.sh
./dist
./dist/cozmo-s200-0.1.0.apk
./dist/cozmo-s200-0.1.0.apk.idsig
./dist/cozmo-s200-0.1.1.apk
./dist/cozmo-s200-0.1.1.apk.idsig
./dist/cozmo-s200-0.1.2.apk
./dist/cozmo-s200-0.1.2.apk.idsig
./dist/cozmo-s200-0.1.3.apk
./dist/cozmo-s200-0.1.3.apk.idsig
./dist/cozmo-s200-0.1.4.apk
./dist/cozmo-s200-0.1.4.apk.idsig
./dist/cozmo-s200-0.1.5.apk
./dist/cozmo-s200-0.1.5.apk.idsig
./dist/cozmo-s200-0.2.0.apk
./dist/cozmo-s200-0.2.0.apk.idsig
./dist/cozmo-s200-0.2.1-m0.apk
./dist/cozmo-s200-0.2.1-m0.apk.idsig
./dist/cozmo-s200-0.3.0.apk
./dist/cozmo-s200-0.3.0.apk.idsig
./dist/cozmo-s200-0.3.1.apk
./dist/cozmo-s200-0.3.1.apk.idsig
./dist/cozmo-s200-0.3.2.apk
./dist/cozmo-s200-0.3.2.apk.idsig
./dist/cozmo-s200-0.3.3.apk
./dist/cozmo-s200-0.3.3.apk.idsig
./dist/cozmo-s200-0.3.4.apk
./dist/cozmo-s200-0.3.4.apk.idsig
./dist/cozmo-s200-0.3.5.apk
./dist/cozmo-s200-0.3.5.apk.idsig
./dist/cozmo-s200-0.3.6.apk
./dist/cozmo-s200-0.3.6.apk.idsig
./dist/cozmo-s200-0.3.7.apk
./dist/cozmo-s200-0.3.7.apk.idsig
./dist/cozmo-s200-0.3.8.apk
./dist/cozmo-s200-0.3.8.apk.idsig
./dist/cozmo-s200-0.3.9.apk
./dist/cozmo-s200-0.3.9.apk.idsig
./dist/cozmo-s200-0.4.0.apk
./dist/cozmo-s200-0.4.0.apk.idsig
./dist/cozmo-s200-0.4.1.apk
./dist/cozmo-s200-0.4.1.apk.idsig
./dist/cozmo-s200-0.4.10-recovery.apk
./dist/cozmo-s200-0.4.10-recovery.apk.idsig
./dist/cozmo-s200-0.4.2-diag.apk
./dist/cozmo-s200-0.4.2-diag.apk.idsig
./dist/cozmo-s200-0.4.3-flow.apk
./dist/cozmo-s200-0.4.3-flow.apk.idsig
./dist/cozmo-s200-0.4.4-diag.apk
./dist/cozmo-s200-0.4.4-diag.apk.idsig
./dist/cozmo-s200-0.4.5-vol.apk
./dist/cozmo-s200-0.4.5-vol.apk.idsig
./dist/cozmo-s200-0.4.6-visual.apk
./dist/cozmo-s200-0.4.6-visual.apk.idsig
./dist/cozmo-s200-0.4.7-visual.apk
./dist/cozmo-s200-0.4.7-visual.apk.idsig
./dist/cozmo-s200-0.4.8-visual.apk
./dist/cozmo-s200-0.4.8-visual.apk.idsig
./dist/cozmo-s200-0.4.9-visual.apk
./dist/cozmo-s200-0.4.9-visual.apk.idsig
./res
./res/drawable
./res/drawable/icon.xml
./scripts
./scripts/__pycache__
./scripts/__pycache__/device_click.cpython-312.pyc
./scripts/__pycache__/extract_headers.cpython-312.pyc
./scripts/__pycache__/golden.cpython-312.pyc
./scripts/bootstrap_upstream.sh
./scripts/build_device_checks.sh
./scripts/device_click.py
./scripts/extract_headers.py
./scripts/golden.py
./scripts/install_s200.sh
./scripts/wait_connected.py
./src
./src/dev
./src/dev/s200
./src/dev/s200/cozmo
./test.sh
./tests
./tests/CoreTest.java
./tests/GeminiLiveTest.java
./tests/HandshakeRetryTest.java
./tests/device
./tests/device/AndroidManifest.xml
./tests/device/DeviceChecks.java
./upstream
./upstream/pycozmo
./upstream/pycozmo/.flake8
./upstream/pycozmo/.forgejo
./upstream/pycozmo/.forgejo/workflows
./upstream/pycozmo/.git
./upstream/pycozmo/.git/HEAD
./upstream/pycozmo/.git/branches
./upstream/pycozmo/.git/config
./upstream/pycozmo/.git/description
./upstream/pycozmo/.git/hooks
./upstream/pycozmo/.git/index
./upstream/pycozmo/.git/info
./upstream/pycozmo/.git/logs
./upstream/pycozmo/.git/objects
./upstream/pycozmo/.git/packed-refs
./upstream/pycozmo/.git/refs
./upstream/pycozmo/.git/shallow
./upstream/pycozmo/.github
./upstream/pycozmo/.github/FUNDING.yml
./upstream/pycozmo/.github/workflows
./upstream/pycozmo/.gitignore
./upstream/pycozmo/CHANGES.md
./upstream/pycozmo/CONTRIBUTING.md
./upstream/pycozmo/LICENSE.md
./upstream/pycozmo/NOTICE
./upstream/pycozmo/README.md
./upstream/pycozmo/assets
./upstream/pycozmo/assets/pycozmo.png
./upstream/pycozmo/docs
./upstream/pycozmo/docs/architecture.md
./upstream/pycozmo/docs/capturing.md
./upstream/pycozmo/docs/cubes_and_games.md
./upstream/pycozmo/docs/esp8266.md
./upstream/pycozmo/docs/functions.md
./upstream/pycozmo/docs/hardware_versions.md
./upstream/pycozmo/docs/offboard_functions.md
./upstream/pycozmo/docs/own_behavior.md
./upstream/pycozmo/docs/protocol.md
./upstream/pycozmo/docs/sound.md
./upstream/pycozmo/docs/source
./upstream/pycozmo/docs/versions.md
./upstream/pycozmo/docs/vision.md
./upstream/pycozmo/examples
./upstream/pycozmo/examples/anim.py
./upstream/pycozmo/examples/audio.py
./upstream/pycozmo/examples/backpack_lights.py
./upstream/pycozmo/examples/camera.py
./upstream/pycozmo/examples/charger_lights.py
./upstream/pycozmo/examples/client.py
./upstream/pycozmo/examples/cube_light_animation.py
./upstream/pycozmo/examples/cube_lights.py
./upstream/pycozmo/examples/display_image.py
./upstream/pycozmo/examples/display_lines.py
./upstream/pycozmo/examples/events.py
./upstream/pycozmo/examples/extremes.py
./upstream/pycozmo/examples/faces.py
./upstream/pycozmo/examples/go_to_pose.py
./upstream/pycozmo/examples/hello.wav
./upstream/pycozmo/examples/imu.py
./upstream/pycozmo/examples/keep_away.py
./upstream/pycozmo/examples/memory_match.py
./upstream/pycozmo/examples/minimal.py
./upstream/pycozmo/examples/nvram.py
./upstream/pycozmo/examples/path.py
./upstream/pycozmo/examples/procedural_face.py
./upstream/pycozmo/examples/procedural_face_expressions.py
./upstream/pycozmo/examples/procedural_face_show.py
./upstream/pycozmo/examples/quick_tap.py
./upstream/pycozmo/examples/rc.py
./upstream/pycozmo/examples/server.py
./upstream/pycozmo/examples/sing.py
./upstream/pycozmo/examples/video.py
./upstream/pycozmo/mypy.ini
./upstream/pycozmo/pycozmo
./upstream/pycozmo/pycozmo/CozmoAnim
./upstream/pycozmo/pycozmo/__init__.py
./upstream/pycozmo/pycozmo/__pycache__
./upstream/pycozmo/pycozmo/activity.py
./upstream/pycozmo/pycozmo/anim.py
./upstream/pycozmo/pycozmo/anim_controller.py
./upstream/pycozmo/pycozmo/anim_encoder.py
./upstream/pycozmo/pycozmo/audio.py
./upstream/pycozmo/pycozmo/audiokinetic
./upstream/pycozmo/pycozmo/audiolib.py
./upstream/pycozmo/pycozmo/behavior.py
./upstream/pycozmo/pycozmo/brain.py
./upstream/pycozmo/pycozmo/camera.py
./upstream/pycozmo/pycozmo/charger.py
./upstream/pycozmo/pycozmo/charger_behaviors.py
./upstream/pycozmo/pycozmo/charger_detection.py
./upstream/pycozmo/pycozmo/charger_handling.py
./upstream/pycozmo/pycozmo/client.py
./upstream/pycozmo/pycozmo/conn.py
./upstream/pycozmo/pycozmo/cube_behaviors.py
./upstream/pycozmo/pycozmo/cube_handling.py
./upstream/pycozmo/pycozmo/cube_lights.py
./upstream/pycozmo/pycozmo/cube_markers
./upstream/pycozmo/pycozmo/cubes.py
./upstream/pycozmo/pycozmo/emotions.py
./upstream/pycozmo/pycozmo/event.py
./upstream/pycozmo/pycozmo/exception.py
./upstream/pycozmo/pycozmo/expressions
./upstream/pycozmo/pycozmo/face_detection.py
./upstream/pycozmo/pycozmo/faces.py
./upstream/pycozmo/pycozmo/filter.py
./upstream/pycozmo/pycozmo/frame.py
./upstream/pycozmo/pycozmo/game_behaviors.py
./upstream/pycozmo/pycozmo/image_encoder.py
./upstream/pycozmo/pycozmo/json_loader.py
./upstream/pycozmo/pycozmo/keep_away.py
./upstream/pycozmo/pycozmo/lights.py
./upstream/pycozmo/pycozmo/logger.py
./upstream/pycozmo/pycozmo/marker_detection.py
./upstream/pycozmo/pycozmo/memory_match.py
./upstream/pycozmo/pycozmo/motion_detection.py
./upstream/pycozmo/pycozmo/needs.py
./upstream/pycozmo/pycozmo/object.py
./upstream/pycozmo/pycozmo/procedural_face.py
./upstream/pycozmo/pycozmo/protocol_ast.py
./upstream/pycozmo/pycozmo/protocol_base.py
./upstream/pycozmo/pycozmo/protocol_declaration.py
./upstream/pycozmo/pycozmo/protocol_encoder.py
./upstream/pycozmo/pycozmo/protocol_generator.py
./upstream/pycozmo/pycozmo/protocol_utils.py
./upstream/pycozmo/pycozmo/py.typed
./upstream/pycozmo/pycozmo/quick_tap.py
./upstream/pycozmo/pycozmo/robot.py
./upstream/pycozmo/pycozmo/robot_debug.py
./upstream/pycozmo/pycozmo/run.py
./upstream/pycozmo/pycozmo/song_behaviors.py
./upstream/pycozmo/pycozmo/songs.py
./upstream/pycozmo/pycozmo/tests
./upstream/pycozmo/pycozmo/unlocks.py
./upstream/pycozmo/pycozmo/util.py
./upstream/pycozmo/pycozmo/window.py
./upstream/pycozmo/pyproject.toml
./upstream/pycozmo/requirements-dev.txt
./upstream/pycozmo/requirements.txt
./upstream/pycozmo/setup.py
./upstream/pycozmo/tools
./upstream/pycozmo/tools/pycozmo_anim.py
./upstream/pycozmo/tools/pycozmo_app.py
./upstream/pycozmo/tools/pycozmo_convert_audio.py
./upstream/pycozmo/tools/pycozmo_dump.py
./upstream/pycozmo/tools/pycozmo_faces.py
./upstream/pycozmo/tools/pycozmo_protocol_generator.py
./upstream/pycozmo/tools/pycozmo_replay.py
./upstream/pycozmo/tools/pycozmo_resources.py
./upstream/pycozmo/tools/pycozmo_update.py
```

```text
$ find src -type f | sort | xargs wc -l
   133 src/dev/s200/cozmo/CameraFrames.java
   780 src/dev/s200/cozmo/MainActivity.java
   304 src/dev/s200/cozmo/MemoryDb.java
  1393 src/dev/s200/cozmo/RobotService.java
    54 src/dev/s200/cozmo/Secrets.java
   422 src/dev/s200/cozmo/core/AiSupervisor.java
   101 src/dev/s200/cozmo/core/AnimClip.java
   229 src/dev/s200/cozmo/core/AudioSynth.java
   226 src/dev/s200/cozmo/core/BehaviorScheduler.java
    25 src/dev/s200/cozmo/core/CameraAssembler.java
   291 src/dev/s200/cozmo/core/Choreographer.java
   324 src/dev/s200/cozmo/core/Cubes.java
   159 src/dev/s200/cozmo/core/EmotionGraph.java
   193 src/dev/s200/cozmo/core/FaceParams.java
   265 src/dev/s200/cozmo/core/FaceRenderer.java
   111 src/dev/s200/cozmo/core/FirmwareRecovery.java
   498 src/dev/s200/cozmo/core/GeminiLiveClient.java
   676 src/dev/s200/cozmo/core/Link.java
   130 src/dev/s200/cozmo/core/NeedsModel.java
   339 src/dev/s200/cozmo/core/Personality.java
    45 src/dev/s200/cozmo/core/Reliable.java
    32 src/dev/s200/cozmo/core/Safety.java
   173 src/dev/s200/cozmo/core/VisionAnalyzer.java
   437 src/dev/s200/cozmo/core/Wire.java
  7340 total
```

## 2. Firmware/binari e hash

```text
$ find . -maxdepth 4 \( -iname '*2381*' -o -iname '*firmware*' -o -iname '*recovery*' -o -iname '*.bin' -o -iname '*.img' -o -iname '*.fw' \) -exec ls -la {} +
-rw-r--r-- 1 vpsuser vpsuser    334 Nov 22 17:08 ./assets/jpeg-color.bin
-rw-r--r-- 1 vpsuser vpsuser    324 Nov 22 17:08 ./assets/jpeg-gray.bin
-rw-r--r-- 1 vpsuser vpsuser      0 Nov 16 20:42 ./build/camera-first-frame.bin
-rw-r--r-- 1 vpsuser vpsuser   1100 Nov 16 20:42 ./build/camera-first-packet.bin
-rw-rw-rw- 1 vpsuser vpsuser     33 Nov 22 17:07 ./build/fixtures/anim-state.bin
-rw-rw-rw- 1 vpsuser vpsuser    762 Nov 22 17:07 ./build/fixtures/audio.bin
-rw-rw-rw- 1 vpsuser vpsuser     49 Nov 22 17:07 ./build/fixtures/backpack-center.bin
-rw-r--r-- 1 vpsuser vpsuser     17 Nov 22 17:07 ./build/fixtures/connect.bin
-rw-rw-rw- 1 vpsuser vpsuser     23 Nov 22 17:07 ./build/fixtures/cube-id.bin
-rw-rw-rw- 1 vpsuser vpsuser     58 Nov 22 17:07 ./build/fixtures/cube-lights.bin
-rw-rw-rw- 1 vpsuser vpsuser     19 Nov 22 17:07 ./build/fixtures/discovery.bin
-rw-r--r-- 1 vpsuser vpsuser    180 Nov 22 17:07 ./build/fixtures/display-java.bin
-rw-r--r-- 1 vpsuser vpsuser     34 Nov 22 17:07 ./build/fixtures/drive.bin
-rw-r--r-- 1 vpsuser vpsuser     18 Nov 22 17:07 ./build/fixtures/enable.bin
-rw-r--r-- 1 vpsuser vpsuser     35 Nov 22 17:07 ./build/fixtures/head.bin
-rw-rw-rw- 1 vpsuser vpsuser     19 Nov 22 17:07 ./build/fixtures/headlight.bin
-rw-r--r-- 1 vpsuser vpsuser   1438 Nov 22 17:07 ./build/fixtures/large-image.bin
-rw------- 1 vpsuser vpsuser     30 Nov 22 17:07 ./build/fixtures/nv-read-calib.bin
-rw------- 1 vpsuser vpsuser     30 Nov 22 17:07 ./build/fixtures/nv-read-cubes.bin
-rw-rw-rw- 1 vpsuser vpsuser     27 Nov 22 17:07 ./build/fixtures/obj-available.bin
-rw-rw-rw- 1 vpsuser vpsuser     31 Nov 22 17:07 ./build/fixtures/obj-conn-state.bin
-rw-rw-rw- 1 vpsuser vpsuser     23 Nov 22 17:07 ./build/fixtures/obj-connect.bin
-rw-rw-rw- 1 vpsuser vpsuser     30 Nov 22 17:07 ./build/fixtures/obj-tapped.bin
-rw-r--r-- 1 vpsuser vpsuser     31 Nov 22 17:07 ./build/fixtures/ping.bin
-rw-r--r-- 1 vpsuser vpsuser     14 Nov 22 17:07 ./build/fixtures/reset.bin
-rw-r--r-- 1 vpsuser vpsuser    109 Nov 22 17:07 ./build/fixtures/robot.bin
-rw------- 1 vpsuser vpsuser     18 Nov 22 17:07 ./build/fixtures/shutdown.bin
-rw-rw-rw- 1 vpsuser vpsuser  65536 Nov 22 17:07 ./build/fixtures/ulaw-all-65536.bin
-rw-rw-rw- 1 vpsuser vpsuser     20 Nov 22 17:07 ./build/fixtures/volume.bin
-rw-r--r-- 1 vpsuser vpsuser    334 Nov 16 20:54 ./build/source-offline-check/assets/jpeg-color.bin
-rw-r--r-- 1 vpsuser vpsuser    324 Nov 16 20:54 ./build/source-offline-check/assets/jpeg-gray.bin
-rw-r--r-- 1 vpsuser vpsuser 131815 Nov 22 17:08 ./dist/cozmo-s200-0.4.10-recovery.apk
-rw-r--r-- 1 vpsuser vpsuser   5498 Nov 22 17:08 ./dist/cozmo-s200-0.4.10-recovery.apk.idsig
```

```text
$ find . -maxdepth 4 \( -iname '*2381*' -o -iname '*firmware*' -o -iname '*recovery*' -o -iname '*.bin' -o -iname '*.img' -o -iname '*.fw' \) ! -iname '*.apk' ! -iname '*.class' -exec sha256sum {} +
e8e79ee273e84309e4303cc084210fdc15d93f37ae9bbaee2ca164eb9083d177  ./dist/cozmo-s200-0.4.10-recovery.apk.idsig
c44b69c9f614304aae9b3e9a3f4367f934b9b93fb6921ae481bd11f6542f2df0  ./build/source-offline-check/assets/jpeg-gray.bin
106a14ba4dd23964bc401bbc4a0f396ff30d6e4db1c4a61b354e56c73c0aee9f  ./build/source-offline-check/assets/jpeg-color.bin
d12bd21297a6fd0815d2a9e4c9e943d5570bacefd7a8ca33a6a5daa1492a62af  ./build/camera-first-packet.bin
e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855  ./build/camera-first-frame.bin
9bae11cc2db9c44e56a0f76c4c9380395b91a035e5a5956180b4665a8f2750e2  ./build/fixtures/ping.bin
2e9d54cf1bfbf8dc5974c2409d3d9b4f442c2f488eaaf49e4ba5b326026fcb79  ./build/fixtures/anim-state.bin
099ab14d86dd92b27ae32d6d08c2cb4930465a11570a22d402e6f93277c0779e  ./build/fixtures/volume.bin
3eb5bfcba735604807e0b6816b081f919ecfabecfd2f16605308c25086a63ca3  ./build/fixtures/audio.bin
b062b53446ac92a2ce70f4003ff0e9979e551bd2f75b362bfdf043c411f7a641  ./build/fixtures/shutdown.bin
a9ff9c24f1d774a05c6b1001f2ab06635076cad4956f56c800e8a813f6d236b3  ./build/fixtures/discovery.bin
ab43e8ba825693183a61c544f0c121607cff039f1999729c0b455395d0b904ef  ./build/fixtures/obj-tapped.bin
e027a84f8f7c15b741276c5712cb7c6fe9e3706fbbd637241eebbe41843e6c2a  ./build/fixtures/obj-connect.bin
5391c634241a852209333528a92bcf629d45b02e3ddbeba6813014527b93af77  ./build/fixtures/obj-conn-state.bin
aa7bef92880a70f8022d38c368b13786fe2572256e6efb883acefb1fc1f7f1de  ./build/fixtures/display-java.bin
ded017942e6ecde30483b46964983a739e17fa80f47e073827148cbac35ec3a5  ./build/fixtures/nv-read-cubes.bin
85dbd1c707f2992cb88ae31a299d9f4aa45945a290202df7c592df3cd99bd61d  ./build/fixtures/backpack-center.bin
16cfaf22d0a0bc0b98afa06bb8d8be271fa77c74a29a16b856f51d1d5d3b9cf2  ./build/fixtures/robot.bin
e3e4540325a77988c18747185156d2c0c18bf67e85501062b6ef340eee26d22f  ./build/fixtures/drive.bin
b7c84859872b54acbf344e4871f21cecb2a36c255434026a0461031ac56dae45  ./build/fixtures/head.bin
86dde06023256f399705c7846ff04813cf246ec890f0f8b3ba13e2d42d440fc1  ./build/fixtures/connect.bin
9c7fd523e3b42934c114eb9c4f41673db42985e4ed888db8336e440b3d571957  ./build/fixtures/nv-read-calib.bin
a0cf555fff403b5dc17b6e530be232a9215fb99023c3aa4c70f36487f39209f2  ./build/fixtures/enable.bin
86a1372cd4071877c0974da33d9b043ce4fbbeee1d7876ae1524926437c76de2  ./build/fixtures/ulaw-all-65536.bin
e179699d995af1c7dbca2e36325606e80b30cfbfe2d682197b05c9445d68ec42  ./build/fixtures/obj-available.bin
9edb903b5ee4ca3634e570066d6d5e79cf0b6ac46135861e145b81f61c354a67  ./build/fixtures/cube-lights.bin
7801ab03a30cd929857e0056a156ddb37530f038d5d5faaafcdb7c72f47dad68  ./build/fixtures/reset.bin
ac1b505522fac65cc0a235789d57566ebdae4aef203208c1383c3947b81e6307  ./build/fixtures/large-image.bin
e7c777648aae07eb022eec20cb4ed07512cc1680da8444efa5963f962bbb9f38  ./build/fixtures/cube-id.bin
83a5d8c91391c27676f317fd5fdcd4c45e2f810158109ca2023f51c95aa26144  ./build/fixtures/headlight.bin
c44b69c9f614304aae9b3e9a3f4367f934b9b93fb6921ae481bd11f6542f2df0  ./assets/jpeg-gray.bin
106a14ba4dd23964bc401bbc4a0f396ff30d6e4db1c4a61b354e56c73c0aee9f  ./assets/jpeg-color.bin
```

## 3. build.sh e manifest

```text
$ cat build.sh
#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
sdkRoot="${ANDROID_SDK_ROOT:-/home/vpsuser/Android/Sdk}"
androidJar="$sdkRoot/platforms/android-34/android.jar"
r8Jar="/home/vpsuser/android-sdk/cmdline-tools/latest/r8.jar"
aaptTool="${AAPT2:-/usr/lib/android-sdk/build-tools/debian/aapt2}"
appVersion=$(python3 -c 'import xml.etree.ElementTree as E; print(E.parse("AndroidManifest.xml").getroot().get("{http://schemas.android.com/apk/res/android}versionName"))')
test -f "$androidJar"
test -f "$r8Jar"
test -x "$aaptTool"
rm -rf build/gen build/classes build/dex
mkdir -p build/gen build/classes build/dex dist .private assets res/drawable
python3 scripts/extract_headers.py
"$aaptTool" compile --dir res -o build/resources.zip
"$aaptTool" link -I "$androidJar" --manifest AndroidManifest.xml --java build/gen -A assets -o build/base.apk build/resources.zip
find src build/gen -name '*.java' -print > build/sources.txt
javac --release 11 -encoding UTF-8 -classpath "$androidJar" -d build/classes @build/sources.txt
find build/classes -name '*.class' -print > build/classes.txt
java -cp "$r8Jar" com.android.tools.r8.D8 --release --lib "$androidJar" --min-api 29 --output build/dex @build/classes.txt
cp build/base.apk build/unsigned.apk
(cd build/dex && jar uf ../unsigned.apk classes.dex)
zipalign -f 4 build/unsigned.apk build/aligned.apk
if [ ! -f .private/debug.keystore ]; then
  keytool -genkeypair -keystore .private/debug.keystore -storepass android -keypass android -alias androiddebugkey -dname 'CN=Cozmo S200 Debug' -keyalg RSA -keysize 2048 -validity 10000 -noprompt
fi
apkFile="dist/cozmo-s200-$appVersion.apk"
cp build/aligned.apk "$apkFile"
apksigner sign --ks .private/debug.keystore --ks-pass pass:android --key-pass pass:android "$apkFile"
apksigner verify --verbose "$apkFile"
sha256sum "$apkFile"
```

```text
$ find . -maxdepth 3 -name 'AndroidManifest.xml' -exec cat {} +
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.s200.cozmo" android:versionCode="29" android:versionName="0.4.10-recovery">
    <uses-sdk android:minSdkVersion="29" android:targetSdkVersion="35"/>
    <uses-permission android:name="android.permission.INTERNET"/>
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>
    <uses-permission android:name="android.permission.CHANGE_NETWORK_STATE"/>
    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE"/>
    <uses-permission android:name="android.permission.CHANGE_WIFI_STATE"/>
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/>
    <uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES" android:usesPermissionFlags="neverForLocation"/>
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE"/>
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
    <uses-permission android:name="android.permission.WAKE_LOCK"/>
    <application android:label="Cozmo S200" android:theme="@android:style/Theme.Material.NoActionBar" android:allowBackup="false" android:debuggable="true" android:supportsRtl="true" android:icon="@drawable/icon">
        <activity android:name=".MainActivity" android:exported="true" android:launchMode="singleTop" android:windowSoftInputMode="adjustResize">
            <intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter>
        </activity>
        <service android:name=".RobotService" android:exported="true" android:foregroundServiceType="connectedDevice" android:stopWithTask="false"/>
    </application>
</manifest>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.s200.cozmo.checks">
    <uses-sdk android:minSdkVersion="29" android:targetSdkVersion="34"/>
    <application android:label="Cozmo Device Checks" android:debuggable="true" android:testOnly="true"/>
    <instrumentation android:name="dev.s200.cozmo.checks.DeviceChecks" android:targetPackage="dev.s200.cozmo.checks" android:functionalTest="true"/>
</manifest>
```

## 4. Ricerca recovery/firmware e codice

```text
$ grep -rn -iE "recovery|firmware|2381|flash|ota|boot|update" src/
src/dev/s200/cozmo/core/Safety.java:9:    public void update(Wire.Telemetry value,long now) {
src/dev/s200/cozmo/core/FaceParams.java:5: * Separate left and right EyeParams with corner radius, rotation, and curved upper/lower eyelids.
src/dev/s200/cozmo/core/CameraAssembler.java:17:        int total=data[14]&255,chunk=data[15]&255,length=b.getShort(18)&65535;
src/dev/s200/cozmo/core/CameraAssembler.java:18:        if(length>data.length-20||(total>0&&chunk>=total)){reset();throw new IllegalArgumentException("Invalid camera chunk length/count");}
src/dev/s200/cozmo/core/CameraAssembler.java:22:        if(total>0&&chunk==total-1){Image image=new Image(partial.toByteArray(),encoding,resolution);reset();return image;}
src/dev/s200/cozmo/core/Wire.java:218:    public static Packet cubeId(int objectId, int rotationPeriodFrames) {
src/dev/s200/cozmo/core/Wire.java:219:        return command(0x10, le(5).putInt(objectId).put((byte) rotationPeriodFrames).array());
src/dev/s200/cozmo/core/Wire.java:238:                // Firmware 2381 sends a compact 65-byte RobotState. The fields used by
src/dev/s200/cozmo/core/EmotionGraph.java:71:        // For incremental tick updates (small dt), convert piecewise slope into smooth step retention
src/dev/s200/cozmo/core/AudioSynth.java:192:        int totalSamples = 0;
src/dev/s200/cozmo/core/AudioSynth.java:195:            totalSamples += Math.max(1, Math.round(s.durationSec * RATE));
src/dev/s200/cozmo/core/AudioSynth.java:197:        totalSamples = Math.min(totalSamples, maxSamples);
src/dev/s200/cozmo/core/AudioSynth.java:198:        short[] out = new short[totalSamples];
src/dev/s200/cozmo/core/AudioSynth.java:202:            if (pos >= totalSamples) break;
src/dev/s200/cozmo/core/AudioSynth.java:206:            for (int i = 0; i < n && pos < totalSamples; i++, pos++) {
src/dev/s200/cozmo/core/Cubes.java:192:        int rotation = 0;
src/dev/s200/cozmo/core/Cubes.java:205:                rotation = 12;
src/dev/s200/cozmo/core/Cubes.java:219:        pkts.add(Wire.cubeId(objectId, rotation));
src/dev/s200/cozmo/core/Personality.java:124:    private long lastUpdateMs = -1;
src/dev/s200/cozmo/core/Personality.java:222:    public synchronized String update(long nowMs, Wire.Telemetry t, boolean faceSeen, boolean cubeActive) {
src/dev/s200/cozmo/core/Personality.java:224:        if (lastUpdateMs < 0) lastUpdateMs = nowMs;
src/dev/s200/cozmo/core/Personality.java:225:        float dtSec = Math.max(0f, Math.min(10f, (nowMs - lastUpdateMs) / 1000.0f));
src/dev/s200/cozmo/core/Personality.java:226:        lastUpdateMs = nowMs;
src/dev/s200/cozmo/core/Link.java:43:    private boolean initialized=false,firmwareEnabled=false,enableSent=false,robotInitialized=false,syncRetrySent=false,signatureSeen=false,animationStateEnabled=false;
src/dev/s200/cozmo/core/Link.java:60:    private volatile boolean factoryFirmware;
src/dev/s200/cozmo/core/Link.java:61:    private volatile FirmwareRecovery firmwareRecovery;
src/dev/s200/cozmo/core/Link.java:77:    public boolean isFactoryFirmware() {return factoryFirmware;}
src/dev/s200/cozmo/core/Link.java:78:    public boolean isRecoveringFirmware() {
src/dev/s200/cozmo/core/Link.java:79:        FirmwareRecovery recovery = firmwareRecovery;
src/dev/s200/cozmo/core/Link.java:80:        return recovery != null && recovery.active();
src/dev/s200/cozmo/core/Link.java:82:    public void recoverFirmware2381(byte[] image) {
src/dev/s200/cozmo/core/Link.java:83:        FirmwareRecovery recovery = new FirmwareRecovery(image);
src/dev/s200/cozmo/core/Link.java:86:            if (!visualOnly || !factoryFirmware || signatureSeen || !initialized
src/dev/s200/cozmo/core/Link.java:89:                    || reliable.pendingCount() != 0 || !queue.isEmpty() || firmwareRecovery != null) {
src/dev/s200/cozmo/core/Link.java:90:                observer.event("firmware_recovery", "Avvio sospeso · servono modalità di fabbrica, basetta e collegamento stabile");
src/dev/s200/cozmo/core/Link.java:94:            firmwareRecovery = recovery;
src/dev/s200/cozmo/core/Link.java:95:            observer.event("firmware_recovery", "Avvio ripristino 2381 sul robot in modalità di fabbrica");
src/dev/s200/cozmo/core/Link.java:120:            if(!running||firmwareRecovery!=null||requests.size()>=48) {observer.event("rejected","Collegamento assente o coda piena");return;}
src/dev/s200/cozmo/core/Link.java:176:                // This firmware rejected OutputSilence during the visual-only probe.
src/dev/s200/cozmo/core/Link.java:346:        enableSent=false;robotInitialized=false;syncRetrySent=false;firmwareEnabled=false;initialized=false;signatureSeen=false;binaryVersionSeen=false;animationStateEnabled=false;
src/dev/s200/cozmo/core/Link.java:363:                    sendEnable(warmTriggerAt>0?"warm_trigger":"fallback_timer","FirmwareSignature assente dopo 700 ms: invio Enable (0x25)");
src/dev/s200/cozmo/core/Link.java:375:                if(emergency.getAndSet(false)&&handshake&&firmwareRecovery==null)doStop(true);
src/dev/s200/cozmo/core/Link.java:376:                if(firmwareRecovery==null&&safety.mustStop(n))doStop(false);
src/dev/s200/cozmo/core/Link.java:377:                if(handshake&&!shutdownSent&&firmwareRecovery==null&&(shutdownRequested.getAndSet(false)||(autoShutdownLowBattery&&safety.mustShutdownLowBattery(n)))){
src/dev/s200/cozmo/core/Link.java:401:                FirmwareRecovery recovery = firmwareRecovery;
src/dev/s200/cozmo/core/Link.java:402:                if (recovery != null) {
src/dev/s200/cozmo/core/Link.java:403:                    if (!recovery.active()) {
src/dev/s200/cozmo/core/Link.java:404:                        // ACK the final reliable report while the robot reboots.
src/dev/s200/cozmo/core/Link.java:410:                    boolean wasCommitting = recovery.committing();
src/dev/s200/cozmo/core/Link.java:411:                    recovery.advance(n, reliable.pendingCount() == 0 && queue.isEmpty(), this::enqueue);
src/dev/s200/cozmo/core/Link.java:412:                    if (!wasCommitting && recovery.committing()) {
src/dev/s200/cozmo/core/Link.java:413:                        observer.event("firmware_recovery", "Ultimo blocco consegnato · richiesta di verifica e riavvio");
src/dev/s200/cozmo/core/Link.java:415:                    if (!recovery.active()) {
src/dev/s200/cozmo/core/Link.java:416:                        observer.event("firmware_recovery", recovery.outcome());
src/dev/s200/cozmo/core/Link.java:448:                if(handshake&&n-lastRx>(isRecoveringFirmware()?30_000:5000)) {status("Robot non più raggiungibile");break;}
src/dev/s200/cozmo/core/Link.java:493:            if(handshake&&!shutdownSent&&firmwareRecovery==null&&now()-lastRx<1000) {
src/dev/s200/cozmo/core/Link.java:495:                // Nothing follows DISCONNECT: the robot may reboot on subsequent packets.
src/dev/s200/cozmo/core/Link.java:503:        enableSent=true;firmwareEnabled=true;enableSentAt=now();
src/dev/s200/cozmo/core/Link.java:522:        // Keep the hardware-verified 0.1.4 boot order. NVStorage reads are not
src/dev/s200/cozmo/core/Link.java:523:        // part of initialization: on this firmware they can precede RobotState
src/dev/s200/cozmo/core/Link.java:525:        // The 2381 application firmware uses the PyCozmo layout. The factory
src/dev/s200/cozmo/core/Link.java:526:        // firmware reported different lengths for these three boot messages.
src/dev/s200/cozmo/core/Link.java:542:            if(handshakeAt==0){handshakeAt=now();initState=InitState.CONNECTED_AWAITING_INIT;status("Handshake riuscito, attendo firmware");}
src/dev/s200/cozmo/core/Link.java:553:                        observer.event("firmware", new String(p.data, 4, length, StandardCharsets.UTF_8));
src/dev/s200/cozmo/core/Link.java:556:                        observer.event("firmware_format", "0xee non JSON · " + p.data.length + " byte");
src/dev/s200/cozmo/core/Link.java:567:        } else if(p.id==0xef && firmwareRecovery != null) {
src/dev/s200/cozmo/core/Link.java:568:            FirmwareRecovery recovery = firmwareRecovery;
src/dev/s200/cozmo/core/Link.java:569:            if (recovery.receive(p.data)) observer.event("firmware_recovery", recovery.outcome() != null
src/dev/s200/cozmo/core/Link.java:570:                ? recovery.outcome() : "Blocchi confermati: " + recovery.confirmed());
src/dev/s200/cozmo/core/Link.java:572:            if (p.data.length == 44 && !factoryFirmware) {
src/dev/s200/cozmo/core/Link.java:573:                factoryFirmware = true;
src/dev/s200/cozmo/core/Link.java:574:                observer.event("firmware_mode", "Cozmo ha segnalato il firmware di fabbrica");
src/dev/s200/cozmo/core/Link.java:580:                    if(sig.contains("{"))observer.event("firmware",sig);
src/dev/s200/cozmo/core/Link.java:592:                // live RobotState. Firmware 2381 rejects 0x9f during boot
src/dev/s200/cozmo/core/Link.java:601:                // RobotState proves that this firmware is already initialized.
src/dev/s200/cozmo/core/Link.java:607:                observer.event("init_existing_state","RobotState ricevuto prima di BodyInfo: riuso inizializzazione firmware attiva");
src/dev/s200/cozmo/core/Link.java:610:                Wire.Telemetry t=Wire.Telemetry.parse(p.data);lastState=now();latestState=t;safety.update(t,lastState);states++;
src/dev/s200/cozmo/core/FaceRenderer.java:88:        float blinkOpen = updateBlink(nowMs, toExpr);
src/dev/s200/cozmo/core/FaceRenderer.java:89:        float[] sac = updateSaccade(nowMs, toExpr, activeProfile);
src/dev/s200/cozmo/core/FaceRenderer.java:101:    private float updateBlink(long nowMs, Personality.Expression expr) {
src/dev/s200/cozmo/core/FaceRenderer.java:137:    private float[] updateSaccade(long nowMs, Personality.Profile profile) {
src/dev/s200/cozmo/core/FaceRenderer.java:177:        // Level 1 degradation: flatten eyelid curvature and rotation
src/dev/s200/cozmo/core/NeedsModel.java:5: * cross-need multipliers, fullness cooldowns, and courteous offline pause recovery.
src/dev/s200/cozmo/core/NeedsModel.java:22:    public static final long OFFLINE_RECOVERY_PAUSE_MS = 2L * 3600L * 1000L; // 2 hours
src/dev/s200/cozmo/core/NeedsModel.java:53:        boolean longPause = savedAtMs <= 0L || (nowMs - savedAtMs) >= OFFLINE_RECOVERY_PAUSE_MS;
src/dev/s200/cozmo/core/FirmwareRecovery.java:8:/** Factory recovery using PyCozmo's FirmwareUpdate / FirmwareUpdateResult messages. */
src/dev/s200/cozmo/core/FirmwareRecovery.java:9:final class FirmwareRecovery {
src/dev/s200/cozmo/core/FirmwareRecovery.java:25:    FirmwareRecovery(byte[] image) {
src/dev/s200/cozmo/core/FirmwareRecovery.java:27:            throw new IllegalArgumentException("Immagine firmware diversa dalla build 2381 verificata");
src/dev/s200/cozmo/core/FirmwareRecovery.java:63:            if (elapsed >= 30_000) outcome = "Il bootloader non ha confermato il primo blocco";
src/dev/s200/cozmo/core/FirmwareRecovery.java:68:        // transport to drain and the flash report to trail by at most one block.
src/dev/s200/cozmo/core/FirmwareRecovery.java:99:            outcome = "Firmware ricevuto · bootloader ha confermato il completamento";
src/dev/s200/cozmo/core/FirmwareRecovery.java:101:            outcome = "Bootloader ha rifiutato il blocco " + index + " · codice " + status;
src/dev/s200/cozmo/GeminiLiveClient.java:295:                else if (firstLine.contains("429")) lastErrorCode = "HTTP_429_QUOTA";
src/dev/s200/cozmo/GeminiLiveClient.java:491:        int readTotal = 0;
src/dev/s200/cozmo/GeminiLiveClient.java:492:        while (readTotal < len) {
src/dev/s200/cozmo/GeminiLiveClient.java:493:            int n = in.read(dst, off + readTotal, len - readTotal);
src/dev/s200/cozmo/GeminiLiveClient.java:495:            readTotal += n;
src/dev/s200/cozmo/RobotService.java:22:    public volatile String status = "Non connesso", firmware = "—", notice = "";
src/dev/s200/cozmo/RobotService.java:87:                    String tr = personality.update(now, t, faceRecent, cubeAct);
src/dev/s200/cozmo/RobotService.java:232:                savedNeeds.updatedMs, System.currentTimeMillis());
src/dev/s200/cozmo/RobotService.java:281:        if (link != null && link.isRecoveringFirmware()) return START_NOT_STICKY;
src/dev/s200/cozmo/RobotService.java:397:            case "RECOVER_FIRMWARE_2381": {
src/dev/s200/cozmo/RobotService.java:399:                if (active != null && active.isVisualOnly() && active.isFactoryFirmware()) {
src/dev/s200/cozmo/RobotService.java:402:                        File capability = new File(getFilesDir(), "recover-2381.token");
src/dev/s200/cozmo/RobotService.java:406:                            event("firmware_recovery", "Avvio sospeso · autorizzazione locale non presente");
src/dev/s200/cozmo/RobotService.java:409:                        File image = new File(getFilesDir(), "firmware-2381.safe");
src/dev/s200/cozmo/RobotService.java:412:                        active.recoverFirmware2381(java.nio.file.Files.readAllBytes(image.toPath()));
src/dev/s200/cozmo/RobotService.java:414:                        event("firmware_recovery", "Immagine non utilizzabile · " + e.getMessage());
src/dev/s200/cozmo/RobotService.java:417:                    event("firmware_recovery", "Avvio sospeso · modalità di fabbrica non rilevata");
src/dev/s200/cozmo/RobotService.java:861:            event("ai_key", "Chiave API vuota ignorata");
src/dev/s200/cozmo/RobotService.java:1117:        else if (kind.equals("firmware")) {
src/dev/s200/cozmo/RobotService.java:1122:                firmware = ver > 0 ? (ver + (bld.isEmpty() ? "" : " (" + bld + ")")) : safeText;
src/dev/s200/cozmo/RobotService.java:1124:                firmware = "Versione non riconosciuta";
src/dev/s200/cozmo/RobotService.java:1255:            root.put("firmware", firmware);
src/dev/s200/cozmo/RobotService.java:1265:            root.put("factory_firmware", l != null && l.isFactoryFirmware());
src/dev/s200/cozmo/RobotService.java:1266:            root.put("firmware_recovering", l != null && l.isRecoveringFirmware());
src/dev/s200/cozmo/MainActivity.java:35:    private final Runnable update = new Runnable() {
src/dev/s200/cozmo/MainActivity.java:602:                "Testa %.1f° · Carrello %.1f mm\nCingoli %.1f / %.1f mm/s\nSensori di caduta: %s\nSollevato: %s · Caduta: %s\nBasetta: %s · Ricarica: %s\nFirmware: %s · Audio TX/HW: %d/%d",
src/dev/s200/cozmo/MainActivity.java:606:                s.firmware, l != null ? l.audioFramesSent() : 0, l != null ? l.audioFramesPlayed() : 0));
src/dev/s200/cozmo/MainActivity.java:641:        handler.post(update);
src/dev/s200/cozmo/MainActivity.java:646:        handler.removeCallbacks(update);
src/dev/s200/cozmo/MemoryDb.java:51:        public final long updatedMs;
src/dev/s200/cozmo/MemoryDb.java:53:        public SavedNeeds(float energy, float play, float comfort, long updatedMs) {
src/dev/s200/cozmo/MemoryDb.java:57:            this.updatedMs = updatedMs;
src/dev/s200/cozmo/MemoryDb.java:89:            "updated_ms INTEGER NOT NULL)");
src/dev/s200/cozmo/MemoryDb.java:145:                db.update("persons", cv, "id = ?", new String[]{String.valueOf(id)});
src/dev/s200/cozmo/MemoryDb.java:188:                db.update("persons", cv, "id = ?", new String[]{String.valueOf(id)});
src/dev/s200/cozmo/MemoryDb.java:255:        cv.put("updated_ms", System.currentTimeMillis());
src/dev/s200/cozmo/MemoryDb.java:261:        try (Cursor c = db.query("needs_state", new String[]{"energy", "play", "comfort", "updated_ms"}, "id = 1", null, null, null, null)) {
```

```text
$ cat src/dev/s200/cozmo/core/FirmwareRecovery.java
package dev.s200.cozmo.core;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.function.Consumer;

/** Factory recovery using PyCozmo's FirmwareUpdate / FirmwareUpdateResult messages. */
final class FirmwareRecovery {
    static final int IMAGE_SIZE = 382416;
    private static final String IMAGE_SHA256 =
        "a4e266cfaaa95e1895fb48729603a732c24f4808a19e14677792137d63a449c0";
    private static final int CHUNK_SIZE = 1024;
    private static final int CHUNKS = (IMAGE_SIZE + CHUNK_SIZE - 1) / CHUNK_SIZE;
    private static final int COMMIT = 0xffff;

    private final byte[] image;
    private int nextChunk;
    private int confirmed = -1;
    private int lastReported = -1;
    private long lastSend;
    private boolean committing;
    private volatile String outcome;

    FirmwareRecovery(byte[] image) {
        if (image.length != IMAGE_SIZE || !IMAGE_SHA256.equals(sha256(image))) {
            throw new IllegalArgumentException("Immagine firmware diversa dalla build 2381 verificata");
        }
        this.image = image.clone();
    }

    private static String sha256(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest) hex.append(Character.forDigit((value & 255) >>> 4, 16))
                .append(Character.forDigit(value & 15, 16));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    boolean active() { return outcome == null; }
    String outcome() { return outcome; }
    boolean committing() { return committing; }
    int confirmed() { return confirmed + 1; }

    void advance(long now, boolean transportDrained, Consumer<Wire.Packet> send) {
        if (!active()) return;
        if (nextChunk == 0) {
            send.accept(dataPacket(nextChunk++));
            lastSend = now;
            return;
        }

        long elapsed = now - lastSend;
        if (committing) {
            if (elapsed >= 30_000) outcome = "Conferma finale assente dopo l'invio di chiusura";
            return;
        }
        if (nextChunk == 1 && confirmed < 0) {
            if (elapsed >= 30_000) outcome = "Il bootloader non ha confermato il primo blocco";
            return;
        }

        // Some Cozmo builds report only every second block. Require the reliable
        // transport to drain and the flash report to trail by at most one block.
        boolean pairConfirmed = confirmed >= nextChunk - 1;
        boolean pairedReport = confirmed >= nextChunk - 2 && elapsed >= 650;
        if (!transportDrained || (!pairConfirmed && !pairedReport)) {
            if (elapsed >= 9_000) outcome = "Conferma del blocco " + (nextChunk - 1) + " assente";
            return;
        }
        if (nextChunk == CHUNKS) {
            send.accept(Wire.command(0xaf, Wire.le(2 + CHUNK_SIZE).putShort((short) COMMIT).array()));
            committing = true;
        } else {
            int limit = Math.min(CHUNKS, nextChunk + 2);
            while (nextChunk < limit) send.accept(dataPacket(nextChunk++));
        }
        lastSend = now;
    }

    private Wire.Packet dataPacket(int index) {
        int offset = index * CHUNK_SIZE;
        ByteBuffer data = Wire.le(2 + CHUNK_SIZE).putShort((short) index);
        data.put(image, offset, Math.min(CHUNK_SIZE, image.length - offset));
        return Wire.command(0xaf, data.array());
    }

    boolean receive(byte[] payload) {
        if (!active() || payload.length != 7) return false;
        ByteBuffer data = Wire.read(payload);
        data.getInt(); // Byte count can include the zero padding of the last block.
        int index = data.getShort() & 65535;
        int status = data.get() & 255;
        if (status == 10 && committing) {
            outcome = "Firmware ricevuto · bootloader ha confermato il completamento";
        } else if (status != 0) {
            outcome = "Bootloader ha rifiutato il blocco " + index + " · codice " + status;
        } else if (index < nextChunk && index >= confirmed) {
            confirmed = index;
        }
        if (outcome != null || (confirmed >= 0 && confirmed / 32 != lastReported)) {
            lastReported = confirmed / 32;
            return true;
        }
        return false;
    }
}
```

```text
$ sed -n '530,625p' src/dev/s200/cozmo/core/Link.java
            enqueue(Wire.command(0x4b,new byte[8]));
        } else {
            enqueue(Wire.command(0x45,Wire.le(20).putInt(0).putInt(0).putInt(1).putFloat(0).putFloat(0).array()));
            enqueue(Wire.command(0x60,Wire.le(12).putInt(1).putInt(0).putInt(0).array()));
            enqueue(Wire.command(0x4b,Wire.le(12).putInt(0).putInt(0).putInt(0).array()));
        }
        if (!visualOnly) enqueue(Wire.setVolume(initialVolume));
        status("Inizializzato, attendo telemetria");
    }
    private void handle(Wire.Packet p) {
        if(p.type==2) {
            handshake=true;
            if(handshakeAt==0){handshakeAt=now();initState=InitState.CONNECTED_AWAITING_INIT;status("Handshake riuscito, attendo firmware");}
        }
        else if(p.type==3) {status("Disconnessione richiesta dal robot");stopping.set(true);return;}
        else if(p.id==0xee) {
            if (!signatureSeen && p.data.length >= 4) {
                int length = Wire.read(p.data).getShort(2) & 65535;
                boolean hasString = length > 0 && length <= p.data.length - 4;
                boolean json = hasString && p.data[4] == '{';
                if (json || !binaryVersionSeen) {
                    if (json) {
                        signatureSeen = true;
                        observer.event("firmware", new String(p.data, 4, length, StandardCharsets.UTF_8));
                    } else {
                        binaryVersionSeen = true;
                        observer.event("firmware_format", "0xee non JSON · " + p.data.length + " byte");
                    }
                    if (!enableSent) {
                        sendEnable(hasString ? "normal" : "warm_trigger", null);
                    } else if (json || !initialized) {
                        enqueue(Wire.command(0x25));
                        enqueue(Wire.command(0x25));
                        observer.event("init_signature_enable", "0xee dopo Enable iniziale");
                    }
                }
            }
        } else if(p.id==0xef && firmwareRecovery != null) {
            FirmwareRecovery recovery = firmwareRecovery;
            if (recovery.receive(p.data)) observer.event("firmware_recovery", recovery.outcome() != null
                ? recovery.outcome() : "Blocchi confermati: " + recovery.confirmed());
        } else if(p.id==0xd2) {
            if (p.data.length == 44 && !factoryFirmware) {
                factoryFirmware = true;
                observer.event("firmware_mode", "Cozmo ha segnalato il firmware di fabbrica");
            }
            if(p.data.length>=4) {
                int length=Wire.read(p.data).getShort(2)&65535;
                if(length>2&&length<=p.data.length-4) {
                    String sig=new String(p.data,4,length,StandardCharsets.UTF_8);
                    if(sig.contains("{"))observer.event("firmware",sig);
                }
            }
            if(handshake&&!enableSent&&warmTriggerAt==0)warmTriggerAt=now();
        } else if(p.id==0xc9&&handshake&&!enableSent) {
            if(warmTriggerAt==0)warmTriggerAt=now();
        } else if(p.id==0xed&&!robotInitialized) {
            observer.event("body_info","BodyInfo 0xed ricevuto");
            initializeRobot("none".equals(initPath)?"normal":initPath,null);
        } else if(p.id==0xf0) {
            if(!animationStateEnabled) {
                // PyCozmo enables the animation controller only after Enable and
                // live RobotState. Firmware 2381 rejects 0x9f during boot
                // ("Received message not expected here tag=9f"), so send it here.
                animationStateEnabled=true;
                if (!visualOnly || signatureSeen) {
                    enqueue(Wire.command(0x9f));
                    observer.event("animation_state_enable","EnableAnimationState (0x9f) con telemetria attiva");
                }
            }
            if(!robotInitialized) {
                // RobotState proves that this firmware is already initialized.
                // Replaying SetOrigin/SyncTime/NV reads here interrupts a warm robot.
                robotInitialized=true;initialized=true;initializedAt=lastRx>0?lastRx:now();
                if("none".equals(initPath))initPath="warm_trigger";
                initState=InitState.INITIALIZED;
                enqueue(Wire.command(0x60,Wire.le(12).putInt(1).putInt(0).putInt(0).array()));
                observer.event("init_existing_state","RobotState ricevuto prima di BodyInfo: riuso inizializzazione firmware attiva");
            }
            try {
                Wire.Telemetry t=Wire.Telemetry.parse(p.data);lastState=now();latestState=t;safety.update(t,lastState);states++;
                if(states==1){
                    firstState=lastState;
                    connectReadyMs=Math.max(0L,lastState-start);
                    observer.event("connect_ready_ms",String.valueOf(connectReadyMs));
                    status("Connesso · telemetria attiva");
                }
                if(t.unsafe()) {motionArmed=false;if(states==1)emergency.set(true);}
                observer.state(t,states);
            } catch(IllegalArgumentException e){
                bad++;
                if(telemetryErrorLogged++<3){
                    StringBuilder hx=new StringBuilder();
                    for(byte x:p.data)hx.append(String.format("%02x",x&255));
                    observer.event("telemetry_error",e.getMessage()+" len="+p.data.length+" hex="+hx);
                }
```

```text
$ sed -n '270,295p' src/dev/s200/cozmo/RobotService.java
            .build();
    }

    @Override
    public int onStartCommand(Intent i, int flags, int id) {
        if (i == null) {
            if (link == null) stopSelf();
            return START_NOT_STICKY;
        }
        String action = i.getAction();
        if (action == null) return START_NOT_STICKY;
        if (link != null && link.isRecoveringFirmware()) return START_NOT_STICKY;
        switch (action) {
            case "CONNECT": {
                String ssid = i.getStringExtra("ssid");
                String pass = i.getStringExtra("password");
                boolean existing = i.getBooleanExtra("existing", false);
                if (i.hasExtra("auto_head")) getSharedPreferences("robot", 0).edit().putBoolean("auto_head_on_dock", i.getBooleanExtra("auto_head", true)).apply();
                if (i.hasExtra("eyes")) getSharedPreferences("robot", 0).edit().putBoolean("eyes_on_connect", i.getBooleanExtra("eyes", true)).apply();
                if (ssid == null || ssid.trim().isEmpty()) ssid = getSharedPreferences("robot", 0).getString("ssid", "Cozmo_71278E");
                if (!existing && (pass == null || pass.isEmpty())) pass = Secrets.read(this);
                connect(ssid.trim(), pass, existing, i.getBooleanExtra("visual_only", false));
                break;
            }
            case "AUTO_HEAD":
                getSharedPreferences("robot", 0).edit().putBoolean("auto_head_on_dock", i.getBooleanExtra("enabled", true)).apply();
```

```text
$ sed -n '385,425p' src/dev/s200/cozmo/RobotService.java
            }
            case "HEADLIGHT":
                if (link != null) link.setHeadLight(i.getBooleanExtra("enabled", true));
                break;
            case "DIAG_NO_DISPLAY":
                diagNoDisplay = i.getBooleanExtra("enabled", true);
                event("diag", "Display continuo " + (diagNoDisplay ? "disattivato" : "attivato"));
                break;
            case "DIAG_SINGLE_FRAME":
                if (link != null) link.displayEncoded(Wire.display(new boolean[128*32]));
                event("diag", "Singolo frame display 128x32 inviato");
                break;
            case "RECOVER_FIRMWARE_2381": {
                Link active = link;
                if (active != null && active.isVisualOnly() && active.isFactoryFirmware()) {
                    try {
                        String token = i.getStringExtra("token");
                        File capability = new File(getFilesDir(), "recover-2381.token");
                        if (token == null || token.length() != 64 || capability.length() != 64
                                || !java.security.MessageDigest.isEqual(token.getBytes(StandardCharsets.US_ASCII),
                                    java.nio.file.Files.readAllBytes(capability.toPath()))) {
                            event("firmware_recovery", "Avvio sospeso · autorizzazione locale non presente");
                            break;
                        }
                        File image = new File(getFilesDir(), "firmware-2381.safe");
                        if (image.length() != 382416) throw new IllegalArgumentException("Dimensione immagine inattesa");
                        java.nio.file.Files.delete(capability.toPath());
                        active.recoverFirmware2381(java.nio.file.Files.readAllBytes(image.toPath()));
                    } catch (Exception e) {
                        event("firmware_recovery", "Immagine non utilizzabile · " + e.getMessage());
                    }
                } else {
                    event("firmware_recovery", "Avvio sospeso · modalità di fabbrica non rilevata");
                }
                break;
            }
            case "DIAG_VISUAL_PLAYER":
                if (link != null) link.probeVisualPlayer(i.getBooleanExtra("tagged", false));
                break;
            case "DIAG_BACKPACK98":
                if (link != null) link.diagBackpack98();
```

```text
$ sed -n '1108,1132p' src/dev/s200/cozmo/RobotService.java
                personality.setGazeTarget(vr.motionX * 5.0f, vr.motionY * 2.5f, now + 1200L);
            }
        }
    }

    @Override
    public void event(String kind, String text) {
        String safeText = AiSupervisor.sanitizeForLog(text, Secrets.readNamed(this, "gemini_live_key_v1"));
        if (kind.equals("status")) status = safeText;
        else if (kind.equals("firmware")) {
            try {
                JSONObject fwObj = new JSONObject(safeText);
                int ver = fwObj.optInt("version", -1);
                String bld = fwObj.optString("build", "");
                firmware = ver > 0 ? (ver + (bld.isEmpty() ? "" : " (" + bld + ")")) : safeText;
            } catch (Exception e) {
                firmware = "Versione non riconosciuta";
            }
        } else if (kind.equals("rejected") || kind.equals("motion") || kind.equals("shutdown") || kind.equals("emergency_stop")) {
            notice = safeText;
        }
        Log.i("CozmoS200", kind + ": " + safeText);
        synchronized (this) {
            if (log != null) {
                try {
```

```text
$ sed -n '1248,1272p' src/dev/s200/cozmo/RobotService.java
            VisionAnalyzer.Result vr = visionResult;
            Personality.PersonalitySnapshot snap = personality.snapshot(now);
            JSONObject root = new JSONObject();
            root.put("time", System.currentTimeMillis());
            root.put("monotonic_ms", now);
            root.put("connected", l != null && l.stateAge() < 350);
            root.put("status", status);
            root.put("firmware", firmware);
            root.put("notice", notice);
            root.put("state_count", stateCount);
            root.put("telemetryCount", stateCount);
            root.put("initPath", l != null ? l.initPath() : "none");
            root.put("connectReadyMs", l != null ? l.connectReadyMs() : -1);
            root.put("camera_count", cameraCount);
            root.put("eyes_enabled", life);
            root.put("armed", l != null && l.armed());
            root.put("visual_only", l != null && l.isVisualOnly());
            root.put("factory_firmware", l != null && l.isFactoryFirmware());
            root.put("firmware_recovering", l != null && l.isRecoveringFirmware());
            root.put("anim_bytes_played", l != null ? l.animBytesPlayed() : 0);
            root.put("anim_drops", l != null ? l.animDrops() : 0);
            root.put("anim_tracks", l != null ? l.animTracks() : 0);
            root.put("audio_tx", l != null ? l.audioFramesSent() : 0);
            root.put("audio_hw", l != null ? l.audioFramesPlayed() : 0);
            root.put("audio_queued", l != null ? l.getQueuedAudioFrames() : 0);
```

## 5. Stato ADB

```text
$ adb devices -l
List of devices attached
100.94.42.57:5555      device product:M24P_EEA model:S200 device:M24P transport_id:518
localhost:5556         device product:redroid_arm64 model:redroid13_arm64 device:redroid_arm64 transport_id:5
```

```text
$ adb shell dumpsys package dev.s200.cozmo | grep -E "versionName|versionCode|firstInstallTime|lastUpdateTime"
adb: more than one device/emulator

Exited with code 1
```

```text
$ adb -s 100.94.42.57:5555 shell dumpsys package dev.s200.cozmo | grep -E "versionName|versionCode|firstInstallTime|lastUpdateTime"
    versionCode=28 minSdk=29 targetSdk=35
    versionName=0.4.9-visual
    lastUpdateTime=2026-11-22 18:03:14
      firstInstallTime=2026-11-16 21:15:47
      firstInstallTime=2026-11-16 21:15:47
      firstInstallTime=2026-11-16 21:15:47
      firstInstallTime=2026-11-16 21:15:47
      firstInstallTime=2026-11-16 21:15:47
```
