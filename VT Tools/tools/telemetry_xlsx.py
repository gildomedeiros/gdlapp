"""Small, dependency-free XLSX writer/reader for the telemetry report.

Uses standard Office Open XML, including shared strings when reading files
saved by LibreOffice. Grouped readings and timestamps are stored as text.
"""
import math
import posixpath
import unicodedata
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path

NS = 'http://schemas.openxmlformats.org/spreadsheetml/2006/main'
REL = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships'
PKG = 'http://schemas.openxmlformats.org/package/2006/relationships'


def col_name(index):
    text = ''
    while index:
        index, remainder = divmod(index - 1, 26)
        text = chr(65 + remainder) + text
    return text


def elem(parent, tag, text=None, **attributes):
    child = ET.SubElement(parent, '{' + NS + '}' + tag, attributes)
    if text is not None:
        child.text = str(text)
    return child


def xml(root):
    return ET.tostring(root, encoding='utf-8', xml_declaration=True)


def write_xlsx(path, headers, rows):
    rows = list(rows)
    matrix = [headers] + [[r.get(k) for k in headers] for r in rows]
    end = f'{col_name(len(headers))}{len(matrix)}'
    sheet = ET.Element('{' + NS + '}worksheet')
    elem(sheet, 'dimension', ref=f'A1:{end}')
    view = elem(elem(sheet, 'sheetViews'), 'sheetView', workbookViewId='0', showGridLines='0')
    elem(view, 'pane', ySplit='1', topLeftCell='A2', activePane='bottomLeft', state='frozen')
    elem(sheet, 'sheetFormatPr', defaultRowHeight='20')
    columns = elem(sheet, 'cols')
    for i, key in enumerate(headers, 1):
        # Fit data only; long headers wrap without widening the columns.
        def width(value):
            return max((sum(2 if unicodedata.east_asian_width(c) in ('W', 'F') else 1 for c in line)
                        for line in str(value if value is not None else '').splitlines()), default=0)
        longest = max((width(row[i - 1]) for row in matrix[1:]), default=0)
        elem(columns, 'col', min=str(i), max=str(i), width=str(min(255, max(8, longest * 1.15 + 3))), customWidth='1')
    data = elem(sheet, 'sheetData')
    for number, values in enumerate(matrix, 1):
        row = elem(data, 'row', r=str(number), ht='60' if number == 1 else '20', customHeight='1')
        for i, value in enumerate(values, 1):
            style = '1' if number == 1 else ('2' if headers[i - 1] == 'KML' else '0')
            cell = elem(row, 'c', r=f'{col_name(i)}{number}', s=style)
            if value is None or value == '':
                continue
            if isinstance(value, bool):
                cell.set('t', 'b'); elem(cell, 'v', '1' if value else '0')
            elif isinstance(value, (int, float)) and math.isfinite(value):
                elem(cell, 'v', value)
            else:
                cell.set('t', 'inlineStr')
                text = elem(elem(cell, 'is'), 't', str(value))
                text.set('{http://www.w3.org/XML/1998/namespace}space', 'preserve')
    elem(sheet, 'autoFilter', ref=f'A1:{end}')
    workbook = ET.Element('{' + NS + '}workbook')
    item = elem(elem(workbook, 'sheets'), 'sheet', name='Telemetry', sheetId='1')
    item.set('{' + REL + '}id', 'rId1')
    styles = ET.Element('{' + NS + '}styleSheet')
    fonts = elem(styles, 'fonts', count='2')
    for bold in (False, True):
        font = elem(fonts, 'font'); elem(font, 'sz', val='11'); elem(font, 'name', val='Arial')
        if bold:
            elem(font, 'b'); elem(font, 'color', rgb='FFFFFFFF')
    fills = elem(styles, 'fills', count='4')
    for pattern, color in [('none', None), ('gray125', None), ('solid', 'FF24476A'), ('solid', 'FFFFF2CC')]:
        fill = elem(elem(fills, 'fill'), 'patternFill', patternType=pattern)
        if color:
            elem(fill, 'fgColor', rgb=color); elem(fill, 'bgColor', indexed='64')
    border = elem(elem(styles, 'borders', count='1'), 'border')
    for side in ('left', 'right', 'top', 'bottom', 'diagonal'):
        elem(border, side)
    elem(elem(styles, 'cellStyleXfs', count='1'), 'xf', numFmtId='0', fontId='0', fillId='0', borderId='0')
    formats = elem(styles, 'cellXfs', count='3')
    for font, fill in [('0', '0'), ('1', '2'), ('0', '3')]:
        xf = elem(formats, 'xf', numFmtId='0', fontId=font, fillId=fill, borderId='0', xfId='0', applyFill='1', applyFont='1', applyAlignment='1')
        elem(xf, 'alignment', vertical='center', **({'wrapText': '1'} if font == '1' else {}))
    elem(elem(styles, 'cellStyles', count='1'), 'cellStyle', name='Normal', xfId='0', builtinId='0')
    content = ET.Element('{http://schemas.openxmlformats.org/package/2006/content-types}Types')
    for ext, kind in [('rels', 'application/vnd.openxmlformats-package.relationships+xml'), ('xml', 'application/xml')]:
        ET.SubElement(content, '{http://schemas.openxmlformats.org/package/2006/content-types}Default', Extension=ext, ContentType=kind)
    for part, kind in [('workbook.xml', 'sheet.main'), ('styles.xml', 'styles'), ('worksheets/sheet1.xml', 'worksheet')]:
        ET.SubElement(content, '{http://schemas.openxmlformats.org/package/2006/content-types}Override', PartName='/xl/' + part,
                      ContentType='application/vnd.openxmlformats-officedocument.spreadsheetml.' + kind + '+xml')
    def relationships(items):
        root = ET.Element('{' + PKG + '}Relationships')
        for ident, target, kind in items:
            ET.SubElement(root, '{' + PKG + '}Relationship', Id=ident, Target=target, Type=REL + '/' + kind)
        return root
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, 'w', zipfile.ZIP_DEFLATED) as z:
        for name, root in [('[Content_Types].xml', content), ('_rels/.rels', relationships([('rId1', 'xl/workbook.xml', 'officeDocument')])),
                           ('xl/workbook.xml', workbook), ('xl/_rels/workbook.xml.rels', relationships([('rId1', 'worksheets/sheet1.xml', 'worksheet'), ('rId2', 'styles.xml', 'styles')])),
                           ('xl/styles.xml', styles), ('xl/worksheets/sheet1.xml', sheet)]:
            z.writestr(name, xml(root))


