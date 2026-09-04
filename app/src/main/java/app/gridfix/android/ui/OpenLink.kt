package app.gridfix.android.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * Open an external link, or say so instead of dying.
 *
 * `UriHandler.openUri` throws when nothing on the device handles the intent, and a
 * locked-down unit phone can genuinely have no browser installed. The three screens
 * that link out - the disclaimer, the paywall and settings - include the two a user
 * meets before they have ever seen the map, so an unguarded call there is a crash on
 * first launch for exactly the phones this app is meant for.
 *
 * The toast is deliberate rather than a snackbar: the disclaimer and the paywall are
 * drawn outside the Scaffold that owns the app's SnackbarHost.
 */
fun openLink(context: Context, url: String) {
    val opened = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrDefault(false)
    if (!opened) {
        Toast.makeText(context, "No app on this phone can open $url", Toast.LENGTH_LONG).show()
    }
}
