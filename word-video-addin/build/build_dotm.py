"""Builds WordVideo.dotm (a Word global template with the add-in) from ../src.

The VBA project is written source-only: _VBA_PROJECT has version 0xFFFF, so
Word ignores the (absent) compiled cache and compiles the modules from source
the first time it loads the template.

Usage:    python build_dotm.py [output.dotm]
"""
import io
import os
import random
import struct
import sys
import uuid
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, '..', 'src')
CODEPAGE = 1252
PROJECT_NAME = 'WordVideo'


# ---------- VBA source ----------

def read_source(name: str) -> str:
    with open(os.path.join(SRC, name), encoding='utf-8') as f:
        return f.read()


def to_vba_ascii(text: str) -> bytes:
    """Non-ASCII characters become \\uXXXX (decoded by U() in modVideo)."""
    text = ''.join(c if ord(c) < 128 else '\\u%04X' % ord(c) for c in text)
    text = text.replace('\r\n', '\n').replace('\n', '\r\n')
    return text.encode('ascii')


def class_source(text: str) -> str:
    """Turns an exported .cls file into the source stored in the project."""
    lines = text.replace('\r\n', '\n').split('\n')
    start = next(i for i, l in enumerate(lines) if l.startswith('Attribute VB_Name'))
    body = [l for l in lines[start + 1:] if not l.startswith('Attribute VB_')]
    head = [
        lines[start],
        'Attribute VB_Base = "0{FCFB3D2A-A0FA-1068-A738-08002B3371B5}"',
        'Attribute VB_GlobalNameSpace = False',
        'Attribute VB_Creatable = False',
        'Attribute VB_PredeclaredId = False',
        'Attribute VB_Exposed = False',
        'Attribute VB_TemplateDerived = False',
        'Attribute VB_Customizable = False',
    ]
    return '\n'.join(head + body)


THIS_DOCUMENT = '\n'.join([
    'Attribute VB_Name = "ThisDocument"',
    'Attribute VB_Base = "1Normal.ThisDocument"',
    'Attribute VB_GlobalNameSpace = False',
    'Attribute VB_Creatable = False',
    'Attribute VB_PredeclaredId = True',
    'Attribute VB_Exposed = True',
    'Attribute VB_TemplateDerived = True',
    'Attribute VB_Customizable = True',
    '',
])

# (name, kind, source) - kind: 'doc', 'std' or 'class'
MODULES = [
    ('ThisDocument', 'doc', THIS_DOCUMENT),
    ('modVideo', 'std', read_source('modVideo.bas')),
    ('clsAppEvents', 'class', class_source(read_source('clsAppEvents.cls'))),
]

REFERENCES = [
    ('stdole', '*\\G{00020430-0000-0000-C000-000000000046}#2.0#0#'
               'C:\\Windows\\System32\\stdole2.tlb#OLE Automation'),
    ('Office', '*\\G{2DF8D04C-5BFA-101B-BDE5-00AA0044DE52}#2.0#0#'
               'C:\\Program Files\\Common Files\\Microsoft Shared\\OFFICE16\\MSO.DLL#'
               'Microsoft Office 16.0 Object Library'),
]


# ---------- dir stream (MS-OVBA 2.3.4.2) ----------

def rec(rid: int, data: bytes) -> bytes:
    return struct.pack('<HI', rid, len(data)) + data


def mbcs(s: str) -> bytes:
    return s.encode('cp%d' % CODEPAGE)


def utf16(s: str) -> bytes:
    return s.encode('utf-16-le')


