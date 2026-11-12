// Modified for Yunkai from Mika d68159366abaea523c5e4e6fd4dc83aad78f9698; see UPSTREAM.md and UPSTREAM_CHANGES.json at repository root.
package com.wangzan101.mika.ui

import com.wangzan101.mika.i18n.tr
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.R
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.Hairline
import com.wangzan101.mika.ui.components.IconTile
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.PageBackground
import com.wangzan101.mika.ui.components.ProgressDots
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/** 上手第一步:说清 Mika 是什么、能做哪几类事,开始前要做的两件事和大概时间。 */
@Composable
fun WelcomeScreen(onStart: () -> Unit) {
    val c = MikaTheme.colors
    PageBackground {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).windowInsetsPadding(WindowInsets.navigationBars)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                Column(Modifier.fillMaxWidth().padding(top = 40.dp, start = 8.dp, end = 8.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(
                        painterResource(R.drawable.yunkai_launcher_art), null,
                        Modifier.size(76.dp).shadow(14.dp, LogoShape, clip = false).clip(LogoShape),
                        contentScale = ContentScale.Crop,
                    )
                    Text(WelcomeCopy.TITLE, Modifier.padding(top = 20.dp), style = MikaType.LargeTitle, fontSize = 26.sp, color = c.ink, textAlign = TextAlign.Center)
                    Text(
                        WelcomeCopy.LEAD, Modifier.padding(top = 8.dp),
                        fontSize = 15.sp, lineHeight = 24.sp, color = c.ink2, textAlign = TextAlign.Center,
                    )
                }
                GroupCard(Modifier.padding(top = 28.dp)) {
                    WelcomeCopy.FEATURES.forEachIndexed { i, f -> Feature(f.icon, f.title, f.body, first = i == 0) }
                }
            }
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)) {
                ProgressDots(Onboarding.DOTS, Onboarding.dot(OnboardingStep.Welcome), Modifier.padding(top = 4.dp))
                Text(
                    WelcomeCopy.NOTE, Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 12.dp, start = 16.dp, end = 16.dp),
                    style = MikaType.Hint, fontSize = 12.5.sp, color = c.ink3, textAlign = TextAlign.Center,
                )
                MikaButton(WelcomeCopy.START, onStart, Modifier.fillMaxWidth().testTag("welcome-start"))
            }
        }
    }
}

@Composable
private fun Feature(icon: ImageVector, title: String, body: String, first: Boolean) {
    val c = MikaTheme.colors
    Column {
        if (!first) Hairline(start = 0.dp)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconTile(icon)
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold, color = c.ink)
                Text(body, Modifier.padding(top = 2.dp), style = MikaType.Caption, color = c.ink2)
            }
        }
    }
}

private val LogoShape = RoundedCornerShape(22.dp)

/** 欢迎页的文字。 */
object WelcomeCopy {
    val TITLE: String get() = tr(R.string.welcome_title)
    val LEAD: String get() = tr(R.string.welcome_lead)
    val NOTE: String get() = tr(R.string.welcome_note)
    val START: String get() = tr(R.string.welcome_start)

    data class Item(val icon: ImageVector, val title: String, val body: String)

    val FEATURES get() = listOf(
        Item(MikaIcons.Apps, tr(R.string.welcome_apps), tr(R.string.welcome_apps_body)),
        Item(MikaIcons.Globe, tr(R.string.welcome_web), tr(R.string.welcome_web_body)),
        Item(MikaIcons.Doc, tr(R.string.welcome_files), tr(R.string.welcome_files_body)),
        Item(MikaIcons.Clock, tr(R.string.welcome_schedule), tr(R.string.welcome_schedule_body)),
    )
}
