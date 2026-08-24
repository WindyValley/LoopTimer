package com.looptimer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.looptimer.data.PresetsRepository
import com.looptimer.ui.presets.PresetEditScreen
import com.looptimer.ui.theme.LoopTimerTheme

/** Create/edit host for a single preset. */
class PresetEditActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = PresetsRepository.getInstance(this)
        val editing = intent.getStringExtra(EXTRA_PRESET_ID)?.let(repository::getPreset)

        setContent {
            LoopTimerTheme(darkTheme = repository.isDarkTheme()) {
                PresetEditScreen(
                    editing = editing,
                    onSave = { name, config ->
                        if (editing != null) {
                            repository.updatePreset(editing.copy(name = name, config = config))
                        } else {
                            repository.addPreset(name, config)
                        }
                        finish()
                    },
                    onCancel = { finish() }
                )
            }
        }
    }

    companion object {
        const val EXTRA_PRESET_ID = "preset_id"
    }
}
