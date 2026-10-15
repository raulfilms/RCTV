package com.nuvio.tv.ui.screens.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.data.disneyplus.DisneyPlusCatalog
import com.nuvio.tv.data.disneyplus.DisneyPlusHub
import com.nuvio.tv.data.disneyplus.DisneyPlusRepository
import com.nuvio.tv.data.disneyplus.DisneyRowKind
import com.nuvio.tv.data.disneyplus.DisneyRowSpec
import com.nuvio.tv.data.disneyplus.withPremiereBadges
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.repository.TitleArtResolver
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject

/**
 * One of Home's TMDB rows (Trending Now, New Releases... Discover Something New), in order.
 * [row] is null while loading or when it came back empty. Card rows fill in [franchiseArt]
 * (a franchise's name and picture, by its row id) and [channelLogos] (by TMDB network id).
 */
data class HomeDiscoverRowState(
    val spec: DisneyRowSpec,
    val title: String,
    val row: CatalogRow? = null,
    val isLoading: Boolean = true,
    val franchiseArt: Map<String, Pair<String, String?>> = emptyMap(),
    val channelLogos: Map<Int, String> = emptyMap()
)

/**
 * Home's rows from TMDB's whole catalog, under the streaming services: see
 * [DisneyPlusHub.HOME] for their definitions. Each row's See All opens its full list (with Type,
 * Genre and Year filters) on the hub See All page.
 */
@HiltViewModel
class HomeDiscoverViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: DisneyPlusRepository
) : ViewModel() {

    private val hubSpec = DisneyPlusCatalog.hub(DisneyPlusHub.HOME)

    /** Title art for the rows' horizontal cards, found as they appear. */
    val titleArt: TitleArtResolver get() = repository.titleArtResolver

    private val _rows = MutableStateFlow(
        hubSpec.rows
            .filter { it.kind != DisneyRowKind.HIDDEN }
            .map { spec ->
                HomeDiscoverRowState(
                    spec = spec,
                    title = spec.title ?: context.getString(spec.titleRes),
                    isLoading = spec.kind == DisneyRowKind.CATALOG
                )
            }
    )
    val rows: StateFlow<List<HomeDiscoverRowState>> = _rows.asStateFlow()

    /** Rows loading at once (the first rows first). */
    private val limiter = Semaphore(4)

    init {
        load()
    }

    private fun load() {
        _rows.value.forEach { state ->
            val spec = state.spec
            when (spec.kind) {
                DisneyRowKind.CATALOG -> viewModelScope.launch {
                    val row = limiter.withPermit {
                        try {
                            repository.resolveRow(DisneyPlusHub.HOME, spec, state.title)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            null
                        }
                    }
                    val shown = if (spec.id == PREMIERES_ROW_ID) row?.let(::badged) else row
                    update(spec.id) { it.copy(row = shown, isLoading = false) }
                }

                DisneyRowKind.FRANCHISES -> viewModelScope.launch {
                    val names = hubSpec.rows.associate { it.id to (it.title ?: it.id) }
                    spec.franchiseRows.forEach { rowId ->
                        launch {
                            val query = names[rowId] ?: return@launch
                            val art = limiter.withPermit { repository.franchiseArt(query) } ?: (query to null)
                            update(spec.id) { it.copy(franchiseArt = it.franchiseArt + (rowId to art)) }
                        }
                    }
                }

                DisneyRowKind.CHANNELS -> viewModelScope.launch {
                    spec.channelTiles.forEach { tile ->
                        launch {
                            val logo = limiter.withPermit { repository.networkLogo(tile.networkId) } ?: return@launch
                            update(spec.id) { it.copy(channelLogos = it.channelLogos + (tile.networkId to logo)) }
                        }
                    }
                }

                else -> Unit
            }
        }
    }

    /**
     * Premieres: what came out these last 30 days first (newest first; "NEW" this past week, else
     * "PREMIERE"), then what's coming (soonest first, "COMING SOON").
     */
    private fun badged(row: CatalogRow): CatalogRow = row.withPremiereBadges(
        newLabel = context.getString(R.string.home_badge_new),
        premiereLabel = context.getString(R.string.home_badge_premiere),
        comingLabel = context.getString(R.string.home_badge_coming_soon),
        newDays = NEW_BADGE_DAYS
    )

    private fun update(rowId: String, change: (HomeDiscoverRowState) -> HomeDiscoverRowState) {
        _rows.update { rows -> rows.map { if (it.spec.id == rowId) change(it) else it } }
    }

    companion object {
        const val PREMIERES_ROW_ID = "home_premieres"
        /** Discover Something New leaves out what the person has already watched. */
        const val DISCOVER_NEW_ROW_ID = "home_discover_new"
        private const val NEW_BADGE_DAYS = 7L
    }
}
