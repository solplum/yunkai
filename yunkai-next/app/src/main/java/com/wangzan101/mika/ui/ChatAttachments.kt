package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import androidx.compose.ui.res.stringResource
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.cardSurface
import com.wangzan101.mika.ui.theme.MikaColors
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/** 对话页附件的说法。 */
object AttachCopy {
    val ADD: String get() = tr(R.string.attach_add)
    val CLOSE: String get() = tr(R.string.attach_close)
    val CAMERA: String get() = tr(R.string.attach_camera)
    val ALBUM: String get() = tr(R.string.attach_album)
    val FILES: String get() = tr(R.string.attach_files)
    val RECENT: String get() = tr(R.string.attach_recent)
    val RECENT_PARTIAL: String get() = tr(R.string.attach_recent_partial)
    val ALL: String get() = tr(R.string.attach_all)
    val CHANGE: String get() = tr(R.string.attach_change)
    val NO_PHOTOS: String get() = tr(R.string.attach_no_photos)
    val NEED_ACCESS: String get() = tr(R.string.attach_need_access)
    val GRANT: String get() = tr(R.string.attach_grant)
    val GRANT_HINT: String get() = tr(R.string.attach_grant_hint)
    val FULL: String get() = tr(R.string.attach_full, AttachTray.LIMIT)
    val NO_CAMERA: String get() = tr(R.string.attach_no_camera)
    val GONE: String get() = tr(R.string.attach_gone)
    val IMPORTING: String get() = tr(R.string.attach_importing)

    fun remove(name: String) = tr(R.string.attach_remove, name)
    fun overLimit(n: Int) = tr(R.string.attach_over_limit, AttachTray.LIMIT, n)
}

// ---------- 面板 ----------

/**
 * 「＋」点开的面板里那一份活:系统相机、照片选择器、文件选择器,照片权限(第一次打开时问一次),最近的照片。
 * 选中的交给 [onPicked](内容地址)或 [onToggle](最近的照片点一下,满了返回 false)。
 */
