package com.looptimer.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.looptimer.BuildConfig
import com.looptimer.R
import com.looptimer.domain.Phase
import com.looptimer.domain.RunState
import com.looptimer.domain.TimerConfig
import com.looptimer.domain.TimerState
import com.looptimer.ui.components.AlarmSettingsPanel
import com.looptimer.ui.components.LoopInputRow
import com.looptimer.ui.components.TimeInputRow
import com.looptimer.ui.theme.BreakColor
import com.looptimer.ui.theme.WorkColor

/**
 * The home screen: a pure function of [config] and [timer].
 *
 * It holds no timer state and runs no coroutines. Every interaction is reported upward, so the
 * service remains the single source of truth.
 */
@Composable
fun HomeScreen(
    config: TimerConfig,
    timer: TimerState,
    alarmRinging: Boolean,
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onConfigChange: (TimerConfig) -> Unit,
    onToggleRun: () -> Unit,
    onReset: () -> Unit,
    onSkip: () -> Unit,
    onStopAlarm: () -> Unit,
    onOpenPresets: () -> Unit = {}
) {
    val phaseColor = if (timer.phase == Phase.WORK) WorkColor else BreakColor
    val phaseLabel = stringResource(
        if (timer.phase == Phase.WORK) R.string.phase_work else R.string.phase_break
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.home_title),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            IconButton(onClick = onToggleTheme) {
                Icon(
                    painter = painterResource(R.drawable.ic_theme),
                    // Was an emoji with no description, so TalkBack announced nothing.
                    contentDescription = stringResource(R.string.cd_toggle_theme),
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = stringResource(
                R.string.home_progress, timer.currentLoop, timer.totalLoops, phaseLabel
            ),
            fontSize = 16.sp,
            color = phaseColor
        )

        Spacer(Modifier.height(40.dp))

        Box(
            modifier = Modifier
                .size(260.dp)
                .background(phaseColor.copy(alpha = 0.2f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "%02d:%02d".format(timer.remainingSeconds / 60, timer.remainingSeconds % 60),
                    fontSize = 64.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                if (alarmRinging) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.alarm_ringing),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        if (alarmRinging) {
            Button(
                onClick = onStopAlarm,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = WorkColor)
            ) {
                Text(stringResource(R.string.action_stop_alarm), fontSize = 18.sp)
            }
            Spacer(Modifier.height(16.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onToggleRun,
                enabled = timer.remainingSeconds > 0 || timer.runState == RunState.COMPLETED,
                colors = ButtonDefaults.buttonColors(containerColor = phaseColor)
            ) {
                Text(
                    stringResource(
                        if (timer.isRunning) R.string.action_pause else R.string.action_start
                    ),
                    fontSize = 18.sp,
                    color = Color.White
                )
            }
            OutlinedButton(onClick = onReset) {
                Text(stringResource(R.string.action_reset), fontSize = 18.sp)
            }
            OutlinedButton(onClick = onSkip) {
                Text(stringResource(R.string.action_skip), fontSize = 18.sp)
            }
        }

        Spacer(Modifier.height(24.dp))

        OutlinedButton(onClick = onOpenPresets, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.home_presets), fontSize = 16.sp)
        }

        Spacer(Modifier.height(16.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    stringResource(R.string.label_settings),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(Modifier.height(16.dp))

                TimeInputRow(
                    label = stringResource(R.string.label_work_duration),
                    minutes = config.workMinutes,
                    seconds = config.workSeconds,
                    onMinutesChange = { onConfigChange(config.copy(workMinutes = it)) },
                    onSecondsChange = { onConfigChange(config.copy(workSeconds = it)) }
                )

                Spacer(Modifier.height(12.dp))

                TimeInputRow(
                    label = stringResource(R.string.label_break_duration),
                    minutes = config.breakMinutes,
                    seconds = config.breakSeconds,
                    onMinutesChange = { onConfigChange(config.copy(breakMinutes = it)) },
                    onSecondsChange = { onConfigChange(config.copy(breakSeconds = it)) },
                    showZeroShortcut = true
                )

                Spacer(Modifier.height(12.dp))

                LoopInputRow(
                    loops = config.loops,
                    onLoopsChange = { onConfigChange(config.copy(loops = it)) }
                )

                Spacer(Modifier.height(16.dp))

                AlarmSettingsPanel(
                    setting = config.alarm,
                    onSettingChange = { onConfigChange(config.copy(alarm = it)) }
                )

                Spacer(Modifier.height(12.dp))

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
                        onCheckedChange = { onConfigChange(config.copy(autoStart = it)) }
                    )
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    text = stringResource(R.string.home_version, BuildConfig.VERSION_NAME),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
