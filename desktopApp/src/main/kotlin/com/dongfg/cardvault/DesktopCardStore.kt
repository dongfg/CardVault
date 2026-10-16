package com.dongfg.cardvault

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*
import java.util.Properties
import java.util.UUID

/** Local files only. The containing directory can be placed inside an unlocked vault. */
class DesktopCardStore(private val root: Path, private val owner: () -> Frame?) : CardStore {
    private val index = root.resolve("cards.properties")
    private val images = root.resolve("images")
    private val cache = object : LinkedHashMap<String, ImageBitmap>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 12
    }

    override fun newId(): String = UUID.randomUUID().toString()
    override fun copy(text: String) { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }

    override suspend fun load(): List<Card> = withContext(Dispatchers.IO) {
        if (!Files.exists(index)) return@withContext emptyList()
        val props = Properties().also { Files.newInputStream(index).use(it::load) }
        require(props.getProperty("version") == "1") { "Unsupported card data version" }
        val count = props.getProperty("count").toInt()
        require(count in 0..100000)
        val cards = (0 until count).map { n ->
            val prefix = "$n."
            fun get(key: String): String = requireNotNull(props.getProperty(prefix + key)) { "Missing card field" }
            fun picture(key: String): String? = get(key).takeIf(String::isNotBlank)?.let {
                val resolved = images.resolve(it).normalize()
                require(resolved.parent == images.normalize()) { "Invalid image path" }
                resolved.toString()
            }
            Card(get("id"), get("name"), get("number"), get("note"), picture("front"), picture("back"))
        }
        require(cards.map { it.id }.distinct().size == cards.size)
        cards
    }

    override suspend fun save(cards: List<Card>): List<Card> = withContext(Dispatchers.IO) {
        Files.createDirectories(images)
        val added = mutableListOf<Path>()
        fun importImage(value: String?): String? {
            if (value == null) return null
            val source = Path.of(value).toAbsolutePath().normalize()
            if (source.parent == images.toAbsolutePath().normalize()) return source.toString()
            val destination = images.resolve("${newId()}.${source.fileName.toString().substringAfterLast('.').lowercase()}")
            Files.copy(source, destination)
            added.add(destination)
            return destination.toString()
        }
        val temporary = root.resolve("cards-${newId()}.tmp")
        try {
            val managed = cards.map { it.copy(front = importImage(it.front), back = importImage(it.back)) }
            val props = Properties()
            props.setProperty("version", "1"); props.setProperty("count", managed.size.toString())
            managed.forEachIndexed { i, card ->
                mapOf("id" to card.id, "name" to card.name, "number" to card.number, "note" to card.note,
                    "front" to (card.front?.let { Path.of(it).fileName.toString() } ?: ""),
                    "back" to (card.back?.let { Path.of(it).fileName.toString() } ?: "")).forEach { (key, value) -> props.setProperty("$i.$key", value) }
            }
            Files.newOutputStream(temporary).use { props.store(it, "CardVault v1") }
            try { Files.move(temporary, index, ATOMIC_MOVE, REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary, index, REPLACE_EXISTING) }
            // Once committed, cleanup failure must not turn a successful save into a failure.
            runCatching {
                val retained = managed.flatMap { listOfNotNull(it.front, it.back) }.map { Path.of(it).fileName.toString() }.toSet()
                Files.list(images).use { stream -> stream.filter { it.fileName.toString() !in retained }.forEach { Files.deleteIfExists(it) } }
            }
            managed
        } catch (e: Exception) {
            added.forEach { runCatching { Files.deleteIfExists(it) } }
            throw e
        } finally { Files.deleteIfExists(temporary) }
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

    override suspend fun image(path: String): ImageBitmap = withContext(Dispatchers.IO) {
        synchronized(cache) { cache[path] } ?: run {
            val file = Path.of(path)
            require(Files.size(file) <= 30 * 1024 * 1024) { "图片过大，请选择 30 MB 以内的图片" }
            val source = Image.makeFromEncoded(Files.readAllBytes(file))
            require(source.width.toLong() * source.height <= 40_000_000) { "图片尺寸过大，请缩小后重试" }
            val bitmap = source.toComposeImageBitmap()
            synchronized(cache) { cache[path] = bitmap }
            bitmap
        }
    }
}
