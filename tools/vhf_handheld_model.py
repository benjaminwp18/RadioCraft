"""Builds the VHF handheld's held-in-hand model: textures/item/vhf_handheld_3d.png (64x64, 4 px per model unit) and
models/item/vhf_handheld_3d.json. The front (keypad, screen, speaker) faces south, toward the camera in first person."""
import json
from PIL import Image

import os
ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'src/main/resources/assets/radiocraft') + '/'
GUI = Image.open(ROOT + 'textures/gui/vhf_handheld.png').convert('RGBA')

def gui(x, y):
    return GUI.getpixel((x, y))

# Palette sampled from the GUI so the radio in hand matches its screen.
FRAME = gui(30, 30)         # the blue-grey case
EDGE = gui(22, 100)
PANEL = gui(40, 45)         # upper panel
GRILLE_DARK = EDGE
GRILLE_LIGHT = gui(35, 172)
LCD = (124, 142, 98, 255)   # the screen's green, a shade brighter so it reads at this size
LCD_EDGE = (70, 84, 60, 255)
KEYPAD = (52, 57, 76, 255)
KEY = gui(35, 172)
KEY_SHADE = (92, 102, 108, 255)
APRS = gui(130, 212)
RED, GREEN, BLUE = gui(36, 113), gui(48, 113), gui(60, 113)
BATTERY = (44, 46, 52, 255)
BATTERY_LIGHT = (70, 73, 80, 255)
BATTERY_DARK = (28, 29, 33, 255)
BLACK = (18, 18, 20, 255)
KNOB = (120, 124, 132, 255)
PTT = (150, 156, 165, 255)

U = 4  # texture pixels per model unit
img = Image.new('RGBA', (64, 64), (0, 0, 0, 0))
px = img.load()

def rect(x0, y0, x1, y1, c):
    for y in range(y0, y1):
        for x in range(x0, x1):
            px[x, y] = c

# ---- Body front: 5 x 8.5 units = 20 x 34 px at (0, 0).
rect(0, 0, 20, 34, FRAME)
rect(1, 1, 19, 13, PANEL)                       # upper panel
for y in range(2, 12):                          # speaker grille
    rect(7, y, 18, y + 1, GRILLE_DARK if y % 2 == 0 else GRILLE_LIGHT)
rect(2, 3, 5, 4, gui(26, 26))          # "VHF FM" label, as a light dash
rect(2, 5, 5, 6, gui(26, 26))
rect(1, 13, 19, 14, EDGE)                       # seam under the panel
px[2, 15], px[4, 15], px[6, 15] = RED, GREEN, BLUE   # LEDs
rect(1, 17, 7, 20, BLACK)                       # signal meter
rect(2, 18, 4, 19, RED); rect(4, 18, 6, 19, GREEN)
rect(8, 14, 19, 21, LCD_EDGE)                   # screen
rect(9, 15, 18, 20, LCD)
rect(1, 21, 19, 22, EDGE)
rect(1, 22, 19, 33, KEYPAD)                     # keypad: 4 rows of 5 keys
cols = [(2, 4), (5, 7), (8, 10), (11, 14), (15, 18)]
for r, y in enumerate([23, 25, 27, 30]):
    for c, (x0, x1) in enumerate(cols):
        if r == 3 and c == 4:
            continue                            # ENTER spans the last two
        color = APRS if (r == 2 and c == 4) else KEY
        if r == 3 and c == 3:
            x1 = 18                             # ENTER
        rect(x0, y, x1, y + 1, color)
        rect(x0, y + 1, x1, y + 2, KEY_SHADE if color == KEY else APRS)
rect(0, 33, 20, 34, EDGE)

# ---- Battery front: 5 x 3.5 units = 20 x 14 px at (0, 36): ribbed pack, as wide as the radio.
rect(0, 36, 20, 50, BATTERY)
for y in range(37, 49, 3):
    rect(1, y, 19, y + 1, BATTERY_LIGHT)
    rect(1, y + 1, 19, y + 2, BATTERY_DARK)
rect(0, 36, 20, 37, BATTERY_DARK)

# ---- Solid swatches for the plain faces (stretched, so one colour each).
rect(52, 0, 64, 12, FRAME)      # body sides, back, top
rect(52, 12, 64, 24, BATTERY)   # battery sides, back, bottom
rect(52, 24, 58, 30, BLACK)     # antenna
rect(58, 24, 64, 30, KNOB)      # knobs
rect(52, 30, 58, 36, PTT)       # PTT key
rect(58, 30, 64, 36, EDGE)      # antenna collar
rect(52, 36, 58, 42, (150, 255, 120, 255))  # the RX LED lit (vhf_handheld_rx_led.json)

img.save(ROOT + 'textures/item/vhf_handheld_3d.png')

