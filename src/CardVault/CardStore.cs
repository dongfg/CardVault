using Microsoft.Data.Sqlite;
using System.Security.Cryptography;

namespace CardVault;

public sealed record Card(string Id, string Name = "", string Number = "", string Note = "", string? Front = null, string? Back = null)
{
    public bool Matches(string query) => Name.Contains(query.Trim(), StringComparison.OrdinalIgnoreCase)
        || Note.Contains(query.Trim(), StringComparison.OrdinalIgnoreCase)
        || Compact(Number).Contains(Compact(query), StringComparison.OrdinalIgnoreCase);
    private static string Compact(string text) => string.Concat(text.Where(c => !char.IsWhiteSpace(c)));
    public string MaskedNumber => Compact(Number) is var n && n.Length > 0 ? "••••  " + n[^Math.Min(4, n.Length - 1)..] : "";
}

public sealed class MissingDatabaseException(string location) : Exception("尚未找到卡册\n" + location);

public sealed class CardStore(string database)
{
    public const string ImagePrefix = "sqlite-image:";
    public string Database { get; } = Path.GetFullPath(database);
    private string? revision;
    private readonly object gate = new();

    public static string ResolvePath(string? pathOverride, bool demo, string? executable = null, bool? mac = null)
    {
        if (!string.IsNullOrWhiteSpace(pathOverride)) return Path.GetFullPath(pathOverride);
        if (demo) return Path.GetFullPath("build/avalonia-demo/cardvault.db");
        var binary = executable ?? Environment.ProcessPath ?? throw new InvalidOperationException("无法定位应用目录");
        if (mac ?? OperatingSystem.IsMacOS())
            for (var dir = new DirectoryInfo(Path.GetDirectoryName(binary)!); dir != null; dir = dir.Parent)
                if (dir.Name.EndsWith(".app", StringComparison.OrdinalIgnoreCase))
                    return Path.Combine(dir.Parent!.FullName, "cardvault.db");
        return Path.Combine(Path.GetDirectoryName(binary)!, "cardvault.db");
    }

    private SqliteConnection Connect(string? path = null, bool create = false)
    {
        path ??= Database;
        if (!create && !File.Exists(path)) throw new MissingDatabaseException(Database);
        var c = new SqliteConnection(new SqliteConnectionStringBuilder {
            DataSource = path, Mode = create ? SqliteOpenMode.ReadWriteCreate : SqliteOpenMode.ReadWrite,
            Pooling = false, ForeignKeys = true, DefaultTimeout = 5 }.ToString());
        try { c.Open(); Execute(c, "PRAGMA journal_mode=DELETE; PRAGMA synchronous=FULL;"); return c; }
        catch { c.Dispose(); throw; }
    }
    private static object? Scalar(SqliteConnection c, string sql, SqliteTransaction? tx = null, params object?[] values)
    { using var cmd = Command(c, sql, tx, values); return cmd.ExecuteScalar(); }
    private static SqliteCommand Command(SqliteConnection c, string sql, SqliteTransaction? tx, params object?[] values)
    {
        var cmd = c.CreateCommand(); cmd.Transaction = tx; cmd.CommandText = sql;
        for (var i = 0; i < values.Length; i++) cmd.Parameters.AddWithValue("$p" + i, values[i] ?? DBNull.Value);
        return cmd;
    }
    private static void Execute(SqliteConnection c, string sql, SqliteTransaction? tx = null, params object?[] values)
    { using var cmd = Command(c, sql, tx, values); cmd.ExecuteNonQuery(); }
    private static void Validate(SqliteConnection c)
    {
        if (Convert.ToInt64(Scalar(c, "PRAGMA application_id")) != 0x43564C54 || Convert.ToInt64(Scalar(c, "PRAGMA user_version")) != 1)
            throw new InvalidOperationException("数据库格式或版本不受支持，请检查 cardvault.db");
    }
    private static string Revision(SqliteConnection c, SqliteTransaction? tx = null) =>
        Scalar(c, "SELECT value FROM metadata WHERE key='revision'", tx) as string ?? throw new InvalidOperationException("数据库缺少版本信息");

    public void Initialize()
    {
        lock (gate)
        {
            if (File.Exists(Database)) throw new IOException("数据库已存在，请重新读取；不会覆盖已有卡册");
            Directory.CreateDirectory(Path.GetDirectoryName(Database)!);
            var temp = Path.Combine(Path.GetDirectoryName(Database)!, $".cardvault-{Guid.NewGuid()}.tmp");
            try
            {
                using (var c = Connect(temp, true))
                {
                    Execute(c, "PRAGMA application_id=1129729108; PRAGMA user_version=1;" +
                        "CREATE TABLE metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL);" +
                        "CREATE TABLE images (id TEXT PRIMARY KEY, data BLOB NOT NULL);" +
                        "CREATE TABLE cards (id TEXT PRIMARY KEY, name TEXT NOT NULL, number TEXT NOT NULL, note TEXT NOT NULL, front TEXT REFERENCES images(id), back TEXT REFERENCES images(id), position INTEGER NOT NULL UNIQUE);");
                    Execute(c, "INSERT INTO metadata VALUES ('revision', $p0)", null, Guid.NewGuid().ToString());
                }
                File.Move(temp, Database, false);
            }
            finally { File.Delete(temp); File.Delete(temp + "-journal"); }
        }
    }

