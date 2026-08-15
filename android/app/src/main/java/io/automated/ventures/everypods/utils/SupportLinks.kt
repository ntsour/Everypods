package io.automated.ventures.everypods.utils

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.net.toUri

const val EVERYPODS_REPO_URL = "https://github.com/ntsour/Everypods"
const val EVERYPODS_ISSUES_URL = "$EVERYPODS_REPO_URL/issues"
const val EVERYPODS_SUPPORT_EMAIL = "automated.ventures.apps@gmail.com"

fun Context.openEveryPodsIssues() {
    startActivity(Intent(Intent.ACTION_VIEW, EVERYPODS_ISSUES_URL.toUri()))
}

fun Context.openEveryPodsSupportEmail(
    subject: String = "",
    body: String = ""
): Boolean {
    val query = buildList {
        if (subject.isNotBlank()) add("subject=${Uri.encode(subject)}")
        if (body.isNotBlank()) add("body=${Uri.encode(body)}")
    }.joinToString("&")
    val uri = "mailto:${Uri.encode(EVERYPODS_SUPPORT_EMAIL)}${if (query.isBlank()) "" else "?$query"}".toUri()
    val intent = Intent(Intent.ACTION_SENDTO, uri)

    return try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, "No email app found.", Toast.LENGTH_SHORT).show()
        false
    }
}
