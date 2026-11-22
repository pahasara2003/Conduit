package com.example.conduit.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.conduit.AiFile
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.window.Dialog
import com.example.conduit.BaseModelGroup
import com.example.conduit.ConduitState
import com.example.conduit.IdeWorkspaceState
import com.example.conduit.MarkdownSegment
import com.example.conduit.ReasoningEffort
import com.example.conduit.ToolActivityKind
import com.example.conduit.WorkspaceItem
import kotlin.math.roundToInt
import com.example.conduit.highlightCodeSyntax
import com.example.conduit.parseInlineMarkdown
import com.example.conduit.parseMarkdownSegments
import com.example.conduit.InlineToken
import com.example.conduit.cleanInlineMarkdown
import com.example.conduit.tokenizeInlineMarkdown
import com.example.conduit.ui.theme.IdeSurfaceBrightDark
import com.example.conduit.ui.theme.CodeBg
import com.example.conduit.ui.theme.CodeBorder
import com.example.conduit.ui.theme.CodeHeaderBg
import com.example.conduit.ui.theme.IdeAccent
import com.example.conduit.ui.theme.IdeTheme
import com.example.conduit.ui.theme.StatusError
import com.example.conduit.ui.theme.StatusOnline
import com.example.conduit.ui.theme.StatusWarning
import com.example.conduit.ui.theme.SyntaxPlain

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdeWorkspaceScreen(
    syncState: ConduitState,
    workspaceState: IdeWorkspaceState,
    promptInput: String,
    isVoiceActive: Boolean,
    onPromptChange: (String) -> Unit,
    onSendPrompt: () -> Unit,
    onVoiceToggle: () -> Unit,
    onOpenDrawer: () -> Unit,
    onRefreshConnection: () -> Unit,
    onRefreshModels: () -> Unit,
    onSelectBaseModel: (BaseModelGroup) -> Unit,
    onSelectEffort: (ReasoningEffort) -> Unit,
    onCopyText: (String) -> Unit,
    onDownloadFiles: (List<AiFile>) -> Unit
) {
    val listState = rememberLazyListState()
    var showModelControl by remember { mutableStateOf(false) }

    LaunchedEffect(workspaceState.items.size) {
        if (workspaceState.items.isNotEmpty()) {
            listState.animateScrollToItem(workspaceState.items.lastIndex)
        }
    }

    val statusDotColor by animateColorAsState(
        targetValue = if (syncState.isPcOnline) StatusOnline else IdeTheme.extras.muted,
        label = "pcDot"
    )

    val sessionTitle = workspaceState.activeSession?.title?.takeIf { it.isNotBlank() } ?: "New Chat"
    val compactModelName = workspaceState.selectedBaseModel.displayName

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onOpenDrawer) {
                            Icon(
                                imageVector = Icons.Rounded.Menu,
                                contentDescription = "Sessions & Settings",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    },
                    title = {
                        Text(
                            text = sessionTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable(onClick = onOpenDrawer)
                        )
                    },
                    actions = {
                        Surface(
                            onClick = {
                                onRefreshModels()
                                showModelControl = true
                            },
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Text(
                                    text = compactModelName,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Icon(
                                    imageVector = Icons.Rounded.ExpandMore,
                                    contentDescription = "Select model",
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .padding(start = 6.dp, end = 12.dp)
                                .size(28.dp)
                                .clip(CircleShape)
                                .clickable(onClick = onRefreshConnection),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(statusDotColor)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        },
        bottomBar = {
            FloatingPromptDock(
                value = promptInput,
                isWorking = workspaceState.isWorking,
                isVoiceActive = isVoiceActive,
                onValueChange = onPromptChange,
                onVoiceToggle = onVoiceToggle,
                onSend = onSendPrompt
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (workspaceState.items.isEmpty()) {
                MinimalEmptyState(
                    modelLabel = workspaceState.selectedModel.label,
                    isOnline = syncState.isPcOnline
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    items(workspaceState.items, key = { it.id }) { item ->
                        when (item) {
                            is WorkspaceItem.UserPrompt -> UserPromptPill(item)
                            is WorkspaceItem.AssistantResponse -> AssistantStreamBlock(
                                item = item,
                                onCopy = onCopyText,
                                onDownloadFiles = onDownloadFiles
                            )
                            is WorkspaceItem.ToolExecution -> MinimalToolOutput(
                                item = item,
                                onCopy = onCopyText
                            )
                            is WorkspaceItem.ErrorNotice -> MinimalErrorNotice(item)
                            else -> Unit
                        }
                    }

                    if (workspaceState.isWorking) {
                        item(key = "generating_indicator") {
                            GeneratingRow(workspaceState.workingStatus)
                        }
                    }
                }
            }
        }
    }

    if (showModelControl) {
        FloatingModelEffortDialog(
            baseModels = workspaceState.baseModels,
            selectedBaseModel = workspaceState.selectedBaseModel,
            selectedEffort = workspaceState.selectedEffort,
            composedModelId = workspaceState.selectedModel.id,
            onDismiss = { showModelControl = false },
            onSelectBaseModel = onSelectBaseModel,
            onSelectEffort = onSelectEffort
        )
    }
}

@Composable
private fun MinimalEmptyState(
    modelLabel: String,
    isOnline: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Rounded.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
            modifier = Modifier.size(28.dp)
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = "What are we working on?",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (isOnline) modelLabel else "$modelLabel · Offline",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun FloatingPromptDock(
    value: String,
    isWorking: Boolean,
    isVoiceActive: Boolean,
    onValueChange: (String) -> Unit,
    onVoiceToggle: () -> Unit,
    onSend: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shadowElevation = 8.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 36.dp, max = 140.dp)
                        .padding(vertical = 7.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (value.isEmpty()) {
                        Text(
                            text = "Ask anything...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = IdeTheme.extras.muted
                        )
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                IconButton(
                    onClick = onVoiceToggle,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = if (isVoiceActive) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                        contentDescription = "Voice input",
                        tint = if (isVoiceActive) StatusError else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(19.dp)
                    )
                }

                val canSend = value.isNotBlank() && !isWorking
                val sendBg by animateColorAsState(
                    targetValue = if (canSend || isWorking) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    label = "sendBg"
                )

                Surface(
                    onClick = onSend,
                    enabled = canSend,
                    shape = CircleShape,
                    color = sendBg,
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (isWorking) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                        } else {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                                contentDescription = "Send",
                                tint = if (canSend) Color.White else IdeTheme.extras.muted,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UserPromptPill(item: WorkspaceItem.UserPrompt) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 300.dp),
            shape = RoundedCornerShape(
                topStart = 20.dp,
                topEnd = 20.dp,
                bottomStart = 20.dp,
                bottomEnd = 6.dp
            ),
            color = IdeTheme.extras.userBubble
        ) {
            Text(
                text = item.text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 15.dp, vertical = 10.dp)
            )
        }
    }
}

@Composable
private fun AssistantStreamBlock(
    item: WorkspaceItem.AssistantResponse,
    onCopy: (String) -> Unit,
    onDownloadFiles: (List<AiFile>) -> Unit
) {
    val segments = remember(item.text) { parseMarkdownSegments(item.text) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        segments.forEach { segment ->
            when (segment) {
                is MarkdownSegment.Heading -> MarkdownHeading(segment)
                is MarkdownSegment.Paragraph -> {
                    MarkdownInlineText(
                        text = segment.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
                is MarkdownSegment.BulletItem -> MarkdownBulletRow(segment)
                is MarkdownSegment.CodeBlock -> {
                    CleanCodeBlock(
                        language = segment.language,
                        code = segment.code,
                        onCopy = { onCopy(segment.code) }
                    )
                }
                is MarkdownSegment.Table -> {
                    MarkdownTable(
                        headers = segment.headers,
                        rows = segment.rows
                    )
                }
                is MarkdownSegment.ToolActivity -> {
                    CollapsibleToolCard(
                        summary = segment.summary,
                        details = segment.details,
                        dotColor = when (segment.kind) {
                            ToolActivityKind.COMMAND -> StatusOnline
                            ToolActivityKind.EDIT -> StatusOnline
                            ToolActivityKind.EXPLORE -> IdeAccent
                            ToolActivityKind.THOUGHT -> IdeAccent
                        },
                        isRunning = false,
                        onCopy = { onCopy(segment.details) }
                    )
                }
                is MarkdownSegment.Image -> MarkdownImage(
                    segment,
                    // the agent often embeds a hidden scratch copy; prefer the attached file with the same name
                    item.files.firstOrNull { it.name == segment.path.substringAfterLast('/') }?.path ?: segment.path
                )
                is MarkdownSegment.Divider -> {
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }
        }

        Row(
            modifier = Modifier.padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onCopy(item.text) }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.ContentCopy,
                    contentDescription = "Copy",
                    tint = IdeTheme.extras.muted,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = "Copy",
                    style = MaterialTheme.typography.labelSmall,
                    color = IdeTheme.extras.muted
                )
            }
            Text(
                text = item.timestamp,
                style = MaterialTheme.typography.labelSmall,
                color = IdeTheme.extras.muted
            )
        }
        if (item.files.isNotEmpty()) {
            item.files.forEach { f -> FileChip("${f.name} · ${formatSize(f.size)}") { onDownloadFiles(listOf(f)) } }
            if (item.files.size > 1) FileChip("Download all (${item.files.size} files, .zip)") { onDownloadFiles(item.files) }
        }
    }
}

private fun formatSize(b: Long): String = when {
    b >= 1_048_576 -> "%.1f MB".format(b / 1_048_576.0)
    b >= 1024 -> "%.0f KB".format(b / 1024.0)
    else -> "$b B"
}

@Composable
private fun MarkdownImage(seg: MarkdownSegment.Image, path: String) {
    val bmp by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(null, path) {
        value = com.example.conduit.ConduitRepository.loadPcImage(path)
    }
    val shape = RoundedCornerShape(12.dp)
    val b = bmp
    if (b != null) {
        androidx.compose.foundation.Image(
            bitmap = b.asImageBitmap(),
            contentDescription = seg.alt,
            contentScale = androidx.compose.ui.layout.ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth().clip(shape)
        )
    } else {
        Box(Modifier.fillMaxWidth().height(120.dp).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Text(seg.alt.ifBlank { "Loading image…" }, style = MaterialTheme.typography.labelMedium, color = IdeTheme.extras.muted)
        }
    }
}

@Composable
private fun FileChip(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.Rounded.Download, contentDescription = "Download", tint = IdeAccent, modifier = Modifier.size(18.dp))
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun MarkdownHeading(heading: MarkdownSegment.Heading) {
    val styled = remember(heading.text) { parseInlineMarkdown(heading.text) }
    val (fontSize, lineHeight, fontWeight, topPad, color) = when (heading.level) {
        1 -> HeadingStyle(20.sp, 28.sp, FontWeight.Bold, 6.dp, MaterialTheme.colorScheme.onBackground)
        2 -> HeadingStyle(17.sp, 24.sp, FontWeight.SemiBold, 5.dp, MaterialTheme.colorScheme.onBackground)
        else -> HeadingStyle(15.sp, 21.sp, FontWeight.SemiBold, 3.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.92f))
    }
    Text(
        text = styled,
        fontSize = fontSize,
        lineHeight = lineHeight,
        fontWeight = fontWeight,
        letterSpacing = (-0.2).sp,
        color = color,
        modifier = Modifier.padding(top = topPad)
    )
}

