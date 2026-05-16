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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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

    private val zaloBlue = Color(0xFF0068FF)
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
            modifier = Modifier.fillMaxSize().background(Color(0xFFF5F5F5)).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("ZaloPilot", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = zaloBlue)
            Text(AppVersion.fullLabel(), fontSize = 12.sp, color = Color.Gray)
            Text("Thiết lập lần đầu", fontSize = 14.sp, color = Color.Gray)
            Spacer(Modifier.height(32.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(1, 2, 3).forEach { s ->
                    Box(Modifier.size(32.dp).clip(CircleShape)
                        .background(if (s <= step) zaloBlue else Color(0xFFE0E0E0)),
                        contentAlignment = Alignment.Center) {
                        Text("$s", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                    if (s < 3) Box(Modifier.width(32.dp).height(2.dp)
                        .background(if (s < step) zaloBlue else Color(0xFFE0E0E0)))
                }
            }

            Spacer(Modifier.height(32.dp))

            when (step) {
                1 -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (!acc) {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                "Nếu vừa thấy \"ZaloPilot tiếp tục dừng\" — Android tự TẮT Trợ năng. " +
                                    "Màn \"Thông tin ứng dụng → Quyền\" KHÔNG phải chỗ bật Trợ năng.",
                                modifier = Modifier.padding(14.dp),
                                fontSize = 12.sp,
                                color = Color(0xFFC62828),
                                lineHeight = 18.sp
                            )
                        }
                    }
                    StepCard(
                        "♿",
                        "Bật Trợ năng (Accessibility)",
                        "Samsung:\n" +
                            "1. Cài đặt → tìm \"Hỗ trợ tiếp cận\"\n" +
                            "2. Ứng dụng đã cài → ZaloPilot → BẬT\n\n" +
                            "Nếu không bật được: Thông tin ứng dụng ZaloPilot → ⋮ → " +
                            "\"Cho phép cài đặt hạn chế\" → quay lại bật Trợ năng.",
                        "Mở cài đặt Trợ năng ZaloPilot",
                        acc
                    ) {
                        AccessibilityHelper.openAccessibilitySettings(this@MainActivity)
                    }
                    if (!acc && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { AccessibilityHelper.openAppDetailsSettings(this@MainActivity) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Thông tin ứng dụng (cài đặt hạn chế)", fontSize = 13.sp)
                        }
                    }
                }
                2 -> StepCard("🪟", "Hiển thị trên app khác",
                    "Để nút ZP nổi lên trên màn hình Zalo.\n\nBấm nút bên dưới → tìm ZaloPilot → bật lên:",
                    "Mở Cài đặt", overlay) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
                3 -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("✅", fontSize = 48.sp)
                    Spacer(Modifier.height(16.dp))
                    Text("Thiết lập hoàn tất!", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF27AE60))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Trang chủ có BẮT ĐẦU/DỪNG và nút nổi ZP (bật khi cần).",
                        fontSize = 14.sp, color = Color.Gray, textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = {
                        recreate()
                    }, colors = ButtonDefaults.buttonColors(containerColor = zaloBlue),
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(12.dp)) {
                        Text("Vào app chính", fontSize = 15.sp, fontWeight = FontWeight.W500)
                    }
                }
            }
        }
    }

    @Composable
    fun StepCard(icon: String, title: String, desc: String, btnText: String, done: Boolean, onClick: () -> Unit) {
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(icon, fontSize = 40.sp)
                Spacer(Modifier.height(12.dp))
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(desc, fontSize = 13.sp, color = Color.Gray, textAlign = TextAlign.Center, lineHeight = 20.sp)
                Spacer(Modifier.height(20.dp))
                if (done) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(20.dp).clip(CircleShape).background(Color(0xFF27AE60)), contentAlignment = Alignment.Center) {
                            Text("✓", color = Color.White, fontSize = 12.sp)
                        }
                        Text("Đã cấp quyền ✓", color = Color(0xFF27AE60), fontWeight = FontWeight.W500)
                    }
                } else {
                    Button(onClick = onClick, colors = ButtonDefaults.buttonColors(containerColor = zaloBlue),
                        modifier = Modifier.fillMaxWidth().height(46.dp), shape = RoundedCornerShape(10.dp)) {
                        Text(btnText)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Sau khi bật xong, app tự chuyển bước tiếp", fontSize = 11.sp, color = Color.Gray, textAlign = TextAlign.Center)
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
                            Toast.makeText(this@MainActivity,
                                if (running) "▶ ZaloPilot đang chạy" else "■ ZaloPilot đã dừng",
                                Toast.LENGTH_SHORT).show()
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

        Scaffold(bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                listOf("Trang chủ", "Cài đặt", "Nhật ký", "Script", "UI").forEachIndexed { i, label ->
                    NavigationBarItem(
                        selected = selectedTab == i,
                        onClick = {
                            selectedTab = i
                            if (i == 2) {
                                logsSlim = logger.readSlimLogs()
                                logsVerbose = logger.readVerboseLogs()
                                logsError = logger.readErrorLogs()
                            }
                        },
                        icon = { Text(listOf("🏠", "⚙️", "📋", "📜", "🌳")[i], fontSize = 18.sp) },
                        label = { Text(label, fontSize = 10.sp) }
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
                        accessibilityConnected
                    )
                    1 -> SettingsScreen(settings) { settings = it; settingsManager.save(it) }
                    2 -> LogScreen(
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
                    3 -> ScriptScreen(scriptStore = scriptStore, zaloBlue = zaloBlue)
                    4 -> UiTreeScreen()
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
        accessibilityConnected: Boolean
    ) {
        LazyColumn(modifier = Modifier.fillMaxSize().background(Color(0xFFF5F5F5)),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!accessibilityOn || !accessibilityConnected) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                if (!accessibilityOn) "⚠️ Chưa bật Trợ năng ZaloPilot"
                                else "⚠️ Trợ năng đã bật nhưng chưa kết nối",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.W600,
                                color = Color(0xFFE65100)
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                if (!accessibilityOn) {
                                    "Bot không chạy được. Bấm nút bên dưới — app mở đúng màn cài đặt → bật ZaloPilot."
                                } else {
                                    "Mở Zalo (Nhật ký hoặc Danh bạ) vài giây, quay lại app. Hoặc tắt/bật lại ZaloPilot trong Trợ năng."
                                },
                                fontSize = 12.sp,
                                color = Color(0xFF5D4037),
                                lineHeight = 18.sp
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = { AccessibilityHelper.openAccessibilitySettings(this@MainActivity) },
                                modifier = Modifier.fillMaxWidth().height(46.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE65100)),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(
                                    if (!accessibilityOn) "♿  Mở cài đặt Trợ năng (ZaloPilot)"
                                    else "♿  Mở lại cài đặt Trợ năng",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.W600
                                )
                            }
                        }
                    }
                }
            }
            item {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(zaloBlue).padding(20.dp)) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("ZaloPilot", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.W500)
                                Text("Auto Like Zalo", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
                                Text(
                                    AppVersion.fullLabel(),
                                    color = Color.White.copy(alpha = 0.65f),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Surface(shape = RoundedCornerShape(99.dp), color = if (isRunning) Color(0xFF27AE60) else Color.White.copy(alpha = 0.2f)) {
                                Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Box(Modifier.size(7.dp).clip(CircleShape).background(Color.White))
                                    Text(if (isRunning) "Đang chạy" else "Đã dừng", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.W500)
                                }
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        val runningMode = remember(settings.likeModeStr) {
                            runCatching { LikeMode.valueOf(settings.likeModeStr) }
                                .getOrDefault(LikeMode.FEED)
                        }
                        if (isRunning) {
                            Text(
                                "Đang chạy: ${if (runningMode == LikeMode.VISIT) "Like danh bạ" else "Like Nhật ký"}",
                                color = Color.White.copy(alpha = 0.9f),
                                fontSize = 13.sp
                            )
                            Spacer(Modifier.height(10.dp))
                            Button(
                                onClick = { AccessibilityHelper.requestStopAutoLike() },
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE24B4A)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("■  DỪNG", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.W600)
                            }
                        } else {
                            Text(
                                "Chạm để like — tham số trong tab Cài đặt",
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 12.sp
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = {
                                        settingsManager.setLikeMode(LikeMode.FEED)
                                        AccessibilityHelper.requestStartAutoLike(this@MainActivity, LikeMode.FEED)
                                    },
                                    modifier = Modifier.weight(1f).height(52.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF27AE60)),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text(
                                        "▶ Like Nhật ký",
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.W600,
                                        textAlign = TextAlign.Center
                                    )
                                }
                                Button(
                                    onClick = {
                                        settingsManager.setLikeMode(LikeMode.VISIT)
                                        AccessibilityHelper.requestStartAutoLike(this@MainActivity, LikeMode.VISIT)
                                    },
                                    modifier = Modifier.weight(1f).height(52.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8E44AD)),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text(
                                        "▶ Like danh bạ",
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.W600,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = {
                                    val intent = packageManager.getLaunchIntentForPackage("com.zing.zalo")
                                    if (intent == null) {
                                        Toast.makeText(this@MainActivity, "⚠️ Chưa cài Zalo", Toast.LENGTH_SHORT).show()
                                    } else {
                                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        startActivity(intent)
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.18f)),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                            ) {
                                Text("Mở Zalo", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.W500)
                            }
                            Button(
                                onClick = {
                                    if (floatingOverlayOn) {
                                        stopFloatingOverlay()
                                    } else {
                                        startFloatingOverlay()
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (floatingOverlayOn) {
                                        Color(0xFFE24B4A).copy(alpha = 0.35f)
                                    } else {
                                        Color.White.copy(alpha = 0.18f)
                                    }
                                ),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    if (floatingOverlayOn) "Tắt nút nổi" else "Bật nút nổi",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.W500
                                )
                            }
                        }
                    }
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("TIẾN ĐỘ HÔM NAY", fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.W500)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            StatCard("${progress.todayLikeCount}", "Đã like", zaloBlue, Modifier.weight(1f))
                            StatCard("${progress.todayPostsHandledCount}", "Đã duyệt", Color(0xFF555555), Modifier.weight(1f))
                            StatCard("${settings.dailyLimit}", "Giới hạn", Color(0xFF444444), Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(10.dp))
                        val pct = if (settings.dailyLimit <= 0) 0f
                        else (progress.todayLikeCount.toFloat() / settings.dailyLimit).coerceIn(0f, 1f)
                        LinearProgressIndicator(progress = { pct },
                            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(99.dp)),
                            color = zaloBlue, trackColor = Color(0xFFE0E0E0))
                        Spacer(Modifier.height(4.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                if (settings.dailyLimit <= 0) "${progress.todayLikeCount} / ∞"
                                else "${progress.todayLikeCount} / ${settings.dailyLimit}",
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                            Text(if (settings.dailyLimit <= 0) "—" else "${(pct * 100).toInt()}%", fontSize = 12.sp, color = Color.Gray)
                        }
                    }
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("TỰ CHẠY", fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.W500)
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(if (settingsManager.isAutoStart()) "🤖 Bật" else "👆 Tắt", fontSize = 14.sp, fontWeight = FontWeight.W500)
                                Text(
                                    "Khi bật: mở Zalo sẽ chạy theo lần chọn Nhật ký/Danh bạ gần nhất",
                                    fontSize = 12.sp,
                                    color = Color.Gray
                                )
                            }
                            Switch(checked = settingsManager.isAutoStart(),
                                onCheckedChange = { settingsManager.setAutoStart(it) },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = zaloBlue))
                        }
                    }
                }
            }
            item {
                // Nút "Bật nút nổi" đã đưa lên header cho dễ thao tác.
            }
        }
    }

    @Composable
    fun StatCard(value: String, label: String, color: Color, modifier: Modifier = Modifier) {
        Box(modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFFF5F5F5)).padding(12.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(value, fontSize = 24.sp, fontWeight = FontWeight.W500, color = color)
                Text(label, fontSize = 12.sp, color = Color.Gray)
            }
        }
    }

    // ─── Settings ────────────────────────────────────────────────

    @Composable
    fun SettingsScreen(settings: LikeSettings, onSave: (LikeSettings) -> Unit) {
        var dailyLimit by remember { mutableIntStateOf(settings.dailyLimit) }
        var feedMode by remember { mutableStateOf(settingsManager.getFeedMode()) }
        var delayMin by remember { mutableStateOf((settings.delayMinMs / 1000).toString()) }
        var delayMax by remember { mutableStateOf((settings.delayMaxMs / 1000).toString()) }
        var ecoMode by remember { mutableStateOf(settings.ecoMode) }
        var requireCharging by remember { mutableStateOf(settings.requireCharging) }
        var lowBatteryPauseEnabled by remember { mutableStateOf(settings.lowBatteryPauseEnabled) }
        var lowBatteryThreshold by remember { mutableIntStateOf(settings.lowBatteryThreshold) }
        var pauseWhenZaloAway by remember { mutableStateOf(settings.pauseWhenZaloAway) }
        var visitLikeCount by remember { mutableIntStateOf(settings.visitLikeCount) }
        var visitCommentCount by remember { mutableIntStateOf(settings.visitCommentCount) }
        var feedCommentCount by remember { mutableIntStateOf(settings.feedCommentCount) }
        var visitMaxProfiles by remember { mutableIntStateOf(settings.visitMaxProfiles) }
        var visitActionMode by remember {
            mutableStateOf(
                try { VisitActionMode.valueOf(settings.visitActionMode) } catch (e: Exception) {
                    VisitActionMode.LIKE_ONLY
                }
            )
        }
        var visitCommentsText by remember {
            mutableStateOf(settings.visitCommentList.joinToString("\n"))
        }

        LazyColumn(modifier = Modifier.fillMaxSize().background(Color(0xFFF5F5F5)),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(zaloBlue).padding(20.dp)) {
                    Column {
                        Text("Cài đặt", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.W500)
                        Text("Tùy chỉnh tốc độ like", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
                        Text(
                            AppVersion.fullLabel(),
                            color = Color.White.copy(alpha = 0.65f),
                            fontSize = 11.sp
                        )
                    }
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("GIỚI HẠN LIKE", fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.W500)
                        Spacer(Modifier.height(8.dp))
                        Text("Tối đa / ngày: $dailyLimit bài", fontSize = 13.sp)
                        Text("Kéo từ 20 tới 3000 — tùy thích", fontSize = 11.sp, color = Color.Gray)
                        Slider(value = dailyLimit.toFloat(), onValueChange = { dailyLimit = it.toInt() },
                            valueRange = 20f..3000f, colors = SliderDefaults.colors(thumbColor = zaloBlue, activeTrackColor = zaloBlue))
                    }
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("TỐC ĐỘ LIKE", fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.W500)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("Delay tối thiểu (giây)", fontSize = 12.sp, color = Color.Gray)
                                OutlinedTextField(value = delayMin, onValueChange = { delayMin = it }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            }
                            Column(Modifier.weight(1f)) {
                                Text("Delay tối đa (giây)", fontSize = 12.sp, color = Color.Gray)
                                OutlinedTextField(value = delayMax, onValueChange = { delayMax = it }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            }
                        }
                    }
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("TIẾT KIỆM PIN (NGỦ TRƯA)", fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.W500)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Poll/scan chậm hơn, delay like dài hơn (~×1.5), màn tắt ít dừng nhầm. Mượt, ít nóng hơn; like chậm hơn. Nên bật khi cắm sạc hoặc để máy nghỉ.",
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                        }
                        Switch(
                            checked = ecoMode,
                            onCheckedChange = { ecoMode = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = zaloBlue)
                        )
                    }
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("PIN & SẠC", fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.W500)
                        Spacer(Modifier.height(10.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Chỉ chạy khi cắm sạc", fontSize = 13.sp, fontWeight = FontWeight.W500)
                                Text(
                                    "Rút sạc → bot tạm dừng. Cắm lại → tự chạy tiếp.",
                                    fontSize = 11.sp,
                                    color = Color.Gray
                                )
                            }
                            Switch(
                                checked = requireCharging,
                                onCheckedChange = { requireCharging = it },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = zaloBlue)
                            )
                        }

                        Spacer(Modifier.height(12.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Pause khi pin thấp", fontSize = 13.sp, fontWeight = FontWeight.W500)
                                Text(
                                    "Pin xuống dưới ngưỡng → tạm dừng. Sạc lên/cắm sạc → chạy lại.",
                                    fontSize = 11.sp,
                                    color = Color.Gray
                                )
                            }
                            Switch(
                                checked = lowBatteryPauseEnabled,
                                onCheckedChange = { lowBatteryPauseEnabled = it },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = zaloBlue)
                            )
                        }

                        if (lowBatteryPauseEnabled) {
                            Spacer(Modifier.height(6.dp))
                            Text("Ngưỡng pin: $lowBatteryThreshold%", fontSize = 12.sp, color = Color.Gray)
                            Slider(
                                value = lowBatteryThreshold.toFloat(),
                                onValueChange = { lowBatteryThreshold = it.toInt() },
                                valueRange = 5f..50f,
                                colors = SliderDefaults.colors(thumbColor = zaloBlue, activeTrackColor = zaloBlue)
                            )
                        }

                        Spacer(Modifier.height(12.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Tiết kiệm khi rời Zalo", fontSize = 13.sp, fontWeight = FontWeight.W500)
                                Text(
                                    "Mở app khác → bot chờ. Mở Zalo lại → tự chạy tiếp (không cần Start lại).",
                                    fontSize = 11.sp,
                                    color = Color.Gray
                                )
                            }
                            Switch(
                                checked = pauseWhenZaloAway,
                                onCheckedChange = { pauseWhenZaloAway = it },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = zaloBlue)
                            )
                        }
                    }
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("LIKE DANH BẠ (VISIT)", fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.W500)
                        Text(
                            "Bấm Like danh bạ ở Trang chủ để chạy — chỉnh số liệu bên dưới",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("Like mỗi profile: $visitLikeCount", fontSize = 13.sp)
                        Slider(
                            value = visitLikeCount.toFloat(),
                            onValueChange = { visitLikeCount = it.toInt() },
                            valueRange = 0f..10f,
                            steps = 9,
                            colors = SliderDefaults.colors(thumbColor = zaloBlue, activeTrackColor = zaloBlue)
                        )
                        Text("Comment mỗi profile: $visitCommentCount", fontSize = 13.sp)
                        Slider(
                            value = visitCommentCount.toFloat(),
                            onValueChange = { visitCommentCount = it.toInt() },
                            valueRange = 0f..5f,
                            steps = 5,
                            colors = SliderDefaults.colors(thumbColor = zaloBlue, activeTrackColor = zaloBlue)
                        )
                        Text("Profile tối đa / phiên: $visitMaxProfiles", fontSize = 13.sp)
                        Slider(
                            value = visitMaxProfiles.toFloat(),
                            onValueChange = { visitMaxProfiles = it.toInt() },
                            valueRange = 5f..200f,
                            colors = SliderDefaults.colors(thumbColor = zaloBlue, activeTrackColor = zaloBlue)
                        )
                        listOf(
                            VisitActionMode.LIKE_ONLY to "Chỉ like",
                            VisitActionMode.COMMENT_ONLY to "Chỉ comment",
                            VisitActionMode.MIX to "Mix"
                        ).forEach { (mode, label) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = visitActionMode == mode,
                                    onClick = { visitActionMode = mode },
                                    colors = RadioButtonDefaults.colors(selectedColor = zaloBlue)
                                )
                                Text(label, fontSize = 13.sp)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("Nội dung bình luận (mỗi dòng 1 câu)", fontSize = 12.sp, color = Color.Gray)
                        OutlinedTextField(
                            value = visitCommentsText,
                            onValueChange = { visitCommentsText = it },
                            modifier = Modifier.fillMaxWidth().height(120.dp),
                            placeholder = { Text("👍\nHay quá!") }
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                visitCommentsText = "👍\n❤️\nHay quá!\nTuyệt vời!"
                            }) { Text("Khôi phục mặc định") }
                            TextButton(onClick = {
                                progressManager.resetVisitIndex()
                                Toast.makeText(this@MainActivity, "Đã reset visitIndex", Toast.LENGTH_SHORT).show()
                            }) { Text("Reset visitIndex") }
                        }
                    }
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("CHẾ ĐỘ FEED", fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.W500)
                        Spacer(Modifier.height(8.dp))
                        Text("Cách bot cuộn khi không tìm thấy like", fontSize = 12.sp, color = Color.Gray)
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
                                    .background(if (feedMode == mode) zaloBlue.copy(alpha = 0.1f) else Color.Transparent)
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = feedMode == mode,
                                    onClick = { feedMode = mode },
                                    colors = RadioButtonDefaults.colors(selectedColor = zaloBlue)
                                )
                                Text(label, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp))
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Comment sau mỗi lần like (0 = chỉ like). Dùng danh sách câu ở mục Like danh bạ.",
                            fontSize = 11.sp,
                            color = Color.Gray
                        )
                        Text("Comment / bài (Nhật ký): $feedCommentCount", fontSize = 13.sp)
                        Slider(
                            value = feedCommentCount.toFloat(),
                            onValueChange = { feedCommentCount = it.toInt() },
                            valueRange = 0f..5f,
                            steps = 5,
                            colors = SliderDefaults.colors(thumbColor = zaloBlue, activeTrackColor = zaloBlue)
                        )
                    }
                }
            }
            item {
                Text(
                    "Phiên bản ${AppVersion.fullLabel()}",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }
            item {
                Button(onClick = {
                    settingsManager.setFeedMode(feedMode)
                    val comments = visitCommentsText.lines()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    onSave(settings.copy(
                        dailyLimit = dailyLimit,
                        delayMinMs = (delayMin.toLongOrNull() ?: 1L) * 1000L,
                        delayMaxMs = (delayMax.toLongOrNull() ?: 3L) * 1000L,
                        interactModeStr = InteractMode.TAP.name,
                        humanLikeScroll = true,
                        ecoMode = ecoMode,
                        requireCharging = requireCharging,
                        lowBatteryPauseEnabled = lowBatteryPauseEnabled,
                        lowBatteryThreshold = lowBatteryThreshold,
                        pauseWhenZaloAway = pauseWhenZaloAway,
                        likeModeStr = settingsManager.getLikeMode().name,
                        visitLikeCount = visitLikeCount,
                        visitCommentCount = visitCommentCount,
                        feedCommentCount = feedCommentCount,
                        visitMaxProfiles = visitMaxProfiles,
                        visitActionMode = visitActionMode.name,
                        visitCommentList = if (comments.isEmpty()) settings.visitCommentList else comments
                    ))
                    Toast.makeText(this@MainActivity, "✅ Đã lưu cài đặt", Toast.LENGTH_SHORT).show()
                }, modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = zaloBlue),
                    shape = RoundedCornerShape(12.dp)) {
                    Text("Lưu cài đặt", fontSize = 15.sp, fontWeight = FontWeight.W500)
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

        Column(Modifier.fillMaxSize().background(Color(0xFFF5F5F5))) {
            Box(Modifier.fillMaxWidth().background(Color(0xFF1a1a2e)).padding(16.dp)) {
                Column {
                    Text("UI Tree Zalo", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    if (treeResult != null) {
                        Text("${treeResult!!.count} nodes · quét lúc ${treeResult!!.scannedAt}",
                            color = Color.White.copy(0.7f), fontSize = 12.sp)
                    } else {
                        Text("Chưa có dữ liệu — mở Zalo rồi bấm Quét", color = Color.White.copy(0.7f), fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val root = ZaloPilotAccessibilityService.instance?.rootInActiveWindow
                                if (root == null) {
                                    Toast.makeText(this@MainActivity, "⚠️ Mở Zalo trước rồi quét", Toast.LENGTH_SHORT).show()
                                } else {
                                    nodeFinder.dumpToFile(root)
                                    // Đợi file ghi xong rồi load lại
                                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                        treeResult = logger.readUiTree()
                                        Toast.makeText(this@MainActivity, "✅ Quét xong ${treeResult?.count} nodes", Toast.LENGTH_SHORT).show()
                                    }, 800)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0068FF)),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) { Text("🔍 Quét", color = Color.White, fontSize = 13.sp) }

                        Button(
                            onClick = {
                                val root = ZaloPilotAccessibilityService.instance?.rootInActiveWindow
                                if (root == null) {
                                    Toast.makeText(this@MainActivity, "⚠️ Mở Zalo trước rồi quét", Toast.LENGTH_SHORT).show()
                                } else {
                                    runCatching {
                                        uiScanner.forceScan(root)
                                        val f = zaloIdStore.exportToJson()
                                        refreshStoredIds()
                                        Toast.makeText(this@MainActivity, "✅ Đã quét UI & lưu ${f.name}", Toast.LENGTH_SHORT).show()
                                    }.onFailure { e ->
                                        Toast.makeText(this@MainActivity, "❌ ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(0.2f)),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) { Text("🧠 Quét UI", color = Color.White, fontSize = 13.sp) }

                        Button(
                            onClick = {
                                treeResult = logger.readUiTree()
                                Toast.makeText(this@MainActivity, "🔄 Đã load lại", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(0.2f)),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) { Text("🔄 Load lại", color = Color.White, fontSize = 13.sp) }

                        Button(
                            onClick = { exportUiTree() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(0.2f)),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) { Text("💾 Lưu", color = Color.White, fontSize = 13.sp) }

                        Button(
                            onClick = { copyUiTreeJson() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(0.2f)),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) { Text("📋 Copy JSON", color = Color.White, fontSize = 13.sp) }
                    }
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("ID đã học", fontSize = 14.sp, fontWeight = FontWeight.W600)
                            Text("ZaloIDStore · bấm Quét UI trên Zalo để cập nhật", fontSize = 11.sp, color = Color.Gray)
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
                        Text(label, fontSize = 10.sp, color = Color.Gray)
                        Text(
                            value,
                            fontSize = 12.sp,
                            color = if (value.startsWith("—")) Color.Gray else Color(0xFF111111),
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
                        Text("📋 Copy chi tiết", fontSize = 13.sp)
                    }
                }
            }

            Column(Modifier.background(Color.White).padding(12.dp)) {
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
                    Text("${filtered.size} / ${nodes.size}", fontSize = 11.sp, color = Color.Gray,
                        modifier = Modifier.align(Alignment.CenterVertically))
                }
            }

            if (nodes.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🌳", fontSize = 40.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("Mở Zalo tab Nhật ký → bấm Quét", color = Color.Gray, fontSize = 13.sp, textAlign = TextAlign.Center)
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
            shape = RoundedCornerShape(6.dp)) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (node.clickable) Text("👆", fontSize = 10.sp)
                    if (node.checked) Text("✅", fontSize = 10.sp)
                    Text(node.className, fontSize = 10.sp, color = Color.Gray)
                }
                if (node.text.isNotEmpty())
                    Text(node.text, fontSize = 13.sp, fontWeight = FontWeight.W500, color = Color(0xFF111111))
                if (node.resourceId.isNotEmpty())
                    Text(node.resourceId, fontSize = 10.sp, color = Color(0xFF0068FF))
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

        Column(Modifier.fillMaxSize().background(Color(0xFFF5F5F5))) {
            Box(Modifier.fillMaxWidth().background(zaloBlue).padding(20.dp)) {
                Column {
                    Text("Nhật ký", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.W500)
                    Text("${activeLogs.size} dòng · ${activeTitle()}", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    TabRow(
                        selectedTabIndex = subTab,
                        containerColor = Color.White.copy(alpha = 0.18f),
                        contentColor = Color.White
                    ) {
                        listOf("Slim", "Verbose", "Lỗi").forEachIndexed { i, label ->
                            Tab(
                                selected = subTab == i,
                                onClick = { subTab = i },
                                text = { Text(label, fontSize = 12.sp, color = Color.White) }
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    // Nhiều nút thao tác → cho phép scroll ngang để không bị chèn ép / khó bấm.
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                    ) {
                        Button(onClick = {
                            when (subTab) {
                                0 -> logger.clearSlimLogs()
                                1 -> logger.clearVerboseLogs()
                                else -> logger.clearErrorLogs()
                            }
                            Toast.makeText(this@MainActivity, "🗑 Đã xóa log ${activeTitle()}", Toast.LENGTH_SHORT).show()
                        },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.25f)),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(8.dp)) {
                            Text("🗑 Xóa", color = Color.White, fontSize = 12.sp)
                        }
                        Button(onClick = {
                            when (subTab) {
                                0 -> exportLogText(logger.getSlimLogText(), suffix = "slim")
                                1 -> exportLogText(logger.getVerboseLogText(), suffix = "verbose")
                                else -> exportLogText(logger.getErrorLogText(), suffix = "error")
                            }
                        },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.25f)),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(8.dp)) {
                            Text("💾 Xuất", color = Color.White, fontSize = 12.sp)
                        }
                        Button(onClick = { dumpZaloUI() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.25f)),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(8.dp)) {
                            Text("🔍 Dump UI Zalo", color = Color.White, fontSize = 12.sp)
                        }
                        Button(onClick = { exportUiDumpJson() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.25f)),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(8.dp)) {
                            Text("💾 Xuất UI Dump", color = Color.White, fontSize = 12.sp)
                        }
                        val unlikedDumpExists = File(filesDir, "ui_dump_unliked.json").exists()
                        Button(
                            onClick = { exportInternalDumpJson("ui_dump_unliked.json", "ZaloPilot_ui_dump_unliked") },
                            enabled = unlikedDumpExists,
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.25f)),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) { Text("💾 Xuất dump chưa like", color = Color.White, fontSize = 12.sp) }

                        val likedDumpExists = File(filesDir, "ui_dump_liked.json").exists()
                        Button(
                            onClick = { exportInternalDumpJson("ui_dump_liked.json", "ZaloPilot_ui_dump_liked") },
                            enabled = likedDumpExists,
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.25f)),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) { Text("💾 Xuất dump đã like", color = Color.White, fontSize = 12.sp) }
                        Button(onClick = {
                            val txt = when (subTab) {
                                0 -> logger.getSlimLogText()
                                1 -> logger.getVerboseLogText()
                                else -> logger.getErrorLogText()
                            }
                            copyLogsText(txt, activeTitle())
                        },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.25f)),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(8.dp)) {
                            Text("📋 Copy log", color = Color.White, fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Dump cây UI khi lỗi", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
                        Switch(
                            checked = verboseUiTreeLog,
                            onCheckedChange = onVerboseUiTreeChange
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Log chi tiết nút Like", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
                        Switch(
                            checked = verboseLikeContextLog,
                            onCheckedChange = onVerboseLikeContextChange
                        )
                    }
                }
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (activeLogs.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("📋", fontSize = 40.sp)
                            Spacer(Modifier.height(8.dp))
                            Text("Chưa có log ${activeTitle()}", color = Color.Gray, fontSize = 14.sp)
                            Text("Mở Zalo và chạy bot để ghi log", color = Color.Gray, fontSize = 12.sp)
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
                                        else -> Color.White
                                    }
                                ),
                                shape = RoundedCornerShape(10.dp),
                                border = if (failure) androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE57373)) else null
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
                                                Text("THẤT BẠI", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFFC62828))
                                            }
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        Text(log.target, fontSize = 13.sp, color = Color(0xFF222222))
                                        Text(log.result, fontSize = 12.sp, color = Color(0xFF555555))
                                        Spacer(Modifier.height(4.dp))
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text(log.timestamp, fontSize = 11.sp, color = Color.Gray)
                                            val meta = buildList {
                                                if (!log.foregroundPkg.isNullOrBlank()) add("pkg ${log.foregroundPkg}")
                                                log.durationMs?.let { add("${it}ms") }
                                            }.joinToString(" · ")
                                            if (meta.isNotEmpty()) {
                                                Text(meta, fontSize = 10.sp, color = Color(0xFF888888), maxLines = 2)
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
