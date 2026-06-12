package com.example.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.engine.EnvironmentMode
import com.example.engine.LineType
import com.example.engine.TerminalLine
import com.example.engine.TerminalTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.random.Random

// --- Theme Style Container ---
data class ThemeColors(
    val bg: Color,
    val text: Color,
    val accent: Color,
    val error: Color,
    val success: Color,
    val systemTxt: Color,
    val toolbarBg: Color,
    val inputBg: Color
)

fun getColorsForTheme(theme: TerminalTheme): ThemeColors {
    return when (theme) {
        TerminalTheme.DRACULA -> ThemeColors(
            bg = Color(0xFF1E1F29),
            text = Color(0xFFF8F8F2),
            accent = Color(0xFFBD93F9),
            error = Color(0xFFFF5555),
            success = Color(0xFF50FA7B),
            systemTxt = Color(0xFF8BE9FD),
            toolbarBg = Color(0xFF282A36),
            inputBg = Color(0xFF21222C)
        )
        TerminalTheme.MATRIX -> ThemeColors(
            bg = Color(0xFF000000),
            text = Color(0xFF00FF00),
            accent = Color(0xFF00DD00),
            error = Color(0xFFFF0033),
            success = Color(0xFF00FF00),
            systemTxt = Color(0xFF98FB98),
            toolbarBg = Color(0xFF0A0F0A),
            inputBg = Color(0xFF020502)
        )
        TerminalTheme.AMBER -> ThemeColors(
            bg = Color(0xFF160E02),
            text = Color(0xFFFFB000),
            accent = Color(0xFFFFD700),
            error = Color(0xFFD2143A),
            success = Color(0xFFFFA500),
            systemTxt = Color(0xFFE69A0B),
            toolbarBg = Color(0xFF231705),
            inputBg = Color(0xFF1D1103)
        )
        TerminalTheme.CYBERPUNK -> ThemeColors(
            bg = Color(0xFF0A0D17),
            text = Color(0xFF00FFFF),
            accent = Color(0xFFEC008C),
            error = Color(0xFFFF0055),
            success = Color(0xFF39FF14),
            systemTxt = Color(0xFFFF00FF),
            toolbarBg = Color(0xFF141A2E),
            inputBg = Color(0xFF0D1221)
        )
        TerminalTheme.MONOKAI -> ThemeColors(
            bg = Color(0xFF272822),
            text = Color(0xFFF8F8F2),
            accent = Color(0xFF66D9EF),
            error = Color(0xFFF92672),
            success = Color(0xFFA6E22E),
            systemTxt = Color(0xFFFD971F),
            toolbarBg = Color(0xFF1E1F1C),
            inputBg = Color(0xFF2E3029)
        )
        TerminalTheme.HACKER_PURPLE -> ThemeColors(
            bg = Color(0xFF0F011F),
            text = Color(0xFFD4BFFF),
            accent = Color(0xFFB026FF),
            error = Color(0xFFFF3385),
            success = Color(0xFF26FFB0),
            systemTxt = Color(0xFFE1CCFF),
            toolbarBg = Color(0xFF1A0236),
            inputBg = Color(0xFF120126)
        )
    }
}

