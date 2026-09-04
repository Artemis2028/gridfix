package app.gridfix.android.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import app.gridfix.android.AppInfo
import app.gridfix.android.BuildConfig

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

/**
 * Open a mail app with a feedback message already addressed and stamped.
 *
 * A closed test is twelve people who cannot reach you. The one thing worth more than
 * their report is a report that says which build it came from: "it crashed" is noise,
 * "0.9.28 build 56, Pixel 7, Android 14, it crashed opening the paywall" is a bug.
 * So the version, the device and the OS go in the body before they type a word.
 *
 * ACTION_SENDTO with a mailto: URI rather than ACTION_SEND, so only mail apps offer to
 * handle it - a send chooser full of messaging apps is how this gets abandoned. Guarded
 * the same way as [openLink]: a phone with no mail app configured must not crash the
 * settings screen.
 */
fun sendFeedback(context: Context) {
    val body = buildString {
        append("\n\n")
        append("---- please leave the lines below ----\n")
        append("App:     MGRS GPS ")
        append(BuildConfig.VERSION_NAME)
        append(" (build ")
        append(BuildConfig.VERSION_CODE)
        append(")\n")
        append("Device:  ")
        append(Build.MANUFACTURER)
        append(" ")
        append(Build.MODEL)
        append("\n")
        append("Android: ")
        append(Build.VERSION.RELEASE)
        append(" (API ")
        append(Build.VERSION.SDK_INT)
        append(")\n")
    }
    val uri = Uri.parse(
        "mailto:" + Uri.encode(AppInfo.SUPPORT_EMAIL) +
            "?subject=" + Uri.encode("MGRS GPS feedback " + BuildConfig.VERSION_NAME) +
            "&body=" + Uri.encode(body)
    )
    val opened = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_SENDTO, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrDefault(false)
    if (!opened) {
        Toast.makeText(context, "No mail app — write to " + AppInfo.SUPPORT_EMAIL, Toast.LENGTH_LONG).show()
    }
}

/**
 * Offer a saved crash report to the user's mail app. Called only from the prompt on the
 * launch after a crash, and only when they tap Send - the trace has been sitting in
 * app-private storage until this moment.
 */
fun sendCrashReport(context: Context, report: String) {
    val uri = Uri.parse(
        "mailto:" + Uri.encode(AppInfo.SUPPORT_EMAIL) +
            "?subject=" + Uri.encode("MGRS GPS crash " + BuildConfig.VERSION_NAME) +
            "&body=" + Uri.encode(
                "What were you doing when it closed?\n\n\n" +
                    "---- crash report, please leave below ----\n" + report
            )
    )
    val opened = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_SENDTO, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrDefault(false)
    if (!opened) {
        Toast.makeText(context, "No mail app — write to " + AppInfo.SUPPORT_EMAIL, Toast.LENGTH_LONG).show()
    }
}
