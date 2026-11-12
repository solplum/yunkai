package com.wangzan101.mika.core.memory

private var fixtureSeq = 0

internal fun mem(
    content: String,
    kind: MemoryKind = MemoryKind.fact,
    keywords: List<String> = emptyList(),
    status: MemoryStatus = MemoryStatus.active,
    pinned: Boolean = false,
    id: String = "m${++fixtureSeq}",
) = MemoryItem(
    id = id, kind = kind, content = content, keywords = keywords,
    source = MemorySource(type = "manual", atMs = 0L), status = status, pinned = pinned,
    createdAt = 0L, updatedAt = 0L,
)

/** 互不相关的填充条目,让检索的 IDF 接近真实规模。 */
internal fun distractors(n: Int) = (1..n).map { mem("无关事实第${it}条:用户周${it % 7}去健身房锻炼身体") }
