"""Writes the TA-43 field phone's models, texture and blockstate, and its handset item.

    python tools/field_phone_models.py [--preview DIR]

The field phone is a full block across and nearly a block tall with its handset: an olive drab case with a lid lip, a
square access door and a round window on the front, two binding posts for the line and a buzzer knob on top, and the
handset lying lengthways in two cradle hooks. Facing north, the front is the north face; the handset's earpiece is to
the east (the viewer's left). The handset's cubes are named earpiece, mouthpiece, handle (and neck_*), as on the
telephone, so its off-hook model is the block without them.

Writes (under assets/radiocraft/):
  textures/block/field_phone.png
  models/block/field_phone.json, field_phone_off_hook.json
  models/item/field_phone.json            the block item
  models/item/field_phone_handset.json    the handset in the hand, lengthways along z as the telephone's, its display
                                          slots those of tools/telephone_models.py scaled to the same size in hand
  blockstates/field_phone.json

`--preview DIR` writes a Blockbench preview of the block and its off-hook model side by side.
"""
import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from blockmodel import *  # noqa: E402,F403
import telephone_models  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent / "src/main/resources/assets/radiocraft"
MODELS = ROOT / "models"
TEXTURES = ROOT / "textures/block"
SIZE = 64
HANDSET = {"earpiece", "mouthpiece", "handle", "neck_ear", "neck_mouth"}

OD = (84, 96, 70)              # olive drab, gone grey-green with age
OD_LIGHT = (108, 120, 90)
OD_DARK = (60, 69, 50)
WORN = (150, 152, 140)         # paint rubbed through to bare steel on the edges
BLACK_BAKELITE = (30, 31, 30)
BLACK_LIGHT = (52, 54, 52)
BRASS_POST = (176, 146, 74)
STEEL = (170, 172, 168)
STEEL_DARK = (110, 112, 108)
GLASS = (46, 54, 58)
GLASS_LIGHT = (86, 98, 104)
PAPER = (214, 208, 186)
INK = (48, 46, 42)

# Regions in sixteenths of the texture: [u0, v0, u1, v1].
REGIONS = {
    "side": [0, 0, 8, 6],        # the case's four sides (stretched over each)
    "top": [8, 0, 16, 8],        # the lid's top (stretched)
    "lid_edge": [0, 6, 8, 7],    # the lid's lip, worn to steel along its edge (stretched)
    "bottom": [0, 7, 8, 8],
    "black": [0, 8, 8, 10],      # the handset and the cradle hooks
    "brass": [8, 8, 10, 9],
    "knob": [10, 8, 12, 10],
    "steel": [12, 8, 14, 9],
    "door": [0, 10, 5, 15],      # the square access door on the front (stretched)
    "window": [5, 10, 10, 15],   # the round window (stretched)
    "label": [10, 10, 15, 12.5],  # the instruction plate on the lid (stretched)
}
STRETCH = {"side", "top", "lid_edge", "bottom", "door", "window", "label"}


def paint():
    p = Painter(REGIONS, size=SIZE, seed=43)

    def side(x, y, w, h):
        if y == 0 or x == 0 or x == w - 1:
            return OD_LIGHT
        if y >= h - 2:
            return OD_DARK  # Grime along the bottom.
        if p.rnd.random() < 0.012:
            return WORN  # Chips.
        return None
    p.fill("side", OD, jitter=4, fn=side)

    def top(x, y, w, h):
        if min(x, y, w - 1 - x, h - 1 - y) == 0:
            return WORN if p.rnd.random() < 0.5 else OD_LIGHT
        if p.rnd.random() < 0.008:
            return WORN
        return None
    p.fill("top", OD, jitter=4, fn=top)
    p.fill("lid_edge", OD_LIGHT, jitter=4, fn=lambda x, y, w, h: WORN if y == 0 and p.rnd.random() < 0.6 else None)
    p.fill("bottom", OD_DARK, jitter=3)
    p.fill("black", BLACK_BAKELITE, jitter=3, fn=lambda x, y, w, h: BLACK_LIGHT if y == 0 else None)
    p.fill("brass", BRASS_POST, jitter=6)
    p.fill("knob", BLACK_LIGHT, jitter=3, fn=lambda x, y, w, h: STEEL_DARK if 2 < x < w - 3 else None)
    p.fill("steel", STEEL, jitter=5)

    def door(x, y, w, h):
        edge = min(x, y, w - 1 - x, h - 1 - y)
        if edge == 0:
            return OD_DARK
        if edge == 1:
            return OD_LIGHT
        if (x in (3, 4, w - 5, w - 4)) and (y in (3, 4, h - 5, h - 4)):
            return STEEL  # The four screws.
        if 6 <= edge and min(x, y, w - 1 - x, h - 1 - y) <= 7:
            return OD_DARK  # The recessed panel's edge.
        return None
    p.fill("door", OD, jitter=3, fn=door)

    def window(x, y, w, h):
        cx, cy = (w - 1) / 2, (h - 1) / 2
        r = ((x - cx) ** 2 + (y - cy) ** 2) ** 0.5
        if r > w / 2 - 1:
            return OD_LIGHT if min(x, y, w - 1 - x, h - 1 - y) == 0 else None
        if r > w / 2 - 3:
            return STEEL_DARK  # The bezel.
        if abs((x - cx) + (y - cy)) < 1.2 and r < w / 2 - 5:
            return (120, 84, 50)  # The lever behind the glass.
        if x < cx and y < cy and abs(r - w / 4) < 0.8:
            return GLASS_LIGHT  # A glint.
        return GLASS
    p.fill("window", OD, jitter=2, fn=window)

    def label(x, y, w, h):
        if min(x, y, w - 1 - x, h - 1 - y) == 0:
            return STEEL_DARK
        if y % 3 == 2 and 2 <= x < w - 2 - (y * 5) % 7:
            return INK  # Lines of instructions.
        return None
    p.fill("label", PAPER, jitter=4, fn=label)
    return p


