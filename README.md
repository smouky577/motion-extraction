# Motion Extraction

Приложение за извличане на движение от клип или от камера. Кадърът се обръща, прави се полупрозрачен и се забавя. Неподвижното става сиво, движението остава цветно.

Има версия за телефон и версия за Windows.

## Телефон

Свали **Motion-Extraction-1.13.apk** от [Releases](https://github.com/smouky577/motion-extraction/releases) и го инсталирай. Ако Android попита, разреши инсталация от този файл.

Кодът за телефона е в папката `android`.

## Windows

Кодът за Windows е в корена на проекта, до този файл:

- `Motion Extraction.bat` — пуска програмата
- `app.py` — прозорецът
- `engine.py` — самият ефект

**Пускане:** отвори папката и кликни два пъти върху `Motion Extraction.bat`.

Батникът стартира `C:\Python314\pythonw.exe` с `app.py`. В този Python вече са нужните библиотеки: OpenCV, NumPy и Pillow.

Ако прозорецът не се появи, отвори папката в командния ред и пусни:

```bat
C:\Python314\python.exe app.py
```

Така на екрана се вижда какво липсва.

---

# Motion Extraction (English)

Motion extraction for a clip or a live camera. The frame is inverted, made half transparent, and delayed. Still areas turn gray. Motion stays in color.

There is a phone app and a Windows app.

## Phone

Download **Motion-Extraction-1.13.apk** from [Releases](https://github.com/smouky577/motion-extraction/releases) and install it. Allow installation from this file if Android asks.

The phone source is in the `android` folder.

## Windows

The Windows source is in the project root, next to this file:

- `Motion Extraction.bat` — starts the program
- `app.py` — the window
- `engine.py` — the effect

**Run it:** open the folder and double-click `Motion Extraction.bat`.

The batch file starts `C:\Python314\pythonw.exe` with `app.py`. That Python already has OpenCV, NumPy, and Pillow.

If the window does not open, open the folder in a command prompt and run:

```bat
C:\Python314\python.exe app.py
```

Any missing piece is printed on screen.
