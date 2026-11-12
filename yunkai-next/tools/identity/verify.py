#!/usr/bin/env python3
"""SDK-free identity/resource checks; not an Android build or device test."""
from pathlib import Path
from collections import Counter
import re
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[2]
res = root / 'app/src/main/res'
xmls = list(res.rglob('*.xml'))
for path in xmls:
    ET.parse(path)
ET.parse(root / 'app/src/main/AndroidManifest.xml')
locales = {}
for locale in ('values', 'values-zh'):
    elements = [e for p in (res / locale).glob('strings*.xml') for e in ET.parse(p).getroot()]
    keys = [e.attrib['name'] for e in elements]
    assert not [k for k, n in Counter(keys).items() if n > 1], locale
    locales[locale] = {e.attrib['name']: e for e in elements}
base, zh = locales['values'], locales['values-zh']
assert {k for k, e in base.items() if e.get('translatable') != 'false'} == set(zh)
assert base['app_name'].text == 'Yunkai' and zh['app_name'].text == '云开'
assert 'Original Mika' in base['about_author'].text
assert '原始 Mika' in zh['about_author'].text
about = (root / 'app/src/main/java/com/wangzan101/mika/ui/AboutScreen.kt').read_text()
visible = about[about.index('PageScaffold('):about.index('@Composable\nprivate fun Hero')]
assert 'onAskMikaToJoin()' not in visible
assert 'AboutSheet.Group -> Unit' in visible
assert 'onOpenUrl(issueUrl)' not in visible
assert 'AboutSheet.Update -> Unit' in visible
export = (root / 'app/src/main/java/com/wangzan101/mika/cap/sandbox/ShareExporter.kt').read_text()
assert 'Download/Mika' not in export and 'DIRECTORY_DOWNLOADS}/Mika' not in export
assert 'Download/Yunkai/$actual' in export
assert 'OWNER_PACKAGE_NAME' in export and 'context.packageName' in export
for p in [root / 'core/src/main/resources/prompts/capabilities/sandbox.md', root / 'core/src/main/resources/prompts/tools/share_file.md']:
    assert 'Download/Yunkai' in p.read_text()
manifest = ET.parse(root / 'app/src/main/AndroidManifest.xml').getroot()
app = manifest.find('application')
ns = '{http://schemas.android.com/apk/res/android}'
assert app.get(ns+'label') == '@string/app_name'
assert 'applicationId = "com.zhuolin.yunkai.next"' in (root/'app/build.gradle.kts').read_text()
for p in (root/'app/src/main').rglob('*.kt'):
    for name in re.findall(r'R\.drawable\.(\w+launcher_art)',p.read_text()):
        assert (res/'drawable-nodpi'/f'{name}.png').is_file(), (p,name)
print(f'PASS: {len(xmls)} resource XMLs + manifest, locale keys ({len(base)}/{len(zh)}), names, attribution, support/update isolation, export path/owner guard, icon refs, application ID')
