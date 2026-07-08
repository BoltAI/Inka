#!/usr/bin/env python3
"""Local handwriting synthesis server for Inka prototypes.

The product path is the PyTorch toolkit engine. The standard-library
placeholder engine remains available for networking and replay smoke tests.
"""

from __future__ import annotations

import argparse
import hashlib
import importlib
import json
import math
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Callable, Optional


Stroke = dict[str, list[dict[str, Any]]]
Generator = Callable[[dict[str, Any]], list[Stroke]]

DEFAULT_STYLE = "default"
STYLE_PRIMER_TEXT = "we write "
STYLE_PRIMER_STEPS = 700


def stable_unit(*parts: object) -> float:
    digest = hashlib.sha256("|".join(str(part) for part in parts).encode("utf-8")).digest()
    value = int.from_bytes(digest[:4], "big")
    return value / 0xFFFFFFFF


def jitter(scale: float, *parts: object) -> float:
    return (stable_unit(*parts) - 0.5) * 2.0 * scale


GlyphTemplate = list[list[tuple[float, float]]]


GLYPH_TEMPLATES: dict[str, GlyphTemplate] = {
    "a": [[(0.78, 0.55), (0.58, 0.35), (0.30, 0.42), (0.18, 0.70), (0.34, 0.93), (0.66, 0.87), (0.77, 0.58), (0.78, 0.96), (1.00, 0.88)]],
    "b": [[(0.18, 0.08), (0.18, 0.96), (0.20, 0.70), (0.48, 0.46), (0.82, 0.60), (0.76, 0.86), (0.46, 0.96), (0.20, 0.82), (0.98, 0.88)]],
    "c": [[(0.82, 0.46), (0.52, 0.34), (0.22, 0.48), (0.16, 0.76), (0.42, 0.96), (0.86, 0.82)]],
    "d": [[(0.76, 0.06), (0.76, 0.96), (0.72, 0.58), (0.52, 0.36), (0.24, 0.46), (0.16, 0.74), (0.36, 0.94), (0.66, 0.86), (0.98, 0.88)]],
    "e": [[(0.20, 0.70), (0.78, 0.62), (0.66, 0.40), (0.34, 0.38), (0.16, 0.62), (0.26, 0.88), (0.58, 0.96), (0.90, 0.80)]],
    "f": [[(0.72, 0.10), (0.44, 0.06), (0.34, 0.34), (0.36, 1.16), (0.54, 1.28)], [(0.16, 0.48), (0.72, 0.44)]],
    "g": [[(0.78, 0.55), (0.58, 0.36), (0.30, 0.42), (0.16, 0.70), (0.34, 0.94), (0.68, 0.84), (0.78, 0.58), (0.72, 1.12), (0.44, 1.30), (0.22, 1.12)]],
    "h": [[(0.18, 0.08), (0.18, 0.96), (0.22, 0.62), (0.46, 0.42), (0.74, 0.48), (0.82, 0.92), (1.00, 0.88)]],
    "i": [[(0.36, 0.48), (0.40, 0.94), (0.62, 0.88)], [(0.42, 0.18), (0.48, 0.16), (0.46, 0.22)]],
    "j": [[(0.58, 0.48), (0.54, 1.08), (0.36, 1.28), (0.16, 1.12)], [(0.56, 0.18), (0.62, 0.16), (0.60, 0.22)]],
    "k": [[(0.18, 0.08), (0.18, 0.96)], [(0.78, 0.42), (0.22, 0.70), (0.86, 0.96)]],
    "l": [[(0.32, 0.08), (0.30, 0.84), (0.42, 0.98), (0.72, 0.88)]],
    "m": [[(0.12, 0.92), (0.16, 0.48), (0.38, 0.42), (0.48, 0.90), (0.58, 0.50), (0.80, 0.44), (0.92, 0.92), (1.00, 0.88)]],
    "n": [[(0.14, 0.92), (0.18, 0.48), (0.48, 0.42), (0.76, 0.92), (0.98, 0.88)]],
    "o": [[(0.78, 0.62), (0.66, 0.42), (0.40, 0.36), (0.16, 0.52), (0.14, 0.76), (0.34, 0.96), (0.64, 0.90), (0.82, 0.68), (0.78, 0.62)]],
    "p": [[(0.18, 1.26), (0.18, 0.48), (0.46, 0.38), (0.78, 0.56), (0.68, 0.82), (0.36, 0.90), (0.18, 0.76), (1.00, 0.88)]],
    "q": [[(0.76, 0.54), (0.54, 0.36), (0.26, 0.44), (0.16, 0.72), (0.36, 0.94), (0.68, 0.86), (0.76, 0.58), (0.76, 1.24), (0.98, 1.10)]],
    "r": [[(0.18, 0.92), (0.20, 0.50), (0.42, 0.42), (0.68, 0.50), (0.88, 0.44)]],
    "s": [[(0.82, 0.46), (0.54, 0.36), (0.24, 0.48), (0.44, 0.68), (0.76, 0.76), (0.62, 0.96), (0.24, 0.90)]],
    "t": [[(0.46, 0.18), (0.42, 0.86), (0.54, 0.98), (0.82, 0.86)], [(0.18, 0.48), (0.76, 0.44)]],
    "u": [[(0.16, 0.50), (0.18, 0.84), (0.42, 0.96), (0.70, 0.82), (0.78, 0.50), (0.82, 0.94), (1.00, 0.88)]],
    "v": [[(0.14, 0.50), (0.42, 0.96), (0.78, 0.50), (0.98, 0.88)]],
    "w": [[(0.10, 0.52), (0.30, 0.96), (0.48, 0.56), (0.66, 0.96), (0.90, 0.52), (1.00, 0.88)]],
    "x": [[(0.18, 0.48), (0.82, 0.94)], [(0.78, 0.48), (0.18, 0.94)]],
    "y": [[(0.14, 0.50), (0.42, 0.94), (0.78, 0.50), (0.58, 1.12), (0.28, 1.30), (0.14, 1.12)]],
    "z": [[(0.20, 0.48), (0.78, 0.46), (0.24, 0.94), (0.86, 0.90)]],
    "0": [[(0.70, 0.20), (0.32, 0.24), (0.18, 0.58), (0.30, 0.94), (0.72, 0.90), (0.86, 0.52), (0.70, 0.20)]],
    "1": [[(0.44, 0.30), (0.58, 0.18), (0.56, 0.94), (0.34, 0.94), (0.82, 0.94)]],
    "2": [[(0.22, 0.38), (0.48, 0.20), (0.78, 0.34), (0.70, 0.62), (0.24, 0.94), (0.84, 0.92)]],
    "3": [[(0.22, 0.32), (0.70, 0.28), (0.46, 0.58), (0.76, 0.76), (0.42, 0.98), (0.18, 0.84)]],
    "4": [[(0.72, 0.20), (0.22, 0.70), (0.86, 0.70)], [(0.72, 0.20), (0.70, 0.96)]],
    "5": [[(0.78, 0.24), (0.30, 0.24), (0.24, 0.56), (0.70, 0.62), (0.76, 0.90), (0.34, 0.98)]],
    "6": [[(0.76, 0.30), (0.34, 0.42), (0.20, 0.78), (0.46, 0.98), (0.78, 0.84), (0.66, 0.62), (0.28, 0.68)]],
    "7": [[(0.20, 0.26), (0.84, 0.26), (0.44, 0.96)]],
    "8": [[(0.52, 0.20), (0.24, 0.40), (0.50, 0.58), (0.78, 0.40), (0.52, 0.20), (0.28, 0.76), (0.52, 0.98), (0.78, 0.78), (0.50, 0.58)]],
    "9": [[(0.76, 0.58), (0.48, 0.42), (0.22, 0.58), (0.36, 0.84), (0.72, 0.76), (0.76, 0.30), (0.36, 0.98)]],
    ".": [[(0.44, 0.94), (0.50, 0.96), (0.48, 1.00)]],
    ",": [[(0.48, 0.92), (0.54, 0.98), (0.38, 1.14)]],
    ";": [[(0.46, 0.48), (0.52, 0.50), (0.50, 0.54)], [(0.48, 0.92), (0.54, 0.98), (0.38, 1.14)]],
    ":": [[(0.46, 0.48), (0.52, 0.50), (0.50, 0.54)], [(0.46, 0.92), (0.52, 0.94), (0.50, 0.98)]],
    "'": [[(0.46, 0.18), (0.40, 0.34)]],
    '"': [[(0.34, 0.18), (0.30, 0.34)], [(0.62, 0.18), (0.58, 0.34)]],
    "!": [[(0.48, 0.20), (0.46, 0.74)], [(0.46, 0.94), (0.52, 0.96), (0.50, 1.00)]],
    "?": [[(0.24, 0.38), (0.52, 0.20), (0.78, 0.38), (0.54, 0.62), (0.50, 0.76)], [(0.48, 0.94), (0.54, 0.96), (0.52, 1.00)]],
    "-": [[(0.22, 0.68), (0.80, 0.66)]],
    "(": [[(0.68, 0.16), (0.36, 0.52), (0.66, 1.02)]],
    ")": [[(0.34, 0.16), (0.66, 0.52), (0.34, 1.02)]],
    "H": [[(0.16, 0.08), (0.16, 0.96)], [(0.82, 0.08), (0.82, 0.96)], [(0.18, 0.52), (0.78, 0.50)]],
}


