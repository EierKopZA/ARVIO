package com.arflix.tv.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import com.arflix.tv.data.model.MediaItem
import com.arflix.tv.ui.skin.ArvioFocusableSurface
import com.arflix.tv.ui.skin.ArvioSkin
import com.arflix.tv.ui.skin.rememberArvioCardShape
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Spacer

/**
 * A hybrid card that expand from Poster to Landscape when focused.
 * Mimics high-end streaming apps (like Netflix or Prime Video updates).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AdaptiveMediaCard(
    item: MediaItem,
    height: Dp = 190.dp, // Height is fixed to keep row steady
    isFocusedOverride: Boolean = false,
    onFocused: () -> Unit = {},
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (item.isPlaceholder) {
        // Fallback to static placeholder for simplicity
        Box(modifier = modifier.height(height).width(height * 0.66f).background(ArvioSkin.colors.surface))
        return
    }

    val posterWidth = height * 0.68f // ~2:3 aspect
    val landscapeWidth = height * 1.6f // ~16:10 aspect for better push effect

    val width by animateDpAsState(
        targetValue = if (isFocusedOverride) landscapeWidth else posterWidth,
        animationSpec = tween(durationMillis = 350),
        label = "adaptiveWidth"
    )

    val shape = rememberArvioCardShape(ArvioSkin.radius.md)
    val context = LocalContext.current
    val density = LocalDensity.current

    // Pre-calculate image requests for both poster and backdrop
    val posterRequest = remember(item.image, posterWidth) {
        val wPx = with(density) { posterWidth.roundToPx() }
        val hPx = with(density) { height.roundToPx() }
        ImageRequest.Builder(context)
            .data(item.image)
            .size(wPx, hPx)
            .precision(Precision.INEXACT)
            .allowHardware(true)
            .build()
    }

    val backdropRequest = remember(item.backdrop ?: item.image, landscapeWidth) {
        val wPx = with(density) { landscapeWidth.roundToPx() }
        val hPx = with(density) { height.roundToPx() }
        ImageRequest.Builder(context)
            .data(item.backdrop ?: item.image)
            .size(wPx, hPx)
            .precision(Precision.INEXACT)
            .allowHardware(true)
            .build()
    }

    Column(
        modifier = modifier
            .width(width)
            .zIndex(if (isFocusedOverride) 10f else 1f)
    ) {
        ArvioFocusableSurface(
            modifier = Modifier
                .fillMaxWidth()
                .height(height),
            shape = shape,
            backgroundColor = ArvioSkin.colors.surface,
            outlineColor = if (isFocusedOverride) ArvioSkin.colors.focusOutline else Color.Transparent,
            outlineWidth = if (isFocusedOverride) 3.dp else 0.dp,
            focusedScale = 1.0f, // Width animation handles the "expansion" feel
            pressedScale = 0.98f,
            isFocusedOverride = isFocusedOverride,
            onClick = onClick,
            onLongClick = onLongClick,
            onFocusChanged = { if (it) onFocused() },
        ) { _ ->
            Box(modifier = Modifier.fillMaxSize()) {
                // Crossfade between poster and landscape art based on focus
                Crossfade(targetState = isFocusedOverride, label = "AdaptiveImage") { focused ->
                    AsyncImage(
                        model = if (focused) backdropRequest else posterRequest,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(ArvioSkin.colors.surface),
                    )
                }

                // Gradient Overlay
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))
                            )
                        )
                )

                // Title overlay (only visible in landscape/focused state)
                if (isFocusedOverride) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(12.dp)
                    ) {
                        Text(
                            text = item.title,
                            style = ArvioSkin.typography.cardTitle.copy(fontSize = 14.sp),
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (item.year.isNotBlank()) {
                            Text(
                                text = item.year,
                                style = ArvioSkin.typography.caption.copy(fontSize = 10.sp),
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }
        
        // External label (fallback if not focused or if preferred)
        if (!isFocusedOverride) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = item.title,
                style = ArvioSkin.typography.caption.copy(fontSize = 11.sp),
                color = ArvioSkin.colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}
