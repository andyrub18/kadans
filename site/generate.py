#!/usr/bin/env python3
"""Builds the website (https://kadansplanning.com) into site/public: plain files nginx serves (deploy/, DEPLOYMENT →
The website). Never edit site/public; change the texts, the legal pages or the assets here and run it again:

    python3 site/generate.py           # writes site/public
    python3 site/generate.py --check   # fails when site/public is not what this makes (CI runs it)

Every page exists in English, French and Kreyòl (/en/…, /fr/…, /ht/…). The words are in texts.py, the privacy policy
and the terms in legal/. The mark comes from branding/ (drawn by branding/generate.py). Standard library only, and the
same output on every run: no dates or random names.
"""
import filecmp
import hashlib
import html
import shutil
import sys
import tempfile
from pathlib import Path

from texts import LANGUAGES, PLAY_STORE, RELEASES, TEXTS

SITE = Path(__file__).resolve().parent
ROOT = SITE.parent
DOMAIN = "https://kadansplanning.com"
PAGES = ["", "download", "support", "delete-account", "privacy", "terms"]

# The files every page may load (the site's Content-Security-Policy allows its own and nothing else).
ASSETS = {
    "style.css": SITE / "assets" / "style.css",
    "download.js": SITE / "assets" / "download.js",
    "kadans-icon.svg": ROOT / "branding" / "kadans-icon.svg",
    "kadans-icon-512.png": ROOT / "branding" / "play-store-icon.png",
}


def version(name):
    """A short hash of an asset's content: its address changes with it, so browsers may keep it a week."""
    return hashlib.sha256(ASSETS[name].read_bytes()).hexdigest()[:10]


def asset(name):
    return f"/assets/{name}?v={version(name)}"


def path(lang, page):
    return f"/{lang}/{page}" if page else f"/{lang}/"


def layout(lang, page, title, description, body, scripts=()):
    t = TEXTS[lang]
    alternates = "\n".join(
        f'<link rel="alternate" hreflang="{other}" href="{DOMAIN}{path(other, page)}">' for other in LANGUAGES
    )
    switcher = " ".join(
        f'<a href="{path(other, page)}" lang="{other}" hreflang="{other}"'
        + (' aria-current="true"' if other == lang else "")
        + f">{html.escape(TEXTS[other]['language_name'])}</a>"
        for other in LANGUAGES
    )
    nav = (
        f'<a href="{path(lang, "download")}"{current(page, "download")}>{t["nav_download"]}</a>'
        f'<a href="{path(lang, "support")}"{current(page, "support")}>{t["nav_support"]}</a>'
    )
    footer = " · ".join(
        f'<a href="{path(lang, slug)}">{t[key]}</a>'
        for slug, key in [("privacy", "footer_privacy"), ("terms", "footer_terms"), ("support", "footer_support"),
                          ("delete-account", "footer_delete")]
    )
    script_tags = "".join(f'\n<script src="{asset(name)}" defer></script>' for name in scripts)
    return f"""<!doctype html>
<html lang="{lang}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{html.escape(title)}</title>
<meta name="description" content="{html.escape(description)}">
<meta name="color-scheme" content="light dark">
<meta name="theme-color" content="#6750A4">
<link rel="canonical" href="{DOMAIN}{path(lang, page)}">
{alternates}
<link rel="alternate" hreflang="x-default" href="{DOMAIN}/{page}">
<link rel="icon" href="{asset("kadans-icon.svg")}" type="image/svg+xml">
<link rel="apple-touch-icon" href="{asset("kadans-icon-512.png")}">
<link rel="stylesheet" href="{asset("style.css")}">{script_tags}
</head>
<body>
<header class="top">
<a class="brand" href="{path(lang, "")}"><img src="{asset("kadans-icon.svg")}" alt="" width="32" height="32">Kadans</a>
<nav class="main" aria-label="{t["nav_label"]}">{nav}</nav>
<nav class="languages" aria-label="{t["language_label"]}">{switcher}</nav>
</header>
<main>
{body}
</main>
<footer class="bottom">
<p>{footer}</p>
<p>© 2026 Kadans</p>
</footer>
</body>
</html>
"""


