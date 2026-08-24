package com.looptimer

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.looptimer.data.PresetsRepository
import com.looptimer.ui.presets.PresetListScreen
import com.looptimer.ui.theme.LoopTimerTheme

/**
 * Preset list host.
 *
 * The list is driven by the repository's Flow, so mutations refresh it directly. The previous
 * implementation called `recreate()` after every add/edit/delete, rebuilding the whole activity
 * to update one row.
 */
class PresetListActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = PresetsRepository.getInstance(this)

        setContent {
            val presets by repository.presets.collectAsState()
            val selectedId by repository.selectedPresetId.collectAsState()

            LoopTimerTheme(darkTheme = repository.isDarkTheme()) {
                PresetListScreen(
                    presets = presets,
                    selectedId = selectedId,
                    onSelect = { preset ->
                        repository.selectPreset(preset)
                        finish()
                    },
                    onEdit = { preset ->
                        startActivity(
                            Intent(this, PresetEditActivity::class.java)
                                .putExtra(PresetEditActivity.EXTRA_PRESET_ID, preset.id)
                        )
                    },
                    onDelete = { preset -> repository.deletePreset(preset.id) },
                    onCreate = {
                        startActivity(Intent(this, PresetEditActivity::class.java))
                    },
                    onBack = { finish() }
                )
            }
        }
    }
}