def stroke_for_char(
    char: str,
    char_index: int,
    x: float,
    baseline: float,
    width: float,
    height: float,
    start_time: int,
) -> tuple[list[Stroke], int]:
    template = GLYPH_TEMPLATES.get(char, GLYPH_TEMPLATES.get(char.lower(), GLYPH_TEMPLATES["?"]))
    stroke_width = width * (0.94 + stable_unit(char, char_index, "width") * 0.12)
    stroke_height = height * (0.95 + stable_unit(char, char_index, "height") * 0.10)
    slant = jitter(0.10, char, char_index, "slant")
    baseline_shift = jitter(1.4, char, char_index, "baseline")
    strokes: list[Stroke] = []
    current_time = start_time

    for stroke_index, control_points in enumerate(template):
        points = []
        for segment_index in range(max(1, len(control_points) - 1)):
            x1, y1 = control_points[segment_index]
            x2, y2 = control_points[min(segment_index + 1, len(control_points) - 1)]
            sample_count = 4
            for sample_index in range(sample_count):
                if segment_index > 0 and sample_index == 0:
                    continue
                t = sample_index / float(sample_count - 1)
                ease = t * t * (3.0 - 2.0 * t)
                gx = x1 + (x2 - x1) * ease
                gy = y1 + (y2 - y1) * ease
                wobble = math.sin((segment_index + t) * math.pi) * jitter(0.018, char, char_index, stroke_index, segment_index, "wobble")
                px = x + stroke_width * (gx + wobble) + slant * stroke_height * (1.0 - gy)
                py = baseline + baseline_shift - stroke_height * (1.0 - gy)
                px += jitter(0.55, char, char_index, stroke_index, segment_index, sample_index, "x")
                py += jitter(0.75, char, char_index, stroke_index, segment_index, sample_index, "y")
                points.append(
                    {
                        "x": px,
                        "y": py,
                        "pressure": 0.44 + stable_unit(char, char_index, stroke_index, segment_index, sample_index, "pressure") * 0.30,
                        "t": current_time,
                    }
                )
                current_time += 11
        if points:
            strokes.append({"points": points})
            current_time += 34

    return strokes, current_time


