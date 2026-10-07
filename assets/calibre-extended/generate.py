import os, random, shutil, sys, uuid, zipfile, io, datetime, sqlite3
from calibre.library import db as open_db
from calibre.ebooks.metadata.book.base import Metadata

# Usage: calibre-debug generate.py -- <output-library-dir> [<scratch-dir>]
HERE = os.path.dirname(os.path.abspath(__file__))
SAMPLE = os.path.join(HERE, '..', 'calibre-sample')
DEST = sys.argv[1]; WORK = sys.argv[2] if len(sys.argv) > 2 else DEST + '-work'
rng = random.Random(20261007)  # private: Calibre itself consumes the global generator
shutil.rmtree(DEST, ignore_errors=True); shutil.rmtree(WORK, ignore_errors=True)
shutil.copytree(SAMPLE, DEST)
os.makedirs(WORK)
api = open_db(DEST).new_api

# --- custom columns (read_status already exists in the sample) ---
legacy = open_db(DEST)
legacy.create_custom_column('topic', '主题', 'text', True)
legacy.create_custom_column('shelf', '书架', 'enumeration', False, display={'enum_values': ['收藏', '待读', '参考'], 'enum_colors': []})
legacy.create_custom_column('note', '备注', 'text', False)
legacy.create_custom_column('pages', '页数', 'int', False)
legacy.create_custom_column('formula', '计算', 'composite', False, display={'composite_template': '{title}'})
legacy.create_custom_column('retired', '待改名栏目', 'bool', False)
legacy.close()
api = open_db(DEST).new_api
def key(label): return '#' + label

def entry(name):
    return zipfile.ZipInfo(name, (2024, 1, 1, 0, 0, 0))  # fixed timestamp keeps the archive reproducible

def epub_bytes(title, n):
    b = io.BytesIO()
    with zipfile.ZipFile(b, 'w') as z:
        z.writestr(entry('mimetype'), 'application/epub+zip', zipfile.ZIP_STORED)
        z.writestr(entry('META-INF/container.xml'), '<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>')
        z.writestr(entry('content.opf'), f'<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>{title}</dc:title><dc:identifier id="id">urn:fixture:{n}</dc:identifier><dc:language>zh</dc:language></metadata><manifest><item id="c" href="c.xhtml" media-type="application/xhtml+xml"/><item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/></manifest><spine toc="ncx"><itemref idref="c"/></spine></package>')
        z.writestr(entry('toc.ncx'), f'<?xml version="1.0"?><ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><head><meta name="dtb:uid" content="urn:fixture:{n}"/></head><docTitle><text>{title}</text></docTitle><navMap><navPoint id="n" playOrder="1"><navLabel><text>1</text></navLabel><content src="c.xhtml"/></navPoint></navMap></ncx>')
        z.writestr(entry('c.xhtml'), f'<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>{title}</title></head><body><h1>{title}</h1><p>测试书籍 {n}</p></body></html>')
    return b.getvalue()

def pdf_bytes(n):
    text = f'Fixture book {n}'
    objs = ['<< /Type /Catalog /Pages 2 0 R >>', '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
        '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 200] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>',
        None, '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>']
    stream = f'BT /F1 18 Tf 20 100 Td ({text}) Tj ET'
    objs[3] = f'<< /Length {len(stream)} >>\nstream\n{stream}\nendstream'
    out = b'%PDF-1.4\n'; offs = []
    for i, o in enumerate(objs, 1):
        offs.append(len(out)); out += f'{i} 0 obj\n{o}\nendobj\n'.encode()
    x = len(out); out += f'xref\n0 {len(objs)+1}\n0000000000 65535 f \n'.encode()
    for o in offs: out += f'{o:010d} 00000 n \n'.encode()
    out += f'trailer\n<< /Size {len(objs)+1} /Root 1 0 R >>\nstartxref\n{x}\n%%EOF\n'.encode()
    return out

def cover_bytes(n):
    from PIL import Image, ImageDraw
    img = Image.new('RGB', (300, 450), (40 + n * 7 % 180, 60 + n * 13 % 160, 80 + n * 29 % 140))
    ImageDraw.Draw(img).text((100, 200), f'No. {n}', fill=(255, 255, 255))
    b = io.BytesIO(); img.save(b, 'JPEG'); return b.getvalue()

