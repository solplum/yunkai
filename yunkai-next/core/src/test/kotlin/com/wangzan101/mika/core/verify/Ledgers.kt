package com.wangzan101.mika.core.verify

import com.wangzan101.mika.core.observation.InputHints
import com.wangzan101.mika.core.observation.ObsKind
import com.wangzan101.mika.core.observation.Observation
import com.wangzan101.mika.core.observation.Place

/** 测试用:按给的读屏、命令输出、工具事实依次记进一份账本(第几步按先后编号)。 */
fun ledgerOf(screens: List<String> = emptyList(), outputs: List<String> = emptyList(), facts: List<String> = emptyList()): EvidenceLedger =
    EvidenceLedger().also { l ->
        var step = 0
        screens.forEach { l.add(++step, ObsKind.Read, null, 0, it) }
        outputs.forEach { l.add(++step, ObsKind.Output, null, 0, it) }
        facts.forEach { l.add(++step, ObsKind.Fact, null, 0, it) }
    }

/** 测试里写网址或包名当地方:http 开头的是网页,其余是 App。 */
fun placeOf(where: String?): Place? = where?.let { if (it.startsWith("http")) Place.page(it) else Place.app(it) }

/** 测试用的简写:第 [step] 步在 [where] 读到 [text]。 */
fun EvidenceLedger.add(
    step: Int, kind: ObsKind, where: String?, atMs: Long, text: String,
    inputs: List<String>? = null, focused: String? = null, authored: String? = null, title: String? = null,
): Observation? = add(obs(step, kind, where, text, inputs, focused, authored, title), step, atMs)

/** 测试用:一条观察。 */
fun obs(
    step: Int, kind: ObsKind, where: String?, text: String,
    inputs: List<String>? = null, focused: String? = null, authored: String? = null, title: String? = null,
) = Observation(kind, text, place = placeOf(where), title = title, authored = authored, inputs = inputs?.let { InputHints(it, focused) }, step = step)

/** 测试用:再看一眼读到的文字(空串为什么也没读到)。 */
fun freshOf(text: String): List<Observation> = if (text.isEmpty()) emptyList() else listOf(Observation(ObsKind.Read, text, inputs = InputHints(emptyList())))
