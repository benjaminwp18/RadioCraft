"""Placeholder models and texture for the DTMF decoder and encoder (bounty: doc/ModelDesignBounty.md).

Both are low boxes like the radio interface that stand against a radio, front to the north before the blockstate
turns them. The decoder has a red and a green lamp on its front (dark here; dtmf_decoder_red/green are the lit faces
the blockstate adds while a lamp is on); the encoder has a telephone keypad on its top and a small display in front.

Run: python tools/dtmf_models.py
"""
from pathlib import Path

from blockmodel import Builder, Painter, lamp, save, rim, edge, BLACK, CREAM, CREAM_DIRTY

ROOT = Path(__file__).resolve().parent.parent / "src/main/resources/assets/radiocraft"
MODELS = ROOT / "models/block"
TEXTURE = ROOT / "textures/block/dtmf.png"

CASE = (70, 74, 78)
CASE_LIGHT = (96, 100, 104)
CASE_DARK = (48, 51, 54)

REGIONS = {
    "case": [0, 0, 8, 8],
    "front": [8, 0, 16, 4],
    "bottom": [8, 8, 16, 16],
    "key": [0, 8, 2, 10],
    "key_side": [2, 8, 4, 10],
    "red_off": [4, 8, 5, 9],
    "red_on": [5, 8, 6, 9],
    "green_off": [6, 8, 7, 9],
    "green_on": [7, 8, 8, 9],
    "lcd": [8, 4, 16, 6],
}


def paint():
    p = Painter(REGIONS, size=32, seed=7)
    p.fill("case", CASE, jitter=4, fn=edge(CASE_LIGHT, CASE_DARK, vertical=False))
    p.fill("front", BLACK, jitter=3, fn=rim(CASE_DARK))
    p.fill("bottom", CASE_DARK, jitter=3)
    p.fill("key", CREAM, jitter=3, fn=rim(CREAM_DIRTY))
    p.fill("key_side", CREAM_DIRTY, jitter=3)
    p.fill("red_off", (70, 20, 18), jitter=2)
    p.fill("red_on", (255, 70, 60), jitter=4)
    p.fill("green_off", (20, 56, 26), jitter=2)
    p.fill("green_on", (90, 255, 110), jitter=4)
    p.fill("lcd", (140, 168, 120), jitter=3, fn=rim((60, 70, 54)))
    p.write(TEXTURE)


def case(b):
    return b.box("case", [1, 0, 4.5], [15, 5, 15.5], "case", overrides={"north": "front", "down": "bottom"})


# The decoder's lamps on its front face: red (the code is coming in) left of green (it came in).
RED = ([9, 2, 4.2], [11, 4, 4.5])
GREEN = ([12, 2, 4.2], [14, 4, 4.5])


def decoder(b):
    return [case(b),
            b.box("red", RED[0], RED[1], "red_off", only=("north", "east", "west", "up", "down")),
            b.box("green", GREEN[0], GREEN[1], "green_off", only=("north", "east", "west", "up", "down"))]


def lit(b, name, at, region):
    frm, to = at
    return [lamp(b, name, [frm[0], frm[1], frm[2] - 0.1], [to[0], to[1], frm[2]], region)]


def encoder(b):
    elements = [case(b), b.box("display", [3, 2, 4.3], [13, 4, 4.5], "lcd", only=("north",))]
    for row in range(4):  # 1 2 3 / 4 5 6 / 7 8 9 / * 0 #, front row first.
        z = 6 + row * 2.3
        for column in range(3):
            x = 4 + column * 3
            elements.append(b.box(f"key_{row}_{column}", [x, 5, z], [x + 2, 5.6, z + 1.8], "key_side", overrides={"up": "key"}, only=("north", "south", "east", "west", "up")))
    return elements


def main():
    paint()
    b = Builder(REGIONS, "radiocraft:block/dtmf")
    save(MODELS, "dtmf_decoder", b.model(decoder(b)))
    save(MODELS, "dtmf_decoder_red", b.model(lit(b, "red", RED, "red_on"), render_type="minecraft:cutout"))
    save(MODELS, "dtmf_decoder_green", b.model(lit(b, "green", GREEN, "green_on"), render_type="minecraft:cutout"))
    save(MODELS, "dtmf_encoder", b.model(encoder(b)))


if __name__ == "__main__":
    main()
