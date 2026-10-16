package com.dongfg.cardvault

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.skia.Image
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteOpenMode
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.time.Instant
import java.util.UUID

/** Persisted image references are content hashes, independent of platform paths. */
class DesktopCardStore(
    database: Path,
    private val owner: () -> Frame?,
) : CardStore {
    val database = database.toAbsolutePath().normalize()
    private val mutex = Mutex()
    private var revision: String? = null
    private val cache = object : LinkedHashMap<String, ImageBitmap>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 12
    }
    override fun newId(): String = UUID.randomUUID().toString()
    override fun copy(text: String) { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }

    private fun connect(path: Path = database, create: Boolean = false): Connection {
        if (!create && !Files.isRegularFile(path)) throw MissingDatabaseException(database.toString())
        val config = SQLiteConfig().apply {
            setBusyTimeout(5000)
            enforceForeignKeys(true)
            setJournalMode(SQLiteConfig.JournalMode.DELETE)
            setSynchronous(SQLiteConfig.SynchronousMode.FULL)
            setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE)
            if (!create) resetOpenMode(SQLiteOpenMode.CREATE)
        }
        return config.createConnection("jdbc:sqlite:${path.toUri().toASCIIString()}")
    }
    private fun validate(c: Connection) {
        fun pragma(name: String) = c.createStatement().use { s -> s.executeQuery("PRAGMA $name").use { it.next(); it.getInt(1) } }
        if (pragma("application_id") != 0x43564C54 || pragma("user_version") != 1)
            throw CardStorageException("数据库格式或版本不受支持，请检查 cardvault.db")
    }
    private fun revision(c: Connection): String = c.createStatement().use { s ->
        s.executeQuery("SELECT value FROM metadata WHERE key='revision'").use {
            if (!it.next()) throw CardStorageException("数据库缺少版本信息")
            it.getString(1)
        }
    }
    override suspend fun load(): List<Card> = withContext(Dispatchers.IO) { mutex.withLock {
        connect().use { c ->
            validate(c)
            c.autoCommit = false
            val current = revision(c)
            val cards = c.createStatement().use { s -> s.executeQuery("SELECT * FROM cards ORDER BY position").use { r -> buildList {
                while (r.next()) add(Card(r.getString("id"), r.getString("name"), r.getString("number"), r.getString("note"),
                    r.getString("front")?.let { IMAGE_PREFIX + it }, r.getString("back")?.let { IMAGE_PREFIX + it }))
            } } }
            c.commit()
            revision = current
            synchronized(cache) { cache.clear() }
            cards
        }
    } }
    override suspend fun initialize() = withContext(Dispatchers.IO) { mutex.withLock {
        if (Files.exists(database)) throw CardStorageException("数据库已存在，请重试读取；不会覆盖已有卡册")
        Files.createDirectories(database.parent)
        val temporary = database.resolveSibling(".cardvault-${newId()}.tmp")
        try {
            connect(temporary, create = true).use { c ->
                c.createStatement().use { s ->
                    s.execute("PRAGMA application_id=1129729108")
                    s.execute("PRAGMA user_version=1")
                    s.execute("CREATE TABLE metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
                    s.execute("CREATE TABLE images (id TEXT PRIMARY KEY, data BLOB NOT NULL)")
                    s.execute("CREATE TABLE cards (id TEXT PRIMARY KEY, name TEXT NOT NULL, number TEXT NOT NULL, note TEXT NOT NULL, front TEXT REFERENCES images(id), back TEXT REFERENCES images(id), position INTEGER NOT NULL UNIQUE)")
                }
                c.autoCommit = false
                c.prepareStatement("INSERT INTO metadata VALUES ('revision', ?)").use { it.setString(1, newId()); it.executeUpdate() }
                c.commit()
            }
            // Never replace an existing or concurrently synced database.
            Files.move(temporary, database)
            Unit
        } finally {
            Files.deleteIfExists(temporary)
            Files.deleteIfExists(Path.of("$temporary-journal"))
        }
    } }
    override suspend fun save(cards: List<Card>): List<Card> = withContext(Dispatchers.IO) { mutex.withLock {
        connect().use { c ->
            validate(c)
            fun checkRevision() {
                if (revision == null || revision(c) != revision)
                    throw CardStorageException("卡册已被其他窗口或同步更新。请重新打开应用后再编辑，当前修改尚未保存")
            }
            checkRevision()
            val backup = backup(c)
            c.autoCommit = false
            try {
                checkRevision()
                val managed = writeCards(c, cards)
                val next = newId()
                c.prepareStatement("UPDATE metadata SET value=? WHERE key='revision'").use { it.setString(1, next); it.executeUpdate() }
                c.commit()
                revision = next
                runCatching { pruneBackups(backup.parent) }
                managed
            } catch (e: Exception) { c.rollback(); throw e }
        }
    } }
    private fun writeCards(c: Connection, cards: List<Card>): List<Card> {
        require(cards.map { it.id }.distinct().size == cards.size) { "卡片 ID 重复" }
        fun importImage(value: String?): String? {
            if (value == null) return null
            if (value.startsWith(IMAGE_PREFIX)) {
                val id = value.removePrefix(IMAGE_PREFIX)
                c.prepareStatement("SELECT 1 FROM images WHERE id=?").use { s ->
                    s.setString(1, id); s.executeQuery().use { require(it.next()) { "卡片图片不存在，请重新导入" } }
                }
                return id
            }
            val bytes = fileBytes(Path.of(value))
            decode(bytes)
            val id = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            c.prepareStatement("INSERT OR IGNORE INTO images VALUES (?, ?)").use { s ->
                s.setString(1, id); s.setBytes(2, bytes); s.executeUpdate()
            }
            return id
        }
        val managed = cards.map { card -> card.copy(front = importImage(card.front)?.let { IMAGE_PREFIX + it }, back = importImage(card.back)?.let { IMAGE_PREFIX + it }) }
        c.createStatement().use { it.executeUpdate("DELETE FROM cards") }
        c.prepareStatement("INSERT INTO cards VALUES (?, ?, ?, ?, ?, ?, ?)").use { s ->
            managed.forEachIndexed { i, card ->
                s.setString(1, card.id); s.setString(2, card.name); s.setString(3, card.number); s.setString(4, card.note)
                s.setString(5, card.front?.removePrefix(IMAGE_PREFIX)); s.setString(6, card.back?.removePrefix(IMAGE_PREFIX)); s.setInt(7, i)
                s.executeUpdate()
            }
        }
        c.createStatement().use { it.executeUpdate("DELETE FROM images WHERE id NOT IN (SELECT front FROM cards WHERE front IS NOT NULL UNION SELECT back FROM cards WHERE back IS NOT NULL)") }
        return managed
    }
    private fun backup(c: Connection): Path {
        val folder = database.parent.resolve("backups")
        Files.createDirectories(folder)
        val target = folder.resolve("cardvault-${Instant.now().toEpochMilli()}-${newId()}.db")
        c.prepareStatement("VACUUM INTO ?").use { it.setString(1, target.toString()); it.execute() }
        return target
    }
    private fun pruneBackups(folder: Path) {
        Files.list(folder).use { files -> files.filter { Files.isRegularFile(it) && it.fileName.toString().matches(Regex("cardvault-[0-9]+-[0-9a-f-]{36}\\.db")) }
            .sorted(compareByDescending<Path> { Files.getLastModifiedTime(it) }).skip(5).forEach { Files.deleteIfExists(it) } }
    }
    override suspend fun pickImage(): String? {
        val selected = withContext(Dispatchers.Swing) {
            val dialog = FileDialog(owner(), "选择卡片照片", FileDialog.LOAD)
            try {
                dialog.setFilenameFilter { _, name -> name.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "webp") }
                dialog.isVisible = true
                dialog.file?.let { Path.of(dialog.directory, it) }
            } finally { dialog.dispose() }
        } ?: return null
        require(selected.fileName.toString().substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "webp")) { "请选择 JPEG、PNG 或 WebP 图片" }
        image(selected.toString()) // Validate now, so an invalid selection never replaces a draft image.
        return selected.toString()
    }

    private fun fileBytes(file: Path): ByteArray {
        require(Files.size(file) <= 30 * 1024 * 1024) { "图片过大，请选择 30 MB 以内的图片" }
        return Files.readAllBytes(file).also { require(it.size <= 30 * 1024 * 1024) }
    }
    private fun decode(bytes: ByteArray): ImageBitmap {
        val source = Image.makeFromEncoded(bytes)
        require(source.width.toLong() * source.height <= 40_000_000) { "图片尺寸过大，请缩小后重试" }
        return source.toComposeImageBitmap()
    }
    override suspend fun image(path: String): ImageBitmap = withContext(Dispatchers.IO) {
        synchronized(cache) { cache[path] } ?: run {
            val bytes = if (path.startsWith(IMAGE_PREFIX)) mutex.withLock {
                connect().use { c ->
                    validate(c)
                    c.prepareStatement("SELECT data FROM images WHERE id=?").use { s ->
                        s.setString(1, path.removePrefix(IMAGE_PREFIX))
                        s.executeQuery().use { r -> check(r.next()) { "图片不存在" }; r.getBytes(1) }
                    }
                }
            } else fileBytes(Path.of(path))
            decode(bytes).also { bitmap -> synchronized(cache) { cache[path] = bitmap } }
        }
    }
    private companion object { const val IMAGE_PREFIX = "sqlite-image:" }
}