zh_title = ['春', '夏', '秋', '冬', '山', '海', '城', '河', '星', '雨']
zh_noun = ['日记', '纪事', '传', '集', '往事', '笔记', '小史', '漫游']
en_title = ['The Quiet', 'A Brief', 'Notes on', 'Tales of', 'Return to', 'Letters from']
en_noun = ['Harbor', 'Garden', 'Machine', 'Orchard', 'Archive', 'Mountain']
authors_pool = ['王小明', '李华', '张三', 'Alice Walker', 'Bob Stone', '陈 静', 'Carol King', '赵六']
tags_pool = ['小说', '历史', '科幻', '散文', '诗歌', '技术', '传记', 'travel', 'cooking', '哲学', '经典', '儿童']
series_pool = ['星河三部曲', 'Harbor Chronicles', '山海志', '小城故事']
N = 283
summary = {}
for n in range(1, N + 1):
    if n % 41 == 0:
        title = '同名之书'  # same title, different books
    elif n % 2:
        title = rng.choice(zh_title) + rng.choice(zh_noun) + (f'（卷{n % 7 + 1}）' if n % 5 == 0 else '')
    else:
        title = f'{rng.choice(en_title)} {rng.choice(en_noun)}' + (f' {n}' if n % 3 else '')
    mi = Metadata(title, [rng.choice(authors_pool)] if n % 17 else ['Unknown'])
    mi.authors = [rng.choice(authors_pool)] if n % 17 else ['Unknown']
    mi.tags = [] if n % 7 == 0 else rng.sample(tags_pool, rng.choice([1, 1, 2, 3]))
    if n % 9 in (1, 2) and n < 200:
        mi.series = series_pool[n % 4]; mi.series_index = float(n // 9 + 1) if n % 9 == 1 else float(n // 9 + 1) + 0.5
    if n % 3: mi.rating = rng.choice([2, 4, 6, 8, 10])
    mi.comments = f'<p>第{n}本测试书的<b>简介</b>，包含 &amp; 符号。</p><script>alert(1)</script>' if n % 4 else ''
    mi.timestamp = datetime.datetime(2024, 10, 7, tzinfo=datetime.timezone.utc) + datetime.timedelta(hours=n * 53 + (n % 5))
    mi.pubdate = mi.timestamp
    bid = api.create_book_entry(mi)
    api.set_field('uuid', {bid: str(uuid.uuid5(uuid.NAMESPACE_URL, f'calibre-cloud-fixture/{n}'))})
    fm_choice = n % 10
    fmts = {0: ['PDF'], 1: ['EPUB', 'PDF'], 2: ['EPUB', 'PDF'], 3: ['EPUB', 'MOBI']}.get(fm_choice, ['EPUB'])
    if n % 19 == 0: fmts = []
    for f in fmts:
        data = epub_bytes(title, n) if f == 'EPUB' else pdf_bytes(n) if f == 'PDF' else b'MOBI-FIXTURE-' + str(n).encode() * 50
        api.add_format(bid, f, io.BytesIO(data), replace=True, run_hooks=False)
    if n % 10 not in (4,):  # ~90% cover
        api.set_cover({bid: cover_bytes(n)})
    # custom columns
    if n % 3 == 0: api.set_field(key('read_status'), {bid: True})
    elif n % 3 == 1: api.set_field(key('read_status'), {bid: False})
    if n % 5 != 0: api.set_field(key('topic'), {bid: rng.sample(['工作', '休闲', 'research', '家庭', '旅行'], rng.choice([1, 2, 3]))})
    if n % 4 == 0: api.set_field(key('shelf'), {bid: rng.choice(['收藏', '待读', '参考'])})
    if n % 6 == 0: api.set_field(key('note'), {bid: rng.choice(['借阅', '纸质版已有', '重读'])})
    if n % 8 == 0: api.set_field(key('retired'), {bid: n % 16 == 0})
api.close()

# --- states Calibre's API cannot produce; applied to the generated copy and recorded in the README ---
db = sqlite3.connect(os.path.join(DEST, 'metadata.db'))
db.execute("delete from books_authors_link where book in (select id from books where id>5 and id%23=0)")
# Calibre initialises every book of a bool column to "no"; restore the empty (never set) state.
db.execute("delete from custom_column_1 where book > 5 and (book - 5) % 3 = 2")
db.commit(); db.close()
shutil.rmtree(WORK, ignore_errors=True)
print('ok')
