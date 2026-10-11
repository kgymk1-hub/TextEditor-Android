"""Run Gradle through the cloud machine's injected proxy without weakening TLS."""
import argparse, os, subprocess
from pathlib import Path
from urllib.parse import urlparse

def main():
    p=argparse.ArgumentParser();p.add_argument('--java',required=True);p.add_argument('--sdk',required=True);p.add_argument('--gradle',required=True);p.add_argument('--project',default=str(Path(__file__).resolve().parents[1]));p.add_argument('tasks',nargs='+')
    a=p.parse_args()
    if not (Path(a.java)/'bin/javac').exists():raise SystemExit('A full JDK is required, not just a Java runtime')
    env=dict(os.environ,JAVA_HOME=a.java,ANDROID_HOME=a.sdk)
    env.setdefault('GRADLE_USER_HOME',str(Path(a.project).resolve()/'work/gradle-cache'))
    env.setdefault('ANDROID_USER_HOME',str(Path(a.project).resolve()/'work/android-user'))
    args=[a.gradle]
    proxy=urlparse(os.environ.get('HTTPS_PROXY') or os.environ.get('https_proxy') or '')
    if proxy.hostname:
        if proxy.username or proxy.password:raise SystemExit('Authenticated proxy needs a supported secure binding; do not put credentials in JVM arguments')
        for protocol in ['http','https']:args += [f'-D{protocol}.proxyHost={proxy.hostname}',f'-D{protocol}.proxyPort={proxy.port or 80}']
    system_trust=Path('/etc/ssl/certs/java/cacerts')
    if system_trust.exists():args += [f'-Djavax.net.ssl.trustStore={system_trust}']
    args += ['-p',a.project,'--no-daemon','--max-workers=2','--stacktrace',*a.tasks]
    return subprocess.call(args,env=env)
if __name__=='__main__':raise SystemExit(main())
