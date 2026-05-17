package com.zalopilot.app.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zalopilot.app.accessibility.ZaloPilotAccessibilityService
import com.zalopilot.app.accessibility.engine.ZPScriptMeta
import com.zalopilot.app.accessibility.engine.ZPScriptStore
import com.zalopilot.app.util.AccessibilityHelper
import com.zalopilot.app.util.AppVersion
import com.zalopilot.app.util.LikeMode
import kotlinx.coroutines.launch

@Composable
fun ScriptScreen(
    scriptStore: ZPScriptStore
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var serverUrl by remember { mutableStateOf(scriptStore.getServerBaseUrl()) }
    var activeId by remember { mutableStateOf(scriptStore.getActiveScriptId()) }
    val localScripts = remember { mutableStateListOf<ZPScriptMeta>() }
    val remoteScripts = remember { mutableStateListOf<ZPScriptMeta>() }
    var jsonPreview by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("") }

    fun refreshLocal() {
        localScripts.clear()
        localScripts.addAll(scriptStore.listLocal())
        val bundled = scriptStore.load(ZPScriptStore.DEFAULT_SCRIPT_ID)
        if (bundled != null && localScripts.none { it.id == ZPScriptStore.DEFAULT_SCRIPT_ID }) {
            localScripts.add(
                0,
                ZPScriptMeta(
                    id = ZPScriptStore.DEFAULT_SCRIPT_ID,
                    version = bundled.optInt("version", 1),
                    file = "${ZPScriptStore.DEFAULT_SCRIPT_ID}.json",
                    desc = bundled.optString("desc", "Bundled assets")
                )
            )
        }
    }

    LaunchedEffect(Unit) { refreshLocal() }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(ZpColors.BgPage),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { IosScreenTitle("Script", "Tải JSON từ server — chỉnh flow không cần build APK") }
        item {
            Text(AppVersion.fullLabel(), fontSize = 11.sp, color = ZpColors.TextSecondary, modifier = Modifier.padding(start = 4.dp))
        }
        item {
            IosCard {
                Text("Server URL", fontSize = 12.sp, color = ZpColors.TextSecondary)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            scriptStore.setServerBaseUrl(serverUrl)
                            Toast.makeText(context, "Đã lưu URL", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ZpColors.AccentBlue)
                    ) { Text("Lưu") }
                    TextButton(onClick = {
                        scriptStore.resetServerBaseUrl()
                        serverUrl = scriptStore.getServerBaseUrl()
                        Toast.makeText(context, "Đã khôi phục URL mặc định", Toast.LENGTH_SHORT).show()
                    }) { Text("Mặc định", color = ZpColors.AccentBlue) }
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        scope.launch {
                            status = "Đang kiểm tra..."
                            val index = scriptStore.fetchIndex()
                            remoteScripts.clear()
                            if (index != null) {
                                remoteScripts.addAll(scriptStore.parseIndexScripts(index))
                                status = "Có ${remoteScripts.size} script trên server"
                            } else {
                                status = "Không tải được index.json"
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = ZpColors.AccentBlue),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("Kiểm tra cập nhật") }
                if (status.isNotBlank()) {
                    Text(status, fontSize = 12.sp, color = ZpColors.TextSecondary)
                }
            }
        }
        items(remoteScripts, key = { "remote-${it.id}" }) { meta ->
            val localVer = scriptStore.getLocalVersion(meta.id)
            val hasNew = meta.version > localVer
            IosCard {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(meta.id, fontWeight = FontWeight.Medium, color = ZpColors.TextPrimary)
                        Text("v${meta.version} · ${meta.desc}", fontSize = 12.sp, color = ZpColors.TextSecondary)
                        if (hasNew) Text("Mới", fontSize = 11.sp, color = ZpColors.ColorOrange)
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                val json = scriptStore.downloadScript(meta)
                                if (json != null) {
                                    refreshLocal()
                                    Toast.makeText(context, "Đã tải ${meta.id}", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Tải thất bại", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ZpColors.AccentBlue),
                        shape = RoundedCornerShape(10.dp)
                    ) { Text(if (hasNew) "Tải" else "Tải lại") }
                }
            }
        }
        item {
            IosSectionLabel("TRÊN MÁY / BUNDLED")
        }
        items(localScripts.ifEmpty {
            listOf(
                ZPScriptMeta(
                    ZPScriptStore.DEFAULT_SCRIPT_ID,
                    scriptStore.load(ZPScriptStore.DEFAULT_SCRIPT_ID)?.optInt("version", 1) ?: 1,
                    "${ZPScriptStore.DEFAULT_SCRIPT_ID}.json",
                    "assets (placeholder)"
                )
            )
        }, key = { "local-${it.id}" }) { meta ->
            val selected = meta.id == activeId
            IosCard {
                Text(
                    "${meta.id} v${meta.version}${if (selected) " · đang dùng" else ""}",
                    fontWeight = FontWeight.Medium,
                    color = ZpColors.TextPrimary
                )
                Text(meta.desc, fontSize = 12.sp, color = ZpColors.TextSecondary)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        scriptStore.setActiveScriptId(meta.id)
                        activeId = meta.id
                    }) {
                        Text(
                            if (selected) "Đang dùng" else "Chọn",
                            color = ZpColors.AccentBlue
                        )
                    }
                    TextButton(onClick = {
                        jsonPreview = scriptStore.load(meta.id)?.toString(2)
                    }) { Text("Xem JSON", color = ZpColors.AccentBlue) }
                }
            }
        }
        item {
            IosCard {
                Text("Script đang dùng: $activeId", fontWeight = FontWeight.Medium, color = ZpColors.TextPrimary)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            AccessibilityHelper.requestStartAutoLike(context, LikeMode.VISIT)
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = ZpColors.ColorGreen),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("Chạy") }
                    Button(
                        onClick = {
                            if (ZaloPilotAccessibilityService.instance != null) {
                                ZaloPilotAccessibilityService.instance?.startVisitScriptTestRound()
                            } else if (AccessibilityHelper.requestStartAutoLike(context)) {
                                Toast.makeText(
                                    context,
                                    "Chờ kết nối Trợ năng — dùng 1 vòng sau khi thấy toast ZaloPilot đã kết nối",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = ZpColors.AccentBlue),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("1 vòng") }
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        scriptStore.clearCache()
                        refreshLocal()
                        activeId = scriptStore.getActiveScriptId()
                        Toast.makeText(context, "Đã xóa cache script local", Toast.LENGTH_LONG).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = ZpColors.ColorRed),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("Xóa cache") }
            }
        }
    }

    if (jsonPreview != null) {
        AlertDialog(
            onDismissRequest = { jsonPreview = null },
            title = { Text("JSON") },
            text = {
                Text(
                    jsonPreview!!.take(8000) + if (jsonPreview!!.length > 8000) "\n…(truncated)" else "",
                    fontSize = 11.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { jsonPreview = null }) { Text("Đóng", color = ZpColors.AccentBlue) }
            }
        )
    }
}
