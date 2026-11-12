package com.wangzan101.mika.core.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SensitiveFilterTest {
    @Test fun passwordWithDigitsHit() {
        assertEquals("password", SensitiveFilter.hit("支付密码是123456"))
        assertNotNull(SensitiveFilter.hit("门禁口令:8866"))
        assertNotNull(SensitiveFilter.hit("123456是我的密码"))
        assertNull(SensitiveFilter.hit("用户忘了密码时会找客服")) // 只有关键词,不带数字字母串
    }

    @Test fun verificationCodeHit() {
        assertNotNull(SensitiveFilter.hit("验证码 4821"))
        assertNotNull(SensitiveFilter.hit("信用卡 CVV 是 1234"))
        assertNotNull(SensitiveFilter.hit("PIN: 0000"))
    }

    @Test fun idNumberHit() {
        assertEquals("id_number", SensitiveFilter.hit("我的身份证 11010519491231002X"))
        assertEquals("id_number", SensitiveFilter.hit("证件号440308199001011239"))
    }

    @Test fun idFormatWithBadDateOrCheckDigitNotId() {
        assertNotEquals("id_number", SensitiveFilter.hit("单号 440308199001011234")) // 校验位不对
        assertNotEquals("id_number", SensitiveFilter.hit("单号 110105194913310021")) // 13 月
    }

    @Test fun fullWidthAndSpacedDigitsHit() {
        assertNotNull(SensitiveFilter.hit("验证码 1 2 3 4"))
        assertNotNull(SensitiveFilter.hit("支付密码是１２３４５６"))
        assertEquals("id_number", SensitiveFilter.hit("身份证１１０１０５１９４９１２３１００２Ｘ"))
    }

    @Test fun pinAndOtpNeedDigits() {
        assertNotNull(SensitiveFilter.hit("otp 552211"))
        assertNull(SensitiveFilter.hit("pin this note to the top"))
        assertNull(SensitiveFilter.hit("PIN: abcd"))
    }

    @Test fun luhnCardHit() {
        assertEquals("card_number", SensitiveFilter.hit("卡号 4111111111111111"))
        assertEquals("card_number", SensitiveFilter.hit("卡号 4111 1111 1111 1111"))
        assertEquals("card_number", SensitiveFilter.hit("卡号6222020200112233446")) // 19 位,过 Luhn
    }

    @Test fun nonLuhnLongNumberNotHit() {
        assertNull(SensitiveFilter.hit("订单号 4111111111111112"))
    }

    @Test fun phoneAndAddressAllowed() {
        assertNull(SensitiveFilter.hit("我手机号 13800138000"))
        assertNull(SensitiveFilter.hit("寄到浦东张江路 100 号"))
        assertNull(SensitiveFilter.hit("用户的妈妈在微信里备注为『老妈』,生日 1960-05-01"))
        assertNull(SensitiveFilter.hit("用户把便签置顶 pinned 了 2024 年的计划")) // pin 要整词
    }

    @Test fun apiKeyHit() {
        assertEquals("api_key", SensitiveFilter.hit("key 是 sk-" + "a1B2c3D4e5F6g7H8i9J0k1L2"))
    }

    @Test fun englishVariantsHit() {
        assertNotNull(SensitiveFilter.hit("my password is hunter2"))
        assertNotNull(SensitiveFilter.hit("Passcode: 7788"))
        assertNotNull(SensitiveFilter.hit("the verification code was 552211"))
        assertNotNull(SensitiveFilter.hit("security code 998"+"7"))
    }
}
