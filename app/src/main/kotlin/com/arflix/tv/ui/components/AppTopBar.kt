package com.arflix.tv.ui.components

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.arflix.tv.data.model.Profile
import com.arflix.tv.ui.skin.ArvioSkin
import com.arflix.tv.ui.theme.AnimationConstants
import com.arflix.tv.ui.theme.ArflixTypography
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

val AppTopBarHeight = 82.dp
val AppTopBarTopPadding = 0.dp
val AppTopBarContentTopInset = 98.dp
/** On mobile/tablet where the topbar is hidden, use a small status-bar-like inset instead. */
val MobileContentTopInset = 16.dp
val AppTopBarHorizontalPadding = 28.dp

fun topBarMaxIndex(hasProfile: Boolean): Int = if (hasProfile) SidebarItem.entries.size else SidebarItem.entries.size - 1

fun topBarSelectedIndex(selectedItem: SidebarItem, hasProfile: Boolean): Int {
    val base = SidebarItem.entries.indexOf(selectedItem)
    return if (hasProfile) base + 1 else base
}

fun topBarFocusedItem(focusedIndex: Int, hasProfile: Boolean): SidebarItem? {
    if (hasProfile && focusedIndex == 0) return null
    val itemIndex = if (hasProfile) focusedIndex - 1 else focusedIndex
    return SidebarItem.entries.getOrNull(itemIndex)
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AppTopBar(
    selectedItem: SidebarItem,
    isFocused: Boolean,
    focusedIndex: Int,
    profile: Profile? = null,
    modifier: Modifier = Modifier
) {
    val hasProfile = profile != null
    val currentTime = rememberTopBarTime()
    val selectedIndex = remember(selectedItem, hasProfile) { topBarSelectedIndex(selectedItem, hasProfile) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(AppTopBarContentTopInset)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.72f),
                        Color.Black.copy(alpha = 0.36f),
                        Color.Transparent
                    )
                )
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(AppTopBarHeight)
                .padding(start = AppTopBarHorizontalPadding, end = AppTopBarHorizontalPadding, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (profile != null) {
                    TopBarProfileChip(
                        profile = profile,
                        isFocused = isFocused && focusedIndex == 0,
                        isSelected = false
                    )
                }

                SidebarItem.entries.forEachIndexed { index, item ->
                    val itemFocusIndex = if (hasProfile) index + 1 else index
                    TopBarNavChip(
                        item = item,
                        isFocused = isFocused && focusedIndex == itemFocusIndex,
                        isSelected = selectedIndex == itemFocusIndex
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = currentTime,
                style = ArflixTypography.clock,
                color = Color.White.copy(alpha = 0.88f)
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TopBarNavChip(
    item: SidebarItem,
    isFocused: Boolean,
    isSelected: Boolean
) {
    val containerColor by animateColorAsState(
        targetValue = when {
            isFocused -> Color.White.copy(alpha = 0.2f)
            isSelected -> Color.White.copy(alpha = 0.1f)
            else -> Color.Transparent
        },
        animationSpec = tween(AnimationConstants.DURATION_FAST),
        label = "topbar_chip_bg"
    )
    val iconColor by animateColorAsState(
        targetValue = when {
            isFocused -> Color.White
            isSelected -> Color.White.copy(alpha = 0.92f)
            else -> Color.White.copy(alpha = 0.62f)
        },
        animationSpec = tween(AnimationConstants.DURATION_FAST),
        label = "topbar_icon_color"
    )
    val textColor by animateColorAsState(
        targetValue = when {
            isFocused -> Color.White
            isSelected -> Color.White.copy(alpha = 0.92f)
            else -> Color.White.copy(alpha = 0.68f)
        },
        animationSpec = tween(AnimationConstants.DURATION_FAST),
        label = "topbar_text_color"
    )
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.04f else 1f,
        animationSpec = tween(AnimationConstants.DURATION_FAST),
        label = "topbar_scale"
    )

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(containerColor)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = item.label,
            tint = iconColor,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = item.label,
            fontSize = 14.sp,
            fontWeight = if (isFocused || isSelected) FontWeight.SemiBold else FontWeight.Medium,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TopBarProfileChip(
    profile: Profile,
    isFocused: Boolean,
    isSelected: Boolean
) {
    val containerColor by animateColorAsState(
        targetValue = when {
            isFocused -> Color.White.copy(alpha = 0.2f)
            isSelected -> Color.White.copy(alpha = 0.1f)
            else -> Color.Transparent
        },
        animationSpec = tween(AnimationConstants.DURATION_FAST),
        label = "topbar_profile_bg"
    )
    val textColor by animateColorAsState(
        targetValue = if (isFocused) Color.White else Color.White.copy(alpha = 0.86f),
        animationSpec = tween(AnimationConstants.DURATION_FAST),
        label = "topbar_profile_text"
    )
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.04f else 1f,
        animationSpec = tween(AnimationConstants.DURATION_FAST),
        label = "topbar_profile_scale"
    )

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(containerColor)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(
                    if (profile.avatarId > 0) {
                        val (c1, c2) = AvatarRegistry.gradientColors(profile.avatarId)
                        Brush.verticalGradient(listOf(c1, c2))
                    } else {
                        Brush.linearGradient(listOf(Color(profile.avatarColor), Color(profile.avatarColor)))
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            if (profile.avatarId > 0) {
                AvatarIcon(
                    avatarId = profile.avatarId,
                    modifier = Modifier
                        .size(34.dp)
                        .padding(4.dp)
                )
            } else {
                Text(
                    text = profile.name.firstOrNull()?.uppercase() ?: "?",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }

        Text(
            text = profile.name,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        if (isFocused) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(20.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(ArvioSkin.colors.focusOutline)
            )
        }
    }
}

@Composable
private fun rememberTopBarTime(): String {
    val context = LocalContext.current
    val is24Hour = DateFormat.is24HourFormat(context)
    
    var currentTime by remember { mutableStateOf(getCurrentTimeForFormat(is24Hour)) }
    
    LaunchedEffect(Unit) {
        while (true) {
            currentTime = getCurrentTimeForFormat(is24Hour)
            val now = System.currentTimeMillis()
            val delayToNextMinute = 60_000L - (now % 60_000L)
            delay(delayToNextMinute.coerceIn(1_000L, 60_000L))
        }
    }
    return currentTime
}

private fun getCurrentTimeForFormat(is24Hour: Boolean): String {
    val sdf = SimpleDateFormat(if (is24Hour) "HH:mm" else "hh:mm a", Locale.getDefault())
    return sdf.format(Date())
}
