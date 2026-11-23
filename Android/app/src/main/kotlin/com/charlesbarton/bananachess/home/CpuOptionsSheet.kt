package com.charlesbarton.bananachess.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.charlesbarton.bananachess.art.Palette
import com.charlesbarton.bananachess.art.PaperButton
import com.charlesbarton.bananachess.engine.cpu.CpuDifficulty

/** The choices that only matter against the CPU, asked for at the moment they matter. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CpuOptionsSheet(
    difficulty: CpuDifficulty,
    humanPlaysFirst: Boolean,
    onDifficultyChange: (CpuDifficulty) -> Unit,
    onHumanPlaysFirstChange: (Boolean) -> Unit,
    onStart: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Palette.warmPaper,
        contentColor = Palette.ink,
        scrimColor = Palette.ink.copy(alpha = 0.28f),
    ) {
        CpuOptions(
            difficulty = difficulty,
            humanPlaysFirst = humanPlaysFirst,
            onDifficultyChange = onDifficultyChange,
            onHumanPlaysFirstChange = onHumanPlaysFirstChange,
            onStart = onStart,
            modifier = Modifier.verticalScroll(rememberScrollState()),
        )
    }
}

@Composable
internal fun CpuOptions(
    difficulty: CpuDifficulty,
    humanPlaysFirst: Boolean,
    onDifficultyChange: (CpuDifficulty) -> Unit,
    onHumanPlaysFirstChange: (Boolean) -> Unit,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = "Play Computer", style = HomeType.sheetTitle, modifier = Modifier.semantics { heading() })

        OptionRow(
            title = "Difficulty",
            options = CpuDifficulty.entries,
            selected = difficulty,
            name = CpuDifficulty::displayName,
            blurb = CpuDifficulty::blurb,
            onSelect = onDifficultyChange,
            titleStyle = HomeType.subtitle,
            blurbStyle = HomeType.sheetBlurb,
        )

        // The whole row is the switch, so the label is part of what TalkBack reads and taps.
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .toggleable(value = humanPlaysFirst, role = Role.Switch, onValueChange = onHumanPlaysFirstChange)
                    .minimumInteractiveComponentSize(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "You move first", style = HomeType.body, modifier = Modifier.weight(1f))
            Switch(
                checked = humanPlaysFirst,
                onCheckedChange = null,
                colors =
                    SwitchDefaults.colors(
                        checkedThumbColor = Palette.cream,
                        checkedTrackColor = Palette.amber,
                        checkedBorderColor = Palette.amber,
                        uncheckedThumbColor = Palette.inkSoft,
                        uncheckedTrackColor = Palette.cream,
                        uncheckedBorderColor = Palette.inkSoft,
                    ),
            )
        }

        PaperButton(text = "Start match", onClick = onStart, modifier = Modifier.fillMaxWidth(), isPrimary = true)
    }
}
