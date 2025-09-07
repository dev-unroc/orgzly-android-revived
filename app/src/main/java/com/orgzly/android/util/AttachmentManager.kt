package com.orgzly.android.util

import android.content.Context
import android.net.Uri
import com.orgzly.BuildConfig
import com.orgzly.android.App
import com.orgzly.android.db.entity.Note
import com.orgzly.android.prefs.AppPreferences
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

/**
 * Manages org-mode style attachments in attachments directories.
 * 
 * Follows Emacs org-mode attachment convention:
 * - Attachments stored in attachments/{note-id}/ directories
 * - Links use [[attachment:filename]] format
 * - Compatible with Emacs org-attach system
 */
object AttachmentManager {
    private val TAG = AttachmentManager::class.java.name

    /**
     * Get the base attachment directory for a book.
     * Returns the attachments directory relative to the book's location.
     */
    fun getBookAttachmentBaseDir(bookFile: File): File {
        return File(bookFile.parent, "attachments")
    }

    /**
     * Get the attachment directory for a specific note.
     * Creates directory structure compatible with Doom Emacs:
     * book-dir/attachments/{first-two-chars}/{note-id}/
     * e.g., attachments/ca/effd7f-f678-486b-8ffa-7bac167f4e71/
     */
    fun getNoteAttachmentDir(bookFile: File, noteId: String): File {
        val baseDir = getBookAttachmentBaseDir(bookFile)
        // Use hierarchical structure like Doom Emacs: first 2 chars of first segment as subdirectory
        // For UUID caeffd7f-f678-486b-8ffa-7bac167f4e71, use "ca" and "effd7f-f678-486b-8ffa-7bac167f4e71"
        val segments = noteId.lowercase().split("-")
        val prefix = if (segments.isNotEmpty() && segments[0].length >= 2) {
            segments[0].take(2)
        } else {
            noteId.lowercase().take(2)
        }
        val shortenedId = if (segments.isNotEmpty() && segments[0].length > 2) {
            segments[0].drop(2) + if (segments.size > 1) "-" + segments.drop(1).joinToString("-") else ""
        } else {
            noteId.lowercase()
        }
        val hierarchicalDir = File(baseDir, prefix)
        return File(hierarchicalDir, shortenedId)
    }

    /**
     * Get the legacy (flat) attachment directory for backward compatibility.
     * Returns: book-dir/attachments/{note-id}/
     */
    fun getLegacyNoteAttachmentDir(bookFile: File, noteId: String): File {
        val baseDir = getBookAttachmentBaseDir(bookFile)
        return File(baseDir, noteId)
    }

    /**
     * Get the attachment directory for a note, creating it if necessary.
     */
    fun getOrCreateNoteAttachmentDir(bookFile: File, noteId: String): File {
        val attachmentDir = getNoteAttachmentDir(bookFile, noteId)
        if (!attachmentDir.exists()) {
            attachmentDir.mkdirs()
        }
        return attachmentDir
    }

    /**
     * Generate a UUID for a new note if it doesn't have one.
     * This follows org-mode's ID property convention (lowercase for Doom Emacs compatibility).
     */
    @JvmStatic
    fun generateNoteId(): String {
        return UUID.randomUUID().toString().lowercase()
    }

