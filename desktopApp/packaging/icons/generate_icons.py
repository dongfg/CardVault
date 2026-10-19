"""Export the checked-in icon master to desktop formats; requires Pillow."""

from pathlib import Path

from PIL import Image


directory = Path(__file__).resolve().parent
with Image.open(directory / "cardvault-source.png") as source:
    master = source.convert("RGBA")
    master.save(directory / "cardvault.ico", sizes=[(n, n) for n in (16, 24, 32, 48, 64, 128, 256)])
    master.save(directory / "cardvault.icns")
    runtime = directory.parents[1] / "src/main/resources/icons/cardvault.png"
    runtime.parent.mkdir(parents=True, exist_ok=True)
    master.resize((256, 256), Image.Resampling.LANCZOS).save(runtime, optimize=True)
