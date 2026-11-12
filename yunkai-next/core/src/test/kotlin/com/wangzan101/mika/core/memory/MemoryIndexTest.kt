package com.wangzan101.mika.core.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryIndexTest {
    @Test fun tokenizeBigramsSingleHanAndWords() {
        assertEquals(listOf("不要", "要香", "香菜"), Tokenize.terms("不要香菜"))
        assertEquals(listOf("香"), Tokenize.terms("香"))
        assertEquals(listOf("用", "didi", "打车", "100"), Tokenize.terms("用 DiDi 打车,100"))
        assertEquals(listOf("香菜", "香", "菜", "辣"), Tokenize.terms("香菜 辣", withUnigrams = true))
    }

    @Test fun singleSharedTermHitsWithOneItemAndWith499Distractors() {
        val target = mem("外卖不要香菜", keywords = listOf("外卖"))
        assertEquals(target, MemoryIndex(listOf(target)).search("点外卖", 8).single().first)
        val big = MemoryIndex(distractors(499) + target).search("点外卖", 8)
        assertEquals(target, big.single().first)
        assertTrue(big.single().second >= MemoryContext.MIN_SCORE)
    }

    @Test fun singleRareTermWithoutKeywordNoHit() {
        // 只此一条有「以后」,但它不是检索词:不算相关
        val index = MemoryIndex(distractors(20) + mem("用户以后都用支付宝付款", keywords = listOf("支付宝", "付款")))
        assertTrue(index.search("以后再说吧", 8).isEmpty())
        assertTrue(MemoryIndex(listOf(mem("外卖不要香菜"))).search("点外卖", 8).isEmpty())
    }

    @Test fun stopTermOnlyNoHit() {
        val index = MemoryIndex(distractors(499) + mem("外卖不要香菜"))
        assertTrue(index.search("不要放葱", 8).isEmpty())
    }

    @Test fun commonSingleTermNotRelevant() {
        val many = (1..60).map { mem("外卖第${it}单送到了") }
        assertTrue(MemoryIndex(many).search("外卖", 8).isEmpty())
    }

    @Test fun singleCharKeywordMatches() {
        val target = mem("订餐口味清淡", keywords = listOf("辣"))
        assertEquals(target, MemoryIndex(distractors(10) + target).search("不要辣的", 8).single().first)
    }

    @Test fun chineseBigramMatches() {
        val target = mem("不要香菜", keywords = listOf("香菜"))
        val hits = MemoryIndex(distractors(10) + target).search("外卖香菜", limit = 3)
        assertEquals(target, hits.first().first)
        assertEquals(1, hits.size)
    }

    @Test fun keywordsBridgeSynonyms() {
        val target = mem("订餐不要辣", keywords = listOf("外卖", "口味"))
        val hits = MemoryIndex(distractors(10) + target).search("点外卖", limit = 3)
        assertEquals(target, hits.single().first)
    }

    @Test fun pendingExcludedByDefault() {
        val pending = mem("点外卖先比价", kind = MemoryKind.procedure, status = MemoryStatus.pending, keywords = listOf("外卖"))
        val index = MemoryIndex(distractors(5) + pending)
        assertTrue(index.search("外卖", limit = 8).isEmpty())
        assertEquals(pending, index.search("外卖", limit = 8, includePending = true).single().first)
    }

    @Test fun englishCaseInsensitive() {
        val target = mem("Use DiDi for rides", keywords = listOf("didi"))
        val hits = MemoryIndex(distractors(5) + target).search("didi", limit = 8)
        assertEquals(target, hits.single().first)
    }

    @Test fun rankedByScoreAndLimited() {
        val strong = mem("外卖不要香菜不要葱")
        val weak = mem("外卖常点面条")
        val hits = MemoryIndex(distractors(10) + weak + strong).search("外卖不要香菜", limit = 1)
        assertEquals(listOf(strong), hits.map { it.first })
    }
}
