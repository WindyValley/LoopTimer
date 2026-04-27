package com.looptimer

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.UUID

class PresetEditActivity : ComponentActivity() {

    private lateinit var presetsManager: PresetsManager
    private var editingPreset: TimerPreset? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        presetsManager = PresetsManager(this)

        // 获取编辑的预设 ID
        val presetId = intent.getStringExtra(EXTRA_PRESET_ID)
        editingPreset = presetId?.let { presetsManager.getPreset(it) }

        val isDark = presetsManager.isDarkTheme()

        setContent {
            MaterialTheme {
                PresetEditScreen(
                    isDarkTheme = isDark,
                    editingPreset = editingPreset,
                    onSave = { preset ->
                        if (editingPreset != null) {
                            presetsManager.updatePreset(preset)
                            Toast.makeText(this, "预设已更新", Toast.LENGTH_SHORT).show()
                        } else {
                            presetsManager.addPreset(preset)
                            Toast.makeText(this, "预设已添加", Toast.LENGTH_SHORT).show()
                        }
                        finish()
                    },
                    onCancel = {
                        finish()
                    }
                )
            }
        }
    }

    companion object {
        const val EXTRA_PRESET_ID = "preset_id"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetEditScreen(
    isDarkTheme: Boolean,
    editingPreset: TimerPreset?,
    onSave: (TimerPreset) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current

    val bgColor = if (isDarkTheme) Color(0xFF1E1E1E) else Color(0xFFF5F5F5)
    val surfaceColor = if (isDarkTheme) Color(0xFF2D2D2D) else Color.White
    val textColor = if (isDarkTheme) Color.White else Color(0xFF1E1E1E)
    val textSecondary = if (isDarkTheme) Color(0xFF9E9E9E) else Color(0xFF757575)
    val accentColor = Color(0xFFE57373)
    val successColor = Color(0xFF81C784)
    val inputBorderColor = if (isDarkTheme) Color.Gray else Color(0xFF9E9E9E)

    var name by remember { mutableStateOf(editingPreset?.name ?: "") }
    var workMinutes by remember { mutableIntStateOf(editingPreset?.workMinutes ?: 25) }
    var workSeconds by remember { mutableIntStateOf(editingPreset?.workSeconds ?: 0) }
    var breakMinutes by remember { mutableIntStateOf(editingPreset?.breakMinutes ?: 5) }
    var breakSeconds by remember { mutableIntStateOf(editingPreset?.breakSeconds ?: 0) }
    var loops by remember { mutableIntStateOf(editingPreset?.loops ?: 4) }
    var useAlarm by remember { mutableStateOf(editingPreset?.useAlarm ?: true) }
    var alarmDuration by remember { mutableIntStateOf(editingPreset?.alarmDuration ?: 30) }
    var autoStart by remember { mutableStateOf(editingPreset?.autoStart ?: false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (editingPreset != null) "编辑预设" else "新建预设", color = textColor) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = surfaceColor,
                    titleContentColor = textColor
                ),
                actions = {
                    TextButton(onClick = onCancel) {
                        Text("取消", color = textColor)
                    }
                    TextButton(
                        onClick = {
                            if (name.isBlank()) {
                                Toast.makeText(context, "请输入预设名称", Toast.LENGTH_SHORT).show()
                                return@TextButton
                            }
                            val preset = TimerPreset(
                                id = editingPreset?.id ?: UUID.randomUUID().toString(),
                                name = name.trim(),
                                workMinutes = workMinutes,
                                workSeconds = workSeconds,
                                breakMinutes = breakMinutes,
                                breakSeconds = breakSeconds,
                                loops = loops,
                                useAlarm = useAlarm,
                                alarmDuration = alarmDuration,
                                autoStart = autoStart
                            )
                            onSave(preset)
                        }
                    ) {
                        Text("保存", color = successColor)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(bgColor)
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 预设名称
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("预设名称", color = textColor) },
                placeholder = { Text("例如: 番茄工作法", color = textSecondary) },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = textColor,
                    unfocusedTextColor = textColor,
                    focusedBorderColor = accentColor,
                    unfocusedBorderColor = textSecondary,
                    focusedLabelColor = textColor,
                    unfocusedLabelColor = textColor
                )
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 预设卡片
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = surfaceColor)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("时间设置", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = textColor)

                    Spacer(modifier = Modifier.height(16.dp))

                    // 工作时长
                    PresetTimeInputRow(
                        label = "工作时长",
                        minutes = workMinutes,
                        seconds = workSeconds,
                        textColor = textColor,
                        accentColor = accentColor,
                        inputBorderColor = inputBorderColor,
                        onMinutesChange = { workMinutes = it },
                        onSecondsChange = { workSeconds = it }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // 休息时长
                    PresetTimeInputRowWithReset(
                        label = "休息时长",
                        minutes = breakMinutes,
                        seconds = breakSeconds,
                        textColor = textColor,
                        accentColor = accentColor,
                        inputBorderColor = inputBorderColor,
                        onMinutesChange = { breakMinutes = it },
                        onSecondsChange = { breakSeconds = it }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // 循环次数
                    PresetLoopInputRow(
                        loops = loops,
                        textColor = textColor,
                        successColor = successColor,
                        onLoopsChange = { loops = it }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    HorizontalDivider(color = textSecondary)

                    Spacer(modifier = Modifier.height(16.dp))

                    // 闹钟设置
                    PresetAlarmSettingsPanel(
                        useAlarm = useAlarm,
                        textColor = textColor,
                        textSecondary = textSecondary,
                        accentColor = accentColor,
                        inputBorderColor = inputBorderColor,
                        onUseAlarmChange = { useAlarm = it },
                        alarmDuration = alarmDuration,
                        onAlarmDurationChange = { alarmDuration = it }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    HorizontalDivider(color = textSecondary)

                    Spacer(modifier = Modifier.height(16.dp))

                    // 自动开始
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("自动开始", color = textColor, fontSize = 16.sp)
                        Switch(
                            checked = autoStart,
                            onCheckedChange = { autoStart = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = successColor,
                                checkedTrackColor = Color(0xFF4CAF50)
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PresetTimeInputRow(
    label: String,
    minutes: Int,
    seconds: Int,
    textColor: Color,
    accentColor: Color,
    inputBorderColor: Color,
    onMinutesChange: (Int) -> Unit,
    onSecondsChange: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = textColor, fontSize = 16.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            PresetNumberInput(
                value = minutes,
                textColor = textColor,
                accentColor = accentColor,
                unfocusedBorderColor = inputBorderColor,
                onValueChange = { if (it in 0..59) onMinutesChange(it) },
                range = 0..59
            )
            Text(" 分 ", color = textColor, fontSize = 14.sp)
            PresetNumberInput(
                value = seconds,
                textColor = textColor,
                accentColor = accentColor,
                unfocusedBorderColor = inputBorderColor,
                onValueChange = { if (it in 0..59) onSecondsChange(it) },
                range = 0..59
            )
            Text(" 秒", color = textColor, fontSize = 14.sp)
        }
    }
}

@Composable
fun PresetTimeInputRowWithReset(
    label: String,
    minutes: Int,
    seconds: Int,
    textColor: Color,
    accentColor: Color,
    inputBorderColor: Color,
    onMinutesChange: (Int) -> Unit,
    onSecondsChange: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = textColor, fontSize = 16.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "[归零]",
                color = Color(0xFFFFB74D),
                fontSize = 12.sp,
                modifier = Modifier.clickable {
                    onMinutesChange(0)
                    onSecondsChange(0)
                }
            )
            Spacer(modifier = Modifier.width(8.dp))
            PresetNumberInput(
                value = minutes,
                textColor = textColor,
                accentColor = accentColor,
                unfocusedBorderColor = inputBorderColor,
                onValueChange = { if (it in 0..59) onMinutesChange(it) },
                range = 0..59
            )
            Text(" 分 ", color = textColor, fontSize = 14.sp)
            PresetNumberInput(
                value = seconds,
                textColor = textColor,
                accentColor = accentColor,
                unfocusedBorderColor = inputBorderColor,
                onValueChange = { if (it in 0..59) onSecondsChange(it) },
                range = 0..59
            )
            Text(" 秒", color = textColor, fontSize = 14.sp)
        }
    }
}

@Composable
fun PresetNumberInput(
    value: Int,
    textColor: Color,
    accentColor: Color,
    unfocusedBorderColor: Color,
    onValueChange: (Int) -> Unit,
    range: IntRange
) {
    OutlinedTextField(
        value = if (value == 0) "" else value.toString(),
        onValueChange = { text ->
            val newValue = text.filter { it.isDigit() }.take(2).toIntOrNull() ?: 0
            if (newValue in range) {
                onValueChange(newValue)
            }
        },
        modifier = Modifier.width(60.dp),
        textStyle = LocalTextStyle.current.copy(fontSize = 14.sp, color = textColor),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = accentColor,
            unfocusedBorderColor = unfocusedBorderColor
        )
    )
}

@Composable
fun PresetLoopInputRow(
    loops: Int,
    textColor: Color,
    successColor: Color,
    onLoopsChange: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("循环次数", color = textColor, fontSize = 16.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { if (loops > 1) onLoopsChange(loops - 1) },
                enabled = loops > 1
            ) {
                Text("-", color = if (loops > 1) textColor else Color.Gray, fontSize = 24.sp)
            }
            Text(
                text = loops.toString(),
                color = textColor,
                fontSize = 18.sp,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            IconButton(onClick = { if (loops < 99) onLoopsChange(loops + 1) }) {
                Text("+", color = successColor, fontSize = 24.sp)
            }
        }
    }
}

@Composable
fun PresetAlarmSettingsPanel(
    useAlarm: Boolean,
    textColor: Color,
    textSecondary: Color,
    accentColor: Color,
    inputBorderColor: Color,
    onUseAlarmChange: (Boolean) -> Unit,
    alarmDuration: Int,
    onAlarmDurationChange: (Int) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("闹钟", color = textColor, fontSize = 16.sp)
            Switch(
                checked = useAlarm,
                onCheckedChange = { onUseAlarmChange(it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = accentColor,
                    checkedTrackColor = Color(0xFFFF5252)
                )
            )
        }

        if (useAlarm) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("闹钟时长", color = textColor, fontSize = 14.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PresetNumberInput(
                        value = alarmDuration,
                        textColor = textColor,
                        accentColor = accentColor,
                        unfocusedBorderColor = inputBorderColor,
                        onValueChange = { if (it in 1..300) onAlarmDurationChange(it) },
                        range = 1..300
                    )
                    Text(" 秒", color = textColor, fontSize = 14.sp)
                }
            }
        }
    }
}
