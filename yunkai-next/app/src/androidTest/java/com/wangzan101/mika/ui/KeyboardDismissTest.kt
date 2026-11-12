package com.wangzan101.mika.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.ui.components.KeyboardDismissArea
import com.wangzan101.mika.ui.components.keepsKeyboard
import com.wangzan101.mika.ui.theme.MikaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeyboardDismissTest {
    @get:Rule val compose = createComposeRule()

    private var clicks = 0

    private fun page() = compose.setContent {
        MikaTheme {
            KeyboardDismissArea(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxWidth().height(300.dp).testTag("outside"))
                    Box(Modifier.size(80.dp).testTag("button").clickable { clicks++ })
                    Box(Modifier.fillMaxWidth().keepsKeyboard()) {
                        var text by remember { mutableStateOf("") }
                        Column {
                            BasicTextField(text, { text = it }, Modifier.fillMaxWidth().testTag("field"))
                            Box(Modifier.size(40.dp).testTag("send"))
                        }
                    }
                }
            }
        }
    }

    @Test
    fun tapOutsideClearsFocusAndStillClicks() {
        page()
        compose.onNodeWithTag("field").performClick().assertIsFocused()
        compose.onNodeWithTag("button").performClick()
        compose.onNodeWithTag("field").assertIsNotFocused()
        assertEquals(1, clicks)
    }

    @Test
    fun tapInsideInputBarKeepsFocus() {
        page()
        compose.onNodeWithTag("field").performClick().assertIsFocused()
        compose.onNodeWithTag("send").performClick()
        compose.onNodeWithTag("field").assertIsFocused()
    }

    @Test
    fun dragOutsideClearsFocus() {
        page()
        compose.onNodeWithTag("field").performClick().assertIsFocused()
        compose.onNodeWithTag("outside").performTouchInput { swipeUp() }
        compose.onNodeWithTag("field").assertIsNotFocused()
    }
}
