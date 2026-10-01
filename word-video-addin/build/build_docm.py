"""Builds WordVideo-Install.docm: a macro-enabled Word document that carries
WordVideo.dotm inside it (Base64) and installs it with one button click.

Open the document, enable macros, and click "התקן את התוסף" on the ribbon.

Usage: python build_docm.py [output.docm]
"""
import base64
import io
import os
import sys
import zipfile

import build_dotm as d

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, '..', 'src')


PAYLOAD_NS = 'urn:wordvideo:payload'
PAYLOAD_GUID = '{C8A7F0E2-9B3D-4A61-8E2F-1D4B6A9C3E57}'


def installer_source() -> str:
    with open(os.path.join(SRC, 'modInstaller.bas'), encoding='utf-8') as f:
        return f.read()


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

CONTENT_TYPES = '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Default Extension="bin" ContentType="application/vnd.ms-office.vbaProject"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.ms-word.document.macroEnabled.main+xml"/>
<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
<Override PartName="/customXml/itemProps1.xml" ContentType="application/vnd.openxmlformats-officedocument.customXmlProperties+xml"/>
</Types>'''

ROOT_RELS = '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
<Relationship Id="rId3" Type="http://schemas.microsoft.com/office/2006/relationships/ui/extensibility" Target="customUI/customUI.xml"/>
</Relationships>'''

DOCUMENT_RELS = '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.microsoft.com/office/2006/relationships/vbaProject" Target="vbaProject.bin"/>
<Relationship Id="rId10" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/customXml" Target="../customXml/item1.xml"/>
</Relationships>'''

ITEM_PROPS = '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<ds:datastoreItem ds:itemID="%s" xmlns:ds="http://schemas.openxmlformats.org/officeDocument/2006/customXml">
<ds:schemaRefs><ds:schemaRef ds:uri="%s"/></ds:schemaRefs></ds:datastoreItem>''' % (PAYLOAD_GUID, PAYLOAD_NS)

ITEM_RELS = '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/customXmlProps" Target="itemProps1.xml"/>
</Relationships>'''


def payload_item(dotm_bytes: bytes) -> bytes:
    b64 = base64.b64encode(dotm_bytes).decode('ascii')
    xml = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
           '<payload xmlns="%s">%s</payload>' % (PAYLOAD_NS, b64))
    return xml.encode('utf-8')

CORE = '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/">
<dc:title>התקנת נגן וידאו ל-Word</dc:title></cp:coreProperties>'''


def para(text, bold=False, size=24, color=None, spacing=200):
    runpr = '<w:rPr>'
    if bold:
        runpr += '<w:b/>'
    runpr += '<w:sz w:val="%d"/>' % size
    if color:
        runpr += '<w:color w:val="%s"/>' % color
    runpr += '<w:rtl/></w:rPr>'
    return ('<w:p><w:pPr><w:bidi/><w:spacing w:after="%d"/>'
            '<w:rPr><w:rtl/></w:rPr></w:pPr>'
            '<w:r>%s<w:t xml:space="preserve">%s</w:t></w:r></w:p>' % (spacing, runpr, text))


def document_xml():
    body = (
        para('נגן וידאו ל-Word', bold=True, size=40, color='2563EB') +
        para('כדי להתקין: למעלה, בלשונית &#8207;&#34;התקנת וידאו&#34;&#8207;, לחץ על הכפתור &#8207;&#34;התקן את התוסף&#34;&#8207;.', bold=True) +
        para('אם מופיע פס אזהרה צהוב &#8207;(&#34;הפוך תוכן לזמין&#34; או &#34;אפשר עריכה&#34;)&#8207; &#8211; לחץ עליו קודם, כדי לאפשר את ההתקנה.') +
        para('אחרי ההתקנה תופיע ב-Word לשונית חדשה בשם &#8207;&#34;וידאו&#34;&#8207;, ליד הלשונית &#8207;&#34;הוספה&#34;&#8207;. משם מוסיפים סרטון מהמחשב לתוך המסמך. אפשר לסגור את המסמך הזה.', spacing=400) +
        para('מה התוסף עושה: מוסיף נגן וידאו בתוך המסמך. הסרטון מועתק לתיקייה ליד המסמך, ומתנגן בלחיצה על כפתור ההפעלה.', size=20, color='555555')
    )
    return ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">'
            '<w:body>%s'
            '<w:sectPr><w:pgSz w:w="11906" w:h="16838"/>'
            '<w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" '
            'w:header="708" w:footer="708" w:gutter="0"/>'
            '<w:bidi/></w:sectPr></w:body></w:document>' % body)


def build(path: str) -> None:
    # 1. Build the add-in template that will be embedded.
    dotm = io.BytesIO()
    import tempfile
    tmp = os.path.join(tempfile.gettempdir(), 'WordVideo.dotm')
    d.build(tmp)
    with open(tmp, 'rb') as f:
        dotm_bytes = f.read()

    # 2. Point build_dotm's globals at the installer document's modules.
    d.PROJECT_NAME = 'WordVideoInstall'
    d.MODULES = [
        ('ThisDocument', 'doc', THIS_DOCUMENT),
        ('modInstaller', 'std', installer_source()),
    ]

    with open(os.path.join(SRC, 'customUI_install.xml'), 'rb') as f:
        custom_ui = f.read()

    buf = io.BytesIO()
    with zipfile.ZipFile(buf, 'w', zipfile.ZIP_DEFLATED) as z:
        z.writestr('[Content_Types].xml', CONTENT_TYPES)
        z.writestr('_rels/.rels', ROOT_RELS)
        z.writestr('docProps/core.xml', CORE)
        z.writestr('word/document.xml', document_xml())
        z.writestr('word/_rels/document.xml.rels', DOCUMENT_RELS)
        z.writestr('word/vbaProject.bin', d.vba_project_bin())
        z.writestr('customUI/customUI.xml', custom_ui)
        z.writestr('customXml/item1.xml', payload_item(dotm_bytes))
        z.writestr('customXml/itemProps1.xml', ITEM_PROPS)
        z.writestr('customXml/_rels/item1.xml.rels', ITEM_RELS)
    with open(path, 'wb') as f:
        f.write(buf.getvalue())


if __name__ == '__main__':
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, '..', 'WordVideo-Install.docm')
    build(out)
    print('wrote', out, os.path.getsize(out), 'bytes')
