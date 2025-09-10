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
 * - Attachments stored in attachments/{prefix}/{note-id}/ directories
 * - Links use [[attachment:filename]] format
 * - Compatible with Emacs org-attach system
 */
object AttachmentManager {
    private val TAG = AttachmentManager::class.java.name

    /**
     * Get the base attachment directory for a book.
     */
    fun getBookAttachmentBaseDir(bookFile: File): File {
        return File(bookFile.parent, "attachments")
    }

    /**
     * Get the hierarchical directory path components for a note ID.
     * Returns a pair of (prefix, shortenedId) for directory structure.
     */
    private fun getHierarchicalDirComponents(noteId: String): Pair<String, String> {
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
        return Pair(prefix, shortenedId)
    }

    /**
     * Get the attachment directory for a specific note.
     * Creates directory structure: attachments/{prefix}/{shortened-id}/
     */
    fun getNoteAttachmentDir(bookFile: File, noteId: String): File {
        val baseDir = getBookAttachmentBaseDir(bookFile)
        val (prefix, shortenedId) = getHierarchicalDirComponents(noteId)
        val hierarchicalDir = File(baseDir, prefix)
        return File(hierarchicalDir, shortenedId)
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
     * This follows org-mode's ID property convention.
     */
    @JvmStatic
    fun generateNoteId(): String {
        return UUID.randomUUID().toString().lowercase()
    }

    /**
     * Extract note ID from note properties.
     * Returns null if no ID property is found.
     */
    fun extractNoteId(note: Note, dataRepository: com.orgzly.android.data.DataRepository? = null): String? {
        // First try to get ID from database properties (new approach)
        if (dataRepository != null) {
            try {
                val properties = dataRepository.getNoteProperties(note.id)
                val idProperty = properties.find { it.name == "ID" }
                if (idProperty != null) {
                    return idProperty.value
                }
            } catch (e: Exception) {
                if (BuildConfig.LOG_DEBUG) {
                    LogUtils.d(TAG, "Failed to get note properties from database: ${e.message}")
                }
            }
        }
        
        // Fallback to content parsing (backward compatibility)
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
     */
    fun resolveAttachmentLink(
        bookFile: File,
        noteId: String?,
        attachmentPath: String
    ): File? {
        return when {
            attachmentPath.startsWith("attachment:") -> {
                val filename = attachmentPath.substring("attachment:".length)
                if (noteId != null) {
                    val attachmentDir = getNoteAttachmentDir(bookFile, noteId)
                    val attachmentFile = File(attachmentDir, filename)
                    if (attachmentFile.exists()) attachmentFile else null
                } else {
                    null
                }
            }
            attachmentPath.contains("/attachments/") -> {
                val bookDir = bookFile.parentFile ?: return null
                val normalizedPath = if (attachmentPath.startsWith("./")) {
                    attachmentPath.substring(2)
                } else {
                    attachmentPath
                }
                val file = File(bookDir, normalizedPath)
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
            "[[attachment:$filename][$filename]]"
        }
    }

    /**
     * List all attachments for a note.
     */
    fun getNoteAttachments(bookFile: File, noteId: String): List<File> {
        val attachmentDir = getNoteAttachmentDir(bookFile, noteId)
        return if (attachmentDir.exists() && attachmentDir.isDirectory) {
            attachmentDir.listFiles()?.filter { it.isFile } ?: emptyList()
        } else {
            emptyList()
        }
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
     * Clean up all attachment files for a note.
     * Returns the number of attachment files deleted.
     */
    fun cleanupNoteAttachments(bookFile: File, noteId: String): Int {
        var deletedCount = 0
        
        val attachmentDir = getNoteAttachmentDir(bookFile, noteId)
        if (!attachmentDir.exists()) return 0
        
        // Delete all files in the attachment directory
        attachmentDir.listFiles()?.forEach { file ->
            if (file.isFile && file.delete()) {
                deletedCount++
            }
        }
        
        // Remove empty directories
        try {
            if (attachmentDir.listFiles()?.isEmpty() == true) {
                attachmentDir.delete()
                
                // Also remove prefix directory if empty
                val prefixDir = attachmentDir.parentFile
                if (prefixDir?.name?.length == 2 && prefixDir.listFiles()?.isEmpty() == true) {
                    prefixDir.delete()
                }
            }
        } catch (e: SecurityException) {
            // Ignore - directories will remain
        }
        
        return deletedCount
    }
    
    /**
     * Check if a note has attachments by examining its content and tags.
     */
    fun noteHasAttachments(note: Note): Boolean {
        val tags = Note.dbDeSerializeTags(note.tags)
        return tags.contains("ATTACH") || note.content?.contains("attachment:") == true
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
