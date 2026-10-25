"""Motion Extraction — recorded clips and a live camera.

Uses the OpenCV, NumPy and Pillow already installed for the other apps.
"""

from __future__ import annotations

import ctypes
import math
import os
import queue
import sys
import threading
import time
import tkinter as tk
from collections import deque
from tkinter import filedialog, messagebox

try:
    import cv2
    import numpy as np
    from PIL import Image, ImageTk
except Exception as exc:
    ctypes.windll.user32.MessageBoxW(
        0,
        f"Липсва библиотека:\n{exc}",
        "Motion Extraction",
        0x10,
    )
    raise SystemExit(1) from exc

from engine import Settings, export_video, normalize, render_frame

BG = "#10120f"
PANEL = "#191c18"
RAISED = "#242824"
LINE = "#31362f"
TEXT = "#f3f1e8"
MUTED = "#9aa194"
ACCENT = "#d6f25c"
DARK = "#16180f"
WARM = "#ffb03a"
RED = "#ff4d3a"
TROUGH = "#2c312b"
RED_TROUGH = "#3a2422"
GREEN_TROUGH = "#243328"
BLUE_TROUGH = "#222a36"

SWAP_CHOICES = (
    ("Оригинал", "none"),
    ("Червено ↔ синьо", "rb"),
    ("Червено ↔ зелено", "rg"),
    ("Зелено ↔ синьо", "gb"),
    ("Цикъл напред", "cycle_fwd"),
    ("Цикъл назад", "cycle_back"),
)
OPEN_TYPES = (
    ("Видео", "*.mp4 *.mov *.mkv *.avi *.webm *.m4v"),
    ("Всички файлове", "*.*"),
)
SAVE_TYPES = (("MP4", "*.mp4"), ("AVI", "*.avi"))
BLUR_MAX = 24


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


