package com.zalopilot.app.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zalopilot.app.accessibility.ZaloPilotAccessibilityService
import com.zalopilot.app.floating.FloatingMenuService
import com.zalopilot.app.util.*
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var progressManager: LikeProgressManager
    @Inject lateinit var settingsManager: LikeSettingsManager
    @Inject lateinit var logger: Logger

    private val zaloBlue = Color(0xFF0068FF)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        progressManager.resetDailyIfNeeded()

        setContent {
            MaterialTheme {
                ZaloPilotApp()
            }
        }
    }

    @Composable
    fun ZaloPilotApp() {
        var selectedTab by remember { mutableIntStateOf(0) }
        var isRunning by remember { mutableStateOf(false) }
        var progress by remember { mutableStateOf(progressManager.load()) }
        var settings by remember { mutableStateOf(settingsManager.load()) }

        DisposableEffect(Unit) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    progress = progressManager.load()
                    isRunning = ZaloPilotAccessibilityService.instance != null
                }
            }
            val filter = IntentFilter().apply {
                addAction("com.zalopilot.STATUS_UPDATE")
                addAction("com.zalopilot.PROGRESS_UPDATE")
            }
            registerReceiver(receiver, filter)
            onDispose { unregisterReceiver(receiver) }
        }

        Scaffold(
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    listOf("Trang chủ", "Cài đặt", "Nhật ký").forEachIndexed { i, label ->
                        NavigationBarItem(
                            selected = selectedTab == i,
                            onClick = { selectedTab = i },
                            icon = {
                                Text(listOf("🏠", "⚙️", "📋")[i], fontSize = 18.sp)
                            },
                            label = { Text(label, fontSize = 11.sp) }
                        )
                    }
                }
            }
        ) { padding ->
            Box(Modifier.padding(padding)) {
                when (selectedTab) {
                    0 -> HomeScreen(isRunning, progress, settings)
                    1 -> SettingsScreen(settings) {
                        settings = it
                        settingsManager.save(it)
                    }
                    2 -> LogScreen()
                }
            }
        }
    }

    @Composable
    fun HomeScreen(isRunning: Boolean, progress: LikeProgress, settings: LikeSettings) {
        val isAccessibilityOn = isAccessibilityServiceEnabled()

        LazyColumn(
            modifier = Modifier.fillMaxSize().background(Color(0xFFF5F5F5)),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                // Header
                Box(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(zaloBlue)
                        .padding(20.dp)
                ) {
                    Column {
                        Text("ZaloPilot", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.W500)
                        Text("Chào Sale!", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            shape = RoundedCornerShape(99.dp),
                            color = if (isRunning) Color(0xFF27AE60) else Color.White.copy(alpha = 0.2f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(Modifier.size(7.dp).clip(CircleShape).background(Color.White))
                                Text(
                                    if (isRunning) "Đang chạy" else "Chờ",
                                    color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.W500
                                )
                            }
                        }
                    }
                }
            }

            if (!isAccessibilityOn) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3CD))
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Cần bật Accessibility Service", fontWeight = FontWeight.W500, color = Color(0xFF856404))
                            Text("Vào Cài đặt → Hỗ trợ tiếp cận → ZaloPilot → Bật lên", fontSize = 13.sp, color = Color(0xFF856404))
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = zaloBlue)
                            ) {
                                Text("Mở Cài đặt")
                            }
                        }
                    }
                }
            }

            item {
                // Tiến độ hôm nay
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("TIẾN ĐỘ HÔM NAY", fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.W500)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            StatCard(
                                value = "${progress.todayLikeCount}",
                                label = "Đã like",
                                color = zaloBlue,
                                modifier = Modifier.weight(1f)
                            )
                            StatCard(
                                value = "${settings.dailyLimit}",
                                label = "Giới hạn",
                                color = Color(0xFF555555),
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { (progress.todayLikeCount.toFloat() / settings.dailyLimit).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(99.dp)),
                            color = zaloBlue,
                            trackColor = Color(0xFFE0E0E0)
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${progress.todayLikeCount} / ${settings.dailyLimit}", fontSize = 12.sp, color = Color.Gray)
                            Text("${((progress.todayLikeCount.toFloat() / settings.dailyLimit) * 100).toInt()}%", fontSize = 12.sp, color = Color.Gray)
                        }
                    }
                }
            }

            item {
                // Tiến độ tổng
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("TIẾN ĐỘ TỔNG", fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.W500)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            StatCard("${progress.lastLikedIndex}", "Đã like", zaloBlue, Modifier.weight(1f))
                            StatCard("${progress.totalFriends}", "Tổng bạn", Color(0xFF555555), Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(10.dp))
                        val totalProgress = if (progress.totalFriends > 0)
                            progress.lastLikedIndex.toFloat() / progress.totalFriends else 0f
                        LinearProgressIndicator(
                            progress = { totalProgress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(99.dp)),
                            color = zaloBlue,
                            trackColor = Color(0xFFE0E0E0)
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Vòng ${progress.totalRounds + 1} · còn ~${progressManager.getRemainingDays()} ngày", fontSize = 12.sp, color = Color.Gray)
                            Text("${(totalProgress * 100).toInt()}%", fontSize = 12.sp, color = Color.Gray)
                        }
                    }
                }
            }

            item {
                // Nút bật/tắt floating
                Button(
                    onClick = {
                        if (Settings.canDrawOverlays(this@MainActivity)) {
                            startService(Intent(this@MainActivity, FloatingMenuService::class.java))
                        } else {
                            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = zaloBlue),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Bật nút nổi trên Zalo", fontSize = 15.sp, fontWeight = FontWeight.W500)
                }
            }
        }
    }

    @Composable
    fun StatCard(value: String, label: String, color: Color, modifier: Modifier = Modifier) {
        Box(
            modifier = modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFFF5F5F5)).padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(value, fontSize = 24.sp, fontWeight = FontWeight.W500, color = color)
                Text(label, fontSize = 12.sp, color = Color.Gray)
            }
        }
    }

    @Composable
    fun SettingsScreen(settings: LikeSettings, onSave: (LikeSettings) -> Unit) {
        var dailyLimit by remember { mutableIntStateOf(settings.dailyLimit) }
        var delayMin by remember { mutableStateOf(settings.delayMinMs.toString()) }
        var delayMax by remember { mutableStateOf(settings.delayMaxMs.toString()) }
        var sessionLimit by remember { mutableIntStateOf(settings.sessionLimit) }
        var restMin by remember { mutableIntStateOf(settings.restMinMinutes) }
        var restMax by remember { mutableIntStateOf(settings.restMaxMinutes) }
        var quietStart by remember { mutableIntStateOf(settings.quietHourStart) }
        var quietEnd by remember { mutableIntStateOf(settings.quietHourEnd) }
        var saveMsg by remember { mutableStateOf("") }

        LazyColumn(
            modifier = Modifier.fillMaxSize().background(Color(0xFFF5F5F5)),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(zaloBlue).padding(20.dp)
                ) {
                    Column {
                        Text("Cài đặt", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.W500)
                        Text("Tùy chỉnh theo ý muốn", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
                    }
                }
            }

            item {
                SettingCard(title = "GIỚI HẠN LIKE") {
                    Text("Tối đa / ngày: $dailyLimit bài", fontSize = 13.sp)
                    Slider(value = dailyLimit.toFloat(), onValueChange = { dailyLimit = it.toInt() },
                        valueRange = 20f..200f, steps = 17, colors = SliderDefaults.colors(thumbColor = zaloBlue, activeTrackColor = zaloBlue))
                    Text("Nghỉ sau: $sessionLimit like liên tiếp", fontSize = 13.sp)
                    Slider(value = sessionLimit.toFloat(), onValueChange = { sessionLimit = it.toInt() },
                        valueRange = 10f..50f, steps = 7, colors = SliderDefaults.colors(thumbColor = zaloBlue, activeTrackColor = zaloBlue))
                }
            }

            item {
                SettingCard(title = "TỐC ĐỘ LIKE") {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("Delay tối thiểu (giây)", fontSize = 12.sp, color = Color.Gray)
                            OutlinedTextField(value = delayMin, onValueChange = { delayMin = it },
                                modifier = Modifier.fillMaxWidth(), singleLine = true)
                        }
                        Column(Modifier.weight(1f)) {
                            Text("Delay tối đa (giây)", fontSize = 12.sp, color = Color.Gray)
                            OutlinedTextField(value = delayMax, onValueChange = { delayMax = it },
                                modifier = Modifier.fillMaxWidth(), singleLine = true)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("Nghỉ giữa session: $restMin - $restMax phút", fontSize = 13.sp)
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

            item {
                SettingCard(title = "GIỜ KHÔNG HOẠT ĐỘNG") {
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

            item {
                if (saveMsg.isNotEmpty()) {
                    Text(saveMsg, color = Color(0xFF27AE60), modifier = Modifier.fillMaxWidth(), fontSize = 13.sp)
                }
                Button(
                    onClick = {
                        val newSettings = settings.copy(
                            dailyLimit = dailyLimit,
                            delayMinMs = (delayMin.toLongOrNull() ?: 1) * 1000,
                            delayMaxMs = (delayMax.toLongOrNull() ?: 3) * 1000,
                            sessionLimit = sessionLimit,
                            restMinMinutes = restMin,
                            restMaxMinutes = restMax,
                            quietHourStart = quietStart,
                            quietHourEnd = quietEnd
                        )
                        onSave(newSettings)
                        saveMsg = "Đã lưu cài đặt!"
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = zaloBlue),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Lưu cài đặt", fontSize = 15.sp, fontWeight = FontWeight.W500)
                }
            }
        }
    }

    @Composable
    fun SettingCard(title: String, content: @Composable ColumnScope.() -> Unit) {
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(16.dp)) {
                Text(title, fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.W500)
                Spacer(Modifier.height(10.dp))
                content()
            }
        }
    }

    @Composable
    fun LogScreen() {
        val logs = remember { logger.readLogs() }
        val colorMap = mapOf(
            "SUCCESS" to Color(0xFF27AE60),
            "SKIP" to Color(0xFF888888),
            "REST" to Color(0xFFBA7517),
            "ERROR" to Color(0xFFE24B4A),
            "STARTED" to Color(0xFF0068FF),
            "STOPPED" to Color(0xFF888888)
        )

        Column(Modifier.fillMaxSize().background(Color(0xFFF5F5F5))) {
            Box(Modifier.fillMaxWidth().background(zaloBlue).padding(20.dp)) {
                Column {
                    Text("Nhật ký", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.W500)
                    Text("Lịch sử hoạt động", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
                }
            }
            if (logs.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Chưa có hoạt động nào", color = Color.Gray)
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text("HÔM NAY", fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.W500)
                                Spacer(Modifier.height(8.dp))
                                logs.forEach { log ->
                                    val dotColor = colorMap.entries.find { log.result.contains(it.key) }?.value ?: Color.Gray
                                    Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor).align(Alignment.CenterVertically))
                                        Column {
                                            Text(
                                                when (log.action) {
                                                    "LIKE" -> "Like thành công — ${log.target}"
                                                    "LIKE_SKIP" -> "Bỏ qua — ${log.target} (đã like rồi)"
                                                    "AUTO_LIKE" -> when (log.result) {
                                                        "STARTED" -> "Bắt đầu phiên"
                                                        "STOPPED" -> "Dừng phiên"
                                                        "SESSION_REST" -> log.target
                                                        "DAILY_LIMIT_REACHED" -> "Đã đủ giới hạn hôm nay"
                                                        else -> log.action
                                                    }
                                                    else -> "${log.action} — ${log.target}"
                                                },
                                                fontSize = 13.sp, color = Color(0xFF222222)
                                            )
                                            Text(log.timestamp, fontSize = 11.sp, color = Color.Gray)
                                        }
                                    }
                                    if (logs.last() != log) Divider(color = Color(0xFFEEEEEE), thickness = 0.5.dp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val service = "$packageName/com.zalopilot.app.accessibility.ZaloPilotAccessibilityService"
        val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return enabledServices.contains(service)
    }
}
