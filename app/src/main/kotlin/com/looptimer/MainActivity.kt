package com.looptimer

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

class MainActivity : ComponentActivity() {
    
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _: Boolean -> }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        
        setContent {
            LoopTimerTheme {
                LoopTimerScreen()
            }
        }
    }
}

object AlarmController {
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var isAlarmPlaying = false
    private var stopThread: Thread? = null
    
    fun startAlarm(context: Context, durationSeconds: Int) {
        if (isAlarmPlaying) return
        isAlarmPlaying = true
        
        playSound(context)
        vibrate(context)
        
        if (durationSeconds > 0) {
            stopThread?.interrupt()
            stopThread = Thread {
                Thread.sleep(durationSeconds * 1000L)
                if (!Thread.currentThread().isInterrupted) {
                    stopAlarm()
                }
            }.apply { start() }
        }
    }
    
    fun stopAlarm() {
        isAlarmPlaying = false
        
        try {
            stopThread?.interrupt()
            stopThread = null
        } catch (e: Exception) { }
        
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Exception) { }
        
        try {
            vibrator?.cancel()
            vibrator = null
        } catch (e: Exception) { }
    }
    
    fun isPlaying(): Boolean = isAlarmPlaying
    
    private fun playSound(context: Context) {
        try {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(context, alarmUri)
                prepare()
                isLooping = true
                start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun vibrate(context: Context) {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        
        val pattern = longArrayOf(0, 1000, 500, 1000, 500, 1000, 500, 1000, 500, 1000)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(pattern, 0)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoopTimerScreen() {
    val context = LocalContext.current
    
    var workMinutes by remember { mutableStateOf(25L) }
    var workSeconds by remember { mutableStateOf(0L) }
    var breakMinutes by remember { mutableStateOf(5L) }
    var breakSeconds by remember { mutableStateOf(0L) }
    var loops by remember { mutableIntStateOf(4) }
    var currentLoop by remember { mutableIntStateOf(1) }
    var timeLeft by remember { mutableLongStateOf(workMinutes * 60 + workSeconds) }
    var isRunning by remember { mutableStateOf(false) }
    var isWorkPhase by remember { mutableStateOf(true) }
    var useAlarm by remember { mutableStateOf(false) }
    var autoStart by remember { mutableStateOf(true) }
    var alarmDuration by remember { mutableIntStateOf(10) }
    var alarmPlaying by remember { mutableStateOf(false) }
    
    val scope = rememberCoroutineScope()
    var timerJob by remember { mutableStateOf<Job?>(null) }
    
    val CHANNEL_ID = "loop_timer_channel"
    val CHANNEL_ID_ALARM = "loop_timer_alarm_channel"
    val NOTIFICATION_ID = 1001
    val ALARM_NOTIFICATION_ID = 1002
    
    fun createNotificationChannel(channelId: String, name: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(channelId, name, importance).apply {
                description = "计时器到期提醒"
                enableVibration(true)
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    fun showNotification(title: String, message: String) {
        createNotificationChannel(CHANNEL_ID, "通知提醒")
        
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val stopIntent = Intent(context, AlarmReceiver::class.java).apply {
            action = "STOP_ALARM"
        }
        val stopPendingIntent = PendingIntent.getBroadcast(
            context, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPendingIntent)
        
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                notificationManager.notify(NOTIFICATION_ID, builder.build())
            }
        } else {
            notificationManager.notify(NOTIFICATION_ID, builder.build())
        }
    }
    
    fun showAlarmNotification(title: String, message: String) {
        createNotificationChannel(CHANNEL_ID_ALARM, "闹钟提醒")
        
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val stopIntent = Intent(context, AlarmReceiver::class.java).apply {
            action = "STOP_ALARM"
        }
        val stopPendingIntent = PendingIntent.getBroadcast(
            context, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val durationText = if (alarmDuration < 0) "一直响" else "${alarmDuration}秒"
        
        val builder = NotificationCompat.Builder(context, CHANNEL_ID_ALARM)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("$title ($durationText)")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止闹钟", stopPendingIntent)
        
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                notificationManager.notify(ALARM_NOTIFICATION_ID, builder.build())
            }
        } else {
            notificationManager.notify(ALARM_NOTIFICATION_ID, builder.build())
        }
    }
    
    fun startTimer() {
        if (timeLeft <= 0) return
        
        isRunning = true
        timerJob?.cancel()
        timerJob = scope.launch {
            while (timeLeft > 0 && isRunning) {
                delay(1000)
                if (isRunning) {
                    timeLeft = timeLeft - 1
                }
            }
            if (isRunning) {
                isRunning = false
                
                val title = if (isWorkPhase) "工作结束！" else "休息结束！"
                val message = if (isWorkPhase) "该休息一下了" else "继续工作吧"
                
                showNotification(title, message)
                
                if (useAlarm) {
                    AlarmController.startAlarm(context, alarmDuration)
                    alarmPlaying = true
                    showAlarmNotification(title, message)
                }
                
                val wasLastWorkPhase = isWorkPhase
                
                if (wasLastWorkPhase) {
                    if (currentLoop < loops) {
                        isWorkPhase = false
                        timeLeft = if (breakMinutes > 0 || breakSeconds > 0) {
                            breakMinutes * 60 + breakSeconds
                        } else {
                            0L
                        }
                    } else {
                        currentLoop = 1
                        isWorkPhase = true
                        timeLeft = 0
                        return@launch
                    }
                } else {
                    if (currentLoop < loops) {
                        currentLoop++
                        isWorkPhase = true
                        timeLeft = workMinutes * 60 + workSeconds
                    } else {
                        currentLoop = 1
                        isWorkPhase = true
                        timeLeft = 0
                        return@launch
                    }
                }
                
                if (autoStart && timeLeft > 0) {
                    startTimer()
                }
            }
        }
    }
    
    fun pauseTimer() {
        isRunning = false
        timerJob?.cancel()
    }
    
    fun resetTimer() {
        isRunning = false
        timerJob?.cancel()
        AlarmController.stopAlarm()
        alarmPlaying = false
        currentLoop = 1
        isWorkPhase = true
        timeLeft = workMinutes * 60 + workSeconds
    }
    
    fun stopAlarm() {
        AlarmController.stopAlarm()
        alarmPlaying = false
    }
    
    fun skipPhase() {
        isRunning = false
        timerJob?.cancel()
        
        val title = if (isWorkPhase) "工作结束！" else "休息结束！"
        val message = if (isWorkPhase) "该休息一下了" else "继续工作吧"
        
        showNotification(title, message)
        
        if (useAlarm) {
            AlarmController.startAlarm(context, alarmDuration)
            alarmPlaying = true
            showAlarmNotification(title, message)
        }
        
        if (isWorkPhase) {
            if (currentLoop < loops) {
                isWorkPhase = false
                timeLeft = if (breakMinutes > 0 || breakSeconds > 0) {
                    breakMinutes * 60 + breakSeconds
                } else {
                    0L
                }
            } else {
                currentLoop = 1
                isWorkPhase = true
                timeLeft = workMinutes * 60 + workSeconds
            }
        } else {
            currentLoop++
            isWorkPhase = true
            timeLeft = workMinutes * 60 + workSeconds
        }
        
        if (autoStart && timeLeft > 0) {
            startTimer()
        }
    }
    
    val minutes = timeLeft / 60
    val seconds = timeLeft % 60
    val phaseColor = if (isWorkPhase) Color(0xFFE57373) else Color(0xFF81C784)
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1E1E1E))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "循环计时器",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "第 $currentLoop / $loops 轮 | ${if (isWorkPhase) "工作" else "休息"}",
            fontSize = 16.sp,
            color = phaseColor
        )
        
        Spacer(modifier = Modifier.height(48.dp))
        
        Box(
            modifier = Modifier
                .size(260.dp)
                .background(phaseColor.copy(alpha = 0.2f), RoundedCornerShape(130.dp)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "%02d:%02d".format(minutes, seconds),
                    fontSize = 64.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                if (alarmPlaying) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "🔔 闹钟响起",
                        fontSize = 14.sp,
                        color = Color(0xFFFFB74D)
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        if (alarmPlaying) {
            Button(
                onClick = { stopAlarm() },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE57373)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("🔇 停止闹钟", fontSize = 18.sp)
            }
            
            Spacer(modifier = Modifier.height(16.dp))
        }
        
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Button(
                onClick = { if (isRunning) pauseTimer() else startTimer() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isWorkPhase) Color(0xFFE57373) else Color(0xFF81C784)
                ),
                enabled = timeLeft > 0
            ) {
                Text(if (isRunning) "暂停" else "开始", fontSize = 18.sp)
            }
            
            Button(
                onClick = { resetTimer() },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF757575))
            ) {
                Text("重置", fontSize = 18.sp)
            }
            
            Button(
                onClick = { skipPhase() },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF424242))
            ) {
                Text("跳过", fontSize = 18.sp)
            }
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF2D2D2D))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("设置", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                
                Spacer(modifier = Modifier.height(16.dp))
                
                TimeInputRow(
                    label = "工作时长",
                    minutes = workMinutes,
                    seconds = workSeconds,
                    onMinutesChange = { workMinutes = it },
                    onSecondsChange = { workSeconds = it }
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                TimeInputRowWithReset(
                    label = "休息时长",
                    minutes = breakMinutes,
                    seconds = breakSeconds,
                    onMinutesChange = { breakMinutes = it },
                    onSecondsChange = { breakSeconds = it }
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                LoopInputRow(
                    loops = loops,
                    onLoopsChange = { loops = it }
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("提醒方式", color = Color.White, fontSize = 16.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "通知",
                            color = if (!useAlarm) Color(0xFF81C784) else Color.Gray,
                            fontSize = 14.sp,
                            modifier = Modifier.clickable { useAlarm = false }
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = "闹钟",
                            color = if (useAlarm) Color(0xFFE57373) else Color.Gray,
                            fontSize = 14.sp,
                            modifier = Modifier.clickable { useAlarm = true }
                        )
                    }
                }
                
                if (useAlarm) {
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("闹钟时长", color = Color.White, fontSize = 16.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AlarmDurationInput(
                                value = alarmDuration,
                                onValueChange = { alarmDuration = it }
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
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

@Composable
fun TimeInputRow(
    label: String,
    minutes: Long,
    seconds: Long,
    onMinutesChange: (Long) -> Unit,
    onSecondsChange: (Long) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.White, fontSize = 16.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            NumberInputField(
                value = minutes.toInt(),
                onValueChange = { onMinutesChange(it.toLong()) },
                range = 0..999
            )
            Text(" 分 ", color = Color.White, fontSize = 14.sp)
            NumberInputField(
                value = seconds.toInt(),
                onValueChange = { onSecondsChange(it.toLong()) },
                range = 0..59
            )
            Text(" 秒", color = Color.White, fontSize = 14.sp)
        }
    }
}

@Composable
fun TimeInputRowWithReset(
    label: String,
    minutes: Long,
    seconds: Long,
    onMinutesChange: (Long) -> Unit,
    onSecondsChange: (Long) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.White, fontSize = 16.sp)
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
            NumberInputField(
                value = minutes.toInt(),
                onValueChange = { onMinutesChange(it.toLong()) },
                range = 0..999
            )
            Text(" 分 ", color = Color.White, fontSize = 14.sp)
            NumberInputField(
                value = seconds.toInt(),
                onValueChange = { onSecondsChange(it.toLong()) },
                range = 0..59
            )
            Text(" 秒", color = Color.White, fontSize = 14.sp)
        }
    }
}

