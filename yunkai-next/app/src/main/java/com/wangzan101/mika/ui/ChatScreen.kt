package com.wangzan101.mika.ui

import androidx.compose.foundation.gestures.scrollBy
import com.wangzan101.mika.ui.components.keepsKeyboard
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.R
import com.wangzan101.mika.chat.SharedFile
import com.wangzan101.mika.confirm.ConfirmationCopy
import com.wangzan101.mika.settings.Prerequisites
import com.wangzan101.mika.takeover.Takeover
import com.wangzan101.mika.task.TaskService
import com.wangzan101.mika.ui.components.BarIconButton
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.GlassBackdrop
import com.wangzan101.mika.ui.components.GlassBar
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaCard
import com.wangzan101.mika.ui.components.PageBackground
import com.wangzan101.mika.ui.components.TopBar
import com.wangzan101.mika.ui.components.cardSurface
import com.wangzan101.mika.ui.components.glassSource
import com.wangzan101.mika.ui.components.rememberGlassBackdrop
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaSize
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.viewer.FileViewer
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalTime

/**
 * 对话页:顶栏(左边打开侧栏、中间对话标题和此刻在做什么、右边新对话),对话在顶栏和玻璃输入栏之间滚动。
 * 进行中是一行状态加一条竖线步骤,不套卡片;要你处理时停在当前步,下面一张白卡;完成后过程收成一行,回答直接排在背景上。
 * Agent 的虚拟屏以悬浮小窗([AgentPip])盖在对话上,不占布局。
 */
