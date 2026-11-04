"""Builds the Radio Moderation Computer's placeholder model: a beige late-90s PC (desktop case, CRT monitor showing a
teal desktop with a window, keyboard) as models/block/moderation_computer.json and textures/block/moderation_computer.png
(128x128, 4 texture pixels per model unit, so UVs are pixels / 8). The front faces north; the blockstate and item model
come from the data generator (complexHorizontalBlockWithItem). See doc/ModelDesignBounty.md.

    python tools/moderation_computer_model.py
"""
import json
import os

from PIL import Image

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'src/main/resources/assets/radiocraft') + '/'

BEIGE = (216, 208, 184, 255)
BEIGE_LIGHT = (230, 224, 206, 255)
BEIGE_SHADE = (196, 187, 162, 255)
BEIGE_DARK = (168, 159, 134, 255)
SEAM = (140, 132, 110, 255)
BLACK = (20, 20, 22, 255)
SLOT = (48, 46, 42, 255)
TEAL = (0, 128, 128, 255)
GLASS_EDGE = (36, 40, 44, 255)
GREY = (192, 192, 192, 255)
WHITE = (255, 255, 255, 255)
NAVY = (0, 0, 128, 255)
GREEN = (60, 220, 60, 255)
AMBER = (230, 160, 40, 255)
KEY = (236, 232, 220, 255)
KEY_SHADE = (150, 144, 126, 255)

img = Image.new('RGBA', (128, 128), (0, 0, 0, 0))
px = img.load()


def rect(x0, y0, x1, y1, c):
    for y in range(y0, y1):
        for x in range(x0, x1):
            px[x, y] = c


def frame(x0, y0, x1, y1, light, dark):
    rect(x0, y0, x1, y0 + 1, light)
    rect(x0, y0, x0 + 1, y1, light)
    rect(x0, y1 - 1, x1, y1, dark)
    rect(x1 - 1, y0, x1, y1, dark)


# ---- Monitor front, 11 x 9.5 units = 44 x 38 px at (0, 0): beige bezel, the glass showing a teal desktop and a window.
rect(0, 0, 44, 38, BEIGE)
frame(0, 0, 44, 38, BEIGE_LIGHT, BEIGE_DARK)
rect(4, 3, 40, 30, GLASS_EDGE)
rect(5, 4, 39, 29, TEAL)
rect(9, 8, 33, 24, GREY)                 # a window
frame(9, 8, 33, 24, WHITE, BLACK)
rect(10, 9, 32, 12, NAVY)                # its title bar
rect(30, 9, 32, 11, GREY)                # close box
for row in range(13, 23, 2):             # rows of a list
    rect(11, row, 31, row + 1, WHITE)
rect(5, 27, 39, 29, GREY)                # the taskbar
rect(6, 27, 11, 29, (0, 128, 0, 255))   # Start
rect(34, 32, 37, 34, GREEN)              # power lamp
rect(6, 32, 16, 33, BEIGE_SHADE)         # a badge

# ---- Case front, 14 x 4 units = 56 x 16 px at (0, 40): floppy and CD drives, power button and lamps.
rect(0, 40, 56, 56, BEIGE)
frame(0, 40, 56, 56, BEIGE_LIGHT, BEIGE_DARK)
rect(4, 43, 22, 48, BEIGE_SHADE)         # CD drive
rect(5, 45, 21, 46, SLOT)
rect(4, 49, 16, 53, BEIGE_SHADE)         # floppy drive
rect(5, 51, 15, 52, SLOT)
rect(46, 45, 51, 50, BEIGE_LIGHT)        # power button
frame(46, 45, 51, 50, WHITE, BEIGE_DARK)
rect(40, 46, 42, 47, GREEN)              # power lamp
rect(40, 49, 42, 50, AMBER)              # disk lamp
for x in range(26, 36, 2):               # vents
    rect(x, 44, x + 1, 52, BEIGE_DARK)

