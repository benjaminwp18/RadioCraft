"""Builds the hand microphone's held-in-hand model: textures/item/hand_microphone_3d.png (64x64, 4 px per model unit)
and models/item/hand_microphone_3d.json, a speaker mic with a grille on its face, a PTT bar on its left side, a clip on
its back and the cord leaving its bottom. The item models of the hand, corded and manpack mics show it in the hand and
keep their flat sprite in the inventory. The face points south, toward the camera in first person."""
import json
import os
from PIL import Image

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'src/main/resources/assets/radiocraft') + '/'
FLAT = Image.open(ROOT + 'textures/item/hand_microphone.png').convert('RGBA')

BODY = FLAT.getpixel((5, 8))        # The case.
EDGE = FLAT.getpixel((3, 6))
GRILLE_DARK = FLAT.getpixel((6, 4))
GRILLE_LIGHT = FLAT.getpixel((7, 4))
LIGHT = FLAT.getpixel((4, 1))
PTT = (120, 124, 132, 255)
CORD = (24, 24, 26, 255)

U = 4
img = Image.new('RGBA', (64, 64), (0, 0, 0, 0))
px = img.load()


def rect(x0, y0, x1, y1, c):
    for y in range(y0, y1):
        for x in range(x0, x1):
            px[x, y] = c


# ---- Face: 4 x 5.5 units = 16 x 22 px at (0, 0). Grille up top, a plain band below it, a seam at the bottom.
rect(0, 0, 16, 22, BODY)
rect(0, 0, 16, 1, LIGHT)
rect(0, 0, 1, 22, LIGHT)
rect(15, 0, 16, 22, EDGE)
rect(0, 21, 16, 22, EDGE)
rect(2, 2, 14, 13, EDGE)
for y in range(3, 12):
    for x in range(3, 13):
        px[x, y] = GRILLE_DARK if (x + y) % 2 == 0 else GRILLE_LIGHT
rect(5, 15, 11, 16, LIGHT)      # A maker's badge.
rect(2, 18, 14, 19, EDGE)       # The seam of the two halves.

# ---- Solid swatches for the plain faces.
rect(52, 0, 64, 12, BODY)
rect(52, 12, 58, 18, PTT)
rect(58, 12, 64, 18, CORD)
rect(52, 18, 58, 24, EDGE)

img.save(ROOT + 'textures/item/hand_microphone_3d.png')

T = 'radiocraft:item/hand_microphone_3d'


def uv(x0, y0, x1, y1):
    return [x0 / U, y0 / U, x1 / U, y1 / U]


FACE = uv(0, 0, 16, 22)
BODY_UV = uv(53, 1, 63, 11)
PTT_UV = uv(53, 13, 57, 17)
CORD_UV = uv(59, 13, 63, 17)
EDGE_UV = uv(53, 19, 57, 23)


def box(name, frm, to, south, other):
    faces = {f: {'uv': other, 'texture': '#0'} for f in ('north', 'east', 'west', 'up', 'down')}
    faces['south'] = {'uv': south, 'texture': '#0'}
    return {'name': name, 'from': frm, 'to': to, 'faces': faces}


elements = [
    box('body', [6, 4, 7.25], [10, 9.5, 8.75], FACE, BODY_UV),
    box('ptt', [5.7, 6.2, 7.6], [6, 8.6, 8.4], PTT_UV, PTT_UV),
    box('clip', [7, 6.5, 6.95], [9, 9.2, 7.25], EDGE_UV, EDGE_UV),
    box('cord_boot', [7.4, 3.2, 7.6], [8.6, 4, 8.4], EDGE_UV, EDGE_UV),
    box('cord', [7.7, 1, 7.8], [8.3, 3.2, 8.2], CORD_UV, CORD_UV),
]

model = {
    'credit': 'RadioCraft placeholder, made by tools/hand_microphone_model.py (see doc/ModelDesignBounty.md)',
    'texture_size': [64, 64],
    'textures': {'0': T, 'particle': T},
    'elements': elements,
    'display': {
        'thirdperson_righthand': {'rotation': [0, 0, 0], 'translation': [0, 2.5, 1.5], 'scale': [0.6, 0.6, 0.6]},
        'thirdperson_lefthand': {'rotation': [0, 0, 0], 'translation': [0, 2.5, 1.5], 'scale': [0.6, 0.6, 0.6]},
        'firstperson_righthand': {'rotation': [-8, 12, 6], 'translation': [-1.5, 2.5, -1], 'scale': [0.7, 0.7, 0.7]},
        'firstperson_lefthand': {'rotation': [-8, -12, -6], 'translation': [-1.5, 2.5, -1], 'scale': [0.7, 0.7, 0.7]},
        'ground': {'rotation': [0, 0, 0], 'translation': [0, 2, 0], 'scale': [0.6, 0.6, 0.6]},
    },
}
json.dump(model, open(ROOT + 'models/item/hand_microphone_3d.json', 'w'), indent=2)

# The three mics: the 3D one in the hand, their flat sprite in the inventory, item frames and on the ground.
flat = {'parent': 'item/handheld', 'textures': {'layer0': 'radiocraft:item/hand_microphone'}}
item = {
    'loader': 'neoforge:separate_transforms',
    'base': {'parent': 'radiocraft:item/hand_microphone_3d'},
    'perspectives': {'gui': flat, 'fixed': flat},
}
for name in ('hand_microphone', 'corded_microphone', 'manpack_mic'):
    json.dump(item, open(ROOT + 'models/item/' + name + '.json', 'w'), indent=2)
