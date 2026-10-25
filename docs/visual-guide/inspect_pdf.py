#!/usr/bin/env python3
"""Render every page and check PDF link/text geometry. Requires PyMuPDF + Pillow.
Usage: python3 docs/visual-guide/inspect_pdf.py [preview-directory]
This checks document production, not product safety or runtime performance.
"""
from pathlib import Path
import json
import sys
import pymupdf
from PIL import Image, ImageDraw

here = Path(__file__).resolve().parent
out = Path(sys.argv[1]) if len(sys.argv) > 1 else Path('/tmp/determine-pdf-preview')
out.mkdir(parents=True, exist_ok=True)
doc = pymupdf.open(here / 'Determine-Simulation-and-Glasses.pdf')
report = {'pages': len(doc), 'outlineEntries': len(doc.get_toc()), 'issues': [], 'pageDetails': []}
thumbs = []
for i, page in enumerate(doc):
    pix = page.get_pixmap(matrix=pymupdf.Matrix(1.6, 1.6), alpha=False)
    pix.save(out / f'page-{i+1:02}.png')
    for word in page.get_text('words'):
        r = pymupdf.Rect(word[:4])
        if r.x0 < 0 or r.y0 < 0 or r.x1 > page.rect.width+.2 or r.y1 > page.rect.height+.2:
            report['issues'].append({'page':i+1,'kind':'text_outside_page','word':word[4],'bbox':list(r)})
    for link in page.get_links():
        if link['kind'] == pymupdf.LINK_GOTO and not (0 <= link['page'] < len(doc)):
            report['issues'].append({'page':i+1,'kind':'invalid_internal_link','link':link})
        if link['kind'] == pymupdf.LINK_URI and not link['uri'].startswith('https://'):
            report['issues'].append({'page':i+1,'kind':'unexpected_external_uri','link':link})
    report['pageDetails'].append({'page':i+1,'words':len(page.get_text('words')),'links':len(page.get_links())})
    im = Image.open(out / f'page-{i+1:02}.png').convert('RGB')
    im.thumbnail((408, 528))
    thumb = Image.new('RGB',(432,564),'#e3e6e7')
    thumb.paste(im,((432-im.width)//2,10))
    ImageDraw.Draw(thumb).text((14,542),f'PAGE {i+1:02}',fill='#152a38')
    thumbs.append(thumb)
for start in range(0,len(thumbs),4):
    batch=thumbs[start:start+4]
    sheet=Image.new('RGB',(864,1128),'#e3e6e7')
    for k,im in enumerate(batch): sheet.paste(im,((k%2)*432,(k//2)*564))
    sheet.save(out / f'sheet-{start//4+1}.png')
(out/'inspection.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2))
if report['issues']:
    raise SystemExit(1)
