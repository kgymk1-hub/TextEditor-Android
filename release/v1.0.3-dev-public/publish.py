"""Publish only the reviewed signed 1.0.3-dev files; no private signing material."""
import hashlib,json,os,subprocess,tempfile,zipfile
from pathlib import Path
root=Path(__file__).resolve().parent
repo='kgymk1-hub/TextEditor-Android'
tag='v1.0.3-dev'
release_id='411472010'
source_commit='8732480eb5fe470c10c8d5cd086592ea03f3785a'
certificate='796224e6df568f22837800ea80a57d2549734d3e4b5cd5421a63aab36a90fe48'
assets=root/'assets'
def api():return json.loads(subprocess.check_output(['gh','api',f'repos/{repo}/releases/{release_id}']))
def digest(p):return hashlib.sha256(p.read_bytes()).hexdigest()
expected={p.name:digest(p) for p in assets.iterdir() if p.is_file()}
checksums={}
for line in (assets/'SHA256SUMS.txt').read_text().splitlines():
 h,n=line.split('  ',1);checksums[n]=h
assert set(expected)==set(checksums)|{'SHA256SUMS.txt'},'Unexpected local asset list'
assert all(expected[n]==h for n,h in checksums.items()),'Local checksum mismatch'
manifest=json.loads((assets/'build-manifest.json').read_text())
assert manifest['commit']==source_commit and manifest['release_signed']
assert manifest['version_name']=='1.0.3-dev' and manifest['version_code']==4
assert manifest['signing_certificate_sha256']==certificate
with zipfile.ZipFile(assets/'TextEditor-Android-source.zip') as z:
 assert not any(any(x in n.lower() for x in ('private-signing','credentials.json','.jks','.keystore','/work/','/release/asset/')) for n in z.namelist()),'Private or binary source entry'
sdk=Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or '/usr/local/lib/android/sdk')
signers=sorted((sdk/'build-tools').glob('*/apksigner'))
assert signers,'Android apksigner is required'
verification=subprocess.check_output([str(signers[-1]),'verify','--verbose','--print-certs',str(assets/'PocketTextEditor-release.apk')],text=True)
assert 'certificate SHA-256 digest: '+certificate in verification,'APK certificate mismatch'
print(verification)
cert=subprocess.check_output(['openssl','x509','-in',str(assets/'signing-certificate.pem'),'-noout','-fingerprint','-sha256'],text=True).strip().split('=')[1].replace(':','').lower()
assert cert==certificate,'Public certificate mismatch'
subprocess.run(['jarsigner','-verify',str(assets/'PocketTextEditor-release.aab')],check=True)
release=api()
assert release['tag_name']==tag and release['prerelease'],'Unexpected release state'
assert release['target_commitish']==source_commit,'Unexpected source target'
existing={a['name']:a for a in release['assets']}
assert set(existing)<=set(expected),'Unexpected remote assets'
for name,h in expected.items():
 if name in existing:assert existing[name].get('digest')=='sha256:'+h,'Remote checksum mismatch: '+name
 else:subprocess.run(['gh','release','upload',tag,str(assets/name),'--repo',repo],check=True)
remote={a['name']:a for a in api()['assets']}
assert set(remote)==set(expected),'Remote asset list mismatch'
for n,h in expected.items():
 assert remote[n]['state']=='uploaded' and remote[n].get('digest')=='sha256:'+h,'Remote verification failed: '+n
with tempfile.TemporaryDirectory() as directory:
 subprocess.run(['gh','release','download',tag,'--repo',repo,'--dir',directory],check=True)
 for n,h in expected.items():assert digest(Path(directory)/n)==h,'Downloaded checksum mismatch: '+n
subprocess.run(['gh','release','edit',tag,'--repo',repo,'--draft=false','--prerelease'],check=True)
release=api()
assert not release['draft'] and release['prerelease']
print('Published verified prerelease:',release['html_url'])
