package com.looptimer.ui.presets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.looptimer.R
import com.looptimer.domain.TimerConfig
import com.looptimer.domain.TimerPreset
import com.looptimer.ui.components.AlarmSettingsPanel
import com.looptimer.ui.components.LoopInputRow
import com.looptimer.ui.components.TimeInputRow

/**
 * Create/edit a preset.
 *
 * Uses the same input components and the same [com.looptimer.domain.TimeRanges] bounds as the
 * home screen, so a preset can express exactly the configurations the timer supports. The old
 * editor had its own controls capped at 59 minutes and 300 alarm seconds.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetEditScreen(
    editing: TimerPreset?,
    onSave: (name: String, config: TimerConfig) -> Unit,
    onCancel: () -> Unit
) {
    var name by remember { mutableStateOf(editing?.name ?: "") }
    var config by remember { mutableStateOf(editing?.config ?: TimerConfig()) }
    var nameError by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (editing != null) R.string.preset_edit_title
                            else R.string.preset_new_title
                        )
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            if (name.isBlank()) {
                                nameError = true
                            } else {
                                onSave(name.trim(), config)
                            }
                        }
                    ) {
                        Text(
                            stringResource(R.string.action_save),
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it.take(TimerPreset.MAX_NAME_LENGTH)
                    nameError = false
                },
                label = { Text(stringResource(R.string.preset_name_label)) },
                placeholder = { Text(stringResource(R.string.preset_name_hint)) },
                isError = nameError,
                supportingText = if (nameError) {
                    { Text(stringResource(R.string.preset_name_required)) }
                } else {
                    null
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(24.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    TimeInputRow(
                        label = stringResource(R.string.label_work_duration),
                        minutes = config.workMinutes,
                        seconds = config.workSeconds,
                        onMinutesChange = { config = config.copy(workMinutes = it) },
                        onSecondsChange = { config = config.copy(workSeconds = it) }
                    )

                    Spacer(Modifier.height(12.dp))

                    TimeInputRow(
                        label = stringResource(R.string.label_break_duration),
                        minutes = config.breakMinutes,
                        seconds = config.breakSeconds,
                        onMinutesChange = { config = config.copy(breakMinutes = it) },
                        onSecondsChange = { config = config.copy(breakSeconds = it) },
                        showZeroShortcut = true
                    )

                    Spacer(Modifier.height(12.dp))

                    LoopInputRow(
                        loops = config.loops,
                        onLoopsChange = { config = config.copy(loops = it) }
                    )

                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))

                    AlarmSettingsPanel(
                        setting = config.alarm,
                        onSettingChange = { config = config.copy(alarm = it) }
                    )

                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.label_auto_start),
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 16.sp
                        )
                        Switch(
                            checked = config.autoStart,
                            onCheckedChange = { config = config.copy(autoStart = it) }
                        )
                    }
                }
            }
        }
    }
}