    public List<Card> Load()
    {
        lock (gate)
        {
            using var c = Connect(); Validate(c);
            using var tx = c.BeginTransaction(deferred: true);
            var next = Revision(c, tx);
            using var cmd = Command(c, "SELECT id,name,number,note,front,back FROM cards ORDER BY position", tx);
            var result = new List<Card>();
            using (var r = cmd.ExecuteReader())
                while (r.Read()) result.Add(new(r.GetString(0), r.GetString(1), r.GetString(2), r.GetString(3),
                    r.IsDBNull(4) ? null : ImagePrefix + r.GetString(4), r.IsDBNull(5) ? null : ImagePrefix + r.GetString(5)));
            tx.Commit(); revision = next; return result;
        }
    }

    public List<Card> Save(IReadOnlyList<Card> cards)
    {
        lock (gate)
        {
            if (cards.Select(x => x.Id).Distinct().Count() != cards.Count) throw new InvalidOperationException("卡片 ID 重复");
            if (cards.Any(x => string.IsNullOrWhiteSpace(x.Name) || x.Name.Length > 40 || x.Number.Length > 80 || x.Note.Length > 2000))
                throw new InvalidOperationException("请检查名称、卡号和备注长度");
            using var c = Connect(); Validate(c);
            void Check(SqliteTransaction? tx = null)
            { if (revision == null || Revision(c, tx) != revision) throw new InvalidOperationException("卡册已被其他窗口或同步更新。请重新打开应用后再编辑，当前修改尚未保存"); }
            Check();
            var folder = Path.Combine(Path.GetDirectoryName(Database)!, "backups"); Directory.CreateDirectory(folder);
            Execute(c, "VACUUM INTO $p0", null, Path.Combine(folder, $"cardvault-{DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()}-{Guid.NewGuid()}.db"));
            using var tx = c.BeginTransaction(deferred: false);
            Check(tx);
            string? Import(string? value)
            {
                if (value == null) return null;
                if (value.StartsWith(ImagePrefix, StringComparison.Ordinal))
                {
                    if (Scalar(c, "SELECT 1 FROM images WHERE id=$p0", tx, value[ImagePrefix.Length..]) == null)
                        throw new IOException("卡片图片不存在，请重新导入");
                    return value;
                }
                var bytes = ReadFile(value); ImageFiles.Validate(bytes);
                var hash = Convert.ToHexStringLower(SHA256.HashData(bytes));
                Execute(c, "INSERT OR IGNORE INTO images VALUES ($p0,$p1)", tx, hash, bytes);
                return ImagePrefix + hash;
            }
            var managed = cards.Select(x => x with { Front = Import(x.Front), Back = Import(x.Back) }).ToList();
            Execute(c, "DELETE FROM cards", tx);
            for (var i = 0; i < managed.Count; i++)
            {
                var card = managed[i];
                Execute(c, "INSERT INTO cards VALUES ($p0,$p1,$p2,$p3,$p4,$p5,$p6)", tx,
                    card.Id, card.Name, card.Number, card.Note, card.Front?[ImagePrefix.Length..], card.Back?[ImagePrefix.Length..], i);
            }
            Execute(c, "DELETE FROM images WHERE id NOT IN (SELECT front FROM cards WHERE front IS NOT NULL UNION SELECT back FROM cards WHERE back IS NOT NULL)", tx);
            var next = Guid.NewGuid().ToString(); Execute(c, "UPDATE metadata SET value=$p0 WHERE key='revision'", tx, next);
            tx.Commit(); revision = next;
            try
            {
                foreach (var file in new DirectoryInfo(folder).GetFiles("cardvault-*.db")
                    .Where(f => System.Text.RegularExpressions.Regex.IsMatch(f.Name, @"^cardvault-[0-9]+-[0-9a-f-]{36}\.db$"))
                    .OrderByDescending(f => f.LastWriteTimeUtc).Skip(5)) file.Delete();
            }
            catch (IOException) { /* A successful save must not be reported as failed because pruning failed. */ }
            catch (UnauthorizedAccessException) { }
            return managed;
        }
    }
    public byte[] ImageBytes(string value)
    {
        lock (gate)
        {
            if (!value.StartsWith(ImagePrefix, StringComparison.Ordinal)) return ReadFile(value);
            using var c = Connect(); Validate(c);
            return Scalar(c, "SELECT data FROM images WHERE id=$p0", null, value[ImagePrefix.Length..]) as byte[] ?? throw new IOException("图片不存在");
        }
    }
    private static byte[] ReadFile(string path)
    {
        using var stream = File.OpenRead(path);
        if (stream.Length > 30 * 1024 * 1024) throw new IOException("图片过大，请选择 30 MB 以内的图片");
        using var output = new MemoryStream();
        var buffer = new byte[81920]; int read;
        while ((read = stream.Read(buffer)) > 0)
        { if (output.Length + read > 30 * 1024 * 1024) throw new IOException("图片过大"); output.Write(buffer, 0, read); }
        return output.ToArray();
    }
}
