package com.looptimer

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import kotlinx.coroutines.*

// Shared state for notification click handling
// When notification is tapped, this is set to true; LoopTimerScreen observes it to call startTimer()
var pendingStartTimerFromNotification = false

class MainActivity : ComponentActivity() {
    
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _: Boolean -> }
    
    private lateinit var presetsManager: PresetsManager
    private var contextForService: Context? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        presetsManager = PresetsManager(this)
        contextForService = this
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        
        handleIntent(intent)
        
        setContent {
            LoopTimerTheme {
                LoopTimerScreen(
                    onStartFromNotification = {
                        startTimerService()
                    }
                )
            }
        }
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }
    
    private fun handleIntent(intent: Intent?) {
        if (intent?.action == "start_timer") {
            startTimerService()
            // Signal LoopTimerScreen to call startTimer() and stop alarm
            pendingStartTimerFromNotification = true
        }
    }
    
    private fun startTimerService() {
        val serviceIntent = Intent(this, TimerService::class.java).apply {
            action = "START"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }
}

class TimerService : Service() {
    
    private val CHANNEL_ID = "timer_service_channel"
    private val NOTIFICATION_ID = 1003
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START" -> {
                startForeground(NOTIFICATION_ID, createNotification())
            }
            "STOP" -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "计时器服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持计时器在后台运行"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): android.app.Notification {
        val stopIntent = Intent(this, TimerService::class.java).apply {
            action = "STOP"
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("循环计时器运行中")
            .setContentText("计时器正在后台运行")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPendingIntent)
            .build()
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
                try {
                    Thread.sleep(durationSeconds * 1000L)
                    if (!Thread.currentThread().isInterrupted) {
                        stopAlarm()
                    }
                } catch (e: InterruptedException) {
                    // Interrupted - alarm was stopped manually
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
fun LoopTimerScreen(
    onStartFromNotification: () -> Unit = {}
) {
    val context = LocalContext.current
    var contextForService by remember { mutableStateOf<Context?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    
    DisposableEffect(Unit) {
        contextForService = context
        onDispose { }
    }
    
    // 监听 onResume 事件，当从预设列表返回时重新加载设置
    var resumeTrigger by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                resumeTrigger++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    
    // 初始化 PresetsManager
    val presetsManager = remember { PresetsManager(context) }
    var lastSettingsTimestamp by remember { mutableLongStateOf(0L) }
    
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
    
    // 加载保存的计时器设置（每次 onResume 都检查更新）
    LaunchedEffect(resumeTrigger) {
        val timestamp = presetsManager.getSettingsTimestamp()
        if (timestamp > lastSettingsTimestamp) {
            val settings = presetsManager.getCurrentTimerSettings()
            settings?.let {
                workMinutes = it.workMinutes.toLong()
                workSeconds = it.workSeconds.toLong()
                breakMinutes = it.breakMinutes.toLong()
                breakSeconds = it.breakSeconds.toLong()
                loops = it.loops
                useAlarm = it.useAlarm
                alarmDuration = it.alarmDuration
                autoStart = it.autoStart
                currentLoop = 1
                isWorkPhase = true
                timeLeft = workMinutes * 60 + workSeconds
            }
            lastSettingsTimestamp = timestamp
        }
    }
    
    val scope = rememberCoroutineScope()
    var timerJob by remember { mutableStateOf<Job?>(null) }
    
    val CHANNEL_ID = "loop_timer_channel"
    val CHANNEL_ID_ALARM = "loop_timer_alarm_channel"
    val NOTIFICATION_ID = 1001
    
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
    
    fun showTimerEndNotification(title: String, message: String, waitingForStart: Boolean) {
        val channelId = if (useAlarm) CHANNEL_ID_ALARM else CHANNEL_ID
        val channelName = if (useAlarm) "闹钟提醒" else "通知提醒"
        createNotificationChannel(channelId, channelName)
        
        // When notification is tapped, go to MainActivity with action "start_timer"
        // This ensures handleIntent is called to start the timer
        val intent = Intent(context, MainActivity::class.java).apply {
            action = "start_timer"
            putExtra("waiting_for_start", waitingForStart)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val notificationText = if (waitingForStart) "点击开始计时" else message
        
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(notificationText)
            .setPriority(if (useAlarm) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
        
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                notificationManager.notify(NOTIFICATION_ID, builder.build())
            }
        } else {
            notificationManager.notify(NOTIFICATION_ID, builder.build())
        }
    }
    
    fun stopTimerService() {
        try {
            val stopIntent = Intent(context, TimerService::class.java).apply {
                action = "STOP"
            }
            context.startService(stopIntent)
        } catch (e: Exception) { }
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
                
                val wasLastWorkPhase = isWorkPhase
                val waitingForStart = !autoStart
                
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
                        if (useAlarm) {
                            AlarmController.startAlarm(context, alarmDuration)
                            alarmPlaying = true
                        }
                        showTimerEndNotification(title, message, waitingForStart)
                        stopTimerService()
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
                        if (useAlarm) {
                            AlarmController.startAlarm(context, alarmDuration)
                            alarmPlaying = true
                        }
                        showTimerEndNotification(title, message, waitingForStart)
                        stopTimerService()
                        return@launch
                    }
                }
                
                if (useAlarm) {
                    AlarmController.startAlarm(context, alarmDuration)
                    alarmPlaying = true
                }
                
                showTimerEndNotification(title, message, waitingForStart)
                
                if (autoStart && timeLeft > 0) {
                    startTimer()
                }
            }
        }
    }
    
    // Observe notification clicks and start timer when triggered
    // Also sync alarmPlaying with AlarmController.isPlaying()
    // Must be after startTimer() definition since it calls startTimer()
    LaunchedEffect(Unit) {
        while (true) {
            try {
                // Sync alarmPlaying - only trust our local state, not AlarmController
                // which may be in inconsistent state after app restart
                if (alarmPlaying && !AlarmController.isPlaying()) {
                    alarmPlaying = false
                }
                
                if (pendingStartTimerFromNotification) {
                    pendingStartTimerFromNotification = false
                    
                    // Stop alarm if playing - use Throwable to catch even non-Exception errors
                    if (alarmPlaying) {
                        try {
                            AlarmController.stopAlarm()
                        } catch (t: Throwable) {
                            // Ignore - alarm may have stopped already or MediaPlayer in bad state
                        }
                        alarmPlaying = false
                    }
                    
                    if (timeLeft <= 0) {
                        // timeLeft = 0 means a phase just ended. 
                        // isWorkPhase indicates the phase that just ended.
                        // We need to start the NEXT phase.
                        // If all loops done (currentLoop >= loops), next is work phase 1.
                        // Otherwise, flip the phase.
                        if (isWorkPhase && currentLoop >= loops) {
                            // Work ended, all loops done -> restart work
                            timeLeft = workMinutes * 60 + workSeconds
                            isWorkPhase = true
                            currentLoop = 1
                        } else if (!isWorkPhase && currentLoop >= loops) {
                            // Break ended, all loops done -> restart work
                            timeLeft = workMinutes * 60 + workSeconds
                            isWorkPhase = true
                            currentLoop = 1
                        } else if (isWorkPhase) {
                            // Work ended, more loops -> start break
                            timeLeft = if (breakMinutes > 0 || breakSeconds > 0) {
                                breakMinutes * 60 + breakSeconds
                            } else {
                                workMinutes * 60 + workSeconds
                            }
                            isWorkPhase = false
                            // currentLoop stays the same
                        } else {
                            // Break ended, more loops -> start work
                            timeLeft = workMinutes * 60 + workSeconds
                            isWorkPhase = true
                            currentLoop++
                        }
                    }
                    startTimer()
                }
            } catch (t: Throwable) {
                // Ignore errors in background loop
            }
            delay(100)
        }
    }
    
    fun pauseTimer() {
        isRunning = false
        timerJob?.cancel()
        stopTimerService()
    }
    
    fun resetTimer() {
        isRunning = false
        timerJob?.cancel()
        AlarmController.stopAlarm()
        alarmPlaying = false
        currentLoop = 1
        isWorkPhase = true
        timeLeft = workMinutes * 60 + workSeconds
        stopTimerService()
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
        
        val waitingForStart = !autoStart
        
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
        
        if (useAlarm) {
            AlarmController.startAlarm(context, alarmDuration)
            alarmPlaying = true
        }
        
        showTimerEndNotification(title, message, waitingForStart)
        
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
                onClick = { 
                    if (isRunning) {
                        pauseTimer()
                        stopTimerService()
                    } else {
                        startTimer()
                        if (contextForService != null) {
                            val serviceIntent = Intent(contextForService, TimerService::class.java).apply {
                                action = "START"
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                contextForService?.startForegroundService(serviceIntent)
                            } else {
                                contextForService?.startService(serviceIntent)
                            }
                        }
                    }
                },
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
        
        // 预设管理按钮
        OutlinedButton(
            onClick = {
                val intent = Intent(context, PresetListActivity::class.java)
                context.startActivity(intent)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("📁 预设管理", fontSize = 16.sp)
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 300.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF2D2D2D))
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
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
                
                AlarmSettingsPanel(
                    useAlarm = useAlarm,
                    onUseAlarmChange = { useAlarm = it },
                    alarmDuration = alarmDuration,
                    onAlarmDurationChange = { alarmDuration = it }
                )
                
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
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = "版本 ${VERSION.NAME}",
                    fontSize = 12.sp,
                    color = Color.Gray,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun AlarmSettingsPanel(
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
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("最大持续时间", color = Color.White, fontSize = 16.sp)
                AlarmDurationInput(
                    value = alarmDuration,
                    onValueChange = { onAlarmDurationChange(it) }
                )
            }
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
