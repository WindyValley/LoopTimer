package com.looptimer.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.looptimer.R
import com.looptimer.domain.AlarmSetting
import com.looptimer.domain.TimeRanges

/**
 * Alarm configuration shared by the home screen and the preset editor.
 *
 * Because [AlarmSetting] models "off / fixed / forever" explicitly, "ring forever" is now
 * selectable everywhere. Previously it was encoded as `alarmDuration = -1` and reachable only
 * from the home screen, while the preset editor clamped input to 1..300.
 */
@Composable
fun AlarmSettingsPanel(
    setting: AlarmSetting,
    onSettingChange: (AlarmSetting) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.label_alarm),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp
            )
            Switch(
                checked = setting.isEnabled,
                onCheckedChange = { enabled ->
                    onSettingChange(
                        if (enabled) AlarmSetting.DEFAULT_FIXED else AlarmSetting.Off
                    )
                }
            )
        }

        if (setting.isEnabled) {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.label_alarm_duration),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NumberInputField(
                        value = (setting as? AlarmSetting.Fixed)?.seconds
                            ?: AlarmSetting.DEFAULT_FIXED.seconds,
                        range = TimeRanges.ALARM_SECONDS,
                        onValueChange = { onSettingChange(AlarmSetting.Fixed(it)) }
                    )
                    Text(
                        stringResource(R.string.unit_second),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    FilterChip(
                        selected = setting is AlarmSetting.Forever,
                        onClick = {
                            onSettingChange(
                                if (setting is AlarmSetting.Forever) {
                                    AlarmSetting.DEFAULT_FIXED
                                } else {
                                    AlarmSetting.Forever
                                }
                            )
                        },
                        label = { Text(stringResource(R.string.alarm_forever), fontSize = 12.sp) }
                    )
                }
            }
        }
    }
}
