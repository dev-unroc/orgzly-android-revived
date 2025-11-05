package com.orgzly.android.ui.views.richtext

import com.orgzly.android.ui.views.style.CheckboxSpan
import com.orgzly.android.ui.views.style.DrawerMarkerSpan
import java.io.File

/**
 * Actions which user can perform in view mode.
 */
interface ActionableRichTextView {
    fun toggleDrawer(markerSpan: DrawerMarkerSpan)
    fun toggleCheckbox(checkboxSpan: CheckboxSpan)
    fun followLinkToNoteOrBookWithProperty(name: String, value: String)
    fun followLinkToFile(path: String)
    fun followLinkToFileWithContext(path: String, noteId: String?, bookFile: File?)
}