def generate_placeholder_strokes(payload: dict[str, Any]) -> list[Stroke]:
    text = str(payload.get("text", "")).strip()
    page_width = int(payload.get("pageWidth", 1404))
    page_height = int(payload.get("pageHeight", 1872))
    left = float(payload.get("left", 96))
    top = float(payload.get("top", 180))
    max_width = max(120.0, float(payload.get("maxWidth", page_width - left * 2)))
    font_size_sp = coerce_float(payload.get("fontSizeSp", 40.0), default=40.0, minimum=28.0, maximum=110.0)
    glyph_height = font_size_sp * 1.36
    metric_scale = glyph_height / 38.0
    left_padding = font_size_sp * 0.42

    baseline = top + font_size_sp * 1.34
    line_left = left + left_padding
    cursor_x = line_left
    line_height = font_size_sp * 2.08
    max_x = min(page_width - left_padding, left + max_width - left_padding)
    strokes: list[Stroke] = []
    current_time = 0

    char_index = 0
    for paragraph_index, paragraph in enumerate(text[:700].split("\n")):
        if paragraph_index > 0:
            cursor_x = line_left
            baseline += line_height
        for word in paragraph.split():
            space_width = (15.0 + jitter(2.0, char_index, "space")) * metric_scale
            word_width = sum(placeholder_char_width(char, metric_scale) + 2.0 * metric_scale for char in word)
            if cursor_x > line_left and cursor_x + space_width + word_width > max_x:
                cursor_x = line_left
                baseline += line_height
            elif cursor_x > line_left:
                cursor_x += space_width
            if baseline > page_height - 60:
                return strokes

            for char in word:
                char_width = placeholder_char_width(char, metric_scale)
                if cursor_x + char_width > max_x:
                    cursor_x = line_left
                    baseline += line_height
                if baseline > page_height - 60:
                    return strokes

                char_strokes, current_time = stroke_for_char(
                    char=char,
                    char_index=char_index,
                    x=cursor_x,
                    baseline=baseline,
                    width=char_width,
                    height=glyph_height * (1.12 if char.isupper() else 1.0),
                    start_time=current_time,
                )
                strokes.extend(char_strokes)
                cursor_x += char_width + 2.0 * metric_scale + jitter(1.4 * metric_scale, char, char_index, "advance")
                char_index += 1

    return strokes


