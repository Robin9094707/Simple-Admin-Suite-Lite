package io.github.robin9094707.simpleadminlite;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.robin9094707.simpleadminlite.Model.*;

class BanTransferTest {
    @TempDir Path temp;
    String a="00000000-0000-0000-0000-000000000001";
    @Test void vanillaPreviewDoesNotWriteAndPreservesExpiryReasonAndType() throws Exception {
        Path file=temp.resolve("banned-players.json");String data="[{\"uuid\":\""+a+"\",\"name\":\"Alice\",\"created\":\"2026-01-01 00:00:00 +0000\",\"expires\":\"2099-01-01 00:00:00 +0000\",\"reason\":\"Original reason\"}]";Files.writeString(file,data);
        BanTransfer.Preview p=BanTransfer.vanilla(temp,"Staff");assertEquals(data,Files.readString(file));assertEquals(1,p.rows().size());Rule r=p.rows().get(0).rule();assertEquals(Kind.BAN,r.kind());assertEquals("Original reason",r.reason());assertEquals(4070908800000L,r.until());assertTrue(r.inherit());
    }
    @Test void vanillaSkipsExpiredMalformedAndWildcardIps() throws Exception {
        Files.writeString(temp.resolve("banned-ips.json"),"[{\"ip\":\"10.0.0.%\",\"expires\":\"forever\"},{\"ip\":\"10.0.0.1\",\"expires\":\"2000-01-01 00:00:00 +0000\"},{\"ip\":\"10.0.0.2\",\"expires\":\"forever\"}]");
        var p=BanTransfer.vanilla(temp,"Staff");assertEquals(1,p.rows().size());assertEquals(Kind.IP,p.rows().get(0).rule().kind());assertEquals(2,p.skipped().size());
    }
    @Test void litebansDoesNotTurnRecordedIpIntoIpBan() throws Exception {
        try(Connection c=DriverManager.getConnection("jdbc:sqlite::memory:")){schema(c);c.createStatement().execute("INSERT INTO litebans_bans VALUES (1,'"+a+"','10.0.0.1','Reason',0,1,0)");
            var p=BanTransfer.liteBans(c,"litebans_","snapshot","Staff");assertEquals(1,p.rows().size());assertEquals(Kind.BAN,p.rows().get(0).rule().kind());assertEquals("",p.rows().get(0).rule().selector());
        }
    }
    @Test void litebansSupportsExactIpBansAndSkipsInactiveExpiredAndWildcards() throws Exception {
        try(Connection c=DriverManager.getConnection("jdbc:sqlite::memory:")){schema(c);c.createStatement().execute("INSERT INTO litebans_bans VALUES (1,NULL,'10.0.0.1','Reason',0,1,1),(2,NULL,'10.0.0.%','Wildcard',0,1,1),(3,'"+a+"','10.0.0.1','Old',1,1,0),(4,'"+a+"','10.0.0.1','Removed',0,0,0)");
            var p=BanTransfer.liteBans(c,"litebans_","snapshot","Staff");assertEquals(1,p.rows().size());assertEquals(Kind.IP,p.rows().get(0).rule().kind());assertEquals(2,p.skipped().size());
        }
    }
    @Test void sqliteSnapshotIsOpenedReadOnly() throws Exception {
        Path path=temp.resolve("litebans.sqlite");try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+path)){schema(c);c.createStatement().execute("INSERT INTO litebans_bans VALUES (1,'"+a+"','10.0.0.1','Reason',0,1,0)");}
        String before=Crypto.sha(Files.readAllBytes(path));assertEquals(1,BanTransfer.liteBans(path,"litebans_","Staff").rows().size());assertEquals(before,Crypto.sha(Files.readAllBytes(path)));
    }
    @Test void h2SnapshotIsOpenedReadOnly() throws Exception {
        Path base=temp.resolve("litebans");try(Connection c=DriverManager.getConnection("jdbc:h2:file:"+base,"sa","")){schema(c);c.createStatement().execute("INSERT INTO litebans_bans VALUES (1,'"+a+"','10.0.0.1','Reason',0,TRUE,FALSE)");}
        Path path=temp.resolve("litebans.mv.db");String before=Crypto.sha(Files.readAllBytes(path));assertEquals(1,BanTransfer.liteBans(path,"litebans_","Staff").rows().size());assertEquals(before,Crypto.sha(Files.readAllBytes(path)));
    }
    @Test void prefixInjectionIsRejected() throws Exception {try(Connection c=DriverManager.getConnection("jdbc:sqlite::memory:")){assertThrows(IllegalArgumentException.class,()->BanTransfer.liteBans(c,"x; DROP TABLE profiles","test","Staff"));}}
    private void schema(Connection c) throws Exception {c.createStatement().execute("CREATE TABLE litebans_bans (id BIGINT,uuid VARCHAR(36),ip VARCHAR(45),reason VARCHAR(2048),until BIGINT,active BOOLEAN,ipban BOOLEAN)");}
}