def dir_stream() -> bytes:
    out = b''
    out += rec(0x0001, struct.pack('<I', 1))            # SYSKIND win32
    out += rec(0x0002, struct.pack('<I', 0x409))        # LCID
    out += rec(0x0014, struct.pack('<I', 0x409))        # LCIDINVOKE
    out += rec(0x0003, struct.pack('<H', CODEPAGE))     # CODEPAGE
    out += rec(0x0004, mbcs(PROJECT_NAME))              # NAME
    out += rec(0x0005, b'') + rec(0x0040, b'')          # DOCSTRING
    out += rec(0x0006, b'') + rec(0x003D, b'')          # HELPFILEPATH
    out += rec(0x0007, struct.pack('<I', 0))            # HELPCONTEXT
    out += rec(0x0008, struct.pack('<I', 0))            # LIBFLAGS
    out += struct.pack('<HIIH', 0x0009, 4, 1, 0)        # VERSION
    out += rec(0x000C, b'') + rec(0x003C, b'')          # CONSTANTS

    for name, libid in REFERENCES:
        out += rec(0x0016, mbcs(name)) + rec(0x003E, utf16(name))
        lib = mbcs(libid)
        out += rec(0x000D, struct.pack('<I', len(lib)) + lib + struct.pack('<IH', 0, 0))

    out += rec(0x000F, struct.pack('<H', len(MODULES)))
    out += rec(0x0013, struct.pack('<H', 0xFFFF))       # PROJECTCOOKIE
    for name, kind, _ in MODULES:
        out += rec(0x0019, mbcs(name))
        out += rec(0x0047, utf16(name))
        out += rec(0x001A, mbcs(name)) + rec(0x0032, utf16(name))
        out += rec(0x001C, b'') + rec(0x0048, b'')
        out += rec(0x0031, struct.pack('<I', 0))        # source starts at offset 0
        out += rec(0x001E, struct.pack('<I', 0))
        out += rec(0x002C, struct.pack('<H', 0xFFFF))
        out += struct.pack('<HI', 0x0021 if kind == 'std' else 0x0022, 0)
        out += struct.pack('<HI', 0x002B, 0)
    out += struct.pack('<HI', 0x0010, 0)
    return out


# ---------- Compression (MS-OVBA 2.4.1) ----------

def compress(data: bytes) -> bytes:
    out = bytearray(b'\x01')
    for start in range(0, len(data), 4096):
        chunk = data[start:start + 4096]
        body = _compress_chunk(chunk)
        if len(body) <= 4096:
            out += struct.pack('<H', 0xB000 | (len(body) + 2 - 3)) + body
        else:
            out += struct.pack('<H', 0x3000 | 0xFFF) + chunk.ljust(4096, b'\x00')
    return bytes(out)


def _compress_chunk(chunk: bytes) -> bytes:
    out = bytearray()
    pos = 0
    while pos < len(chunk):
        flag_index = len(out)
        out.append(0)
        flags = 0
        for bit in range(8):
            if pos >= len(chunk):
                break
            bit_count = max((pos - 1).bit_length(), 4) if pos > 0 else 4
            max_len = (0xFFFF >> bit_count) + 3
            best_len, best_off = 0, 0
            for cand in range(pos - 1, -1, -1):
                n = 0
                while (n < max_len and pos + n < len(chunk)
                       and chunk[cand + n] == chunk[pos + n]):
                    n += 1
                if n > best_len:
                    best_len, best_off = n, pos - cand
                    if n == max_len:
                        break
            if best_len >= 3:
                token = ((best_off - 1) << (16 - bit_count)) | (best_len - 3)
                out += struct.pack('<H', token)
                flags |= 1 << bit
                pos += best_len
            else:
                out.append(chunk[pos])
                pos += 1
        out[flag_index] = flags
    return bytes(out)


# ---------- PROJECT stream (MS-OVBA 2.3.1) ----------

