package io.bigmoeonedge.example.scan

import io.bigmoeonedge.example.AppSettings
import io.bigmoeonedge.example.DenseWeights
import org.json.JSONObject

/**
 * [AppSettings] as JSON, for a scan cell's record and a model's saved profile. Every field is named
 * explicitly: a field added to AppSettings later reads as its default from an older record, which is
 * the same rule SharedPreferences gives the settings screen.
 */
object SettingsJson {
    fun toJson(s: AppSettings): String = JSONObject().apply {
        put("mmap", s.mmap); put("cacheMb", s.cacheMb); put("cacheCeilMb", s.cacheCeilMb)
        put("ioThreads", s.ioThreads); put("threads", s.threads); put("nExpertUsed", s.nExpertUsed)
        put("nPredict", s.nPredict); put("sessionCtx", s.sessionCtx); put("oDirect", s.oDirect)
        put("overlap", s.overlap); put("denseWeights", s.denseWeights.name)
        put("prefetchLayers", s.prefetchLayers); put("predictPrefetch", s.predictPrefetch)
        put("predictSpecMax", s.predictSpecMax); put("routeAhead", s.routeAhead)
        put("dropColdPct", s.dropColdPct); put("rowStream", s.rowStream); put("releaseMmap", s.releaseMmap)
        put("npuPrefill", s.npuPrefill); put("npuLoaders", s.npuLoaders); put("substitutePct", s.substitutePct)
        put("spec", s.spec); put("mtpDraft", s.mtpDraft); put("mtpPMinPct", s.mtpPMinPct)
        put("thinking", s.thinking); put("metricsCsv", s.metricsCsv)
    }.toString()

    fun fromJson(json: String): AppSettings {
        val o = JSONObject(json)
        val d = AppSettings()
        return AppSettings(
            mmap = o.optBoolean("mmap", d.mmap),
            cacheMb = o.optInt("cacheMb", d.cacheMb),
            cacheCeilMb = o.optInt("cacheCeilMb", d.cacheCeilMb),
            ioThreads = o.optInt("ioThreads", d.ioThreads),
            threads = o.optInt("threads", d.threads),
            nExpertUsed = o.optInt("nExpertUsed", d.nExpertUsed),
            nPredict = o.optInt("nPredict", d.nPredict),
            sessionCtx = o.optInt("sessionCtx", d.sessionCtx),
            oDirect = o.optBoolean("oDirect", d.oDirect),
            overlap = o.optBoolean("overlap", d.overlap),
            denseWeights = runCatching { DenseWeights.valueOf(o.optString("denseWeights")) }.getOrDefault(d.denseWeights),
            prefetchLayers = o.optInt("prefetchLayers", d.prefetchLayers),
            predictPrefetch = o.optBoolean("predictPrefetch", d.predictPrefetch),
            predictSpecMax = o.optInt("predictSpecMax", d.predictSpecMax),
            routeAhead = o.optInt("routeAhead", d.routeAhead),
            dropColdPct = o.optInt("dropColdPct", d.dropColdPct),
            rowStream = o.optBoolean("rowStream", d.rowStream),
            releaseMmap = o.optBoolean("releaseMmap", d.releaseMmap),
            npuPrefill = o.optBoolean("npuPrefill", d.npuPrefill),
            npuLoaders = o.optInt("npuLoaders", d.npuLoaders),
            substitutePct = o.optInt("substitutePct", d.substitutePct),
            spec = o.optString("spec", d.spec).takeIf { it in AppSettings.SPEC_CHOICES } ?: d.spec,
            mtpDraft = o.optInt("mtpDraft", d.mtpDraft),
            mtpPMinPct = o.optInt("mtpPMinPct", d.mtpPMinPct),
            thinking = o.optBoolean("thinking", d.thinking),
            metricsCsv = o.optBoolean("metricsCsv", d.metricsCsv),
        )
    }

    /** The same settings with every lossy knob off: what "lossless baseline" means. */
    fun lossless(s: AppSettings): AppSettings =
        s.copy(dropColdPct = 0, substitutePct = 0, nExpertUsed = 0, routeAhead = 0)

    /** True when the settings can change the generated text (they are not a pure speed trade). */
    fun isLossy(s: AppSettings): Boolean =
        s.dropColdPct > 0 || s.substitutePct > 0 || s.nExpertUsed > 0 || s.routeAhead > 0
}
