package com.looptimer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.looptimer.R
import com.looptimer.domain.TimeRanges

/**
 * A bounded integer field.
 *
 * One implementation shared by every screen. There used to be two divergent versions
 * (`NumberInputField` and `PresetNumberInput`) that disagreed about ranges, about whether an
 * out-of-range entry was clamped or silently dropped, and about how an empty field behaved.
 */
@Composable
fun NumberInputField(
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    width: androidx.compose.ui.unit.Dp = 64.dp
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    var focused by remember { mutableStateOf(false) }

    BasicTextField(
        value = text,
        onValueChange = { raw ->
            val digits = raw.filter(Char::isDigit).take(4)
            text = digits
            // Commit only in-range values while typing; the field is reconciled on focus loss,
            // so a partially typed number cannot leave the model stale.
            digits.toIntOrNull()?.let { if (it in range) onValueChange(it) }
        },
        textStyle = LocalTextStyle.current.copy(
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 18.sp,
            textAlign = TextAlign.Center
        ),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done
        ),
        singleLine = true,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
        modifier = modifier
            .width(width)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .onFocusChanged { focusState ->
                focused = focusState.isFocused
                if (!focusState.isFocused) {
                    // Snap back to the committed value, clamping anything invalid.
                    val committed = text.toIntOrNull()?.coerceIn(range) ?: value
                    onValueChange(committed)
                    text = committed.toString()
                }
            }
    )
}

/** A "minutes + seconds" row, optionally with a reset-to-zero shortcut. */
@Composable
fun TimeInputRow(
    label: String,
    minutes: Int,
    seconds: Int,
    onMinutesChange: (Int) -> Unit,
    onSecondsChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    showZeroShortcut: Boolean = false
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showZeroShortcut) {
                TextButton(onClick = {
                    onMinutesChange(0)
                    onSecondsChange(0)
                }) {
                    Text(
                        stringResource(R.string.action_zero),
                        color = MaterialTheme.colorScheme.tertiary,
                        fontSize = 12.sp
                    )
                }
                Spacer(Modifier.width(4.dp))
            }
            NumberInputField(
                value = minutes,
                range = TimeRanges.MINUTES,
                onValueChange = onMinutesChange
            )
            Text(
                stringResource(R.string.unit_minute),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp
            )
            NumberInputField(
                value = seconds,
                range = TimeRanges.SECONDS,
                onValueChange = onSecondsChange
            )
            Text(
                stringResource(R.string.unit_second),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp
            )
        }
    }
}

/** Loop-count stepper with a typable field. */
@Composable
fun LoopInputRow(
    loops: Int,
    onLoopsChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            stringResource(R.string.label_loops),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 16.sp
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = { onLoopsChange((loops - 1).coerceIn(TimeRanges.LOOPS)) },
                enabled = loops > TimeRanges.LOOPS.first,
                modifier = Modifier.size(40.dp)
            ) {
                Text("−", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface)
            }
            NumberInputField(
                value = loops,
                range = TimeRanges.LOOPS,
                onValueChange = onLoopsChange,
                width = 56.dp
            )
            TextButton(
                onClick = { onLoopsChange((loops + 1).coerceIn(TimeRanges.LOOPS)) },
                enabled = loops < TimeRanges.LOOPS.last,
                modifier = Modifier.size(40.dp)
            ) {
                Text("+", fontSize = 20.sp, color = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}

