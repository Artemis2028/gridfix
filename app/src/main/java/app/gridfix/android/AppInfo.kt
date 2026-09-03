package app.gridfix.android

/**
 * The few strings that identify the app to the outside world. They live in one
 * place because they change for reasons that have nothing to do with the code:
 * a support address, a moved privacy policy, a new store listing.
 */
object AppInfo {
    /** Public contact for tile servers. Never a personal address: this goes out on every request. */
    const val CONTACT_URL = "https://github.com/Artemis2028/gridfix-legal"

    const val PRIVACY_URL = "https://github.com/Artemis2028/gridfix-legal/blob/main/PRIVACY.md"

    const val TERMS_URL = "https://github.com/Artemis2028/gridfix-legal/blob/main/TERMS.md"

    /**
     * User-Agent for OpenStreetMap, OpenTopoMap and the Terrarium terrain tiles.
     * OSM's tile usage policy asks for an identifiable app and a way to make
     * contact; the URL above is that contact.
     */
    fun userAgent(versionName: String): String = "MGRS GPS/$versionName (+$CONTACT_URL)"
}
