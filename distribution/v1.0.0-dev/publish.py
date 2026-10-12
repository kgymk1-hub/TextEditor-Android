"""Resume publication after uploads.github.com is allowed by the environment."""
import hashlib,json,subprocess
from pathlib import Path
root=Path(__file__).resolve().parent
repo='kgymk1-hub/TextEditor-Android'
release_id='409933157'
def info():
 return json.loads(subprocess.check_output(['gh','api',f'repos/{repo}/releases/{release_id}']))
expected={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in (root/'assets').iterdir() if p.is_file()}
checksums = {}
for line in (root/'assets/SHA256SUMS.txt').read_text().splitlines():
 digest, name = line.split('  ', 1)
 checksums[name] = digest
if set(expected) != set(checksums) | {'SHA256SUMS.txt'}:
 raise SystemExit('Local asset list does not match SHA256SUMS.txt.')
for name, digest in checksums.items():
 if expected[name] != digest: raise SystemExit('Local checksum mismatch: ' + name)
manifest=json.loads((root/'assets/build-manifest.json').read_text())
if not manifest.get('release_signed'): raise SystemExit('Expected signed release artifacts.')
release=info()
if release['tag_name']!='v1.0.0-dev' or not release['prerelease']:
 raise SystemExit('Unexpected release state; inspect before continuing.')
existing={a['name']:a for a in release['assets']}
for name,digest in expected.items():
 if name in existing:
  if existing[name].get('digest')!='sha256:'+digest:raise SystemExit('Remote asset mismatch: '+name)
 else:
  subprocess.run(['gh','release','upload','v1.0.0-dev',str(root/'assets'/name),'--repo',repo],check=True)
release=info();assets={a['name']:a for a in release['assets']}
if set(assets)!=set(expected):raise SystemExit('Remote asset list differs from the reviewed public package.')
for name,digest in expected.items():
 if assets[name].get('state')!='uploaded' or assets[name].get('digest')!='sha256:'+digest:
  raise SystemExit('Remote asset verification failed: '+name)
subprocess.run(['gh','release','edit','v1.0.0-dev','--repo',repo,'--draft=false','--prerelease'],check=True)
release=info()
assert not release['draft'] and release['prerelease']
print(release['html_url'])