def encrypt(project_id: str, data: bytes) -> str:
    """MS-OVBA 2.4.3.2 data encryption, returned as a hex string."""
    seed = random.randint(0, 255)
    version = 2
    key = sum(ord(c) for c in project_id) & 0xFF
    out = [seed, version ^ seed, key ^ seed]
    unenc1, enc1, enc2 = key, key ^ seed, version ^ seed
    payload = [random.randint(0, 255) for _ in range((seed & 6) // 2)]
    payload += list(struct.pack('<I', len(data))) + list(data)
    for b in payload:
        e = b ^ ((enc2 + unenc1) & 0xFF)
        out.append(e)
        enc2, enc1, unenc1 = enc1, e, b
    return ''.join('%02X' % b for b in out)


def project_stream(project_id: str) -> bytes:
    lines = ['ID="%s"' % project_id]
    for name, kind, _ in MODULES:
        if kind == 'doc':
            lines.append('Document=%s/&H00000000' % name)
        elif kind == 'std':
            lines.append('Module=%s' % name)
        else:
            lines.append('Class=%s' % name)
    lines += [
        'Name="%s"' % PROJECT_NAME,
        'HelpContextID="0"',
        'VersionCompatible32="393222000"',
        'CMG="%s"' % encrypt(project_id, b'\x00\x00\x00\x00'),
        'DPB="%s"' % encrypt(project_id, b'\x00'),
        'GC="%s"' % encrypt(project_id, b'\xFF'),
        '',
        '[Host Extender Info]',
        '&H00000001={3832D640-CF90-11CF-8E43-00A0C911005A};VBE;&H00000000',
        '',
        '[Workspace]',
    ]
    lines += ['%s=0, 0, 0, 0, C' % name for name, _, _ in MODULES]
    return mbcs('\r\n'.join(lines) + '\r\n')


def projectwm_stream() -> bytes:
    out = b''
    for name, _, _ in MODULES:
        out += mbcs(name) + b'\x00' + utf16(name) + b'\x00\x00'
    return out + b'\x00\x00'


# ---------- Compound File Binary writer (MS-CFB, version 3) ----------

SECTOR = 512
MINI = 64
CUTOFF = 4096
FREE, ENDOFCHAIN, FATSECT = 0xFFFFFFFF, 0xFFFFFFFE, 0xFFFFFFFD
NOSTREAM = 0xFFFFFFFF


class Node:
    def __init__(self, name, data=None, children=None):
        self.name = name
        self.data = data
        self.children = children
        self.sid = 0
        self.start = ENDOFCHAIN
        self.left = self.right = self.child = NOSTREAM


def cfb_key(node):
    return (len(node.name), node.name.upper())


def build_tree(nodes):
    """Balanced binary search tree; returns the root SID."""
    if not nodes:
        return NOSTREAM
    nodes = sorted(nodes, key=cfb_key)
    mid = len(nodes) // 2
    nodes[mid].left = build_tree(nodes[:mid])
    nodes[mid].right = build_tree(nodes[mid + 1:])
    return nodes[mid].sid


def write_cfb(root_children) -> bytes:
    root = Node('Root Entry', children=root_children)
    entries = []

    def number(node):
        node.sid = len(entries)
        entries.append(node)
        for c in node.children or []:
            number(c)
    number(root)

    def link(node):
        if node.children is not None:
            node.child = build_tree(node.children)
            for c in node.children:
                link(c)
    link(root)

    streams = [e for e in entries if e.data is not None]
    small = [s for s in streams if len(s.data) < CUTOFF]
    big = [s for s in streams if len(s.data) >= CUTOFF]

    # Mini stream and mini FAT.
    ministream = b''
    minifat = []
    for s in small:
        n = max(1, -(-len(s.data) // MINI)) if s.data else 0
        if n == 0:
            s.start = ENDOFCHAIN
            continue
        s.start = len(minifat)
        for i in range(n):
            minifat.append(s.start + i + 1 if i < n - 1 else ENDOFCHAIN)
        ministream += s.data.ljust(n * MINI, b'\x00')

    def nsect(size):
        return -(-size // SECTOR)

    dir_sectors = nsect(len(entries) * 128)
    minifat_sectors = nsect(len(minifat) * 4)
    ministream_sectors = nsect(len(ministream))
    big_sectors = sum(nsect(len(s.data)) for s in big)
    data_sectors = dir_sectors + minifat_sectors + ministream_sectors + big_sectors
    fat_sectors = 1
    while fat_sectors * (SECTOR // 4) < fat_sectors + data_sectors:
        fat_sectors += 1
    assert fat_sectors <= 109

    fat = [FATSECT] * fat_sectors
    blobs = []

    def alloc(data):
        if not data:
            return ENDOFCHAIN
        n = nsect(len(data))
        start = len(fat)
        for i in range(n):
            fat.append(start + i + 1 if i < n - 1 else ENDOFCHAIN)
        blobs.append(data.ljust(n * SECTOR, b'\x00'))
        return start

    # Directory is written after the sector numbers are known, so reserve it.
    dir_start = len(fat)
    for i in range(dir_sectors):
        fat.append(dir_start + i + 1 if i < dir_sectors - 1 else ENDOFCHAIN)
    blobs.append(None)
    minifat_start = alloc(b''.join(struct.pack('<I', x) for x in minifat))
    root.start = alloc(ministream)
    for s in big:
        s.start = alloc(s.data)
    fat += [FREE] * (fat_sectors * (SECTOR // 4) - len(fat))

    def entry(e):
        name = utf16(e.name) + b'\x00\x00'
        etype = 5 if e is root else (1 if e.children is not None else 2)
        size = len(ministream) if e is root else (len(e.data) if e.data is not None else 0)
        start = e.start if (e is root or e.data is not None) else 0
        if e.data is not None and size == 0:
            start = ENDOFCHAIN
        return (name.ljust(64, b'\x00') +
                struct.pack('<HBB', len(name), etype, 1) +     # 1 = black
                struct.pack('<III', e.left, e.right, e.child) +
                b'\x00' * 16 + struct.pack('<I', 0) + b'\x00' * 16 +
                struct.pack('<IQ', start, size))

    directory = b''.join(entry(e) for e in entries)
    directory = directory.ljust(dir_sectors * SECTOR, b'\x00')
    for i in range(0, len(directory), 128):  # unused entries
        if i // 128 >= len(entries):
            directory = (directory[:i] + b'\x00' * 64 + struct.pack('<HBB', 0, 0, 0) +
                         struct.pack('<III', NOSTREAM, NOSTREAM, NOSTREAM) +
                         b'\x00' * 48 + directory[i + 128:])
    blobs[blobs.index(None)] = directory

    difat = list(range(fat_sectors)) + [FREE] * (109 - fat_sectors)
    header = (b'\xD0\xCF\x11\xE0\xA1\xB1\x1A\xE1' + b'\x00' * 16 +
              struct.pack('<HHHHH', 0x003E, 3, 0xFFFE, 9, 6) + b'\x00' * 6 +
              struct.pack('<IIIIIIIII', 0, fat_sectors, dir_start, 0, CUTOFF,
                          minifat_start if minifat else ENDOFCHAIN,
                          minifat_sectors, ENDOFCHAIN, 0) +
              b''.join(struct.pack('<I', x) for x in difat))
    assert len(header) == 512
    fat_bytes = b''.join(struct.pack('<I', x) for x in fat)
    return header + fat_bytes + b''.join(blobs)


def vba_project_bin() -> bytes:
    project_id = '{%s}' % str(uuid.uuid4()).upper()
    vba = [Node('_VBA_PROJECT', data=struct.pack('<HHBH', 0x61CC, 0xFFFF, 0, 0)),
           Node('dir', data=compress(dir_stream()))]
    for name, _, source in MODULES:
        vba.append(Node(name, data=compress(to_vba_ascii(source))))
    return write_cfb([
        Node('VBA', children=vba),
        Node('PROJECT', data=project_stream(project_id)),
        Node('PROJECTwm', data=projectwm_stream()),
    ])


# ---------- OOXML package ----------

CONTENT_TYPES = '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Default Extension="bin" ContentType="application/vnd.ms-office.vbaProject"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.ms-word.template.macroEnabledTemplate.main+xml"/>
<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
</Types>'''

ROOT_RELS = '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
<Relationship Id="rId3" Type="http://schemas.microsoft.com/office/2006/relationships/ui/extensibility" Target="customUI/customUI.xml"/>
</Relationships>'''

DOCUMENT = '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:body><w:p/><w:sectPr><w:pgSz w:w="11906" w:h="16838"/>
<w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="708" w:footer="708" w:gutter="0"/>
</w:sectPr></w:body></w:document>'''

DOCUMENT_RELS = '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.microsoft.com/office/2006/relationships/vbaProject" Target="vbaProject.bin"/>
</Relationships>'''

CORE = '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/">
<dc:title>WordVideo</dc:title></cp:coreProperties>'''


def build(path: str) -> None:
    with open(os.path.join(SRC, 'customUI.xml'), 'rb') as f:
        custom_ui = f.read()
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, 'w', zipfile.ZIP_DEFLATED) as z:
        z.writestr('[Content_Types].xml', CONTENT_TYPES)
        z.writestr('_rels/.rels', ROOT_RELS)
        z.writestr('docProps/core.xml', CORE)
        z.writestr('word/document.xml', DOCUMENT)
        z.writestr('word/_rels/document.xml.rels', DOCUMENT_RELS)
        z.writestr('word/vbaProject.bin', vba_project_bin())
        z.writestr('customUI/customUI.xml', custom_ui)
    with open(path, 'wb') as f:
        f.write(buf.getvalue())


if __name__ == '__main__':
    build(sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, '..', 'WordVideo.dotm'))
