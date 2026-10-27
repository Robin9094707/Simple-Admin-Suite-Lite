package io.github.robin9094707.simpleadminlite;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Private bridge transport. Never exposes commands, files or general administration endpoints. */
final class BridgeServer implements AutoCloseable {
    private final SimpleAdminLite plugin;
    private final String secret;
    private final EnvelopeGuard guard=new EnvelopeGuard();
    private final ConcurrentMap<String,Snapshot> pending=new ConcurrentHashMap<>();
    private HttpServer server;private ExecutorService workers;
    volatile long heartbeat;
    record Snapshot(Properties data,long received){}
    BridgeServer(SimpleAdminLite plugin,String secret){this.plugin=plugin;this.secret=secret;}
    void start() throws Exception {
        InetAddress host=InetAddress.getByName(plugin.getConfig().getString("bridge.host","127.0.0.1"));
        if(!host.isLoopbackAddress())throw new IllegalArgumentException("The Sentinel receiver must bind to a loopback address. Use an SSH tunnel for a remote proxy.");
        server=HttpServer.create(new InetSocketAddress(host,plugin.getConfig().getInt("bridge.port",39093)),32);
        workers=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(64),r->{Thread t=new Thread(r,"SimpleSentinel-HTTP");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
        server.setExecutor(workers);server.createContext("/v1/session",e->handle(e,false));server.createContext("/v1/heartbeat",e->handle(e,true));server.start();
    }
    synchronized boolean acceptNonce(String nonce,long timestamp,long now){
        return guard.accept(nonce,timestamp,now);
    }
    private void handle(HttpExchange exchange,boolean isHeartbeat){
        try(exchange){
            if(!exchange.getRemoteAddress().getAddress().isLoopbackAddress()||!exchange.getRequestMethod().equals("POST")||!exchange.getRequestURI().getPath().equals(isHeartbeat?"/v1/heartbeat":"/v1/session")){reply(exchange,404,"Not found");return;}
            byte[] body=exchange.getRequestBody().readNBytes(65537);
            if(body.length>65536){reply(exchange,413,"Payload too large");return;}
            if(!Crypto.valid(body,secret,exchange.getRequestHeaders().getFirst("X-Simple-Signature"))){reply(exchange,401,"Invalid signature");return;}
            Properties p=Model.decode(body);long now=System.currentTimeMillis();long timestamp;
            try{timestamp=Long.parseLong(p.getProperty("timestamp","0"));}catch(NumberFormatException e){reply(exchange,400,"Invalid timestamp");return;}
            if(!acceptNonce(p.getProperty("nonce"),timestamp,now)){reply(exchange,409,"Expired or replayed envelope");return;}
            heartbeat=now;
            if(!isHeartbeat){
                String xuid=p.getProperty("xuid","");
                if(!xuid.matches("[0-9]{1,22}")){reply(exchange,400,"Authenticated XUID required");return;}
                if(p.size()>120||p.stringPropertyNames().stream().anyMatch(k->k.length()>80||p.getProperty(k).length()>2048)){reply(exchange,400,"Invalid metadata");return;}
                pending.entrySet().removeIf(e->e.getValue().received()<now-120000);
                if(pending.size()>=2048&&!pending.containsKey(xuid)){reply(exchange,429,"Receiver busy");return;}
                pending.put(xuid,new Snapshot(p,now));plugin.onBridgeSnapshot(xuid);
            }
            reply(exchange,200,"enabled=true\nsession-refresh=true\n");
        }catch(Exception error){plugin.getLogger().fine("Sentinel request rejected: "+error.getClass().getSimpleName());}
    }
    Snapshot get(String xuid){Snapshot s=pending.get(xuid);return s!=null&&s.received()>System.currentTimeMillis()-120000?s:null;}
    void forget(String xuid){if(xuid!=null)pending.remove(xuid);}
    private void reply(HttpExchange e,int status,String text) throws IOException {
        byte[] b=text.getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","text/plain; charset=utf-8");e.getResponseHeaders().set("X-Simple-Response-Signature",Crypto.hmac(b,secret));e.sendResponseHeaders(status,b.length);e.getResponseBody().write(b);
    }
    String status(){return heartbeat==0?"No bridge heartbeat received":System.currentTimeMillis()-heartbeat<150000?"Connected":"Heartbeat overdue";}
    @Override public void close(){if(server!=null)server.stop(0);if(workers!=null)workers.shutdownNow();pending.clear();}
}