@Composable
fun ChatScreen(
    vm: ChatViewModel,
    check: Prerequisites.Check,
    frames: StateFlow<Bitmap?>,
    webOpen: StateFlow<Boolean>,
    reduceMotion: Boolean,
    /** 左上角菜单:打开侧栏。 */
    onOpenDrawer: () -> Unit,
    /** 被拦下的提示里的「去设置」。 */
    openSettings: () -> Unit,
    openRun: (String) -> Unit = {},
    /** 任务要用手机而手机操作没准备好:最后一轮下面放一行去设置。 */
    phoneSetupNeeded: Boolean = false,
    openPhoneSetup: () -> Unit = {},
    /** 按运行记录取依据的来处(「来源」胶囊和点开后的截图);读盘的活由调用方放到后台线程。 */
    loadSource: suspend (runId: String) -> EvidenceSource? = { null },
    openSource: (runId: String?, legacyText: String?) -> Unit = { _, _ -> },
) {
    val ui by vm.ui.collectAsState()
    val others by vm.otherRunning.collectAsState()
    val live by frames.collectAsState()
    val webLive by webOpen.collectAsState()
    // 任务一结束虚拟屏就释放了;完成时小窗还要停留几秒打勾,继续显示最后一帧
    var lastFrame by remember { mutableStateOf<Bitmap?>(null) }
    var lastFrameWasWeb by remember { mutableStateOf(false) }
    LaunchedEffect(live, webLive, ui.pip.mode) {
        live?.let {
            lastFrame = it
            if (webLive) lastFrameWasWeb = true
            else if (ui.pip.mode != PipMode.Done) lastFrameWasWeb = false
        }
    }
    val frame = live ?: lastFrame.takeIf { ui.pip.visible && ui.pip.mode == PipMode.Done }
    val webContent = if (ui.pip.mode == PipMode.Done) lastFrameWasWeb else webLive
    var input by rememberSaveable { mutableStateOf("") }
    var composerHeight by rememberSaveable { mutableIntStateOf(0) }
    var topHeight by rememberSaveable { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // 只有原本跟着最新消息时才继续跟随。这个状态与列表位置一起跨页面保存。
    var followingTail by rememberSaveable { mutableStateOf(true) }
    var previousScrollMarker by rememberSaveable { mutableStateOf<String?>(null) }
    var previousConversation by rememberSaveable { mutableStateOf<String?>(null) }
    val tailThresholdPx = with(density) { 240.dp.roundToPx() }
    LaunchedEffect(listState, tailThresholdPx) {
        var wasScrolling = false
        snapshotFlow { listState.isScrollInProgress to listState.nearTail(tailThresholdPx) }.collect { (scrolling, nearTail) ->
            if (scrolling || wasScrolling) followingTail = nearTail
            wasScrolling = scrolling
        }
    }

    // 返回聊天时同一条内容不重新滚动；新进展仅在跟随末尾时滚动。
    val last = ui.exchanges.lastOrNull()
    val scrollMarker = "${ui.conversationId}:${ui.exchanges.size}:${last?.steps?.size}:${last?.steers?.size}:${last?.status?.hashCode()}:${ui.blocked?.hashCode()}"
    LaunchedEffect(scrollMarker) {
        if (previousScrollMarker == scrollMarker) return@LaunchedEffect
        val firstEntry = previousScrollMarker == null
        val changedConversation = previousConversation != ui.conversationId
        previousScrollMarker = scrollMarker
        previousConversation = ui.conversationId
        if (changedConversation) followingTail = true
        if (!firstEntry && !changedConversation && !followingTail) return@LaunchedEffect
        val count = listState.layoutInfo.totalItemsCount
        if (count > 0) {
            if (firstEntry || changedConversation || reduceMotion) listState.scrollToItem(count - 1)
            else listState.animateScrollToItem(count - 1)
        }
    }
    // 流式文字增长时跟住底部；用户主动向上翻看过程时不拉回去。
    LaunchedEffect(last?.draft?.length) {
        if (last?.draft.isNullOrEmpty() || !followingTail || listState.isScrollInProgress) return@LaunchedEffect
        val layout = listState.layoutInfo
        val end = layout.totalItemsCount - 1
        val draft = layout.visibleItemsInfo.firstOrNull { it.index == end } ?: return@LaunchedEffect
        val tailGap = draft.offset + draft.size - layout.viewportEndOffset
        if (tailGap > with(density) { 240.dp.roundToPx() }) return@LaunchedEffect
        val viewport = layout.viewportEndOffset - layout.viewportStartOffset
        val offset = (draft.size - viewport + with(density) { 12.dp.roundToPx() }).coerceAtLeast(0)
        listState.scrollToItem(end, offset)
    }

    // 输入栏变高(多半是输入法弹起,也可能是附件、多行输入):原本跟着最新消息时,对话流一起往上顶同样的距离,
    // 最后一条仍贴着输入栏;用户正往上翻看时不动。变矮时列表到底自己会收回来。
    var lastComposerHeight by remember { mutableIntStateOf(composerHeight) }
    LaunchedEffect(composerHeight) {
        val grown = composerHeight - lastComposerHeight
        lastComposerHeight = composerHeight
        if (grown > 0 && followingTail && !listState.isScrollInProgress) listState.scrollBy(grown.toFloat())
    }

    val c = MikaTheme.colors
    val backdrop = rememberGlassBackdrop()
    PageBackground {
        LazyColumn(
            Modifier.fillMaxSize().glassSource(backdrop),
            state = listState,
            contentPadding = with(density) {
                PaddingValues(
                    start = 16.dp, end = 16.dp, top = topHeight.toDp() + 4.dp, bottom = composerHeight.toDp() + 12.dp,
                )
            },
        ) {
            if (ui.exchanges.isEmpty() && ui.blocked == null) {
                item("empty") { EmptyState(ui.conversationId) { input = it } }
            }
            ui.exchanges.forEachIndexed { i, ex ->
                val backgroundNote = i == ui.exchanges.lastIndex && ui.operatingPhone
                exchange(i, ex, ui.conversationId, ui.takeoverStage, vm, reduceMotion, backgroundNote, openRun, loadSource, openSource)
            }
            if (phoneSetupNeeded && ui.exchanges.isNotEmpty()) item("phone-setup") { PhoneSetupLine(openPhoneSetup) }
            ui.blocked?.let { problems ->
                item("blocked") {
                    NeedCard(
                        stringResource(R.string.chat_cannot_start),
                        stringResource(R.string.chat_missing, problems.joinToString(stringResource(R.string.list_sep)) { StatusCopy.banner(it).title }),
                    ) {
                        CardButton(stringResource(R.string.ok_got_it), onClick = vm::dismissBlocked)
                        CardButton(stringResource(R.string.chat_go_settings), primary = true, onClick = { vm.dismissBlocked(); openSettings() })
                    }
                }
            }
        }

        val sub = ChatCopy.subtitle(last, last?.let(vm::livePlace))
        Column(Modifier.onSizeChanged { topHeight = it.height }) {
            TopBar(
                ChatCopy.APP_TITLE,
                left = MikaIcons.Menu, leftLabel = stringResource(R.string.chat_menu), onLeft = onOpenDrawer,
                // 别的对话里还有任务在跑(并行时):菜单上一个小圆点,侧栏「进行中」里能切过去
                leftBadge = others.isNotEmpty(),
                right = { BarIconButton(MikaIcons.New, stringResource(R.string.chat_new), { vm.newConversation() }, enabled = vm.canSwitch()) },
                subtitle = sub?.first,
                subtitleColor = when (sub?.second) {
                    ChatCopy.Tone.Accent -> c.accent
                    ChatCopy.Tone.Warn -> c.warn
                    null -> Color.Unspecified
                },
            )
        }

        Composer(
            Modifier.align(Alignment.BottomCenter).onSizeChanged { composerHeight = it.height },
            backdrop = backdrop,
            text = input,
            onText = { input = it },
            mode = ui.composer,
            hasHistory = ui.exchanges.isNotEmpty(),
            onSend = { if (vm.send(input, check)) input = "" },
            onStop = vm::cancel,
            attachPanel = if (ui.attachEnabled && ui.composer == ComposerMode.Idle) ({
                AttachPanelHost(ui.attachments, onPicked = vm::attach, onToggle = vm::toggleAttachment)
            }) else null,
            attachments = ui.attachments,
            importing = ui.importing,
            rejected = ui.rejectedAttachments,
            overLimit = ui.overLimit,
            reduceMotion = reduceMotion,
            onRemove = vm::removeAttachment,
            onDismissRejected = vm::dismissRejected,
        )

        AgentPip(
            pip = ui.pip,
            frame = frame,
            webContent = webContent,
            reduceMotion = reduceMotion,
            topInset = topHeight.toFloat(),
            bottomInset = composerHeight.toFloat(),
            onExpand = vm::expandPip,
            onCollapse = vm::collapsePip,
            // 要你处理时点小窗:收起,滚到要你处理的那张卡片(在最后一问一答里,它后面只可能还有文件卡片和去设置那一行)
            onAttention = {
                vm.attendPip()
                val count = listState.layoutInfo.totalItemsCount
                val after = (if (last?.files?.isNotEmpty() == true) 1 else 0) + (if (phoneSetupNeeded) 1 else 0)
                val card = count - 1 - after
                if (card >= 0) scope.launch { if (reduceMotion) listState.scrollToItem(card) else listState.animateScrollToItem(card) }
            },
        )
    }
}

/** 最后一项可能是一段很长的答复，仅仅看到它的开头不代表用户正在看末尾。 */
private fun LazyListState.nearTail(thresholdPx: Int): Boolean {
    val layout = layoutInfo
    val end = layout.totalItemsCount - 1
    val tail = layout.visibleItemsInfo.firstOrNull { it.index == end } ?: return false
    return tail.offset + tail.size - layout.viewportEndOffset <= thresholdPx
}

// ---------- 空对话 ----------

/** 问候、一句「想让 Mika 做什么？」和一列示例;点示例把它填进输入框,改好了再发。 */
@Composable
private fun EmptyState(conversation: String?, onPick: (String) -> Unit) {
    val c = MikaTheme.colors
    val hour = remember(conversation) { LocalTime.now().hour }
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 52.dp)) {
            Text(ChatCopy.greeting(hour), style = MikaType.LargeTitle, color = c.ink)
            Text(ChatCopy.EMPTY_PROMPT, Modifier.padding(top = 6.dp), style = EmptyLead, color = c.ink2)
        }
        MikaCard(Modifier.padding(top = 28.dp)) {
            ChatCopy.examples.forEachIndexed { i, ex ->
                ExampleRow(exampleIcon(ex.kind), ex.text, first = i == 0) { onPick(ex.text) }
            }
        }
    }
}

