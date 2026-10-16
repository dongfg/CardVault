package com.dongfg.cardvault

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

val VaultBackground = Color(0xFFF7F8F5)
val VaultInk = Color(0xFF182B29)
val VaultMuted = Color(0xFF5E716D)
val VaultGreen = Color(0xFF176B5B)
val VaultPale = Color(0xFFE8F2EE)
val VaultBorder = Color(0xFFDFE6E1)
private val Danger = Color(0xFFB43D3D)
private val Shape = RoundedCornerShape(32.dp)

@Composable
fun VaultTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(primary = VaultGreen, onPrimary = Color.White,
        primaryContainer = VaultPale, background = VaultBackground, surface = Color.White,
        onSurface = VaultInk, onSurfaceVariant = VaultMuted, outline = VaultBorder, error = Danger), content = content)
}

@Composable
fun App(store: CardStore, closeController: CloseController, closeWindow: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var cards by remember { mutableStateOf<List<Card>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf(false) }
    var missingDatabase by remember { mutableStateOf<MissingDatabaseException?>(null) }
    var readMessage by remember { mutableStateOf("") }
    var page by remember { mutableStateOf("home") }
    var selected by remember { mutableStateOf<Card?>(null) }
    var original by remember { mutableStateOf<Card?>(null) }
    var draft by remember { mutableStateOf<Card?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pendingLeave by remember { mutableStateOf<(() -> Unit)?>(null) }
    var deleteDialog by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val dirty = draft != null && draft != original
    fun message(text: String) { scope.launch { snackbar.showSnackbar(text) } }
    fun leave(action: () -> Unit) { if (!busy) { if (page == "edit" && dirty) pendingLeave = action else action() } }
    fun load() { scope.launch {
        loading = true; loadError = false; missingDatabase = null
        try { cards = store.load() }
        catch (e: MissingDatabaseException) { missingDatabase = e; loadError = true }
        catch (e: Exception) { readMessage = (e as? CardStorageException)?.message ?: "请检查数据库是否已同步到本机，以及所在目录是否可写"; loadError = true }
        loading = false
    } }
    fun edit(card: Card?) { val value = card ?: Card(store.newId()); original = value; draft = value; page = "edit" }
    SideEffect { closeController.request = { leave(closeWindow) } }
    LaunchedEffect(Unit) { load() }
    VaultTheme {
        Box(Modifier.fillMaxSize().background(VaultBackground)) {
            Column(Modifier.widthIn(max = 480.dp).fillMaxSize().align(Alignment.TopCenter)) {
                when (page) {
                    "home" -> {
                        Column(Modifier.padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                VaultMark(); Spacer(Modifier.width(10.dp))
                                Text("CardVault", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp)
                            }
                            Spacer(Modifier.height(16.dp))
                            OutlinedTextField(value = query, onValueChange = { query = it },
                                placeholder = { Text("搜索名称、卡号或备注", fontSize = 14.sp) },
                                leadingIcon = { Glyph("search", "搜索") },
                                trailingIcon = if (query.isNotEmpty()) ({ IconButton(onClick = { query = "" }) { Glyph("close", "清空搜索") } }) else null,
                                singleLine = true, shape = RoundedCornerShape(14.dp),
                                colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = VaultBorder, unfocusedContainerColor = Color.White), modifier = Modifier.fillMaxWidth().height(52.dp))
                        }
                        Box(Modifier.fillMaxSize()) {
                            val filtered = cards.filter { it.matches(query) }
                            when {
                                loading -> Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    repeat(2) { Box(Modifier.fillMaxWidth().height(290.dp).clip(Shape).background(VaultBorder.copy(alpha = .45f))) }
                                }
                                missingDatabase != null -> {
                                    val missing = missingDatabase!!
                                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Box(Modifier.weight(1f)) {
                                            EmptyState(if (missing.hasLegacyData) "发现旧版卡册" else "尚未找到卡册",
                                                "${missing.location}\n\n已有卡册请等待同步完成后重试。" + if (missing.hasLegacyData) "\n导入会保留旧版文件。" else "首次使用可创建新卡册。",
                                                if (busy) "处理中…" else if (missing.hasLegacyData) "导入旧卡册" else "创建新卡册") {
                                                if (!busy) scope.launch {
                                                    busy = true
                                                    try { store.initialize(); load() }
                                                    catch (e: Exception) { message(e.message ?: "无法创建卡册，请检查目录权限") }
                                                    busy = false
                                                }
                                            }
                                        }
                                        TextButton(enabled = !busy, onClick = ::load, modifier = Modifier.padding(bottom = 24.dp)) { Text("重新读取") }
                                    }
                                }
                                loadError -> EmptyState("暂时无法读取卡片", readMessage, "重试", ::load)
                                cards.isEmpty() -> EmptyState("把常用卡片收进来", "保存正反面、卡号和备注，随时查找")
                                filtered.isEmpty() -> EmptyState("没有找到相关卡片", "换个关键词试试", "清空搜索", { query = "" })
                                else -> LazyColumn(state = listState, contentPadding = PaddingValues(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                                    items(filtered, key = { it.id }) { card -> ListCard(card, store) { selected = card; page = "detail" } }
                                }
                            }
                            if (!loading && !loadError) FloatingActionButton(onClick = { edit(null) }, containerColor = VaultGreen, contentColor = Color.White,
                                shape = CircleShape, modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp).size(56.dp)) { Glyph("plus", "添加卡片", Color.White) }
                        }
                    }
                    "detail" -> selected?.let { card ->
                        PageBar("", "返回", { page = "home" }, "编辑", { edit(card) })
                        DetailPage(card, store, { preview = it }) {
                            try { store.copy(card.number); message("卡号已复制") } catch (_: Exception) { message("复制失败，请重试") }
                        }
                    }
                    "edit" -> draft?.let { card ->
                        val isNew = cards.none { it.id == card.id }
                        val canSave = card.name.trim().isNotEmpty() && card.name.length <= 40 && card.number.length <= 80 && card.note.length <= 2000 && dirty && !busy
                        PageBar(if (isNew) "添加卡片" else "编辑卡片", "取消", {
                            leave { draft = null; page = if (selected == null || isNew) "home" else "detail" }
                        }, if (busy) "处理中…" else "保存", {
                            if (canSave) scope.launch {
                                busy = true
                                try {
                                    val value = card.copy(name = card.name.trim(), number = card.number.trim())
                                    cards = store.save(if (isNew) listOf(value) + cards else cards.map { if (it.id == value.id) value else it })
                                    selected = cards.first { it.id == value.id }; draft = null; page = "detail"
                                    message(if (isNew) "卡片已添加" else "修改已保存")
                                } catch (e: Exception) { message((e as? CardStorageException)?.message ?: "保存失败，请检查数据库目录权限和可用空间") }
                                busy = false
                            }
                        }, canSave)
                        EditPage(card, store, busy, !isNew, { draft = it }, { front -> scope.launch {
                            busy = true
                            try { store.pickImage()?.let { path -> draft = if (front) draft?.copy(front = path) else draft?.copy(back = path) } }
                            catch (e: Exception) { message(e.message ?: "图片导入失败，请重试") }
                            busy = false
                        } }, { preview = it }, { deleteDialog = true })
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp).widthIn(max = 440.dp))
        }
        if (pendingLeave != null) AlertDialog(onDismissRequest = { pendingLeave = null }, title = { Text("放弃未保存的修改？") }, text = { Text("当前修改还没有保存。") },
            confirmButton = { TextButton(onClick = { val action = pendingLeave; pendingLeave = null; draft = null; action?.invoke() }) { Text("放弃修改", color = Danger) } },
            dismissButton = { TextButton(onClick = { pendingLeave = null }) { Text("继续编辑") } })
        if (deleteDialog) AlertDialog(onDismissRequest = { if (!busy) deleteDialog = false }, title = { Text("删除「${draft?.name}」？") }, text = { Text("这张卡片的正反面图片、卡号和备注将一并删除，无法撤销。") },
            confirmButton = { TextButton(enabled = !busy, onClick = { scope.launch {
                busy = true
                try { cards = store.save(cards.filterNot { it.id == draft?.id }); deleteDialog = false; draft = null; selected = null; page = "home"; message("卡片已删除") }
                catch (e: Exception) { message((e as? CardStorageException)?.message ?: "删除失败，请重试") }
                busy = false
            } }) { Text(if (busy) "删除中…" else "删除", color = Danger) } },
            dismissButton = { TextButton(enabled = !busy, onClick = { deleteDialog = false }) { Text("取消") } })
        preview?.let { path -> ImagePreview(path, store) { preview = null } }
    }
}

