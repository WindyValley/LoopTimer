package com.looptimer

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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
        
        setContent {
            MaterialTheme {
                PresetEditScreen(
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
    editingPreset: TimerPreset?,
    onSave: (TimerPreset) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    
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
                title = { Text(if (editingPreset != null) "编辑预设" else "新建预设") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF2D2D2D),
                    titleContentColor = Color.White
                ),
                actions = {
                    TextButton(onClick = onCancel) {
                        Text("取消", color = Color.White)
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
                        Text("保存", color = Color(0xFF81C784))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF1E1E1E))
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 预设名称
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("预设名称", color = Color.White) },
                placeholder = { Text("例如: 番茄工作法", color = Color.Gray) },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFFE57373),
                    unfocusedBorderColor = Color.Gray,
                    focusedLabelColor = Color.White,
                    unfocusedLabelColor = Color.White
                )
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // 预设卡片
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2D2D2D))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("时间设置", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // 工作时长
                    PresetTimeInputRow(
                        label = "工作时长",
                        minutes = workMinutes,
                        seconds = workSeconds,
                        onMinutesChange = { workMinutes = it },
                        onSecondsChange = { workSeconds = it }
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // 休息时长
                    PresetTimeInputRowWithReset(
                        label = "休息时长",
                        minutes = breakMinutes,
                        seconds = breakSeconds,
                        onMinutesChange = { breakMinutes = it },
                        onSecondsChange = { breakSeconds = it }
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // 循环次数
                    PresetLoopInputRow(
                        loops = loops,
                        onLoopsChange = { loops = it }
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Divider(color = Color.Gray)
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // 闹钟设置
                    PresetAlarmSettingsPanel(
                        useAlarm = useAlarm,
                        onUseAlarmChange = { useAlarm = it },
                        alarmDuration = alarmDuration,
                        onAlarmDurationChange = { alarmDuration = it }
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Divider(color = Color.Gray)
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // 自动开始
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("自动开始", color = Color.White, fontSize = 16.sp)
                        Switch(
                            checked = autoStart,
                            onCheckedChange = { autoStart = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF81C784),
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
    onMinutesChange: (Int) -> Unit,
    onSecondsChange: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.White, fontSize = 16.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            PresetNumberInput(
                value = minutes,
                onValueChange = { if (it in 0..59) onMinutesChange(it) },
                range = 0..59
            )
            Text(" 分 ", color = Color.White, fontSize = 14.sp)
            PresetNumberInput(
                value = seconds,
                onValueChange = { if (it in 0..59) onSecondsChange(it) },
                range = 0..59
            )
            Text(" 秒", color = Color.White, fontSize = 14.sp)
        }
    }
}

@Composable
fun PresetTimeInputRowWithReset(
    label: String,
    minutes: Int,
    seconds: Int,
    onMinutesChange: (Int) -> Unit,
    onSecondsChange: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.White, fontSize = 16.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            PresetNumberInput(
                value = minutes,
                onValueChange = { if (it in 0..59) onMinutesChange(it) },
                range = 0..59
            )
            Text(" 分 ", color = Color.White, fontSize = 14.sp)
            PresetNumberInput(
                value = seconds,
                onValueChange = { if (it in 0..59) onSecondsChange(it) },
                range = 0..59
            )
            Text(" 秒", color = Color.White, fontSize = 14.sp)
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(onClick = {
                onMinutesChange(0)
                onSecondsChange(0)
            }) {
                Text("归零", color = Color(0xFFE57373), fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun PresetNumberInput(
    value: Int,
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
        textStyle = LocalTextStyle.current.copy(fontSize = 14.sp, color = Color.White),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color(0xFFE57373),
            unfocusedBorderColor = Color.Gray
        )
    )
}

@Composable
fun PresetLoopInputRow(
    loops: Int,
    onLoopsChange: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("循环次数", color = Color.White, fontSize = 16.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { if (loops > 1) onLoopsChange(loops - 1) },
                enabled = loops > 1
            ) {
                Text("-", color = if (loops > 1) Color.White else Color.Gray, fontSize = 24.sp)
            }
            Text(
                text = loops.toString(),
                color = Color.White,
                fontSize = 18.sp,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            IconButton(onClick = { if (loops < 99) onLoopsChange(loops + 1) }) {
                Text("+", color = Color(0xFF81C784), fontSize = 24.sp)
            }
        }
    }
}

@Composable
fun PresetAlarmSettingsPanel(
    useAlarm: Boolean,
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
            Text("闹钟", color = Color.White, fontSize = 16.sp)
            Switch(
                checked = useAlarm,
                onCheckedChange = { onUseAlarmChange(it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color(0xFFE57373),
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
                Text("闹钟时长", color = Color.White, fontSize = 14.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PresetNumberInput(
                        value = alarmDuration,
                        onValueChange = { if (it in 1..300) onAlarmDurationChange(it) },
                        range = 1..300
                    )
                    Text(" 秒", color = Color.White, fontSize = 14.sp)
                }
            }
        }
    }
}