private fun exampleIcon(kind: ChatCopy.ExampleKind): ImageVector = when (kind) {
    ChatCopy.ExampleKind.App -> MikaIcons.Apps
    ChatCopy.ExampleKind.Web -> MikaIcons.Globe
    ChatCopy.ExampleKind.File -> MikaIcons.Doc
    ChatCopy.ExampleKind.Schedule -> MikaIcons.Clock
    ChatCopy.ExampleKind.Device -> MikaIcons.Alarm
}

@Composable
private fun ExampleRow(icon: ImageVector, text: String, first: Boolean, onClick: () -> Unit) {
    val c = MikaTheme.colors
    Row(
        Modifier.fillMaxWidth()
            .then(if (first) Modifier else Modifier.drawBehind {
                val y = 0.25.dp.toPx()
                drawLine(c.hair, Offset(52.dp.toPx(), y), Offset(size.width, y), strokeWidth = 0.5.dp.toPx())
            })
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, null, Modifier.size(MikaSize.Icon), tint = c.ink2)
        Text(text, Modifier.weight(1f), style = ExampleText, color = c.ink)
    }
}

// ---------- 一问一答 ----------

private fun LazyListScope.exchange(
    index: Int, ex: Exchange, conversationId: String?, stage: Takeover.Stage, vm: ChatViewModel, reduceMotion: Boolean, backgroundNote: Boolean,
    openRun: (String) -> Unit, loadSource: suspend (String) -> EvidenceSource?, openSource: (String?, String?) -> Unit,
) {
    if (ex.goal.isNotEmpty()) item("u$index") { UserBubble(ex.goal, conversationId) }
    val active = ex.status.isActive()
    val answering = ex.status == ExchangeStatus.Working && !ex.draft.isNullOrBlank()
    if (active) {
        if (!answering) item("s$index") { WorkBlock(ex, reduceMotion, backgroundNote) }
    } else if (ex.steps.isNotEmpty() || ex.runId != null) {
        item("s$index") { DoneLine(ex, reduceMotion, openRun) }
    }
    // 任务进行中插的话:用户的原话,下面一行小字说它的去向
    ex.steers.forEach { n -> item("m$index-${n.id}") { UserBubble(n.text, conversationId, steerLabel(n.state)) } }
    item("r$index") { StatusCard(ex.status, stage, vm, ex.runId, loadSource, openSource) }
    ex.draft?.takeIf(String::isNotBlank)?.let { draft -> item("d$index") { DraftText(draft, ex.waitingForModel) } }
    if (ex.files.isNotEmpty()) item("f$index") { FileCards(ex.files) }
    learningNotes(index, ex, vm)
}

private fun ExchangeStatus.isActive() =
    this !is ExchangeStatus.Done && this !is ExchangeStatus.Failed && this !is ExchangeStatus.Cancelled

/**
 * 用户的话:蓝色气泡,圆角 20、右下尾角 6,最宽八成。[note] 是插话的去向。
 * 带着用户此刻的屏幕时上面是截图缩略图和「你此刻的屏幕 · App · 时刻」,附言在下面的气泡里(见 [UserMessage])。
 * 带着附件时上面是照片格子和文件卡片(见 [SentAttachments]),点开在 App 里的查看页看;写的话在下面另起一个气泡,路径那几行不显示。
 */
@Composable
internal fun UserBubble(text: String, conversationId: String?, note: String? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val attached = remember(text) { AttachmentMessage.parse(text) }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 14.dp), contentAlignment = Alignment.CenterEnd) {
        val max = maxWidth * 0.8f
        Column(horizontalAlignment = Alignment.End) {
            if (attached.hasAttachments) {
                SentAttachments(attached, conversationId, max) { item ->
                    val id = conversationId ?: return@SentAttachments
                    scope.launch { if (!AttachFiles.openSent(context, id, item.path)) Toast.makeText(context, AttachCopy.GONE, Toast.LENGTH_SHORT).show() }
                }
            }
            if (attached.hasAttachments && attached.words.isNotEmpty()) Spacer(Modifier.size(6.dp))
            if (!attached.hasAttachments || attached.words.isNotEmpty()) UserMessage(conversationId, if (attached.hasAttachments) attached.words else text, ChatThumbWidth) { words ->
                // 长按选中、复制自己说过的话;蓝底上选中的底色用白色半透明,把手照旧是蓝色(画在气泡外面)
                val colors = MikaTheme.colors
                CompositionLocalProvider(LocalTextSelectionColors provides TextSelectionColors(colors.accent, colors.onAccent.copy(alpha = 0.35f))) {
                    SelectionContainer(Modifier.testTag("selectable-user")) {
                        Text(
                            words,
                            Modifier.widthIn(max = max)
                                .background(colors.accent, MikaShapes.UserBubble)
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            style = BubbleText, color = colors.onAccent,
                        )
                    }
                }
            }
            note?.let { Text(it, Modifier.padding(top = 3.dp, end = 4.dp), style = MikaType.Hint, color = MikaTheme.colors.ink3) }
        }
    }
}