@Composable
fun TermuxScreen(
    modifier: Modifier = Modifier,
    vm: TermuxViewModel = viewModel()
) {
    val currentTheme by vm.currentTheme.collectAsState()
    val colors = getColorsForTheme(currentTheme)
    val currentMode by vm.currentMode.collectAsState()
    
    val editorState by vm.editorState.collectAsState()
    val showMatrixAnimation by vm.showMatrixAnimation.collectAsState()

    Surface(
        modifier = modifier.fillMaxSize(),
        color = colors.bg
    ) {
        when {
            showMatrixAnimation -> {
                MatrixRainScreen(
                    onExit = { vm.closeMatrixAnimation() }
                )
            }
            editorState != null -> {
                LiveFileEditorScreen(
                    state = editorState!!,
                    colors = colors,
                    onSave = { content -> vm.saveEditorContent(content) },
                    onClose = { vm.closeEditor() }
                )
            }
            else -> {
                TerminalConsoleMainView(vm = vm, colors = colors, currentMode = currentMode)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TerminalConsoleMainView(
    vm: TermuxViewModel,
    colors: ThemeColors,
    currentMode: EnvironmentMode
) {
    val scope = rememberCoroutineScope()
    val sessions by vm.sessions.collectAsState()
    val activeSessionId by vm.activeSessionId.collectAsState()
    val sessionLines by vm.sessionLines.collectAsState()
    val scrollTrigger by vm.scrollTrigger.collectAsState()
    val commandHistory by vm.commandHistory.collectAsState()

    val currentLines = sessionLines[activeSessionId] ?: emptyList()
    val activeSessionName = sessions.firstOrNull { it.id == activeSessionId }?.name ?: "Bash"

    var inputText by remember { mutableStateOf(TextFieldValue("")) }
    var historyIndex by remember { mutableStateOf(-1) }

    var showsThemeDropdown by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current
    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()

    LaunchedEffect(scrollTrigger, currentLines.size) {
        if (currentLines.isNotEmpty()) {
            listState.animateScrollToItem(currentLines.size - 1)
        }
    }

    LaunchedEffect(activeSessionId) {
        focusRequester.requestFocus()
    }

    Scaffold(
        containerColor = colors.bg,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.toolbarBg,
                    titleContentColor = colors.text
                ),
                title = {
                    Row(
                        modifier = Modifier,
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Build,
                            contentDescription = "Termux",
                            tint = colors.accent,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = "Termux [Session: $activeSessionName]",
                            fontSize = 17.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { vm.createNewSession() },
                        modifier = Modifier.testTag("add_session_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "New Session",
                            tint = colors.accent
                        )
                    }

                    Box {
                        IconButton(onClick = { showsThemeDropdown = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Menu",
                                tint = colors.text
                            )
                        }
                        DropdownMenu(
                            expanded = showsThemeDropdown,
                            onDismissRequest = { showsThemeDropdown = false },
                            modifier = Modifier.background(colors.toolbarBg).border(1.dp, colors.accent)
                        ) {
                            Divider(color = colors.accent.copy(alpha = 0.3f))
                            Text(
                                text = "Environment Mode",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = colors.accent,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                            )
                            EnvironmentMode.values().forEach { mode ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            mode.displayName,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 13.sp,
                                            color = colors.text,
                                            fontWeight = if (mode == currentMode) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    onClick = {
                                        vm.executeTerminalCommand("mode ${mode.name.lowercase()}")
                                        showsThemeDropdown = false
                                    }
                                )
                            }
                            Divider(color = colors.accent.copy(alpha = 0.3f))
                            Text(
                                text = "Select Theme",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = colors.accent,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                            )
                            Divider(color = colors.accent.copy(alpha = 0.3f))
                            TerminalTheme.values().forEach { theme ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            theme.displayName,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 13.sp,
                                            color = colors.text,
                                            fontWeight = if (theme == vm.currentTheme.value) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    onClick = {
                                        vm.executeTerminalCommand("theme ${theme.name.lowercase()}")
                                        showsThemeDropdown = false
                                    }
                                )
                            }
                            Divider(color = colors.accent.copy(alpha = 0.3f))
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "Copy Console Text",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 13.sp,
                                        color = colors.success
                                    )
                                },
                                onClick = {
                                    val logText = currentLines.joinToString("\n") { it.text }
                                    clipboardManager.setText(AnnotatedString(logText))
                                    showsThemeDropdown = false
                                    vm.executeTerminalCommand("echo '[Console lines successfully copied to clipboard]'")
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "Reset Shell",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 13.sp,
                                        color = colors.error
                                    )
                                },
                                onClick = {
                                    vm.executeTerminalCommand("clear")
                                    vm.executeTerminalCommand("banner")
                                    showsThemeDropdown = false
                                }
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
            ) {
                if (sessions.size > 1) {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(colors.toolbarBg)
                            .padding(vertical = 4.dp, horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(sessions) { s ->
                            val isActive = s.id == activeSessionId
                            androidx.compose.foundation.layout.Row(
                                modifier = Modifier
                                    .background(
                                        if (isActive) colors.accent.copy(alpha = 0.2f) else colors.inputBg,
                                        shape = RoundedCornerShape(4.dp)
                                    )
                                    .border(
                                        1.dp,
                                        if (isActive) colors.accent else colors.text.copy(alpha = 0.2f),
                                        shape = RoundedCornerShape(4.dp)
                                    )
                                    .clickable { vm.switchSession(s.id) }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = s.name,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = if (isActive) colors.accent else colors.text
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = colors.error.copy(alpha = 0.7f),
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clickable { vm.removeSession(s) }
                                )
                            }
                        }
                    }
                }

                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.toolbarBg)
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    item {
                        AccessoryButton(label = "TAB", colors = colors) {
                            inputText = TextFieldValue(
                                text = inputText.text + "    ",
                                selection = androidx.compose.ui.text.TextRange(inputText.text.length + 4)
                            )
                        }
                    }
                    item {
                        AccessoryButton(label = "CTRL", colors = colors, isToggled = false) {
                            vm.executeTerminalCommand("echo 'Ctrl action toggled (Simulated)'")
                        }
                    }
                    item {
                        AccessoryButton(label = "ALT", colors = colors) {
                            vm.executeTerminalCommand("echo 'Alt action toggled'")
                        }
                    }
                    item {
                        AccessoryButton(label = "ESC", colors = colors) {
                            inputText = TextFieldValue("")
                            historyIndex = -1
                        }
                    }
                    item {
                        AccessoryButton(label = "▲", colors = colors) {
                            if (commandHistory.isNotEmpty()) {
                                val nextInd = historyIndex + 1
                                if (nextInd < commandHistory.size) {
                                    historyIndex = nextInd
                                    val prevCmd = commandHistory[nextInd].command
                                    inputText = TextFieldValue(text = prevCmd, selection = androidx.compose.ui.text.TextRange(prevCmd.length))
                                }
                            }
                        }
                    }
                    item {
                        AccessoryButton(label = "▼", colors = colors) {
                            if (historyIndex > 0) {
                                val nextInd = historyIndex - 1
                                historyIndex = nextInd
                                val prevCmd = commandHistory[nextInd].command
                                inputText = TextFieldValue(text = prevCmd, selection = androidx.compose.ui.text.TextRange(prevCmd.length))
                            } else {
                                historyIndex = -1
                                inputText = TextFieldValue("")
                            }
                        }
                    }
                    listOf("|", "/", "-", "_", "~", "$", ">", ";", "&", "*").forEach { char ->
                        item {
                            AccessoryButton(label = char, colors = colors) {
                                val currentCursor = inputText.selection.start
                                val original = inputText.text
                                val newStr = original.substring(0, currentCursor) + char + original.substring(currentCursor)
                                inputText = TextFieldValue(
                                    text = newStr,
                                    selection = androidx.compose.ui.text.TextRange(currentCursor + 1)
                                )
                                focusRequester.requestFocus()
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.inputBg)
                        .border(1.dp, colors.accent.copy(alpha = 0.2f))
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val prompt = if (vm.engine.isPythonActive(activeSessionId)) ">>> " else "$ "
                    Text(
                        text = prompt,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        color = colors.accent,
                        fontWeight = FontWeight.Bold
                    )

                    BasicTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("terminal_input_field")
                            .focusRequester(focusRequester),
                        textStyle = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                            color = colors.text
                        ),
                        cursorBrush = SolidColor(colors.accent),
                        keyboardOptions = KeyboardOptions(
                            imeAction = ImeAction.Send,
                            autoCorrectEnabled = false
                        ),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                val cmd = inputText.text
                                inputText = TextFieldValue("")
                                historyIndex = -1
                                vm.executeTerminalCommand(cmd)
                            }
                        )
                    )

                    IconButton(
                        onClick = {
                            val cmd = inputText.text
                            inputText = TextFieldValue("")
                            historyIndex = -1
                            vm.executeTerminalCommand(cmd)
                        },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = "Run",
                            tint = colors.accent,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    focusRequester.requestFocus()
                }
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
            ) {
                items(currentLines) { line ->
                    val textColor = when (line.type) {
                        LineType.INPUT -> colors.accent
                        LineType.ERROR -> colors.error
                        LineType.SYSTEM -> colors.systemTxt
                        LineType.SUCCESS -> colors.success
                        LineType.AI_USER -> colors.accent
                        LineType.AI_RESP -> colors.text
                        else -> colors.text
                    }
                    
                    val textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = textColor,
                        lineHeight = 18.sp
                    )
                    
                    SelectionContainer {
                        Text(
                            text = line.text,
                            style = textStyle,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 1.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AccessoryButton(
    label: String,
    colors: ThemeColors,
    isToggled: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .background(
                if (isToggled) colors.accent else colors.inputBg,
                shape = RoundedCornerShape(4.dp)
            )
            .border(1.dp, colors.accent.copy(alpha = 0.5f), shape = RoundedCornerShape(4.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = if (isToggled) colors.bg else colors.text,
            fontWeight = FontWeight.Bold
        )
    }
}

// SelectionContainer wrapper fallback if Foundation libraries not found
@Composable
fun SelectionContainer(content: @Composable () -> Unit) {
    androidx.compose.foundation.text.selection.SelectionContainer {
        content()
    }
}


// --- 2. Live Interactive Monospace Code Editor ---

@Composable
fun LiveFileEditorScreen(
    state: TermuxViewModel.EditorState,
    colors: ThemeColors,
    onSave: (String) -> Unit,
    onClose: () -> Unit
) {
    var editorText by remember { mutableStateOf(state.content) }
    val filename = remember { File(state.filePath).name }

    Scaffold(
        containerColor = colors.bg,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.toolbarBg)
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Editor",
                        tint = colors.accent,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(
                        text = if (state.isVim) "VIM 8.2 - $filename" else "NANO 2.9 - [Editing: $filename]",
                        color = colors.text,
                        fontSize = 15.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        colors = ButtonDefaults.buttonColors(containerColor = colors.error),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        shape = RoundedCornerShape(4.dp),
                        onClick = onClose
                    ) {
                        Text("Exit", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }

                    Button(
                        colors = ButtonDefaults.buttonColors(containerColor = colors.success),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        shape = RoundedCornerShape(4.dp),
                        onClick = { onSave(editorText) },
                        modifier = Modifier.testTag("editor_save_button")
                    ) {
                        Text("Save & Quit", color = colors.bg, fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.toolbarBg)
                    .navigationBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("^G Get Help", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = colors.text.copy(alpha = 0.6f))
                Text("^O WriteOut", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = colors.text.copy(alpha = 0.6f))
                Text("^R Read File", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = colors.text.copy(alpha = 0.6f))
                Text("^Y Prev Page", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = colors.text.copy(alpha = 0.6f))
                Text("^V Next Page", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = colors.text.copy(alpha = 0.6f))
                Text("^X Close Nano", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = colors.text.copy(alpha = 0.6f))
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(colors.inputBg)
        ) {
            BasicTextField(
                value = editorText,
                onValueChange = { editorText = it },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
                    .testTag("file_editor_textarea"),
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    color = colors.text,
                    lineHeight = 20.sp
                ),
                cursorBrush = SolidColor(colors.accent)
            )
        }
    }
}


// --- 3. Digital Matrix Falling Retro Rain Animation Screen ---

data class MatrixDrop(
    var x: Float,
    var y: Float,
    var speed: Float,
    var chars: String,
    var activeChar: Char
)

@Composable
fun MatrixRainScreen(
    onExit: () -> Unit
) {
    val drops = remember { mutableStateListOf<MatrixDrop>() }
    var ticks by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        for (i in 0 until 35) {
            drops.add(
                MatrixDrop(
                    x = Random.nextFloat(),
                    y = Random.nextFloat() * -1.2f,
                    speed = 0.015f + Random.nextFloat() * 0.035f,
                    chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ$#@&%*=+?!",
                    activeChar = 'a'
                )
            )
        }

        while (true) {
            delay(40)
            ticks += 1
            for (drop in drops) {
                drop.y += drop.speed
                drop.activeChar = drop.chars[Random.nextInt(drop.chars.length)]
                if (drop.y > 1.1f) {
                    drop.y = -0.1f - Random.nextFloat() * 0.2f
                    drop.speed = 0.015f + Random.nextFloat() * 0.035f
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable { onExit() }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height

            for (drop in drops) {
                val dropX = drop.x * width
                val dropY = drop.y * height

                val dropSize = 13
                for (t in 0 until dropSize) {
                    val tailY = dropY - (t * 22)
                    if (tailY < 0 || tailY > height) continue

                    val alpha = (1.0f - (t.toFloat() / dropSize)).coerceIn(0f, 1f)
                    val greenColor = if (t == 0) {
                        Color(0xFFE5FFEA)
                    } else {
                        Color(0xFF00FF41).copy(alpha = alpha)
                    }

                    drawContext.canvas.nativeCanvas.drawText(
                        drop.activeChar.toString(),
                        dropX,
                        tailY,
                        android.graphics.Paint().apply {
                            color = greenColor.value.toLong().toInt()
                            textSize = 42f
                            typeface = android.graphics.Typeface.MONOSPACE
                            isAntiAlias = true
                        }
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .statusBarsPadding()
                .padding(bottom = 24.dp)
                .background(Color.DarkGray.copy(alpha = 0.8f), shape = RoundedCornerShape(8.dp))
                .border(1.dp, Color(0xFF00FF41), shape = RoundedCornerShape(8.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Text(
                "MATRIX FALLOUT STREAM - Tap anywhere to exit",
                color = Color(0xFF00FF41),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
