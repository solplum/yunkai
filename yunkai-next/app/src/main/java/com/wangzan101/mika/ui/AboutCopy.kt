package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.about.CheckStatus
import com.wangzan101.mika.about.MikaLinks
import com.wangzan101.mika.about.UpdateState
import com.wangzan101.mika.settings.Prerequisites
import com.wangzan101.mika.settings.Problem

/** 关于页的文字,以及「检查更新」一行、设置首页「关于 Mika」一行、侧栏入口上的提示怎么显示。 */
object AboutCopy {
    val TITLE: String get() = tr(R.string.settings_about)
    val TAGLINE: String get() = tr(R.string.about_tagline)
    const val BETA = "Beta"

    val CHECK: String get() = tr(R.string.about_check)
    val CHECK_SUB: String get() = tr(R.string.about_check_sub)
    val CHECK_SUB_NEW: String get() = tr(R.string.about_check_sub_new)
    val UP_TO_DATE: String get() = tr(R.string.about_up_to_date)
    val UP_TO_DATE_TOAST: String get() = tr(R.string.about_up_to_date_toast)
    val OFFLINE: String get() = tr(R.string.about_offline)
    val OFFLINE_TOAST: String get() = tr(R.string.about_offline_toast)
    val CHANGELOG: String get() = tr(R.string.about_changelog)
    val CHANGELOG_SUB: String get() = tr(R.string.about_changelog_sub)
    val CHANGELOG_EMPTY: String get() = tr(R.string.about_changelog_empty)
    val CHANGELOG_ALL: String get() = tr(R.string.about_changelog_all)

    val FEEDBACK: String get() = tr(R.string.about_feedback)
    val FEEDBACK_SUB: String get() = tr(R.string.about_feedback_sub)
    val FEEDBACK_SHEET_SUB: String get() = tr(R.string.about_feedback_sheet_sub)
    val FEEDBACK_GITHUB: String get() = tr(R.string.about_feedback_github)
    val FEEDBACK_GITHUB_SUB: String get() = tr(R.string.about_feedback_github_sub)
    val FEEDBACK_WECHAT: String get() = tr(R.string.about_feedback_wechat)
    val FEEDBACK_WECHAT_SUB: String get() = tr(R.string.about_feedback_wechat_sub)
    val FEEDBACK_X: String get() = tr(R.string.about_feedback_x)
    val FEEDBACK_X_SUB: String get() = tr(R.string.about_feedback_x_sub, MikaLinks.X_HANDLE)

    val DIAGNOSTICS: String get() = tr(R.string.diagnostics_copy)
    val DIAGNOSTICS_SUB: String get() = tr(R.string.diagnostics_copy_sub)
    val DIAGNOSTICS_TITLE: String get() = tr(R.string.diagnostics_title)
    val DIAGNOSTICS_NOTE: String get() = tr(R.string.diagnostics_note)
    val DIAGNOSTICS_COPY: String get() = tr(R.string.diagnostics_copy_button)
    val DIAGNOSTICS_SHARE: String get() = tr(R.string.diagnostics_share)
    val DIAGNOSTICS_COPIED: String get() = tr(R.string.diagnostics_copied)
    val DIAGNOSTICS_LOADING: String get() = tr(R.string.diagnostics_loading)

    val GROUP: String get() = tr(R.string.about_group)
    val GROUP_SUB: String get() = tr(R.string.about_group_sub)
    val GROUP_SHEET_TITLE: String get() = tr(R.string.about_group_sheet_title)
    val GROUP_SHEET_SUB: String get() = tr(R.string.about_group_sheet_sub)
    val GROUP_HINT: String get() = tr(R.string.about_group_hint)
    val SAVE_QR: String get() = tr(R.string.about_save_qr)
    val QR_SAVED: String get() = tr(R.string.about_qr_saved)
    val QR_SAVE_FAILED: String get() = tr(R.string.about_qr_save_failed)
    val GROUP_ALREADY_ADDED: String get() = tr(R.string.about_group_already_added)
    val GROUP_ASK_MIKA: String get() = tr(R.string.about_group_ask_mika)

