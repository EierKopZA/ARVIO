package com.arflix.tv.ui.screens.tv.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.arflix.tv.data.model.IptvProgram

/**
 * A single EPG program cell placed inside a row with an absolute offset.
 * Width is determined by duration × px/min (handled by caller).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ProgramCell(
    program: IptvProgram,
    width: androidx.compose.ui.unit.Dp,
    isNow: Boolean,
    isPast: Boolean,
    isFocusTarget: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val baseBg = when {
        isNow -> LiveColors.FocusBg
        else -> LiveColors.Panel
    }
    val bg = if (focused) LiveColors.PanelRaised else baseBg
    val border = when {
        focused -> LiveColors.Accent
        isNow -> LiveColors.Accent.copy(alpha = 0.35f)
        else -> Color.Transparent
    }
    Box(
        modifier = modifier
            .height(LiveDims.EpgRowHeight)
            .width(width)
            .padding(horizontal = 3.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(LiveDims.CellRadius))
            .background(bg)
            .border(
                width = if (focused) LiveDims.FocusBorder else 1.dp,
                color = border,
                shape = RoundedCornerShape(LiveDims.CellRadius),
            )
            .alpha(if (isPast && !focused) 0.55f else 1f)
            .focusable()
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown &&
                    (ev.key == Key.DirectionCenter || ev.key == Key.Enter)) {
                    onClick(); true
                } else false
            }
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        if (isNow) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                LiveColors.Accent.copy(alpha = 0.22f),
                                Color.Transparent,
                            )
                        )
                    )
            )
        }
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val nowMs = System.currentTimeMillis()
                // A program starting within the last 24h that has never aired
                // before is treated as NEW — cyan pill, matches mockup.
                val isNewTag = (nowMs - program.startUtcMillis) in 0..24L * 60 * 60 * 1000L &&
                    !program.isLive(nowMs)
                if (program.isLive(nowMs)) {
                    Badge("LIVE", Color.White, LiveColors.LiveRed)
                    Spacer(Modifier.size(6.dp))
                } else if (isNewTag) {
                    Badge("NEW", LiveColors.Bg, LiveColors.Accent)
                    Spacer(Modifier.size(6.dp))
                }
                Text(
                    text = program.title,
                    style = LiveType.CellTitle.copy(color = LiveColors.Fg),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            if (!program.description.isNullOrBlank()) {
                Text(
                    text = program.description!!,
                    style = LiveType.BodySynopsis.copy(color = LiveColors.FgDim, fontSize = 11.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = formatClock(program.startUtcMillis),
                    style = LiveType.TimeMono.copy(color = LiveColors.FgMute, fontSize = 10.sp),
                )
                val mins = ((program.endUtcMillis - program.startUtcMillis) / 60_000L)
                    .coerceAtLeast(0L)
                if (mins > 0) {
                    Text(
                        text = "·  ${mins}min",
                        style = LiveType.TimeMono.copy(color = LiveColors.FgMute, fontSize = 10.sp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun Badge(label: String, fg: Color, bg: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(bg)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(label, style = LiveType.Badge.copy(color = fg, fontSize = 9.sp))
    }
}
