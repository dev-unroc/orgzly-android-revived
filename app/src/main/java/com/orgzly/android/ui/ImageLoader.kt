package com.orgzly.android.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.text.Spannable
import android.text.style.ImageSpan
import android.view.View
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.orgzly.BuildConfig
import com.orgzly.R
import com.orgzly.android.App
import com.orgzly.android.prefs.AppPreferences
import com.orgzly.android.ui.views.style.FileLinkSpan
import com.orgzly.android.ui.views.style.AttachmentLinkSpan
import com.orgzly.android.usecase.LinkFindTarget
import com.orgzly.android.usecase.UseCaseRunner
import com.orgzly.android.util.AppPermissions
import com.orgzly.android.util.AttachmentManager
import com.orgzly.android.util.LogUtils
import java.io.File

object ImageLoader {
    @JvmStatic
    @JvmOverloads
    fun loadImages(textWithMarkup: TextView, noteId: String? = null, bookFile: File? = null) {
        val context = textWithMarkup.context
        
        android.util.Log.d(TAG, "loadImages: Called with noteId=$noteId, bookFile=${bookFile?.absolutePath}")
        android.util.Log.d(TAG, "loadImages: imagesEnabled=${AppPreferences.imagesEnabled(context)}")
        android.util.Log.d(TAG, "loadImages: externalFilesPermission=${AppPermissions.isGranted(context, AppPermissions.Usage.EXTERNAL_FILES_ACCESS)}")

        // Only if AppPreferences.displayImages(context) is true
        // Setup image visualization inside the note
        if (AppPreferences.imagesEnabled(context)
                // Storage permission has been granted
                && AppPermissions.isGranted(context, AppPermissions.Usage.EXTERNAL_FILES_ACCESS)) {
            
            android.util.Log.d(TAG, "loadImages: Conditions met, scanning for image links")
            
            // Count spans
            val fileLinkSpans = mutableListOf<FileLinkSpan>()
            val attachmentLinkSpans = mutableListOf<AttachmentLinkSpan>()
            
            SpanUtils.forEachSpan(textWithMarkup.text as Spannable, FileLinkSpan::class.java) { span, _, _ ->
                fileLinkSpans.add(span)
            }
            
            SpanUtils.forEachSpan(textWithMarkup.text as Spannable, AttachmentLinkSpan::class.java) { span, _, _ ->
                attachmentLinkSpans.add(span)
            }
            
            android.util.Log.d(TAG, "loadImages: Found ${fileLinkSpans.size} FileLinkSpan(s) and ${attachmentLinkSpans.size} AttachmentLinkSpan(s)")
            
            // Load images for FileLinkSpan (regular file links)
            for (span in fileLinkSpans) {
                loadImageFromFileLink(textWithMarkup, span, noteId, bookFile)
            }
            
            // Load images for AttachmentLinkSpan (attachment links)
            for (span in attachmentLinkSpans) {
                loadImageFromAttachmentLink(textWithMarkup, span, noteId, bookFile)
            }
        } else {
            android.util.Log.d(TAG, "loadImages: Conditions not met - images disabled or no permission")
        }
    }

    private fun loadImageFromFileLink(textWithMarkup: TextView, fileLinkSpan: FileLinkSpan, noteId: String?, bookFile: File?) {
        val path = fileLinkSpan.path

        if (hasSupportedExtension(path)) {
            val text = textWithMarkup.text as Spannable
            // Get the current context
            val context = App.getAppContext()

            val file = UseCaseRunner.run(LinkFindTarget(path, noteId, bookFile)).userData

            if (file !is File) {
                if (BuildConfig.LOG_DEBUG) LogUtils.d(TAG, "Did not find a File target for $path, actually found $file")
                return
            }

            if (file.exists()) {
                loadImageFromFile(textWithMarkup, file, fileLinkSpan)
            } else {
                if (BuildConfig.LOG_DEBUG) LogUtils.d(TAG, "File $file (from $path) does not exist")
            }
        }
    }

    private fun loadImageFromAttachmentLink(textWithMarkup: TextView, attachmentSpan: AttachmentLinkSpan, noteId: String?, bookFile: File?) {
        val filename = attachmentSpan.filename
        
        android.util.Log.d(TAG, "loadImageFromAttachmentLink: Processing attachment $filename")
        android.util.Log.d(TAG, "loadImageFromAttachmentLink: isImageFile=${AttachmentManager.isImageFile(filename)}")
        android.util.Log.d(TAG, "loadImageFromAttachmentLink: noteId=$noteId, bookFile=${bookFile?.absolutePath}")

        if (AttachmentManager.isImageFile(filename)) {
            if (noteId != null && bookFile != null) {
                android.util.Log.d(TAG, "loadImageFromAttachmentLink: Resolving attachment link for $filename")
                val attachmentFile = AttachmentManager.resolveAttachmentLink(bookFile, noteId, "attachment:$filename")
                android.util.Log.d(TAG, "loadImageFromAttachmentLink: Resolved to: ${attachmentFile?.absolutePath ?: "null"}")
                if (attachmentFile != null) {
                    android.util.Log.d(TAG, "loadImageFromAttachmentLink: Loading image from file: ${attachmentFile.absolutePath}")
                    loadImageFromFile(textWithMarkup, attachmentFile, attachmentSpan)
                } else {
                    android.util.Log.d(TAG, "loadImageFromAttachmentLink: Attachment file not found: $filename for note: $noteId")
                }
            } else {
                android.util.Log.d(TAG, "loadImageFromAttachmentLink: Cannot resolve attachment without note ID and book file: $filename")
            }
        } else {
            android.util.Log.d(TAG, "loadImageFromAttachmentLink: File is not an image: $filename")
        }
    }

