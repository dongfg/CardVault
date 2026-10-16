package com.dongfg.cardvault

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.awt.image.BufferedImage
import java.util.Properties
import javax.imageio.ImageIO
import kotlin.test.*

class CardStoreTest {
    private fun store(db: Path) = DesktopCardStore(db) { null }
    private fun picture(path: Path, color: Int = 0): String {
        val image = BufferedImage(10, 6, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, color)
        ImageIO.write(image, "png", path.toFile())
        return path.toString()
    }
    private fun temporary(block: suspend (Path) -> Unit): Unit = runBlocking {
        val root = Files.createTempDirectory("cardvault-test-")
        try { block(root) }
        finally { Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
    private fun scalar(db: Path, query: String): String = DriverManager.getConnection("jdbc:sqlite:${db.toUri()}").use { c ->
        c.createStatement().use { it.executeQuery(query).use { r -> assertTrue(r.next()); r.getString(1) } }
    }
    @Test fun databasePathsAreIndependentOfWorkingDirectoryAndBundleInternals() {
        val directory = Path.of("同步 文件夹").toAbsolutePath()
        assertEquals(directory.resolve("cardvault.db"), resolveDatabasePath(null, directory.resolve("CardVault.exe").toString(), false, "Windows 11"))
        assertEquals(directory.resolve("cardvault.db"), resolveDatabasePath(null, directory.resolve("CardVault.app/Contents/MacOS/CardVault").toString(), false, "Mac OS X"))
        assertEquals(directory.resolve("cardvault.db"), resolveDatabasePath(null, directory.resolve("CardVault.app").toString(), false, "Mac OS X"))
        assertEquals(directory.resolve("custom.db"), resolveDatabasePath(directory.resolve("custom.db").toString(), null, false))
        assertEquals(directory.resolve("cardvault.db"), resolveDatabasePath(null, null, false, legacyDirectory = directory.toString()))
        assertEquals(Path.of("build/demo-data/cardvault.db").toAbsolutePath(), resolveDatabasePath(null, "ignored.exe", true))
        assertEquals(Path.of("cardvault.db").toAbsolutePath(), resolveDatabasePath(null, null, false))
    }
    @Test fun searchAndMaskHandleRealCardFormats() {
        val card = Card("a", "城市交通卡", "AB 1234 5678", "日常通勤")
        assertTrue(card.matches("ab1234")); assertTrue(card.matches("通勤")); assertFalse(card.matches("不存在"))
        assertEquals("••••  5678", maskedNumber(card.number)); assertEquals("••••  ", maskedNumber("7")); assertEquals("", maskedNumber(""))
    }
    @Test fun missingAndInvalidDatabasesNeverBecomeEmptyAlbumsSilently() = temporary { root ->
        val db = root.resolve("cardvault.db")
        val store = store(db)
        val error = assertFailsWith<MissingDatabaseException> { store.load() }
        assertEquals(db.toString(), error.location); assertFalse(error.hasLegacyData); assertFalse(Files.exists(db))
        assertFailsWith<MissingDatabaseException> { store.save(emptyList()) }
        store.initialize(); assertTrue(store.load().isEmpty())
        assertFailsWith<CardStorageException> { store.initialize() }
        Files.delete(db)
        assertFailsWith<MissingDatabaseException> { store.load() }
        assertFalse(Files.exists(db))
        store.initialize(); store.load()
        val original = Files.readAllBytes(db)
        assertFailsWith<CardStorageException> { store.initialize() }
        assertContentEquals(original, Files.readAllBytes(db))
        val invalid = root.resolve("invalid.db")
        Files.writeString(invalid, "not a SQLite database")
        assertFails { store(invalid).load() }; assertEquals("not a SQLite database", Files.readString(invalid))
        val unknown = root.resolve("unknown.db")
        DriverManager.getConnection("jdbc:sqlite:$unknown").use { it.createStatement().use { s -> s.execute("CREATE TABLE unrelated(id)") } }
        assertFailsWith<CardStorageException> { store(unknown).load() }
    }
    @Test fun blobsSurviveMovingOnlyTheDatabaseAndFailedSavesRollback() = temporary { root ->
        val db = root.resolve("源 卡册/cardvault.db")
        val source = picture(root.resolve("source.png"))
        val back = picture(root.resolve("back.png"), 0xff0000)
        val store = store(db); store.initialize(); store.load()
        val saved = store.save(listOf(Card("a", "交通卡 ' 中文", "AB 1234", "第一行\n第二行", source, back), Card("b", "会员卡", front = source)))
        assertTrue(saved.first().front!!.startsWith("sqlite-image:"))
        assertEquals(saved.first().front, saved.last().front)
        assertEquals("2", scalar(db, "SELECT count(*) FROM images"))
        assertEquals("blob", scalar(db, "SELECT typeof(data) FROM images LIMIT 1"))
        assertEquals("delete", scalar(db, "PRAGMA journal_mode"))
        val before = Files.readAllBytes(db)
        assertFails { store.save(listOf(Card("broken", front = root.resolve("missing.png").toString()))) }
        assertContentEquals(before, Files.readAllBytes(db))
        assertFails { store.save(listOf(saved.first(), saved.first())) }
        assertFails { store.save(listOf(Card("unknown", front = "sqlite-image:missing"))) }
        assertEquals(saved, store.load())
        Files.delete(Path.of(source)); Files.delete(Path.of(back))
        val relocated = root.resolve("另一台电脑/cardvault.db")
        Files.createDirectories(relocated.parent); Files.copy(db, relocated)
        val moved = store(relocated)
        assertEquals(saved, moved.load()); assertEquals(10, moved.image(saved.first().front!!).width)
        assertEquals(6, moved.image(saved.first().back!!).height)
        val edited = moved.save(listOf(saved.first().copy(note = "更新", back = null)))
        assertEquals("更新", moved.load().single().note)
        assertEquals("1", scalar(relocated, "SELECT count(*) FROM images"))
        assertNull(edited.single().back)
        val replacement = picture(root.resolve("replacement.png"), 0x00ff00)
        val replaced = moved.save(listOf(edited.single().copy(front = replacement)))
        assertNotEquals(edited.single().front, replaced.single().front)
        assertEquals("1", scalar(relocated, "SELECT count(*) FROM images"))
        moved.save(emptyList()); assertTrue(moved.load().isEmpty())
        assertEquals("0", scalar(relocated, "SELECT count(*) FROM images"))
        assertFalse(Files.exists(Path.of("$relocated-wal"))); assertFalse(Files.exists(Path.of("$relocated-shm")))
        assertFalse(Files.exists(Path.of("$relocated-journal")))
    }
    @Test fun staleWindowCannotOverwriteNewerData() = temporary { root ->
        val db = root.resolve("cardvault.db")
        val first = store(db); first.initialize(); first.load()
        val second = store(db); second.load()
        val saved = first.save(listOf(Card("a", "更新")))
        assertFailsWith<CardStorageException> { second.save(listOf(Card("b", "过期窗口"))) }
        assertEquals(saved, second.load())
        second.save(listOf(Card("b", "重新读取后可保存")))
        assertFailsWith<CardStorageException> { first.save(emptyList()) }
    }
    @Test fun backupsContainPreviousCommittedAlbumAndAreBounded() = temporary { root ->
        val db = root.resolve("cardvault.db")
        val store = store(db); store.initialize(); store.load()
        val image = picture(root.resolve("source.png"))
        val first = store.save(listOf(Card("a", "初版", front = image)))
        store.save(listOf(first.single().copy(note = "二版")))
        val backups = Files.list(root.resolve("backups")).use { it.toList() }
        assertEquals(2, backups.size)
        val nonemptyBackup = backups.single { store(it).load().isNotEmpty() }
        val backupStore = store(nonemptyBackup)
        assertEquals(first, backupStore.load())
        assertEquals(10, backupStore.image(first.single().front!!).width)
        repeat(7) { store.save(listOf(first.single().copy(note = "$it"))) }
        Files.list(root.resolve("backups")).use { assertEquals(5L, it.count()) }
    }
    private fun legacy(root: Path, front: String) {
        Files.createDirectories(root.resolve("images"))
        val props = Properties()
        mapOf("version" to "1", "count" to "1", "0.id" to "old", "0.name" to "旧卡册", "0.number" to "1234", "0.note" to "备注\n第二行", "0.front" to front, "0.back" to "").forEach { (k,v) -> props.setProperty(k,v) }
        Files.newOutputStream(root.resolve("cards.properties")).use { props.store(it, "legacy") }
    }
    @Test fun legacyImportKeepsOriginalFilesAndEmbedsImages() = temporary { root ->
        val old = root.resolve("data"); legacy(old, "front.png")
        val image = picture(old.resolve("images/front.png"))
        val db = root.resolve("cardvault.db"); val store = store(db)
        assertTrue(assertFailsWith<MissingDatabaseException> { store.load() }.hasLegacyData)
        store.initialize()
        val card = store.load().single(); assertEquals("旧卡册", card.name); assertEquals("备注\n第二行", card.note)
        assertTrue(Files.exists(old.resolve("cards.properties"))); assertTrue(Files.exists(Path.of(image)))
        Files.delete(Path.of(image)); assertEquals(10, store.image(card.front!!).width)
    }
    @Test fun brokenLegacyImportLeavesNoPartialDatabase() = temporary { root ->
        val old = root.resolve("data"); legacy(old, "missing.png")
        val db = root.resolve("cardvault.db")
        assertFails { store(db).initialize() }; assertFalse(Files.exists(db))
        assertTrue(Files.exists(old.resolve("cards.properties")))
        legacy(old, "../outside.png")
        assertFails { store(db).initialize() }; assertFalse(Files.exists(db))
        Files.list(root).use { assertFalse(it.anyMatch { p -> p.fileName.toString().startsWith(".cardvault-") }) }
    }
    @Test fun demoSeedsEmbeddedPicturesOnceAndKeepsEdits() = temporary { root ->
        val db = root.resolve("demo/cardvault.db")
        prepareDemo(db)
        val store = store(db)
        val cards = store.load()
        assertEquals(3, cards.size)
        assertEquals(4, scalar(db, "SELECT count(*) FROM images").toInt())
        assertEquals(960, store.image(cards.first().front!!).width)
        store.save(emptyList())
        prepareDemo(db)
        assertTrue(store.load().isEmpty())
        Files.list(db.parent).use { files -> assertFalse(files.anyMatch { it.toString().endsWith(".png") }) }
    }
}
