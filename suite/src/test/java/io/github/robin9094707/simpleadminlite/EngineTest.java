package io.github.robin9094707.simpleadminlite;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.robin9094707.simpleadminlite.Model.*;

class EngineTest {
    @TempDir Path temp;Store store;Engine engine;long now=System.currentTimeMillis();
    UUID a=UUID.fromString("00000000-0000-0000-0000-000000000001"),b=UUID.fromString("00000000-0000-0000-0000-000000000002"),c=UUID.fromString("00000000-0000-0000-0000-000000000003");
    @BeforeEach void open() throws Exception {store=new Store(temp.resolve("state.sqlite"));engine=new Engine(store);}
    @AfterEach void close() throws Exception {store.close();}
    void observe(UUID id,String ip,String...tokens)throws Exception {engine.observe(id,"Player"+id.toString().substring(35),"JAVA",ip,Map.of("brand","fabric","channels","same:shared"),List.of(tokens),now,8);}
    @Test void unrelatedJavaClientsDoNotLink() throws Exception {observe(a,"10.0.0.1");observe(b,"10.0.0.2");assertEquals(Set.of(a),engine.component(a));assertTrue(engine.matches(a).isEmpty());}
    @Test void sharedIpIsOnlyAHint() throws Exception {observe(a,"10.0.0.1");observe(b,"10.0.0.1");assertEquals(Set.of(a),engine.component(a));assertFalse(engine.matches(a).get(0).strong());}
    @Test void selfSignedIdIsOnlyAHint() throws Exception {observe(a,"10.0.0.1","self:shared");observe(b,"10.0.0.2","self:shared");assertFalse(engine.matches(a).get(0).strong());}
    @Test void sharedDeviceCreatesTransitiveGroup() throws Exception {observe(a,"10.0.0.1","device:one");observe(b,"10.0.0.2","device:one","device:two");observe(c,"10.0.0.3","device:two");assertEquals(Set.of(a,b,c),engine.component(a));assertEquals(engine.component(a),engine.component(c));}
    @Test void authenticatedXuidLinksAccounts() throws Exception {observe(a,"10.0.0.1","xuid:one");observe(b,"10.0.0.2","xuid:one");assertEquals(Set.of(a,b),engine.component(a));}
    @Test void explicitIgnorePreventsIndirectInheritanceAndGroupsStaySymmetric() throws Exception {
        observe(a,"10.0.0.1","device:one");observe(b,"10.0.0.2","device:one","device:two");observe(c,"10.0.0.3","device:two");engine.link(a,c,"ignored","Admin");
        assertFalse(engine.component(a).contains(c));for(UUID id:List.of(a,b,c))for(UUID member:engine.component(id))assertEquals(engine.component(id),engine.component(member));
    }
    @Test void staffCanConfirmAndRevertAnIpHint() throws Exception {observe(a,"10.0.0.1");observe(b,"10.0.0.1");engine.link(a,b,"confirmed","Admin");assertEquals(Set.of(a,b),engine.component(a));engine.link(a,b,"automatic","Admin");assertEquals(Set.of(a),engine.component(a));}
    @Test void aRealBanDeniesTheAccountImmediately() throws Exception {Rule r=Rule.make(Kind.BAN,a,"","account",0,true,"Reason","Staff");engine.add(r);assertEquals(r,engine.denial(a,"10.0.0.1",Set.of(),now));assertNull(engine.denial(b,"10.0.0.1",Set.of(),now));}
    @Test void expiredBansDoNotDeny() throws Exception {engine.add(Rule.make(Kind.BAN,a,"","account",now-1,true,"Reason","Staff"));assertNull(engine.denial(a,"10.0.0.1",Set.of(),now));}
    @Test void inheritedBanAppliesToNewlyObservedAccount() throws Exception {observe(a,"10.0.0.1","device:one");Rule r=Rule.make(Kind.BAN,a,"","account",0,true,"Reason","Staff");engine.add(r);observe(b,"10.0.0.2","device:one");assertEquals(r,engine.denial(b,"10.0.0.2",Set.of("device:one"),now));}
    @Test void inheritanceCanBeDisabled() throws Exception {observe(a,"10.0.0.1","device:one");observe(b,"10.0.0.2","device:one");engine.add(Rule.make(Kind.BAN,a,"","account",0,false,"Reason","Staff"));assertNull(engine.denial(b,"10.0.0.2",Set.of("device:one"),now));}
    @Test void ultraBanCatchesAnUnregisteredDeviceSession() throws Exception {observe(a,"10.0.0.1","device:one");Rule r=Rule.make(Kind.BAN,a,"","ultra",0,false,"Reason","Staff");engine.add(r);assertEquals(r,engine.denial(c,"10.0.0.3",Set.of("device:one"),now));}
    @Test void deviceBlockChecksCurrentDeviceNotJustOldHistory() throws Exception {observe(a,"10.0.0.1","device:old");engine.add(Rule.make(Kind.DEVICE,a,"device:old","",0,false,"Reason","Staff"));assertNull(engine.denial(a,"10.0.0.1",Set.of("device:new"),now));assertNotNull(engine.denial(a,"10.0.0.1",Set.of("device:old"),now));}
    @Test void activeIpBanLearnsADeviceAndRevocationRemovesDerivedBlocks() throws Exception {
        Rule r=Rule.make(Kind.IP,null,"10.0.0.1","",now+60000,false,"Reason","Staff");engine.add(r);assertTrue(engine.learnDeviceBan(r,a,"device:one",now));assertFalse(engine.learnDeviceBan(r,a,"device:one",now));
        Rule derived=engine.denial(b,"10.0.0.2",Set.of("device:one"),now);assertNotNull(derived);assertEquals(r.until(),derived.until());assertEquals(r.id(),derived.parent());engine.revoke(r.id(),"Staff");assertNull(engine.denial(b,"10.0.0.2",Set.of("device:one"),now));
    }
    @Test void revokedIpBanCannotLearnAnOrphanedDevice() throws Exception {Rule r=Rule.make(Kind.IP,null,"10.0.0.1","",0,false,"Reason","Staff");engine.add(r);engine.revoke(r.id(),"Staff");assertFalse(engine.learnDeviceBan(r,a,"device:one",now));}
    @Test void permissionDenialExpiresAndInheritedSourceCanBeRevoked() throws Exception {
        observe(a,"10.0.0.1","device:one");observe(b,"10.0.0.2","device:one");Rule r=Rule.make(Kind.PERMISSION,a,"test.fly","false",now+60000,true,"Reason","Staff");engine.add(r);
        assertTrue(engine.effective(b,now).contains(r));assertTrue(engine.effective(b,now+60001).isEmpty());engine.revoke(r.id(),"Staff");assertTrue(engine.effective(b,now).isEmpty());
    }
    @Test void rolesDoNotInheritByDefault() throws Exception {observe(a,"10.0.0.1","device:one");observe(b,"10.0.0.2","device:one");engine.add(Rule.make(Kind.ROLE,a,"administrator","",0,false,"Reason","Staff"));assertTrue(engine.effective(b,now).isEmpty());}
    @Test void profilesRulesLinksAndAuditSurviveRestart() throws Exception {observe(a,"10.0.0.1","device:one");observe(b,"10.0.0.2");engine.link(a,b,"confirmed","Staff");Rule r=Rule.make(Kind.BAN,a,"","account",0,true,"Reason","Staff");engine.add(r);Engine fresh=new Engine(store);assertEquals(Set.of(a,b),fresh.component(a));assertEquals(r,fresh.denial(b,"10.0.0.2",Set.of(),now));assertFalse(store.audit(10).isEmpty());}
    @Test void historiesAreBoundedPerType() throws Exception {for(int i=1;i<=20;i++)engine.observe(a,"PlayerA","BEDROCK","10.0.0."+i,Map.of(),List.of("device:"+i,"xuid:"+i),now+i,8);Profile p=engine.profile(a);assertEquals(8,p.tokens("ip").size());assertEquals(8,p.tokens("device").size());assertEquals(8,p.tokens("xuid").size());}
    @Test void importIsSelectedAndIdempotent() throws Exception {ImportRow one=new ImportRow("source:1",Rule.make(Kind.BAN,a,"","account",0,true,"One","Staff"),"A","");ImportRow two=new ImportRow("source:2",Rule.make(Kind.BAN,b,"","account",0,true,"Two","Staff"),"B","");assertEquals(1,engine.importRows(List.of(one),"Staff"));assertNull(engine.denial(b,"",Set.of(),now));assertEquals(0,engine.importRows(List.of(one),"Staff"));assertEquals(1,engine.importRows(List.of(two),"Staff"));}
    @Test void failedBatchRollsBackAllBanRowsAndImportMarkers() throws Exception {Rule r=Rule.make(Kind.BAN,a,"","account",0,true,"Reason","Staff");ImportRow one=new ImportRow("one",r,"A","");ImportRow duplicateId=new ImportRow("two",r,"B","");assertThrows(Exception.class,()->engine.importRows(List.of(one,duplicateId),"Staff"));assertTrue(store.rules().isEmpty());assertEquals(1,engine.importRows(List.of(one),"Staff"));}
    @Test void ordinaryClientRefreshDoesNotInvalidateExportRevision() throws Exception {observe(a,"10.0.0.1");long revision=engine.revision();engine.observe(a,"Player1","JAVA","10.0.0.1",Map.of("ping","50"),List.of(),now+10,8);assertEquals(revision,engine.revision());}
    @Test void identifiersAreKeyedAndDomainSeparated() {String key=Crypto.secret();assertNotEquals(Crypto.token("device","same",key),Crypto.token("xuid","same",key));assertNotEquals(Crypto.token("device","same",key),Crypto.token("device","same",Crypto.secret()));}
    @Test void hmacRejectsTamperingAndMissingSignature(){byte[] b="hello".getBytes();String key=Crypto.secret();assertTrue(Crypto.valid(b,key,Crypto.hmac(b,key)));assertFalse(Crypto.valid("changed".getBytes(),key,Crypto.hmac(b,key)));assertFalse(Crypto.valid(b,key,null));}
    @Test void durationAndIpValidationRejectAmbiguousInputs(){assertEquals(0,expiry("permanent",now));assertEquals(now+3600000,expiry("1h",now));assertThrows(IllegalArgumentException.class,()->expiry("-1h",now));assertThrows(IllegalArgumentException.class,()->ip("localhost"));assertThrows(IllegalArgumentException.class,()->ip("10.0.0.%"));assertThrows(IllegalArgumentException.class,()->ip("127.1"));assertEquals("127.0.0.1",ip("127.0.0.1"));}
}
