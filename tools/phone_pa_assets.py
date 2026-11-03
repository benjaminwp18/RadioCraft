"""Writes the Phone PA's assets from the phone PABX's: its models and blockstate, and a texture in the same style, blue
grey instead of beige with a speaker grille on the front plate. Also its plain screen panel (see pabx_gui.py).

    python tools/phone_pa_assets.py
"""
import json
from pathlib import Path

from PIL import Image

from pabx_gui import panel

ASSETS = Path(__file__).resolve().parent.parent / "src/main/resources/assets/radiocraft"
GRILLE = (38, 44, 54, 255)


def tinted(r, g, b, a):
    """The beige case as blue grey: the same shading, cooler and paler in colour."""
    grey = (r + g + b) / 3
    return (int(grey * 0.72), int(grey * 0.8), int(grey * 0.92), a)


def texture(model):
    image = Image.open(ASSETS / "textures/block/pabx.png").convert("RGBA")
    px = image.load()
    scale = image.width // 16
    lamps_from = 14 * scale  # The bottom row holds the lamp's colours, on and off: left as they are.
    body = next(e for e in model["elements"] if e["name"] == "body")["faces"]["north"]["uv"]
    grille_top = round(body[3] * scale) + 3  # Below the part the case's other faces use, so only the front has it.
    for x in range(image.width):
        for y in range(lamps_from):
            if px[x, y][3]:
                px[x, y] = tinted(*px[x, y])
    for y in range(grille_top, lamps_from - 2, 3):  # The slots of a speaker grille, clear of the lamp.
        for x in range(5 * scale, 14 * scale):
            px[x, y] = GRILLE
    image.save(ASSETS / "textures/block/phone_pa.png")


def renamed(path, out):
    text = path.read_text(encoding="utf-8").replace("block/pabx", "block/phone_pa")
    out.write_text(text, encoding="utf-8")


def main():
    texture(json.loads((ASSETS / "models/block/pabx.json").read_text(encoding="utf-8")))
    renamed(ASSETS / "models/block/pabx.json", ASSETS / "models/block/phone_pa.json")
    renamed(ASSETS / "models/block/pabx_on.json", ASSETS / "models/block/phone_pa_on.json")
    renamed(ASSETS / "models/item/pabx.json", ASSETS / "models/item/phone_pa.json")
    renamed(ASSETS / "blockstates/pabx.json", ASSETS / "blockstates/phone_pa.json")
    panel(176, 138).save(ASSETS / "textures/gui/phone_pa.png")


if __name__ == "__main__":
    main()
