"""Writes the Repeater Autopatch Controller's assets from the phone PABX's: its models and blockstate, and the PABX's
texture recoloured dark green (a placeholder until it gets its own art, see doc/ModelDesignBounty.md). Also its plain
screen panel (see pabx_gui.py).

    python tools/repeater_autopatch_assets.py
"""
import colorsys
from pathlib import Path

from PIL import Image

from pabx_gui import panel

ASSETS = Path(__file__).resolve().parent.parent / "src/main/resources/assets/radiocraft"


def tinted(r, g, b, a):
    """The beige case as dark green: the same shading, darker and a little more saturated."""
    h, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
    r2, g2, b2 = colorsys.hsv_to_rgb(0.38, min(1.0, s * 1.1 + 0.12), v * 0.46)
    return (round(r2 * 255), round(g2 * 255), round(b2 * 255), a)


def texture():
    image = Image.open(ASSETS / "textures/block/pabx.png").convert("RGBA")
    px = image.load()
    lamps_from = 14 * (image.width // 16)  # The bottom row holds the lamp's colours, on and off: left as they are.
    for x in range(image.width):
        for y in range(lamps_from):
            if px[x, y][3]:
                px[x, y] = tinted(*px[x, y])
    image.save(ASSETS / "textures/block/repeater_autopatch.png")


def renamed(path, out):
    text = path.read_text(encoding="utf-8").replace("block/pabx", "block/repeater_autopatch")
    out.write_text(text, encoding="utf-8")


def main():
    texture()
    renamed(ASSETS / "models/block/pabx.json", ASSETS / "models/block/repeater_autopatch.json")
    renamed(ASSETS / "models/block/pabx_on.json", ASSETS / "models/block/repeater_autopatch_on.json")
    renamed(ASSETS / "models/item/pabx.json", ASSETS / "models/item/repeater_autopatch.json")
    renamed(ASSETS / "blockstates/pabx.json", ASSETS / "blockstates/repeater_autopatch.json")
    panel(176, 166).save(ASSETS / "textures/gui/repeater_autopatch.png")


if __name__ == "__main__":
    main()
