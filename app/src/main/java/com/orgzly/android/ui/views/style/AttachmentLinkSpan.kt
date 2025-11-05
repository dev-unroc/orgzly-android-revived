package com.orgzly.android.ui.views.style

import android.os.Handler
import android.view.View
import com.orgzly.android.ui.views.richtext.ActionableRichTextView

/**
 * Span for attachment links in format [[attachment:filename.ext]] or [[attachment:filename.ext][description]].
 * 
 * These links are resolved relative to the current note's attachment directory,
 * following Emacs org-mode attachment convention.
 */
class AttachmentLinkSpan(
    val type: Int,
    val filename: String,
    val name: String?
) : LinkSpan(), Offsetting {

    override val characterOffset = when (type) {
        TYPE_NO_BRACKETS -> 0
        TYPE_BRACKETS -> 4
        TYPE_BRACKETS_WITH_NAME -> 6 + "attachment:$filename".length
        else -> 0
    }

    override fun onClick(view: View) {
        if (view is ActionableRichTextView) {
            Handler().post { // Run after onClick to prevent Snackbar from closing immediately
                view.followLinkToFile("attachment:$filename")
            }
        }
    }

    companion object {
        const val PREFIX = "attachment:"
    }
}
