package io.github.robin9094707.simpleadminlite;

import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static io.github.robin9094707.simpleadminlite.Model.*;

/** A single serialized connection. All normal I/O runs on the suite's worker, never the tick thread. */
public final class Store implements AutoCloseable {
    private final Connection db;
    public Store(Path file) throws Exception {
        Files.createDirectories(file.toAbsolutePath().getParent());Class.forName("org.sqlite.JDBC");
        db=DriverManager.getConnection("jdbc:sqlite:"+file.toAbsolutePath());
        try(Statement s=db.createStatement()){
            s.execute("PRAGMA journal_mode=WAL");s.execute("PRAGMA synchronous=FULL");s.execute("PRAGMA busy_timeout=5000");
            s.execute("CREATE TABLE IF NOT EXISTS metadata (key TEXT PRIMARY KEY,value TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS profiles (uuid TEXT PRIMARY KEY,data BLOB NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS rules (id TEXT PRIMARY KEY,kind TEXT NOT NULL,target TEXT,selector TEXT NOT NULL,value TEXT NOT NULL,created INTEGER NOT NULL,until_at INTEGER NOT NULL,inherit_alts INTEGER NOT NULL,reason TEXT NOT NULL,actor TEXT NOT NULL,parent TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS links (a TEXT NOT NULL,b TEXT NOT NULL,kind TEXT NOT NULL,PRIMARY KEY(a,b))");
            s.execute("CREATE TABLE IF NOT EXISTS audit (id INTEGER PRIMARY KEY AUTOINCREMENT,time INTEGER NOT NULL,actor TEXT NOT NULL,action TEXT NOT NULL,detail TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS imports (source_key TEXT PRIMARY KEY,rule_id TEXT NOT NULL)");
            s.execute("INSERT OR IGNORE INTO metadata VALUES ('schema','1')");
        }
        if(!"1".equals(meta("schema")))throw new IOException("Unsupported database schema; no data was changed.");
    }
    public synchronized String meta(String key) throws SQLException {
        try(PreparedStatement s=db.prepareStatement("SELECT value FROM metadata WHERE key=?")){s.setString(1,key);try(ResultSet r=s.executeQuery()){return r.next()?r.getString(1):"";}}
    }
    public synchronized void meta(String key,String value) throws SQLException {
        try(PreparedStatement s=db.prepareStatement("INSERT OR REPLACE INTO metadata VALUES (?,?)")){s.setString(1,key);s.setString(2,value);s.executeUpdate();}
    }
    public synchronized List<Profile> profiles() throws Exception {
        List<Profile> out=new ArrayList<>();try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT data FROM profiles")){while(r.next())out.add(Model.profile(r.getBytes(1)));}return out;
    }
    public synchronized void save(Profile p) throws Exception {
        try(PreparedStatement s=db.prepareStatement("INSERT OR REPLACE INTO profiles VALUES (?,?)")){s.setString(1,p.id().toString());s.setBytes(2,profileBytes(p));s.executeUpdate();}
    }
    public synchronized List<Rule> rules() throws SQLException {
        List<Rule> out=new ArrayList<>();try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT * FROM rules ORDER BY created,id")){
            while(r.next())out.add(new Rule(r.getString("id"),Kind.valueOf(r.getString("kind")),r.getString("target")==null?null:UUID.fromString(r.getString("target")),r.getString("selector"),r.getString("value"),r.getLong("created"),r.getLong("until_at"),r.getBoolean("inherit_alts"),r.getString("reason"),r.getString("actor"),r.getString("parent")));
        }return out;
    }
    public synchronized Map<String,String> links() throws SQLException {
        Map<String,String> out=new HashMap<>();try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT * FROM links")){while(r.next())out.put(r.getString(1)+"|"+r.getString(2),r.getString(3));}return out;
    }
    public synchronized void link(UUID a,UUID b,String kind,String actor) throws Exception {
        transaction(()->{try(PreparedStatement s=db.prepareStatement("INSERT OR REPLACE INTO links VALUES (?,?,?)")){String[]p=pair(a,b).split("\\|");s.setString(1,p[0]);s.setString(2,p[1]);s.setString(3,kind);s.executeUpdate();}audit(actor,"ACCOUNT_LINK",a+" / "+b+" = "+kind);});
    }
    public static String pair(UUID a,UUID b){return a.compareTo(b)<0?a+"|"+b:b+"|"+a;}
    private void put(Rule r) throws SQLException {
        try(PreparedStatement s=db.prepareStatement("INSERT INTO rules VALUES (?,?,?,?,?,?,?,?,?,?,?)")){
            s.setString(1,r.id());s.setString(2,r.kind().name());s.setString(3,r.target()==null?null:r.target().toString());s.setString(4,r.selector());s.setString(5,r.value());s.setLong(6,r.created());s.setLong(7,r.until());s.setBoolean(8,r.inherit());s.setString(9,r.reason());s.setString(10,r.actor());s.setString(11,r.parent());s.executeUpdate();
        }
    }
    public synchronized void add(Rule r) throws Exception {transaction(()->{put(r);audit(r.actor(),"ADD_"+r.kind(),r.id()+" / "+r.target()+" / "+r.selector()+" / "+r.reason());});}
    public synchronized void revoke(String id,String actor) throws Exception {
        transaction(()->{try(PreparedStatement s=db.prepareStatement("DELETE FROM rules WHERE id=? OR parent=?")){s.setString(1,id);s.setString(2,id);s.executeUpdate();}audit(actor,"REVOKE",id);});
    }
    public synchronized int importRows(List<ImportRow> rows,String actor) throws Exception {
        int[] count={0};transaction(()->{for(ImportRow row:rows){
            try(PreparedStatement q=db.prepareStatement("SELECT 1 FROM imports WHERE source_key=?")){q.setString(1,row.key());try(ResultSet rs=q.executeQuery()){if(rs.next())continue;}}
            if(!row.rule().active(System.currentTimeMillis()))continue;
            put(row.rule());try(PreparedStatement s=db.prepareStatement("INSERT INTO imports VALUES (?,?)")){s.setString(1,row.key());s.setString(2,row.rule().id());s.executeUpdate();}count[0]++;
        }audit(actor,"IMPORT",count[0]+" selected bans imported");});return count[0];
    }
    public synchronized void audit(String actor,String action,String detail) throws SQLException {
        try(PreparedStatement s=db.prepareStatement("INSERT INTO audit(time,actor,action,detail) VALUES (?,?,?,?)")){s.setLong(1,System.currentTimeMillis());s.setString(2,clean(actor,100));s.setString(3,clean(action,100));s.setString(4,clean(detail,2048));s.executeUpdate();}
    }
    public synchronized List<String> audit(int limit) throws SQLException {
        List<String> out=new ArrayList<>();try(PreparedStatement s=db.prepareStatement("SELECT time,actor,action,detail FROM audit ORDER BY id DESC LIMIT ?")){s.setInt(1,Math.min(500,Math.max(1,limit)));try(ResultSet r=s.executeQuery()){while(r.next())out.add(when(r.getLong(1))+" | "+r.getString(2)+" | "+r.getString(3)+" | "+r.getString(4));}}return out;
    }
    private interface Work {void run() throws Exception;}
    private void transaction(Work work) throws Exception {
        db.setAutoCommit(false);try{work.run();db.commit();}catch(Exception e){db.rollback();throw e;}finally{db.setAutoCommit(true);}
    }
    @Override public synchronized void close() throws SQLException {db.close();}
}