/** 对话页里截图缩略图的宽。 */
private val ChatThumbWidth = 110.dp

/** 正在生成的回答:和完成后的回答一样放在淡灰底上。 */
@Composable
private fun DraftText(text: String, generating: Boolean) {
    MarkdownText(
        text, AnswerText.copy(color = MikaTheme.colors.ink),
        Modifier.fillMaxWidth().padding(bottom = 12.dp).answerSurface(), streaming = generating,
    )
}

/** Mika 回答的底:很淡的灰,左下尾角。 */
@Composable
private fun Modifier.answerSurface(): Modifier =
    background(MikaTheme.colors.answer, MikaShapes.AnswerBubble).padding(horizontal = 14.dp, vertical = 12.dp)

// ---------- 进度 ----------

/**
 * 进行中:一行状态(转圈 + 此刻的进展 + 用时),下面一条竖线步骤(做完的灰点、当前的蓝点;停下来等你时当前步是橙点)。
 * 在后台操作手机时最下面一行小字。
 */
@Composable
private fun WorkBlock(ex: Exchange, reduceMotion: Boolean, backgroundNote: Boolean) {
    val c = MikaTheme.colors
    val paused = ChatCopy.paused(ex.status)
    val elapsed = rememberElapsed(ex.startedAt)
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Spinner(
                Modifier.size(16.dp), spinning = !paused && !reduceMotion,
                track = if (paused) c.warnSoft else c.accentSoft, head = if (paused) c.warnSolid else c.accent,
            )
            Text(
                ChatCopy.progressLine(ex), Modifier.weight(1f), style = NowText, color = c.ink,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (elapsed != null) Text(ChatCopy.clock(elapsed), style = ClockText, color = c.ink3)
        }
        if (ex.steps.isNotEmpty()) StepLine(ex.steps, live = true, paused = paused)
        if (backgroundNote) {
            Row(
                Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(MikaIcons.Eye, null, Modifier.size(15.dp), tint = c.ink3)
                Text(ChatCopy.BACKGROUND_NOTE, style = NoteText, color = c.ink3)
            }
        }
    }
}

/** 从 [startedAt] 起的用时,每秒更新;不知道开始时刻(为 0)时为 null。 */
@Composable
private fun rememberElapsed(startedAt: Long): Long? {
    if (startedAt <= 0L) return null
    val now by produceState(System.currentTimeMillis(), startedAt) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000L - value % 1_000L)
        }
    }
    return (now - startedAt).coerceAtLeast(0L)
}

private enum class Dot { Done, Current, Paused, Dim }

/**
 * 竖线步骤。[live] 时最后一步按任务此刻的状态标蓝点或橙点;在跑时只列最近 [ChatCopy.VISIBLE_STEPS] 步,
 * 前面的收成一行,点开看全部。
 */
@Composable
private fun StepLine(steps: List<StepRow>, live: Boolean, paused: Boolean) {
    val c = MikaTheme.colors
    var all by rememberSaveable { mutableStateOf(!live) }
    val hidden = if (all) 0 else (steps.size - ChatCopy.VISIBLE_STEPS).coerceAtLeast(0)
    Column(
        Modifier.padding(top = 6.dp).fillMaxWidth().drawBehind {
            val x = StepRail.toPx()
            drawLine(c.hair, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.5.dp.toPx())
        },
    ) {
        if (hidden > 0) {
            StepItem(ChatCopy.earlierSteps(hidden), "", Dot.Dim, current = false, link = true, onClick = { all = true })
        }
        steps.drop(hidden).forEachIndexed { i, row ->
            val isLast = hidden + i == steps.lastIndex
            val dot = when {
                live && isLast && paused -> Dot.Paused
                live && isLast && row.state == StepState.Run -> Dot.Current
                row.state == StepState.Dim -> Dot.Dim
                else -> Dot.Done
            }
            StepItem(row.text, row.subtitle, dot, current = dot == Dot.Current || dot == Dot.Paused)
        }
    }
}

