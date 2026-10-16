package com.dongfg.cardvault

import kotlinx.coroutines.runBlocking
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/** Explicit demo mode; never seeds or reads the user's normal card directory. */
fun prepareDemo(root: Path) = runBlocking {
    val store = DesktopCardStore(root) { null }
    if (Files.exists(root.resolve("cards.properties"))) return@runBlocking
    Files.createDirectories(root)
    fun picture(file: String, theme: Int, back: Boolean = false): String {
        val image = BufferedImage(960, 604, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val base = listOf(Color(0x174E46), Color(0xD9CBB4), Color(0x344966))[theme]
        val ink = if (theme == 1) Color(0x493F32) else Color(0xF0EFE4)
        g.color = base; g.fillRect(0, 0, 960, 604)
        g.color = Color(255,255,255,18)
        g.fillOval(460, -150, 700, 700); g.fillOval(580, -50, 500, 500)
        g.stroke = BasicStroke(2f)
        g.color = Color(255,255,255,35)
        for (i in 0..7) g.drawArc(430-i*24, 160-i*24, 540+i*48, 540+i*48, 10, 220)
        g.color = ink
        g.font = Font("SansSerif", Font.BOLD, 20)
        g.drawString("C A R D V A U L T   /   S A M P L E", 60, 70)
        if (back) {
            g.color = Color(0x172F2B); g.fillRect(0, 140, 960, 110)
            g.color = ink; g.font = Font("SansSerif", Font.PLAIN, 23)
            g.drawString("DEMO CARD  /  NOT VALID FOR USE", 60, 335)
            for (i in 0..65) g.fillRect(60+i*5, 390, if (i%3 == 0) 3 else 1, 65)
            g.drawString("Card details and notes stay together.", 60, 535)
        } else {
            g.font = Font("SansSerif", Font.BOLD, 58)
            g.drawString(listOf("URBAN PASS", "THE READING CLUB", "EVERYDAY")[theme], 60, 280)
            g.font = Font("SansSerif", Font.PLAIN, 21)
            g.drawString(listOf("A little closer to the city.", "A world between the pages.", "Small moments. Beautiful days.")[theme], 63, 325)
            g.font = Font("Monospaced", Font.PLAIN, 25)
            g.drawString(listOf("DEMO  0000  4826", "DEMO  0000  1650", "DEMO  0000  2098")[theme], 60, 525)
            g.font = Font("SansSerif", Font.PLAIN, 16); g.drawString("SAMPLE", 810, 535)
        }
        g.dispose()
        val path = root.resolve(file)
        ImageIO.write(image, "png", path.toFile())
        return path.toString()
    }
    val a = picture("transit-front.png", 0)
    val b = picture("transit-back.png", 0, true)
    val c = picture("library-front.png", 1)
    val d = picture("member-front.png", 2)
    store.save(listOf(
        Card("demo-transit", "城市交通卡", "DEMO 0000 4826", "日常通勤，让每次出发都从容一点。", a, b),
        Card("demo-library", "图书馆借阅卡", "DEMO 0000 1650", "周末的阅读时光", c),
        Card("demo-member", "日常会员卡", "DEMO 0000 2098", "收好生活里的小小便利", d),
    ))
    listOf(a,b,c,d).forEach { Files.deleteIfExists(Path.of(it)) }
}
