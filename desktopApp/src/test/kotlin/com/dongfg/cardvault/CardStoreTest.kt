package com.dongfg.cardvault

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.test.*

class CardStoreTest {
    @Test fun packagedDataLivesBesideLauncherRatherThanWorkingDirectory() {
        val launcher = java.nio.file.Path.of("portable", "CardVault.exe").toAbsolutePath()
        assertEquals(launcher.parent.resolve("data"), resolveDataRoot(null, launcher.toString(), false))
        assertEquals(java.nio.file.Path.of("custom").toAbsolutePath(), resolveDataRoot("custom", launcher.toString(), false))
        assertEquals(java.nio.file.Path.of("build/demo-data").toAbsolutePath(), resolveDataRoot(null, launcher.toString(), true))
    }

    @Test fun searchAndMaskHandleRealCardFormats() {
        val card = Card("a", "城市交通卡", "AB 1234 5678", "日常通勤")
        assertTrue(card.matches("ab1234"))
        assertTrue(card.matches("通勤"))
        assertFalse(card.matches("不存在"))
        assertEquals("••••  5678", maskedNumber(card.number))
        assertEquals("••••  ", maskedNumber("7"))
        assertEquals("", maskedNumber(""))
    }

    @Test fun recordsAndImagesSurviveReloadAndFailedSave() = runBlocking {
        val temp = Files.createTempDirectory("cardvault-test-")
        try {
            val root = temp.resolve("vault")
            val source = temp.resolve("source.png")
            ImageIO.write(BufferedImage(10, 6, BufferedImage.TYPE_INT_RGB), "png", source.toFile())
            val store = DesktopCardStore(root) { null }
            assertTrue(store.load().isEmpty())
            val card = Card("a", "交通卡", "AB 1234", "第一行\n第二行", source.toString(), source.toString())
            val saved = store.save(listOf(card))
            assertNotEquals(source.toString(), saved.single().front)
            assertEquals(saved, DesktopCardStore(root) { null }.load())
            Files.delete(source)
            assertTrue(Files.exists(java.nio.file.Path.of(saved.single().front!!)))
            val edited = store.save(listOf(saved.single().copy(note = "已修改")))
            assertEquals("已修改", store.load().single().note)
            assertFailsWith<java.nio.file.NoSuchFileException> { store.save(listOf(Card("b", "坏图", front = temp.resolve("missing.png").toString()))) }
            assertEquals(edited, store.load())
            store.save(emptyList())
            assertTrue(store.load().isEmpty())
            Files.list(root.resolve("images")).use { assertEquals(0L, it.count()) }
        } finally {
            Files.walk(temp).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}
