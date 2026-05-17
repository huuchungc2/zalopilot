package com.zalopilot.app.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Build
import android.provider.Settings
import android.provider.MediaStore
import android.widget.Toast
import java.io.File
import java.util.Locale
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.ClipData
import android.content.ClipboardManager
import com.zalopilot.app.accessibility.NodeFinder
import com.zalopilot.app.accessibility.ZaloUIScanner
import com.zalopilot.app.accessibility.ZaloPilotAccessibilityService
import com.zalopilot.app.data.model.ZaloIDStore
import com.zalopilot.app.accessibility.engine.ZPScriptStore
import com.zalopilot.app.floating.FloatingMenuService
import com.zalopilot.app.util.AccessibilityHelper
import com.zalopilot.app.util.DebugHighlightPrefs
import com.zalopilot.app.util.LikeMode
import com.zalopilot.app.util.VisitActionMode
import com.zalopilot.app.util.LogTag
import com.zalopilot.app.util.*
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var progressManager: LikeProgressManager
    @Inject lateinit var settingsManager: LikeSettingsManager
    @Inject lateinit var logger: Logger
    @Inject lateinit var nodeFinder: NodeFinder
    @Inject lateinit var debugHighlightPrefs: DebugHighlightPrefs
    @Inject lateinit var uiScanner: ZaloUIScanner
    @Inject lateinit var zaloIdStore: ZaloIDStore
    @Inject lateinit var scriptStore: ZPScriptStore
    @Inject lateinit var visitHandledContacts: VisitHandledContactsManager

    private val permissionGateTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        progressManager.resetDailyIfNeeded()
        setContent {
            MaterialTheme {
                PermissionGate()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        permissionGateTick.intValue++
        if (!AccessibilityHelper.isAccessibilityEnabled(this)) {
            Toast.makeText(
                this,
                "Trợ năng ZaloPilot đã bị tắt (thường do app vừi crash). Bật lại ở màn hình bên dưới.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** Sau crash Android hay tắt Trợ năng — mỗi lần mở app kiểm tra lại, không chỉ lần cài đầu. */
    @Composable
    private fun PermissionGate() {
        val permTick = permissionGateTick.intValue
        val accOn = remember(permTick) { AccessibilityHelper.isAccessibilityEnabled(this@MainActivity) }
        val overlayOn = remember(permTick) { Settings.canDrawOverlays(this@MainActivity) }
        when {
            !accOn || !overlayOn -> SetupWizard(initialStep = when {
                !accOn -> 1
                else -> 2
            })
            else -> ZaloPilotApp()
        }
    }

    // ─── Setup Wizard ────────────────────────────────────────────

    @Composable
    fun SetupWizard(initialStep: Int = 1) {
        var step by remember { mutableIntStateOf(initialStep.coerceIn(1, 3)) }
        var tick by remember { mutableIntStateOf(0) }

        LaunchedEffect(Unit) {
            while (true) {
                kotlinx.coroutines.delay(1000)
                tick++
            }
        }

        val acc = remember(tick) { isAccessibilityServiceEnabled() }
        val overlay = remember(tick) { Settings.canDrawOverlays(this) }

        LaunchedEffect(acc, overlay) {
            if (step == 1 && acc) step = 2
            if (step == 2 && overlay) step = 3
        }

        Column(
            modifier = Modifier.fillMaxSize().background(ZpColors.BgPage).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("ZaloPilot", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = ZpColors.TextPrimary)
            Text(AppVersion.fullLabel(), fontSize = 12.sp, color = ZpColors.TextSecondary)
            Text("Thiết lập lần đầu", fontSize = 14.sp, color = ZpColors.TextSecondary)
            Spacer(Modifier.height(32.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(1, 2, 3).forEach { s ->
                    Box(Modifier.size(32.dp).clip(CircleShape)
                        .background(if (s <= step) ZpColors.AccentBlue else ZpColors.BgSecondary),
                        contentAlignment = Alignment.Center) {
                        Text("$s", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                    if (s < 3) Box(Modifier.width(32.dp).height(2.dp)
                        .background(if (s < step) ZpColors.AccentBlue else ZpColors.BgSecondary))
                }
            }

            Spacer(Modifier.height(32.dp))

            when (step) {
                1 -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    StepCard(
                        Icons.Filled.Info,
                        "Bật Trợ năng",
                        "Mở màn cài đặt → bật ZaloPilot.",
                        "Mở Trợ năng",
                        acc
                    ) {
                        AccessibilityHelper.openAccessibilitySettings(this@MainActivity)
                    }
                }
                2 -> StepCard(
                    Icons.Filled.Phone,
                    "Hiển thị trên app khác",
                    "Cho phép nút ZP nổi trên Zalo.",
                    "Mở cài đặt",
                    overlay
                ) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
                3 -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = ZpColors.ColorGreen
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("Xong!", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = ZpColors.ColorGreen)
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = {
                        recreate()
                    }, colors = ButtonDefaults.buttonColors(containerColor = ZpColors.AccentBlue),
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(12.dp)) {
                        Text("Vào app chính", fontSize = 15.sp, fontWeight = FontWeight.W500)
                    }
                }
            }
        }
    }

    @Composable
    fun StepCard(
        icon: ImageVector,
        title: String,
        desc: String,
        btnText: String,
        done: Boolean,
        onClick: () -> Unit
    ) {
        IosCard(contentPadding = PaddingValues(24.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(40.dp), tint = ZpColors.AccentBlue)
                Spacer(Modifier.height(12.dp))
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = ZpColors.TextPrimary)
                Spacer(Modifier.height(8.dp))
                Text(desc, fontSize = 13.sp, color = ZpColors.TextSecondary, textAlign = TextAlign.Center, lineHeight = 20.sp)
                Spacer(Modifier.height(20.dp))
                if (done) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            Modifier.size(20.dp).clip(CircleShape).background(ZpColors.ColorGreen),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("✓", color = Color.White, fontSize = 12.sp)
                        }
                        Text("Đã cấp quyền", color = ZpColors.ColorGreen, fontWeight = FontWeight.W500)
                    }
                } else {
                    Button(
                        onClick = onClick,
                        colors = ButtonDefaults.buttonColors(containerColor = ZpColors.AccentBlue),
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(btnText)
                    }
                }
            }
        }
    }

    // ─── Main App ────────────────────────────────────────────────

    @Composable
    fun ZaloPilotApp() {
        var selectedTab by remember { mutableIntStateOf(0) }
        var isRunning by remember { mutableStateOf(false) }
        var progress by remember { mutableStateOf(progressManager.load()) }
        var settings by remember { mutableStateOf(settingsManager.load()) }
        var logsSlim by remember { mutableStateOf(logger.readSlimLogs()) }
        var logsVerbose by remember { mutableStateOf(logger.readVerboseLogs()) }
        var logsError by remember { mutableStateOf(logger.readErrorLogs()) }
        var verboseUiTreeLog by remember { mutableStateOf(debugHighlightPrefs.isVerboseUiTreeLoggingEnabled()) }
        var verboseLikeContextLog by remember { mutableStateOf(debugHighlightPrefs.isVerboseLikeContextLoggingEnabled()) }
        var overlayTick by remember { mutableIntStateOf(0) }
        LaunchedEffect(Unit) {
            while (true) {
                kotlinx.coroutines.delay(800)
                overlayTick++
            }
        }
        val floatingOverlayOn = remember(overlayTick) { FloatingMenuService.isOverlayRunning }
        val accessibilityOn = remember(overlayTick) { AccessibilityHelper.isAccessibilityEnabled(this@MainActivity) }
        val accessibilityConnected = remember(overlayTick) { ZaloPilotAccessibilityService.instance != null }

        DisposableEffect(Unit) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    progress = progressManager.load()
                    settings = settingsManager.load()
                    logsSlim = logger.readSlimLogs()
                    logsVerbose = logger.readVerboseLogs()
                    logsError = logger.readErrorLogs()
                    when (intent?.action) {
                        "com.zalopilot.STATUS_UPDATE" -> {
                            val running = intent.getBooleanExtra("running", false)
                            isRunning = running
                            val modeExtra = intent.getStringExtra(
                                ZaloPilotAccessibilityService.EXTRA_RUNNING_LIKE_MODE
                            )
                            if (running && modeExtra != null) {
                                runCatching { LikeMode.valueOf(modeExtra) }.getOrNull()?.let { mode ->
                                    settingsManager.setLikeMode(mode)
                                    settings = settingsManager.load()
                                }
                            }
                            val modeLabel = when (settingsManager.getLikeMode()) {
                                LikeMode.VISIT -> "danh bạ"
                                LikeMode.FEED -> "nhật ký"
                            }
                            Toast.makeText(
                                this@MainActivity,
                                if (running) "▶ Đang chạy like $modeLabel" else "■ ZaloPilot đã dừng",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        "com.zalopilot.DAILY_LIMIT" ->
                            Toast.makeText(this@MainActivity,
                                "✅ Đã like đủ ${settingsManager.load().dailyLimit} bài hôm nay!",
                                Toast.LENGTH_LONG).show()
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction("com.zalopilot.STATUS_UPDATE")
                addAction("com.zalopilot.PROGRESS_UPDATE")
                addAction("com.zalopilot.DAILY_LIMIT")
                addAction("com.zalopilot.ZALO_STATE")
            }
            // Android 13+ (targetSdk 34) BẮT BUỘC khai báo flag, không thì SecurityException →
            // receiver không nhận được broadcast → counter "Đã like" không tự update.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(receiver, filter)
            }
            onDispose { unregisterReceiver(receiver) }
        }

        Scaffold(
            containerColor = ZpColors.BgPage,
            bottomBar = {
            NavigationBar(containerColor = ZpColors.BgCard) {
                val tabs = listOf(
                    Triple("Home", Icons.Filled.Home, 0),
                    Triple("Cài đặt", Icons.Filled.Settings, 1),
                    Triple("BL/Tin", Icons.Filled.Email, 2),
                    Triple("Log", Icons.Filled.List, 3),
                    Triple("Script", Icons.Filled.Edit, 4),
                    Triple("UI", Icons.Filled.Menu, 5)
                )
                tabs.forEach { (label, icon, index) ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = {
                            selectedTab = index
                            if (index == 3) {
                                logsSlim = logger.readSlimLogs()
                                logsVerbose = logger.readVerboseLogs()
                                logsError = logger.readErrorLogs()
                            }
                        },
                        icon = {
                            Icon(
                                icon,
                                contentDescription = label,
                                modifier = Modifier.size(24.dp)
                            )
                        },
                        label = {
                            Text(
                                label,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = ZpColors.AccentBlue,
                            selectedTextColor = ZpColors.AccentBlue,
                            unselectedIconColor = ZpColors.TextSecondary,
                            unselectedTextColor = ZpColors.TextSecondary,
                            indicatorColor = Color.Transparent
                        )
                    )
                }
            }
        }) { padding ->
            Box(Modifier.padding(padding)) {
                when (selectedTab) {
                    0 -> HomeScreen(
                        isRunning,
                        progress,
                        settings,
                        floatingOverlayOn,
                        accessibilityOn,
                        accessibilityConnected,
                        overlayTick = overlayTick,
                        onOpenSettings = { selectedTab = 1 },
                        onOpenComments = { selectedTab = 2 }
                    )
                    1 -> SettingsScreen(
                        settings,
                        onSave = { settings = it; settingsManager.save(it) }
                    )
                    2 -> CommentScreen(
                        settings,
                        onSave = { settings = it; settingsManager.save(it) }
                    )
                    3 -> LogScreen(
                        logsSlim = logsSlim,
                        logsVerbose = logsVerbose,
                        logsError = logsError,
                        verboseUiTreeLog = verboseUiTreeLog,
                        verboseLikeContextLog = verboseLikeContextLog,
                        onVerboseUiTreeChange = {
                            verboseUiTreeLog = it
                            debugHighlightPrefs.setVerboseUiTreeLoggingEnabled(it)
                        },
                        onVerboseLikeContextChange = {
                            verboseLikeContextLog = it
                            debugHighlightPrefs.setVerboseLikeContextLoggingEnabled(it)
                        },
                        onClearLogs = {
                            logger.clearDebugArtifacts()
                            sendBroadcast(Intent(ZaloPilotAccessibilityService.ACTION_CLEAR_DEBUG_STATE).setPackage(packageName))
                            logsSlim = logger.readSlimLogs()
                            logsVerbose = logger.readVerboseLogs()
                            logsError = logger.readErrorLogs()
                            Toast.makeText(this@MainActivity, "Đã xóa log & file tạm", Toast.LENGTH_SHORT).show()
                        }
                    )
                    4 -> ScriptScreen(scriptStore = scriptStore)
                    5 -> UiTreeScreen()
                }
            }
        }
    }

    // ─── Home ────────────────────────────────────────────────────

    @Composable
    fun HomeScreen(
        isRunning: Boolean,
        progress: LikeProgress,
        settings: LikeSettings,
        floatingOverlayOn: Boolean,
        accessibilityOn: Boolean,
        accessibilityConnected: Boolean,
        overlayTick: Int,
        onOpenSettings: () -> Unit,
        onOpenComments: () -> Unit
    ) {
        var autoStart by remember { mutableStateOf(settingsManager.isAutoStart()) }
        var ecoMode by remember(settings) { mutableStateOf(settings.ecoMode) }
        val runningMode = remember(isRunning, settings.likeModeStr, overlayTick) {
            val session = ZaloPilotAccessibilityService.instance?.getSessionLikeMode()
            when {
                isRunning && session != null -> session
                else -> runCatching { LikeMode.valueOf(settings.likeModeStr) }.getOrDefault(LikeMode.FEED)
            }
        }
        val pct = if (settings.dailyLimit <= 0) 0f
        else (progress.todayLikeCount.toFloat() / settings.dailyLimit).coerceIn(0f, 1f)
        val pctLabel = if (settings.dailyLimit <= 0) "—" else "${(pct * 100).toInt()}%"

        LazyColumn(
            modifier = Modifier.fillMaxSize().background(ZpColors.BgPage),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!accessibilityOn || !accessibilityConnected) {
                item {
                    IosCard {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                if (!accessibilityOn) "Chưa bật Trợ năng" else "Chưa kết nối",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.W600,
                                color = ZpColors.ColorOrange,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = { AccessibilityHelper.openAccessibilitySettings(this@MainActivity) }
                            ) {
                                Text("Mở", fontWeight = FontWeight.W600, color = ZpColors.AccentBlue)
                            }
                        }
                    }
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp, start = 4.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("ZaloPilot", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = ZpColors.TextPrimary)
                        Text("Auto Like Zalo", fontSize = 15.sp, color = ZpColors.TextSecondary)
                    }
                    Surface(shape = RoundedCornerShape(99.dp), color = ZpColors.BgSecondary) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                Modifier.size(7.dp).clip(CircleShape)
                                    .background(if (isRunning) ZpColors.ColorGreen else ZpColors.ColorRed)
                            )
                            Text(
                                if (isRunning) "Đang chạy" else "Đã dừng",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.W600,
                                color = ZpColors.TextPrimary
                            )
                        }
                    }
                }
            }
            item {
                IosCard {
                    if (isRunning) {
                        Text(
                            "Đang chạy: ${if (runningMode == LikeMode.VISIT) "Like danh bạ" else "Like nhật ký"}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.W600,
                            color = if (runningMode == LikeMode.VISIT) ZpColors.AccentPurple else ZpColors.AccentBlue
                        )
                        Spacer(Modifier.height(12.dp))
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(
                                Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(14.dp))
                                    .background(ZpColors.AccentBlue).clickable {
                                        settingsManager.setLikeMode(LikeMode.FEED)
                                        AccessibilityHelper.requestStartAutoLike(this@MainActivity, LikeMode.FEED)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "▶ Nhật ký",
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.W600,
                                    textAlign = TextAlign.Center
                                )
                            }
                            Box(
                                Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(14.dp))
                                    .background(ZpColors.AccentPurple).clickable {
                                        settingsManager.setLikeMode(LikeMode.VISIT)
                                        AccessibilityHelper.requestStartAutoLike(this@MainActivity, LikeMode.VISIT)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "▶ Danh bạ",
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.W600,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(14.dp))
                                .background(ZpColors.BgSecondary).clickable {
                                    val intent = packageManager.getLaunchIntentForPackage("com.zing.zalo")
                                    if (intent == null) {
                                        Toast.makeText(this@MainActivity, "Chưa cài Zalo", Toast.LENGTH_SHORT).show()
                                    } else {
                                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        startActivity(intent)
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Mở Zalo", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = ZpColors.TextPrimary)
                        }
                        Box(
                            Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(14.dp))
                                .background(ZpColors.BgSecondary).clickable {
                                    if (floatingOverlayOn) stopFloatingOverlay() else startFloatingOverlay()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                if (floatingOverlayOn) "Tắt nút nổi" else "Bật nút nổi",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = ZpColors.TextPrimary
                            )
                        }
                    }
                }
            }
            if (isRunning) {
                item {
                    Box(
                        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(14.dp))
                            .background(ZpColors.ColorRed).clickable {
                                AccessibilityHelper.requestStopAutoLike(this@MainActivity)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("■  Dừng lại", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.W600)
                    }
                }
            }
            item { IosSectionLabel("TIẾN ĐỘ HÔM NAY") }
            item {
                IosCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatCard(
                            "${progress.todayLikeCount}",
                            "Đã like",
                            ZpColors.AccentBlue,
                            Modifier.weight(1f)
                        )
                        StatCard(
                            "${progress.todayPostsHandledCount}",
                            "Đã duyệt",
                            ZpColors.TextPrimary,
                            Modifier.weight(1f)
                        )
                        StatCard(
                            "${settings.dailyLimit}",
                            "Giới hạn",
                            ZpColors.TextPrimary,
                            Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            if (settings.dailyLimit <= 0) "${progress.todayLikeCount}"
                            else "${progress.todayLikeCount} / ${settings.dailyLimit}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = ZpColors.TextPrimary
                        )
                        LinearProgressIndicator(
                            progress = { pct },
                            modifier = Modifier.weight(1f).height(5.dp).clip(RoundedCornerShape(99.dp)),
                            color = ZpColors.AccentBlue,
                            trackColor = ZpColors.BgSecondary
                        )
                        Text(pctLabel, fontSize = 13.sp, color = ZpColors.TextSecondary)
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TextButton(onClick = onOpenSettings) {
                        Text("Cài đặt", color = ZpColors.AccentBlue, fontSize = 14.sp)
                    }
                    TextButton(onClick = onOpenComments) {
                        Text("Bình luận", color = ZpColors.AccentBlue, fontSize = 14.sp)
                    }
                }
            }
            item { IosSectionLabel("TỰ CHẠY") }
            item {
                IosCard(contentPadding = PaddingValues(0.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Tự động chạy lại", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = ZpColors.TextPrimary)
                            Text(
                                "Tự bật bot khi mở Zalo (nếu chưa DỪNG)",
                                fontSize = 12.sp,
                                color = ZpColors.TextSecondary
                            )
                        }
                        Switch(
                            checked = autoStart,
                            onCheckedChange = {
                                autoStart = it
                                settingsManager.setAutoStart(it)
                            },
                            colors = iosSwitchColors
                        )
                    }
                    HorizontalDivider(color = ZpColors.Divider, thickness = 0.5.dp)
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Ngủ trưa", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = ZpColors.TextPrimary)
                            Text(
                                "Tiết kiệm pin — poll chậm, delay dài hơn",
                                fontSize = 12.sp,
                                color = ZpColors.TextSecondary
                            )
                        }
                        Switch(
                            checked = ecoMode,
                            onCheckedChange = {
                                ecoMode = it
                                val updated = settings.copy(ecoMode = it)
                                settingsManager.save(updated)
                            },
                            colors = iosSwitchColors
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun StatCard(value: String, label: String, color: Color, modifier: Modifier = Modifier) {
        Box(
            modifier.clip(RoundedCornerShape(12.dp)).background(ZpColors.BgSecondary).padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = color)
                Text(label, fontSize = 12.sp, color = ZpColors.TextSecondary)
            }
        }
    }

    // ─── Settings ────────────────────────────────────────────────

    @Composable
    fun SettingsScreen(settings: LikeSettings, onSave: (LikeSettings) -> Unit) {
        var dailyLimit by remember(settings) { mutableIntStateOf(settings.dailyLimit) }
        var feedMode by remember { mutableStateOf(settingsManager.getFeedMode()) }
        var delayMinSec by remember(settings) {
            mutableIntStateOf((settings.delayMinMs / 1000).toInt().coerceIn(1, 60))
        }
        var delayMaxSec by remember(settings) {
            mutableIntStateOf((settings.delayMaxMs / 1000).toInt().coerceIn(1, 60))
        }
        var ecoMode by remember(settings) { mutableStateOf(settings.ecoMode) }
        var requireCharging by remember(settings) { mutableStateOf(settings.requireCharging) }
        var lowBatteryPauseEnabled by remember(settings) { mutableStateOf(settings.lowBatteryPauseEnabled) }
        var lowBatteryThreshold by remember(settings) { mutableIntStateOf(settings.lowBatteryThreshold) }
        var pauseWhenZaloAway by remember(settings) { mutableStateOf(settings.pauseWhenZaloAway) }
        var visitLikeCount by remember(settings) { mutableIntStateOf(settings.visitLikeCount) }
        var visitMaxProfiles by remember(settings) { mutableIntStateOf(settings.visitMaxProfiles) }

        LazyColumn(
            modifier = Modifier.fillMaxSize().background(ZpColors.BgPage),
            contentPadding = PaddingValues(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    IosScreenTitle("Cài đặt", AppVersion.fullLabel())
                }
            }
            item { IosSectionLabel("GIỚI HẠN LIKE") }
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    IosCard {
                        Text("Tối đa / ngày: $dailyLimit bài", fontSize = 13.sp, color = ZpColors.TextPrimary)
                        Text("Kéo từ 20 tới 3000 — tùy thích", fontSize = 11.sp, color = ZpColors.TextSecondary)
                        Spacer(Modifier.height(8.dp))
                        Slider(
                            value = dailyLimit.toFloat(),
                            onValueChange = { dailyLimit = it.toInt() },
                            valueRange = 20f..3000f,
                            colors = iosSliderColors
                        )
                    }
                }
            }
            item { IosSectionLabel("TỐC ĐỘ LIKE") }
            item {
                ZaloSettingsGroup {
                    StepperRow(
                        label = "Delay tối thiểu",
                        subLabel = "Giây giữa các lần like",
                        value = delayMinSec,
                        min = 1,
                        max = 60,
                        unit = " giây",
                        onValueChange = {
                            delayMinSec = it
                            if (delayMaxSec < it) delayMaxSec = it
                        }
                    )
                    ZaloSettingsDivider()
                    StepperRow(
                        label = "Delay tối đa",
                        subLabel = "Giây giữa các lần like",
                        value = delayMaxSec,
                        min = 1,
                        max = 60,
                        unit = " giây",
                        onValueChange = {
                            delayMaxSec = it
                            if (delayMinSec > it) delayMinSec = it
                        }
                    )
                }
            }
            item { IosSectionLabel("TIẾT KIỆM PIN") }
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                IosCard {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Ngủ trưa", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = ZpColors.TextPrimary)
                            Text(
                                "Poll chậm, delay dài hơn — ít nóng pin",
                                fontSize = 12.sp,
                                color = ZpColors.TextSecondary
                            )
                        }
                        Switch(
                            checked = ecoMode,
                            onCheckedChange = { ecoMode = it },
                            colors = iosSwitchColors
                        )
                    }
                }
                }
            }
            item { IosSectionLabel("PIN & SẠC") }
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                IosCard(contentPadding = PaddingValues(0.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Chỉ chạy khi cắm sạc", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = ZpColors.TextPrimary)
                            Text("Rút sạc → tạm dừng", fontSize = 12.sp, color = ZpColors.TextSecondary)
                        }
                        Switch(
                            checked = requireCharging,
                            onCheckedChange = { requireCharging = it },
                            colors = iosSwitchColors
                        )
                    }
                    HorizontalDivider(color = ZpColors.Divider, thickness = 0.5.dp)
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Pause khi pin thấp", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = ZpColors.TextPrimary)
                            Text("Pin dưới ngưỡng → tạm dừng", fontSize = 12.sp, color = ZpColors.TextSecondary)
                        }
                        Switch(
                            checked = lowBatteryPauseEnabled,
                            onCheckedChange = { lowBatteryPauseEnabled = it },
                            colors = iosSwitchColors
                        )
                    }
                    if (lowBatteryPauseEnabled) {
                        HorizontalDivider(color = ZpColors.Divider, thickness = 0.5.dp)
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text("Ngưỡng pin: $lowBatteryThreshold%", fontSize = 12.sp, color = ZpColors.TextSecondary)
                            Slider(
                                value = lowBatteryThreshold.toFloat(),
                                onValueChange = { lowBatteryThreshold = it.toInt() },
                                valueRange = 5f..50f,
                                colors = iosSliderColors
                            )
                        }
                    }
                    HorizontalDivider(color = ZpColors.Divider, thickness = 0.5.dp)
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Tiết kiệm khi rời Zalo", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = ZpColors.TextPrimary)
                            Text("Mở Zalo lại → tự chạy tiếp", fontSize = 12.sp, color = ZpColors.TextSecondary)
                        }
                        Switch(
                            checked = pauseWhenZaloAway,
                            onCheckedChange = { pauseWhenZaloAway = it },
                            colors = iosSwitchColors
                        )
                    }
                }
                }
            }
            item { IosSectionLabel("LIKE DANH BẠ") }
            item {
                ZaloSettingsGroup {
                    StepperRow(
                        label = "Like mỗi profile",
                        subLabel = "Bấm Like danh sách ở Home",
                        value = visitLikeCount,
                        min = 0,
                        max = 10,
                        unit = " bài",
                        onValueChange = { visitLikeCount = it }
                    )
                    ZaloSettingsDivider()
                    StepperRow(
                        label = "Profile tối đa / phiên",
                        subLabel = "Giới hạn một lần chạy Visit",
                        value = visitMaxProfiles,
                        min = 5,
                        max = 200,
                        unit = "",
                        onValueChange = { visitMaxProfiles = it }
                    )
                    ZaloSettingsDivider()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Comment & câu mẫu → tab Comment",
                            fontSize = 12.sp,
                            color = ZpColors.TextSecondary,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            progressManager.resetVisitIndex()
                            Toast.makeText(this@MainActivity, "Đã reset visitIndex", Toast.LENGTH_SHORT).show()
                        }) { Text("Reset visitIndex", color = ZpColors.AccentBlue) }
                    }
                }
            }
            item { IosSectionLabel("CHẾ ĐỘ FEED") }
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                IosCard {
                        Text("Cách bot cuộn khi không tìm thấy like", fontSize = 12.sp, color = ZpColors.TextSecondary)
                        Spacer(Modifier.height(10.dp))
                        listOf(
                            FeedMode.SCROLL to "Cuộn tự động",
                            FeedMode.MANUAL to "Đẩy tay",
                            FeedMode.MIX to "Kết hợp"
                        ).forEach { (mode, label) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (feedMode == mode) ZpColors.AccentBlue.copy(alpha = 0.1f) else Color.Transparent)
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = feedMode == mode,
                                    onClick = { feedMode = mode },
                                    colors = RadioButtonDefaults.colors(selectedColor = ZpColors.AccentBlue)
                                )
                                Text(label, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp))
                            }
                        }
                        Text(
                            "Chế độ like/comment → tab Bình luận",
                            fontSize = 12.sp,
                            color = ZpColors.TextSecondary,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                }
                }
            }
            item {
                Text(
                    "Phiên bản ${AppVersion.fullLabel()}",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    textAlign = TextAlign.Center,
                    fontSize = 12.sp,
                    color = ZpColors.TextSecondary
                )
            }
            item {
                Button(onClick = {
                    settingsManager.setFeedMode(feedMode)
                    val base = settingsManager.load()
                    val minSec = delayMinSec.coerceIn(1, 60)
                    val maxSec = delayMaxSec.coerceIn(minSec, 60)
                    onSave(base.copy(
                        dailyLimit = dailyLimit,
                        delayMinMs = minSec * 1000L,
                        delayMaxMs = maxSec * 1000L,
                        interactModeStr = InteractMode.TAP.name,
                        humanLikeScroll = true,
                        ecoMode = ecoMode,
                        requireCharging = requireCharging,
                        lowBatteryPauseEnabled = lowBatteryPauseEnabled,
                        lowBatteryThreshold = lowBatteryThreshold,
                        pauseWhenZaloAway = pauseWhenZaloAway,
                        likeModeStr = settingsManager.getLikeMode().name,
                        visitLikeCount = visitLikeCount,
                        visitMaxProfiles = visitMaxProfiles
                    ))
                    Toast.makeText(this@MainActivity, "✅ Đã lưu cài đặt", Toast.LENGTH_SHORT).show()
                },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .padding(horizontal = 16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ZpColors.AccentBlue),
                    shape = RoundedCornerShape(14.dp)) {
                    Text("Lưu cài đặt", fontSize = 15.sp, fontWeight = FontWeight.W500)
                }
            }
        }
    }

    // ─── Comment ─────────────────────────────────────────────────

    @Composable
    fun CommentScreen(settings: LikeSettings, onSave: (LikeSettings) -> Unit) {
        var visitCommentCount by remember(settings) { mutableIntStateOf(settings.visitCommentCount) }
        var visitChatCount by remember(settings) { mutableIntStateOf(settings.visitChatCount) }
        var feedCommentCount by remember(settings) { mutableIntStateOf(settings.feedCommentCount) }
        var visitActionMode by remember(settings) {
            mutableStateOf(
                try {
                    VisitActionMode.valueOf(settings.visitActionMode)
                } catch (e: Exception) {
                    VisitActionMode.LIKE_ONLY
                }
            )
        }
        var visitCommentsText by remember(settings) {
            mutableStateOf(settings.visitCommentList.joinToString("\n"))
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().background(ZpColors.BgPage),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { IosScreenTitle("Bình luận & tin", "Nhật ký, comment profile & tin chat dùng chung danh sách câu") }
            item { IosSectionLabel("CHẾ ĐỘ") }
            item {
                IosCard {
                    Text(
                        "Áp dụng cả Nhật ký và Like danh bạ",
                        fontSize = 12.sp,
                        color = ZpColors.TextSecondary
                    )
                    Spacer(Modifier.height(8.dp))
                    listOf(
                        VisitActionMode.LIKE_ONLY to "Chỉ like",
                        VisitActionMode.COMMENT_ONLY to "Chỉ comment",
                        VisitActionMode.MIX to "Like rồi comment",
                        VisitActionMode.CHAT_ONLY to "Chỉ gửi tin (chat)"
                    ).forEach { (mode, label) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = visitActionMode == mode,
                                onClick = { visitActionMode = mode },
                                colors = RadioButtonDefaults.colors(selectedColor = ZpColors.AccentBlue)
                            )
                            Text(label, fontSize = 13.sp)
                        }
                    }
                }
            }
            item { IosSectionLabel("NHẬT KÝ") }
            item {
                IosCard {
                    Text("Số comment / bài (0 = không gửi)", fontSize = 12.sp, color = ZpColors.TextSecondary)
                    Text("Comment / bài: $feedCommentCount", fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                    Slider(
                        value = feedCommentCount.toFloat(),
                        onValueChange = { feedCommentCount = it.toInt() },
                        valueRange = 0f..5f,
                        steps = 5,
                        colors = iosSliderColors
                    )
                    when (visitActionMode) {
                        VisitActionMode.COMMENT_ONLY -> Text(
                            "Chỉ comment: bot mở profile, không like, chỉ gửi bình luận bài.",
                            fontSize = 11.sp,
                            color = ZpColors.TextSecondary,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        VisitActionMode.CHAT_ONLY -> Text(
                            "Chỉ tin: sau tap bạn bè bot gửi tin trong khung chat, không vào profile.",
                            fontSize = 11.sp,
                            color = ZpColors.TextSecondary,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        else -> Unit
                    }
                }
            }
            item { IosSectionLabel("DANH BẠ (VISIT)") }
            item {
                var handledCount by remember { mutableIntStateOf(visitHandledContacts.count()) }
                val recentHandled = remember(handledCount) { visitHandledContacts.recent(12) }
                IosCard {
                    Text(
                        "Đã xử lý (like / comment / tin): $handledCount",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.W500
                    )
                    Text(
                        "Bot bỏ qua tên có trong danh sách; danh bạ A–Z — cuộn khi cả màn đã xử lý.",
                        fontSize = 11.sp,
                        color = ZpColors.TextSecondary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    if (recentHandled.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        recentHandled.take(6).forEach { entry ->
                            Text(
                                "• ${entry.displayName} (${entry.outcome.lowercase()})",
                                fontSize = 11.sp,
                                color = ZpColors.TextSecondary
                            )
                        }
                        if (handledCount > 6) {
                            Text(
                                "… và ${handledCount - 6} người khác",
                                fontSize = 11.sp,
                                color = ZpColors.TextSecondary
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            visitHandledContacts.clear()
                            handledCount = 0
                            Toast.makeText(
                                this@MainActivity,
                                "Đã xóa danh sách đã xử lý Visit",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Xóa danh sách đã xử lý", fontSize = 13.sp)
                    }
                }
            }
            item {
                IosCard {
                    Text("Số comment / profile (0 = không gửi)", fontSize = 12.sp, color = ZpColors.TextSecondary)
                    Text("Comment / profile: $visitCommentCount", fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                    Slider(
                        value = visitCommentCount.toFloat(),
                        onValueChange = { visitCommentCount = it.toInt() },
                        valueRange = 0f..5f,
                        steps = 5,
                        colors = iosSliderColors,
                        enabled = visitActionMode != VisitActionMode.CHAT_ONLY
                    )
                    if (visitActionMode == VisitActionMode.CHAT_ONLY) {
                        Text(
                            "Chế độ tin: comment profile tắt.",
                            fontSize = 11.sp,
                            color = ZpColors.TextSecondary,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("Số tin / contact (chat)", fontSize = 12.sp, color = ZpColors.TextSecondary)
                    Text("Tin / contact: $visitChatCount", fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                    Slider(
                        value = visitChatCount.toFloat(),
                        onValueChange = { visitChatCount = it.toInt() },
                        valueRange = 0f..5f,
                        steps = 5,
                        colors = iosSliderColors,
                        enabled = visitActionMode == VisitActionMode.CHAT_ONLY
                    )
                }
            }
            item { IosSectionLabel("NỘI DUNG (MỖI DÒNG 1 CÂU)") }
            item {
                IosCard {
                    Text(
                        "Bot chọn ngẫu nhiên một dòng mỗi lần gửi. Thêm bao nhiêu dòng cũng được.",
                        fontSize = 12.sp,
                        color = ZpColors.TextSecondary
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = visitCommentsText,
                        onValueChange = { visitCommentsText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 160.dp, max = 360.dp),
                        placeholder = { Text("👍\n🔥 Xịn quá\n💖 Thích lắm") },
                        minLines = 6
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = {
                            visitCommentsText = settingsManager.defaultVisitCommentsText()
                        }) { Text("Khôi phục mặc định") }
                        val lineCount = visitCommentsText.lines().count { it.trim().isNotEmpty() }
                        Text("$lineCount câu", fontSize = 12.sp, color = ZpColors.TextSecondary)
                    }
                }
            }
            item {
                Button(
                    onClick = {
                        val comments = visitCommentsText.lines()
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                        val base = settingsManager.load()
                        onSave(
                            base.copy(
                                visitCommentCount = visitCommentCount,
                                visitChatCount = visitChatCount,
                                feedCommentCount = feedCommentCount,
                                visitActionMode = visitActionMode.name,
                                visitCommentList = if (comments.isEmpty()) base.visitCommentList else comments
                            )
                        )
                        Toast.makeText(this@MainActivity, "✅ Đã lưu bình luận", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ZpColors.AccentBlue),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Lưu bình luận", fontSize = 15.sp, fontWeight = FontWeight.W500)
                }
            }
        }
    }

    // ─── UI Tree Screen ──────────────────────────────────────────

    @Composable
    fun UiTreeScreen() {
        var treeResult by remember { mutableStateOf(logger.readUiTree()) }
        var filter by remember { mutableStateOf("") }
        var showOnlyClickable by remember { mutableStateOf(false) }
        var showOnlyWithId by remember { mutableStateOf(false) }

        val nodes = treeResult?.nodes ?: emptyList()
        val filtered = remember(nodes, filter, showOnlyClickable, showOnlyWithId) {
            nodes.filter { node ->
                val matchText = filter.isEmpty()
                    || node.text.contains(filter, ignoreCase = true)
                    || node.resourceId.contains(filter, ignoreCase = true)
                    || node.className.contains(filter, ignoreCase = true)
                val matchClickable = !showOnlyClickable || node.clickable
                val matchId = !showOnlyWithId || node.resourceId.isNotEmpty()
                matchText && matchClickable && matchId
            }
        }

        var storedIds by remember { mutableStateOf(zaloIdStore.listStoredIdsForDebug()) }
        fun refreshStoredIds() {
            storedIds = zaloIdStore.listStoredIdsForDebug()
        }
        LaunchedEffect(Unit) {
            refreshStoredIds()
        }
        LaunchedEffect(treeResult?.scannedAt) {
            refreshStoredIds()
        }

        Column(Modifier.fillMaxSize().background(ZpColors.BgPage)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("UI Tree", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = ZpColors.TextPrimary)
                if (treeResult != null) {
                    Text(
                        "${treeResult!!.count} nodes · quét lúc ${treeResult!!.scannedAt}",
                        fontSize = 13.sp,
                        color = ZpColors.TextSecondary
                    )
                } else {
                    Text("Chưa có dữ liệu — mở Zalo rồi bấm Quét", fontSize = 13.sp, color = ZpColors.TextSecondary)
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    IosSecondaryButton("Quét", onClick = {
                        val root = ZaloPilotAccessibilityService.instance?.rootInActiveWindow
                        if (root == null) {
                            Toast.makeText(this@MainActivity, "Mở Zalo trước rồi quét", Toast.LENGTH_SHORT).show()
                        } else {
                            nodeFinder.dumpToFile(root)
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                treeResult = logger.readUiTree()
                                Toast.makeText(this@MainActivity, "Quét xong ${treeResult?.count} nodes", Toast.LENGTH_SHORT).show()
                            }, 800)
                        }
                    })
                    IosSecondaryButton("Quét UI", onClick = {
                        val root = ZaloPilotAccessibilityService.instance?.rootInActiveWindow
                        if (root == null) {
                            Toast.makeText(this@MainActivity, "Mở Zalo trước rồi quét", Toast.LENGTH_SHORT).show()
                        } else {
                            runCatching {
                                uiScanner.forceScan(root)
                                val f = zaloIdStore.exportToJson()
                                refreshStoredIds()
                                Toast.makeText(this@MainActivity, "Đã quét UI & lưu ${f.name}", Toast.LENGTH_SHORT).show()
                            }.onFailure { e ->
                                Toast.makeText(this@MainActivity, "${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    })
                    IosSecondaryButton("Load lại", onClick = {
                        treeResult = logger.readUiTree()
                        Toast.makeText(this@MainActivity, "Đã load lại", Toast.LENGTH_SHORT).show()
                    })
                    IosSecondaryButton("Lưu", onClick = { exportUiTree() })
                    IosSecondaryButton("Copy JSON", onClick = { copyUiTreeJson() })
                }
            }

            IosCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("ID đã học", fontSize = 14.sp, fontWeight = FontWeight.W600, color = ZpColors.TextPrimary)
                            Text("ZaloIDStore · bấm Quét UI trên Zalo để cập nhật", fontSize = 11.sp, color = ZpColors.TextSecondary)
                        }
                        TextButton(
                            onClick = {
                                refreshStoredIds()
                                Toast.makeText(this@MainActivity, "Đã làm mới", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Text("Làm mới", fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    storedIds.forEach { (label, value) ->
                        Text(label, fontSize = 10.sp, color = ZpColors.TextSecondary)
                        Text(
                            value,
                            fontSize = 12.sp,
                            color = if (value.startsWith("—")) ZpColors.TextSecondary else ZpColors.TextPrimary,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(
                                ClipData.newPlainText("ZaloPilot IDs", zaloIdStore.getStoredIdsDebugTextWithKeys())
                            )
                            Toast.makeText(this@MainActivity, "Đã copy (nhãn + key)", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Copy chi tiết", fontSize = 13.sp)
                    }
            }

            Column(Modifier.background(ZpColors.BgCard).padding(12.dp)) {
                OutlinedTextField(
                    value = filter, onValueChange = { filter = it },
                    placeholder = { Text("Tìm text / id / class...", fontSize = 13.sp) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = showOnlyClickable, onClick = { showOnlyClickable = !showOnlyClickable },
                        label = { Text("Clickable", fontSize = 11.sp) })
                    FilterChip(selected = showOnlyWithId, onClick = { showOnlyWithId = !showOnlyWithId },
                        label = { Text("Có ID", fontSize = 11.sp) })
                    Text("${filtered.size} / ${nodes.size}", fontSize = 11.sp, color = ZpColors.TextSecondary,
                        modifier = Modifier.align(Alignment.CenterVertically))
                }
            }

            if (nodes.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Chưa có dữ liệu", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = ZpColors.TextPrimary)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Mở Zalo tab Nhật ký → bấm Quét",
                            color = ZpColors.TextSecondary,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(filtered) { node -> UiNodeCard(node) }
                }
            }
        }
    }

    @Composable
    fun UiNodeCard(node: UiNodeEntry) {
        val indentDp = (node.depth * 8).coerceAtMost(80)
        val bgColor = when {
            node.text.contains("Thích", ignoreCase = true) -> Color(0xFFE8F5E9)
            node.text.contains("Bình luận", ignoreCase = true) -> Color(0xFFE3F2FD)
            node.clickable && node.resourceId.isNotEmpty() -> Color(0xFFFFF8E1)
            else -> Color.White
        }
        Card(modifier = Modifier.fillMaxWidth().padding(start = indentDp.dp),
            colors = CardDefaults.cardColors(containerColor = bgColor),
            shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (node.clickable) {
                        Text("TAP", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = ZpColors.AccentBlue)
                    }
                    if (node.checked) {
                        Text("ON", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = ZpColors.ColorGreen)
                    }
                    Text(node.className, fontSize = 10.sp, color = ZpColors.TextSecondary)
                }
                if (node.text.isNotEmpty()) {
                    Text(node.text, fontSize = 13.sp, fontWeight = FontWeight.W500, color = ZpColors.TextPrimary)
                }
                if (node.resourceId.isNotEmpty())
                    Text(node.resourceId, fontSize = 10.sp, color = ZpColors.AccentBlue)
            }
        }
    }

    // ─── Log Screen ──────────────────────────────────────────────

    @Composable
    fun LogScreen(
        logsSlim: List<LogEntry>,
        logsVerbose: List<LogEntry>,
        logsError: List<LogEntry>,
        verboseUiTreeLog: Boolean,
        verboseLikeContextLog: Boolean,
        onVerboseUiTreeChange: (Boolean) -> Unit,
        onVerboseLikeContextChange: (Boolean) -> Unit,
        onClearLogs: () -> Unit
    ) {
        var subTab by remember { mutableIntStateOf(0) }
        val activeLogs = when (subTab) {
            0 -> logsSlim
            1 -> logsVerbose
            else -> logsError
        }
        fun activeTitle(): String = when (subTab) {
            0 -> "Slim"
            1 -> "Verbose"
            else -> "Lỗi"
        }

        fun tagColor(tag: LogTag): Color = when (tag) {
            LogTag.POLL -> Color(0xFF5C6BC0)
            LogTag.EVENT_HINT -> Color(0xFF8E24AA)
            LogTag.SCAN -> Color(0xFF00838F)
            LogTag.FOUND -> Color(0xFF2E7D32)
            LogTag.CLICK -> Color(0xFFE65100)
            LogTag.SCROLL -> Color(0xFF1565C0)
            LogTag.ERROR -> Color(0xFFC62828)
            LogTag.STATE -> Color(0xFF546E7A)
        }

        fun isClickFailure(log: LogEntry): Boolean {
            if (log.tag != LogTag.CLICK) return false
            val r = log.result.uppercase(Locale.getDefault())
            return r.contains("FAIL") || r.contains("CLICK_FAILED") || r.contains("GIVEUP") ||
                r.contains("ALL_PARENTS_FAILED") || r.contains("TIMEOUT_OR_FAIL") || r.contains("ABORT")
        }

        fun isScrollFailure(log: LogEntry): Boolean =
            log.tag == LogTag.SCROLL && log.result.contains("FAIL", ignoreCase = true)

        Column(Modifier.fillMaxSize().background(ZpColors.BgPage)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("Log", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = ZpColors.TextPrimary)
                Text("${activeLogs.size} dòng · ${activeTitle()}", fontSize = 13.sp, color = ZpColors.TextSecondary)
            }
            TabRow(
                selectedTabIndex = subTab,
                modifier = Modifier.padding(horizontal = 16.dp),
                containerColor = ZpColors.BgCard,
                contentColor = ZpColors.AccentBlue,
                divider = { HorizontalDivider(color = ZpColors.Divider, thickness = 0.5.dp) }
            ) {
                listOf("Slim", "Verbose", "Lỗi").forEachIndexed { i, label ->
                    Tab(
                        selected = subTab == i,
                        onClick = { subTab = i },
                        text = {
                            Text(
                                label,
                                fontSize = 13.sp,
                                color = if (subTab == i) ZpColors.AccentBlue else ZpColors.TextSecondary
                            )
                        }
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .horizontalScroll(rememberScrollState())
            ) {
                IosSecondaryButton("Xóa", onClick = {
                    when (subTab) {
                        0 -> logger.clearSlimLogs()
                        1 -> logger.clearVerboseLogs()
                        else -> logger.clearErrorLogs()
                    }
                    Toast.makeText(this@MainActivity, "Đã xóa log ${activeTitle()}", Toast.LENGTH_SHORT).show()
                })
                IosSecondaryButton("Xuất", onClick = {
                    when (subTab) {
                        0 -> exportLogText(logger.getSlimLogText(), suffix = "slim")
                        1 -> exportLogText(logger.getVerboseLogText(), suffix = "verbose")
                        else -> exportLogText(logger.getErrorLogText(), suffix = "error")
                    }
                })
                IosSecondaryButton("Dump UI", onClick = { dumpZaloUI() })
                IosSecondaryButton("Xuất dump", onClick = { exportUiDumpJson() })
                val unlikedDumpExists = File(filesDir, "ui_dump_unliked.json").exists()
                IosSecondaryButton(
                    "Dump chưa like",
                    onClick = { exportInternalDumpJson("ui_dump_unliked.json", "ZaloPilot_ui_dump_unliked") },
                    enabled = unlikedDumpExists
                )
                val likedDumpExists = File(filesDir, "ui_dump_liked.json").exists()
                IosSecondaryButton(
                    "Dump đã like",
                    onClick = { exportInternalDumpJson("ui_dump_liked.json", "ZaloPilot_ui_dump_liked") },
                    enabled = likedDumpExists
                )
                IosSecondaryButton("Copy log", onClick = {
                    val txt = when (subTab) {
                        0 -> logger.getSlimLogText()
                        1 -> logger.getVerboseLogText()
                        else -> logger.getErrorLogText()
                    }
                    copyLogsText(txt, activeTitle())
                })
            }
            IosCard(
                modifier = Modifier.padding(horizontal = 16.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Dump cây UI khi lỗi", fontSize = 14.sp, color = ZpColors.TextPrimary)
                    Switch(checked = verboseUiTreeLog, onCheckedChange = onVerboseUiTreeChange, colors = iosSwitchColors)
                }
                HorizontalDivider(color = ZpColors.Divider, thickness = 0.5.dp)
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Log chi tiết nút Like", fontSize = 14.sp, color = ZpColors.TextPrimary)
                    Switch(checked = verboseLikeContextLog, onCheckedChange = onVerboseLikeContextChange, colors = iosSwitchColors)
                }
            }
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (activeLogs.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Chưa có log", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = ZpColors.TextPrimary)
                            Spacer(Modifier.height(6.dp))
                            Text("${activeTitle()} · Mở Zalo và chạy bot", color = ZpColors.TextSecondary, fontSize = 13.sp)
                        }
                    }
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(activeLogs) { log ->
                            val accent = tagColor(log.tag)
                            val failure = isClickFailure(log) || isScrollFailure(log)
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = when {
                                        failure -> Color(0xFFFFEBEE)
                                        log.tag == LogTag.ERROR -> Color(0xFFFFCDD2)
                                        else -> ZpColors.BgCard
                                    }
                                ),
                                shape = RoundedCornerShape(12.dp),
                                border = if (failure) BorderStroke(1.dp, Color(0xFFE57373)) else null
                            ) {
                                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Box(
                                        Modifier.size(10.dp).clip(CircleShape).background(accent).align(Alignment.Top)
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Surface(
                                                color = accent.copy(alpha = 0.15f),
                                                shape = RoundedCornerShape(6.dp)
                                            ) {
                                                Text(
                                                    log.tag.name,
                                                    Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = accent
                                                )
                                            }
                                            if (failure) {
                                                Text("THẤT BẠI", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = ZpColors.ColorRed)
                                            }
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        Text(log.target, fontSize = 13.sp, color = ZpColors.TextPrimary)
                                        Text(log.result, fontSize = 12.sp, color = ZpColors.TextSecondary)
                                        Spacer(Modifier.height(4.dp))
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text(log.timestamp, fontSize = 11.sp, color = ZpColors.TextSecondary)
                                            val meta = buildList {
                                                if (!log.foregroundPkg.isNullOrBlank()) add("pkg ${log.foregroundPkg}")
                                                log.durationMs?.let { add("${it}ms") }
                                            }.joinToString(" · ")
                                            if (meta.isNotEmpty()) {
                                                Text(meta, fontSize = 10.sp, color = ZpColors.TextSecondary, maxLines = 2)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────

    private fun copyUiTreeJson() {
        try {
            val text = logger.uiTreeFile.takeIf { it.exists() }?.readText()
                ?: "Chưa có dữ liệu — bấm Quét trước"
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("ZaloPilot UI Tree", text))
            Toast.makeText(this, "✅ Đã copy uitree.json vào clipboard", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "❌ ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyLogsText(text: String, label: String) {
        try {
            val safe = text.ifBlank { "Chưa có log $label" }
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("ZaloPilot $label Log", safe))
            Toast.makeText(this, "✅ Đã copy log $label vào clipboard", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "❌ ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportUiTree() {
        try {
            val src = logger.uiTreeFile
            if (!src.exists()) {
                Toast.makeText(this, "⚠️ Chưa có uitree — bấm Quét trước", Toast.LENGTH_SHORT).show()
                return
            }
            val dest = java.io.File(
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                "ZaloPilot_uitree_${System.currentTimeMillis()}.json"
            )
            src.copyTo(dest, overwrite = true)
            Toast.makeText(this, "✅ Đã lưu: ${dest.name}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "❌ ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun exportLogText(text: String, suffix: String) {
        try {
            if (text.isBlank()) {
                Toast.makeText(this, "⚠️ Chưa có log để xuất", Toast.LENGTH_LONG).show()
                return
            }
            val dest = java.io.File(
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                "ZaloPilot_${suffix}_log_${System.currentTimeMillis()}.json"
            )
            dest.writeText(text)
            Toast.makeText(this, "✅ Đã xuất vào Downloads: ${dest.name}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "❌ Lỗi: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun exportUiDumpJson() {
        try {
            val src = File(filesDir, "ui_dump.json")
            if (!src.exists()) {
                Toast.makeText(this, "⚠️ Chưa có ui_dump.json — hãy bấm Dump UI ở nút ZP nổi", Toast.LENGTH_LONG).show()
                return
            }

            val name = "ZaloPilot_ui_dump_${System.currentTimeMillis()}.json"
            val saved = copyFileToDownloads(src, name)
            Toast.makeText(this, "✅ Đã xuất: $saved", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "❌ Lỗi: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun exportInternalDumpJson(internalName: String, prefix: String) {
        try {
            val src = File(filesDir, internalName)
            if (!src.exists()) {
                Toast.makeText(this, "⚠️ Chưa có $internalName", Toast.LENGTH_LONG).show()
                return
            }
            val name = "${prefix}_${System.currentTimeMillis()}.json"
            val saved = copyFileToDownloads(src, name)
            Toast.makeText(this, "✅ Đã xuất: $saved", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "❌ Lỗi: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun copyFileToDownloads(src: File, displayName: String): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Không tạo được file trong Downloads")
            resolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { it.copyTo(out) }
            } ?: throw IllegalStateException("Không mở được output stream Downloads")
            "Downloads/$displayName ($uri)"
        } else {
            val dest = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                displayName
            )
            src.copyTo(dest, overwrite = true)
            dest.absolutePath
        }
    }

    private fun dumpZaloUI() {
        val svc = ZaloPilotAccessibilityService.instance
        if (svc == null) {
            AccessibilityHelper.openAccessibilitySettings(this)
            return
        }
        val root = svc.rootInActiveWindow
        if (root == null) {
            Toast.makeText(this, "⚠️ Không đọc được UI — hãy mở Zalo trước", Toast.LENGTH_SHORT).show()
            return
        }
        logger.log(LogTag.SCAN, "manual debugDump", "START")
        nodeFinder.debugDump(root, maxNodes = 800)
        Toast.makeText(this, "🔍 Đã dump UI vào log — bấm Lưu log để lấy file", Toast.LENGTH_LONG).show()
    }

    private fun startFloatingOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "Cần quyền hiển thị trên app khác để bật nút ZP",
                Toast.LENGTH_LONG
            ).show()
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }
        FloatingMenuService.start(this)
        Toast.makeText(this, "✅ Đã bật nút ZP nổi", Toast.LENGTH_SHORT).show()
    }

    private fun stopFloatingOverlay() {
        FloatingMenuService.stop(this)
        Toast.makeText(this, "✅ Đã tắt nút ZP nổi", Toast.LENGTH_SHORT).show()
    }

    private fun isSetupComplete() = isAccessibilityServiceEnabled() && Settings.canDrawOverlays(this)

    private fun isAccessibilityServiceEnabled(): Boolean {
        val service = "$packageName/com.zalopilot.app.accessibility.ZaloPilotAccessibilityService"
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return enabled.contains(service)
    }
}
