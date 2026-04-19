package com.arflix.tv.ui.screens.tv.live

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.arflix.tv.data.model.IptvNowNext

/**
 * Channel column row — spec §3.4, mockup layout:
 *
 *   ┌─ [number mono] ─ [logo 44] ─ [name / program / progress / time] ─ [HD/HI] ─┐
 *
 * Active channel: 3dp cyan left indicator, accent bg tint, CH number cyan.
 * Focused: full row sits on PanelRaised so the selection is obvious.
 */
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChannelRow(
    channel: EnrichedChannel,
    nowNext: IptvNowNext?,
    isActive: Boolean,
    isFavorite: Boolean,
    stripe: Boolean = false,
    onClick: () -> Unit,
    onFavoriteToggle: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> LiveColors.PanelRaised
        isActive -> LiveColors.FocusBg
        stripe -> LiveColors.RowStripe
        else -> Color.Transparent
    }
    val now = nowNext?.now
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(LiveDims.EpgRowHeight)
            .background(bg)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onFavoriteToggle,
            )
            .focusable()
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown && ev.key == Key.Menu) {
                    onFavoriteToggle(); true
                } else false
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ─ active left indicator ─────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(LiveDims.ActiveIndicator)
                .background(if (isActive) LiveColors.Accent else Color.Transparent),
        )

        // ─ channel number ────────────────────────────────────
        Box(
            modifier = Modifier
                .width(56.dp)
                .padding(start = 12.dp, end = 8.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = channel.number.toString(),
                style = LiveType.NumberMono.copy(
                    color = if (isActive) LiveColors.Accent else LiveColors.FgMute,
                    fontSize = 12.sp,
                ),
            )
        }

        // ─ logo ──────────────────────────────────────────────
        ChannelLogo(channel = channel, size = 44.dp)

        Spacer(Modifier.width(12.dp))

        // ─ name / program / progress / time ──────────────────
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = channel.name,
                    style = LiveType.CellTitle.copy(
                        color = if (isActive) LiveColors.Accent else LiveColors.Fg,
                        fontSize = 14.sp,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isFavorite) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = null,
                        tint = LiveColors.Accent,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
            Text(
                text = now?.title ?: "No info",
                style = LiveType.BodySynopsis.copy(
                    color = if (now != null) LiveColors.FgDim else LiveColors.FgMute,
                    fontSize = 12.sp,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Thin progress line + end-time
            val progress = progressOf(now)
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.width(140.dp).height(2.dp),
                    color = LiveColors.Accent,
                    trackColor = LiveColors.Divider,
                )
            }
            Text(
                text = now?.endUtcMillis?.let { formatClock(it) } ?: "",
                style = LiveType.TimeMono.copy(color = LiveColors.FgMute, fontSize = 10.sp),
            )
        }

        // ─ stacked badges (quality + lang) ───────────────────
        Column(
            modifier = Modifier.padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.End,
        ) {
            SmallPillBadge(channel.quality.label)
            SmallPillBadge(channel.lang)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SmallPillBadge(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(LiveColors.Panel)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text.uppercase(), style = LiveType.Badge.copy(color = LiveColors.FgDim))
    }
}