@Composable
private fun StepItem(text: String, subtitle: String, dot: Dot, current: Boolean, link: Boolean = false, onClick: (() -> Unit)? = null) {
    val c = MikaTheme.colors
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClickLabel = stringResource(R.string.chat_expand_steps), onClick = onClick) else Modifier)
            .heightIn(min = 28.dp).padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(StepRail * 2), contentAlignment = Alignment.Center) { StepDot(dot) }
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text, style = if (current) StepCurrentText else StepText,
                color = when {
                    link -> c.accent
                    current -> c.ink
                    else -> c.ink2
                },
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotEmpty()) Text(subtitle, style = MikaType.Hint, color = c.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 步骤点:8dp 实心;当前步外面一圈 3dp 的淡色光环。 */
@Composable
private fun StepDot(dot: Dot) {
    val c = MikaTheme.colors
    val (fill, ring) = when (dot) {
        Dot.Done -> c.ink3 to null
        Dot.Dim -> c.hair to null
        Dot.Current -> c.accent to c.accentSoft
        Dot.Paused -> c.warnSolid to c.warnSoft
    }
    // 底色先盖住竖线,点才是干净的
    val bg = c.bg
    Box(
        Modifier.size(14.dp).drawBehind {
            val center = Offset(size.width / 2, size.height / 2)
            if (ring != null) {
                drawCircle(bg, 7.dp.toPx(), center)
                drawCircle(ring, 7.dp.toPx(), center)
            }
            drawCircle(fill, 4.dp.toPx(), center)
        },
    )
}

/** 转圈:淡色圆环,顶上一段实色。[spinning] 为假(关了动画、停下来等你)时停在原处。 */
@Composable
private fun Spinner(modifier: Modifier, spinning: Boolean, track: Color, head: Color) {
    val turn = if (!spinning) 0f else rememberInfiniteTransition(label = "spin").animateFloat(
        0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "spin",
    ).value
    Box(
        modifier.graphicsLayer { rotationZ = turn }
            .border(2.dp, track, CircleShape)
            .border(
                2.dp,
                Brush.sweepGradient(0f to Color.Transparent, 0.62f to Color.Transparent, 0.63f to head, 0.88f to head, 0.89f to Color.Transparent),
                CircleShape,
            ),
    )
}

/**
 * 结束后过程收成一行:「做了 N 步 · 用时」(点了在下面展开步骤)和「查看过程」(进时间线)。
 */
@Composable
private fun DoneLine(ex: Exchange, reduceMotion: Boolean, openRun: (String) -> Unit) {
    val c = MikaTheme.colors
    var open by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Row(
            Modifier.heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (ex.status is ExchangeStatus.Done) Icon(MikaIcons.Check, null, Modifier.size(16.dp), tint = c.ink3)
            if (ex.steps.isNotEmpty()) {
                Text(
                    stepsSummary(ex),
                    Modifier.clickable(onClickLabel = if (open) stringResource(R.string.chat_collapse_steps) else stringResource(R.string.chat_expand_steps)) { open = !open },
                    style = DoneText, color = c.ink3,
                )
            }
            ex.runId?.let { id ->
                Text(
                    // 灰色斜体加下划线:和回答融在一起,又看得出能点
                    stringResource(R.string.chat_view_process), Modifier.clip(MikaShapes.Pill).clickable(role = Role.Button) { openRun(id) }.padding(horizontal = 4.dp, vertical = 4.dp),
                    style = DoneText.copy(fontStyle = FontStyle.Italic, textDecoration = TextDecoration.Underline), color = c.ink3,
                )
            }
        }
        Reveal(open && ex.steps.isNotEmpty(), reduceMotion) { StepLine(ex.steps, live = false, paused = false) }
    }
}

/** 点开的内容:平时展开收起带动画,关了动画时直接出现、消失。 */
@Composable
private fun Reveal(visible: Boolean, reduceMotion: Boolean, content: @Composable () -> Unit) {
    if (reduceMotion) {
        if (visible) content()
    } else {
        AnimatedVisibility(visible, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) { content() }
    }
}

// ---------- 状态与结果 ----------

@StringRes
fun takeoverReasonLabel(reason: String): Int? = when (reason) {
    "captcha" -> R.string.takeover_reason_captcha
    "login" -> R.string.takeover_reason_login
    "access_blocked" -> R.string.takeover_reason_access_blocked
    "payment" -> R.string.takeover_reason_payment
    "decision" -> R.string.takeover_reason_decision
    else -> null
}

