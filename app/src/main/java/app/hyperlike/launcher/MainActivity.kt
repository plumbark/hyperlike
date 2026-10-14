package app.hyperlike.launcher

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Path
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

// Values below come from HyperOS Home's own resources (com.miui.home 6.01.02):
// default_icon_size=60dp, icon_corner_radius_standard=13.4dp, config_cell_count_x=4, _y=6,
// shortcut_menu_blur_radius=70dp
private const val CELLS_X = 4
private const val CELLS_Y = 6
private const val ICON_DP = 60
private const val CORNER_RATIO = 13.4f / 60f
private const val DRAWER_BLUR_DP = 70f

class MainActivity : ComponentActivity() {
    private val homeTick = mutableIntStateOf(0)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // light nav/status icons over the wallpaper, no forced scrim behind the gesture handle / 3-button bar
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        window.isNavigationBarContrastEnforced = false
        setContent { Launcher(homeTick.intValue) }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_MAIN) homeTick.intValue++
    }
}

data class App(val label: String, val pkg: String, val cls: String, val icon: ImageBitmap)

fun squircle(d: Drawable, s: Int): ImageBitmap {
    val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = AndroidCanvas(bmp)
    val r = s * CORNER_RATIO
    c.clipPath(Path().apply { addRoundRect(0f, 0f, s.toFloat(), s.toFloat(), r, r, Path.Direction.CW) })
    if (d is AdaptiveIconDrawable) {
        val o = (s * 0.25f).toInt()
        for (l in listOf(d.background, d.foreground)) { l?.setBounds(-o, -o, s + o, s + o); l?.draw(c) }
    } else {
        c.drawColor(0xFFF2F2F7.toInt())
        val p = (s * 0.16f).toInt()
        d.setBounds(p, p, s - p, s - p); d.draw(c)
    }
    return bmp.asImageBitmap()
}

fun loadApps(ctx: Context, px: Int): List<App> {
    val pm = ctx.packageManager
    val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(q, 0)
        .filter { it.activityInfo.packageName != ctx.packageName }
        .map { App(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.activityInfo.name, squircle(it.loadIcon(pm), px)) }
        .sortedBy { it.label.lowercase() }
}

fun dockPackages(ctx: Context): List<String> {
    val pm = ctx.packageManager
    return listOf(
        Intent(Intent.ACTION_DIAL),
        Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
        Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")),
        Intent(MediaStore.ACTION_IMAGE_CAPTURE),
    ).mapNotNull { pm.resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName }
}

fun launchApp(ctx: Context, v: View, a: App, r: Rect) {
    val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClassName(a.pkg, a.cls)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    val o = ActivityOptions.makeScaleUpAnimation(v, r.left.toInt(), r.top.toInt(), r.width.toInt(), r.height.toInt())
    try { ctx.startActivity(i, o.toBundle()) } catch (_: Exception) {}
}

fun expandShade(ctx: Context) {
    try {
        val sb = ctx.getSystemService("statusbar")
        sb.javaClass.getMethod("expandNotificationsPanel").invoke(sb)
    } catch (_: Throwable) {}
}

class DragState {
    var app by mutableStateOf<App?>(null)
    var pos by mutableStateOf(Offset.Zero)
    var moved by mutableStateOf(false)
    var src = ""
    var srcBounds = Rect.Zero
    var blockTap = false
    var onEnd: (App) -> Unit = {}
    fun end() { app?.let { onEnd(it) }; app = null }
}

fun appKey(a: App) = a.pkg + "/" + a.cls

fun mergeLayout(saved: List<String>, apps: List<App>): List<App> {
    val byKey = apps.associateBy { appKey(it) }
    val ordered = saved.mapNotNull { byKey[it] }
    val seen = ordered.map { appKey(it) }.toSet()
    return ordered + apps.filter { appKey(it) !in seen }
}

fun moveApp(l: List<App>, a: App, t: App): List<App> {
    val m = l.toMutableList(); val to = l.indexOf(t); m.remove(a); m.add(to.coerceIn(0, m.size), a); return m
}

fun moveAfter(l: List<App>, a: App, t: App): List<App> {
    val m = l.toMutableList(); m.remove(a); m.add(m.indexOf(t) + 1, a); return m
}