def current(page, slug):
    return ' aria-current="page"' if page == slug else ""


def store_button(t, css="button primary"):
    return f'<a class="{css}" href="{PLAY_STORE}">{t["play_button"]}</a>'


def home(lang):
    t = TEXTS[lang]
    features = "\n".join(
        f'<article class="card"><h3>{t[f"f{i}_title"]}</h3><p>{t[f"f{i}_text"]}</p></article>' for i in (1, 2, 3)
    )
    body = f"""<section class="hero">
<img class="hero-mark" src="{asset("kadans-icon.svg")}" alt="" width="112" height="112">
<h1>{t["home_h1"]}</h1>
<p class="lead">{t["home_lead"]}</p>
<p class="actions">{store_button(t)}<a class="button" href="{path(lang, "download")}">{t["home_desktop_button"]}</a></p>
<p class="note">{t["home_price"]}</p>
</section>
<section>
<h2>{t["home_features_title"]}</h2>
<div class="cards">
{features}
</div>
</section>
<section class="split">
<div><h2>{t["home_sync_title"]}</h2><p>{t["home_sync_text"]}</p><p>{t["home_languages"]}</p></div>
<div><h2>{t["home_private_title"]}</h2><p>{t["home_private_text"]}</p>
<p><a href="{path(lang, "privacy")}">{t["home_private_link"]}</a></p></div>
</section>"""
    return layout(lang, "", t["home_title"], t["description"], body)


def download(lang):
    t = TEXTS[lang]

    def desktop(platform, file, title, button, install):
        return f"""<article class="card download" data-platform="{platform}">
<p class="suggested-label">{t["dl_suggested"]}</p>
<h3>{title}</h3>
<p><a class="button primary" href="/files/{file}">{button}</a></p>
<p class="install">{install}</p>
</article>"""

    body = f"""<h1>{t["dl_h1"]}</h1>
<p class="lead">{t["dl_lead"]}</p>
<section>
<h2>{t["dl_phone_title"]}</h2>
<div class="cards">
<article class="card"><h3>Android</h3><p>{store_button(t)}</p><p class="install">{t["dl_android_note"]}</p></article>
<article class="card muted"><h3>iPhone</h3><p>{t["dl_later"]}</p></article>
</div>
</section>
<section>
<h2>{t["dl_desktop_title"]}</h2>
<div class="cards" id="desktop">
{desktop("deb", "kadans-linux-amd64.deb", t["dl_deb_title"], t["dl_deb_button"], t["dl_deb_install"])}
{desktop("rpm", "kadans-linux-x86_64.rpm", t["dl_rpm_title"], t["dl_rpm_button"], t["dl_rpm_install"])}
{desktop("windows", "kadans-windows-x64.msi", t["dl_win_title"], t["dl_win_button"], t["dl_win_install"])}
<article class="card muted"><h3>Mac</h3><p>{t["dl_later"]}</p></article>
</div>
<p class="note">{t["dl_updates"].format(releases=RELEASES)}</p>
</section>"""
    return layout(lang, "download", t["dl_title"], t["description"], body, scripts=["download.js"])


def support(lang):
    t = TEXTS[lang]
    faq = "\n".join(
        f'<details><summary>{t[f"q{i}"]}</summary><p>{t[f"a{i}"].format(delete=path(lang, "delete-account"))}</p></details>'
        for i in range(1, 6)
    )
    body = f"""<h1>{t["support_h1"]}</h1>
<p class="lead">{t["support_lead"]}</p>
<section>
<h2>{t["support_faq"]}</h2>
<div class="faq">
{faq}
</div>
</section>"""
    return layout(lang, "support", t["support_title"], t["description"], body)


def delete_account(lang):
    t = TEXTS[lang]
    steps = "\n".join(f"<li>{line}</li>" for line in t["del_what"])
    body = f"""<h1>{t["del_h1"]}</h1>
<p class="lead">{t["del_lead"]}</p>
<div class="cards two">
<article class="card"><h2>{t["del_app_title"]}</h2><p>{t["del_app"]}</p></article>
<article class="card"><h2>{t["del_web_title"]}</h2><p>{t["del_web"]}</p></article>
</div>
<section>
<h2>{t["del_what_title"]}</h2>
<ul>
{steps}
</ul>
</section>"""
    return layout(lang, "delete-account", t["del_title"], t["description"], body)


