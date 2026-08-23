#!/usr/bin/env python3
# 4pda topic dumper — generic (нет твоего паттерна, помечаю generic).
# Идея: headed-браузер проходит Cloudflare один раз (руками), дальше листает сам.
# Голый requests/net-http тут не работает — CF отдаёт JS-челлендж.
#
# Установка:
#   pip install playwright
#   playwright install chromium
# Запуск:
#   python scrape_4pda.py
# Результат: папка dump/page_00000.html ... — заархивируй и загрузи мне.

import asyncio, os, re
from playwright.async_api import async_playwright

TOPIC   = 917380          # id темы
OUT     = "dump"
STEP    = 20              # 4pda: 20 постов на страницу
DELAY_S = 4.0             # пауза между страницами — не долби, поймаешь бан/капчу
BASE    = "https://4pda.to/forum/index.php"

async def detect_max_st(page) -> int:
    # берём все ссылки пагинации вида ...&st=NNN и находим максимум
    hrefs = await page.eval_on_selector_all(
        "a[href*='st=']", "els => els.map(e => e.getAttribute('href'))")
    mx = 0
    for h in hrefs or []:
        m = re.search(r"st=(\d+)", h or "")
        if m:
            mx = max(mx, int(m.group(1)))
    return mx

async def main():
    os.makedirs(OUT, exist_ok=True)
    async with async_playwright() as p:
        ctx = await p.chromium.launch_persistent_context(
            user_data_dir="./pw-profile",     # держит cookies/cf_clearance между запусками
            headless=False,                    # ВАЖНО: headless CF почти всегда режет
            viewport={"width": 1280, "height": 900},
            args=["--disable-blink-features=AutomationControlled"],
        )
        page = ctx.pages[0] if ctx.pages else await ctx.new_page()

        await page.goto(f"{BASE}?showtopic={TOPIC}", wait_until="domcontentloaded")
        input(">>> Пройди Cloudflare/залогинься в окне браузера, потом нажми Enter здесь... ")

        max_st = await detect_max_st(page)
        if max_st == 0:
            max_st = 2140  # фолбэк, если пагинацию не нашли — поправь руками
        print(f"Последний st = {max_st} (~{max_st // STEP + 1} страниц)")

        for st in range(0, max_st + STEP, STEP):
            url = f"{BASE}?showtopic={TOPIC}&st={st}"
            try:
                await page.goto(url, wait_until="domcontentloaded")
                await page.wait_for_timeout(1500)          # догрузка постов
                html = await page.content()
                # грубая проверка, что это не страница CF-челленджа
                if "Just a moment" in html or "cf-challenge" in html:
                    print(f"[!] st={st}: похоже на Cloudflare-челлендж — пройди руками и Enter")
                    input("    Enter для продолжения... ")
                    html = await page.content()
                path = os.path.join(OUT, f"page_{st:05d}.html")
                with open(path, "w", encoding="utf-8") as f:
                    f.write(html)
                print(f"saved st={st} -> {path}")
            except Exception as e:
                print(f"[err] st={st}: {e} — пропускаю")
            await page.wait_for_timeout(int(DELAY_S * 1000))

        await ctx.close()
        print("Готово. Заархивируй папку dump/ и загрузи в чат.")

if __name__ == "__main__":
    asyncio.run(main())
