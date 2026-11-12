package com.wangzan101.mika.ui

import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.record.RunStore
import java.io.File

data class SourceEntry(
    val step: Int,
    val place: String?,
    val title: String?,
    val text: String?,
    val screenshots: List<Int>,
)

data class SourceUi(val legacyText: String?, val entries: List<SourceEntry>)

/** 从已保存的运行记录还原回答来源；旧记录没有 Sources 时保留最后一组截图。 */
class SourceViewModel(
    private val store: RunStore,
    private val runId: String?,
    private val legacyText: String?,
    private val appLabel: (String) -> String = { it },
) {
    fun load(): SourceUi {
        val events = runId?.let(store::events).orEmpty()
        val observations = events.filterIsInstance<RunEvent.Observed>().associateBy { it.id }
        val sources = events.filterIsInstance<RunEvent.Sources>().lastOrNull()?.items.orEmpty()
        val entries = if (sources.isNotEmpty()) sources.map { source ->
            val observed = observations[source.obsId]
            SourceEntry(
                step = source.step,
                place = source.place?.let { evidencePlace(it, appLabel) },
                title = source.title ?: observed?.title,
                text = observed?.text?.takeIf(String::isNotBlank),
                screenshots = source.mediaIds.distinct(),
            )
        } else {
            val last = events.filterIsInstance<RunEvent.Media>().groupBy { it.step }.maxByOrNull { it.key }
            last?.let { (step, shots) ->
                listOf(SourceEntry(step, shots.lastOrNull { it.place != null }?.place?.let { evidencePlace(it, appLabel) },
                    null, null, shots.map { it.mediaId }.distinct()))
            }.orEmpty()
        }
        return SourceUi(legacyText, entries)
    }

    fun screen(id: Int): File? = runId?.let { store.screen(it, id) }
}

private fun evidencePlace(place: String, appLabel: (String) -> String): String = when {
    place.startsWith("网站 ") -> place.removePrefix("网站 ")
    place.startsWith("http://") || place.startsWith("https://") -> runCatching { java.net.URI(place).host }.getOrNull() ?: place
    com.wangzan101.mika.core.tool.PACKAGE_NAME_RE.matches(place) -> appLabel(place)
    else -> place
}
