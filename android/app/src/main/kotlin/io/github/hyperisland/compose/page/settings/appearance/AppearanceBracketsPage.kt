package io.github.hyperisland.compose.page.settings.appearance

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.hyperisland.R
import io.github.hyperisland.compose.component.AppCard
import io.github.hyperisland.compose.component.PreferenceSwitch
import io.github.hyperisland.compose.component.SectionTitle
import io.github.hyperisland.compose.data.FlutterPrefsRepository
import io.github.hyperisland.compose.data.rememberBooleanPreference
import io.github.hyperisland.utils.CameraBracketsConfig

/** 「外观 → 括号」：在岛两侧画 ( )，可选常驻摄像头孔并带防烧屏。 */
@Composable
internal fun AppearanceBracketsPage(prefs: FlutterPrefsRepository, onBack: () -> Unit) {
    val enabled = rememberBooleanPreference(prefs, CameraBracketsConfig.ENABLED_KEY, false)
    val always = rememberBooleanPreference(prefs, CameraBracketsConfig.ALWAYS_KEY, false)
    val burnIn = rememberBooleanPreference(prefs, CameraBracketsConfig.BURN_IN_KEY, true)
    AppearanceDetailPage(title = stringResource(R.string.appearance_brackets), onBack = onBack) {
        item {
            Column {
                SectionTitle(stringResource(R.string.appearance_brackets))
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    PreferenceSwitch(
                        title = stringResource(R.string.brackets_enable),
                        summary = stringResource(R.string.brackets_enable_summary),
                        icon = null,
                        checked = enabled.value,
                    ) {
                        enabled.value = it
                        prefs.putBoolean(CameraBracketsConfig.ENABLED_KEY, it)
                    }
                    PreferenceSwitch(
                        title = stringResource(R.string.brackets_always),
                        summary = stringResource(R.string.brackets_always_summary),
                        icon = null,
                        checked = always.value,
                        enabled = enabled.value,
                    ) {
                        always.value = it
                        prefs.putBoolean(CameraBracketsConfig.ALWAYS_KEY, it)
                    }
                    PreferenceSwitch(
                        title = stringResource(R.string.brackets_burn_in),
                        summary = stringResource(R.string.brackets_burn_in_summary),
                        icon = null,
                        checked = burnIn.value,
                        enabled = enabled.value && always.value,
                    ) {
                        burnIn.value = it
                        prefs.putBoolean(CameraBracketsConfig.BURN_IN_KEY, it)
                    }
                }
            }
        }
    }
}
