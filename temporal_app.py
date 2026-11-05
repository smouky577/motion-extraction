"""Temporal FFT — pick a clip and where the result goes.

The command-line script needed the file names up front. This window asks.
"""

from __future__ import annotations

import ctypes
import os
import sys
import threading
import tkinter as tk
from tkinter import filedialog, messagebox

import cv2
import numpy as np
from PIL import Image, ImageTk

from temporal import filter_clip, load_frames, write_video

BG = "#10120f"
RAISED = "#242824"
TEXT = "#f3f1e8"
MUTED = "#9aa194"
ACCENT = "#d6f25c"
DARK = "#16180f"
TROUGH = "#2c312b"
RED = "#ff4d3a"
LINE = "#31362f"
ABOUT_EN = "Donate to Dr. Baysinger. He promises to spend it on hookers and beer."
ABOUT_BG = "Дарете на Dr. Baysinger. Той обещава да ги изхарчи за курви и бело."
REVOLUT = "revolut.me/georgi09qc"
BTC = (
    "bc1qr84akpfry7sjs6759wwq722fyert9apcarpst6",
    "bc1p6hp5q20u9t9upsjt7425wgqdrs0cr8wnnlf9atu24z92pzsnq85q62uc8e",
)

MODES = (
    ("Бързи", "highpass"),
    ("Бавни", "lowpass"),
    ("Лента", "bandpass"),
    ("Спектър", "spectrum"),
)
OPEN_TYPES = (
    ("Видео", "*.mp4 *.mov *.mkv *.avi *.webm *.m4v"),
    ("Всички файлове", "*.*"),
)
SAVE_TYPES = (("MP4", "*.mp4"), ("AVI", "*.avi"))


def enable_dpi_awareness() -> None:
    try:
        ctypes.windll.shcore.SetProcessDpiAwareness(2)
    except Exception:
        try:
            ctypes.windll.user32.SetProcessDPIAware()
        except Exception:
            pass


def media_dir() -> str:
    videos = os.path.join(os.path.expanduser("~"), "Videos")
    if os.path.isdir(videos):
        return videos
    return os.path.expanduser("~")


def fit_display(image: np.ndarray, box_w: int, box_h: int) -> np.ndarray:
    height, width = image.shape[:2]
    if box_w < 2 or box_h < 2 or width < 1 or height < 1:
        return image
    scale = min(box_w / width, box_h / height)
    new_w = max(1, int(width * scale))
    new_h = max(1, int(height * scale))
    if new_w == width and new_h == height:
        return image
    interp = cv2.INTER_AREA if scale < 1 else cv2.INTER_LINEAR
    return cv2.resize(image, (new_w, new_h), interpolation=interp)


