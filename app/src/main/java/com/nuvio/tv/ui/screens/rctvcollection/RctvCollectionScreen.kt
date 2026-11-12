package com.nuvio.tv.ui.screens.rctvcollection

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.AppleTvColors
import com.nuvio.tv.ui.components.ErrorState
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.TitlesSeeAllPage

/** RCTV Control: a website collection's titles, on the same page as every See All. */
@Composable
fun RctvCollectionScreen(
    onItemClick: (MetaPreview) -> Unit,
    onBack: () -> Unit,
    viewModel: RctvCollectionViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppleTvColors.Background),
        contentAlignment = Alignment.Center
    ) {
        when {
            state.isLoading -> LoadingIndicator()
            state.unavailable || state.items.isEmpty() -> ErrorState(
                message = stringResource(R.string.rctv_collection_unavailable),
                onRetry = onBack,
                actionLabel = stringResource(R.string.rctv_collection_back)
            )
            else -> TitlesSeeAllPage(
                title = state.name,
                stateKey = "rctv_collection_${state.name}",
                items = state.items,
                isLoadingMore = state.isLoadingMore,
                onItemClick = onItemClick,
                onItemLongPress = {},
                onLoadMore = viewModel::loadMore,
                vertical = true
            )
        }
    }
}
