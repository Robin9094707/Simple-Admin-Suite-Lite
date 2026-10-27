package io.github.robin9094707.simpleadminlite;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import static org.junit.jupiter.api.Assertions.*;

class TransportAndUpdateTest {
    @TempDir Path temp;
    @Test void replayAndClockSkewAreRejected(){EnvelopeGuard guard=new EnvelopeGuard();long now=System.currentTimeMillis();String nonce=UUID.randomUUID().toString();assertTrue(guard.accept(nonce,now,now));assertFalse(guard.accept(nonce,now,now));assertFalse(guard.accept(UUID.randomUUID().toString(),now-60001,now));assertFalse(guard.accept(UUID.randomUUID().toString(),now+60001,now));assertFalse(guard.accept("short",now,now));}
    @Test void nonceCapacityIsBoundedAndOldEntriesArePruned(){EnvelopeGuard guard=new EnvelopeGuard();long now=System.currentTimeMillis();for(int i=0;i<4096;i++)assertTrue(guard.accept(UUID.randomUUID().toString(),now,now));assertFalse(guard.accept(UUID.randomUUID().toString(),now,now));assertTrue(guard.accept(UUID.randomUUID().toString(),now+100000,now+100000));}
    @Test void downloadLimitDoesNotWritePastTheBound(){ByteArrayOutputStream out=new ByteArrayOutputStream();assertThrows(IOException.class,()->Updates.copyLimited(new ByteArrayInputStream(new byte[11]),out,10));assertEquals(0,out.size());}
    @Test void minecraftVersionsHandleTheYearBasedScheme(){assertTrue(Updates.compareVersion("26.3","1.21.11")>0);assertTrue(Updates.compareVersion("1.21.11","26.3")<0);assertEquals(0,Updates.compareVersion("1.21.11","1.21.11"));assertTrue(Updates.compareVersion("1.21.12","1.21.11")>0);}
    @Test void compatibleJarIsAccepted() throws Exception {Path jar=jar("Geyser-Spigot","1.21",65);Updates.verifyJar(jar,"Geyser-Spigot",21,"1.21.11-R0.1-SNAPSHOT");}
    @Test void wrongPluginIsRejected() throws Exception {Path jar=jar("AnotherPlugin","1.21",65);assertThrows(IOException.class,()->Updates.verifyJar(jar,"Geyser-Spigot",21,"1.21.11"));}
    @Test void newerJavaIsRejected() throws Exception {Path jar=jar("Geyser-Spigot","1.21",69);assertThrows(IOException.class,()->Updates.verifyJar(jar,"Geyser-Spigot",21,"1.21.11"));}
    @Test void newerMinecraftApiIsRejected() throws Exception {Path jar=jar("Geyser-Spigot","26.3",65);assertThrows(IOException.class,()->Updates.verifyJar(jar,"Geyser-Spigot",21,"1.21.11"));}
    @Test void malformedJarIsRejected() throws Exception {Path jar=temp.resolve("invalid.jar");Files.writeString(jar,"not a jar");assertThrows(IOException.class,()->Updates.verifyJar(jar,"Geyser-Spigot",21,"1.21.11"));}
    private Path jar(String name,String api,int major)throws Exception {
        Path out=temp.resolve(UUID.randomUUID()+".jar");try(JarOutputStream zip=new JarOutputStream(Files.newOutputStream(out))){
            zip.putNextEntry(new JarEntry("plugin.yml"));zip.write(("name: "+name+"\nversion: 1.0\nmain: test.Main\napi-version: '"+api+"'\n").getBytes());zip.closeEntry();zip.putNextEntry(new JarEntry("test/Main.class"));DataOutputStream data=new DataOutputStream(zip);data.writeInt(0xcafebabe);data.writeShort(0);data.writeShort(major);zip.closeEntry();
        }return out;
    }
}
