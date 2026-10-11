#!/usr/bin/env python3
"""Package reviewed tracked sources, built binaries and successful verification reports."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument('--output', default='work/deliverables')
args = parser.parse_args()
root = Path(__file__).resolve().parent.parent
out = (root / args.output).resolve()
if subprocess.check_output(['git', 'status', '--porcelain'], cwd=root).strip():
    raise SystemExit('Commit reviewed changes before packaging; working tree must be clean.')
revision = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
reports = list((root / 'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'))
if not reports:
    raise SystemExit('Run testDebugUnitTest before packaging.')
tests = failures = errors = skipped = 0
for report in reports:
    suite = ET.parse(report).getroot()
    for field in ('tests', 'failures', 'errors', 'skipped'):
        value = int(suite.get(field, '0'))
        if field == 'tests': tests += value
        elif field == 'failures': failures += value
        elif field == 'errors': errors += value
        else: skipped += value
if failures or errors or skipped:
    raise SystemExit('Tests must pass without skipped cases before packaging.')
warnings = {}
for variant in ('debug', 'release'):
    report = root / f'app/build/reports/lint-results-{variant}.xml'
    if not report.exists():
        raise SystemExit(f'Run lint{variant.title()} before packaging.')
    issues = ET.parse(report).getroot().findall('issue')
    if any(i.get('severity') in ('Error', 'Fatal') for i in issues):
        raise SystemExit('Lint contains errors.')
    warnings[variant] = len(issues)
artifacts = {
    'PocketTextEditor-debug.apk': 'app/build/outputs/apk/debug/app-debug.apk',
    'PocketTextEditor-release-unsigned.apk': 'app/build/outputs/apk/release/app-release-unsigned.apk',
    'PocketTextEditor-release-unsigned.aab': 'app/build/outputs/bundle/release/app-release.aab',
}
if any(not (root / path).exists() for path in artifacts.values()):
    raise SystemExit('Build all debug/release APK and AAB artifacts before packaging.')
out.mkdir(parents=True, exist_ok=True)
for name, path in artifacts.items():
    shutil.copy2(root / path, out / name)
subprocess.run(['git', 'archive', '--format=zip', '--prefix=TextEditor-Android/', '-o', str(out / 'TextEditor-Android-source.zip'), 'HEAD'], cwd=root, check=True)
with zipfile.ZipFile(out / 'test-and-lint-reports.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
    for folder in ('app/build/test-results/testDebugUnitTest', 'app/build/reports/tests/testDebugUnitTest'):
        for file in sorted((root / folder).rglob('*')):
            if file.is_file(): archive.write(file, file.relative_to(root))
    for file in sorted((root / 'app/build/reports').glob('lint-results-*.*')):
        archive.write(file, file.relative_to(root))
shutil.copy2(root / 'README.md', out / 'README.md')
shutil.copy2(root / 'docs/RELEASE.md', out / 'RELEASE.md')
(out / '検証結果.md').write_text(f'''# 検証結果

ソースコミット: `{revision}`

- JUnit / Robolectric: {tests}件成功、失敗・エラー・スキップ0件。
- Debug / Release Lint: エラー0件、警告はそれぞれ{warnings['debug']}件／{warnings['release']}件。詳細は同梱レポート。
- Debug APK、署名前のRelease APK、署名前のAABを生成。
- 大容量編集: 20MiB末尾編集、区間をまたぐUndo/Redo、連続編集Undo、区間外の本文保持、サロゲートとCRLFの境界を自動検証。
- CSV: 複数行引用符、行索引、20MiB表、20万行／60万セル相当を自動検証。
- Room: 本文FTS更新、旧DB移行時の本文・履歴・位置保持を自動検証。
- 日本語入力: InputConnectionの未確定更新→確定を自動検証。実IMEの受入は別途必要。

実機試験は実施していません。RobolectricはAndroid APIのシミュレーションであり、端末のp95性能・実IME・SAF提供元・TalkBackの受入結果ではありません。

本番署名鍵は提供されていないため、Release APK／AABは署名前です。所有者の鍵を使う設定はソースとRELEASE.mdに含まれます。ストア公開は行っていません。
''', encoding='utf-8')
manifest = {'commit': revision, 'tests': tests, 'lint_warnings': warnings, 'device_tests': False, 'release_signed': False}
(out / 'build-manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
paths = sorted(p for p in out.iterdir() if p.is_file() and p.name != 'SHA256SUMS.txt')
(out / 'SHA256SUMS.txt').write_text(''.join(f'{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n' for p in paths), encoding='utf-8')
print(f'Packaged {len(paths)} files; {tests} tests passed; source commit {revision}')