    private fun loadImageFromFile(textWithMarkup: TextView, file: File, linkSpan: Any) {
        if (!file.exists()) {
            if (BuildConfig.LOG_DEBUG) LogUtils.d(TAG, "File does not exist: ${file.path}")
            return
        }

        val text = textWithMarkup.text as Spannable
        val context = App.getAppContext()

        // Get the Uri
        val contentUri = FileProvider.getUriForFile(
                context, BuildConfig.APPLICATION_ID + ".fileprovider", file)

        // Get image sizes to reduce their memory footprint by rescaling
        val options = BitmapFactory.Options()
        options.inJustDecodeBounds = true
        BitmapFactory.decodeFile(file.absolutePath, options)

        val size = calculateImageDisplaySize(
                file.name, "pre-load", textWithMarkup, options.outWidth, options.outHeight)

        // Setup a placeholder
        val drawable = ResourcesCompat.getDrawable(
                context.resources, R.drawable.image_placeholder, context.applicationContext.theme)
                ?: ColorDrawable(Color.TRANSPARENT)
        drawable.setBounds(0, 0, size.first, size.second)

        Glide.with(context)
                .asBitmap()
                .apply(RequestOptions().placeholder(drawable))
                .load(contentUri)
                .into(object : CustomTarget<Bitmap>() {

                    val start = text.getSpanStart(linkSpan)
                    val end = text.getSpanEnd(linkSpan)
                    val flags = text.getSpanFlags(linkSpan)

                    var placeholderSpan: ImageSpan? = null

                    override fun onLoadStarted(placeholder: Drawable?) {
                        if (placeholder != null) {
                            placeholderSpan = ImageSpan(placeholder)
                            text.setSpan(placeholderSpan, start, end, flags)
                        }
                    }

                    override fun onResourceReady(bitmap: Bitmap, transition: Transition<in Bitmap>?) {
                        val bitmapDrawable = BitmapDrawable(
                                textWithMarkup.context.resources, bitmap)

                        val newSize = calculateImageDisplaySize(
                                file.name, "on-load",
                                textWithMarkup,
                                bitmapDrawable.bitmap.width,
                                bitmapDrawable.bitmap.height)

                        bitmapDrawable.setBounds(0, 0, newSize.first, newSize.second)

                        placeholderSpan?.let {
                            text.removeSpan(it)
                        }

                        text.setSpan(ImageSpan(bitmapDrawable), start, end, flags)
                    }

                    override fun onLoadCleared(placeholder: Drawable?) {
                    }
                })
    }

    fun hasSupportedExtension(path: String): Boolean {
        return path.matches(Regex(""".+\.(?:jpg|jpeg|gif|png|bmp|webp|svg)""", RegexOption.IGNORE_CASE))
    }

    private fun calculateImageDisplaySize(
            file: String, stage: String, view: View, width: Int, height: Int): Pair<Int, Int> {

        val ratio = height.toFloat() / width.toFloat()

        var newWidth = width

        // Get the display metrics to be able to rescale the image if needed
        val metrics = view.context.resources.displayMetrics

        // Before image loading view.width might not be initialized
        // So we take a default maximum value that will be reduced
        var maxWidth = view.width
        if (maxWidth == 0) {
            maxWidth = metrics.widthPixels
        }

        // Within limits
        if (newWidth > maxWidth) {
            newWidth = maxWidth
        }

        // Scale down to width
        if (AppPreferences.imagesScaleDownToWidth(view.context)) {
            val downToWidth = AppPreferences.imagesScaleDownToWidthValue(view.context)

            if (newWidth > downToWidth) {
                newWidth = downToWidth
            }
        }

        val newHeight = (newWidth * ratio).toInt()

        if (BuildConfig.LOG_DEBUG)
            LogUtils.d(TAG, file, String.format("%-8s  View: %-4d  Metrics: %-4d  Input: %-4dx%-4d  Ratio: %.5f  Output: %-4dx%-4d",
                    stage, view.width, metrics.widthPixels, width, height, ratio, newWidth, newHeight))

        return Pair(newWidth, newHeight)
    }

    private val TAG = ImageLoader::class.java.name
}