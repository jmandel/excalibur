package dev.exe.kindleconverter.convert

import dev.exe.kindleconverter.data.Stage

/** A coarse, honest progress point derived from a calibre log line. */
data class StageHint(val stage: Stage, val label: String, val fraction: Float)

// calibre emits log lines, not percentages. We map the lines we know to a 5-stage rail
// with a coarse fraction. Order matters: later matches represent later progress; the
// manager only ever moves forward, so noisy ordering can't make the bar jump backward.
private val MARKERS = listOf(
    "imported browser_convert" to StageHint(Stage.IMPORT, "Starting up", 0.06f),
    "Input running" to StageHint(Stage.PARSE, "Reading the book", 0.18f),
    "Parsing all content" to StageHint(Stage.PARSE, "Reading the book", 0.24f),
    "Merging user specified metadata" to StageHint(Stage.PARSE, "Reading the book", 0.32f),
    "Detecting structure" to StageHint(Stage.LAYOUT, "Laying out pages", 0.46f),
    "Detected chapters" to StageHint(Stage.LAYOUT, "Laying out pages", 0.52f),
    "Flattening CSS" to StageHint(Stage.LAYOUT, "Laying out pages", 0.58f),
    "Rasterizing" to StageHint(Stage.LAYOUT, "Laying out pages", 0.62f),
    "inline TOC" to StageHint(Stage.LAYOUT, "Laying out pages", 0.66f),
    "Creating" to StageHint(Stage.WRITE, "Building the Kindle file", 0.80f),
    "Serializing" to StageHint(Stage.WRITE, "Building the Kindle file", 0.86f),
    "Compressing" to StageHint(Stage.WRITE, "Building the Kindle file", 0.90f),
    "converted" to StageHint(Stage.DONE, "Finishing", 0.98f),
    "Output saved" to StageHint(Stage.DONE, "Finishing", 0.99f),
)

fun matchStage(line: String): StageHint? =
    MARKERS.firstOrNull { line.contains(it.first, ignoreCase = true) }?.second

/** A representative fill for a stage, used when no live fraction is available (e.g. the list). */
fun stageBaseFraction(stage: Stage) = when (stage) {
    Stage.IMPORT -> 0.08f
    Stage.PARSE -> 0.30f
    Stage.LAYOUT -> 0.58f
    Stage.WRITE -> 0.85f
    Stage.DONE -> 1f
}