# ---- Keyboard top, 12 x 4.5 units = 48 x 18 px at (0, 58): rows of keys.
rect(0, 58, 48, 76, BEIGE)
frame(0, 58, 48, 76, BEIGE_LIGHT, BEIGE_DARK)
for r, y in enumerate((60, 63, 66, 69)):
    for x in range(2, 36, 3):
        rect(x, y, x + 2, y + 2, KEY)
        rect(x, y + 2, x + 2, y + 3, KEY_SHADE)
rect(8, 72, 28, 74, KEY)                 # space bar
rect(8, 74, 28, 75, KEY_SHADE)
for y in (60, 63, 66, 69):               # number pad
    for x in (38, 41, 44):
        rect(x, y, x + 2, y + 2, KEY)
        rect(x, y + 2, x + 2, y + 3, KEY_SHADE)

# ---- Keyboard front edge, 12 x 1.5 units = 48 x 6 px at (0, 78).
rect(0, 78, 48, 84, BEIGE_SHADE)
rect(0, 83, 48, 84, BEIGE_DARK)

# ---- Case side, 10 x 4 units = 40 x 16 px at (56, 40), with vent slots; the monitor tub's side, 6 x 7.5 = 24 x 30 at (56, 0).
rect(56, 40, 96, 56, BEIGE_SHADE)
frame(56, 40, 96, 56, BEIGE, BEIGE_DARK)
for y in range(44, 53, 2):
    rect(62, y, 80, y + 1, SEAM)
rect(56, 0, 80, 30, BEIGE_SHADE)
for y in range(6, 26, 3):
    rect(60, y, 76, y + 1, BEIGE_DARK)

# ---- Plain swatches (stretched over faces).
rect(96, 0, 104, 8, BEIGE)
rect(104, 0, 112, 8, BEIGE_LIGHT)
rect(112, 0, 120, 8, BEIGE_SHADE)
rect(120, 0, 128, 8, BEIGE_DARK)
rect(96, 8, 104, 16, SEAM)

img.save(ROOT + 'textures/block/moderation_computer.png')


def uv(x0, y0, x1, y1):
    return [x0 / 8, y0 / 8, x1 / 8, y1 / 8]


PLAIN = uv(97, 1, 103, 7)
LIGHT = uv(105, 1, 111, 7)
SHADE = uv(113, 1, 119, 7)
DARK = uv(121, 1, 127, 7)
MONITOR_FRONT = uv(0, 0, 44, 38)
CASE_FRONT = uv(0, 40, 56, 56)
KEYS = uv(0, 58, 48, 76)
KEYS_FRONT = uv(0, 78, 48, 84)
CASE_SIDE = uv(56, 40, 96, 56)
TUB_SIDE = uv(56, 0, 80, 30)


def box(name, frm, to, north, south, east, west, up, down):
    faces = {}
    for face, area in (('north', north), ('south', south), ('east', east), ('west', west), ('up', up), ('down', down)):
        faces[face] = {'uv': area, 'texture': '#0'}
    return {'name': name, 'from': frm, 'to': to, 'faces': faces}


elements = [
    box('keyboard', [2, 0, 0.5], [14, 1.5, 5], KEYS_FRONT, SHADE, SHADE, SHADE, KEYS, DARK),
    box('case', [1, 0, 5.5], [15, 4, 15.5], CASE_FRONT, SHADE, CASE_SIDE, CASE_SIDE, LIGHT, DARK),
    box('stand', [5.5, 4, 8], [10.5, 5, 13], SHADE, SHADE, SHADE, SHADE, PLAIN, DARK),
    box('bezel', [2.5, 5, 6], [13.5, 14.5, 9], MONITOR_FRONT, SHADE, SHADE, SHADE, LIGHT, SHADE),
    box('tub', [4, 6, 9], [12, 13.5, 15], SHADE, DARK, TUB_SIDE, TUB_SIDE, PLAIN, SHADE),
]

T = 'radiocraft:block/moderation_computer'
model = {
    'credit': 'RadioCraft placeholder, made by script (see doc/ModelDesignBounty.md)',
    'parent': 'block/block',
    'texture_size': [128, 128],
    'textures': {'0': T, 'particle': T},
    'elements': elements,
}
json.dump(model, open(ROOT + 'models/block/moderation_computer.json', 'w'), indent=2)
