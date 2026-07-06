package com.inkwell.diary.page

import com.inkwell.diary.data.Prefs

fun Prefs.dissolveConfig(): DissolveConfig {
    return DissolveConfig(
        cellSizePx = dissolveCellSizePx,
        sweepMs = dissolveSweepMs,
        cellLifeMs = dissolveCellLifeMs,
        windShearPx = dissolveWindShearPx.toFloat(),
        driftMaxPx = dissolveDriftMaxPx.toFloat(),
    )
}

fun Prefs.saveDissolveConfig(config: DissolveConfig) {
    dissolveCellSizePx = config.cellSizePx
    dissolveSweepMs = config.sweepMs
    dissolveCellLifeMs = config.cellLifeMs
    dissolveWindShearPx = config.windShearPx.toInt()
    dissolveDriftMaxPx = config.driftMaxPx.toInt()
}