def coerce_float(value: object, default: float, minimum: float, maximum: float) -> float:
    try:
        parsed = float(value)
    except (TypeError, ValueError):
        return default
    if not math.isfinite(parsed):
        return default
    return min(max(parsed, minimum), maximum)


def coerce_seed(value: object) -> Optional[int]:
    if value is None or value == "":
        return None
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        return None
    if parsed <= 0:
        return None
    return parsed % 2_147_483_647


def placeholder_char_width(char: str, scale: float = 1.0) -> float:
    if char in "ilI1.,;:'\"!":
        char_width = 11.0 + stable_unit(char, "width") * 4.0
    elif char in "mwMW":
        char_width = 26.0 + stable_unit(char, "width") * 5.0
    else:
        char_width = 18.0 + stable_unit(char, "width") * 5.0
    if char.isupper():
        char_width *= 1.18
    return char_width * scale


class PytorchToolkitEngine:
    def __init__(
        self,
        toolkit_dir: str,
        model_dir: str,
        bias: float,
        steps: int,
        device_name: str,
        sample_attempts: int,
    ) -> None:
        if not toolkit_dir:
            raise RuntimeError("--toolkit-dir is required for --engine pytorch")
        if not model_dir:
            raise RuntimeError("--model-dir is required for --engine pytorch")

        sys.path.insert(0, toolkit_dir)
        torch = importlib.import_module("torch")
        sampling = importlib.import_module("handwriting_synthesis.sampling")
        self._split_into_components = importlib.import_module("handwriting_synthesis.utils").split_into_components
        self._get_strokes = importlib.import_module("handwriting_synthesis.utils").get_strokes

        self.torch = torch
        device = torch.device(device_name)
        synthesizer_cls = sampling.HandwritingSynthesizer
        self.synthesizer = synthesizer_cls.load(model_dir, device, bias)
        self.synthesizer.num_steps = steps
        self.sample_attempts = max(1, sample_attempts)
        self._style_primers: dict[str, tuple[Any, Any]] = {}
        self._sample_lock = threading.RLock()

    def __call__(self, payload: dict[str, Any]) -> list[Stroke]:
        text = sanitize_model_text(str(payload.get("text", "")))
        if not text:
            return []

        page_width = int(payload.get("pageWidth", 1404))
        page_height = int(payload.get("pageHeight", 1872))
        left = float(payload.get("left", 96))
        top = float(payload.get("top", 180))
        max_width = max(120.0, float(payload.get("maxWidth", page_width - left * 2)))
        font_size_sp = coerce_float(payload.get("fontSizeSp", 56.0), default=56.0, minimum=28.0, maximum=110.0)
        style_key = sanitize_style_key(str(payload.get("style", DEFAULT_STYLE)))
        seed = coerce_seed(payload.get("seed"))
        if seed is not None and style_key == DEFAULT_STYLE:
            style_key = sanitize_style_key(f"seed-{seed}")

        with self._sample_lock:
            style_primer = self._style_primer(style_key, seed)
            return self._sample_lines_into_page(
                text=text,
                left=left,
                top=top,
                max_width=max_width,
                page_width=page_width,
                page_height=page_height,
                font_size_sp=font_size_sp,
                style_primer=style_primer,
            )

    def _sample_lines_into_page(
        self,
        text: str,
        left: float,
        top: float,
        max_width: float,
        page_width: int,
        page_height: int,
        font_size_sp: float,
        style_primer: tuple[Any, Any],
    ) -> list[Stroke]:
        strokes: list[Stroke] = []
        line_top = top
        line_height = font_size_sp * 2.10
        top_padding = font_size_sp * 0.12
        for line in wrap_text_for_width(text, max_width=max_width, font_size_sp=font_size_sp):
            if line_top > page_height - line_height:
                break
            target_width = min(max_width, estimate_model_line_width(line, font_size_sp))
            line_strokes = self._sample_normalized_line(
                line=line,
                left=left,
                top=line_top + top_padding,
                target_width=target_width,
                page_width=page_width,
                page_height=page_height,
                font_size_sp=font_size_sp,
                style_primer=style_primer,
            )
            if not line_strokes:
                return []
            strokes.extend(line_strokes)
            line_top += line_height
        return strokes

    def _sample_normalized_line(
        self,
        line: str,
        left: float,
        top: float,
        target_width: float,
        page_width: int,
        page_height: int,
        font_size_sp: float,
        style_primer: tuple[Any, Any],
    ) -> list[Stroke]:
        best_strokes: list[Stroke] = []
        best_score = -1.0
        for _ in range(self.sample_attempts):
            raw_strokes = self._sample_primed_line(with_model_sentinel(line), style_primer)
            strokes = normalize_strokes(
                raw_strokes=raw_strokes,
                left=left,
                top=top,
                target_width=target_width,
                page_width=page_width,
                page_height=page_height,
            )
            score = stroke_quality_score(strokes, target_width=target_width, font_size_sp=font_size_sp)
            if score > best_score:
                best_score = score
                best_strokes = strokes
            if line_sample_is_usable(
                strokes,
                target_width=target_width,
                font_size_sp=font_size_sp,
                step_limit=self.synthesizer.num_steps,
            ):
                return strokes
        if line_sample_is_usable(
            best_strokes,
            target_width=target_width,
            font_size_sp=font_size_sp,
            step_limit=self.synthesizer.num_steps,
        ):
            return best_strokes
        return []

    def _style_primer(self, style_key: str, seed: Optional[int]) -> tuple[Any, Any]:
        cache_key = style_key if seed is None else f"{style_key}-{seed}"
        cached = self._style_primers.get(cache_key)
        if cached is not None:
            return cached

        context = self.synthesizer._encode_text(STYLE_PRIMER_TEXT)
        if seed is None:
            priming = self.synthesizer.model.sample_means(
                context=context,
                steps=STYLE_PRIMER_STEPS,
                stochastic=True,
            ).unsqueeze(0)
        else:
            with self.torch.random.fork_rng(devices=[]):
                self.torch.manual_seed(seed)
                priming = self.synthesizer.model.sample_means(
                    context=context,
                    steps=STYLE_PRIMER_STEPS,
                    stochastic=True,
                ).unsqueeze(0)
        primer = (priming, context)
        self._style_primers[cache_key] = primer
        return primer

    def _sample_primed_line(self, text: str, style_primer: tuple[Any, Any]) -> list[list[tuple[float, float]]]:
        priming, priming_context = style_primer
        line_context = self.synthesizer._encode_text(text)
        sampled = self.synthesizer.model.sample_primed(
            priming,
            priming_context,
            line_context,
            steps=self.synthesizer.num_steps,
        )
        sampled = sampled.cpu()
        sampled = self.synthesizer._undo_normalization(sampled)
        raw_x, raw_y, eos = self._split_into_components(sampled)
        return list(self._get_strokes(raw_x, raw_y, eos))

    def _sample_line(self, text: str) -> list[list[tuple[float, float]]]:
        context = self.synthesizer._encode_text(text)
        sampled = self.synthesizer.model.sample_means(
            context=context,
            steps=self.synthesizer.num_steps,
            stochastic=True,
        )
        sampled = sampled.cpu()
        sampled = self.synthesizer._undo_normalization(sampled)
        raw_x, raw_y, eos = self._split_into_components(sampled)
        return list(self._get_strokes(raw_x, raw_y, eos))


