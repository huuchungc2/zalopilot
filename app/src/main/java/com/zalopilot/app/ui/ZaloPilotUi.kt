package com.zalopilot.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.compose.material3.SliderDefaults

object ZpColors {
    val BgPage = Color(0xFFF2F2F7)
    val BgCard = Color.White
    val BgSecondary = Color(0xFFE5E5EA)
    val AccentBlue = Color(0xFF007AFF)
    val AccentPurple = Color(0xFFAF52DE)
    val TextPrimary = Color(0xFF000000)
    val TextSecondary = Color(0xFF8E8E93)
    val TextBlue = Color(0xFF007AFF)
    val ColorGreen = Color(0xFF34C759)
    val ColorRed = Color(0xFFFF3B30)
    val ColorOrange = Color(0xFFFF9500)
    val Divider = Color(0xFFE5E5EA)
}

val iosSwitchColors
    @Composable get() = SwitchDefaults.colors(
        checkedThumbColor = Color.White,
        checkedTrackColor = ZpColors.AccentBlue
    )

val iosSliderColors
    @Composable get() = SliderDefaults.colors(
        thumbColor = ZpColors.AccentBlue,
        activeTrackColor = ZpColors.AccentBlue
    )

@Composable
fun IosSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        modifier = modifier
            .fillMaxWidth()
            .background(ZpColors.BgPage)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = ZpColors.TextSecondary,
        letterSpacing = 0.04.sp
    )
}

/** Nhóm cài đặt nền trắng + divider (UI_FIX_ALL — không card bo góc). */
@Composable
fun ZaloSettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(ZpColors.BgCard)
    ) {
        content()
    }
}

@Composable
fun StepperRow(
    label: String,
    subLabel: String = "",
    value: Int,
    min: Int,
    max: Int,
    unit: String = "",
    onValueChange: (Int) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 15.sp, color = ZpColors.TextPrimary)
            if (subLabel.isNotEmpty()) {
                Text(subLabel, fontSize = 12.sp, color = ZpColors.TextSecondary)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(ZpColors.BgSecondary)
                    .clickable(enabled = value > min) {
                        if (value > min) onValueChange(value - 1)
                    },
                contentAlignment = Alignment.Center
            ) {
                Text("−", fontSize = 18.sp, color = ZpColors.AccentBlue, fontWeight = FontWeight.W600)
            }
            Text(
                "$value$unit",
                fontSize = 15.sp,
                fontWeight = FontWeight.W600,
                color = ZpColors.TextPrimary,
                modifier = Modifier.padding(horizontal = 14.dp)
            )
            Box(
                Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(ZpColors.BgSecondary)
                    .clickable(enabled = value < max) {
                        if (value < max) onValueChange(value + 1)
                    },
                contentAlignment = Alignment.Center
            ) {
                Text("+", fontSize = 18.sp, color = ZpColors.AccentBlue, fontWeight = FontWeight.W600)
            }
        }
    }
}

@Composable
fun ZaloSettingsDivider() {
    HorizontalDivider(
        color = ZpColors.Divider,
        thickness = 0.5.dp,
        modifier = Modifier.padding(start = 16.dp)
    )
}

@Composable
fun IosCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    val cardModifier = modifier.fillMaxWidth()
    val colors = CardDefaults.cardColors(containerColor = ZpColors.BgCard)
    val elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    val border = BorderStroke(0.5.dp, ZpColors.Divider)
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = cardModifier,
            shape = shape,
            colors = colors,
            elevation = elevation,
            border = border
        ) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    } else {
        Card(
            modifier = cardModifier,
            shape = shape,
            colors = colors,
            elevation = elevation,
            border = border
        ) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    }
}

@Composable
fun IosSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (enabled) ZpColors.BgSecondary else ZpColors.BgSecondary.copy(alpha = 0.5f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = if (enabled) ZpColors.TextPrimary else ZpColors.TextSecondary
        )
    }
}

@Composable
fun IosScreenTitle(title: String, subtitle: String? = null) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 12.dp, bottom = 4.dp)
    ) {
        Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = ZpColors.TextPrimary)
        if (subtitle != null) {
            Text(subtitle, fontSize = 15.sp, color = ZpColors.TextSecondary)
        }
    }
}
