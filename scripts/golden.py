"""Produce independent wire fixtures using the original upstream Python implementation."""
import importlib
import pathlib
import sys
import types

root = pathlib.Path(__file__).resolve().parents[1]
pkg = types.ModuleType('pycozmo')
pkg.__path__ = [str(root / 'upstream/pycozmo/pycozmo')]
sys.modules['pycozmo'] = pkg
p = importlib.import_module('pycozmo.protocol_encoder')
f = importlib.import_module('pycozmo.frame')
ft = importlib.import_module('pycozmo.protocol_ast').FrameType
out = root / 'build/fixtures'
out.mkdir(parents=True, exist_ok=True)


def ulaw_py(sample: int) -> int:
    mask = 0x4000
    position = 14
    sign = 0
    if sample < 0:
        sample = -sample
        sign = 0x80
    sample += 132
    if sample > 0x7FFF:
        sample = 0x7FFF
    while (sample & mask) != mask and position >= 7:
        mask >>= 1
        position -= 1
    lsb = (sample >> (position - 4)) & 0x0f
    return sign | ((position - 7) << 4) | lsb


ulaw_table = bytes(ulaw_py(s) for s in range(-32768, 32768))
(out / 'ulaw-all-65536.bin').write_bytes(ulaw_table)

audio_samples = bytes(ulaw_py((i * 87) - 16000) for i in range(744))
ls_cyan = p.LightState(on_color=0x03ff, off_color=0, on_frames=0, off_frames=0,
                       transition_on_frames=0, transition_off_frames=0, offset=0)

fixtures = {
    'reset': f.Frame(ft.RESET, 0, 0, 65535, []),
    'enable': f.Frame(ft.ENGINE, 0, 0, 0, [p.Enable()]),
    'drive': f.Frame(ft.ENGINE, 7, 7, 12, [p.DriveWheels(30, -30, 100, 100)]),
    'head': f.Frame(ft.ENGINE, 8, 8, 12, [p.SetHeadAngle(angle_rad=0.1, max_speed_rad_per_sec=1, accel_rad_per_sec2=4, duration_sec=0.6, action_id=0)]),
    'connect': f.Frame(ft.ROBOT, 0, 0, 65535, [p.Connect()]),
    'ping': f.Frame(ft.PING, 65535, 65535, 2, [p.Ping(12.5, 1, 0, 0)]),
    'robot': f.Frame(ft.ROBOT, 65535, 65535, 8, [p.RobotState(timestamp=1234, pose_x=12, pose_y=23, head_angle_rad=0.2, lift_angle_rad=0.3, battery_voltage=3.8, status=0x3000, cliff_data_raw=[100, 200, 300, 400])]),
    'large-image': f.Frame(ft.ROBOT, 65535, 65535, 8, [p.ImageChunk(image_id=7, image_encoding=8, image_resolution=4, image_chunk_count=2, chunk_id=0, data=bytes([42]) * 1400)]),
    'volume': f.Frame(ft.ENGINE, 3, 3, 1, [p.SetRobotVolume(level=32767)]),
    'audio': f.Frame(ft.ENGINE, 4, 4, 1, [p.OutputAudio(samples=audio_samples)]),
    'headlight': f.Frame(ft.ENGINE, 5, 5, 1, [p.SetHeadLight(enable=True)]),
    'discovery': f.Frame(ft.ENGINE, 6, 6, 1, [p.SetAccessoryDiscovery(enable=True)]),
    'obj-connect': f.Frame(ft.ENGINE, 9, 9, 2, [p.ObjectConnect(factory_id=0x0a0b0c0d, slot=1)]),
    'cube-id': f.Frame(ft.ENGINE, 10, 10, 2, [p.CubeId(object_id=1, rotation_period_frames=12)]),
    'cube-lights': f.Frame(ft.ENGINE, 11, 11, 2, [p.CubeLights(states=(ls_cyan, ls_cyan, ls_cyan, ls_cyan))]),
    'backpack-center': f.Frame(ft.ENGINE, 12, 12, 2, [p.LightStateCenter(states=(ls_cyan, ls_cyan, ls_cyan))]),
    'anim-state': f.Frame(ft.ROBOT, 65535, 65535, 4, [p.AnimationState(timestamp=900, num_anim_bytes_played=4096, num_audio_frames_played=27, enabled_anim_tracks=3, tag=1, client_drop_count=0)]),
    'obj-available': f.Frame(ft.ROBOT, 65535, 65535, 5, [p.ObjectAvailable(factory_id=0x0a0b0c0d, object_type=2, rssi=-48)]),
    'obj-conn-state': f.Frame(ft.ROBOT, 1, 1, 5, [p.ObjectConnectionState(object_id=1, factory_id=0x0a0b0c0d, object_type=2, connected=True)]),
    'obj-tapped': f.Frame(ft.ROBOT, 2, 2, 5, [p.ObjectTapped(timestamp=777, object_id=1, num_taps=2, tap_time=35, tap_neg=-8, tap_pos=11)]),
    'nv-read-calib': f.Frame(ft.ENGINE, 13, 13, 2, [p.NvStorageOp(tag=p.NvEntryTag.NVEntry_CameraCalib, length=1, op=p.NvOperation.NVOP_READ)]),
    'nv-read-cubes': f.Frame(ft.ENGINE, 14, 14, 2, [p.NvStorageOp(tag=p.NvEntryTag.NVEntry_SavedCubeIDs, length=28, op=p.NvOperation.NVOP_READ)]),
    'shutdown': f.Frame(ft.ENGINE, 15, 15, 2, [p.ShutdownRobot()]),
}
for name, frame in fixtures.items():
    (out / (name + '.bin')).write_bytes(frame.to_bytes())
print('Independent PyCozmo fixtures:', len(fixtures))
