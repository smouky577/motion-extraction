"""Motion extraction.

Same mix as:

    split → negate → opacity 50% → delay → overlay

Static pixels cancel to mid-gray. Pixels that moved stay visible.
"""

from __future__ import annotations

import os
from collections import deque
from collections.abc import Callable, Sequence
from dataclasses import dataclass

import cv2
import numpy as np

SWAPS: dict[str, tuple[int, int, int] | None] = {
    "none": None,
    "rb": (2, 1, 0),
    "rg": (0, 2, 1),
    "gb": (1, 0, 2),
    "cycle_fwd": (2, 0, 1),
    "cycle_back": (1, 2, 0),
}


@dataclass(frozen=True)
class Settings:
    delay: int = 1
    off_r: int = 0
    off_g: int = 0
    off_b: int = 0
    blur: int = 0
    glow: bool = False
    swap: str = "none"
    span: int = 30


def channel_delays(
    delay: int,
    offset_b: int,
    offset_g: int,
    offset_r: int,
    limit: int,
) -> tuple[int, int, int]:
    """Delays for B, G and R, in frames, clamped to 0..limit."""
    limit = max(0, int(limit))
    base = int(delay)

    def one(offset: int) -> int:
        return max(0, min(limit, base + int(offset)))

    return one(offset_b), one(offset_g), one(offset_r)


def normalize(frame: np.ndarray) -> np.ndarray:
    if frame.ndim == 2:
        frame = cv2.cvtColor(frame, cv2.COLOR_GRAY2BGR)
    elif frame.shape[2] == 4:
        frame = cv2.cvtColor(frame, cv2.COLOR_BGRA2BGR)
    height, width = frame.shape[:2]
    even_h = height - (height % 2)
    even_w = width - (width % 2)
    if even_h != height or even_w != width:
        frame = frame[:even_h, :even_w]
    if not frame.flags["C_CONTIGUOUS"]:
        frame = np.ascontiguousarray(frame)
    return frame


def mix(current: np.ndarray, delayed: np.ndarray) -> np.ndarray:
    """50% overlay of the negated delayed frame on the current frame."""
    mixed = current.astype(np.uint16)
    mixed = mixed + np.uint16(255) - delayed.astype(np.uint16)
    return (mixed >> 1).astype(np.uint8)


def _blur(image: np.ndarray, sigma: float) -> np.ndarray:
    if sigma <= 0.05:
        return image
    return cv2.GaussianBlur(image, (0, 0), sigmaX=sigma, sigmaY=sigma)


def _frame_at(history: Sequence[np.ndarray], delay: int) -> np.ndarray:
    if delay <= 0:
        return history[-1]
    if delay >= len(history):
        return history[0]
    return history[-1 - delay]


def apply_glow(frame: np.ndarray) -> np.ndarray:
    """Bloom around pixels that are not the canceled gray."""
    base = frame.astype(np.float32)
    delta = base - 127.0
    energy = np.mean(np.abs(delta), axis=2).astype(np.float32)
    sigma = max(3.0, frame.shape[1] / 110.0)
    halo = cv2.GaussianBlur(energy, (0, 0), sigmaX=sigma, sigmaY=sigma)[..., None]
    tint = cv2.GaussianBlur(delta, (0, 0), sigmaX=sigma * 0.75, sigmaY=sigma * 0.75)
    gain = np.clip(halo / 12.0, 0.0, 3.0)
    core = np.clip(halo / 36.0, 0.0, 0.85)
    out = base + delta * core + tint * gain * 1.25
    return np.clip(out, 0, 255).astype(np.uint8)


def swap_channels(frame: np.ndarray, name: str) -> np.ndarray:
    order = SWAPS.get(name)
    if order is None:
        return frame
    return frame[:, :, order]


def extract(
    history: Sequence[np.ndarray],
    delay_b: int,
    delay_g: int,
    delay_r: int,
    blur_sigma: float,
    glow: bool,
    swap: str,
) -> np.ndarray:
    """Extract motion from a history whose last frame is the current one.

    Until enough past frames exist, the current frame is returned unchanged.
    """
    if len(history) == 0:
        raise ValueError("empty history")
    current = history[-1]
    need = max(int(delay_b), int(delay_g), int(delay_r))
    if len(history) <= need:
        return current.copy()

    if delay_b == delay_g == delay_r:
        delayed = _frame_at(history, delay_b)
        out = mix(_blur(current, blur_sigma), _blur(delayed, blur_sigma))
    else:
        current_ch = cv2.split(current)
        mixed = []
        for index, delay in enumerate((delay_b, delay_g, delay_r)):
            previous = np.ascontiguousarray(_frame_at(history, delay)[:, :, index])
            mixed.append(mix(_blur(current_ch[index], blur_sigma), _blur(previous, blur_sigma)))
        out = cv2.merge(mixed)

    out = swap_channels(out, swap)
    if glow:
        out = apply_glow(out)
    return out