T = 'radiocraft:item/vhf_handheld_3d'

def uv(x0, y0, x1, y1):
    return [x0 / U, y0 / U, x1 / U, y1 / U]

FRONT = uv(0, 0, 20, 34)
BATT_FRONT = uv(0, 36, 20, 50)
FRAME_UV = uv(53, 1, 63, 11)
BATT_UV = uv(53, 13, 63, 23)
BLACK_UV = uv(53, 25, 57, 29)
KNOB_UV = uv(59, 25, 63, 29)
PTT_UV = uv(53, 31, 57, 35)
COLLAR_UV = uv(59, 31, 63, 35)

def box(name, frm, to, south, other):
    faces = {f: {'uv': other, 'texture': '#0'} for f in ('north', 'east', 'west', 'up', 'down')}
    faces['south'] = {'uv': south, 'texture': '#0'}
    return {'name': name, 'from': frm, 'to': to, 'faces': faces}

elements = [
    box('body', [5.5, 5, 6.75], [10.5, 13.5, 9.25], FRONT, FRAME_UV),
    box('battery', [5.5, 1.5, 6.75], [10.5, 5, 9.25], BATT_FRONT, BATT_UV),
    box('antenna_collar', [5.9, 13.5, 7.3], [7.3, 15, 8.7], COLLAR_UV, COLLAR_UV),
    box('antenna', [6.2, 15, 7.6], [7.0, 21.5, 8.4], BLACK_UV, BLACK_UV),
    box('mic_knob', [8.1, 13.5, 7.5], [9.1, 14.4, 8.5], KNOB_UV, KNOB_UV),
    box('vol_knob', [9.4, 13.5, 7.5], [10.4, 14.4, 8.5], KNOB_UV, KNOB_UV),
    box('ptt', [5.2, 8.5, 7.4], [5.5, 11.5, 8.6], PTT_UV, PTT_UV),
]

model = {
    'credit': 'RadioCraft placeholder, made by script (see doc/ModelDesignBounty.md)',
    'texture_size': [64, 64],
    'textures': {'0': T, 'particle': T},
    'elements': elements,
    'display': {
        'thirdperson_righthand': {'rotation': [0, 0, 0], 'translation': [0, 2.5, 1.5], 'scale': [0.5625, 0.5625, 0.5625]},
        'thirdperson_lefthand': {'rotation': [0, 0, 0], 'translation': [0, 2.5, 1.5], 'scale': [0.5625, 0.5625, 0.5625]},
        'firstperson_righthand': {'rotation': [-8, 12, 6], 'translation': [-1.5, 0.5, -1], 'scale': [0.6, 0.6, 0.6]},
        'firstperson_lefthand': {'rotation': [-8, -12, -6], 'translation': [-1.5, 0.5, -1], 'scale': [0.6, 0.6, 0.6]},
        'ground': {'rotation': [0, 0, 0], 'translation': [0, 2, 0], 'scale': [0.6, 0.6, 0.6]},
        'head': {'rotation': [0, 180, 0], 'translation': [0, 13, 7], 'scale': [1, 1, 1]},
        # Inventory and item frames: the face toward you, turned a little to show its depth, small enough that the
        # antenna stays inside the slot (the model is 20 units tall, centred 3.5 above the slot's middle).
        'gui': {'rotation': [10, -25, 0], 'translation': [0, -2.4, 0], 'scale': [0.68, 0.68, 0.68]},
        'fixed': {'rotation': [0, 180, 0], 'translation': [0, -2.4, 0], 'scale': [0.68, 0.68, 0.68]},
    },
    'gui_light': 'front',
}
json.dump(model, open(ROOT + 'models/item/vhf_handheld_3d.json', 'w'), indent=2)

# The RX LED lit, drawn over the green LED on the face while a docked handheld hears something. Full block and sky
# light (NeoForge's neoforge_data), so it glows in the dark.
LIT_UV = uv(53, 37, 57, 41)
led = box('rx_led', [6.45, 9.45, 9.25], [6.8, 9.8, 9.32], LIT_UV, LIT_UV)
led['neoforge_data'] = {'block_light': 15, 'sky_light': 15, 'ambient_occlusion': False}
json.dump({'credit': model['credit'], 'texture_size': [64, 64], 'textures': {'0': T, 'particle': T}, 'elements': [led]},
          open(ROOT + 'models/item/vhf_handheld_rx_led.json', 'w'), indent=2)

# Each crystal's item model is the 3D radio, in the hand and in the inventory alike.
for suffix in ('', '_poor', '_pure'):
    json.dump({'parent': 'radiocraft:item/vhf_handheld_3d'}, open(ROOT + 'models/item/vhf_handheld' + suffix + '.json', 'w'), indent=2)