@Composable
internal fun AttachPanelHost(
    selected: List<PendingAttachment>,
    onPicked: (List<String>) -> Unit,
    onToggle: (PendingAttachment) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var access by remember { mutableStateOf(RecentPhotos.access(context)) }
    var refresh by remember { mutableIntStateOf(0) }
    val recent by produceState<List<PendingAttachment>?>(null, access, refresh) {
        value = if (access == PhotoAccess.None) emptyList() else RecentPhotos.query(context)
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        access = RecentPhotos.access(context)
        refresh++
    }
    // 回到 App 时(可能刚在系统设置里改过照片权限、刚拍了照)重新看一眼;面板刚打开那一次上面已经在读
    var resumedOnce by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        val now = RecentPhotos.access(context)
        if (now != access) access = now else if (resumedOnce) refresh++
        resumedOnce = true
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) {
        if (access == PhotoAccess.None && !RecentPhotos.asked(context)) {
            RecentPhotos.markAsked(context)
            permissions.launch(RecentPhotos.permissions())
        }
    }
    var cameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraUri ?: return@rememberLauncherForActivityResult
        cameraUri = null
        if (ok) onPicked(listOf(uri)) else AttachFiles.discardCamera(context, Uri.parse(uri))
    }
    val album = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(AttachTray.LIMIT)) { uris ->
        onPicked(uris.map { it.toString() })
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        onPicked(uris.map { it.toString() })
    }
    val openAlbum = { album.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    AttachPanel(
        access = access,
        recent = recent,
        selected = selected,
        onCamera = {
            val uri = AttachFiles.newCameraUri(context)
            cameraUri = uri.toString()
            try { camera.launch(uri) } catch (e: ActivityNotFoundException) {
                cameraUri = null
                AttachFiles.discardCamera(context, uri)
                Toast.makeText(context, AttachCopy.NO_CAMERA, Toast.LENGTH_SHORT).show()
            }
        },
        onAlbum = openAlbum,
        onFiles = { files.launch(arrayOf("*/*")) },
        onToggle = { if (!onToggle(it)) Toast.makeText(context, AttachCopy.FULL, Toast.LENGTH_SHORT).show() },
        onGrant = {
            val activity = context.findActivity()
            val blocked = access == PhotoAccess.None && RecentPhotos.asked(context) && activity != null &&
                RecentPhotos.permissions().none { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
            RecentPhotos.markAsked(context)
            // 用户选过「不再询问」:系统不会再弹窗,只能去 App 信息里开
            if (blocked) context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
            else permissions.launch(RecentPhotos.permissions())
        },
        modifier = modifier,
    )
}

/**
 * 面板:上面一排相机 / 相册 / 文件,下面四列最近的照片(点一下选上,右上角是序号;再点取消)。
 * 没给照片权限时下面是一句说明和「允许访问照片」;只给了部分照片时列出给了的那些,旁边能改范围。[recent] 为 null 时还在读。
 */
@Composable
internal fun AttachPanel(
    access: PhotoAccess,
    recent: List<PendingAttachment>?,
    selected: List<PendingAttachment>,
    onCamera: () -> Unit,
    onAlbum: () -> Unit,
    onFiles: () -> Unit,
    onToggle: (PendingAttachment) -> Unit,
    onGrant: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MikaTheme.colors
    Column(modifier.fillMaxSize().testTag("attach-panel").padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile(MikaIcons.PhotoCamera, AttachCopy.CAMERA, onCamera)
            Tile(MikaIcons.Photo, AttachCopy.ALBUM, onAlbum)
            Tile(MikaIcons.Paperclip, AttachCopy.FILES, onFiles)
        }
        if (access == PhotoAccess.None) {
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
            ) {
                Text(AttachCopy.NEED_ACCESS, style = PanelText, color = c.ink2, textAlign = TextAlign.Center)
                MikaButton(AttachCopy.GRANT, onGrant, kind = ButtonKind.Primary, size = ButtonSize.Medium)
                Text(AttachCopy.GRANT_HINT, style = MikaType.Hint, color = c.ink3, textAlign = TextAlign.Center)
            }
            return@Column
        }
        Row(Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (access == PhotoAccess.Partial) AttachCopy.RECENT_PARTIAL else AttachCopy.RECENT,
                Modifier.weight(1f), style = MikaType.Hint, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (access == PhotoAccess.Partial) PanelLink(AttachCopy.CHANGE, onGrant)
            PanelLink(AttachCopy.ALL, onAlbum)
        }
        when {
            recent == null -> Box(Modifier.weight(1f))
            recent.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(AttachCopy.NO_PHOTOS, style = PanelText, color = c.ink3)
            }
            else -> LazyVerticalGrid(
                GridCells.Fixed(4),
                Modifier.weight(1f).fillMaxWidth().clip(GridShape),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(bottom = 4.dp),
            ) {
                items(recent, key = { it.uri }) { p -> RecentCell(p, AttachTray.order(selected, p.uri)) { onToggle(p) } }
            }
        }
    }
}

@Composable
private fun RowScope.Tile(icon: ImageVector, label: String, onClick: () -> Unit) {
    val c = MikaTheme.colors
    Column(
        Modifier.weight(1f).height(76.dp).cardSurface(c, TileShape).clip(TileShape).clickable(role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = c.ink)
        Text(label, style = TileText, color = c.ink)
    }
}

@Composable
private fun PanelLink(text: String, onClick: () -> Unit) {
    Text(
        text, Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 6.dp, vertical = 4.dp),
        style = LinkText, color = MikaTheme.colors.accent,
    )
}

