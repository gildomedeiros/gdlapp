#!/usr/bin/env python3
r"""Export recorded GPS points only for report rows marked y in the KML column.

Keep records.xlsx and its matching gps.kml together. No original flight logs or
video files are required. Coordinates, labels, colours and direction arrows
are copied unchanged from gps.kml; no coordinates are inferred from the workbook.

PowerShell:
  python telemetry_selected_kml.py --report "C:\Users\gildo\temp\telemetry-report\records.xlsx"

Output defaults to selected.kml beside the workbook. Override --source-kml if the
workbook was moved, or --output to choose another destination. Save LibreOffice/Excel edits to
workbook before running. Do not regenerate records.xlsx after marking your selections.
"""
import argparse
import zipfile
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from telemetry_xlsx import read_xlsx

NS = 'http://www.opengis.net/kml/2.2'
N = {'k': NS}


def export_selected(report, source_kml=None, output=None):
    report = Path(report).resolve()
    source = Path(source_kml).resolve() if source_kml else report.with_name('gps.kml')
    output = Path(output).resolve() if output else report.with_name('selected.kml')
    if output in (report, source):
        raise ValueError('Output must not overwrite the input workbook or source KML')
    if report.suffix.lower() != '.xlsx':
        raise ValueError('Use records.xlsx; save the edited report as Excel 2007-365 (.xlsx) in LibreOffice.')
    headers, rows = read_xlsx(report)
    required = {'KML', 'droneKmlPoint', 'surferKmlPoint'}
    if not required.issubset(headers):
        raise ValueError('Workbook must contain KML, droneKmlPoint and surferKmlPoint columns.')
    selected, marked, empty = set(), 0, 0
    for row in rows:
        if (row.get('KML') or '').strip().lower() != 'y':
            continue
        marked += 1
        labels = [label.strip() for key in ('droneKmlPoint', 'surferKmlPoint')
                  for label in (row.get(key) or '').split(';') if label.strip()]
        if not labels:
            empty += 1
        selected.update(labels)
    if not marked:
        raise ValueError('No rows marked y in the KML column. No output written.')
    if not selected:
        raise ValueError('Marked rows have no recorded KML point references. No positions can be exported.')
    tree = ET.parse(source)
    root = tree.getroot()
    points = {}
    for mark in root.findall('.//k:Placemark', N):
        if mark.find('k:Point', N) is not None:
            label = mark.findtext('k:name', '', N)
            if label in points:
                raise ValueError(f'Duplicate point label {label} in source KML')
            points[label] = mark
    missing = selected - points.keys()
    if missing:
        raise ValueError('Selected labels are absent from this gps.kml. Use the matching report/KML pair: ' + ', '.join(sorted(missing)))
    kept_arrows = 0
    for parent in root.iter():
        for mark in list(parent):
            if mark.tag != '{' + NS + '}Placemark':
                continue
            name = mark.findtext('k:name', '', N)
            is_point = mark.find('k:Point', N) is not None
            label = name if is_point else name.split(' ', 1)[0]
            if label not in selected:
                parent.remove(mark)
            elif not is_point:
                kept_arrows += 1
    # Remove empty folders, retaining all original colour styles.
    for parent in reversed(list(root.iter())):
        for child in list(parent):
            if child.tag == '{' + NS + '}Folder' and child.find('.//k:Placemark', N) is None:
                parent.remove(child)
    doc = root.find('k:Document', N)
    if doc is not None:
        name = doc.find('k:name', N)
        if name is not None:
            name.text = 'Selected report rows — recorded GPS'
        description = doc.find('k:description', N)
        if description is not None:
            description.text = (f'{marked} report rows marked y; {len(selected)} unique recorded points. '
                                'Labels, coordinates and retreat colours preserved. Direction arrows retain '
                                'their original bearing, which may refer to a previous point not selected here.\n'
                                + (description.text or ''))
    ET.register_namespace('', NS)
    output.parent.mkdir(parents=True, exist_ok=True)
    tree.write(output, encoding='utf-8', xml_declaration=True)
    print(f'Exported {len(selected)} points and {kept_arrows} direction arrows from {marked} marked rows: {output}')
    if empty:
        print(f'{empty} marked rows had no plotted point references; no positions were invented.', file=sys.stderr)
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--report', required=True, help='Edited records.xlsx with y in KML column')
    parser.add_argument('--source-kml', help='Matching full gps.kml (default beside workbook)')
    parser.add_argument('--output', help='Destination (default selected.kml beside workbook)')
    args = parser.parse_args()
    try:
        export_selected(args.report, args.source_kml, args.output)
    except (ValueError, OSError, ET.ParseError, zipfile.BadZipFile, KeyError, IndexError) as error:
        parser.exit(2, f'Error: {error}\n')


if __name__ == '__main__':
    main()