@Composable
fun AlarmDurationInput(
    value: Int,
    onValueChange: (Int) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "[",
            color = Color.White,
            fontSize = 14.sp
        )
        NumberInputField(
            value = if (value < 0) 0 else value,
            onValueChange = { 
                onValueChange(it) 
            },
            range = 0..300
        )
        Text(
            text = "] 秒 | 一直响",
            color = Color.White,
            fontSize = 14.sp,
            modifier = Modifier.clickable { onValueChange(-1) }
        )
    }
}

@Composable
fun NumberInputField(
    value: Int,
    onValueChange: (Int) -> Unit,
    range: IntRange
) {
    var textValue by remember(value) { mutableStateOf(value.toString()) }
    var isFocused by remember { mutableStateOf(false) }
    
    LaunchedEffect(value) {
        if (!isFocused) {
            textValue = value.toString()
        }
    }
    
    BasicTextField(
        value = textValue,
        onValueChange = { newValue ->
            if (newValue.isEmpty() || newValue.all { it.isDigit() }) {
                textValue = newValue
                newValue.toIntOrNull()?.let { parsed ->
                    if (parsed in range) {
                        onValueChange(parsed)
                    }
                }
            }
        },
        textStyle = LocalTextStyle.current.copy(
            color = Color.White,
            fontSize = 18.sp,
            textAlign = TextAlign.Center
        ),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(
            onDone = { }
        ),
        singleLine = true,
        cursorBrush = SolidColor(Color.White),
        modifier = Modifier
            .width(64.dp)
            .background(Color(0xFF3D3D3D), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .onFocusChanged { focusState ->
                isFocused = focusState.isFocused
                if (!focusState.isFocused) {
                    textValue = value.toString()
                }
            }
    )
}

@Composable
fun LoopInputRow(
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
            TextButton(
                onClick = { if (loops > 1) onLoopsChange(loops - 1) },
                modifier = Modifier.size(36.dp)
            ) {
                Text("-", fontSize = 20.sp, color = Color.White)
            }
            BasicTextField(
                value = loops.toString(),
                onValueChange = { newValue ->
                    if (newValue.isEmpty() || newValue.all { it.isDigit() }) {
                        newValue.toIntOrNull()?.let { parsed ->
                            if (parsed in 1..99) {
                                onLoopsChange(parsed)
                            }
                        }
                    }
                },
                textStyle = LocalTextStyle.current.copy(
                    color = Color.White,
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center
                ),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                singleLine = true,
                cursorBrush = SolidColor(Color.White),
                modifier = Modifier
                    .width(56.dp)
                    .background(Color(0xFF3D3D3D), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
            TextButton(
                onClick = { if (loops < 99) onLoopsChange(loops + 1) },
                modifier = Modifier.size(36.dp)
            ) {
                Text("+", fontSize = 20.sp, color = Color.White)
            }
        }
    }
}

@Composable
fun LoopTimerTheme(content: @Composable () -> Unit) {
    MaterialTheme {
        content()
    }
}
