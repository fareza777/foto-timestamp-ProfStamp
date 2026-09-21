package com.proofstamp.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.proofstamp.app.BuildConfig
import com.proofstamp.app.R
import com.proofstamp.app.ads.AdBanner
import com.proofstamp.app.data.settings.AppSettings
import com.proofstamp.app.data.settings.WatermarkTemplate
import com.proofstamp.app.di.AppContainer
import com.proofstamp.app.ui.components.PsCard
import com.proofstamp.app.ui.components.SectionLabel
import com.proofstamp.app.ui.components.SettingToggle
import com.proofstamp.app.ui.nav.containerViewModel
import com.proofstamp.app.ui.theme.PsColors
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    val settings: StateFlow<AppSettings> = container.settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private val repo get() = container.settings
    fun setTemplate(t: WatermarkTemplate) = viewModelScope.launch { repo.setTemplate(t) }
    fun setShowQr(v: Boolean) = viewModelScope.launch { repo.setShowQr(v) }
    fun setShowCoordinates(v: Boolean) = viewModelScope.launch { repo.setShowCoordinates(v) }
    fun setShowPlaceName(v: Boolean) = viewModelScope.launch { repo.setShowPlaceName(v) }
    fun setGpsEnabled(v: Boolean) = viewModelScope.launch { repo.setGpsEnabled(v) }
    fun setStripGps(v: Boolean) = viewModelScope.launch { repo.setStripGpsOnShare(v) }
    fun setOperator(v: String) = viewModelScope.launch { repo.setOperator(v.trim()) }
    fun setDefaultProject(v: String) = viewModelScope.launch { repo.setDefaultProject(v.trim()) }
}

@Composable
fun SettingsScreen(container: AppContainer, onOpenPresets: () -> Unit) {
    val vm = containerViewModel { SettingsViewModel(container) }
    val s by vm.settings.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().background(PsColors.Bg)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 16.dp)) {
            Text(
                stringResource(R.string.nav_settings),
                style = MaterialTheme.typography.headlineMedium,
                color = PsColors.Text,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 16.dp),
            )

            SectionLabel(stringResource(R.string.settings_identity))
            PsCard {
                CommitTextField(s.operator, stringResource(R.string.settings_operator), vm::setOperator)
                Spacer(Modifier.height(8.dp))
                CommitTextField(s.defaultProject, stringResource(R.string.settings_project), vm::setDefaultProject)
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onOpenPresets).padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.presets), style = MaterialTheme.typography.bodyLarge, color = PsColors.Text, modifier = Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = PsColors.TextDim)
                }
            }
            Spacer(Modifier.height(16.dp))

            SectionLabel(stringResource(R.string.settings_watermark))
            PsCard {
                Text(stringResource(R.string.settings_template), style = MaterialTheme.typography.labelMedium, color = PsColors.TextDim)
                Spacer(Modifier.height(6.dp))
                WatermarkTemplate.entries.forEach { t ->
                    val selected = s.template == t
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.setTemplate(t) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                            null,
                            tint = if (selected) PsColors.Accent else PsColors.TextFaint,
                        )
                        Column {
                            Text(t.label, style = MaterialTheme.typography.bodyLarge, color = PsColors.Text)
                            Text(t.description, style = MaterialTheme.typography.bodySmall, color = PsColors.TextDim)
                        }
                    }
                }
            }
            PsCard(Modifier.padding(top = 8.dp)) {
                SettingToggle(stringResource(R.string.settings_show_qr), checked = s.showQr, onChange = vm::setShowQr)
                SettingToggle(stringResource(R.string.settings_show_coords), checked = s.showCoordinates, onChange = vm::setShowCoordinates)
                SettingToggle(stringResource(R.string.settings_show_place), checked = s.showPlaceName, onChange = vm::setShowPlaceName)
            }
            Spacer(Modifier.height(16.dp))

            SectionLabel(stringResource(R.string.settings_privacy))
            PsCard {
                SettingToggle(stringResource(R.string.settings_gps_capture), checked = s.gpsEnabled, onChange = vm::setGpsEnabled)
                SettingToggle(
                    stringResource(R.string.settings_strip_exif),
                    subtitle = stringResource(R.string.settings_strip_exif_body),
                    checked = s.stripGpsOnShare,
                    onChange = vm::setStripGps,
                )
            }
            Spacer(Modifier.height(16.dp))

            SectionLabel(stringResource(R.string.settings_about))
            PsCard {
                Text(stringResource(R.string.settings_offline), style = MaterialTheme.typography.bodyMedium, color = PsColors.Text)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.settings_c2pa_body), style = MaterialTheme.typography.bodySmall, color = PsColors.TextDim)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.settings_ads), style = MaterialTheme.typography.bodySmall, color = PsColors.TextDim)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodySmall, color = PsColors.TextFaint)
            }
            Spacer(Modifier.height(24.dp))
        }
        AdBanner()
    }
}

/** Text field that commits on focus loss so DataStore isn't written per keystroke. */
@Composable
private fun CommitTextField(value: String, label: String, onCommit: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(focused) { if (!focused && text != value) onCommit(text) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
    )
}
