using Microsoft.Data.Sqlite;
using CardVault.Models;
using CardVault.Storage;

namespace CardVault.Checks;

internal static class StorageChecks
{
    public static void Main()
    {
        var root = Path.Combine(Path.GetTempPath(), "cardvault-check-" + Guid.NewGuid()); Directory.CreateDirectory(root);
        var count = 0;
        void Check(bool condition, string name) { if (!condition) throw new Exception("FAIL: " + name); count++; Console.WriteLine("PASS: " + name); }
        void Fails(Action action, string name)
        { bool failed = false; try { action(); } catch { failed = true; } Check(failed, name); }
        try
        {
            var db = Path.Combine(root,"cardvault.db"); var store = new CardStore(db);
            Fails(() => store.Load(), "missing database is not silently created"); Check(!File.Exists(db),"no file created on read");
            store.Initialize(); Fails(store.Initialize,"initialize refuses overwrite"); Check(store.Load().Count == 0,"empty collection");
            // Seed exactly the schema and raw image IDs written by the Kotlin/JDBC implementation.
            var png = Convert.FromBase64String("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aWQAAAABJRU5ErkJggg==");
            var hash = Convert.ToHexStringLower(System.Security.Cryptography.SHA256.HashData(png));
            using (var c = new SqliteConnection($"Data Source={db};Pooling=False"))
            {
                c.Open(); using var cmd = c.CreateCommand();
                cmd.CommandText = "INSERT INTO images VALUES ($id,$bytes); INSERT INTO cards VALUES ('legacy','旧卡片','1234','旧备注',$id,NULL,0)";
                cmd.Parameters.AddWithValue("$id",hash); cmd.Parameters.AddWithValue("$bytes",png); cmd.ExecuteNonQuery();
            }
            var legacy = store.Load(); Check(legacy[0].Front == CardStore.ImagePrefix + hash && store.ImageBytes(legacy[0].Front!).SequenceEqual(png),"JDBC image references and BLOB compatible");
            var path = Path.Combine(root,"sample.png"); File.WriteAllBytes(path,png);
            var result = store.Save([legacy[0],new("new","新卡","5678","备注",path,path)]);
            Check(result[1].Front == result[1].Back && result[1].Front == legacy[0].Front,"SHA-256 image deduplication");
            File.Delete(path); Check(store.ImageBytes(result[1].Front!).SequenceEqual(png),"images independent of source files");
            Fails(() => store.Save([result[0] with { Front = "missing.png" }]),"failed image import rolls back");
            Check(store.Load().Count == 2,"rollback preserves collection");
            var other = new CardStore(db); other.Load(); store.Save(result);
            Fails(() => other.Save([]),"stale revision rejected");
            for(var i=0;i<7;i++) store.Save(result);
            Check(Directory.GetFiles(Path.Combine(root,"backups"),"*.db").Length == 5,"five backup retention");
            using (var c = new SqliteConnection($"Data Source={db};Pooling=False"))
            { c.Open(); using var cmd = c.CreateCommand(); cmd.CommandText="PRAGMA integrity_check"; Check((string?)cmd.ExecuteScalar()=="ok","database integrity"); cmd.CommandText="PRAGMA foreign_key_check"; Check(cmd.ExecuteScalar()==null,"foreign key integrity"); }
            Check(!File.Exists(db+"-wal") && !File.Exists(db+"-journal"),"no persistent journal files");
            store.Save([]); using(var stream=File.Open(db, FileMode.Open,FileAccess.ReadWrite,FileShare.None)) Check(stream.Length>0,"connections released after save");
            Check(new Card("1","城市卡","1234 5678","通勤").Matches("34 56"),"whitespace-insensitive number search");
            Check(new Card("1", Number:"1").MaskedNumber=="••••  ","short number remains hidden");
            Check(CardStore.ResolvePath(null,false,Path.Combine(root,"CardVault.exe"),false)==db,"Windows portable database location");
            Check(CardStore.ResolvePath(null,false,Path.Combine(root,"CardVault.app","Contents","MacOS","CardVault"),true)==db,"macOS database beside app");
            Check(CardStore.ResolvePath(db,true)==db,"explicit database override");
            Console.WriteLine($"{count} storage checks passed.");
        }
        finally { Directory.Delete(root,true); }
    }
}
