"""Costanti hardware reali di Anki Cozmo (prima generazione).

Fonte: misure ingegneristiche del robot e specifiche del protocollo cozmoclad.
Ogni valore qui è dichiarato una volta sola e consumato dagli altri moduli:
un numero che cambia si cambia QUI, mai ridigitato nei singoli algoritmi.
"""

import math

# --- Trasmissione: trazione differenziale skid-steer ---
WHEEL_TRACK_MM = 48.0                 # carreggiata effettiva tra i due cingoli [mm]
WHEEL_SPEED_SMOOTH_MAX = 150.0        # range operativo fluido [mm/s]
WHEEL_SPEED_HARD_MAX = 200.0          # limite massimo hardware [mm/s]
WHEEL_ACCEL_TYPICAL = (200.0, 500.0)  # accelerazione tipica [mm/s^2] (min, max)
WHEEL_RADIUS_MM = 21.5                # raggio nominale ruota motrice [mm] (stima)

# --- Testa: 1 DOF pitch ---
HEAD_ANGLE_MIN = math.radians(-25.0)   # sguardo al pavimento davanti ai cingoli
HEAD_ANGLE_MAX = math.radians(44.5)    # sguardo al volto di una persona seduta

# --- Carrello elevatore (lift): 1 DOF quadrilatero ---
LIFT_HEIGHT_MIN = 32.0                # abbassato, a filo del suolo [mm]
LIFT_HEIGHT_MAX = 92.0                # sollevato sopra la testa [mm]

# --- Videocamera di bordo ---
CAMERA_W = 320
CAMERA_H = 240
CAMERA_HFOV_DEG = 60.0
CAMERA_VFOV_DEG = 45.0

# --- Display facciale OLED ---
OLED_W = 128
OLED_H = 64
OLED_BUFFER_BYTES = OLED_W * OLED_H // 8   # 1024 B, 1 bit per pixel

# --- Audio di bordo ---
AUDIO_SAMPLE_RATE = 22050             # 22.05 kHz
AUDIO_BITS = 16                       # signed int
AUDIO_CHANNELS = 1                    # mono
AUDIO_FRAME_MS = 20                   # chunk tipico inviato al firmware

# --- Rete e trasporto (cozmoclad over UDP) ---
COZMO_IP = "172.31.1.1"
COZMO_PORT = 5551
CLIENT_SUBNET = "172.31.1.0/24"
SERVER_IF_PRIMARY = "eth0"            # o wlan0: default route + DNS
SERVER_IF_COZMO = "wlan1"             # dongle dedicato: solo rotta verso Cozmo

# --- Vincolo di keep-alive del firmware ---
KEEPALIVE_TIMEOUT_S = 3.5             # 3..5 s: oltre, il firmware va in safe-state
KEEPALIVE_PERIOD_S = 1.0              # periodo di ping del loop di comunicazione

# --- Modello occhi procedurale (19 parametri, come pycozmo procedural_face) ---
FACE_PARAMS = (
    "center_x", "center_y", "scale_x", "scale_y", "angle",
    "upper_lid_y", "upper_lid_angle", "upper_lid_bend",
    "lower_lid_y", "lower_lid_angle", "lower_lid_bend",
    "upper_lid_y_l", "upper_lid_angle_l", "upper_lid_bend_l",
    "lower_lid_y_l", "lower_lid_angle_l", "lower_lid_bend_l",
    "hotspot", "glow",
)
FACE_PARAM_COUNT = len(FACE_PARAMS)   # == 19
FACE_FPS_TARGET = 30

# --- Limiti fisici del mondo del digital twin (tavolo) ---
TABLE_HALF_X_MM = 250.0               # tavolo 500 x 400 mm
TABLE_HALF_Y_MM = 200.0