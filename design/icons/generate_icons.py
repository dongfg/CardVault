"""Export the checked-in icon master to desktop formats; requires Pillow."""

from pathlib import Path

from PIL import Image


directory = Path(__file__).resolve().parent
assets = directory.parents[1] / "src/CardVault/Assets"
assets.mkdir(parents=True, exist_ok=True)
with Image.open(directory / "cardvault-source.png") as source:
    master = source.convert("RGBA")
    master.save(assets / "cardvault.ico", sizes=[(n, n) for n in (16, 24, 32, 48, 64, 128, 256)])
    master.save(assets / "cardvault.icns")
    runtime = assets / "cardvault.png"
    master.resize((256, 256), Image.Resampling.LANCZOS).save(runtime, optimize=True)