def sanitize_model_text(text: str) -> str:
    cleaned = " ".join(text.strip().split())
    # The pretrained toolkit model is IAM-style Latin handwriting. Keeping the
    # first pass conservative avoids model failures on unsupported symbols.
    allowed = set("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 .,;:'\"!?()-")
    return "".join(char if char in allowed else " " for char in cleaned)[:280]


def sanitize_style_key(style: str) -> str:
    cleaned = "".join(char for char in style.strip().lower() if char.isalnum() or char in "-_")
    return cleaned[:48] or DEFAULT_STYLE


def estimate_token_width(token: str) -> float:
    width = 0.0
    for char in token:
        if char.isspace():
            width += 16.0
        elif char in ".,;:'\"!?()-":
            width += 9.0
        elif char.isupper():
            width += 30.0
        elif char in "mwMW":
            width += 31.0
        elif char in "ilI1":
            width += 13.0
        else:
            width += 23.0
    return max(24.0, min(width, 1500.0))


def estimate_model_line_width(line: str, font_size_sp: float) -> float:
    return estimate_token_width(line) * (font_size_sp / 56.0)


def wrap_text_for_width(text: str, max_width: float, font_size_sp: float) -> list[str]:
    words = text.split()
    lines: list[str] = []
    current = ""
    max_chars = max(32, min(48, int(max_width / max(font_size_sp * 0.34, 1.0))))
    max_line_width = max_width * 0.96
    for word in words:
        candidate = word if not current else f"{current} {word}"
        if len(candidate) <= max_chars and estimate_model_line_width(candidate, font_size_sp) <= max_line_width:
            current = candidate
            continue
        if current:
            lines.append(current)
        while len(word) > max_chars:
            lines.append(word[:max_chars])
            word = word[max_chars:]
        current = word
    if current:
        lines.append(current)
    return rebalance_short_final_line(lines, max_width=max_width, font_size_sp=font_size_sp)