/** 一问一答此刻的状态:要你做的事是一张白卡([NeedCard]),结果直接排在背景上。 */
@Composable
private fun StatusCard(
    status: ExchangeStatus, stage: Takeover.Stage, vm: ChatViewModel, runId: String?,
    loadSource: suspend (String) -> EvidenceSource?, openSource: (String?, String?) -> Unit,
) {
    when (status) {
        ExchangeStatus.Working -> Unit
        ExchangeStatus.WaitingUnlock -> NeedCard(stringResource(R.string.chat_wait_unlock), stringResource(R.string.chat_unlock_body))
        is ExchangeStatus.NeedsTakeover -> {
            val label = takeoverReasonLabel(status.reason)
            val title = if (label != null) stringResource(R.string.takeover_title_reason, stringResource(label)) else stringResource(R.string.takeover_title)
            val handedOver = stage == Takeover.Stage.HandedOver
            val text = if (handedOver) stringResource(R.string.takeover_handed_over_hint) else status.message
            // 付款的商品、金额、地址单独成行:这张卡片就是付款前的确认
            val body = listOfNotNull(text, StatusCopy.paymentLines(status.item, status.amount, status.address)).joinToString("\n")
            NeedCard(title, body, footer = { TextLink(stringResource(R.string.action_cancel_task), vm::cancel) }) {
                if (handedOver) {
                    CardButton(stringResource(R.string.action_finished_myself), onClick = vm::finishedMyself)
                    CardButton(stringResource(R.string.action_hand_back), primary = true, onClick = vm::handBack)
                } else {
                    CardButton(stringResource(R.string.action_skip), onClick = vm::skip)
                    CardButton(stringResource(R.string.action_take_over), primary = true, onClick = vm::takeOver)
                }
            }
        }
        is ExchangeStatus.NeedsInput -> {
            val options = status.options.orEmpty()
            val note = stringResource(R.string.no_answer_note, TaskService.INPUT_TIMEOUT_MINUTES)
            NeedCard(
                stringResource(R.string.input_title), stringResource(R.string.chat_input_body, status.prompt, note),
                options = if (options.isEmpty()) null else ({
                    options.forEach { o -> MikaButton(o, { vm.answer(o) }, Modifier.fillMaxWidth(), kind = ButtonKind.Tonal, size = ButtonSize.Medium) }
                }),
            ) {
                if (options.isEmpty()) CardButton(stringResource(R.string.action_cancel_task), onClick = vm::cancel)
            }
        }
        // 只有花钱的操作要你同意,每次只同意这一次
        is ExchangeStatus.NeedsConfirmation -> NeedCard(status.title, status.body + "\n" + ConfirmationCopy.NOTE) {
            CardButton(stringResource(R.string.chat_refuse), onClick = { vm.decline(status.key) })
            CardButton(stringResource(R.string.chat_allow_once), primary = true, onClick = { vm.approve(status.key) })
        }
        is ExchangeStatus.Paused -> NeedCard(stringResource(R.string.chat_paused), status.why) {
            CardButton(stringResource(R.string.action_cancel_task), onClick = vm::cancel)
            CardButton(stringResource(R.string.chat_continue), primary = true, onClick = vm::resume)
        }
        is ExchangeStatus.Done -> Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).answerSurface()) {
            status.unverifiedLine?.let { line ->
                Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(18.dp).background(MikaTheme.colors.warnSoft, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(MikaIcons.Exclaim, null, Modifier.size(11.dp), tint = MikaTheme.colors.warn)
                    }
                    Text(line, style = MikaType.Caption, fontWeight = FontWeight.SemiBold, color = MikaTheme.colors.ink2)
                }
            }
            SelectableAnswer { MarkdownText(status.answer, AnswerText.copy(color = MikaTheme.colors.ink)) }
            // 来源:旧记录里模型写的依据文字,或运行记录里代码记下的来源;都没有就不显示
            if (status.evidence != null || runId != null) SourceChip(status.evidence, runId, loadSource, openSource)
        }
        is ExchangeStatus.Failed -> Column(Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).background(MikaTheme.colors.bad, CircleShape))
                Text(stringResource(R.string.chat_not_done), style = MikaType.BodyStrong, color = MikaTheme.colors.ink)
            }
            Text(status.reason, Modifier.padding(top = 4.dp), style = CardBody, color = MikaTheme.colors.ink2)
        }
        // 停止后补上的交代当作这一轮的回答,排在「已停止」下面;还在写时一行小字
        is ExchangeStatus.Cancelled -> Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
            Text(stringResource(R.string.chat_stopped), Modifier.padding(start = 2.dp), style = MikaType.Body, color = MikaTheme.colors.ink2)
            if (status.summarizing) Text(
                StatusCopy.STOP_SUMMARIZING, Modifier.padding(start = 2.dp, top = 4.dp), style = MikaType.Caption, color = MikaTheme.colors.ink3,
            )
            status.summary?.let { summary ->
                Column(Modifier.fillMaxWidth().padding(top = 8.dp).answerSurface()) {
                    SelectableAnswer { MarkdownText(summary, AnswerText.copy(color = MikaTheme.colors.ink)) }
                }
            }
        }
    }
}

/**
 * 回答的文字可以长按选中、用系统的复制条复制;回答里的链接照旧点开。只包住文字,下面的来源等入口不在选区里。
 * 选区的把手在单独的弹出窗口里,拖把手不会带出侧栏;长按后接着拖选的移动由选区消费,侧栏和列表也不跟着动。
 */
@Composable
private fun SelectableAnswer(content: @Composable () -> Unit) {
    SelectionContainer(Modifier.testTag("selectable-answer")) { content() }
}

