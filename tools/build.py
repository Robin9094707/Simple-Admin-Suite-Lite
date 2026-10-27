#!/usr/bin/env python3
"""Reproducible real-API build. Python 3.10+ and Java 17+; Java 25 for the complete test suite."""
import argparse, concurrent.futures, hashlib, json, os, pathlib, shutil, subprocess, sys, urllib.request, zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
DEPS = ROOT / '.deps'
BUILD = ROOT / 'build'
DIST = ROOT / 'dist'
VERSION = '1.0.0'
LOCK = json.loads((ROOT / 'tools/dependencies.json').read_text())

def sha(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for block in iter(lambda: f.read(1024 * 1024), b''): h.update(block)
    return h.hexdigest()

def dependency(item):
    path = DEPS / item['name']
    if path.exists() and sha(path) == item['sha256']: return path
    req = urllib.request.Request(item['url'], headers={'User-Agent': 'SimpleAdminSuiteLite/1.0.0'})
    temporary = path.with_suffix('.download')
    try:
        with urllib.request.urlopen(req, timeout=120) as src, open(temporary, 'wb') as out:
            shutil.copyfileobj(src, out)
        if sha(temporary) != item['sha256']: raise RuntimeError('Dependency checksum mismatch: ' + item['name'])
        temporary.replace(path)
    finally: temporary.unlink(missing_ok=True)
    print('Verified dependency:', item['name'], flush=True)
    return path

def java():
    home = os.environ.get('JAVA_HOME')
    return str(pathlib.Path(home) / 'bin/java') if home else 'java'

def run(args):
    subprocess.run([str(x) for x in args], cwd=ROOT, check=True)

def classpath(paper='paper-api-1.21.11.jar'):
    return os.pathsep.join(str(p) for p in sorted(DEPS.glob('*.jar')) if not p.name.startswith('paper-api-') or p.name == paper)

def compile_module(module, paper='paper-api-1.21.11.jar', tests=False, out=None):
    target = out or BUILD / (module + ('-tests' if tests else ''))
    if target.exists(): shutil.rmtree(target)
    target.mkdir(parents=True)
    sources = list((ROOT / module / 'src/main/java').rglob('*.java'))
    if tests: sources += list((ROOT / module / 'src/test/java').rglob('*.java'))
    run([java(), '-jar', DEPS / 'ecj-3.46.0.jar', '-17', '-encoding', 'UTF-8', '-proc:none', '-warn:none', '-cp', classpath(paper), '-d', target, *sorted(sources)])
    return target

def write_entry(z, name, data):
    info = zipfile.ZipInfo(name, (2026, 10, 27, 0, 0, 0)); info.compress_type = zipfile.ZIP_DEFLATED; info.external_attr = 0o644 << 16
    z.writestr(info, data)

def jar(module, title, output):
    entries = {'META-INF/MANIFEST.MF': ('Manifest-Version: 1.0\r\nImplementation-Title: ' + title + '\r\nImplementation-Version: ' + VERSION + '\r\n\r\n').encode()}
    for directory in [BUILD / module, ROOT / module / 'src/main/resources']:
        for p in directory.rglob('*'):
            if p.is_file(): entries[p.relative_to(directory).as_posix()] = p.read_bytes()
    if module == 'suite':
        services = {}
        for filename in ['sqlite-jdbc-3.53.4.0.jar', 'h2-2.4.240.jar']:
            with zipfile.ZipFile(DEPS / filename) as source:
                for info in source.infolist():
                    name = info.filename
                    if info.is_dir() or name == 'module-info.class' or name.endswith(('.SF', '.RSA', '.DSA')) or name == 'META-INF/MANIFEST.MF': continue
                    if name.startswith('META-INF/versions/') and name.endswith('/module-info.class'): continue
                    if name.startswith('META-INF/services/'):
                        services[name] = services.get(name, b'') + source.read(name) + b'\n'; continue
                    if name.startswith('META-INF/') and ('LICENSE' in name.upper() or 'NOTICE' in name.upper()): name = 'META-INF/licenses/' + filename + '/' + name.split('/')[-1]
                    # Preserve all SQLite native architectures; portable deployments can use the same JAR.
                    entries.setdefault(name, source.read(info.filename))
        entries.update(services)
    with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as target:
        for name in sorted(entries): write_entry(target, name, entries[name])
    with zipfile.ZipFile(output) as result:
        forbidden = ('org/bukkit/', 'org/geysermc/geyser/api/', 'org/geysermc/cumulus/')
        if any(n.startswith(forbidden) for n in result.namelist()): raise RuntimeError('A server API was accidentally packaged')
    print('Built', output.name, output.stat().st_size, 'bytes', flush=True)

def source_zip(module, output):
    files = [ROOT / 'README.md', ROOT / 'INSTALLATION.md', ROOT / 'SECURITY-MODEL.md', ROOT / 'VALIDATION.md']
    files += list((ROOT / 'tools').glob('*.py')) + list((ROOT / 'tools').glob('*.json'))
    files += [p for p in (ROOT / module).rglob('*') if p.is_file() and 'target' not in p.parts]
    with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as target:
        for p in sorted(files):
            if p.exists(): write_entry(target, p.relative_to(ROOT).as_posix(), p.read_bytes())

def main():
    parser = argparse.ArgumentParser(); parser.add_argument('module', choices=['all', 'suite', 'sentinel-bridge'], nargs='?', default='all'); parser.add_argument('--test', action='store_true'); parser.add_argument('--compatibility', action='store_true'); args = parser.parse_args()
    DEPS.mkdir(exist_ok=True); BUILD.mkdir(exist_ok=True); DIST.mkdir(exist_ok=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool: list(pool.map(dependency, LOCK))
    modules = ['suite', 'sentinel-bridge'] if args.module == 'all' else [args.module]
    for module in modules:
        compile_module(module)
        base = 'SimpleAdminSuiteLite' if module == 'suite' else 'SimpleSentinelBridge'
        jar(module, base, DIST / (base + '-' + VERSION + '.jar'))
        source_zip(module, DIST / (base + '-' + VERSION + '-source.zip'))
    if args.test:
        target = compile_module('suite', tests=True)
        run([java(), '-jar', DEPS / 'junit-platform-console-standalone-1.14.0.jar', 'execute', '--class-path', str(target) + os.pathsep + classpath(), '--scan-class-path=' + str(target), '--fail-if-no-tests', '--disable-banner', '--details=summary', '--reports-dir=' + str(BUILD / 'test-results')])
    if args.compatibility:
        compile_module('suite', 'paper-api-26.3.jar', out=BUILD / 'suite-api26')
        print('Compatibility compile passed: Paper API 1.21.11 and 26.3', flush=True)
    artifacts = sorted(p for p in DIST.iterdir() if p.suffix in ['.jar', '.zip'])
    (DIST / 'SHA256SUMS.txt').write_text(''.join(sha(p) + '  ' + p.name + '\n' for p in artifacts))

if __name__ == '__main__': main()