def rebalance_short_final_line(lines: list[str], max_width: float, font_size_sp: float) -> list[str]:
    if len(lines) < 2:
        return lines

    balanced = lines[:]
    min_final_width = max_width * 0.34
    max_final_width = max_width * 0.72
    min_previous_width = max_width * 0.45

    while True:
        previous_words = balanced[-2].split()
        final_words = balanced[-1].split()
        if len(previous_words) <= 2:
            break
        if estimate_model_line_width(" ".join(final_words), font_size_sp) >= min_final_width:
            break

        candidate_previous = " ".join(previous_words[:-1])
        candidate_final = " ".join([previous_words[-1], *final_words])
        if estimate_model_line_width(candidate_previous, font_size_sp) < min_previous_width:
            break
        if estimate_model_line_width(candidate_final, font_size_sp) > max_final_width:
            break

        balanced[-2] = candidate_previous
        balanced[-1] = candidate_final

    return balanced


def with_model_sentinel(line: str) -> str:
    return line if line.endswith(" ") else f"{line} "


def wrap_text(text: str, max_chars: int) -> list[str]:
    words = text.split()
    lines: list[str] = []
    current = ""
    for word in words:
        candidate = word if not current else f"{current} {word}"
        if len(candidate) <= max_chars:
            current = candidate
            continue
        if current:
            lines.append(current)
        while len(word) > max_chars:
            lines.append(word[:max_chars])
            word = word[max_chars:]
        current = word
    if current:
        lines.append(current)
    return lines


