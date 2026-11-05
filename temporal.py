"""Temporal FFT for a whole clip.

Same filter as temporal_fft.py: one real FFT along time, per pixel and
color channel. Static light becomes gray in the highpass. Slow change stays
in the lowpass.
"""

from __future__ import annotations

import os

import cv2
import numpy as np


def load_frames(path: str, max_width: int | None, max_seconds: float | None, progress=None, cancel=None):
    capture = cv2.VideoCapture(path)
    if not capture.isOpened():
        raise RuntimeError("Не мога да отворя клипа.")
    fps = float(capture.get(cv2.CAP_PROP_FPS) or 0)
    if fps < 1 or fps > 240:
        fps = 30.0
    total = int(capture.get(cv2.CAP_PROP_FRAME_COUNT) or 0)
    limit = None if max_seconds is None else max(4, int(max_seconds * fps))
    frames = []
    while True:
        if cancel is not None and cancel():
            break
        if limit is not None and len(frames) >= limit:
            break
        ok, frame = capture.read()
        if not ok:
            break
        frame = _fit_width(frame, max_width)
        frames.append(frame)
        if progress is not None and (len(frames) == 1 or len(frames) % 8 == 0):
            progress(len(frames), total)
    capture.release()
    if cancel is not None and cancel():
        raise RuntimeError("Спряно.")
    if len(frames) < 4:
        raise RuntimeError("Твърде малко кадри.")
    return np.stack(frames).astype(np.float32), fps


def _fit_width(frame: np.ndarray, max_width: int | None) -> np.ndarray:
    height, width = frame.shape[:2]
    if max_width is not None and width > max_width:
        new_h = int(round(height * max_width / width / 2) * 2)
        new_h = max(2, new_h)
        frame = cv2.resize(frame, (max_width - max_width % 2, new_h), interpolation=cv2.INTER_AREA)
        height, width = frame.shape[:2]
    even_h = height - height % 2
    even_w = width - width % 2
    if even_h != height or even_w != width:
        frame = frame[:even_h, :even_w]
    return frame


def filter_clip(
    video: np.ndarray,
    fps: float,
    mode: str,
    cutoff: float,
    low: float,
    high: float,
    gain: float,
) -> np.ndarray:
    count = video.shape[0]
    spectrum = np.fft.rfft(video, axis=0)
    freqs = np.fft.rfftfreq(count, d=1.0 / fps)
    if mode == "spectrum":
        mag = np.log1p(np.abs(spectrum))
        ref = np.percentile(mag[1:], 99.5) if mag.shape[0] > 1 else float(mag.max())
        if ref <= 1e-8:
            ref = 1.0
        return (np.clip(mag / ref, 0, 1) * 255).astype(np.uint8)

    if mode == "highpass":
        mask = freqs >= cutoff
    elif mode == "lowpass":
        mask = freqs <= cutoff
    else:
        mask = (freqs >= low) & (freqs <= high)
    spectrum *= mask[:, None, None, None]
    result = np.fft.irfft(spectrum, n=count, axis=0)
    if mode == "lowpass":
        out = np.clip(result, 0, 255)
    else:
        out = np.clip(128 + gain * result, 0, 255)
    return out.astype(np.uint8)


def write_video(path: str, frames: np.ndarray, fps: float, progress=None, cancel=None) -> None:
    count, height, width, _ = frames.shape
    ext = os.path.splitext(path)[1].lower()
    code = "XVID" if ext == ".avi" else "mp4v"
    writer = cv2.VideoWriter(path, cv2.VideoWriter_fourcc(*code), float(fps), (width, height))
    if not writer.isOpened():
        raise RuntimeError("Не мога да създам изходния файл.")
    try:
        for index, frame in enumerate(frames):
            if cancel is not None and cancel():
                raise RuntimeError("Спряно.")
            writer.write(frame)
            if progress is not None and (index == 0 or index % 8 == 0 or index + 1 == count):
                progress(index + 1, count)
    finally:
        writer.release()


def _self_test() -> None:
    count = 32
    fps = float(count)
    sine = 40 + 3 * np.sin(2 * np.pi * 2 * np.arange(count) / count)
    video = np.broadcast_to(sine[:, None, None, None], (count, 2, 2, 3)).astype(np.float32).copy()
    fast = filter_clip(video, fps, "highpass", 1.5, 0, 0, 2).astype(np.float32)
    expect = 128 + 6 * np.sin(2 * np.pi * 2 * np.arange(count) / count)
    assert np.max(np.abs(fast - expect[:, None, None, None])) < 2

    slow = filter_clip(video, fps, "lowpass", 0.5, 0, 0, 1)
    assert np.all(np.abs(slow.astype(np.int16) - 40) <= 1)

    band = filter_clip(video, fps, "bandpass", 0, 1.5, 2.5, 1).astype(np.float32)
    band_expect = 128 + 3 * np.sin(2 * np.pi * 2 * np.arange(count) / count)
    assert np.max(np.abs(band - band_expect[:, None, None, None])) < 2

    spec = filter_clip(video, fps, "spectrum", 0, 0, 0, 1)
    assert spec.shape[0] == count // 2 + 1
    assert spec.max() > 200

    import tempfile

    folder = tempfile.mkdtemp()
    sample = os.path.join(folder, "in.mp4")
    out = os.path.join(folder, "out.mp4")
    blank = np.zeros((12, 48, 64, 3), np.uint8)
    blank[:, :, :] = (20, 40, 80)
    for index in range(12):
        blank[index, 10:30, 8 + index : 20 + index] = 220
    write_video(sample, blank, 24)
    loaded, loaded_fps = load_frames(sample, 32, None)
    assert loaded.shape[2] == 32
    assert abs(loaded_fps - 24) < 0.2
    filtered = filter_clip(loaded, loaded_fps, "highpass", 1, 0, 0, 3)
    write_video(out, filtered, loaded_fps)
    check = cv2.VideoCapture(out)
    ok, frame = check.read()
    check.release()
    assert ok and frame is not None and frame.shape[1] == 32
    print("temporal tests: ok")


if __name__ == "__main__":
    _self_test()