/** 回答下面的「来源」小胶囊,只是入口;依据和截图在独立页面看。 */
@Composable
private fun SourceChip(text: String?, runId: String?, loadSource: suspend (String) -> EvidenceSource?, openSource: (String?, String?) -> Unit) {
    val source by produceState<EvidenceSource?>(null, runId) { value = runId?.let { loadSource(it) } }
    if (text == null && source?.items.isNullOrEmpty() && source?.screenshots.isNullOrEmpty()) return
    val c = MikaTheme.colors
    Row(
        Modifier.padding(top = 12.dp).heightIn(min = 30.dp)
            .cardSurface(c, MikaShapes.Pill).clip(MikaShapes.Pill)
            .clickable(onClickLabel = stringResource(R.string.chat_view_source)) { openSource(runId, text) }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(MikaIcons.Link, null, Modifier.size(15.dp), tint = c.ink2)
        Text(sourceLabel(source), style = PillText, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * 「需要你」卡片(要你处理、提问、确认、暂停、锁屏、被拦下):白卡,标题前一个橙色圆点,下面说明;
 * [options] 是一列可点的答案;按钮并排,次要的在左(浅色)、主要的在右(蓝色);[footer] 在最下面。
 */
@Composable
private fun NeedCard(
    title: String,
    body: String,
    footer: (@Composable () -> Unit)? = null,
    options: (@Composable () -> Unit)? = null,
    buttons: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = MikaTheme.colors
    MikaCard(Modifier.padding(top = 8.dp, bottom = 12.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).background(c.warnSolid, CircleShape))
                Text(title, style = NeedTitle, color = c.ink)
            }
            Text(body, Modifier.padding(top = 6.dp), style = CardBody, color = c.ink2)
            if (options != null) {
                Column(Modifier.padding(top = 14.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) { options() }
            }
            if (buttons != null) {
                Row(Modifier.padding(top = 14.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { buttons() }
            }
            footer?.let { Box(Modifier.padding(top = 10.dp).fillMaxWidth(), contentAlignment = Alignment.Center) { it() } }
        }
    }
}

@Composable
private fun RowScope.CardButton(text: String, primary: Boolean = false, onClick: () -> Unit) {
    MikaButton(
        text, onClick, Modifier.weight(1f),
        kind = if (primary) ButtonKind.Primary else ButtonKind.Tonal, size = ButtonSize.Medium,
    )
}

@Composable
private fun TextLink(text: String, onClick: () -> Unit) {
    Text(text, Modifier.clip(MikaShapes.Pill).clickable(onClick = onClick).padding(4.dp), style = MikaType.Caption, color = MikaTheme.colors.ink3)
}

/** 导出到「下载/Mika」的文件:小卡片,名字、大小和存放处;点了能看的在 App 里打开([FileViewer]),其余交给系统里能打开它的 App。 */
@Composable
private fun FileCards(files: List<SharedFile>) {
    val context = LocalContext.current
    val c = MikaTheme.colors
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (f in files) {
            Row(
                Modifier.fillMaxWidth().cardSurface(c, MikaShapes.Card).clip(MikaShapes.Card)
                    .clickable(onClickLabel = stringResource(R.string.chat_open), role = Role.Button) { openShared(context, f) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(36.dp).background(c.neutralSoft, FileTileShape), contentAlignment = Alignment.Center) {
                    Icon(MikaIcons.Doc, null, Modifier.size(20.dp), tint = c.ink)
                }
                Column(Modifier.weight(1f)) {
                    Text(f.name, style = FileName, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${ChatViewModel.sizeLabel(f.size)} · ${f.savedAs.substringBeforeLast('/')}",
                        style = FileMeta, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(MikaIcons.Chevron, null, Modifier.size(MikaSize.SmallIcon), tint = c.ink3)
            }
        }
    }
}

private fun openShared(context: Context, f: SharedFile) = FileViewer.open(context, Uri.parse(f.uri), f.name)

// ---------- 输入栏 ----------

/** 「＋」面板:关着、开着、正让给键盘(点了输入框:键盘升起来时面板跟着矮下去,键盘到位再关)。 */
private enum class Panel { Closed, Open, Yielding }

/**
 * 玻璃胶囊输入栏:左边「＋」,右边发送(没内容时灰);任务在跑时多一个停止,输入框的灰字提示插话会交给当前任务。
 * 「＋」点开的附件面板([attachPanel])在输入栏下面、占键盘那块位置,和键盘互换:开面板收键盘,
 * 再点「＋」(这时是 ×)或点输入框就收起面板、换成键盘;返回先收面板。待发的附件在输入栏上方一排([PendingTray])。
 */
@Composable
internal fun Composer(
    modifier: Modifier,
    text: String,
    onText: (String) -> Unit,
    mode: ComposerMode,
    hasHistory: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    /** 沙箱就绪、没有任务在跑时才有「＋」;点开是这块面板。 */
    attachPanel: (@Composable () -> Unit)? = null,
    attachments: List<PendingAttachment> = emptyList(),
    importing: Boolean = false,
    rejected: List<String> = emptyList(),
    overLimit: Int = 0,
    onRemove: (String) -> Unit = {},
    onDismissRejected: () -> Unit = {},
    /** 输入栏后面那层内容的画面;为 null 时输入栏不做模糊、用不透明底。 */
    backdrop: GlassBackdrop? = null,
    reduceMotion: Boolean = false,
) {
    val c = MikaTheme.colors
    // 底部从页面底色淡出到透明,输入栏是浮在上面的玻璃
    val fade = remember(c) { Brush.verticalGradient(0f to c.bg.copy(alpha = 0f), 0.6f to c.bg, 1f to c.bg) }
    val busy = mode == ComposerMode.Busy
    val density = LocalDensity.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val field = remember { FocusRequester() }
    val ime = WindowInsets.ime
    val nav = WindowInsets.navigationBars
    var panel by rememberSaveable { mutableStateOf(Panel.Closed) }
    // 面板和键盘一样高:记下见过的键盘高度(在导航栏之上的那段),没见过时用默认高度
    var keyboardPx by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(ime, nav, density) {
        snapshotFlow { ime.getBottom(density) - nav.getBottom(density) }.collect { if (it > keyboardPx) keyboardPx = it }
    }
    val panelPx = with(density) {
        if (keyboardPx > 0) keyboardPx.coerceIn(PanelMin.roundToPx(), PanelMax.roundToPx()) else PanelDefault.roundToPx()
    }
    LaunchedEffect(attachPanel == null) { if (attachPanel == null) panel = Panel.Closed }
    BackHandler(enabled = panel == Panel.Open) { panel = Panel.Closed }
    // 让给键盘:等键盘升到差不多面板高(没有键盘弹出来时最多等一会儿)再关
    LaunchedEffect(panel, panelPx) {
        if (panel != Panel.Yielding) return@LaunchedEffect
        withTimeoutOrNull(YIELD_MS) {
            snapshotFlow { ime.getBottom(density) - nav.getBottom(density) }.first { it >= panelPx * 9 / 10 }
        }
        panel = Panel.Closed
    }
    val togglePanel: () -> Unit = {
        if (panel == Panel.Open) {
            panel = Panel.Yielding
            field.requestFocus()
            keyboard?.show()
        } else {
            focus.clearFocus()
            keyboard?.hide()
            panel = Panel.Open
        }
    }
    val turn by animateFloatAsState(if (panel == Panel.Open) 45f else 0f, if (reduceMotion) snap() else tween(200), label = "plus")
    Box(
        modifier.fillMaxWidth()
            .background(fade)
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 6.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            if (attachments.isNotEmpty()) PendingTray(attachments, onRemove, Modifier.padding(bottom = 2.dp))
            if (importing || rejected.isNotEmpty() || overLimit > 0) {
                Column(Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (importing) Text(AttachCopy.IMPORTING, style = MikaType.Caption, color = c.ink3)
                    if (rejected.isNotEmpty() || overLimit > 0) {
                        val why = listOfNotNull(
                            rejected.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.chat_attach_rejected, it.joinToString(stringResource(R.string.list_sep))) },
                            overLimit.takeIf { it > 0 }?.let(AttachCopy::overLimit),
                        ).joinToString("\n")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(why, Modifier.weight(1f), style = MikaType.Caption, color = c.warn)
                            TextLink(stringResource(R.string.ok_got_it), onDismissRejected)
                        }
                    }
                }
            }
            GlassBar(Modifier.fillMaxWidth().keepsKeyboard(), backdrop) {
                if (attachPanel != null) {
                    val label = if (panel == Panel.Open) AttachCopy.CLOSE else AttachCopy.ADD
                    Box(
                        Modifier.size(MikaSize.IconButton).clip(CircleShape)
                            .clickable(enabled = !importing, onClickLabel = label, onClick = togglePanel),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(MikaIcons.Plus, label, Modifier.size(MikaSize.Icon).graphicsLayer { rotationZ = turn }, tint = c.ink)
                    }
                }
                Box(Modifier.weight(1f).padding(start = if (attachPanel != null) 2.dp else 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)) {
                    if (text.isEmpty()) Text(ChatCopy.placeholder(mode, hasHistory), style = MikaType.Body, color = c.ink3, maxLines = 1)
                    BasicTextField(
                        text, onText,
                        Modifier.fillMaxWidth().testTag("composer-field").focusRequester(field)
                            // 面板开着时点输入框:面板让给键盘
                            .onFocusChanged { if (it.isFocused && panel == Panel.Open) panel = Panel.Yielding },
                        textStyle = MikaType.Body.copy(color = c.ink),
                        cursorBrush = SolidColor(c.accent),
                        maxLines = 5,
                    )
                }
                val canSend = AttachTray.canSend(text, attachments, importing)
                // 任务在跑时停止一直在;写了字再多一个发送,发出去的话交给当前任务
                if (busy) {
                    Box(
                        Modifier.size(MikaSize.IconButton).background(c.ink, CircleShape).clip(CircleShape)
                            .clickable(onClickLabel = stringResource(R.string.chat_stop), onClick = onStop),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(MikaIcons.Stop, stringResource(R.string.chat_stop), Modifier.size(26.dp), tint = c.bg)
                    }
                }
                if (!busy || canSend) {
                    Box(
                        Modifier.size(MikaSize.IconButton)
                            .background(if (canSend) c.accent else c.greySoft, CircleShape)
                            .clip(CircleShape)
                            .clickable(enabled = canSend, onClickLabel = stringResource(R.string.chat_send)) { panel = Panel.Closed; onSend() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(MikaIcons.Up, stringResource(R.string.chat_send), Modifier.size(20.dp), tint = if (canSend) c.onAccent else c.ink3)
                    }
                }
            }
            if (panel != Panel.Closed && attachPanel != null) {
                // 键盘还在(刚开面板时正在收、让给键盘时正在升)的那一段从面板里扣掉:输入栏不跟着上下跳
                Box(
                    Modifier.fillMaxWidth().clipToBounds().layout { m, cons ->
                        val keyboardNow = (ime.getBottom(this) - nav.getBottom(this)).coerceAtLeast(0)
                        val shown = (panelPx - keyboardNow).coerceAtLeast(0)
                        val p = m.measure(Constraints.fixed(cons.maxWidth, panelPx))
                        layout(cons.maxWidth, shown) { p.place(0, 0) }
                    },
                ) {
                    Box(Modifier.fillMaxSize().padding(top = 4.dp)) { attachPanel() }
                }
            }
        }
    }
}

/** 面板高度:没见过键盘时的默认值,以及跟着键盘时的上下限。 */
private val PanelDefault = 320.dp
private val PanelMin = 280.dp
private val PanelMax = 420.dp
/** 让给键盘时最多等这么久(毫秒)。 */
private const val YIELD_MS = 700L

/** 竖线的位置(从左边算起);步骤点、转圈的中心都在这条线上。 */
private val StepRail = 8.dp

private val FileTileShape = RoundedCornerShape(10.dp)
private val EmptyLead = TextStyle(fontSize = 15.sp, lineHeight = 21.sp)
private val ExampleText = TextStyle(fontSize = 15.sp, lineHeight = 21.sp)
private val BubbleText = TextStyle(fontSize = 15.5.sp, lineHeight = 22.5.sp)
/** 回答:正文字号,行距放宽到 1.7。 */
private val AnswerText = TextStyle(fontSize = 15.5.sp, lineHeight = 26.sp)
private val NowText = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
private val ClockText = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp, fontFeatureSettings = "tnum")
private val StepText = TextStyle(fontSize = 14.sp, lineHeight = 19.sp)
private val StepCurrentText = TextStyle(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium)
private val NoteText = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp)
private val DoneText = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
private val NeedTitle = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold)
private val CardBody = TextStyle(fontSize = 14.sp, lineHeight = 21.5.sp)
private val PillText = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
private val FileName = TextStyle(fontSize = 14.5.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
private val FileMeta = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp)
