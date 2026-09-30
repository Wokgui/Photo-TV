from __future__ import annotations

import base64
import json
import math
import sys
from pathlib import Path
from PIL import Image, ImageChops, ImageFilter, ImageOps, ImageEnhance

ROOT = Path(__file__).resolve().parent.parent
RESULTS = ROOT / "visual-results"
REFS = ROOT / "visual" / "reference"
NAMES = ["preview", "photos", "editor", "settings"]

def decode_reference(name: str) -> Image.Image:
    raw = base64.b64decode((REFS / f"{name}.jpg.b64").read_text().strip())
    path = RESULTS / f"{name}-reference.jpg"
    path.write_bytes(raw)
    return Image.open(path).convert("RGB")

def normalized_mae(a: Image.Image, b: Image.Image) -> float:
    diff = ImageChops.difference(a, b)
    hist = diff.histogram()
    total = a.width * a.height * len(a.getbands())
    value = sum((i % 256) * count for i, count in enumerate(hist))
    return value / (255.0 * total)

def edge_image(im: Image.Image) -> Image.Image:
    gray = ImageOps.grayscale(im)
    gray = ImageEnhance.Contrast(gray).enhance(1.4)
    return gray.filter(ImageFilter.FIND_EDGES)

def make_diff(ref: Image.Image, actual: Image.Image, name: str) -> None:
    diff = ImageChops.difference(ref, actual)
    diff = ImageEnhance.Contrast(diff).enhance(2.2)
    diff.resize((768, 512), Image.Resampling.NEAREST).save(RESULTS / f"{name}-diff.png")
    actual.resize((768, 512), Image.Resampling.LANCZOS).save(RESULTS / f"{name}-actual.jpg", quality=88)
    ref.resize((768, 512), Image.Resampling.LANCZOS).save(RESULTS / f"{name}-reference-large.jpg", quality=88)

scores = {}
for name in NAMES:
    ref = decode_reference(name).resize((96, 64), Image.Resampling.LANCZOS)
    actual_path = RESULTS / f"{name}.png"
    if not actual_path.exists():
        raise SystemExit(f"Missing screenshot: {actual_path}")
    actual = Image.open(actual_path).convert("RGB").resize((96, 64), Image.Resampling.LANCZOS)

    color_error = normalized_mae(ref, actual)
    edge_error = normalized_mae(edge_image(ref).convert("RGB"), edge_image(actual).convert("RGB"))
    # Edge structure is weighted more heavily than exact photo/color content.
    error = 0.32 * color_error + 0.68 * edge_error
    score = max(0.0, 100.0 * (1.0 - error))
    scores[name] = {
        "score": round(score, 2),
        "color_error": round(color_error, 4),
        "edge_error": round(edge_error, 4),
    }
    make_diff(ref, actual, name)
    print(f"{name:8s} score={score:6.2f} color={color_error:.4f} edge={edge_error:.4f}")

average = sum(v["score"] for v in scores.values()) / len(scores)
report = {"average_score": round(average, 2), "screens": scores}
(RESULTS / "report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
print(f"average visual score: {average:.2f}")

# This is intentionally a gross-regression gate. The detailed score/diff artifact is
# used to tighten the threshold after deliberate visual updates to the approved mockup.
if average < 38 or min(v["score"] for v in scores.values()) < 30:
    raise SystemExit("Visual regression is too large compared with the approved mockup.")