@Composable
private fun PageBar(title: String, back: String, onBack: () -> Unit, action: String, onAction: () -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) { if (back == "返回") Glyph("back", "返回"); Text(back) }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
        TextButton(onClick = onAction, enabled = enabled) { Text(action, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun ListCard(card: Card, store: CardStore, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    var focused by remember { mutableStateOf(false) }
    val elevation by animateDpAsState(if (pressed) 2.dp else if (hovered) 8.dp else 4.dp)
    val scale by animateFloatAsState(if (pressed) .99f else 1f)
    val hasBothSides = card.front != null && card.back != null
    val cover = card.front ?: card.back
    val imageShape = RoundedCornerShape(24.dp)

    Column(Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.clickable(
        interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick,
    )) {
        Box(Modifier.fillMaxWidth().padding(top = if (hasBothSides) 10.dp else 0.dp).graphicsLayer {
            scaleX = scale; scaleY = scale
        }) {
            if (hasBothSides) {
                CardImage(card.back, store, "${card.name}反面", Modifier
                    .padding(horizontal = 10.dp).fillMaxWidth().aspectRatio(1.586f)
                    .offset(y = (-8).dp).graphicsLayer { rotationZ = -1f; alpha = .65f }
                    .shadow(2.dp, imageShape, clip = false), cornerRadius = 24.dp)
            }
            CardImage(cover, store, card.name, Modifier.fillMaxWidth().aspectRatio(1.586f)
                .shadow(elevation, imageShape, clip = false, ambientColor = VaultInk.copy(alpha = .14f), spotColor = VaultInk.copy(alpha = .14f))
                .border(1.dp, if (focused) VaultGreen else Color.Transparent, imageShape), cornerRadius = 24.dp, showPlaceholderName = true)
            if (cover != null) {
                Text(card.name, modifier = Modifier.align(Alignment.BottomStart).padding(14.dp)
                    .background(Color.Black.copy(alpha = .6f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                    fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (card.front == null && card.back != null) {
                Text("反面", modifier = Modifier.align(Alignment.TopEnd).padding(14.dp)
                    .background(Color.Black.copy(alpha = .6f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 11.sp, color = Color.White)
            }
        }
    }
}

@Composable
private fun CardImage(path: String?, store: CardStore, name: String, modifier: Modifier = Modifier, background: Color = VaultPale, cornerRadius: Dp = 12.dp, showPlaceholderName: Boolean = false) {
    var bitmap by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(path) { mutableStateOf(false) }
    LaunchedEffect(path) { if (path != null) try { bitmap = store.image(path) } catch (_: Exception) { failed = true } }
    Box(modifier.clip(RoundedCornerShape(cornerRadius)).background(background), contentAlignment = Alignment.Center) {
        when {
            bitmap != null -> Image(bitmap!!, name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            path != null && !failed -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                VaultMark(true)
                Text(if (failed) "图片无法读取" else if (showPlaceholderName) name else name.take(1).ifBlank { "卡片" },
                    modifier = Modifier.padding(horizontal = 24.dp), fontSize = if (showPlaceholderName && !failed) 18.sp else 15.sp,
                    fontWeight = if (showPlaceholderName && !failed) FontWeight.Medium else FontWeight.Normal,
                    textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, color = VaultMuted)
            }
        }
    }
}

@Composable
private fun DetailPage(card: Card, store: CardStore, onPreview: (String) -> Unit, onCopy: () -> Unit) {
    var front by remember(card.id) { mutableStateOf(card.front != null || card.back == null) }
    var reveal by remember(card.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
        Text(card.name, fontSize = 26.sp, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(VaultBorder.copy(alpha = .5f)).padding(4.dp)) {
            listOf(true, false).forEach { side ->
                val exists = if (side) card.front != null else card.back != null
                Surface(onClick = { front = side }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(9.dp), color = if (front == side) Color.White else Color.Transparent) {
                    Box(Modifier.height(44.dp), contentAlignment = Alignment.Center) { Text((if (side) "正面" else "反面") + if (exists) "" else " · 未添加", color = if (front == side) VaultGreen else VaultMuted, fontSize = 14.sp) }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        val path = if (front) card.front else card.back
        CardImage(path, store, card.name, Modifier.fillMaxWidth().aspectRatio(1.586f).then(if (path != null) Modifier.clickable { onPreview(path) } else Modifier))
        Text(if (path != null) "点击图片查看大图" else "编辑以添加${if (front) "正面" else "反面"}", Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp), fontSize = 12.sp, color = VaultMuted)
        Spacer(Modifier.height(20.dp)); FieldLabel("卡号")
        Surface(shape = RoundedCornerShape(14.dp), color = Color.White, border = BorderStroke(1.dp, VaultBorder)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (card.number.isBlank()) "未填写卡号" else if (reveal) card.number else maskedNumber(card.number), modifier = Modifier.weight(1f).padding(end = 8.dp), fontSize = 16.sp, color = if (card.number.isBlank()) VaultMuted else VaultInk)
                if (card.number.isNotBlank()) {
                    TextButton(onClick = { reveal = !reveal }, modifier = Modifier.size(48.dp), contentPadding = PaddingValues(horizontal = 8.dp)) { Text(if (reveal) "隐藏" else "显示") }
                    TextButton(onClick = onCopy, modifier = Modifier.size(48.dp), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("复制") }
                }
            }
        }
        Spacer(Modifier.height(20.dp)); FieldLabel("备注")
        Surface(shape = RoundedCornerShape(14.dp), color = Color.White, border = BorderStroke(1.dp, VaultBorder)) {
            Text(card.note.ifBlank { "暂无备注" }, Modifier.fillMaxWidth().padding(16.dp), fontSize = 15.sp, lineHeight = 25.sp, color = if (card.note.isBlank()) VaultMuted else VaultInk)
        }
    }
}

@Composable
private fun EditPage(card: Card, store: CardStore, busy: Boolean, existing: Boolean, onChange: (Card) -> Unit, onPick: (Boolean) -> Unit, onPreview: (String) -> Unit, onDelete: () -> Unit) {
    var nameFocused by remember(card.id) { mutableStateOf(false) }
    var nameTouched by remember(card.id) { mutableStateOf(false) }
    val nameError = if (card.name.length > 40) "名称最多 40 字" else if (nameTouched && card.name.isBlank()) "请填写卡片名称" else null
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 32.dp)) {
        FieldLabel("卡片照片")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(true, false).forEach { front ->
                val path = if (front) card.front else card.back
                var menu by remember { mutableStateOf(false) }
                Box(Modifier.weight(1f)) {
                    Box(Modifier.fillMaxWidth().aspectRatio(1.586f).clip(RoundedCornerShape(12.dp)).background(VaultPale).border(1.dp, VaultBorder, RoundedCornerShape(12.dp)).clickable(enabled = !busy) { if (path == null) onPick(front) else menu = true }, contentAlignment = Alignment.Center) {
                        if (path != null) CardImage(path, store, if (front) "正面" else "反面", Modifier.fillMaxSize())
                        else Column(horizontalAlignment = Alignment.CenterHorizontally) { Glyph("plus", if (front) "添加正面" else "添加反面", VaultGreen); Text(if (front) "添加正面" else "添加反面", fontSize = 13.sp, color = VaultGreen) }
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("查看大图") }, onClick = { menu = false; path?.let(onPreview) })
                        DropdownMenuItem(text = { Text("替换图片") }, onClick = { menu = false; onPick(front) })
                        DropdownMenuItem(text = { Text("移除图片", color = Danger) }, onClick = { menu = false; onChange(if (front) card.copy(front = null) else card.copy(back = null)) })
                    }
                    if (path != null) Text(if (front) "正面" else "反面", Modifier.align(Alignment.BottomStart).padding(8.dp).background(Color.White.copy(alpha = .9f), CircleShape).padding(horizontal = 8.dp, vertical = 2.dp), fontSize = 11.sp, color = VaultMuted)
                }
            }
        }
        Text("添加卡面，查找时更直观", Modifier.padding(top = 10.dp), fontSize = 13.sp, color = VaultMuted)
        Spacer(Modifier.height(28.dp)); FieldLabel("名称 *")
        OutlinedTextField(card.name, { onChange(card.copy(name = it)) }, enabled = !busy, placeholder = { Text("例如：城市交通卡") }, singleLine = true, isError = nameError != null,
            supportingText = if (nameError != null) ({ Text(nameError) }) else null, colors = formColors(), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().onFocusChanged {
                if (nameFocused && !it.isFocused) nameTouched = true
                nameFocused = it.isFocused
            })
        Spacer(Modifier.height(20.dp)); FieldLabel("卡号")
        OutlinedTextField(card.number, { onChange(card.copy(number = it)) }, enabled = !busy, placeholder = { Text("输入卡号") }, singleLine = true, isError = card.number.length > 80,
            supportingText = if (card.number.length > 80) ({ Text("卡号最多 80 字符") }) else null, colors = formColors(), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(20.dp)); FieldLabel("备注")
        OutlinedTextField(card.note, { onChange(card.copy(note = it)) }, enabled = !busy, placeholder = { Text("记录用途或其他补充信息") }, minLines = 4, isError = card.note.length > 2000,
            supportingText = if (card.note.length > 1800) ({ Text("${card.note.length} / 2000") }) else null, colors = formColors(), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
        if (existing) { Spacer(Modifier.height(32.dp)); TextButton(onClick = onDelete, enabled = !busy, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("删除卡片", color = Danger) } }
    }
}

@Composable
private fun formColors() = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Color.White, focusedContainerColor = Color.White, disabledContainerColor = Color.White, unfocusedBorderColor = VaultBorder)

@Composable
private fun FieldLabel(text: String) { Text(text, Modifier.padding(bottom = 10.dp), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = VaultMuted) }

@Composable
private fun EmptyState(title: String, subtitle: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxSize().padding(28.dp).padding(bottom = 70.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(100.dp).background(VaultPale, CircleShape), contentAlignment = Alignment.Center) { VaultMark(true) }
        Spacer(Modifier.height(24.dp)); Text(title, fontSize = 20.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(12.dp)); Text(subtitle, fontSize = 13.sp, color = VaultMuted, textAlign = TextAlign.Center)
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun ImagePreview(path: String, store: CardStore, onClose: () -> Unit) {
    var scale by remember { mutableStateOf(1f) }; var x by remember { mutableStateOf(0f) }; var y by remember { mutableStateOf(0f) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xFF101B19))) {
            Box(Modifier.fillMaxSize().padding(24.dp).pointerInput(Unit) { detectTransformGestures { _, pan, zoom, _ -> scale = (scale * zoom).coerceIn(1f, 5f); x += pan.x; y += pan.y } }) {
                CardImage(path, store, "卡片大图", Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = x; translationY = y }, background = Color.Transparent)
            }
            Row(Modifier.align(Alignment.TopEnd).padding(12.dp).background(Color.White, CircleShape)) {
                TextButton(onClick = { scale = (scale - .5f).coerceAtLeast(1f); if (scale == 1f) { x = 0f; y = 0f } }) { Text("缩小") }
                TextButton(onClick = { scale = (scale + .5f).coerceAtMost(5f) }) { Text("放大") }
                TextButton(onClick = onClose) { Text("关闭") }
            }
        }
    }
}