/** 最近的一张照片:方块缩略图;选中时蓝框、淡蓝罩、右上角蓝底序号,没选时右上角一个空心圈。 */
@Composable
private fun RecentCell(photo: PendingAttachment, order: Int?, onClick: () -> Unit) {
    val c = MikaTheme.colors
    val thumb = rememberAttachImage(AttachImage.Picked(photo.uri), RECENT_PX)
    Box(
        Modifier.testTag("recent-photo").aspectRatio(1f).clip(CellShape).background(c.fill)
            .semantics { selected = order != null }
            .clickable(onClickLabel = if (order != null) stringResource(R.string.attach_deselect) else stringResource(R.string.attach_select), onClick = onClick),
    ) {
        thumb?.let { Image(it, photo.name, Modifier.matchParentSize(), contentScale = ContentScale.Crop) }
        if (order != null) Box(Modifier.matchParentSize().background(c.accent.copy(alpha = 0.12f)).border(2.5.dp, c.accent, CellShape))
        Box(
            Modifier.align(Alignment.TopEnd).padding(5.dp).size(22.dp)
                .background(if (order != null) c.accent else Color.Black.copy(alpha = 0.18f), CircleShape)
                .border(1.5.dp, if (order != null) c.accent else Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (order != null) Text("$order", style = BadgeText, color = c.onAccent)
        }
    }
}

// ---------- 待发 ----------

/** 输入框上方一排待发的附件:照片是方块缩略图,文件是小卡片(类型、名字、大小),右上角 × 去掉。 */
@Composable
internal fun PendingTray(items: List<PendingAttachment>, onRemove: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = MikaTheme.colors
    Row(
        modifier.fillMaxWidth().testTag("attach-tray").horizontalScroll(rememberScrollState()).padding(start = 4.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for (a in items) {
            Box {
                if (a.photo) {
                    val thumb = rememberAttachImage(AttachImage.Picked(a.uri), TRAY_PX)
                    Box(Modifier.testTag("tray-photo").size(58.dp).clip(ChipShape).background(c.fill)) {
                        thumb?.let { Image(it, a.name, Modifier.matchParentSize(), contentScale = ContentScale.Crop) }
                    }
                } else {
                    Row(
                        Modifier.testTag("tray-file").height(58.dp).widthIn(max = 200.dp).cardSurface(c, ChipShape).padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TypeBadge(a.name, Modifier.size(28.dp, 34.dp), small = true)
                        Column(Modifier.widthIn(max = 130.dp)) {
                            Text(a.name, style = ChipName, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(ChatViewModel.sizeLabel(a.size), style = ChipMeta, color = c.ink3, maxLines = 1)
                        }
                    }
                }
                Box(
                    Modifier.align(Alignment.TopEnd).offset(6.dp, (-6).dp).size(20.dp).clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.65f))
                        .clickable(role = Role.Button, onClickLabel = AttachCopy.remove(a.name)) { onRemove(a.uri) }
                        .semantics { contentDescription = AttachCopy.remove(a.name) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(MikaIcons.Close, null, Modifier.size(12.dp), tint = Color.White)
                }
            }
        }
    }
}

// ---------- 发出去的 ----------

/**
 * 用户消息里带的附件:照片排成格子(见 [PhotoGrid];1 张保持比例,2 张并排,3 张起三列),文件是卡片(类型、名字、大小);
 * 点照片或卡片交给 [onOpen](在 App 里的查看页打开)。最宽 [maxWidth]。
 */
@Composable
internal fun SentAttachments(parsed: AttachmentMessage.Parsed, conversationId: String?, maxWidth: Dp, onOpen: (AttachmentMessage.Item) -> Unit) {
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (parsed.photos.isNotEmpty()) SentPhotos(parsed.photos, conversationId, maxWidth, onOpen)
        for (f in parsed.files) SentFileCard(f, maxWidth) { onOpen(f) }
    }
}

@Composable
private fun SentPhotos(photos: List<AttachmentMessage.Item>, conversationId: String?, maxWidth: Dp, onOpen: (AttachmentMessage.Item) -> Unit) {
    val c = MikaTheme.colors
    val cols = PhotoGrid.columns(photos.size)
    if (cols == 1) {
        val p = photos.single()
        val thumb = conversationId?.let { rememberAttachImage(AttachImage.Sent(it, p.path), SINGLE_PX) }
        val ratio = thumb?.let { (it.width.toFloat() / it.height.coerceAtLeast(1)).coerceIn(0.56f, 1.8f) } ?: 0.75f
        Box(
            Modifier.testTag("sent-photo").width(minOf(SingleWidth, maxWidth)).aspectRatio(ratio).clip(GridPhotoShape).background(c.fill)
                .clickable(role = Role.Image, onClickLabel = stringResource(R.string.attach_view_large)) { onOpen(p) },
        ) {
            thumb?.let { Image(it, p.name, Modifier.matchParentSize(), contentScale = ContentScale.Crop) }
        }
        return
    }
    val gap = 4.dp
    val cell = minOf(if (cols == 2) 118.dp else 96.dp, (maxWidth - gap * (cols - 1)) / cols)
    val px = with(LocalDensity.current) { cell.roundToPx() }.coerceAtLeast(64)
    Column(Modifier.clip(GridPhotoShape), verticalArrangement = Arrangement.spacedBy(gap)) {
        photos.chunked(cols).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                row.forEach { p ->
                    val thumb = conversationId?.let { rememberAttachImage(AttachImage.Sent(it, p.path), px) }
                    Box(
                        Modifier.testTag("sent-photo").size(cell).background(c.fill)
                            .clickable(role = Role.Image, onClickLabel = stringResource(R.string.attach_view_large)) { onOpen(p) },
                    ) {
                        thumb?.let { Image(it, p.name, Modifier.matchParentSize(), contentScale = ContentScale.Crop) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SentFileCard(f: AttachmentMessage.Item, maxWidth: Dp, onClick: () -> Unit) {
    val c = MikaTheme.colors
    Row(
        Modifier.testTag("sent-file").widthIn(min = minOf(220.dp, maxWidth), max = maxWidth).cardSurface(c, FileCardShape).clip(FileCardShape)
            .clickable(onClickLabel = stringResource(R.string.chat_open), role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TypeBadge(f.name, Modifier.size(38.dp, 44.dp), small = false)
        Column(Modifier.weight(1f, fill = false)) {
            Text(f.name, style = FileCardName, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(f.size, style = ChipMeta, color = c.ink3, maxLines = 1)
        }
    }
}

/** 文件类型角标:按扩展名配色,上面写扩展名。 */
@Composable
private fun TypeBadge(name: String, modifier: Modifier, small: Boolean) {
    val shape = RoundedCornerShape(if (small) 5.dp else 7.dp)
    Box(modifier.background(badgeColor(FileBadge.kind(name), MikaTheme.colors), shape), contentAlignment = Alignment.Center) {
        Text(FileBadge.text(name), style = if (small) BadgeSmall else BadgeLarge, color = Color.White, maxLines = 1)
    }
}

private fun badgeColor(kind: FileBadge.Kind, c: MikaColors): Color = when (kind) {
    FileBadge.Kind.Pdf -> c.bad
    FileBadge.Kind.Sheet -> c.ok
    FileBadge.Kind.Doc -> c.accent
    FileBadge.Kind.Slides -> c.warnSolid
    FileBadge.Kind.Archive, FileBadge.Kind.Other -> NeutralBadge
}

/** 缩略图解到这么大(像素):面板格子、待发的小方块、单张大图。 */
private const val RECENT_PX = 256
private const val TRAY_PX = 180
private const val SINGLE_PX = 640

private val SingleWidth = 200.dp
private val NeutralBadge = Color(0xFF8E8E93)
private val TileShape = RoundedCornerShape(16.dp)
private val GridShape = RoundedCornerShape(12.dp)
private val CellShape = RoundedCornerShape(6.dp)
private val ChipShape = RoundedCornerShape(12.dp)
private val GridPhotoShape = RoundedCornerShape(14.dp)
private val FileCardShape = RoundedCornerShape(14.dp)
private val PanelText = TextStyle(fontSize = 13.5.sp, lineHeight = 21.sp)
private val TileText = TextStyle(fontSize = 12.5.sp, lineHeight = 16.sp)
private val LinkText = TextStyle(fontSize = 12.5.sp, lineHeight = 16.sp)
private val BadgeText = TextStyle(fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold)
private val BadgeSmall = TextStyle(fontSize = 8.5.sp, lineHeight = 10.sp, fontWeight = FontWeight.Bold)
private val BadgeLarge = TextStyle(fontSize = 10.5.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold)
private val ChipName = TextStyle(fontSize = 12.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
private val ChipMeta = TextStyle(fontSize = 11.5.sp, lineHeight = 15.sp)
private val FileCardName = TextStyle(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium)
