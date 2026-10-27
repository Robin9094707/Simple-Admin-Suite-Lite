#!/usr/bin/env python3
"""Isolated real Paper startup/console/persistence smoke checks. Use JAVA_HOME for Java 25."""
import argparse, hashlib, json, os, pathlib, queue, re, shutil, socket, sqlite3, subprocess, threading, time, urllib.request, urllib.parse, zipfile, hmac, uuid

ROOT=pathlib.Path(__file__).resolve().parents[1]

def free_port():
    with socket.socket() as s:s.bind(('127.0.0.1',0));return s.getsockname()[1]

def check(item,crossplay=False,timeout=240):
    name=item['version']+('-crossplay' if crossplay else '-java')
    work=ROOT/'build'/'smoke'/name;work.mkdir(parents=True,exist_ok=True)
    jar=ROOT/'.deps'/item['name']
    if not jar.exists() or hashlib.sha256(jar.read_bytes()).hexdigest()!=item['sha256']:
        with urllib.request.urlopen(urllib.request.Request(item['url'],headers={'User-Agent':'SimpleAdminSuiteLite/1.0.0 (https://github.com/Robin9094707/Simple-Admin-Suite-Lite)'}),timeout=120) as r:jar.write_bytes(r.read())
    assert hashlib.sha256(jar.read_bytes()).hexdigest()==item['sha256']
    # Precache the exact Mojang object declared by the verified Paperclip JAR.
    # Python follows the host's configured network proxy; a bare JVM may not.
    with zipfile.ZipFile(jar) as archive:
        digest,url,filename=archive.read('META-INF/download-context').decode().strip().split('\t')
    cache=work/'cache';cache.mkdir(exist_ok=True);mojang=cache/filename
    if not mojang.exists() or hashlib.sha256(mojang.read_bytes()).hexdigest()!=digest:
        with urllib.request.urlopen(url,timeout=120) as response:mojang.write_bytes(response.read())
    assert hashlib.sha256(mojang.read_bytes()).hexdigest()==digest
    plugins=work/'plugins';plugins.mkdir(exist_ok=True)
    shutil.copyfile(ROOT/'dist/SimpleAdminSuiteLite-1.0.0.jar',plugins/'SimpleAdminSuiteLite.jar')
    suite=plugins/'SimpleAdminSuiteLite';suite.mkdir(exist_ok=True)
    bridge_port=free_port()
    (suite/'config.yml').write_text('prefix: "[Simple] "\nidentity-secret: "'+'a'*64+'"\nbridge:\n  enabled: true\n  host: 127.0.0.1\n  port: '+str(bridge_port)+'\n  shared-secret: "'+'b'*64+'"\nupdates:\n  hourly-check: false\n')
    if crossplay:
        shutil.copyfile(ROOT/'.deps/Geyser-Spigot-2.12.1-1273.jar',plugins/'Geyser-Spigot.jar')
        flood=ROOT/'.deps/floodgate-spigot-2.2.5-141.jar'
        if not flood.exists():
            lock=json.loads((ROOT/'tools/crossplay.json').read_text());entry=lock[0]
            with urllib.request.urlopen(entry['url'],timeout=120) as r:flood.write_bytes(r.read())
            assert hashlib.sha256(flood.read_bytes()).hexdigest()==entry['sha256']
        shutil.copyfile(flood,plugins/'floodgate-spigot.jar')
        extension=plugins/'Geyser-Spigot/extensions';extension.mkdir(parents=True,exist_ok=True)
        shutil.copyfile(ROOT/'dist/SimpleSentinelBridge-1.0.0.jar',extension/'SimpleSentinelBridge.jar')
        config=extension/'simplesentinelbridge';config.mkdir(exist_ok=True)
        (config/'bridge.properties').write_text('host=127.0.0.1\nport='+str(bridge_port)+'\nshared-secret='+'b'*64+'\n')
        geyser=plugins/'Geyser-Spigot/config.yml'
        geyser.write_text('bedrock:\n  address: 127.0.0.1\n  port: '+str(free_port())+'\n  transport: raknet\nremote:\n  address: auto\n  auth-type: floodgate\nconfig-version: 4\n')
    (work/'eula.txt').write_text('eula=true\n')
    (work/'server.properties').write_text('server-ip=127.0.0.1\nserver-port='+str(free_port())+'\nonline-mode=false\nenforce-secure-profile=false\nlevel-type=minecraft:flat\ngenerate-structures=false\nspawn-protection=0\nview-distance=2\nsimulation-distance=2\nmax-players=2\nenable-rcon=false\n')
    java=str(pathlib.Path(os.environ['JAVA_HOME'])/'bin/java') if os.environ.get('JAVA_HOME') else 'java'
    options=[]
    proxy=urllib.parse.urlparse(os.environ.get('HTTPS_PROXY',os.environ.get('https_proxy','')))
    if proxy.hostname:
        options += ['-Dhttps.proxyHost='+proxy.hostname,'-Dhttps.proxyPort='+str(proxy.port or 80),'-Dhttp.proxyHost='+proxy.hostname,'-Dhttp.proxyPort='+str(proxy.port or 80),'-Dhttp.nonProxyHosts=localhost|127.*|[::1]']
    if os.environ.get('JAVA_TEST_TRUSTSTORE'):options += ['-Djavax.net.ssl.trustStore='+os.environ['JAVA_TEST_TRUSTSTORE']]
    lines=[];messages=queue.Queue();log=open(work/'smoke-console.log','w')
    process=subprocess.Popen([java,*options,'--enable-native-access=ALL-UNNAMED','-Xms256M','-Xmx1400M','-jar',str(jar),'--nogui'],cwd=work,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,bufsize=1)
    def reader():
        for line in process.stdout:lines.append(line);log.write(line);log.flush();messages.put(line)
    threading.Thread(target=reader,daemon=True).start()
    def wait(pattern,seconds=timeout):
        deadline=time.monotonic()+seconds
        while time.monotonic()<deadline:
            try:
                line=messages.get(timeout=1)
                if re.search(pattern,line):return line
            except queue.Empty:
                if process.poll() is not None:raise RuntimeError(name+' exited before '+pattern+'\n'+''.join(lines[-20:]))
        raise RuntimeError(name+' timeout waiting for '+pattern+'\n'+''.join(lines[-20:]))
    def command(text):process.stdin.write(text+'\n');process.stdin.flush()
    try:
        wait(r'Done \(')
        assert any('Simple Admin Suite Lite 1.0.0 enabled' in line for line in lines),'Suite did not enable'
        body=('timestamp='+str(int(time.time()*1000))+'\nnonce='+str(uuid.uuid4())+'\n').encode()
        signature=hmac.new(('b'*64).encode(),body,hashlib.sha256).hexdigest()
        req=urllib.request.Request('http://127.0.0.1:'+str(bridge_port)+'/v1/heartbeat',data=body,headers={'X-Simple-Signature':signature})
        with urllib.request.urlopen(req,timeout=5) as response:
            payload=response.read();assert response.headers['X-Simple-Response-Signature']==hmac.new(('b'*64).encode(),payload,hashlib.sha256).hexdigest()
        try:urllib.request.urlopen(req,timeout=5);raise AssertionError('Bridge accepted a replay')
        except urllib.error.HTTPError as error:assert error.code==409
        command('sal help');wait(r'Open the security menu',30)
        command('sal reload');wait(r'configuration reloaded',30)
        command('sal ban 00000000-0000-0000-0000-000000000001 account 1h true CI-test')
        line=wait(r'Ban saved: ([0-9a-f-]+)',30);rule=re.search(r'Ban saved: ([0-9a-f-]+)',line).group(1)
        db=sqlite3.connect('file:'+str(suite/'security.sqlite')+'?mode=ro',uri=True)
        assert db.execute('SELECT reason FROM rules WHERE id=?',(rule,)).fetchone()==('CI-test',);db.close()
        command('sal export preview');preview=wait(r'export confirm ([0-9a-f]+)',30);token=re.search(r'export confirm ([0-9a-f]+)',preview).group(1)
        command('sal export confirm '+token);wait(r'Minecraft export complete',30)
        exported=json.loads((work/'banned-players.json').read_text());assert any(row.get('uuid')=='00000000-0000-0000-0000-000000000001' and row.get('reason')=='CI-test' for row in exported)
        command('sal unban '+rule);wait(r'Ban revoked',30)
        if crossplay:
            deadline=time.monotonic()+25
            while time.monotonic()<deadline and not any('SimpleSentinelBridge 1.0.0 ready' in l for l in lines):time.sleep(.25)
            assert any('SimpleSentinelBridge 1.0.0 ready' in l for l in lines),'Bridge did not initialize'
        command('stop');process.wait(timeout=60)
        errors=[l for l in lines if 'Error occurred while enabling SimpleAdminSuiteLite' in l or 'Could not load' in l and 'Simple' in l or 'Cannot initialize Lite' in l or 'NoSuchMethodError' in l or 'NoClassDefFoundError' in l]
        assert not errors,''.join(errors)
        print('PASS real Paper',name,'| suite enable, reload, durable ban, unban, clean shutdown',flush=True)
        return name
    finally:
        if process.poll() is None:
            try:command('stop');process.wait(timeout=30)
            except Exception:process.kill()
        log.close()

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--version');parser.add_argument('--crossplay',action='store_true');args=parser.parse_args()
    for item in json.loads((ROOT/'tools/servers.json').read_text()):
        if not args.version or item['version']==args.version:check(item,args.crossplay)

if __name__=='__main__':main()