@Composable
fun AppCell(
    app: App, drag: DragState, source: String, modifier: Modifier = Modifier,
    showLabel: Boolean = true, cellBounds: MutableMap<String, Rect>? = null
) {
    val ctx = LocalContext.current
    val view = LocalView.current
    var pressed by remember { mutableStateOf(false) }
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val scale by animateFloatAsState(
        if (pressed) 0.88f else 1f,
        spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium), label = "press"
    )
    val k = appKey(app)
    DisposableEffect(k) { onDispose { cellBounds?.remove(k) } }
    val lifted = drag.app?.let { appKey(it) == k } == true && drag.moved
    Box(
        modifier
            .onGloballyPositioned { bounds = it.boundsInRoot(); cellBounds?.put(k, bounds) }
            .pointerInput(app) {
                detectTapGestures(
                    onPress = { drag.blockTap = false; pressed = true; tryAwaitRelease(); pressed = false },
                    onTap = { if (!drag.blockTap) launchApp(ctx, view, app, bounds) }
                )
            }
            .pointerInput(app) {
                var total = Offset.Zero
                detectDragGesturesAfterLongPress(
                    onDragStart = { o ->
                        total = Offset.Zero
                        drag.blockTap = true; drag.src = source; drag.srcBounds = bounds
                        drag.moved = false; drag.pos = bounds.topLeft + o; drag.app = app
                    },
                    onDrag = { c, d ->
                        c.consume(); total += d; drag.pos += d
                        if (total.getDistance() > 12.dp.toPx()) drag.moved = true
                    },
                    onDragEnd = { drag.end() },
                    onDragCancel = { drag.app = null }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                app.icon, null,
                Modifier.size(ICON_DP.dp).graphicsLayer {
                    scaleX = scale; scaleY = scale; alpha = if (lifted) 0.25f else 1f
                }
            )
            if (showLabel) {
                Spacer(Modifier.height(6.dp))
                BasicText(
                    app.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 2.dp),
                    style = TextStyle(
                        color = Color.White, fontSize = 11.sp, textAlign = TextAlign.Center,
                        shadow = Shadow(Color.Black.copy(alpha = 0.4f), blurRadius = 4f)
                    )
                )
            }
        }
    }
}

@Composable
fun MenuItem(t: String, onClick: () -> Unit) {
    BasicText(
        t,
        Modifier.clickable(onClick = onClick).padding(horizontal = 22.dp, vertical = 12.dp).widthIn(min = 140.dp),
        style = TextStyle(color = Color.White, fontSize = 15.sp)
    )
}

