#!/usr/bin/env python3
"""Check app text coverage, duplicate keys, arrays and format arguments.

Run from any directory with: python tools/check_localizations.py
Shared non-translatable values and arrays made entirely of resource references
use Android's default resources and do not require localized copies.
"""

from collections import Counter
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

RES = Path(__file__).resolve().parents[1] / 'app/src/main/res'
TEXT_TYPES = {'string', 'string-array', 'plurals'}
FORMAT = re.compile(r'%(?:(\d+)\$)?([-#+ 0,(<]*)\d*(?:\.\d+)?([tT]?[a-zA-Z%])')


def text(element):
    return ''.join(element.itertext()).strip()


def arguments(value):
    result = Counter()
    ordinary = 0
    previous = None
    for match in FORMAT.finditer(value):
        position, flags, conversion = match.groups()
        if conversion in {'%', 'n'}:
            continue
        if position:
            index = int(position)
        elif '<' in flags:
            index = previous
        else:
            ordinary += 1
            index = ordinary
        previous = index
        result[index, conversion] += 1
    return result


def read(directory, errors):
    entries = {}
    for path in sorted(directory.glob('*.xml')):
        for element in ET.parse(path).getroot():
            if element.tag not in TEXT_TYPES:
                continue
            key = element.tag, element.get('name')
            if key in entries:
                errors.append(f'{path}: duplicate {key}')
            entries[key] = element, path
    return entries


def main():
    errors = []
    base = read(RES / 'values', errors)
    required = {
        key: entry for key, entry in base.items()
        if entry[0].get('translatable') != 'false'
        and any(value.strip() and not value.strip().startswith('@')
                for value in entry[0].itertext())
    }
    directories = sorted(d for d in RES.glob('values-*') if (d / 'strings.xml').exists())
    for directory in directories:
        localized = read(directory, errors)
        missing = sorted(required.keys() - localized.keys())
        print(f'{directory.name}: {len(required) - len(missing)}/{len(required)}, missing {len(missing)}')
        for key in missing:
            errors.append(f'{directory}: missing {key}')
        for key, (element, path) in localized.items():
            if key not in required:
                continue
            default = required[key][0]
            if not text(element):
                errors.append(f'{path}: empty {key}')
            if element.tag == 'string':
                pairs = [(default, element)]
            elif element.tag == 'string-array':
                if len(element) != len(default):
                    errors.append(f'{path}: {key} array length {len(element)} != {len(default)}')
                pairs = list(zip(default, element))
            else:
                defaults = {item.get('quantity'): item for item in default}
                if not any(item.get('quantity') == 'other' for item in element):
                    errors.append(f'{path}: {key} lacks plural quantity other')
                pairs = [(defaults.get(item.get('quantity'), defaults['other']), item)
                         for item in element]
            for source, target in pairs:
                if not text(target):
                    errors.append(f'{path}: empty item in {key}')
                if default.get('formatted') == 'false':
                    continue
                expected = arguments(text(source))
                actual = arguments(text(target))
                # Singular forms may spell out the number or print it; French
                # also uses its singular form for zero. Both are valid when
                # the arguments are supplied by the default plural's other.
                if element.tag == 'plurals' and target.get('quantity') != 'other':
                    allowed = arguments(text(default.find("item[@quantity='other']")))
                    if not (actual - allowed):
                        continue
                if expected != actual:
                    errors.append(f'{path}: {key} format arguments differ: '
                                  f'{expected} != {actual}')
    for error in errors:
        print(error, file=sys.stderr)
    print(f'{len(directories)} locales checked; {len(errors)} errors')
    return bool(errors)


if __name__ == '__main__':
    sys.exit(main())