    /**
     * Extract note ID from note properties.
     * Returns null if no ID property is found.
     */
    fun extractNoteId(note: Note): String? {
        // Look for ID property in note content
        val content = note.content
        if (content != null) {
            val lines = content.split('\n')
            var inProperties = false
            
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed == ":PROPERTIES:") {
                    inProperties = true
                    continue
                }
                if (trimmed == ":END:") {
                    break
                }
                if (inProperties && trimmed.startsWith(":ID:")) {
                    return trimmed.substring(4).trim()
                }
            }
        }
        return null
    }

    /**
     * Copy a file from URI to attachment directory.
     * Returns the destination file.
     */
    @Throws(IOException::class)
    fun copyUriToAttachment(
        context: Context,
        uri: Uri,
        attachmentDir: File,
        suggestedFilename: String? = null
    ): File {
        val filename = suggestedFilename ?: getFileNameFromUri(context, uri)
        val destinationFile = File(attachmentDir, filename)

        // Ensure attachment directory exists
        if (!attachmentDir.exists()) {
            attachmentDir.mkdirs()
        }

        // Handle filename conflicts by appending numbers
        val finalFile = getUniqueFile(destinationFile)

        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            FileOutputStream(finalFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        } ?: throw IOException("Could not open input stream for URI: $uri")

        return finalFile
    }

    /**
     * Copy a regular file to attachment directory.
     * Returns the destination file.
     */
    @Throws(IOException::class)
    fun copyFileToAttachment(
        sourceFile: File,
        attachmentDir: File,
        suggestedFilename: String? = null
    ): File {
        val filename = suggestedFilename ?: sourceFile.name
        val destinationFile = File(attachmentDir, filename)

        // Ensure attachment directory exists
        if (!attachmentDir.exists()) {
            attachmentDir.mkdirs()
        }

        // Handle filename conflicts
        val finalFile = getUniqueFile(destinationFile)

        sourceFile.copyTo(finalFile)
        return finalFile
    }

    /**
     * Resolve an attachment link to an actual file.
     * Handles both "attachment:filename" and direct "attachments/" paths.
     * Supports both hierarchical (Doom Emacs) and legacy flat directory structures.
     */
    fun resolveAttachmentLink(
        bookFile: File,
        noteId: String?,
        attachmentPath: String
    ): File? {
        if (BuildConfig.LOG_DEBUG) {
            LogUtils.d(TAG, "Resolving attachment: $attachmentPath for note: $noteId")
        }

        return when {
            attachmentPath.startsWith("attachment:") -> {
                // Handle [[attachment:filename.ext]] format
                val filename = attachmentPath.substring("attachment:".length)
                if (noteId != null) {
                    // First try hierarchical structure (Doom Emacs compatible)
                    val hierarchicalDir = getNoteAttachmentDir(bookFile, noteId)
                    val hierarchicalFile = File(hierarchicalDir, filename)
                    if (hierarchicalFile.exists()) {
                        if (BuildConfig.LOG_DEBUG) {
                            LogUtils.d(TAG, "Found attachment in hierarchical structure: ${hierarchicalFile.absolutePath}")
                        }
                        return hierarchicalFile
                    }
                    
                    // Fallback to legacy flat structure for backward compatibility
                    val legacyDir = getLegacyNoteAttachmentDir(bookFile, noteId)
                    val legacyFile = File(legacyDir, filename)
                    if (legacyFile.exists()) {
                        if (BuildConfig.LOG_DEBUG) {
                            LogUtils.d(TAG, "Found attachment in legacy flat structure: ${legacyFile.absolutePath}")
                        }
                        return legacyFile
                    }
                    
                    if (BuildConfig.LOG_DEBUG) {
                        LogUtils.d(TAG, "Attachment not found in either structure: $filename")
                    }
                    return null
                } else {
                    null
                }
            }
            attachmentPath.contains("/attachments/") -> {
                // Handle direct attachment paths like ./attachments/ca/id/filename.ext or ./attachments/id/filename.ext
                val bookDir = bookFile.parentFile ?: return null
                val file = File(bookDir, attachmentPath)
                if (file.exists()) file else null
            }
            else -> null
        }
    }

    /**
     * Create an attachment link for a file.
     * Returns the org-mode link format: [[attachment:filename.ext]]
     */
    @JvmStatic
    @JvmOverloads
    fun createAttachmentLink(attachmentFile: File, linkText: String? = null): String {
        val filename = attachmentFile.name
        return if (linkText != null && linkText != filename) {
            "[[attachment:$filename][$linkText]]"
        } else {
            "[[attachment:$filename]]"
        }
    }

    /**
     * List all attachments for a note.
     * Returns list of files from both hierarchical and legacy directory structures.
     */
    fun getNoteAttachments(bookFile: File, noteId: String): List<File> {
        val attachments = mutableListOf<File>()
        
        // Check hierarchical structure first (Doom Emacs compatible)
        val hierarchicalDir = getNoteAttachmentDir(bookFile, noteId)
        if (hierarchicalDir.exists() && hierarchicalDir.isDirectory) {
            hierarchicalDir.listFiles()?.let { files ->
                attachments.addAll(files.filter { it.isFile })
            }
        }
        
        // Check legacy flat structure for backward compatibility
        val legacyDir = getLegacyNoteAttachmentDir(bookFile, noteId)
        if (legacyDir.exists() && legacyDir.isDirectory) {
            legacyDir.listFiles()?.let { files ->
                // Only add files that aren't already in the list (avoid duplicates)
                files.filter { it.isFile && !attachments.any { existing -> existing.name == it.name } }
                    .let { uniqueFiles -> attachments.addAll(uniqueFiles) }
            }
        }
        
        return attachments
    }

    /**
     * Delete an attachment file.
     * Returns true if successful.
     */
    fun deleteAttachment(attachmentFile: File): Boolean {
        return try {
            attachmentFile.delete()
        } catch (exception: SecurityException) {
            if (BuildConfig.LOG_DEBUG) {
                // TODO: Fix compilation issue
                // LogUtils.e(TAG, "Failed to delete attachment: ${attachmentFile.path}", exception)
            }
            false
        }
    }

    /**
     * Get file extension from filename.
     */
    fun getFileExtension(filename: String): String {
        val lastDot = filename.lastIndexOf('.')
        return if (lastDot > 0 && lastDot < filename.length - 1) {
            filename.substring(lastDot + 1).lowercase()
        } else {
            ""
        }
    }

    /**
     * Check if file is an image based on extension.
     */
    fun isImageFile(filename: String): Boolean {
        val extension = getFileExtension(filename)
        return extension in setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "svg")
    }

    /**
     * Get filename from URI using content resolver.
     */
    @JvmStatic
    fun getFileNameFromUri(context: Context, uri: Uri): String {
        var filename = "attachment"
        
        // Try to get filename from URI
        uri.lastPathSegment?.let { segment ->
            // Remove any path separators and use just the filename
            filename = segment.substringAfterLast('/')
        }

        // Try content resolver for better filename
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val displayNameColumn = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (displayNameColumn >= 0) {
                    cursor.getString(displayNameColumn)?.let { name ->
                        filename = name
                    }
                }
            }
        }

        // Ensure we have some kind of extension
        if (!filename.contains('.')) {
            // Try to determine from MIME type
            val mimeType = context.contentResolver.getType(uri)
            val extension = when (mimeType) {
                "image/jpeg" -> "jpg"
                "image/png" -> "png"
                "image/gif" -> "gif"
                "image/bmp" -> "bmp"
                "image/webp" -> "webp"
                "application/pdf" -> "pdf"
                "text/plain" -> "txt"
                else -> "bin"
            }
            filename += ".$extension"
        }

        return filename
    }

    /**
     * Get a unique filename by appending numbers if file already exists.
     */
    private fun getUniqueFile(file: File): File {
        if (!file.exists()) {
            return file
        }

        val name = file.nameWithoutExtension
        val extension = file.extension
        val parent = file.parentFile
        var counter = 1

        while (true) {
            val newName = if (extension.isNotEmpty()) {
                "${name}_${counter}.${extension}"
            } else {
                "${name}_${counter}"
            }
            val newFile = File(parent, newName)
            if (!newFile.exists()) {
                return newFile
            }
            counter++
        }
    }
}