def show_about(root: tk.Tk) -> None:
    window = tk.Toplevel(root)
    window.title("About")
    window.configure(bg=BG)
    window.resizable(False, False)
    window.transient(root)
    frame = tk.Frame(window, bg=BG, padx=18, pady=16)
    frame.pack(fill="both", expand=True)
    tk.Label(frame, text="About", bg=BG, fg=TEXT, font=("Segoe UI", 16, "bold"), anchor="w").pack(fill="x")
    tk.Label(
        frame, text=ABOUT_EN, bg=BG, fg=TEXT, font=("Segoe UI", 11), wraplength=640, justify="left", anchor="w"
    ).pack(fill="x", pady=(16, 8))
    tk.Label(
        frame, text=ABOUT_BG, bg=BG, fg=TEXT, font=("Segoe UI", 11), wraplength=640, justify="left", anchor="w"
    ).pack(fill="x")
    tk.Frame(frame, bg=LINE, height=1).pack(fill="x", pady=14)
    tk.Label(frame, text="Revolut", bg=BG, fg=TEXT, font=("Segoe UI", 12, "bold"), anchor="w").pack(fill="x")
    note = tk.Label(frame, text="", bg=BG, fg=ACCENT, font=("Segoe UI", 9), anchor="w")

    def copy(value: str) -> None:
        root.clipboard_clear()
        root.clipboard_append(value)
        note.configure(text="Copied")

    def link(value: str) -> None:
        label = tk.Label(
            frame, text=value, bg=BG, fg=ACCENT, font=("Segoe UI", 10), cursor="hand2", anchor="w", justify="left"
        )
        label.pack(fill="x", pady=(6, 0))
        label.bind("<Button-1>", lambda _event: copy(value))

    link(REVOLUT)
    tk.Label(frame, text="BTC address", bg=BG, fg=TEXT, font=("Segoe UI", 12, "bold"), anchor="w").pack(
        fill="x", pady=(16, 0)
    )
    for address in BTC:
        link(address)
    note.pack(fill="x", pady=(14, 0))
    window.update_idletasks()
    width, height = 700, window.winfo_reqheight()
    parent_x = root.winfo_rootx() + max(0, (root.winfo_width() - width) // 2)
    parent_y = root.winfo_rooty() + max(0, (root.winfo_height() - height) // 2)
    window.geometry(f"{width}x{height}+{parent_x}+{parent_y}")
    window.grab_set()
    window.focus_set()


class TemporalApp:
    def __init__(self, visible: bool = True) -> None:
        enable_dpi_awareness()
        self.root = tk.Tk()
        self.root.withdraw()
        self.root.title("Temporal FFT")
        self.root.configure(bg=BG)
        self.root.minsize(920, 700)
        try:
            ppi = float(self.root.winfo_fpixels("1i"))
            if ppi > 1:
                self.root.tk.call("tk", "scaling", ppi / 72.0)
        except tk.TclError:
            pass

        self.path = ""
        self.busy = False
        self.cancel_flag = False
        self.frames: np.ndarray | None = None
        self.source_count = 0
        self.play_index = 0
        self.play_fps = 30.0
        self.playing = False
        self.play_after = None
        self.capture = None
        self.capture_pos = -1
        self._seeking = False
        self.photo = None
        self.image_id = None
        self._ready = False

        self._build()
        self._place_window()
        self.root.protocol("WM_DELETE_WINDOW", self._on_close)
        self._ready = True
        self._apply_mode()
        if visible:
            self.root.deiconify()
            self.root.lift()

    def _place_window(self) -> None:
        width, height = 1180, 780
        self.root.update_idletasks()
        screen_w = self.root.winfo_screenwidth()
        screen_h = self.root.winfo_screenheight()
        x = max(0, (screen_w - width) // 2)
        y = max(0, (screen_h - height) // 2)
        self.root.geometry(f"{width}x{height}+{x}+{y}")

    def _build(self) -> None:
        self.root.columnconfigure(0, weight=1)
        self.root.rowconfigure(1, weight=1)

        header = tk.Frame(self.root, bg=BG)
        header.grid(row=0, column=0, sticky="ew", padx=18, pady=(14, 0))
        title_box = tk.Frame(header, bg=BG)
        title_box.pack(side="left", anchor="w")
        tk.Label(title_box, text="TEMPORAL", bg=BG, fg=ACCENT, font=("Segoe UI", 18, "bold")).pack(side="left")
        tk.Label(title_box, text="  FFT", bg=BG, fg=TEXT, font=("Segoe UI", 18)).pack(side="left")
        tk.Label(
            header,
            text="бързи    ·    бавни    ·    лента    ·    спектър",
            bg=BG,
            fg=MUTED,
            font=("Segoe UI", 9),
        ).pack(side="left", padx=(16, 0), pady=(8, 0))
        credit = tk.Label(
            header,
            text="by Dr. Baysinger",
            bg=BG,
            fg=MUTED,
            font=("Segoe UI", 9),
            cursor="hand2",
        )
        credit.pack(side="right")
        credit.bind("<Button-1>", lambda _event: show_about(self.root))

        rule = tk.Frame(self.root, bg=ACCENT, height=2)
        rule.grid(row=2, column=0, sticky="ew", padx=18, pady=(8, 0))
        rule.grid_propagate(False)

        self.preview = tk.Frame(self.root, bg="#070807")
        self.preview.grid(row=1, column=0, sticky="nsew", padx=18, pady=(8, 0))
        self.canvas = tk.Canvas(
            self.preview,
            bg="#070807",
            highlightthickness=0,
            bd=0,
            width=800,
            height=180,
        )
        self.canvas.pack(fill="both", expand=True)
        self.placeholder = self.canvas.create_text(
            450,
            210,
            text="Отвори клип\nПосле избери къде да се запише резултатът",
            fill=MUTED,
            font=("Segoe UI", 13),
            justify="center",
        )
        self.canvas.bind("<Configure>", lambda _e: self._draw())

        self.seek_row = tk.Frame(self.root, bg=BG)
        self.seek_row.grid(row=3, column=0, sticky="ew", padx=18, pady=(8, 0))
        times = tk.Frame(self.seek_row, bg=BG)
        times.pack(fill="x")
        self.time_now = tk.Label(times, text="0:00", bg=BG, fg=MUTED, font=("Segoe UI", 9), width=6, anchor="w")
        self.time_now.pack(side="left")
        self.seek_var = tk.IntVar(value=0)
        self.seek = self._scale(times, self.seek_var, 0, 1, self._on_seek, TROUGH)
        self.seek.pack(side="left", fill="x", expand=True, padx=8)
        self.time_end = tk.Label(times, text="0:00", bg=BG, fg=MUTED, font=("Segoe UI", 9), width=6, anchor="e")
        self.time_end.pack(side="right")
        self.play_btn = self._button(self.seek_row, "Пусни", self._toggle_play)
        self.play_btn.pack(fill="x", pady=(6, 0))
        self.seek_row.grid_remove()

        buttons = tk.Frame(self.root, bg=BG)
        buttons.grid(row=4, column=0, sticky="ew", padx=18, pady=(10, 0))
        self.open_btn = self._button(buttons, "Отвори клип", self._on_open, primary=True)
        self.run_btn = self._button(buttons, "Обработи", self._on_run)
        self.cancel_btn = self._button(buttons, "Отказ", self._on_cancel)
        self.open_btn.pack(side="left", padx=(0, 8))
        self.run_btn.pack(side="left", padx=(0, 8))
        self.run_btn.configure(state="disabled")

        modes = tk.Frame(self.root, bg=BG)
        modes.grid(row=5, column=0, sticky="ew", padx=18, pady=(8, 0))
        self.mode_var = tk.StringVar(value="highpass")
        self.mode_buttons = {}
        for label, key in MODES:
            button = self._button(modes, label, lambda key=key: self._set_mode(key))
            button.pack(side="left", padx=(0, 8))
            self.mode_buttons[key] = button
        self.size_btn = self._button(modes, "Оригинал", self._toggle_size)
        self.clip_btn = self._button(modes, "Цял клип", self._toggle_clip)
        self.size_btn.pack(side="left", padx=(16, 8))
        self.clip_btn.pack(side="left")

        self.cutoff_var = tk.DoubleVar(value=2.0)
        self.cutoff_row, self.cutoff_value = self._slider_row("Граница", self.cutoff_var, 0.2, 12, 0.1, "Hz")
        self.low_var = tk.DoubleVar(value=0.5)
        self.low_row, self.low_value = self._slider_row("Долна", self.low_var, 0.1, 12, 0.1, "Hz")
        self.high_var = tk.DoubleVar(value=3.0)
        self.high_row, self.high_value = self._slider_row("Горна", self.high_var, 0.2, 16, 0.1, "Hz")
        self.gain_var = tk.DoubleVar(value=3.0)
        self.gain_row, self.gain_value = self._slider_row("Усилване", self.gain_var, 0.5, 8, 0.1, "×")
        self.width_var = tk.IntVar(value=480)
        self.width_row, self.width_value = self._slider_row("Ширина", self.width_var, 320, 1920, 16, "px")
        self.seconds_var = tk.IntVar(value=10)
        self.seconds_row, self.seconds_value = self._slider_row("Дължина", self.seconds_var, 1, 60, 1, "сек")
        self.full_size = tk.BooleanVar(value=False)
        self.full_clip = tk.BooleanVar(value=False)

        status = tk.Frame(self.root, bg=BG)
        status.grid(row=13, column=0, sticky="ew", padx=18, pady=(8, 14))
        self.detail = tk.Label(
            status,
            text="Отвори клип. Резултатът се записва където посочиш.",
            bg=BG,
            fg=MUTED,
            font=("Segoe UI", 9),
            anchor="w",
        )
        self.detail.pack(side="left", fill="x", expand=True)
        self.message = tk.Label(status, text="", bg=BG, fg=MUTED, font=("Segoe UI", 9), anchor="e")
        self.message.pack(side="right")

    def _caption(self, parent: tk.Misc, text: str) -> tk.Label:
        return tk.Label(parent, text=text, bg=BG, fg=MUTED, font=("Segoe UI", 10), width=16, anchor="w")

    def _button(self, parent: tk.Misc, text: str, command, primary: bool = False) -> tk.Button:
        bg = ACCENT if primary else RAISED
        fg = DARK if primary else TEXT
        return tk.Button(
            parent,
            text=text,
            command=command,
            bg=bg,
            fg=fg,
            activebackground=bg,
            activeforeground=fg,
            disabledforeground="#6e726c",
            relief="flat",
            bd=0,
            padx=14,
            pady=8,
            font=("Segoe UI", 10),
            cursor="hand2",
            takefocus=0,
        )

    def _scale(self, parent, variable, start, end, command, trough, resolution=1):
        return tk.Scale(
            parent,
            from_=start,
            to=end,
            orient="horizontal",
            showvalue=False,
            variable=variable,
            resolution=resolution,
            command=command,
            bg=parent["bg"],
            fg=TEXT,
            troughcolor=trough,
            activebackground=ACCENT,
            highlightthickness=0,
            bd=0,
            sliderrelief="flat",
            sliderlength=16,
            width=12,
            cursor="hand2",
            takefocus=0,
        )

    def _slider_row(self, label: str, variable, start, end, resolution, unit: str):
        row = tk.Frame(self.root, bg=BG)
        row.grid(row=self._next_row(), column=0, sticky="ew", padx=18, pady=(6, 0))
        self._caption(row, label).pack(side="left")
        scale = self._scale(row, variable, start, end, lambda _v: self._refresh_limits(), TROUGH, resolution)
        scale.pack(side="left", fill="x", expand=True, padx=12)
        value = tk.Label(row, text="", bg=BG, fg=TEXT, font=("Segoe UI", 10), width=28, anchor="e")
        value.pack(side="right")
        return row, value

    def _next_row(self) -> int:
        row = getattr(self, "_row", 5) + 1
        self._row = row
        return row

    def _paint(self, button: tk.Button, bg: str, fg: str, text: str) -> None:
        button.configure(text=text, bg=bg, fg=fg, activebackground=bg, activeforeground=fg)

    def _toggle_size(self) -> None:
        if self.busy:
            return
        self.full_size.set(not self.full_size.get())
        self._paint_toggles()
        self._refresh_limits()

    def _toggle_clip(self) -> None:
        if self.busy:
            return
        self.full_clip.set(not self.full_clip.get())
        self._paint_toggles()
        self._refresh_limits()

    def _paint_toggles(self) -> None:
        if self.full_size.get():
            self._paint(self.size_btn, ACCENT, DARK, "Оригинал ВКЛ")
        else:
            self._paint(self.size_btn, RAISED, TEXT, "Оригинал")
        if self.full_clip.get():
            self._paint(self.clip_btn, ACCENT, DARK, "Цял клип ВКЛ")
        else:
            self._paint(self.clip_btn, RAISED, TEXT, "Цял клип")

    def _set_mode(self, key: str) -> None:
        if self.busy:
            return
        self.mode_var.set(key)
        self._apply_mode()

    def _apply_mode(self) -> None:
        mode = self.mode_var.get()
        for key, button in self.mode_buttons.items():
            if key == mode:
                button.configure(bg=ACCENT, fg=DARK, activebackground=ACCENT, activeforeground=DARK)
            else:
                button.configure(bg=RAISED, fg=TEXT, activebackground=RAISED, activeforeground=TEXT)
        show_cutoff = mode in ("highpass", "lowpass")
        show_band = mode == "bandpass"
        show_gain = mode in ("highpass", "bandpass")
        self._show(self.cutoff_row, show_cutoff)
        self._show(self.low_row, show_band)
        self._show(self.high_row, show_band)
        self._show(self.gain_row, show_gain)
        self._paint_toggles()
        self._refresh_limits()

    def _show(self, row: tk.Frame, visible: bool) -> None:
        if visible:
            row.grid()
        else:
            row.grid_remove()

    def _refresh_limits(self) -> None:
        if not self._ready:
            return
        self.cutoff_value.configure(text=f"{self.cutoff_var.get():.1f} Hz")
        low = min(self.low_var.get(), self.high_var.get())
        high = max(self.low_var.get(), self.high_var.get())
        self.low_value.configure(text=f"{low:.1f} Hz")
        self.high_value.configure(text=f"{high:.1f} Hz")
        self.gain_value.configure(text=f"{self.gain_var.get():.1f}×")
        if self.full_size.get():
            self.width_value.configure(text="оригинал")
        else:
            self.width_value.configure(text=f"{int(self.width_var.get())} px")
        if self.full_clip.get():
            self.seconds_value.configure(text="цял клип")
        else:
            self.seconds_value.configure(text=f"{int(self.seconds_var.get())} сек")

    def _on_open(self) -> None:
        if self.busy:
            return
        path = filedialog.askopenfilename(
            parent=self.root,
            title="Отвори клип",
            initialdir=os.path.dirname(self.path) if self.path else media_dir(),
            filetypes=OPEN_TYPES,
        )
        if not path:
            return
        capture = cv2.VideoCapture(path)
        if not capture.isOpened():
            messagebox.showerror("Temporal FFT", "Не мога да отворя този клип.")
            return
        ok, frame = capture.read()
        fps = float(capture.get(cv2.CAP_PROP_FPS) or 0)
        count = int(capture.get(cv2.CAP_PROP_FRAME_COUNT) or 0)
        if not ok or frame is None:
            capture.release()
            messagebox.showerror("Temporal FFT", "Клипът няма кадри.")
            return
        self._stop_play()
        self._close_capture()
        self.path = path
        self.frames = None
        self.capture = capture
        self.capture_pos = 1
        self.source_count = max(count, 1)
        self.play_fps = fps if fps > 1 else 30.0
        self.play_index = 0
        last = max(0, self.source_count - 1)
        self.seek.configure(to=max(1, last))
        self._seeking = True
        self.seek_var.set(0)
        self._seeking = False
        self._show_bgr(frame)
        self._paint_time()
        if last > 0:
            self.seek_row.grid()
        else:
            self.seek_row.grid_remove()
        seconds = (count / fps) if fps > 1 and count > 0 else 0
        height, width = frame.shape[:2]
        bits = [os.path.basename(path), f"{width}×{height}"]
        if fps > 1:
            bits.append(f"{fps:.2f} кадъра/сек")
        if seconds:
            bits.append(f"{seconds:.1f} сек")
        self.detail.configure(text="    ".join(bits))
        self.message.configure(text="", fg=MUTED)
        self.run_btn.configure(state="normal")

    def _clock(self, frame: int) -> str:
        fps = self.play_fps if self.play_fps >= 1 else 30.0
        total = max(0, int(frame / fps))
        return f"{total // 60}:{total % 60:02d}"

    def _play_count(self) -> int:
        if self.frames is not None:
            return int(self.frames.shape[0])
        return int(self.source_count)

    def _close_capture(self) -> None:
        if self.capture is not None:
            self.capture.release()
            self.capture = None
            self.capture_pos = -1

    def _frame_at(self, index: int) -> np.ndarray | None:
        if self.frames is not None:
            return self.frames[index]
        if not self.path:
            return None
        if self.capture is None or not self.capture.isOpened():
            self.capture = cv2.VideoCapture(self.path)
            self.capture_pos = 0
        if self.capture_pos != index:
            self.capture.set(cv2.CAP_PROP_POS_FRAMES, index)
        ok, frame = self.capture.read()
        if not ok or frame is None:
            return None
        self.capture_pos = index + 1
        return frame

    def _move_to(self, index: int) -> None:
        count = self._play_count()
        if count <= 0:
            return
        index = max(0, min(index, count - 1))
        frame = self._frame_at(index)
        if frame is None:
            return
        self.play_index = index
        if int(self.seek_var.get()) != index:
            self._seeking = True
            self.seek_var.set(index)
            self._seeking = False
        self._show_bgr(frame)
        self._paint_time()

    def _paint_time(self) -> None:
        count = self._play_count()
        if count <= 0:
            return
        self.time_now.configure(text=self._clock(self.play_index))
        self.time_end.configure(text=self._clock(count))

    def _toggle_play(self) -> None:
        if self.busy or self._play_count() < 2:
            return
        if self.playing:
            self._stop_play()
            return
        self.playing = True
        self._paint(self.play_btn, ACCENT, DARK, "Спри")
        self._play_tick()

    def _stop_play(self) -> None:
        self.playing = False
        if self.play_after is not None:
            self.root.after_cancel(self.play_after)
            self.play_after = None
        if getattr(self, "play_btn", None) is not None:
            self._paint(self.play_btn, RAISED, TEXT, "Пусни")

    def _play_tick(self) -> None:
        if not self.playing:
            return
        count = self._play_count()
        if count < 2:
            self._stop_play()
            return
        nxt = 0 if self.play_index >= count - 1 else self.play_index + 1
        self._move_to(nxt)
        delay = max(16, int(1000 / max(1.0, min(60.0, self.play_fps))))
        self.play_after = self.root.after(delay, self._play_tick)

    def _on_seek(self, _value=None) -> None:
        if self._seeking:
            return
        self._move_to(int(self.seek_var.get()))

    def _on_close(self) -> None:
        self._stop_play()
        self._close_capture()
        self.root.destroy()

    def _show_bgr(self, frame: np.ndarray) -> None:
        self._shown = frame
        self._draw()

    def _draw(self) -> None:
        frame = getattr(self, "_shown", None)
        width = max(1, self.canvas.winfo_width())
        height = max(1, self.canvas.winfo_height())
        if frame is None:
            self.canvas.coords(self.placeholder, width // 2, height // 2)
            self.canvas.itemconfigure(self.placeholder, state="normal")
            if self.image_id is not None:
                self.canvas.itemconfigure(self.image_id, state="hidden")
            return
        fitted = fit_display(cv2.cvtColor(frame, cv2.COLOR_BGR2RGB), width - 12, height - 12)
        image = Image.fromarray(fitted)
        self.photo = ImageTk.PhotoImage(image=image)
        cx, cy = width // 2, height // 2
        if self.image_id is None:
            self.image_id = self.canvas.create_image(cx, cy, image=self.photo)
        else:
            self.canvas.itemconfigure(self.image_id, image=self.photo, state="normal")
            self.canvas.coords(self.image_id, cx, cy)
        self.canvas.itemconfigure(self.placeholder, state="hidden")

    def _on_cancel(self) -> None:
        self.cancel_flag = True

    def _on_run(self) -> None:
        if self.busy or not self.path:
            return
        stem = os.path.splitext(os.path.basename(self.path))[0]
        folder = os.path.dirname(self.path) or media_dir()
        dest = filedialog.asksaveasfilename(
            parent=self.root,
            title="Къде да се запише",
            initialdir=folder,
            initialfile=f"{stem}-fft.mp4",
            defaultextension=".mp4",
            filetypes=SAVE_TYPES,
        )
        if not dest:
            return
        mode = self.mode_var.get()
        cutoff = float(self.cutoff_var.get())
        low = min(float(self.low_var.get()), float(self.high_var.get()))
        high = max(float(self.low_var.get()), float(self.high_var.get()))
        gain = float(self.gain_var.get())
        max_width = None if self.full_size.get() else int(self.width_var.get())
        max_seconds = None if self.full_clip.get() else float(self.seconds_var.get())
        self.busy = True
        self.cancel_flag = False
        self._stop_play()
        self.run_btn.configure(state="disabled")
        self.open_btn.configure(state="disabled")
        if not self.cancel_btn.winfo_ismapped():
            self.cancel_btn.pack(side="left")
        self.message.configure(text="Чете клипа…", fg=MUTED)
        threading.Thread(
            target=self._work,
            args=(self.path, dest, mode, cutoff, low, high, gain, max_width, max_seconds),
            daemon=True,
        ).start()

    def _work(self, src, dest, mode, cutoff, low, high, gain, max_width, max_seconds) -> None:
        try:
            def reading(done: int, total: int) -> None:
                if total > 1:
                    self._status(f"Чете {done}/{total}")
                else:
                    self._status(f"Чете кадър {done}")

            video, fps = load_frames(src, max_width, max_seconds, reading, lambda: self.cancel_flag)
            count, height, width, _ = video.shape
            self._status(f"Смята {count} кадъра, {width}×{height}")
            result = filter_clip(video, fps, mode, cutoff, low, high, gain)
            del video

            def writing(done: int, total: int) -> None:
                self._status(f"Записва {done}/{total}")

            write_video(dest, result, fps, writing, lambda: self.cancel_flag)
            self.frames = result
            self.play_fps = float(fps)
            name = os.path.basename(dest)
            note = "спектър" if mode == "spectrum" else "без звук"
            self.root.after(0, lambda: self._finish(f"Готово: {name}  ·  {note}"))
        except Exception as exc:
            text = str(exc) or "Грешка"
            if text == "Спряно.":
                try:
                    os.remove(dest)
                except OSError:
                    pass
            self.root.after(0, lambda text=text: self._finish(text, error=text != "Спряно."))

    def _status(self, text: str) -> None:
        self.root.after(0, lambda: self.message.configure(text=text, fg=MUTED))

    def _finish(self, text: str, error: bool = False) -> None:
        self.busy = False
        self.cancel_btn.pack_forget()
        self.open_btn.configure(state="normal")
        self.run_btn.configure(state="normal" if self.path else "disabled")
        self.message.configure(text=text, fg=RED if error else ACCENT)
        if error and text != "Спряно.":
            messagebox.showerror("Temporal FFT", text)
        if self.frames is not None and not error:
            self._close_capture()
            self._stop_play()
            last = self.frames.shape[0] - 1
            self.play_index = 0
            self.seek.configure(to=max(1, last))
            self._seeking = True
            self.seek_var.set(0)
            self._seeking = False
            if last > 0:
                self.seek_row.grid()
            else:
                self.seek_row.grid_remove()
            self._show_bgr(self.frames[0])
            self._paint_time()


def main() -> None:
    if "--self-test" in sys.argv:
        from temporal import _self_test

        _self_test()
        app = TemporalApp(visible=False)
        app.root.update()
        app.root.destroy()
        print("temporal app: ok")
        return
    TemporalApp().root.mainloop()


if __name__ == "__main__":
    main()