def legal(lang, page):
    t = TEXTS[lang]
    text = (SITE / "legal" / f"{page}.{lang}.html").read_text(encoding="utf-8").strip()
    title = t["privacy_title"] if page == "privacy" else t["terms_title"]
    return layout(lang, page, title, t["description"], f'<article class="legal">\n{text}\n</article>')


def not_found():
    """One page for every language: nginx shows it for any address it does not have."""
    blocks = "\n".join(
        f'<section lang="{lang}"><h1>{TEXTS[lang]["nf_h1"]}</h1>'
        f'<p><a href="{path(lang, "")}">{TEXTS[lang]["nf_home"]}</a></p></section>'
        for lang in LANGUAGES
    )
    page = layout("en", "", TEXTS["en"]["nf_h1"], TEXTS["en"]["description"], blocks)
    return page.replace('<link rel="canonical" href="https://kadansplanning.com/en/">\n', "")


def sitemap():
    urls = "\n".join(
        f"<url><loc>{DOMAIN}{path(lang, page)}</loc>"
        + "".join(
            f'<xhtml:link rel="alternate" hreflang="{other}" href="{DOMAIN}{path(other, page)}"/>' for other in LANGUAGES
        )
        + "</url>"
        for page in PAGES
        for lang in LANGUAGES
    )
    return (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:xhtml="http://www.w3.org/1999/xhtml">\n'
        f"{urls}\n</urlset>\n"
    )


def build(out):
    files = {"404.html": not_found(), "robots.txt": f"User-agent: *\nAllow: /\nSitemap: {DOMAIN}/sitemap.xml\n",
             "sitemap.xml": sitemap()}
    for lang in LANGUAGES:
        files[f"{lang}/index.html"] = home(lang)
        files[f"{lang}/download.html"] = download(lang)
        files[f"{lang}/support.html"] = support(lang)
        files[f"{lang}/delete-account.html"] = delete_account(lang)
        files[f"{lang}/privacy.html"] = legal(lang, "privacy")
        files[f"{lang}/terms.html"] = legal(lang, "terms")
    for name, text in files.items():
        target = out / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding="utf-8")
    (out / "assets").mkdir(parents=True, exist_ok=True)
    for name, source in ASSETS.items():
        shutil.copyfile(source, out / "assets" / name)


def same_tree(a, b):
    compared = filecmp.dircmp(a, b)
    if compared.left_only or compared.right_only or compared.diff_files or compared.funny_files:
        return False
    # dircmp compares by size and date: look at the bytes of the files it calls the same.
    for name in compared.common_files:
        if (Path(a) / name).read_bytes() != (Path(b) / name).read_bytes():
            return False
    return all(same_tree(Path(a) / sub, Path(b) / sub) for sub in compared.common_dirs)


def main():
    public = SITE / "public"
    if "--check" in sys.argv:
        with tempfile.TemporaryDirectory() as scratch:
            build(Path(scratch))
            if not public.is_dir() or not same_tree(scratch, public):
                print("site/public is not what site/generate.py makes: run `python3 site/generate.py` and commit it.")
                sys.exit(1)
        print("site/public is up to date.")
        return
    # Into place file by file, never removing the folder: nginx and a running test server keep a handle on it.
    with tempfile.TemporaryDirectory() as scratch:
        build(Path(scratch))
        made = {p.relative_to(scratch) for p in Path(scratch).rglob("*") if p.is_file()}
        for name in made:
            target = public / name
            target.parent.mkdir(parents=True, exist_ok=True)
            if not target.exists() or target.read_bytes() != (Path(scratch) / name).read_bytes():
                shutil.copyfile(Path(scratch) / name, target)
    for stale in sorted(public.rglob("*"), reverse=True):
        if stale.is_file() and stale.relative_to(public) not in made:
            stale.unlink()
        elif stale.is_dir() and not any(stale.iterdir()):
            stale.rmdir()
    print(f"wrote {len(made)} files to site/public")


if __name__ == "__main__":
    main()