def render_frame(history: Sequence[np.ndarray], settings: Settings) -> np.ndarray:
    span = max(2, int(settings.span))
    limit = span * 2
    base = min(max(0, int(settings.delay)), span)
    delay_b, delay_g, delay_r = channel_delays(
        base, settings.off_b, settings.off_g, settings.off_r, limit
    )
    width = history[-1].shape[1]
    sigma = 0.0 if settings.blur <= 0 else float(settings.blur) * (width / 960.0)
    return extract(history, delay_b, delay_g, delay_r, sigma, settings.glow, settings.swap)


def export_video(
    src: str,
    dst: str,
    settings: Settings,
    progress: Callable[[int, int], None] | None = None,
    cancel: Callable[[], bool] | None = None,
) -> int:
    """Write the whole clip with the current settings. Returns frames written."""
    capture = cv2.VideoCapture(src)
    if not capture.isOpened():
        raise RuntimeError("Не мога да отворя клипа за запис.")
    writer = None
    written = 0
    try:
        fps = float(capture.get(cv2.CAP_PROP_FPS) or 0)
        if fps < 1 or fps > 240:
            fps = 30.0
        total = int(capture.get(cv2.CAP_PROP_FRAME_COUNT) or 0)
        span = max(2, int(settings.span))
        history: deque[np.ndarray] = deque(maxlen=span * 2 + 3)
        while True:
            if cancel is not None and cancel():
                break
            ok, frame = capture.read()
            if not ok:
                break
            history.append(normalize(frame))
            out = render_frame(history, settings)
            if writer is None:
                height, width = out.shape[:2]
                ext = os.path.splitext(dst)[1].lower()
                code = "XVID" if ext == ".avi" else "mp4v"
                writer = cv2.VideoWriter(dst, cv2.VideoWriter_fourcc(*code), fps, (width, height))
                if not writer.isOpened():
                    raise RuntimeError("Не мога да създам изходния файл.")
            writer.write(out)
            written += 1
            if progress is not None and (written == 1 or written % 8 == 0 or written == total):
                progress(written, total)
        if progress is not None and written:
            progress(written, total or written)
    finally:
        capture.release()
        if writer is not None:
            writer.release()
    return written


def _self_test() -> None:
    still = np.full((8, 8, 3), 40, np.uint8)
    assert np.array_equal(extract([still], 1, 1, 1, 0, False, "none"), still)

    canceled = extract([still, still.copy()], 1, 1, 1, 0, False, "none")
    assert np.all(canceled == 127)

    noisy = np.arange(8 * 8 * 3, dtype=np.uint8).reshape(8, 8, 3)
    blurred = extract([noisy, noisy.copy()], 1, 1, 1, 3.0, True, "rb")
    assert np.all(blurred == 127)

    past = np.zeros((20, 20, 3), np.uint8)
    now = np.zeros((20, 20, 3), np.uint8)
    now[:, :] = (0, 0, 255)
    split = extract([past, now], 0, 0, 1, 0, False, "none")
    assert split[0, 0, 0] == 127
    assert split[0, 0, 1] == 127
    assert split[0, 0, 2] == 255

    swapped = swap_channels(now, "rb")
    assert swapped[0, 0, 0] == 255
    assert swapped[0, 0, 2] == 0

    sharp_past = np.zeros((31, 31, 3), np.uint8)
    sharp_now = np.zeros((31, 31, 3), np.uint8)
    sharp_now[15, 15] = 255
    sharp = extract([sharp_past, sharp_now], 1, 1, 1, 0, False, "none")
    soft = extract([sharp_past, sharp_now], 1, 1, 1, 2.5, False, "none")
    assert np.all(sharp[15, 14] == 127)
    assert int(soft[15, 14, 0]) != 127

    plate = np.full((80, 80, 3), 127, np.uint8)
    assert np.array_equal(apply_glow(plate), plate)
    plate[30:50, 30:50] = 255
    glowing = apply_glow(plate)
    assert abs(int(glowing[28, 40, 0]) - 127) > 2

    delays = channel_delays(10, -3, 4, 100, limit=20)
    assert delays == (7, 14, 20)

    history = deque(maxlen=8)
    block_past = np.full((90, 160, 3), 25, np.uint8)
    block_now = block_past.copy()
    cv2.rectangle(block_past, (20, 30), (50, 70), (240, 240, 240), -1)
    cv2.rectangle(block_now, (70, 30), (100, 70), (240, 240, 240), -1)
    history.append(block_past)
    history.append(block_now)
    effect = render_frame(history, Settings(delay=1, span=30))
    assert 120 <= int(effect[10, 10, 0]) <= 134
    assert int(effect[50, 85, 0]) > 200
    assert int(effect[50, 35, 0]) < 40

    glow_effect = render_frame(history, Settings(delay=1, span=30, glow=True, blur=2))
    assert int(glow_effect[50, 85, 0]) > int(effect[50, 85, 0]) - 5
    print("engine tests: ok")


if __name__ == "__main__":
    _self_test()