def normalize_strokes(
    raw_strokes: list[list[tuple[float, float]]],
    left: float,
    top: float,
    target_width: float,
    page_width: int,
    page_height: int,
) -> list[Stroke]:
    points = [(float(x), float(y)) for stroke in raw_strokes for x, y in stroke]
    if not points:
        return []
    min_x = min(x for x, _ in points)
    max_x = max(x for x, _ in points)
    min_y = min(y for _, y in points)
    max_y = max(y for _, y in points)
    raw_width = max(max_x - min_x, 1.0)
    scale = target_width / raw_width
    if not math.isfinite(scale) or scale <= 0:
        return []

    strokes: list[Stroke] = []
    current_time = 0
    for stroke_index, stroke in enumerate(raw_strokes):
        converted = []
        for point_index, (x, y) in enumerate(stroke):
            px = left + (float(x) - min_x) * scale
            py = top + (float(y) - min_y) * scale
            if not math.isfinite(px) or not math.isfinite(py):
                continue
            converted.append(
                {
                    "x": max(0.0, min(float(page_width), px)),
                    "y": max(0.0, min(float(page_height), py)),
                    "pressure": 0.48 + stable_unit("model-pressure", stroke_index, point_index) * 0.24,
                    "t": current_time,
                }
            )
            current_time += 12
        if converted:
            strokes.append({"points": converted})
            current_time += 48
    return strokes


def stroke_quality_score(strokes: list[Stroke], target_width: float, font_size_sp: float) -> float:
    bounds = stroke_payload_bounds(strokes)
    if bounds is None:
        return -1.0
    min_x, min_y, max_x, max_y, point_count = bounds
    visual_width = max_x - min_x
    visual_height = max_y - min_y
    ideal_height = font_size_sp * 1.35
    height_penalty = abs(visual_height - ideal_height) * 3.0
    width_penalty = abs(visual_width - target_width) * 0.25
    return (
        visual_width
        + min(visual_height, ideal_height * 1.3) * 2.0
        + min(len(strokes), 40) * 8.0
        + min(point_count, 1200) * 0.02
        - height_penalty
        - width_penalty
    )


def line_sample_is_usable(
    strokes: list[Stroke],
    target_width: float,
    font_size_sp: float,
    step_limit: int,
) -> bool:
    bounds = stroke_payload_bounds(strokes)
    if bounds is None:
        return False
    if has_bad_runaway_stroke(strokes, target_width=target_width, font_size_sp=font_size_sp):
        return False
    min_x, min_y, max_x, max_y, point_count = bounds
    visual_width = max_x - min_x
    visual_height = max_y - min_y
    min_width = min(target_width * 0.72, max(24.0, target_width * 0.28))
    return (
        point_count >= 48
        and visual_width >= min_width
        and visual_height >= font_size_sp * 0.28
        and visual_height <= font_size_sp * 2.20
    )


