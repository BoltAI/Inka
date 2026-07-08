package co.podzim.inka.page

import co.podzim.inka.data.Prefs

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