private data class HeadingStyle(
    val fontSize: androidx.compose.ui.unit.TextUnit,
    val lineHeight: androidx.compose.ui.unit.TextUnit,
    val fontWeight: FontWeight,
    val topPadding: androidx.compose.ui.unit.Dp,
    val color: Color
)

@Composable
private fun MarkdownInlineText(
    text: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = MaterialTheme.colorScheme.onBackground
) {
    val tokens = remember(text) { tokenizeInlineMarkdown(text) }
    // If no inline code pills, use normal Text
    val hasCode = remember(tokens) { tokens.any { it is InlineToken.Code } }
    if (!hasCode) {
        val styled = remember(text) { parseInlineMarkdown(text) }
        Text(text = styled, style = style, color = color, modifier = modifier)
        return
    }

    // One flowing Text: code spans are inline placeholders (rounded pills) so wrapping stays natural
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val codeStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val annotated = remember(tokens, style) {
        androidx.compose.ui.text.buildAnnotatedString {
            tokens.forEachIndexed { i, token ->
                when (token) {
                    is InlineToken.Code -> appendInlineContent("code$i", token.code)
                    is InlineToken.Text -> append(token.text)
                    is InlineToken.Bold -> withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.SemiBold)) { append(token.text) }
                    is InlineToken.Italic -> withStyle(androidx.compose.ui.text.SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) { append(token.text) }
                    is InlineToken.BoldItalic -> withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) { append(token.text) }
                    is InlineToken.Link -> withStyle(androidx.compose.ui.text.SpanStyle(color = IdeAccent)) { append(token.label) }
                }
            }
        }
    }
    val inline = remember(tokens, style) {
        tokens.mapIndexedNotNull { i, token ->
            if (token !is InlineToken.Code) return@mapIndexedNotNull null
            val w = measurer.measure(token.code, codeStyle, softWrap = false).size.width
            val pillW = with(density) { (w.toDp() + 14.dp).toSp() } // 6dp padding each side + border
            val pillH = with(density) { (measurer.measure(token.code, codeStyle).size.height.toDp() + 4.dp).toSp() }
            "code$i" to androidx.compose.foundation.text.InlineTextContent(
                androidx.compose.ui.text.Placeholder(pillW, pillH, androidx.compose.ui.text.PlaceholderVerticalAlign.TextCenter)
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = IdeTheme.extras.codePill,
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = token.code,
                        style = codeStyle,
                        color = IdeTheme.extras.codePillText,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }.toMap()
    }
    Text(text = annotated, inlineContent = inline, style = style, color = color, modifier = modifier)
}