    val FOLLOW: String get() = tr(R.string.about_follow)
    val FOLLOW_SUB: String get() = tr(R.string.about_follow_sub, MikaLinks.X_HANDLE)
    val REPO: String get() = tr(R.string.about_repo)
    val REPO_SUB: String get() = tr(R.string.about_repo_sub, MikaLinks.REPO_LABEL)
    val GUIDE: String get() = tr(R.string.about_guide)
    val GUIDE_SUB: String get() = tr(R.string.about_guide_sub)

    val DATA_NOTE: String get() = tr(R.string.about_data_note)
    val LICENSE_LINK: String get() = tr(R.string.about_license_link)
    val AUTHOR: String get() = tr(R.string.about_author)
    val LICENSE_TITLE: String get() = tr(R.string.about_license_title)

    val UPDATE_DOWNLOAD: String get() = tr(R.string.about_update_download)
    val UPDATE_MIRROR: String get() = tr(R.string.about_update_mirror)
    val UPDATE_LATER: String get() = tr(R.string.about_update_later)
    val NO_BROWSER: String get() = tr(R.string.about_no_browser)

    fun version(versionName: String): String = tr(R.string.about_version, versionName)

    fun buildLabel(build: String): String = tr(R.string.about_build, build)

    fun available(version: String): String = tr(R.string.about_available, version)
    fun updateTitle(version: String): String = tr(R.string.about_update_title, version)

    /** 「当前 0.1.0 Beta · 2026-11-05 发布」;不知道发布日期时只写当前版本。 */
    fun updateSub(current: String, beta: Boolean, date: String?): String =
        tr(R.string.about_current, current + if (beta) " $BETA" else "") + (date?.let { tr(R.string.about_released_on, it) } ?: "")

    /** 「检查更新」一行右边:正在查转圈,有新版写版本号带红点,查过一次写结果;从没查过什么都不写。 */
    data class CheckValue(val text: String?, val dot: Boolean = false, val spinner: Boolean = false, val highlight: Boolean = false)

    fun checkValue(s: UpdateState): CheckValue = when {
        s.status == CheckStatus.Checking -> CheckValue(null, spinner = true)
        s.update != null -> CheckValue(available(s.update.version), dot = true, highlight = true)
        s.status == CheckStatus.Failed -> CheckValue(OFFLINE)
        s.latest != null -> CheckValue(UP_TO_DATE)
        else -> CheckValue(null)
    }

    /** 设置首页「关于 Mika」一行右边:有新版写「x 可更新」带红点,不然写当前版本号。 */
    fun settingsValue(updateVersion: String?, versionName: String): CheckValue =
        if (updateVersion != null) CheckValue(available(updateVersion), dot = true, highlight = true)
        else CheckValue(versionName.takeIf { it.isNotEmpty() })

    /** 「让 Mika 帮我发消息入群」还差的那一步:提示 [message],去 [fix] 那页补上(为空时去设置首页)。 */
    enum class GroupTaskGap(private val messageRes: Int, val fix: Fix?) {
        Model(R.string.about_group_gap_model, Fix.AddModel),
        Phone(R.string.about_group_gap_phone, Fix.OpenPhoneSetup),
        TooOld(R.string.about_group_gap_too_old, null);

        val message: String get() = tr(messageRes)
    }

    /** 这件事要操作微信:模型配好、手机操作就绪才开始;差什么返回什么,都齐了为 null。 */
    fun groupTaskGap(check: Prerequisites.Check): GroupTaskGap? = when {
        Problem.NoModel in check.problems -> GroupTaskGap.Model
        Problem.SystemTooOld in check.problems -> GroupTaskGap.TooOld
        !check.phoneReady -> GroupTaskGap.Phone
        else -> null
    }

    /** 侧栏「关于 Mika」上的红点。 */
    fun drawerDot(s: UpdateState): Boolean = s.hasUpdate
}
