package com.arflix.tv.ui.screens.recommendations

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.foundation.lazy.grid.TvGridCells
import androidx.tv.foundation.lazy.grid.TvLazyVerticalGrid
import androidx.tv.foundation.lazy.grid.itemsIndexed
import androidx.tv.material3.Text
import com.arflix.tv.data.model.MediaItem
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.data.repository.MediaRepository
import com.arflix.tv.ui.components.MediaCard
import com.arflix.tv.ui.focus.arvioDpadFocusGroup
import com.arflix.tv.ui.theme.ArflixTypography
import com.arflix.tv.ui.theme.appBackgroundDark
import com.arflix.tv.ui.theme.TextPrimary
import com.arflix.tv.ui.theme.TextSecondary
import com.arflix.tv.util.LocalDeviceType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RecommendationsUiState(
    val items: List<MediaItem> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null
)

@HiltViewModel
class RecommendationsViewModel @Inject constructor(
    private val mediaRepository: MediaRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(RecommendationsUiState())
    val uiState: StateFlow<RecommendationsUiState> = _uiState.asStateFlow()

    fun load(mediaType: MediaType, mediaId: Int) {
        if (!_uiState.value.isLoading && _uiState.value.items.isNotEmpty()) return
        viewModelScope.launch {
            _uiState.value = RecommendationsUiState(isLoading = true)
            val result = runCatching {
                mediaRepository.getSimilar(mediaType, mediaId)
            }.getOrDefault(emptyList())

            _uiState.value = if (result.isEmpty()) {
                RecommendationsUiState(
                    items = emptyList(),
                    isLoading = false,
                    error = "No recommendations found for this title."
                )
            } else {
                RecommendationsUiState(
                    items = result,
                    isLoading = false
                )
            }
        }
    }

    fun retry(mediaType: MediaType, mediaId: Int) {
        _uiState.value = RecommendationsUiState(isLoading = true)
        load(mediaType, mediaId)
    }
}

@Composable
fun RecommendationsScreen(
    mediaType: MediaType,
    mediaId: Int,
    mediaTitle: String,
    viewModel: RecommendationsViewModel = hiltViewModel(),
    onNavigateToDetails: (MediaType, Int) -> Unit,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(mediaType, mediaId) { viewModel.load(mediaType, mediaId) }
    BackHandler(onBack = onBack)

    val isMobile = LocalDeviceType.current.isTouchDevice()
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Responsive grid columns — same formula as CollectionDetailsScreen poster mode
    val gridColumns = if (isMobile) {
        if (isLandscape) 4 else 3
    } else {
        when {
            configuration.screenWidthDp >= 2200 -> 8
            configuration.screenWidthDp >= 1600 -> 7
            else -> 5
        }
    }

    val cardWidth = if (isMobile) 138.dp else when {
        configuration.screenWidthDp >= 2200 -> 196.dp
        configuration.screenWidthDp >= 1600 -> 184.dp
        else -> 172.dp
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(appBackgroundDark())
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    (event.key == Key.Back || event.key == Key.Escape)
                ) {
                    onBack()
                    true
                } else {
                    false
                }
            }
    ) {
        // Accent gradient backdrop
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    androidx.compose.ui.graphics.Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF2F9C95).copy(alpha = 0.25f),
                            Color.Transparent
                        )
                    )
                )
        )

        if (uiState.isLoading) {
            // Skeleton grid
            TvLazyVerticalGrid(
                columns = TvGridCells.Fixed(gridColumns),
                modifier = Modifier
                    .fillMaxSize()
                    .arvioDpadFocusGroup()
                    .padding(top = 72.dp),
                contentPadding = PaddingValues(
                    start = 42.dp,
                    end = 42.dp,
                    top = 20.dp,
                    bottom = 48.dp
                ),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                itemsIndexed((1..gridColumns * 3).toList()) { _, _ ->
                    Box(
                        modifier = Modifier
                            .height(cardWidth * 1.5f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = 0.05f))
                    )
                }
            }

            // Title overlay
            Text(
                text = "More Like $mediaTitle",
                style = ArflixTypography.sectionTitle.copy(
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                ),
                color = TextPrimary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 42.dp, top = 18.dp, end = 42.dp)
            )
        } else if (uiState.error != null) {
            // Error state
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.foundation.layout.Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = uiState.error ?: "Something went wrong",
                        style = ArflixTypography.body,
                        color = TextSecondary
                    )
                    androidx.tv.material3.Button(
                        onClick = { viewModel.retry(mediaType, mediaId) }
                    ) {
                        Text(androidx.compose.ui.res.stringResource(com.arflix.tv.R.string.retry))
                    }
                }
            }
        } else {
            // Recommendations grid
            TvLazyVerticalGrid(
                columns = TvGridCells.Fixed(gridColumns),
                modifier = Modifier
                    .fillMaxSize()
                    .arvioDpadFocusGroup()
                    .padding(top = 56.dp),
                contentPadding = PaddingValues(
                    start = 42.dp,
                    end = 42.dp,
                    top = 4.dp,
                    bottom = 48.dp
                ),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                item(
                    span = { androidx.tv.foundation.lazy.grid.TvGridItemSpan(maxLineSpan) },
                    contentType = "header"
                ) {
                    Text(
                        text = "More Like $mediaTitle",
                        style = ArflixTypography.sectionTitle.copy(
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = TextPrimary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    )
                }

                itemsIndexed(
                    items = uiState.items,
                    key = { _, item -> "${item.mediaType}-${item.id}" },
                    contentType = { _, _ -> "recommendation_card" }
                ) { _, item ->
                    MediaCard(
                        item = item,
                        width = cardWidth,
                        isLandscape = false,
                        showTitle = true,
                        titleMaxLines = 2,
                        onClick = { onNavigateToDetails(item.mediaType, item.id) }
                    )
                }
            }
        }
    }
}
