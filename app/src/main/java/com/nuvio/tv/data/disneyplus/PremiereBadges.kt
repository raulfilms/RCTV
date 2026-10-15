package com.nuvio.tv.data.disneyplus

import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.MetaPreview
import java.time.LocalDate

/**
 * A row of releases with labels on its cards: what's out first (newest first; [newLabel] for the
 * last [newDays] days, else [premiereLabel] when there is one), then what's coming (soonest first,
 * [comingLabel]).
 */
fun CatalogRow.withPremiereBadges(
    newLabel: String,
    premiereLabel: String?,
    comingLabel: String,
    newDays: Long,
    today: LocalDate = LocalDate.now()
): CatalogRow {
    fun MetaPreview.date(): LocalDate? = released?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
    val (upcoming, out) = items.partition { item -> item.date()?.isAfter(today) == true }
    val released = out
        .sortedByDescending { it.date() ?: LocalDate.MIN }
        .map { item ->
            val date = item.date()
            val isNew = date != null && !date.isBefore(today.minusDays(newDays))
            item.copy(badge = if (isNew) newLabel else premiereLabel, badgeUpcoming = false)
        }
    val coming = upcoming
        .sortedBy { it.date() ?: LocalDate.MAX }
        .map { it.copy(badge = comingLabel, badgeUpcoming = true) }
    return copy(items = released + coming)
}
