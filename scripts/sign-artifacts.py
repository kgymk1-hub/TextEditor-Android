#!/usr/bin/env python3
"""Sign existing release artifacts; passwords are read only from environment variables."""
import argparse
import os
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('--apk', required=True)
parser.add_argument('--aab', required=True)
parser.add_argument('--output', required=True)
args = parser.parse_args()
required = ('JAVA_HOME', 'ANDROID_HOME', 'POCKET_SIGN_STORE',
            'POCKET_SIGN_STORE_PASSWORD', 'POCKET_SIGN_ALIAS', 'POCKET_SIGN_KEY_PASSWORD')
if any(not os.environ.get(name) for name in required):
    raise SystemExit('Set JAVA_HOME, ANDROID_HOME and all four POCKET_SIGN_* variables.')
apk, aab = Path(args.apk).resolve(), Path(args.aab).resolve()
if not apk.is_file() or not aab.is_file():
    raise SystemExit('Both input release artifacts must exist.')
out = Path(args.output).resolve()
if out.exists():
    raise SystemExit('Output directory already exists; choose a new directory.')
out.mkdir(parents=True)
java = Path(os.environ['JAVA_HOME']) / 'bin'
tools = Path(os.environ['ANDROID_HOME']) / 'build-tools/35.0.0'
store, alias = os.environ['POCKET_SIGN_STORE'], os.environ['POCKET_SIGN_ALIAS']
def run(*command):
    subprocess.run([str(part) for part in command], check=True)
signed_apk = out / 'PocketTextEditor-release.apk'
signed_aab = out / 'PocketTextEditor-release.aab'
aligned = out / 'aligned-unsigned.apk'
run(tools / 'zipalign', '-P', '16', '-f', '4', apk, aligned)
run(tools / 'apksigner', 'sign', '--ks', store, '--ks-key-alias', alias,
    '--ks-pass', 'env:POCKET_SIGN_STORE_PASSWORD', '--key-pass', 'env:POCKET_SIGN_KEY_PASSWORD',
    '--out', signed_apk, aligned)
aligned.unlink()
run(tools / 'zipalign', '-c', '-P', '16', '4', signed_apk)
run(tools / 'apksigner', 'verify', '--verbose', '--print-certs', signed_apk)
run(java / 'jarsigner', '-keystore', store, '-storepass:env', 'POCKET_SIGN_STORE_PASSWORD',
    '-keypass:env', 'POCKET_SIGN_KEY_PASSWORD', '-sigalg', 'SHA256withRSA', '-digestalg', 'SHA-256',
    '-signedjar', signed_aab, aab, alias)
run(java / 'jarsigner', '-verify', signed_aab)
run(java / 'keytool', '-exportcert', '-rfc', '-keystore', store,
    '-storepass:env', 'POCKET_SIGN_STORE_PASSWORD', '-alias', alias,
    '-file', out / 'signing-certificate.pem')
print('Signed APK/AAB and exported public certificate. Keep the private keystore off GitHub.')