@Composable
fun Launcher(homeTick: Int) {
    val ctx = LocalContext.current
    val act = ctx as Activity
    val density = LocalDensity.current
    val conf = LocalConfiguration.current
    val iconPx = with(density) { ICON_DP.dp.roundToPx() }

    var apps by remember { mutableStateOf<List<App>>(emptyList()) }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.Default) { loadApps(ctx, iconPx) } }

    val prefs = remember { ctx.getSharedPreferences("layout", Context.MODE_PRIVATE) }
    var layout by remember(apps) {
        mutableStateOf(mergeLayout(prefs.getString("order", "").orEmpty().split("\n").filter { it.isNotEmpty() }, apps))
    }
    val dockPkgs = remember { dockPackages(ctx) }
    val dock = remember(apps) { dockPkgs.mapNotNull { p -> apps.firstOrNull { it.pkg == p } }.distinctBy { it.pkg }.take(4) }
    val pages = remember(layout, dock) {
        val dk = dock.map { appKey(it) }.toSet()
        layout.filter { appKey(it) !in dk }.chunked(CELLS_X * CELLS_Y)
    }

    var drawer by remember { mutableStateOf(false) }
    val pager = rememberPagerState { pages.size.coerceAtLeast(1) }
    LaunchedEffect(homeTick) { drawer = false; pager.scrollToPage(0) }
    BackHandler(drawer) { drawer = false }

    val drag = remember { DragState() }
    val cellBounds = remember { mutableMapOf<String, Rect>() }
    var menu by remember { mutableStateOf<Pair<App, Rect>?>(null) }

    SideEffect {
        drag.onEnd = { a ->
            if (!drag.moved) {
                menu = a to drag.srcBounds
            } else if (drag.src == "home") {
                val p = drag.pos
                val tk = cellBounds.entries.firstOrNull { it.value.contains(p) && it.key != appKey(a) }?.key
                val t = tk?.let { k -> layout.firstOrNull { appKey(it) == k } }
                val last = pages.getOrNull(pager.currentPage)?.lastOrNull()
                val nl = when {
                    t != null -> moveApp(layout, a, t)
                    last != null && last != a -> moveAfter(layout, a, last)
                    else -> layout
                }
                layout = nl
                prefs.edit().putString("order", nl.joinToString("\n") { appKey(it) }).apply()
            }
        }
    }

    // hold an icon near the screen edge to flip pages
    val edgePx = with(density) { 48.dp.toPx() }
    val screenW = with(density) { conf.screenWidthDp.dp.toPx() }
    LaunchedEffect(drag.app != null) {
        while (drag.app != null) {
            if (drag.moved && drag.src == "home" && !drawer) {
                val x = drag.pos.x
                if (x < edgePx && pager.currentPage > 0) { pager.animateScrollToPage(pager.currentPage - 1); delay(600) }
                else if (x > screenW - edgePx && pager.currentPage < pager.pageCount - 1) { pager.animateScrollToPage(pager.currentPage + 1); delay(600) }
            }
            delay(80)
        }
    }

    val blur by animateFloatAsState(if (drawer) DRAWER_BLUR_DP else 0f, tween(300), label = "blur")
    SideEffect {
        try { act.window.setBackgroundBlurRadius(with(density) { blur.dp.roundToPx() }) } catch (_: Throwable) {}
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)
                .pointerInput(Unit) {
                    var a = 0f
                    detectVerticalDragGestures(
                        onDragStart = { a = 0f },
                        onDragEnd = { if (a < -100f) drawer = true else if (a > 100f) expandShade(ctx) },
                        onVerticalDrag = { _, dy -> a += dy }
                    )
                }
        ) {
            HorizontalPager(pager, Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp)) { p ->
                val rows = pages.getOrNull(p).orEmpty().chunked(CELLS_X)
                Column(Modifier.fillMaxSize()) {
                    rows.forEach { row ->
                        Row(Modifier.fillMaxWidth().weight(1f)) {
                            row.forEach { AppCell(it, drag, "home", Modifier.weight(1f).fillMaxHeight(), cellBounds = cellBounds) }
                            repeat(CELLS_X - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    repeat(CELLS_Y - rows.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            if (pages.size > 1) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    repeat(pages.size) { i ->
                        Box(
                            Modifier.padding(3.dp).size(6.dp).clip(CircleShape)
                                .background(Color.White.copy(alpha = if (i == pager.currentPage) 1f else 0.4f))
                        )
                    }
                }
            }
            Box(
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(30.dp)).background(Color.White.copy(alpha = 0.22f))
                    .padding(vertical = 12.dp)
            ) {
                Row(Modifier.fillMaxWidth()) { dock.forEach { AppCell(it, drag, "dock", Modifier.weight(1f), showLabel = false) } }
            }
        }

        AnimatedVisibility(
            drawer,
            enter = slideInVertically(tween(260)) { it / 6 } + fadeIn(tween(220)),
            exit = slideOutVertically(tween(200)) { it / 6 } + fadeOut(tween(180))
        ) {
            LazyVerticalGrid(
                GridCells.Fixed(CELLS_X),
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).windowInsetsPadding(WindowInsets.systemBars),
                contentPadding = PaddingValues(12.dp, 24.dp)
            ) {
                items(apps, key = { appKey(it) }) { AppCell(it, drag, "drawer", Modifier.padding(vertical = 10.dp)) }
            }
        }

        // icon following the finger while moving
        drag.app?.let { a ->
            if (drag.moved) {
                Image(
                    a.icon, null,
                    Modifier.offset { IntOffset((drag.pos.x - iconPx / 2).toInt(), (drag.pos.y - iconPx / 2).toInt()) }
                        .size(ICON_DP.dp).graphicsLayer { scaleX = 1.12f; scaleY = 1.12f; alpha = 0.95f }
                )
            }
        }

        // long-press (without moving) menu
        menu?.let { (a, r) ->
            val h = with(density) { conf.screenHeightDp.dp.toPx() }
            val y = if (r.bottom > h * 0.65f) r.top - with(density) { 110.dp.toPx() } else r.bottom
            val x = r.left.coerceAtMost(screenW - with(density) { 190.dp.toPx() })
            Popup(
                alignment = Alignment.TopStart, offset = IntOffset(x.toInt(), y.toInt()),
                onDismissRequest = { menu = null }, properties = PopupProperties(focusable = true)
            ) {
                Column(Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xE61C1C1E)).padding(vertical = 6.dp)) {
                    MenuItem("App info") {
                        menu = null
                        ctx.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                    MenuItem("Uninstall") {
                        menu = null
                        ctx.startActivity(
                            Intent(Intent.ACTION_DELETE, Uri.parse("package:${a.pkg}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
            }
        }
    }
}