def read_xlsx(path):
    """Read the Telemetry sheet, including LibreOffice shared/inline strings."""
    ns = {'s': NS}
    with zipfile.ZipFile(path) as z:
        workbook = ET.fromstring(z.read('xl/workbook.xml'))
        sheets = workbook.findall('s:sheets/s:sheet', ns)
        sheet = next((s for s in sheets if s.get('name') == 'Telemetry'), sheets[0] if sheets else None)
        if sheet is None:
            raise ValueError('Workbook has no worksheets')
        rid = sheet.get('{' + REL + '}id')
        links = ET.fromstring(z.read('xl/_rels/workbook.xml.rels'))
        target = next((r.get('Target') for r in links if r.get('Id') == rid and r.get('TargetMode') != 'External'), None)
        if not target:
            raise ValueError('Worksheet relationship missing')
        part = target.lstrip('/') if target.startswith('/') else posixpath.normpath('xl/' + target)
        shared = []
        if 'xl/sharedStrings.xml' in z.namelist():
            shared = [''.join(t.text or '' for t in item.iter('{' + NS + '}t')) for item in ET.fromstring(z.read('xl/sharedStrings.xml'))]
        sheet_root = ET.fromstring(z.read(part))
        rows = []
        for row in sheet_root.findall('s:sheetData/s:row', ns):
            values = {}
            for cell in row.findall('s:c', ns):
                index = 0
                for c in cell.get('r', ''):
                    if c.isalpha(): index = index * 26 + ord(c.upper()) - 64
                    else: break
                if not index: continue
                kind = cell.get('t')
                text = cell.findtext('s:v', '', ns)
                if kind == 'inlineStr': text = ''.join(t.text or '' for t in cell.findall('s:is//s:t', ns))
                elif kind == 's': text = shared[int(text)]
                values[index - 1] = text
            if values: rows.append(values)
    if not rows:
        return [], []
    headers = [rows[0].get(i, '') for i in range(max(rows[0]) + 1)]
    return headers, [{key: row.get(i, '') for i, key in enumerate(headers)} for row in rows[1:]]
