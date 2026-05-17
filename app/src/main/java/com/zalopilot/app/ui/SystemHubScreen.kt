package com.zalopilot.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Bottom nav «Hệ thống» — 3 phần: Log · Script · UI. */
@Composable
fun SystemHubScreen(
    logContent: @Composable () -> Unit,
    scriptContent: @Composable () -> Unit,
    uiTreeContent: @Composable () -> Unit
) {
    var section by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize().background(ZpColors.BgPage)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            IosScreenTitle("Hệ thống", "Log · Script Visit · cây UI Zalo")
        }
        TabRow(
            selectedTabIndex = section,
            modifier = Modifier.padding(horizontal = 16.dp),
            containerColor = ZpColors.BgCard,
            contentColor = ZpColors.AccentBlue,
            divider = { HorizontalDivider(color = ZpColors.Divider, thickness = 0.5.dp) }
        ) {
            listOf("Log", "Script", "UI").forEachIndexed { index, label ->
                Tab(
                    selected = section == index,
                    onClick = { section = index },
                    text = {
                        Text(
                            label,
                            fontSize = 13.sp,
                            color = if (section == index) ZpColors.AccentBlue else ZpColors.TextSecondary
                        )
                    }
                )
            }
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when (section) {
                0 -> logContent()
                1 -> scriptContent()
                2 -> uiTreeContent()
            }
        }
    }
}
