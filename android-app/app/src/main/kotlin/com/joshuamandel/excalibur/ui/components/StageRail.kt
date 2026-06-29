package com.joshuamandel.excalibur.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.joshuamandel.excalibur.data.Stage

private val STAGES = listOf(
    Stage.IMPORT to "Import",
    Stage.PARSE to "Parse",
    Stage.LAYOUT to "Layout",
    Stage.WRITE to "Write",
    Stage.DONE to "Ready",
)

/**
 * The "binding press": one continuous track that fills as conversion advances, with the
 * five stages marked as milestone dots sitting on it. A single indicator — the dots show
 * which phase you're in, the fill shows progress through it.
 */
@Composable
fun StageRail(stage: Stage, fraction: Float, modifier: Modifier = Modifier, showLabels: Boolean = true) {
    val current = STAGES.indexOfFirst { it.first == stage }.coerceAtLeast(0)
    val frac by animateFloatAsState(fraction.coerceIn(0f, 1f), label = "rail")
    val cs = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(16.dp), contentAlignment = Alignment.CenterStart) {
            Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(cs.outlineVariant))
            Box(Modifier.fillMaxWidth(frac).height(3.dp).clip(CircleShape).background(cs.primary))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                STAGES.forEachIndexed { i, _ ->
                    val done = i <= current
                    Box(
                        Modifier.size(if (done) 14.dp else 11.dp).clip(CircleShape)
                            .background(if (done) cs.primary else cs.outlineVariant)
                    )
                }
            }
        }
        if (showLabels) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                STAGES.forEachIndexed { i, (_, label) ->
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (i <= current) cs.onSurface else cs.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
