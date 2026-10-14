package com.nuvio.tv.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.skipStep
import com.nuvio.tv.domain.model.supportsExtra
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The genre (or year) filter an addon catalog offers itself, for Home's See All page.
 *
 * Many addon catalogs (Cinemeta, the TMDB addon...) list the genres they can filter by in their
 * manifest ("extra": genre with options); some list years there instead (a "By year" catalog).
 * Picking one asks the addon for that catalog filtered by it, page after page, so the See All page
 * finds every match the addon has instead of only the ones already loaded.
 */
data class CatalogGenreFilterState(
    /** The addon's genre choices for this catalog (all of them), or empty when it has none. */
    val genres: List<String> = emptyList(),
    /** The addon's year choices, when the catalog's genre option is a year ("2024"). */
    val years: List<String> = emptyList(),
    /** The picked genre (or year) the addon is asked for; null shows the catalog as it is. */
    val selected: String? = null,
    val items: List<MetaPreview> = emptyList(),
    val isLoading: Boolean = false,
    val hasMore: Boolean = false,
    /** The last page failed (offline, busy): scrolling tries it again. */
    val failed: Boolean = false
) {
    val isActive: Boolean get() = selected != null
}

@HiltViewModel
class CatalogGenreFilterViewModel @Inject constructor(
    private val addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository
) : ViewModel() {

    private val _state = MutableStateFlow(CatalogGenreFilterState())
    val state: StateFlow<CatalogGenreFilterState> = _state.asStateFlow()

    private var boundKey: String? = null
    private var addon: Addon? = null
    private var catalog: CatalogDescriptor? = null
    private var nextSkip = 0
    private var loadJob: Job? = null

    /** Finds the catalog in its addon's manifest and the genres (or years) it can be filtered by. */
    fun bind(addonId: String, type: String, catalogId: String) {
        val key = "$addonId|$type|$catalogId"
        if (key == boundKey) return
        boundKey = key
        loadJob?.cancel()
        _state.value = CatalogGenreFilterState()
        viewModelScope.launch {
            val addons: List<Addon> = try {
                addonRepository.getInstalledAddons().first()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            val found: Addon = addons.firstOrNull { it.id == addonId } ?: return@launch
            val descriptor: CatalogDescriptor = found.catalogs.firstOrNull { it.id == catalogId && it.apiType.equals(type, ignoreCase = true) }
                ?: found.catalogs.firstOrNull { it.id == catalogId }
                ?: return@launch
            // A catalog that needs some other choice to work at all (e.g. a search) is left as it is.
            val needsOther = descriptor.extra.any { it.isRequired && !it.name.equals("genre", ignoreCase = true) } ||
                descriptor.extraRequired.any { !it.equals("genre", ignoreCase = true) }
            if (needsOther) return@launch
            val options = descriptor.extra
                .firstOrNull { it.name.equals("genre", ignoreCase = true) }
                ?.options.orEmpty()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
            if (options.isEmpty()) return@launch
            addon = found
            catalog = descriptor
            val allYears = options.all { YEAR.matches(it) }
            _state.update {
                it.copy(
                    genres = if (allYears) emptyList() else options,
                    years = if (allYears) options.sortedDescending() else emptyList()
                )
            }
        }
    }

    /** Picks a genre (or year) for the addon to filter by; null goes back to the catalog as it is. */
    fun select(value: String?) {
        if (value == _state.value.selected) return
        loadJob?.cancel()
        nextSkip = 0
        _state.update {
            it.copy(selected = value, items = emptyList(), isLoading = false, hasMore = value != null, failed = false)
        }
        if (value != null) loadMore()
    }

    /** The next page of the filtered catalog from the addon. */
    fun loadMore() {
        val current = _state.value
        val value = current.selected ?: return
        if (current.isLoading || !current.hasMore) return
        val source = addon ?: return
        val descriptor = catalog ?: return
        _state.update { it.copy(isLoading = true, failed = false) }
        val skip = nextSkip
        loadJob = viewModelScope.launch {
            val supportsSkip = descriptor.supportsExtra("skip")
            catalogRepository.getCatalog(
                addonBaseUrl = source.baseUrl,
                addonId = source.id,
                addonName = source.displayName,
                catalogId = descriptor.id,
                catalogName = descriptor.name,
                type = descriptor.apiType,
                skip = skip,
                skipStep = descriptor.skipStep(),
                extraArgs = mapOf("genre" to value),
                supportsSkip = supportsSkip
            ).collect { result ->
                when (result) {
                    is NetworkResult.Success -> {
                        val row = result.data
                        _state.update { state ->
                            if (state.selected != value) return@update state
                            val known = state.items.mapTo(HashSet()) { "${it.apiType}:${it.id}" }
                            val fresh = row.items.filter { known.add("${it.apiType}:${it.id}") }
                            state.copy(
                                items = state.items + fresh,
                                isLoading = false,
                                // A page of only repeats means the addon has nothing further.
                                hasMore = row.hasMore && fresh.isNotEmpty()
                            )
                        }
                        nextSkip = row.nextSkip
                    }
                    is NetworkResult.Error -> {
                        _state.update { state ->
                            if (state.selected != value) state else state.copy(isLoading = false, failed = true)
                        }
                    }
                    NetworkResult.Loading -> Unit
                }
            }
        }
    }

    private companion object {
        val YEAR = Regex("""^(18|19|20)\d{2}$""")
    }
}
