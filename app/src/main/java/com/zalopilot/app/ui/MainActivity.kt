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
import com.zalopilot.app.floating.FloatingMenuService
import com.zalopilot.app.util.DebugHighlightPrefs
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

    private val zaloBlue = Color(0xFF0068FF)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        progressManager.resetDailyIfNeeded()
        setContent {
            MaterialTheme {
                if (!isSetupComplete()) SetupWizard() else ZaloPilotApp()
            }
        }
    }

    // ─── Setup Wizard ────────────────────────────────────────────

    @Composable
    fun SetupWizard() {
        var step by remember { mutableIntStateOf(1) }
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
                1 -> StepCard("♿", "Bật Accessibility Service",
                    "Cho phép ZaloPilot điều khiển Zalo.\n\nSamsung: Cài đặt → tìm kiếm 'Hỗ trợ tiếp cận' → Ứng dụng đã cài → ZaloPilot → BẬT\n\nHoặc bấm nút bên dưới:",
                    "Mở Cài đặt Accessibility", acc) {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
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
                    Text("Mở Zalo → vào tab Nhật ký → bấm nút ZP → Bắt đầu like",
                        fontSize = 14.sp, color = Color.Gray, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = {
                        startService(Intent(this@MainActivity, FloatingMenuService::class.java))
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
        var isRunning by remember { mutableStateOf(ZaloPilotAccessibilityService.isActive) }
        var progress by remember { mutableStateOf(progressManager.load()) }
        var settings by remember { mutableStateOf(settingsManager.load()) }
        var logsSlim by remember { mutableStateOf(logger.readSlimLogs()) }
        var logsVerbose by remember { mutableStateOf(logger.readVerboseLogs()) }
        var logsError by remember { mutableStateOf(logger.readErrorLogs()) }
        var verboseUiTreeLog by remember { mutableStateOf(debugHighlightPrefs.isVerboseUiTreeLoggingEnabled()) }
        var verboseLikeContextLog by remember { mutableStateOf(debugHighlightPrefs.isVerboseLikeContextLoggingEnabled()) }

        DisposableEffect(Unit) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    progress = progressManager.load()
                    settings = settingsManager.load()
                    isRunning = ZaloPilotAccessibilityService.isActive
                    logsSlim = logger.readSlimLogs()
                    logsVerbose = logger.readVerboseLogs()
                    logsError = logger.readErrorLogs()
                    when (intent?.action) {
                        "com.zalopilot.STATUS_UPDATE" -> {
                            val running = intent.getBooleanExtra("running", false)
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
            registerReceiver(receiver, filter)
            onDispose { unregisterReceiver(receiver) }
        }

        Scaffold(bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                listOf("Trang chủ", "Cài đặt", "Nhật ký", "UI Tree").forEachIndexed { i, label ->
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
                        icon = { Text(listOf("🏠", "⚙️", "📋", "🌳")[i], fontSize = 18.sp) },
                        label = { Text(label, fontSize = 10.sp) }
                    )
                }
            }
        }) { padding ->
            Box(Modifier.padding(padding)) {
                when (selectedTab) {
                    0 -> HomeScreen(isRunning, progress, settings)
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
                            sendBroadcast(Intent(ZaloPilotAccessibilityService.ACTION_CLEAR_DEBUG_STATE))
                            logsSlim = logger.readSlimLogs()
                            logsVerbose = logger.readVerboseLogs()
                            logsError = logger.readErrorLogs()
                            Toast.makeText(this@MainActivity, "Đã xóa log & file tạm", Toast.LENGTH_SHORT).show()
                        }
                    )
                    3 -> UiTreeScreen()
                }
            }
        }
    }

    // ─── Home ────────────────────────────────────────────────────

    @Composable
    fun HomeScreen(isRunning: Boolean, progress: LikeProgress, settings: LikeSettings) {
        LazyColumn(modifier = Modifier.fillMaxSize().background(Color(0xFFF5F5F5)),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(zaloBlue).padding(20.dp)) {
                    Column {
                        Text("ZaloPilot", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.W500)
                        Text("Auto Like Zalo", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
                        Spacer(Modifier.height(8.dp))
                        Surface(shape = RoundedCornerShape(99.dp), color = if (isRunning) Color(0xFF27AE60) else Color.White.copy(alpha = 0.2f)) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(Modifier.size(7.dp).clip(CircleShape).background(Color.White))
                                Text(if (isRunning) "Đang chạy" else "Chờ", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.W500)
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
                            StatCard("${settings.dailyLimit}", "Giới hạn", Color(0xFF555555), Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(10.dp))
                        val pct = (progress.todayLikeCount.toFloat() / settings.dailyLimit).coerceIn(0f, 1f)
                        LinearProgressIndicator(progress = { pct },
                            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(99.dp)),
                            color = zaloBlue, trackColor = Color(0xFFE0E0E0))
                        Spacer(Modifier.height(4.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${progress.todayLikeCount} / ${settings.dailyLimit}", fontSize = 12.sp, color = Color.Gray)
                            Text("${(pct * 100).toInt()}%", fontSize = 12.sp, color = Color.Gray)
                        }
                    }
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("CHẾ ĐỘ CHẠY", fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.W500)
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text(if (settingsManager.isAutoStart()) "🤖 Tự động" else "👆 Thủ công", fontSize = 14.sp, fontWeight = FontWeight.W500)
                                Text(if (settingsManager.isAutoStart()) "Zalo mở là tự chạy" else "Bấm Start trên nút ZP", fontSize = 12.sp, color = Color.Gray)
                            }
                            Switch(checked = settingsManager.isAutoStart(),
                                onCheckedChange = { settingsManager.setAutoStart(it) },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = zaloBlue))
                        }
                    }
                }
            }
            item {
                Button(onClick = { startService(Intent(this@MainActivity, FloatingMenuService::class.java)) },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = zaloBlue),
                    shape = RoundedCornerShape(12.dp)) {
                    Text("Bật nút nổi ZP trên Zalo", fontSize = 15.sp, fontWeight = FontWeight.W500)
                }
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
        var interactMode by remember { mutableStateOf(
            try { InteractMode.valueOf(settings.interactModeStr) } catch (e: Exception) { InteractMode.MIX }
        ) }
        var feedMode by remember { mutableStateOf(settingsManager.getFeedMode()) }
        var delayMin by remember { mutableStateOf((settings.delayMinMs / 1000).toString()) }
        var delayMax by remember { mutableStateOf((settings.delayMaxMs / 1000).toString()) }
        var sessionLimit by remember { mutableIntStateOf(settings.sessionLimit) }
        var restMin by remember { mutableIntStateOf(settings.restMinMinutes) }
        var restMax by remember { mutableIntStateOf(settings.restMaxMinutes) }
        var quietStart by remember { mutableIntStateOf(settings.quietHourStart) }
        var quietEnd by remember { mutableIntStateOf(settings.quietHourEnd) }
        var ecoMode by remember { mutableStateOf(settings.ecoMode) }
        var nodeHighlightDebug by remember { mutableStateOf(debugHighlightPrefs.isNodeHighlightEnabled()) }
        var verboseLogEnabled by remember { mutableStateOf(logger.isVerboseLogEnabled()) }

        LazyColumn(modifier = Modifier.fillMaxSize().background(Color(0xFFF5F5F5)),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(zaloBlue).padding(20.dp)) {
                    Column {
                        Text("Cài đặt", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.W500)
                        Text("Tùy chỉnh tốc độ like", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
                    }
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("GIỚI HẠN LIKE", fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.W500)
                        Spacer(Modifier.height(8.dp))
                        Text("Tối đa / ngày: $dailyLimit bài", fontSize = 13.sp)
                        Slider(value = dailyLimit.toFloat(), onValueChange = { dailyLimit = it.toInt() },
                            valueRange = 20f..200f, colors = SliderDefaults.colors(thumbColor = zaloBlue, activeTrackColor = zaloBlue))
                        Text("Nghỉ sau: $sessionLimit like liên tiếp", fontSize = 13.sp)
                        Slider(value = sessionLimit.toFloat(), onValueChange = { sessionLimit = it.toInt() },
                            valueRange = 10f..50f, colors = SliderDefaults.colors(thumbColor = zaloBlue, activeTrackColor = zaloBlue))
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
                        Spacer(Modifier.height(8.dp))
                        Text("Nghỉ giữa session: $restMin–$restMax phút", fontSize = 13.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("Tối thiểu", fontSize = 12.sp, color = Color.Gray)
                                Slider(value = restMin.toFloat(), onValueChange = { restMin = it.toInt() },
                                    valueRange = 1f..30f, colors = SliderDefaults.colors(thumbColor = zaloBlue, activeTrackColor = zaloBlue))
                            }
                            Column(Modifier.weight(1f)) {
                                Text("Tối đa", fontSize = 12.sp, color = Color.Gray)
                                Slider(value = restMax.toFloat(), onValueChange = { restMax = it.toInt() },
                                    valueRange = 1f..60f, colors = SliderDefaults.colors(thumbColor = zaloBlue, activeTrackColor = zaloBlue))
                            }
                        }
                    }
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("GIỜ KHÔNG HOẠT ĐỘNG", fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.W500)
                        Spacer(Modifier.height(8.dp))
                        Text("Tắt từ ${quietStart}:00 đến ${quietEnd}:00", fontSize = 13.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("Từ giờ", fontSize = 12.sp, color = Color.Gray)
                                Slider(value = quietStart.toFloat(), onValueChange = { quietStart = it.toInt() },
                                    valueRange = 0f..23f, steps = 22, colors = SliderDefaults.colors(thumbColor = zaloBlue, activeTrackColor = zaloBlue))
                            }
                            Column(Modifier.weight(1f)) {
                                Text("Đến giờ", fontSize = 12.sp, color = Color.Gray)
                                Slider(value = quietEnd.toFloat(), onValueChange = { quietEnd = it.toInt() },
                                    valueRange = 0f..23f, steps = 22, colors = SliderDefaults.colors(thumbColor = zaloBlue, activeTrackColor = zaloBlue))
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
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("VERBOSE LOG", fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.W500)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Bật để ghi toàn bộ log vào log_verbose.json. Tắt để chỉ ghi Slim/Lỗi (nhẹ, ít tốn disk).",
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                        }
                        Switch(
                            checked = verboseLogEnabled,
                            onCheckedChange = { on ->
                                verboseLogEnabled = on
                                logger.setVerboseLogEnabled(on)
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = zaloBlue)
                        )
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
                    }
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("CHẾ ĐỘ TƯƠNG TÁC", fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.W500)
                        Spacer(Modifier.height(8.dp))
                        Text("Cách bot chạm màn hình khi like", fontSize = 12.sp, color = Color.Gray)
                        Spacer(Modifier.height(10.dp))
                        listOf(
                            InteractMode.TAP   to "👆 Tap — Click ảo vào ID node (nhanh, ít tự nhiên)",
                            InteractMode.SWIPE to "👋 Vuốt — Kéo màn hình + chạm tọa độ thật (chậm hơn, tự nhiên)",
                            InteractMode.MIX   to "🎲 Mix — Ngẫu nhiên giữa Tap và Vuốt (tự nhiên nhất)"
                        ).forEach { (mode, label) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (interactMode == mode) zaloBlue.copy(alpha = 0.1f) else Color.Transparent)
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = interactMode == mode,
                                    onClick = { interactMode = mode },
                                    colors = RadioButtonDefaults.colors(selectedColor = zaloBlue)
                                )
                                Text(label, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp))
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
                            Text("VIỀN DEBUG (NÚT THÍCH)", fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.W500)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Vẽ bounds các node bot vừa tìm được; viền dày = node sắp click. Không chặn chạm.",
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                        }
                        Switch(
                            checked = nodeHighlightDebug,
                            onCheckedChange = { on ->
                                nodeHighlightDebug = on
                                debugHighlightPrefs.setNodeHighlightEnabled(on)
                                ZaloPilotAccessibilityService.syncDebugHighlightFromPrefs()
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = zaloBlue)
                        )
                    }
                }
            }
            item {
                Button(onClick = {
                    settingsManager.setFeedMode(feedMode)
                    onSave(settings.copy(
                        dailyLimit = dailyLimit,
                        delayMinMs = (delayMin.toLongOrNull() ?: 1L) * 1000L,
                        delayMaxMs = (delayMax.toLongOrNull() ?: 3L) * 1000L,
                        sessionLimit = sessionLimit,
                        restMinMinutes = restMin,
                        restMaxMinutes = restMax,
                        quietHourStart = quietStart,
                        quietHourEnd = quietEnd,
                        interactModeStr = interactMode.name,
                        ecoMode = ecoMode
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
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
                LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
            Toast.makeText(this, "⚠️ Accessibility chưa bật", Toast.LENGTH_SHORT).show()
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

    private fun isSetupComplete() = isAccessibilityServiceEnabled() && Settings.canDrawOverlays(this)

    private fun isAccessibilityServiceEnabled(): Boolean {
        val service = "$packageName/com.zalopilot.app.accessibility.ZaloPilotAccessibilityService"
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return enabled.contains(service)
    }
}