def handset(b, along_x):
    """The handset: two cups and an arched handle between, 12 long; earpiece east (along x) or south (along z)."""
    def box(name, length, across, height, mat="black"):
        # length/across: (from, to) along the handset and across it.
        if along_x:
            return b.box(name, [length[0], height[0], across[0]], [length[1], height[1], across[1]], mat)
        return b.box(name, [across[0], height[0], length[0]], [across[1], height[1], length[1]], mat)
    across, grip = (8, 11), (8.75, 10.25)
    return [
        box("mouthpiece", (2, 4.75), across, (12.5, 14.25)),
        box("earpiece", (11.25, 14), across, (12.5, 14.25)),
        box("neck_mouth", (4.75, 5.75), grip, (13.5, 14.75)),
        box("neck_ear", (10.25, 11.25), grip, (13.5, 14.75)),
        box("handle", (5.75, 10.25), grip, (14, 15.5)),
    ]


def block_elements(b):
    els = [
        b.box("case", [0.5, 0, 0.5], [15.5, 10.5, 15.5], "side", overrides={"down": "bottom"}),
        b.box("lid", [0, 10.5, 0], [16, 11.5, 16], "lid_edge", overrides={"up": "top", "down": "bottom"}),
        b.box("door", [2, 2, 0.25], [7, 7.5, 0.5], "door", only=("north", "east", "west", "up", "down")),
        b.box("window", [9, 2, 0.25], [14, 7.5, 0.5], "window", only=("north", "east", "west", "up", "down")),
        # The cradle: a forked hook under each cup.
        b.box("cradle_mouth", [2.5, 11.5, 7.5], [4.25, 12.5, 11.5], "black"),
        b.box("cradle_ear", [11.75, 11.5, 7.5], [13.5, 12.5, 11.5], "black"),
        b.box("cord", [1, 11.5, 9], [2, 13.25, 10], "black"),  # Where the cord comes out (it stays with the handset up).
        # The line's binding posts, front left (east), and the buzzer knob.
        b.box("post_a", [13.6, 11.5, 1.6], [14.9, 13.5, 2.9], "black"),
        b.box("post_a_collar", [13.4, 12.2, 1.4], [15.1, 12.6, 3.1], "brass"),
        b.box("post_b", [13.6, 11.5, 4.1], [14.9, 13.5, 5.4], "black"),
        b.box("post_b_collar", [13.4, 12.2, 3.9], [15.1, 12.6, 5.6], "brass"),
        b.box("knob", [7, 11.5, 2], [9, 12.75, 4], "knob"),
        b.box("knob_bar", [7.75, 12.75, 1.5], [8.25, 13.25, 4.5], "steel"),
        b.box("label", [4, 11.5, 4.5], [10.5, 11.6, 7], "label", only=("up",)),
        # Strap rings on the ends.
        b.box("ring_w", [0, 7.5, 7], [0.5, 9, 9], "steel"),
        b.box("ring_e", [15.5, 7.5, 7], [16, 9, 9], "steel"),
    ]
    return els + handset(b, along_x=True)


def blockstate():
    variants = {}
    for facing, y in telephone_models.FACINGS.items():
        for hook in (False, True):
            variant = {"model": "radiocraft:block/" + ("field_phone_off_hook" if hook else "field_phone")}
            if y:
                variant["y"] = y
            variants[f"facing={facing},off_hook={str(hook).lower()}"] = variant
    return {"variants": variants}


def handset_item(b):
    """The handset item: the cubes along z as the telephone's, its slots scaled to its handset's length in hand."""
    els = handset(b, along_x=False)
    model = {"credit": "RadioCraft", "texture_size": [SIZE, SIZE],
             "textures": {"0": "radiocraft:block/field_phone", "particle": "radiocraft:block/field_phone"}, "elements": els}
    lo, hi = telephone_models.bounds(els)
    phone_lo, phone_hi = telephone_models.bounds([e for e in json.loads((MODELS / "item/phone_item.json").read_text(encoding="utf-8"))["elements"]])
    shrink = (phone_hi[2] - phone_lo[2]) / (hi[2] - lo[2])
    slots = telephone_models.SLOTS
    try:
        telephone_models.SLOTS = {k: (r, t, s * shrink) for k, (r, t, s) in slots.items()}
        display = telephone_models.handset_display(model)["display"]
    finally:
        telephone_models.SLOTS = slots
    model["display"] = display
    return model


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--preview")
    args = ap.parse_args()
    b = Builder(REGIONS, "radiocraft:block/field_phone", size=SIZE, stretch=STRETCH)
    paint().write(TEXTURES / "field_phone.png")
    els = block_elements(b)
    on_hook = b.model(els)
    on_hook["parent"] = "block/block"
    off = dict(on_hook)
    off["elements"] = [e for e in els if e["name"] not in HANDSET]
    save(MODELS / "block", "field_phone", on_hook)
    save(MODELS / "block", "field_phone_off_hook", off)
    save(MODELS / "item", "field_phone", {"parent": "radiocraft:block/field_phone"})
    save(MODELS / "item", "field_phone_handset", handset_item(b))
    path = ROOT / "blockstates/field_phone.json"
    path.write_text(json.dumps(blockstate(), indent="\t") + "\n", encoding="utf-8")
    print("wrote", path)
    if args.preview:
        write_preview(args.preview, "radiocraft", TEXTURES, [(["field_phone"], els), (["field_phone"], off["elements"])], size=SIZE)


if __name__ == "__main__":
    main()
