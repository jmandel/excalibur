#!/usr/bin/env python3
"""Create tiny, legally unencumbered EPUB fixtures for converter tests."""
from __future__ import annotations

import base64
import os
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "fixtures" / "generated"

PNG_1X1_RED = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII="
)


def write_epub(name: str, files: dict[str, bytes | str]) -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    path = OUT / name
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("mimetype", "application/epub+zip", compress_type=zipfile.ZIP_STORED)
        for fn, data in files.items():
            if isinstance(data, str):
                data = data.encode("utf-8")
            z.writestr(fn, data, compress_type=zipfile.ZIP_DEFLATED)
    print(path)


def container() -> str:
    return '''<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>
'''


def nav(title="Contents") -> str:
    return f'''<!doctype html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><title>{title}</title></head>
<body>
<nav epub:type="toc" id="toc"><ol>
<li><a href="chapter1.xhtml">Chapter 1</a></li>
<li><a href="chapter2.xhtml#start">Chapter 2</a></li>
</ol></nav>
</body></html>
'''


def ncx(title="Minimal") -> str:
    return f'''<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
<head><meta name="dtb:uid" content="urn:uuid:fixture"/></head>
<docTitle><text>{title}</text></docTitle>
<navMap>
<navPoint id="nav1" playOrder="1"><navLabel><text>Chapter 1</text></navLabel><content src="chapter1.xhtml"/></navPoint>
<navPoint id="nav2" playOrder="2"><navLabel><text>Chapter 2</text></navLabel><content src="chapter2.xhtml#start"/></navPoint>
</navMap>
</ncx>
'''


def opf(title: str, manifest_extra: str, spine: str, guide: str = "") -> str:
    return f'''<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="uid">urn:uuid:{title.lower().replace(' ', '-')}</dc:identifier>
    <dc:title>{title}</dc:title>
    <dc:creator>Test Fixture</dc:creator>
    <dc:language>en</dc:language>
    <meta property="dcterms:modified">2026-06-27T00:00:00Z</meta>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
    <item id="ch1" href="chapter1.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch2" href="chapter2.xhtml" media-type="application/xhtml+xml"/>
{manifest_extra}
  </manifest>
  <spine toc="ncx">
{spine}
  </spine>
{guide}
</package>
'''


