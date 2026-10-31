# Motion Extraction

Приложение за извличане на движение от клип или от камера. Кадърът се обръща, прави се полупрозрачен и се забавя. Неподвижното става сиво, движението остава цветно.

Има версия за телефон и версия за Windows.

## Телефон

Свали **Motion-Extraction-1.14.apk** от [Releases](https://github.com/smouky577/motion-extraction/releases) и го инсталирай. Ако Android попита, разреши инсталация от този файл.

Кодът за телефона е в папката `android`.

## Windows

Свали **Motion-Extraction-Windows-1.13.zip** от [Releases](https://github.com/smouky577/motion-extraction/releases). Цъкни върху името на zip файла. Той се сваля цял.

Не отваряй `app.py` и `engine.py` в сайта и не копирай текста в нов файл. Така файловете само се показват, не се свалят.

Разархивирай zip-а. В папката има:

- `Motion Extraction.bat` — пуска програмата
- `app.py` — прозорецът
- `engine.py` — самият ефект

Кликни два пъти върху `Motion Extraction.bat`. Трите файла трябва да останат в една папка.

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

Download **Motion-Extraction-1.14.apk** from [Releases](https://github.com/smouky577/motion-extraction/releases) and install it. Allow installation from this file if Android asks.

The phone source is in the `android` folder.

## Windows

Download **Motion-Extraction-Windows-1.13.zip** from [Releases](https://github.com/smouky577/motion-extraction/releases). Click the zip file name. The whole archive downloads.

Do not open `app.py` or `engine.py` on the site and do not paste them into a new file. That only shows the text.

Unzip the archive. The folder contains:

- `Motion Extraction.bat` — starts the program
- `app.py` — the window
- `engine.py` — the effect

Double-click `Motion Extraction.bat`. Keep the three files in the same folder.

The batch file starts `C:\Python314\pythonw.exe` with `app.py`. That Python already has OpenCV, NumPy, and Pillow.

If the window does not open, open the folder in a command prompt and run:

```bat
C:\Python314\python.exe app.py
```

Any missing piece is printed on screen.
