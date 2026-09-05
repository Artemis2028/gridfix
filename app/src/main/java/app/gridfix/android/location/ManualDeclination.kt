package app.gridfix.android.location

/** Convert a fully entered angle only when its reference is known. */
fun manualDeclination(text: String, east: Boolean, mils: Boolean, asGridMagnetic: Boolean,
    convergence: Float?): Float? {
    val entered = text.toFloatOrNull()?.takeIf { it.isFinite() } ?: return null
    val degrees = if (mils) entered * 360f / 6400f else entered
    if (degrees !in 0f..180f) return null
    val signed = if (east) degrees else -degrees
    val result = if (asGridMagnetic) {
        signed + (convergence?.takeIf { it.isFinite() } ?: return null)
    } else signed
    return ((result + 540f) % 360f) - 180f
}