def main() -> None:
    common = {"META-INF/container.xml": container()}

    write_epub("minimal.epub", {
        **common,
        "OEBPS/content.opf": opf("Minimal", "", '    <itemref idref="ch1"/>\n    <itemref idref="ch2"/>'),
        "OEBPS/nav.xhtml": nav(),
        "OEBPS/toc.ncx": ncx(),
        "OEBPS/chapter1.xhtml": '''<!doctype html><html xmlns="http://www.w3.org/1999/xhtml"><head><title>One</title></head><body><h1 id="top">Chapter 1</h1><p>Hello <a href="chapter2.xhtml#start">next</a>.</p></body></html>''',
        "OEBPS/chapter2.xhtml": '''<!doctype html><html xmlns="http://www.w3.org/1999/xhtml"><head><title>Two</title></head><body><h1 id="start">Chapter 2</h1><p>Back to <a href="chapter1.xhtml#top">start</a>.</p></body></html>''',
    })

    write_epub("css-image.epub", {
        **common,
        "OEBPS/content.opf": opf("CSS Image", '    <item id="css" href="style.css" media-type="text/css"/>\n    <item id="img" href="image.png" media-type="image/png"/>', '    <itemref idref="ch1"/>\n    <itemref idref="ch2"/>'),
        "OEBPS/nav.xhtml": nav(),
        "OEBPS/toc.ncx": ncx("CSS Image"),
        "OEBPS/style.css": "body { font-family: serif; } .red { color: #900; } img { width: 1em; height: 1em; }",
        "OEBPS/image.png": PNG_1X1_RED,
        "OEBPS/chapter1.xhtml": '''<!doctype html><html xmlns="http://www.w3.org/1999/xhtml"><head><title>One</title><link rel="stylesheet" href="style.css" type="text/css"/></head><body><h1>Styled</h1><p class="red">Red text <img src="image.png" alt="dot"/></p></body></html>''',
        "OEBPS/chapter2.xhtml": '''<!doctype html><html xmlns="http://www.w3.org/1999/xhtml"><head><title>Two</title><link rel="stylesheet" href="style.css" type="text/css"/></head><body><p id="start">Second file.</p></body></html>''',
    })

    write_epub("svg.epub", {
        **common,
        "OEBPS/content.opf": opf("SVG", '    <item id="svg" href="diagram.svg" media-type="image/svg+xml"/>', '    <itemref idref="ch1"/>\n    <itemref idref="ch2"/>'),
        "OEBPS/nav.xhtml": nav(),
        "OEBPS/toc.ncx": ncx("SVG"),
        "OEBPS/diagram.svg": '''<svg xmlns="http://www.w3.org/2000/svg" width="100" height="30"><rect width="100" height="30" fill="yellow"/><text x="5" y="20">SVG</text></svg>''',
        "OEBPS/chapter1.xhtml": '''<!doctype html><html xmlns="http://www.w3.org/1999/xhtml"><head><title>SVG</title></head><body><h1>SVG</h1><img src="diagram.svg" alt="svg"/></body></html>''',
        "OEBPS/chapter2.xhtml": '''<!doctype html><html xmlns="http://www.w3.org/1999/xhtml"><head><title>End</title></head><body><p id="start">End.</p></body></html>''',
    })

    epub2_opf = '''<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="uid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="uid">urn:uuid:epub2-ncx</dc:identifier>
    <dc:title>EPUB2 NCX</dc:title>
    <dc:creator>Test Fixture</dc:creator>
    <dc:language>en</dc:language>
  </metadata>
  <manifest>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
    <item id="ch1" href="chapter1.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch2" href="chapter2.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine toc="ncx">
    <itemref idref="ch1"/>
    <itemref idref="ch2"/>
  </spine>
</package>
'''
    write_epub("epub2-ncx.epub", {
        **common,
        "OEBPS/content.opf": epub2_opf,
        "OEBPS/toc.ncx": ncx("EPUB2 NCX"),
        "OEBPS/chapter1.xhtml": '''<html xmlns="http://www.w3.org/1999/xhtml"><head><title>One</title></head><body><h1>EPUB2 Chapter</h1><p>NCX only.</p></body></html>''',
        "OEBPS/chapter2.xhtml": '''<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Two</title></head><body><p id="start">Second.</p></body></html>''',
    })

    rtl_opf = opf("RTL Arabic", "", '    <itemref idref="ch1"/>\n    <itemref idref="ch2"/>').replace('<dc:language>en</dc:language>', '<dc:language>ar</dc:language>').replace('<spine toc="ncx">', '<spine toc="ncx" page-progression-direction="rtl">')
    write_epub("rtl.epub", {
        **common,
        "OEBPS/content.opf": rtl_opf,
        "OEBPS/nav.xhtml": nav("الفهرس"),
        "OEBPS/toc.ncx": ncx("RTL Arabic"),
        "OEBPS/chapter1.xhtml": '''<!doctype html><html xmlns="http://www.w3.org/1999/xhtml" dir="rtl" lang="ar"><head><title>واحد</title></head><body><h1 id="top">الفصل الأول</h1><p>مرحبا <a href="chapter2.xhtml#start">التالي</a>.</p></body></html>''',
        "OEBPS/chapter2.xhtml": '''<!doctype html><html xmlns="http://www.w3.org/1999/xhtml" dir="rtl" lang="ar"><head><title>اثنان</title></head><body><h1 id="start">الفصل الثاني</h1><p>نهاية.</p></body></html>''',
    })


if __name__ == "__main__":
    main()