def has_bad_runaway_stroke(strokes: list[Stroke], target_width: float, font_size_sp: float) -> bool:
    for stroke in strokes:
        points = stroke.get("points", [])
        if len(points) < 8:
            continue
        xs = [float(point["x"]) for point in points]
        ys = [float(point["y"]) for point in points]
        visual_width = max(xs) - min(xs)
        visual_height = max(ys) - min(ys)
        if visual_width > max(160.0, target_width * 0.42) and visual_height < font_size_sp * 0.34:
            return True
        if visual_height > font_size_sp * 2.20 and visual_width < max(64.0, target_width * 0.24):
            return True
        if visual_height > font_size_sp * 2.80 and visual_width > target_width * 0.28:
            return True
    return False


def stroke_payload_bounds(strokes: list[Stroke]) -> Optional[tuple[float, float, float, float, int]]:
    min_x = math.inf
    min_y = math.inf
    max_x = -math.inf
    max_y = -math.inf
    point_count = 0
    for stroke in strokes:
        for point in stroke.get("points", []):
            x = float(point["x"])
            y = float(point["y"])
            min_x = min(min_x, x)
            min_y = min(min_y, y)
            max_x = max(max_x, x)
            max_y = max(max_y, y)
            point_count += 1
    if point_count == 0:
        return None
    return min_x, min_y, max_x, max_y, point_count


class Handler(BaseHTTPRequestHandler):
    server_version = "InkaHandwritingServer/0.1"
    generator: Generator = generate_placeholder_strokes

    def do_GET(self) -> None:
        if self.path == "/health":
            self.send_json({"ok": True})
            return
        self.send_error(404)

    def do_POST(self) -> None:
        if self.path != "/handwriting":
            self.send_error(404)
            return
        started_at = time.perf_counter()
        try:
            length = int(self.headers.get("content-length", "0"))
            payload = json.loads(self.rfile.read(length).decode("utf-8"))
            strokes = type(self).generator(payload)
        except Exception as exc:
            self.send_json({"error": exc.__class__.__name__}, status=400)
            elapsed_ms = int((time.perf_counter() - started_at) * 1000)
            print(f"POST /handwriting failed error={exc.__class__.__name__} elapsed_ms={elapsed_ms}", flush=True)
            return
        self.send_json({"strokes": strokes})
        elapsed_ms = int((time.perf_counter() - started_at) * 1000)
        point_count = sum(len(stroke.get("points", [])) for stroke in strokes)
        print(
            f"POST /handwriting generated strokes={len(strokes)} points={point_count} elapsed_ms={elapsed_ms}",
            flush=True,
        )

    def send_json(self, payload: dict[str, Any], status: int = 200) -> None:
        body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        try:
            self.send_response(status)
            self.send_header("content-type", "application/json")
            self.send_header("content-length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def log_message(self, format: str, *args: object) -> None:
        print("%s - %s" % (self.address_string(), format % args))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", default=8787, type=int)
    parser.add_argument("--engine", choices=("placeholder", "pytorch"), default="placeholder")
    parser.add_argument("--toolkit-dir", default="")
    parser.add_argument("--model-dir", default="")
    parser.add_argument("--bias", default=0.8, type=float)
    parser.add_argument("--steps", default=1500, type=int)
    parser.add_argument("--device", default="cpu")
    parser.add_argument("--sample-attempts", default=8, type=int)
    args = parser.parse_args()

    if args.engine == "pytorch":
        Handler.generator = PytorchToolkitEngine(
            toolkit_dir=args.toolkit_dir,
            model_dir=args.model_dir,
            bias=args.bias,
            steps=args.steps,
            device_name=args.device,
            sample_attempts=args.sample_attempts,
        )
    else:
        Handler.generator = generate_placeholder_strokes

    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"Listening on http://{args.host}:{args.port} using {args.engine} engine")
    server.serve_forever()


if __name__ == "__main__":
    main()