/** Natural single-line width in px of inline markdown, counting code pills' padding. */
private fun measureInlineWidth(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    measurer: androidx.compose.ui.text.TextMeasurer,
    padPx: Float
): Float = tokenizeInlineMarkdown(text).sumOf { t ->
    val (str, st, extra) = when (t) {
        is InlineToken.Code -> Triple(t.code, style.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp), padPx)
        is InlineToken.Text -> Triple(t.text, style, 0f)
        is InlineToken.Bold -> Triple(t.text, style.copy(fontWeight = FontWeight.SemiBold), 0f)
        is InlineToken.Italic -> Triple(t.text, style, 0f)
        is InlineToken.BoldItalic -> Triple(t.text, style.copy(fontWeight = FontWeight.Bold), 0f)
        is InlineToken.Link -> Triple(t.label, style, 0f)
    }
    (measurer.measure(str, st, softWrap = false).size.width + extra).toDouble()
}.toFloat()

@Composable
private fun MarkdownTable(
    headers: List<String>,
    rows: List<List<String>>
) {
    // Column width = widest measured cell (header or body) + cell padding; very long cells wrap at the cap
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val headerStyle = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
    val bodyStyle = MaterialTheme.typography.bodySmall
    val columnWidths = remember(headers, rows, headerStyle, bodyStyle) {
        val pad = with(density) { 14.dp.toPx() }
        headers.indices.map { c ->
            val widest = maxOf(
                measureInlineWidth(headers.getOrElse(c) { "" }, headerStyle, measurer, pad),
                rows.maxOfOrNull { measureInlineWidth(it.getOrElse(c) { "" }, bodyStyle, measurer, pad) } ?: 0f
            )
            with(density) { (widest.toDp() + 24.dp).coerceIn(72.dp, 420.dp) }
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 4.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 4.dp)) {
                // Header Row
                Row(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    headers.forEachIndexed { colIdx, headerText ->
                        val colWidth = columnWidths.getOrElse(colIdx) { 130.dp }
                        Box(
                            modifier = Modifier
                                .width(colWidth)
                                .padding(horizontal = 10.dp)
                        ) {
                            MarkdownInlineText(
                                text = headerText,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)

                // Body Rows
                rows.forEachIndexed { rowIdx, rowCells ->
                    Row(
                        modifier = Modifier
                            .background(
                                if (rowIdx % 2 == 1) MaterialTheme.colorScheme.surface.copy(alpha = 0.35f)
                                else Color.Transparent
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        headers.indices.forEach { colIdx ->
                            val colWidth = columnWidths.getOrElse(colIdx) { 130.dp }
                            val cellText = rowCells.getOrNull(colIdx) ?: ""
                            Box(
                                modifier = Modifier
                                    .width(colWidth)
                                    .padding(horizontal = 10.dp)
                            ) {
                                MarkdownInlineText(
                                    text = cellText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                            }
                        }
                    }
                    if (rowIdx < rows.size - 1) {
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkdownBulletRow(item: MarkdownSegment.BulletItem) {
    val styled = remember(item.text) { parseInlineMarkdown(item.text) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (6 + item.indent * 14).dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = item.marker,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = styled,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun CollapsibleToolCard(
    summary: String,
    details: String,
    dotColor: Color,
    isRunning: Boolean,
    onCopy: () -> Unit
) {
    var expanded by remember(summary, details) { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                if (isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(10.dp),
                        strokeWidth = 1.6.dp,
                        color = StatusWarning
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(dotColor)
                    )
                }
                Text(
                    text = summary,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                    maxLines = if (expanded) 3 else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = IdeTheme.extras.muted,
                    modifier = Modifier.size(16.dp)
                )
            }

            AnimatedVisibility(
                visible = expanded && details.isNotBlank(),
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column {
                    HorizontalDivider(thickness = 0.5.dp, color = CodeBorder)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(CodeBg)
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .horizontalScroll(rememberScrollState())
                            ) {
                                Text(
                                    text = details,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.5.sp,
                                    lineHeight = 17.sp,
                                    color = SyntaxPlain
                                )
                            }
                            IconButton(
                                onClick = onCopy,
                                modifier = Modifier
                                    .padding(start = 6.dp)
                                    .size(22.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ContentCopy,
                                    contentDescription = "Copy details",
                                    tint = IdeTheme.extras.muted,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CleanCodeBlock(
    language: String,
    code: String,
    onCopy: () -> Unit
) {
    val highlighted = remember(code) { highlightCodeSyntax(code) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = CodeBg,
        border = BorderStroke(1.dp, CodeBorder)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CodeHeaderBg)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = language.ifBlank { "code" },
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onCopy)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ContentCopy,
                        contentDescription = "Copy code",
                        modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Copy",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp)
            ) {
                Text(
                    text = highlighted,
                    fontSize = 12.5.sp,
                    lineHeight = 19.sp
                )
            }
        }
    }
}

@Composable
private fun MinimalToolOutput(
    item: WorkspaceItem.ToolExecution,
    onCopy: (String) -> Unit
) {
    val output = buildString {
        append("$ ${item.command}")
        if (item.stdout.isNotBlank()) {
            append("\n\n")
            append(item.stdout)
        }
        if (item.stderr.isNotBlank()) {
            append("\n")
            append(item.stderr)
        }
    }
    val summary = when {
        item.isRunning -> "Running ${item.label}..."
        item.exitCode == 0 -> "Ran ${item.label}"
        else -> "Ran ${item.label} (exit ${item.exitCode ?: -1})"
    }
    val dotColor = when {
        item.isRunning -> StatusWarning
        item.exitCode == 0 -> StatusOnline
        else -> StatusError
    }
    CollapsibleToolCard(
        summary = summary,
        details = output,
        dotColor = dotColor,
        isRunning = item.isRunning,
        onCopy = { onCopy(output) }
    )
}

@Composable
private fun MinimalErrorNotice(item: WorkspaceItem.ErrorNotice) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = StatusError.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, StatusError.copy(alpha = 0.25f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.labelLarge,
                color = StatusError
            )
            Text(
                text = item.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun GeneratingRow(status: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(13.dp),
            strokeWidth = 1.8.dp,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = status.ifBlank { "Thinking..." },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private val EffortColors = listOf(
    Color(0xFFE54D2E), // Red (Low)
    Color(0xFFF5D90A), // Yellow (Med)
    Color(0xFF46A758), // Green (High)
    Color(0xFF3E63DD), // Blue (Max 1)
    Color(0xFF8E4EC6)  // Purple (Max 2)
)

private fun getEffortColor(stepFraction: Float): Color {
    val clamped = stepFraction.coerceIn(0f, 3f)
    return when {
        clamped <= 1f -> {
            val f = clamped
            Color(
                red = (1f - f) * 0.90f + f * 0.96f,
                green = (1f - f) * 0.30f + f * 0.85f,
                blue = (1f - f) * 0.18f + f * 0.04f,
                alpha = 1f
            )
        }
        clamped <= 2f -> {
            val f = clamped - 1f
            Color(
                red = (1f - f) * 0.96f + f * 0.27f,
                green = (1f - f) * 0.85f + f * 0.65f,
                blue = (1f - f) * 0.04f + f * 0.35f,
                alpha = 1f
            )
        }
        else -> {
            val f = clamped - 2f
            Color(
                red = (1f - f) * 0.27f + f * 0.55f,
                green = (1f - f) * 0.65f + f * 0.30f,
                blue = (1f - f) * 0.35f + f * 0.78f,
                alpha = 1f
            )
        }
    }
}

@Composable
private fun CustomColorEffortTrackSlider(
    selectedEffort: ReasoningEffort,
    onSelectEffort: (ReasoningEffort) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    var trackWidthPx by remember { mutableStateOf(0) }
    val stepTarget = selectedEffort.stepIndex.toFloat()

    val animatedStep by animateFloatAsState(
        targetValue = stepTarget,
        animationSpec = spring(
            dampingRatio = 0.82f,
            stiffness = 380f
        ),
        label = "sliderStep"
    )

    val activeColor = remember(animatedStep) {
        getEffortColor(animatedStep)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(34.dp)
            .onSizeChanged { trackWidthPx = it.width }
            .pointerInput(trackWidthPx) {
                detectTapGestures { offset ->
                    if (trackWidthPx > 0) {
                        val fraction = (offset.x / trackWidthPx.toFloat()).coerceIn(0f, 1f)
                        val step = (fraction * 3f).roundToInt().coerceIn(0, 3)
                        onSelectEffort(ReasoningEffort.fromStep(step))
                    }
                }
            }
            .pointerInput(trackWidthPx) {
                detectDragGestures { change, _ ->
                    change.consume()
                    if (trackWidthPx > 0) {
                        val fraction = (change.position.x / trackWidthPx.toFloat()).coerceIn(0f, 1f)
                        val step = (fraction * 3f).roundToInt().coerceIn(0, 3)
                        val newEffort = ReasoningEffort.fromStep(step)
                        if (newEffort != selectedEffort) {
                            onSelectEffort(newEffort)
                        }
                    }
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        val thumbRadiusDp = 11.dp
        val thumbRadiusPx = with(density) { thumbRadiusDp.toPx() }
        val usableWidthPx = (trackWidthPx - thumbRadiusPx * 2).coerceAtLeast(0f)
        val thumbOffsetPx = thumbRadiusPx + (animatedStep / 3f) * usableWidthPx

        // Background Track (pill container)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(22.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(Color(0xFF23252B))
        ) {
            // Filled Active Track on left of thumb
            if (thumbOffsetPx > 0f && trackWidthPx > 0) {
                val fillFraction = (thumbOffsetPx / trackWidthPx.toFloat()).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fillFraction)
                        .fillMaxSize()
                        .background(activeColor)
                )
            }

            // Discrete Circular Dots on the Track
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = thumbRadiusDp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                for (step in 0..3) {
                    val isPastOrCurrent = animatedStep >= step - 0.1f
                    val dotColor = if (isPastOrCurrent) {
                        Color.White.copy(alpha = 0.5f)
                    } else {
                        Color.White.copy(alpha = 0.35f)
                    }
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(dotColor)
                    )
                }
            }
        }

        // Circular Thumb (Floating crisp white circle with drop shadow)
        if (trackWidthPx > 0) {
            val thumbLeftDp = with(density) { (thumbOffsetPx - thumbRadiusPx).toDp() }
            Box(
                modifier = Modifier
                    .offset(x = thumbLeftDp)
                    .size(thumbRadiusDp * 2)
                    .shadow(elevation = 4.dp, shape = CircleShape)
                    .clip(CircleShape)
                    .background(Color.White)
            )
        }
    }
}

@Composable
private fun FloatingModelEffortDialog(
    baseModels: List<BaseModelGroup>,
    selectedBaseModel: BaseModelGroup,
    selectedEffort: ReasoningEffort,
    composedModelId: String,
    onDismiss: () -> Unit,
    onSelectBaseModel: (BaseModelGroup) -> Unit,
    onSelectEffort: (ReasoningEffort) -> Unit
) {
    var showModelList by remember { mutableStateOf(false) }

    val currentAccentColor = remember(selectedEffort) {
        getEffortColor(selectedEffort.stepIndex.toFloat())
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                ,
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shadowElevation = 16.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Top Row: Glowing ⚡ + Active Effort label + Model selector dropdown button
                val chevronRotation by animateFloatAsState(
                    targetValue = if (showModelList) 90f else 0f,
                    animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f),
                    label = "chevronRotation"
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(currentAccentColor.copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Bolt,
                                contentDescription = "Reasoning effort",
                                tint = currentAccentColor,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                        Text(
                            text = selectedEffort.label,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = currentAccentColor
                        )
                    }

                    Surface(
                        onClick = { showModelList = !showModelList },
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = selectedBaseModel.displayName,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Icon(
                                imageVector = Icons.Rounded.ChevronRight,
                                contentDescription = "Switch base model",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .size(16.dp)
                                    .rotate(chevronRotation)
                            )
                        }
                    }
                }

                // Expandable Clean Base Model List with buttery smooth spring animation
                if (showModelList) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .verticalScroll(rememberScrollState())
                            .padding(6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        baseModels.forEach { group ->
                            val isSelected = group.baseId == selectedBaseModel.baseId
                            Surface(
                                onClick = {
                                    onSelectBaseModel(group)
                                    showModelList = false
                                },
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                } else {
                                    Color.Transparent
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = group.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = "Selected",
                                            tint = currentAccentColor,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Bottom Row: Custom Color Map Discrete Slider (Red -> Yellow -> Green -> Blue -> Purple)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    CustomColorEffortTrackSlider(
                        selectedEffort = selectedEffort,
                        onSelectEffort = onSelectEffort,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        ReasoningEffort.entries.forEach { effort ->
                            val active = effort == selectedEffort
                            val labelColor = if (active) getEffortColor(effort.stepIndex.toFloat()) else IdeTheme.extras.muted
                            Text(
                                text = effort.shortLabel,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                color = labelColor,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { onSelectEffort(effort) }
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                // Subtle active composed ID hint
                Text(
                    text = composedModelId,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = IdeTheme.extras.muted,
                    modifier = Modifier.align(Alignment.End)
                )
            }
        }
    }
}
