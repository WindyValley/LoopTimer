package com.looptimer

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class PresetListActivity : ComponentActivity() {

    private lateinit var presetsManager: PresetsManager

    private val editLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        // 刷新列表
        recreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        presetsManager = PresetsManager(this)
        val isDark = presetsManager.isDarkTheme()

        setContent {
            MaterialTheme {
                PresetListScreen(
                    isDarkTheme = isDark,
                    presets = presetsManager.getAllPresets(),
                    selectedId = presetsManager.getSelectedPresetId(),
                    onSelectPreset = { preset ->
                        presetsManager.setSelectedPresetId(preset.id)
                        presetsManager.saveCurrentTimerSettings(preset)
                        Toast.makeText(this, "已选择: ${preset.name}", Toast.LENGTH_SHORT).show()
                        finish()
                    },
                    onEditPreset = { preset ->
                        val intent = Intent(this, PresetEditActivity::class.java).apply {
                            putExtra(PresetEditActivity.EXTRA_PRESET_ID, preset.id)
                        }
                        editLauncher.launch(intent)
                    },
                    onDeletePreset = { preset ->
                        presetsManager.deletePreset(preset.id)
                        Toast.makeText(this, "已删除: ${preset.name}", Toast.LENGTH_SHORT).show()
                        recreate()
                    },
                    onCreatePreset = {
                        val intent = Intent(this, PresetEditActivity::class.java)
                        editLauncher.launch(intent)
                    },
                    onBack = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetListScreen(
    isDarkTheme: Boolean,
    presets: List<TimerPreset>,
    selectedId: String?,
    onSelectPreset: (TimerPreset) -> Unit,
    onEditPreset: (TimerPreset) -> Unit,
    onDeletePreset: (TimerPreset) -> Unit,
    onCreatePreset: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    val bgColor = if (isDarkTheme) Color(0xFF1E1E1E) else Color(0xFFF5F5F5)
    val surfaceColor = if (isDarkTheme) Color(0xFF2D2D2D) else Color.White
    val textColor = if (isDarkTheme) Color.White else Color(0xFF1E1E1E)
    val textSecondary = if (isDarkTheme) Color(0xFF9E9E9E) else Color(0xFF757575)
    val accentColor = Color(0xFFE57373)
    val successColor = Color(0xFF81C784)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("预设管理", color = textColor) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = surfaceColor,
                    titleContentColor = textColor
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("←", color = textColor, fontSize = 20.sp)
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onCreatePreset,
                containerColor = accentColor
            ) {
                Icon(Icons.Default.Add, contentDescription = "新建", tint = Color.White)
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(bgColor)
                .padding(padding)
        ) {
            if (presets.isEmpty()) {
                // 空状态
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "暂无预设",
                            fontSize = 18.sp,
                            color = textSecondary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "点击 + 创建一个预设",
                            fontSize = 14.sp,
                            color = textSecondary
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(presets) { preset ->
                        PresetCard(
                            preset = preset,
                            isSelected = preset.id == selectedId,
                            isDarkTheme = isDarkTheme,
                            onSelect = { onSelectPreset(preset) },
                            onEdit = { onEditPreset(preset) },
                            onDelete = { onDeletePreset(preset) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PresetCard(
    preset: TimerPreset,
    isSelected: Boolean,
    isDarkTheme: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val surfaceColor = if (isDarkTheme) Color(0xFF2D2D2D) else Color.White
    val textColor = if (isDarkTheme) Color.White else Color(0xFF1E1E1E)
    val textSecondary = if (isDarkTheme) Color(0xFF9E9E9E) else Color(0xFF757575)
    val accentColor = Color(0xFFE57373)
    val successColor = Color(0xFF81C784)

    val borderColor = if (isSelected) successColor else Color.Transparent
    val borderWidth = if (isSelected) 2.dp else 0.dp

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = surfaceColor),
        border = if (isSelected) {
            androidx.compose.foundation.BorderStroke(borderWidth, borderColor)
        } else null
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = preset.name,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor
                )
                if (isSelected) {
                    Text(
                        text = "✓ 已选中",
                        fontSize = 12.sp,
                        color = successColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "工作 ${preset.workMinutes}分${preset.workSeconds}秒 | 休息 ${preset.breakMinutes}分${preset.breakSeconds}秒",
                fontSize = 14.sp,
                color = accentColor
            )

            Text(
                text = "循环 ${preset.loops} 次 | ${if (preset.autoStart) "自动开始" else "手动开始"}",
                fontSize = 12.sp,
                color = textSecondary
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = onEdit) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = "编辑",
                        tint = successColor
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "删除",
                        tint = accentColor
                    )
                }
            }
        }
    }
}