def downscale(frame: np.ndarray, max_w: int, max_h: int) -> np.ndarray:
    height, width = frame.shape[:2]
    scale = min(max_w / width, max_h / height, 1.0)
    if scale >= 0.999:
        return frame
    new_w = max(2, int(width * scale) // 2 * 2)
    new_h = max(2, int(height * scale) // 2 * 2)
    return cv2.resize(frame, (new_w, new_h), interpolation=cv2.INTER_AREA)


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


def format_clock(seconds: float) -> str:
    whole = max(0, int(seconds))
    return f"{whole // 60:02d}:{whole % 60:02d}"


def describe(snap: dict) -> str:
    mode = snap["mode"]
    fps = float(snap["fps"] or 0)
    width, height = int(snap["src_w"]), int(snap["src_h"])
    size = f"{width}×{height}" if width and height else ""
    if mode == "file":
        bits = [snap["name"], size]
        if fps > 1:
            bits.append(f"{fps:.2f} кадъра/сек")
        count = int(snap["count"])
        if count > 0:
            bits.append(f"{int(snap['frame']) + 1}/{count}")
        return "    ".join(bit for bit in bits if bit)
    if mode == "live":
        bits = ["Камера", size]
        if fps > 1:
            bits.append(f"{fps:.0f} кадъра/сек")
        return "    ".join(bit for bit in bits if bit)
    if mode == "demo":
        return "Демо    неподвижното изчезва, движението остава"
    return "Отвори клип, пусни камерата или демото"


class MotionApp:
    def __init__(self, visible: bool = True) -> None:
        enable_dpi_awareness()
        self.root = tk.Tk()
        self.root.withdraw()
        self.root.title("Motion Extraction")
        self.root.configure(bg=BG)
        self.root.minsize(920, 700)
        try:
            ppi = float(self.root.winfo_fpixels("1i"))
            if ppi > 1:
                self.root.tk.call("tk", "scaling", ppi / 72.0)
        except tk.TclError:
            pass

        self.commands: queue.Queue = queue.Queue()
        self.wake = threading.Event()
        self.cancel_event = threading.Event()
        self.frame_lock = threading.Lock()
        self.state_lock = threading.Lock()
        self.settings_lock = threading.Lock()
        self.settings = Settings()
        self.generation = 0
        self.last_bgr: np.ndarray | None = None
        self.state = {
            "mode": "idle",
            "playing": False,
            "fps": 30.0,
            "frame": 0,
            "count": 0,
            "src_w": 0,
            "src_h": 0,
            "name": "",
            "recording": False,
            "record_s": 0.0,
            "export": None,
            "message": "",
        }

        self.delay_max = 30
        self.display_fps = 30.0
        self.seeking = False
        self.hidden_open = False
        self.chrome_key = None
        self.play_mode_ui = "idle"
        self.seek_count = -1
        self.photo = None
        self.image_id = None
        self.placeholder_id = None
        self.drawn_key = None
        self.ui_alive = True
        self.closed = False
        self._ready = False
        self.read_fails = 0

        self.cap = None
        self.buffer: deque[np.ndarray] = deque()
        self.play_mode = "idle"
        self.playing = False
        self.file_path = ""
        self.file_fps = 30.0
        self.file_count = 0
        self.frame_index = -1
        self.src_w = 0
        self.src_h = 0
        self.next_due: float | None = None
        self.rendered_gen = -1
        self.record_writer = None
        self.record_path = ""
        self.record_started = 0.0
        self.fps_ema: float | None = None
        self.last_frame_t: float | None = None
        self.demo_static: np.ndarray | None = None
        self.demo_i = 0

        self._build()
        self._place_window()
        self.push_settings()
        self.worker = threading.Thread(target=self.worker_loop, name="motion", daemon=True)
        self.worker.start()
        self.root.protocol("WM_DELETE_WINDOW", self.on_close)
        self.root.report_callback_exception = self._report_error
        self.root.after(33, self.tick)
        if visible:
            self.root.deiconify()
            self.root.lift()
            self.root.focus_force()

    def _report_error(self, _exc, val, _tb) -> None:
        messagebox.showerror("Motion Extraction", str(val))

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
        self.logo = tk.Label(
            title_box,
            text="MOTION",
            bg=BG,
            fg=ACCENT,
            font=("Segoe UI", 18, "bold"),
        )
        self.logo.pack(side="left")
        self.logo_sub = tk.Label(
            title_box,
            text="  EXTRACTION",
            bg=BG,
            fg=TEXT,
            font=("Segoe UI", 18),
        )
        self.logo_sub.pack(side="left")
        self.rgb_flag = tk.Label(title_box, text="", bg=BG, fg=WARM, font=("Segoe UI", 9, "bold"))
        self.rgb_flag.pack(side="left", padx=(10, 0), pady=(6, 0))
        for widget in (self.logo, self.logo_sub, title_box):
            widget.bind("<Triple-Button-1>", self.toggle_hidden)
            widget.bind("<Button-3>", self.toggle_hidden)

        subtitle = tk.Label(
            header,
            text="инверсия    ·    50%    ·    изместване във времето",
            bg=BG,
            fg=MUTED,
            font=("Segoe UI", 9),
        )
        subtitle.pack(side="left", padx=(16, 0), pady=(8, 0))

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
        self.canvas.bind("<MouseWheel>", self.on_blur_wheel)
        self.placeholder_id = self.canvas.create_text(
            450,
            210,
            text="Отвори клип, камера или демо\nНеподвижното става сиво, движението остава",
            fill=MUTED,
            font=("Segoe UI", 13),
            justify="center",
        )

        self.seek_row = tk.Frame(self.root, bg=BG)
        self.seek_row.grid(row=3, column=0, sticky="ew", padx=18, pady=(8, 0))
        self.seek_var = tk.IntVar(value=0)
        self.seek_scale = self._scale(self.seek_row, self.seek_var, 0, 1, None, TROUGH)
        self.seek_scale.pack(fill="x")
        self.seek_scale.bind("<ButtonPress-1>", self.on_seek_press)
        self.seek_scale.bind("<ButtonRelease-1>", self.on_seek_release)
        self.seek_row.grid_remove()

        transport = tk.Frame(self.root, bg=BG)
        transport.grid(row=4, column=0, sticky="ew", padx=18, pady=(10, 0))
        self.open_btn = self._button(transport, "Отвори клип", self.on_open, primary=True)
        self.camera_btn = self._button(transport, "Камера", self.on_camera)
        self.demo_btn = self._button(transport, "Демо", self.on_demo)
        self.play_btn = self._button(transport, "Пусни", self.on_toggle)
        self.export_btn = self._button(transport, "Експорт", self.on_export)
        self.record_btn = self._button(transport, "Запис", self.on_record)
        self.cancel_btn = self._button(transport, "Отказ", self.on_cancel)
        for button in (
            self.open_btn,
            self.camera_btn,
            self.demo_btn,
            self.play_btn,
            self.export_btn,
            self.record_btn,
        ):
            button.pack(side="left", padx=(0, 8))

        delay_row = tk.Frame(self.root, bg=BG)
        delay_row.grid(row=5, column=0, sticky="ew", padx=18, pady=(14, 0))
        self._caption(delay_row, "Изместване").pack(side="left")
        self.delay_var = tk.IntVar(value=1)
        self.delay_scale = self._scale(delay_row, self.delay_var, 1, 30, self.on_setting, TROUGH)
        self.delay_scale.pack(side="left", fill="x", expand=True, padx=12)
        self.delay_value = tk.Label(
            delay_row, text="", bg=BG, fg=TEXT, font=("Segoe UI", 10), width=28, anchor="e"
        )
        self.delay_value.pack(side="right")

        blur_row = tk.Frame(self.root, bg=BG)
        blur_row.grid(row=6, column=0, sticky="ew", padx=18, pady=(6, 0))
        self._caption(blur_row, "Блур за детайл").pack(side="left")
        self.blur_var = tk.IntVar(value=0)
        self.blur_scale = self._scale(blur_row, self.blur_var, 0, BLUR_MAX, self.on_setting, TROUGH)
        self.blur_scale.pack(side="left", fill="x", expand=True, padx=12)
        self.blur_scale.bind("<MouseWheel>", self.on_blur_wheel)
        self.blur_value = tk.Label(
            blur_row, text="", bg=BG, fg=TEXT, font=("Segoe UI", 10), width=28, anchor="e"
        )
        self.blur_value.pack(side="right")

        actions = tk.Frame(self.root, bg=BG)
        actions.grid(row=8, column=0, sticky="ew", padx=18, pady=(8, 0))
        self.glow_var = tk.BooleanVar(value=False)
        self.glow_btn = self._button(actions, "Сияние", self.on_glow)
        self.glow_btn.pack(side="left")
        tk.Label(actions, text="Цветове", bg=BG, fg=MUTED, font=("Segoe UI", 10)).pack(
            side="left", padx=(16, 8)
        )
        self.swap_var = tk.StringVar(value="Оригинал")
        self.swap_menu = tk.OptionMenu(
            actions,
            self.swap_var,
            *(label for label, _key in SWAP_CHOICES),
        )
        self.swap_menu.configure(
            bg=RAISED,
            fg=TEXT,
            activebackground=ACCENT,
            activeforeground=DARK,
            relief="flat",
            highlightthickness=0,
            font=("Segoe UI", 10),
            padx=10,
            pady=4,
        )
        self.swap_menu["menu"].configure(
            bg=PANEL,
            fg=TEXT,
            activebackground=ACCENT,
            activeforeground=DARK,
            font=("Segoe UI", 10),
            borderwidth=0,
        )
        self.swap_menu.pack(side="left")
        self.swap_var.trace_add("write", lambda *_: self.on_setting())
        self.channel_btn = self._button(actions, "Канали", self.toggle_hidden)
        self.channel_btn.pack(side="left", padx=(8, 0))

        self.hidden = tk.Frame(self.root, bg="#141712", highlightbackground=LINE, highlightthickness=1)
        self.hidden.grid(row=9, column=0, sticky="ew", padx=18, pady=(10, 0))
        hidden_head = tk.Frame(self.hidden, bg="#141712")
        hidden_head.pack(fill="x", padx=12, pady=(10, 0))
        tk.Label(
            hidden_head,
            text="Канално време",
            bg="#141712",
            fg=TEXT,
            font=("Segoe UI", 11, "bold"),
        ).pack(side="left")
        tk.Label(
            hidden_head,
            text="добавя се към основното изместване",
            bg="#141712",
            fg=MUTED,
            font=("Segoe UI", 9),
        ).pack(side="left", padx=(10, 0))
        close = self._button(hidden_head, "Затвори", self.toggle_hidden)
        close.pack(side="right")
        self.off_r = tk.IntVar(value=0)
        self.off_g = tk.IntVar(value=0)
        self.off_b = tk.IntVar(value=0)
        self.scale_r, self.red_value = self._channel_row("Червено", self.off_r, RED, RED_TROUGH)
        self.scale_g, self.green_value = self._channel_row("Зелено", self.off_g, "#8dce78", GREEN_TROUGH)
        self.scale_b, self.blue_value = self._channel_row("Синьо", self.off_b, "#7eb0e8", BLUE_TROUGH)
        foot = tk.Frame(self.hidden, bg="#141712")
        foot.pack(fill="x", padx=12, pady=(4, 10))
        self._button(foot, "Нулирай", self.on_reset_offsets).pack(side="left")
        tk.Label(
            foot,
            text="Бутон Канали или F2",
            bg="#141712",
            fg=MUTED,
            font=("Segoe UI", 8),
        ).pack(side="right")
        self.hidden.grid_remove()

        status = tk.Frame(self.root, bg=BG)
        status.grid(row=10, column=0, sticky="ew", padx=18, pady=(8, 14))
        self.detail = tk.Label(status, text="", bg=BG, fg=MUTED, font=("Segoe UI", 9), anchor="w")
        self.detail.pack(side="left", fill="x", expand=True)
        self.message = tk.Label(status, text="", bg=BG, fg=MUTED, font=("Segoe UI", 9), anchor="e")
        self.message.pack(side="right")

        self.root.bind("<F2>", self.toggle_hidden)
        self.root.bind("<Escape>", self.on_escape)
        self.root.bind("<space>", self.on_space)
        self.root.bind("<Left>", lambda e: self.nudge_delay(-1))
        self.root.bind("<Right>", lambda e: self.nudge_delay(1))
        self.refresh_glow_button()
        self._ready = True
        self.update_labels()

    def _caption(self, parent: tk.Misc, text: str) -> tk.Label:
        return tk.Label(
            parent,
            text=text,
            bg=BG,
            fg=MUTED,
            font=("Segoe UI", 10),
            width=16,
            anchor="w",
        )

    def _button(self, parent: tk.Misc, text: str, command, primary: bool = False) -> tk.Button:
        bg = ACCENT if primary else RAISED
        fg = DARK if primary else TEXT
        button = tk.Button(
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
        return button

    def _scale(self, parent, variable, start, end, command, trough) -> tk.Scale:
        return tk.Scale(
            parent,
            from_=start,
            to=end,
            orient="horizontal",
            showvalue=False,
            variable=variable,
            resolution=1,
            command=command if command is not None else None,
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

    def _channel_row(self, name: str, variable: tk.IntVar, color: str, trough: str):
        row = tk.Frame(self.hidden, bg="#141712")
        row.pack(fill="x", padx=12, pady=3)
        tk.Label(
            row,
            text=name,
            bg="#141712",
            fg=color,
            font=("Segoe UI", 10),
            width=12,
            anchor="w",
        ).pack(side="left")
        scale = self._scale(row, variable, -30, 30, self.on_setting, trough)
        scale.pack(side="left", fill="x", expand=True, padx=8)
        value = tk.Label(
            row, text="", bg="#141712", fg=TEXT, font=("Segoe UI", 9), width=26, anchor="e"
        )
        value.pack(side="right")
        return scale, value

    def snapshot(self) -> dict:
        with self.state_lock:
            return dict(self.state)

    def publish_state(self, **kwargs) -> None:
        with self.state_lock:
            self.state.update(kwargs)

    def read_settings(self) -> tuple[Settings, int]:
        with self.settings_lock:
            return self.settings, self.generation

    def push_settings(self) -> None:
        swap = "none"
        label = self.swap_var.get()
        for name, key in SWAP_CHOICES:
            if name == label:
                swap = key
                break
        settings = Settings(
            delay=int(self.delay_var.get()),
            off_r=int(self.off_r.get()),
            off_g=int(self.off_g.get()),
            off_b=int(self.off_b.get()),
            blur=int(self.blur_var.get()),
            glow=bool(self.glow_var.get()),
            swap=swap,
            span=int(self.delay_max),
        )
        with self.settings_lock:
            self.settings = settings
            self.generation += 1
        self.wake.set()
        self.update_labels()

    def on_setting(self, _value=None) -> None:
        if self.closed or not self._ready:
            return
        self.push_settings()

    def update_labels(self) -> None:
        fps = self.display_fps if self.display_fps > 1 else 30.0
        delay = int(self.delay_var.get())
        seconds = delay / fps
        word = "кадър" if delay == 1 else "кадъра"
        self.delay_value.configure(text=f"{delay} {word}   ·   {seconds:.2f} сек")
        blur = int(self.blur_var.get())
        if blur <= 0:
            self.blur_value.configure(text="дребен детайл")
        else:
            self.blur_value.configure(text=f"блур {blur}   ·   по-едър")
        span = self.delay_max
        for offset, label in (
            (self.off_r, self.red_value),
            (self.off_g, self.green_value),
            (self.off_b, self.blue_value),
        ):
            extra = int(offset.get())
            total = max(0, min(span * 2, delay + extra))
            label.configure(text=f"{extra:+d}    общо {total} к")
        active = any(int(var.get()) != 0 for var in (self.off_r, self.off_g, self.off_b))
        self.rgb_flag.configure(text="RGB" if active else "")

    def refresh_glow_button(self) -> None:
        if self.glow_var.get():
            self.glow_btn.configure(
                text="Сияние  ВКЛ",
                bg=WARM,
                fg=DARK,
                activebackground=WARM,
                activeforeground=DARK,
            )
        else:
            self.glow_btn.configure(
                text="Сияние",
                bg=RAISED,
                fg=TEXT,
                activebackground=RAISED,
                activeforeground=TEXT,
            )

    def send(self, command: tuple) -> None:
        self.commands.put(command)
        self.wake.set()

    def on_open(self) -> None:
        path = filedialog.askopenfilename(
            parent=self.root,
            title="Отвори клип",
            initialdir=media_dir(),
            filetypes=OPEN_TYPES,
        )
        if path:
            self.send(("file", path))

    def on_camera(self) -> None:
        if self.play_mode_ui == "live":
            self.send(("stop",))
        else:
            self.send(("camera",))

    def on_demo(self) -> None:
        if self.play_mode_ui == "demo":
            self.send(("stop",))
        else:
            self.send(("demo",))

    def on_toggle(self) -> None:
        self.send(("toggle",))

    def on_export(self) -> None:
        path = filedialog.asksaveasfilename(
            parent=self.root,
            title="Запази движението",
            initialdir=media_dir(),
            defaultextension=".mp4",
            filetypes=SAVE_TYPES,
        )
        if path:
            self.send(("export", path))

    def on_record(self) -> None:
        snap = self.snapshot()
        if snap["recording"]:
            self.send(("record", ""))
            return
        path = filedialog.asksaveasfilename(
            parent=self.root,
            title="Запис на движението",
            initialdir=media_dir(),
            defaultextension=".mp4",
            filetypes=SAVE_TYPES,
        )
        if path:
            self.send(("record", path))

    def on_cancel(self) -> None:
        self.cancel_event.set()

    def on_glow(self) -> None:
        self.glow_var.set(not self.glow_var.get())
        self.refresh_glow_button()
        self.push_settings()

    def on_reset_offsets(self) -> None:
        self.off_r.set(0)
        self.off_g.set(0)
        self.off_b.set(0)
        self.push_settings()

    def on_blur_wheel(self, event) -> str:
        step = 1 if event.delta > 0 else -1
        value = max(0, min(BLUR_MAX, int(self.blur_var.get()) + step))
        self.blur_var.set(value)
        return "break"

    def nudge_delay(self, step: int):
        focus = self.root.focus_get()
        if focus is self.seek_scale:
            return
        span = max(2, self.delay_max)
        value = max(1, min(span, int(self.delay_var.get()) + step))
        self.delay_var.set(value)
        return "break"

    def on_seek_press(self, _event=None) -> None:
        self.seeking = True

    def on_seek_release(self, _event=None) -> None:
        self.seeking = False
        if self.play_mode_ui == "file":
            self.send(("seek", int(self.seek_var.get())))

    def toggle_hidden(self, _event=None):
        self.hidden_open = not self.hidden_open
        if self.hidden_open:
            self.hidden.grid()
        else:
            self.hidden.grid_remove()
        self.root.update_idletasks()
        if self.hidden_open:
            needed = self.root.winfo_reqheight()
            if needed > self.root.winfo_height():
                height = min(needed + 8, self.root.winfo_screenheight() - 48)
                self.root.geometry(f"{max(self.root.winfo_width(), 900)}x{height}")
        self.refresh_channel_button()
        return "break"

    def refresh_channel_button(self) -> None:
        if self.hidden_open:
            self._paint(self.channel_btn, ACCENT, DARK, "Канали", True)
        else:
            self._paint(self.channel_btn, RAISED, TEXT, "Канали", True)

    def on_escape(self, _event=None):
        if self.hidden_open:
            self.toggle_hidden()
            return "break"

    def on_space(self, _event=None):
        self.on_toggle()
        return "break"

    def apply_span(self, fps: float) -> None:
        if fps > 1:
            self.display_fps = fps
        span = max(2, int(round(self.display_fps)))
        if span == self.delay_max or abs(span - self.delay_max) < 2:
            return
        self.delay_max = span
        self.delay_scale.configure(to=span)
        for scale in (self.scale_r, self.scale_g, self.scale_b):
            scale.configure(from_=-span, to=span)
        if int(self.delay_var.get()) > span:
            self.delay_var.set(span)
        for var in (self.off_r, self.off_g, self.off_b):
            value = int(var.get())
            if value > span:
                var.set(span)
            elif value < -span:
                var.set(-span)
        self.push_settings()

    def apply_chrome(self, snap: dict) -> None:
        mode = snap["mode"]
        recording = bool(snap["recording"])
        exporting = snap["export"] is not None
        playing = bool(snap["playing"])
        key = (mode, recording, exporting, playing)
        if key == self.chrome_key:
            return
        self.chrome_key = key
        self.play_mode_ui = mode

        if mode == "live":
            self._paint(self.camera_btn, ACCENT, DARK, "Спри камерата", True)
        else:
            self._paint(self.camera_btn, RAISED, TEXT, "Камера", not exporting)
        if mode == "demo":
            self._paint(self.demo_btn, ACCENT, DARK, "Спри демото", True)
        else:
            self._paint(self.demo_btn, RAISED, TEXT, "Демо", not exporting)

        self._paint(self.open_btn, ACCENT, DARK, "Отвори клип", not exporting)
        if mode == "file" and playing:
            self._paint(self.play_btn, RAISED, TEXT, "Пауза", True)
        else:
            self._paint(self.play_btn, RAISED, TEXT, "Пусни", mode == "file" and not exporting)
        self._paint(self.export_btn, RAISED, TEXT, "Експорт", mode == "file" and not exporting and not recording)
        if recording:
            self._paint(self.record_btn, RED, TEXT, "Спри записа", True)
        else:
            self._paint(
                self.record_btn,
                RAISED,
                TEXT,
                "Запис",
                mode in ("live", "demo") and not exporting,
            )
        if exporting:
            if not self.cancel_btn.winfo_ismapped():
                self.cancel_btn.pack(side="left", padx=(0, 8))
        elif self.cancel_btn.winfo_ismapped():
            self.cancel_btn.pack_forget()

        if mode == "file" and int(snap["count"]) > 1:
            self.seek_row.grid()
        else:
            self.seek_row.grid_remove()

    def _paint(self, button: tk.Button, bg: str, fg: str, text: str, enabled: bool) -> None:
        button.configure(
            text=text,
            bg=bg,
            fg=fg,
            activebackground=bg,
            activeforeground=fg,
            state="normal" if enabled else "disabled",
        )

    def apply_status(self, snap: dict) -> None:
        self.detail.configure(text=describe(snap))
        if snap["recording"]:
            self.message.configure(text=f"● ЗАПИС  {format_clock(snap['record_s'])}", fg=RED)
            return
        if snap["export"] is not None:
            self.message.configure(text=f"Експорт  {int(float(snap['export']) * 100)}%", fg=ACCENT)
            return
        msg = snap["message"]
        color = MUTED
        if msg.startswith("Грешка") or msg.startswith("Не"):
            color = RED
        elif msg.startswith("Готово") or msg.startswith("Запис"):
            color = ACCENT
        self.message.configure(text=msg, fg=color)

    def follow_playback(self, snap: dict) -> None:
        count = int(snap["count"])
        if count != self.seek_count and count > 0:
            self.seek_count = count
            self.seek_scale.configure(to=max(0, count - 1))
        if snap["mode"] == "file" and not self.seeking:
            frame = max(0, int(snap["frame"]))
            if int(self.seek_var.get()) != frame:
                self.seek_var.set(frame)

    def draw(self) -> None:
        with self.frame_lock:
            frame = self.last_bgr
        width = max(1, self.canvas.winfo_width())
        height = max(1, self.canvas.winfo_height())
        if frame is None:
            self.canvas.coords(self.placeholder_id, width // 2, height // 2)
            self.canvas.itemconfigure(self.placeholder_id, state="normal")
            if self.image_id is not None:
                self.canvas.itemconfigure(self.image_id, state="hidden")
            self.drawn_key = None
            return
        key = (id(frame), width, height)
        if key == self.drawn_key:
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
        self.canvas.itemconfigure(self.placeholder_id, state="hidden")
        self.drawn_key = key

    def tick(self) -> None:
        if not self.ui_alive:
            return
        try:
            snap = self.snapshot()
            self.apply_span(float(snap["fps"] or self.display_fps))
            self.follow_playback(snap)
            self.update_labels()
            self.apply_chrome(snap)
            self.apply_status(snap)
            self.draw()
            self.root.after(33, self.tick)
        except tk.TclError:
            return

    def publish_frame(self, frame: np.ndarray) -> None:
        with self.frame_lock:
            self.last_bgr = frame

    def clear_frame(self) -> None:
        with self.frame_lock:
            self.last_bgr = None

    def drain(self) -> list:
        items = []
        while True:
            try:
                items.append(self.commands.get_nowait())
            except queue.Empty:
                return items

    def wait_until(self, when: float) -> str:
        while True:
            remaining = when - time.perf_counter()
            if remaining <= 0:
                return "time"
            self.wake.wait(remaining)
            if self.wake.is_set():
                self.wake.clear()
                return "wake"

    def worker_loop(self) -> None:
        while True:
            commands = self.drain()
            if self.dispatch(commands):
                return
            if commands:
                continue
            if self.play_mode == "idle":
                self.wake.wait(0.08)
                self.wake.clear()
                continue

            settings, generation = self.read_settings()
            self.ensure_buffer(settings)
            if self.play_mode == "file" and not self.playing:
                if self.buffer and generation != self.rendered_gen:
                    self.rendered_gen = generation
                    self.publish_frame(render_frame(self.buffer, settings))
                self.wake.wait(0.05)
                self.wake.clear()
                continue
            if not self.wait_frame_slot():
                continue
            self.advance_clock()
            if self.play_mode == "demo":
                self.step_demo(settings, generation)
                continue
            self.step_capture(settings, generation)

    def dispatch(self, commands: list) -> bool:
        for command in commands:
            if command[0] == "quit":
                self.close_record(announce=False)
                self.release_hardware()
                return True
            try:
                self.handle_command(command)
            except Exception as exc:
                self.publish_state(message=f"Грешка: {exc}")
        return False

    def handle_command(self, command: tuple) -> None:
        kind = command[0]
        if kind == "stop":
            recorded = self.record_path
            self.close_record(announce=False)
            self.release_hardware()
            self.clear_frame()
            message = f"Запис: {os.path.basename(recorded)}" if recorded else ""
            self.publish_state(
                mode="idle",
                playing=False,
                recording=False,
                record_s=0.0,
                message=message,
                name="",
                frame=0,
                count=0,
                export=None,
                src_w=0,
                src_h=0,
            )
        elif kind == "file":
            self.open_file(command[1])
        elif kind == "camera":
            self.open_camera()
        elif kind == "demo":
            self.open_demo()
        elif kind == "toggle":
            if self.play_mode == "file" and self.cap is not None:
                self.playing = not self.playing
                self.next_due = None
                self.publish_state(playing=self.playing, mode="file")
        elif kind == "seek":
            self.seek_to(int(command[1]))
        elif kind == "export":
            self.run_export(command[1])
        elif kind == "record":
            if command[1]:
                self.start_record(command[1])
            else:
                self.close_record(announce=True)

    def release_hardware(self) -> None:
        if self.cap is not None:
            self.cap.release()
            self.cap = None
        self.buffer.clear()
        self.playing = False
        self.play_mode = "idle"
        self.file_path = ""
        self.frame_index = -1
        self.next_due = None
        self.read_fails = 0
        self.demo_static = None
        self.fps_ema = None
        self.last_frame_t = None

    def open_file(self, path: str) -> None:
        self.close_record(announce=False)
        self.release_hardware()
        capture = cv2.VideoCapture(path)
        if not capture.isOpened():
            self.clear_frame()
            self.publish_state(mode="idle", playing=False, message="Не мога да отворя този клип.")
            return
        fps = float(capture.get(cv2.CAP_PROP_FPS) or 0)
        if fps < 1 or fps > 240:
            fps = 30.0
        self.cap = capture
        self.file_path = path
        self.file_fps = fps
        self.file_count = int(capture.get(cv2.CAP_PROP_FRAME_COUNT) or 0)
        self.src_w = int(capture.get(cv2.CAP_PROP_FRAME_WIDTH) or 0)
        self.src_h = int(capture.get(cv2.CAP_PROP_FRAME_HEIGHT) or 0)
        self.play_mode = "file"
        self.playing = True
        self.frame_index = -1
        self.buffer.clear()
        self.next_due = None
        self.publish_state(
            mode="file",
            playing=True,
            fps=fps,
            count=self.file_count,
            frame=0,
            src_w=self.src_w,
            src_h=self.src_h,
            name=os.path.basename(path),
            message="",
            export=None,
            recording=False,
        )

    def open_camera(self) -> None:
        self.close_record(announce=False)
        self.release_hardware()
        capture = cv2.VideoCapture(0, cv2.CAP_DSHOW)
        if not capture.isOpened():
            capture.release()
            capture = cv2.VideoCapture(0)
        if not capture.isOpened():
            self.publish_state(mode="idle", playing=False, message="Камерата не се отваря.")
            return
        capture.set(cv2.CAP_PROP_FRAME_WIDTH, 1280)
        capture.set(cv2.CAP_PROP_FRAME_HEIGHT, 720)
        capture.set(cv2.CAP_PROP_BUFFERSIZE, 1)
        self.cap = capture
        self.play_mode = "live"
        self.playing = True
        self.file_fps = 30.0
        self.buffer.clear()
        self.frame_index = -1
        self.publish_state(
            mode="live",
            playing=True,
            fps=30.0,
            count=0,
            frame=0,
            name="Камера",
            message="",
            export=None,
            recording=False,
            src_w=0,
            src_h=0,
        )

    def open_demo(self) -> None:
        self.close_record(announce=False)
        self.release_hardware()
        height, width = 540, 960
        generator = np.random.default_rng(1)
        base = generator.integers(28, 46, size=(height, width), dtype=np.uint8)
        static = np.dstack((base + 14, base, base + 6)).clip(0, 255).astype(np.uint8)
        cv2.rectangle(static, (70, 48), (310, 150), (72, 64, 58), -1)
        cv2.rectangle(static, (680, 360), (900, 490), (48, 58, 74), -1)
        cv2.line(static, (0, 270), (width, 270), (60, 62, 58), 2)
        self.demo_static = static
        self.demo_i = 0
        self.play_mode = "demo"
        self.playing = True
        self.file_fps = 30.0
        self.src_w, self.src_h = width, height
        self.buffer.clear()
        self.frame_index = -1
        self.next_due = None
        self.publish_state(
            mode="demo",
            playing=True,
            fps=30.0,
            count=0,
            frame=0,
            src_w=width,
            src_h=height,
            name="Демо",
            message="",
            export=None,
            recording=False,
        )

    def seek_to(self, target: int) -> None:
        if self.cap is None or self.play_mode != "file":
            return
        settings, generation = self.read_settings()
        span = max(2, int(settings.span))
        target = max(0, int(target))
        if self.file_count > 0:
            target = min(self.file_count - 1, target)
        start = max(0, target - span * 2)
        self.cap.set(cv2.CAP_PROP_POS_FRAMES, start)
        self.buffer = deque(maxlen=span * 2 + 3)
        index = start
        while index <= target:
            ok, frame = self.cap.read()
            if not ok:
                break
            raw = normalize(frame)
            self.src_h, self.src_w = raw.shape[:2]
            self.buffer.append(downscale(raw, 1280, 720))
            index += 1
        self.frame_index = index - 1
        self.next_due = None
        if not self.buffer:
            return
        self.rendered_gen = generation
        self.finish_frame(render_frame(self.buffer, settings))

    def run_export(self, path: str) -> None:
        if self.play_mode != "file" or not self.file_path:
            self.publish_state(message="Експортът е за отворен клип.")
            return
        settings, _generation = self.read_settings()
        position = max(0, self.frame_index)
        source = self.file_path
        self.playing = False
        self.publish_state(playing=False, export=0.0, message="")
        if self.cap is not None:
            self.cap.release()
            self.cap = None
        self.cancel_event.clear()

        def progress(done: int, total: int) -> None:
            fraction = (done / total) if total else 0.0
            self.publish_state(export=fraction)

        try:
            export_video(
                source,
                path,
                settings,
                progress=progress,
                cancel=self.cancel_event.is_set,
            )
        except Exception as exc:
            self.publish_state(export=None, message=f"Грешка: {exc}")
        else:
            if self.cancel_event.is_set():
                try:
                    os.remove(path)
                except OSError:
                    pass
                self.publish_state(export=None, message="Експортът е спрян.")
            else:
                self.publish_state(
                    export=None,
                    message=f"Готово: {os.path.basename(path)}  ·  без звук",
                )
        capture = cv2.VideoCapture(source)
        if capture.isOpened():
            self.cap = capture
            self.play_mode = "file"
            self.file_path = source
            self.playing = False
            self.seek_to(position)
            self.publish_state(mode="file", playing=False, export=None)
        else:
            self.publish_state(mode="idle", playing=False, export=None)

    def start_record(self, path: str) -> None:
        if self.play_mode not in ("live", "demo"):
            self.publish_state(message="Записът е за камера или демо.")
            return
        self.close_record(announce=False)
        self.record_path = path
        self.record_writer = None
        self.record_started = time.perf_counter()
        self.publish_state(recording=True, record_s=0.0, message="")

    def close_record(self, announce: bool) -> None:
        path = self.record_path
        if self.record_writer is not None:
            self.record_writer.release()
            self.record_writer = None
        self.record_path = ""
        if announce and path:
            self.publish_state(recording=False, record_s=0.0, message=f"Запис: {os.path.basename(path)}")
        elif path:
            self.publish_state(recording=False, record_s=0.0)

    def write_record(self, frame: np.ndarray) -> None:
        if not self.record_path:
            return
        if self.record_writer is None:
            height, width = frame.shape[:2]
            fps = self.current_fps()
            if fps < 1 or fps > 120:
                fps = 30.0
            ext = os.path.splitext(self.record_path)[1].lower()
            code = "XVID" if ext == ".avi" else "mp4v"
            writer = cv2.VideoWriter(
                self.record_path,
                cv2.VideoWriter_fourcc(*code),
                float(fps),
                (width, height),
            )
            if not writer.isOpened():
                self.record_path = ""
                self.publish_state(recording=False, message="Не мога да запиша видеото.")
                return
            self.record_writer = writer
        self.record_writer.write(frame)

    def ensure_buffer(self, settings: Settings) -> None:
        need = max(2, int(settings.span)) * 2 + 3
        if self.buffer.maxlen != need:
            self.buffer = deque(self.buffer, maxlen=need)

    def current_fps(self) -> float:
        if self.play_mode == "live" and self.fps_ema:
            return float(self.fps_ema)
        if self.file_fps > 1:
            return float(self.file_fps)
        return 30.0

    def note_time(self) -> None:
        now = time.perf_counter()
        if self.last_frame_t is not None:
            delta = now - self.last_frame_t
            if 0.005 < delta < 0.5:
                instant = 1.0 / delta
                self.fps_ema = instant if self.fps_ema is None else self.fps_ema * 0.85 + instant * 0.15
        self.last_frame_t = now

    def wait_frame_slot(self) -> bool:
        if self.play_mode == "live":
            return True
        fps = self.file_fps if self.file_fps > 1 else 30.0
        if self.next_due is None:
            self.next_due = time.perf_counter()
        if time.perf_counter() >= self.next_due:
            return True
        return self.wait_until(self.next_due) == "time"

    def advance_clock(self) -> None:
        if self.play_mode == "live":
            return
        fps = self.file_fps if self.file_fps > 1 else 30.0
        if self.next_due is None:
            self.next_due = time.perf_counter()
        self.next_due += 1.0 / fps
        if time.perf_counter() - self.next_due > 0.5:
            self.next_due = time.perf_counter()

    def step_demo(self, settings: Settings, generation: int) -> None:
        frame = self.make_demo_frame()
        self.buffer.append(frame)
        self.frame_index += 1
        self.src_w, self.src_h = frame.shape[1], frame.shape[0]
        self.rendered_gen = generation
        self.finish_frame(render_frame(self.buffer, settings))

    def make_demo_frame(self) -> np.ndarray:
        assert self.demo_static is not None
        frame = self.demo_static.copy()
        index = self.demo_i
        self.demo_i += 1
        height, width = frame.shape[:2]
        x = 80 + (index * 9) % (width - 170)
        y = int(height * 0.46 + math.sin(index / 8.0) * 100)
        cv2.circle(frame, (x, y), 32, (235, 235, 242), -1)
        x2 = 30 + (index * 4) % (width - 240)
        cv2.rectangle(frame, (x2, height - 130), (x2 + 130, height - 78), (40, 170, 230), -1)
        return frame

    def step_capture(self, settings: Settings, generation: int) -> None:
        if self.cap is None:
            return
        ok, frame = self.cap.read()
        if not ok:
            self.read_fails += 1
            if self.play_mode == "file" and self.cap is not None and self.read_fails == 1:
                self.cap.set(cv2.CAP_PROP_POS_FRAMES, 0)
                self.buffer.clear()
                self.frame_index = -1
                self.rendered_gen = -1
                return
            if self.play_mode == "live" and self.read_fails < 20:
                return
            self.publish_state(message="Източникът спря.")
            self.handle_command(("stop",))
            return
        self.read_fails = 0
        if self.play_mode == "live":
            self.note_time()
        raw = normalize(frame)
        self.src_h, self.src_w = raw.shape[:2]
        self.buffer.append(downscale(raw, 1280, 720))
        self.frame_index += 1
        self.rendered_gen = generation
        self.finish_frame(render_frame(self.buffer, settings))

    def finish_frame(self, frame: np.ndarray) -> None:
        self.publish_frame(frame)
        self.write_record(frame)
        recording = bool(self.record_path)
        elapsed = time.perf_counter() - self.record_started if recording else 0.0
        self.publish_state(
            mode=self.play_mode,
            playing=self.playing,
            frame=max(0, self.frame_index),
            src_w=self.src_w,
            src_h=self.src_h,
            fps=self.current_fps(),
            recording=recording,
            record_s=elapsed,
        )

    def on_close(self) -> None:
        self.shutdown()

    def shutdown(self) -> None:
        if self.closed:
            return
        self.closed = True
        self.ui_alive = False
        exporting = self.snapshot()["export"] is not None
        self.cancel_event.set()
        try:
            self.commands.put(("quit",))
            self.wake.set()
        except Exception:
            pass
        if self.worker.is_alive():
            self.worker.join(timeout=4.0 if exporting else 0.4)
        try:
            self.root.destroy()
        except tk.TclError:
            pass


def run_self_test() -> int:
    import tempfile

    sample = os.path.join(tempfile.gettempdir(), "motion_extract_sample.mp4")
    exported = os.path.join(tempfile.gettempdir(), "motion_extract_out.mp4")
    writer = cv2.VideoWriter(sample, cv2.VideoWriter_fourcc(*"mp4v"), 25, (160, 90))
    if not writer.isOpened():
        print("self-test: cannot write sample")
        return 1
    for index in range(16):
        frame = np.full((90, 160, 3), 30, np.uint8)
        x = 8 + index * 8
        cv2.rectangle(frame, (x, 28), (x + 24, 62), (240, 240, 240), -1)
        writer.write(frame)
    writer.release()

    app = MotionApp(visible=False)
    try:
        app.root.update()
        app.toggle_hidden()
        app.root.update()
        app.toggle_hidden()
        app.glow_var.set(True)
        app.refresh_glow_button()
        app.blur_var.set(3)
        app.off_r.set(2)
        app.push_settings()
        app.root.update()
        app.send(("file", sample))
        deadline = time.perf_counter() + 4.0
        seen = None
        while time.perf_counter() < deadline:
            app.root.update()
            with app.frame_lock:
                if app.last_bgr is not None:
                    seen = app.last_bgr.copy()
                    break
            time.sleep(0.02)
        if seen is None:
            print("self-test: no frame")
            return 1
        if seen.ndim != 3 or seen.shape[2] != 3:
            print("self-test: bad frame", seen.shape)
            return 1
        app.send(("export", exported))
        deadline = time.perf_counter() + 20.0
        while time.perf_counter() < deadline:
            app.root.update()
            message = app.snapshot()["message"]
            if message.startswith("Готово"):
                break
            if message.startswith("Грешка"):
                print("self-test:", message)
                return 1
            time.sleep(0.02)
        else:
            print("self-test: export timeout", app.snapshot()["message"])
            return 1
        check = cv2.VideoCapture(exported)
        count = int(check.get(cv2.CAP_PROP_FRAME_COUNT) or 0)
        ok, frame = check.read()
        check.release()
        if not ok or count < 10:
            print("self-test: export unreadable", count, ok)
            return 1
        still = np.full((12, 12, 3), 80, np.uint8)
        gray = render_frame(deque([still, still.copy()]), Settings())
        if not np.all(gray == 127):
            print("self-test: mix failed")
            return 1
        print("app self-test: ok", seen.shape, "frames", count)
        return 0
    finally:
        app.shutdown()
        for path in (sample, exported):
            try:
                os.remove(path)
            except OSError:
                pass


def main() -> None:
    if "--self-test" in sys.argv:
        raise SystemExit(run_self_test())
    MotionApp().root.mainloop()


if __name__ == "__main__":
    main()
