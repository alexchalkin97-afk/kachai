"""Generate RuStore mockup HTML pages from template.html and render them to PNG with headless Edge."""
import subprocess
import sys
from pathlib import Path

ROOT = Path(sys.argv[1]).resolve()  # dist/rustore
EDGE = r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
CREDIT = "Видео в примерах: Blender Foundation, лицензия CC BY"

SLIDES = [
    ("01-link", "home", "", "Скачивайте видео по&nbsp;ссылке",
     "Вставьте ссылку или нажмите «Поделиться» → «Качай»", ""),
    ("02-quality", "result", "", "Любое качество — до&nbsp;4K",
     "От лёгких 360p до 4K для монтажа", CREDIT),
    ("03-audio", "audio", "", "Только звук в&nbsp;MP3 и&nbsp;M4A",
     "Музыка и озвучка для ваших роликов", CREDIT),
    ("04-background", "progress", "", "Быстро и&nbsp;в&nbsp;фоне",
     "Скорость и время до конца — прямо в списке", CREDIT),
    ("05-library", "downloads", "", "Всё скачанное под&nbsp;рукой",
     "Файлы сразу в папке «Загрузки/Качай»", CREDIT),
    ("06-dark", "dark_downloads", "dark", "Светлая и&nbsp;тёмная тема",
     "1000+ сайтов: VK Видео, Rutube, Дзен и другие", CREDIT),
]

template = (ROOT / "mockups" / "template.html").read_text(encoding="utf-8")
for name, screen, theme, title, subtitle, credit in SLIDES:
    html = (template.replace("{{THEME}}", theme).replace("{{TITLE}}", title)
            .replace("{{SUBTITLE}}", subtitle).replace("{{SCREEN}}", screen).replace("{{CREDIT}}", credit))
    page = ROOT / "mockups" / f"{name}.html"
    page.write_text(html, encoding="utf-8")
    out = ROOT / f"{name}.png"
    subprocess.run([
        EDGE, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--force-device-scale-factor=1",
        "--window-size=1080,1920", "--virtual-time-budget=8000",
        f"--screenshot={out}", page.as_uri(),
    ], check=True, capture_output=True, timeout=120)
    print(out.name, out.stat().st_size)
