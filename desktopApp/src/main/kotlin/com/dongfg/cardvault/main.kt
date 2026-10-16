package com.dongfg.cardvault

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.*
import java.awt.Dimension
import java.nio.file.Path

fun main(args: Array<String>) {
    val dataRoot = resolveDataRoot(System.getProperty("cardvault.dataDir") ?: System.getenv("CARDVAULT_DATA_DIR"), System.getProperty("jpackage.app-path"), "--demo" in args)
    if ("--demo" in args) prepareDemo(dataRoot)
    application {
    val state = rememberWindowState(width = 440.dp, height = 860.dp, position = WindowPosition(Alignment.Center))
    val close = remember { CloseController() }
    Window(onCloseRequest = { close.request() }, title = "CardVault", state = state, undecorated = true) {
        window.minimumSize = Dimension(360, 620)
        val store = remember { DesktopCardStore(dataRoot) { window } }
        VaultTheme {
            Column(Modifier.fillMaxSize().background(VaultBackground).border(1.dp, VaultBorder)) {
                Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                    WindowDraggableArea(Modifier.weight(1f).fillMaxHeight()) {
                        Box(Modifier.fillMaxSize().padding(start = 20.dp), contentAlignment = Alignment.CenterStart) {
                            Text("CARDVAULT", color = VaultMuted, fontSize = 10.sp, letterSpacing = 2.sp)
                        }
                    }
                    IconButton(onClick = { state.isMinimized = true }, modifier = Modifier.size(48.dp)) { Glyph("min", "最小化") }
                    IconButton(onClick = { state.placement = if (state.placement == WindowPlacement.Maximized) WindowPlacement.Floating else WindowPlacement.Maximized }, modifier = Modifier.size(48.dp)) { Glyph("max", "最大化或还原") }
                    IconButton(onClick = { close.request() }, modifier = Modifier.size(48.dp)) { Glyph("close", "关闭") }
                }
                Box(Modifier.weight(1f)) { App(store, close, ::exitApplication) }
            }
        }
    }
}
}

internal fun resolveDataRoot(
    override: String?, executable: String?, demo: Boolean,
    osName: String = System.getProperty("os.name"),
    userHome: String = System.getProperty("user.home"),
): Path = when {
    override != null -> Path.of(override)
    demo -> Path.of("build/demo-data")
    executable != null && osName.startsWith("Mac", ignoreCase = true) ->
        Path.of(userHome, "Library", "Application Support", "CardVault", "data")
    executable != null -> Path.of(executable).parent.resolve("data")
    else -> Path.of("data")
}.toAbsolutePath().normalize()
