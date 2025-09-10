package com.orgzly.android.util

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.orgzly.BuildConfig
import com.orgzly.android.App
import com.orgzly.android.BookName
import com.orgzly.android.data.DataRepository
import com.orgzly.android.db.entity.BookView
import com.orgzly.android.db.entity.Note
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
     * Clean up all attachments for a book.
     * This method finds all notes with attachments in the book and removes their attachment files.
     * 
     * @param dataRepository DataRepository instance to query notes
     * @param bookView The book being deleted
     * @param cleanupRemoteAttachments If true, also delete attachments in sync repositories
     * @return Number of attachment files deleted
     */
    @JvmStatic
    fun cleanupBookAttachments(
        dataRepository: DataRepository, 
        bookView: BookView, 
        cleanupRemoteAttachments: Boolean = true
    ): Int {
        if (BuildConfig.LOG_DEBUG) {
            android.util.Log.d(TAG, "Cleaning up attachments for book: ${bookView.book.name} (remote: $cleanupRemoteAttachments)")
        }
        
        var totalDeleted = 0
        
        try {
            // Get all notes in the book that have attachments
            val notesWithAttachments = dataRepository.getNotesWithAttachments(bookView.book.id)
            
            if (notesWithAttachments.isEmpty()) {
                if (BuildConfig.LOG_DEBUG) {
                    android.util.Log.d(TAG, "No notes with attachments found in book: ${bookView.book.name}")
                }
                return 0
            }
            
            if (BuildConfig.LOG_DEBUG) {
                android.util.Log.d(TAG, "Found ${notesWithAttachments.size} notes with attachments in book: ${bookView.book.name}")
            }
            
            // Always clean up local attachments (app storage)
            totalDeleted += cleanupLocalAttachments(dataRepository, bookView, notesWithAttachments)
            
            // Clean up remote attachments only if requested
            if (cleanupRemoteAttachments && bookView.linkRepo != null) {
                totalDeleted += cleanupRemoteAttachments(dataRepository, bookView, notesWithAttachments)
            }
            
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error cleaning up book attachments for ${bookView.book.name}", e)
        }
        
        if (BuildConfig.LOG_DEBUG) {
            android.util.Log.d(TAG, "Total attachment cleanup for book ${bookView.book.name}: $totalDeleted files deleted")
        }
        
        return totalDeleted
    }
    
    /**
     * Clean up local attachments stored in app storage.
     */
    private fun cleanupLocalAttachments(
        dataRepository: DataRepository,
        bookView: BookView,
        notesWithAttachments: List<Note>
    ): Int {
        val context = App.getAppContext()
        val appExternalDir = context.getExternalFilesDir("orgzly-books") ?: context.filesDir
        val bookDirName = bookView.book.name.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
        val bookFile = File(appExternalDir, "$bookDirName.org")
        
        if (BuildConfig.LOG_DEBUG) {
            android.util.Log.d(TAG, "Cleaning up local attachments at: ${bookFile.absolutePath}")
        }
        
        return cleanupAttachmentsAtPath(bookFile, notesWithAttachments, dataRepository)
    }
    
    /**
     * Clean up remote attachments stored in sync repositories.
     */
    private fun cleanupRemoteAttachments(
        dataRepository: DataRepository,
        bookView: BookView,
        notesWithAttachments: List<Note>
    ): Int {
        val repoUri = bookView.linkRepo?.url ?: return 0
        
        if (BuildConfig.LOG_DEBUG) {
            android.util.Log.d(TAG, "Cleaning up remote attachments in repo: $repoUri")
        }
        
        return when {
            repoUri.startsWith("content://com.android.externalstorage.documents/tree/") -> {
                cleanupBookAttachmentsDocumentFile(dataRepository, bookView, notesWithAttachments)
            }
            else -> {
                val bookFile = getBookFileForSyncRepo(dataRepository, bookView)
                cleanupAttachmentsAtPath(bookFile, notesWithAttachments, dataRepository)
            }
        }
    }
    
    /**
     * Get book file path for sync repository (excludes app storage fallback).
     */
    private fun getBookFileForSyncRepo(dataRepository: DataRepository, bookView: BookView): File {
        if (bookView.syncedTo != null) {
            val repoRelativePath = BookName.getRepoRelativePath(bookView)
            val repo = bookView.linkRepo
            if (repo != null) {
                when {
                    repo.url.startsWith("file:") -> {
                        val repoPath = repo.url.removePrefix("file:")
                        return File(repoPath, repoRelativePath)
                    }
                    repo.url.startsWith("content://com.android.externalstorage.documents/tree/primary") -> {
                        val path = repo.url.removePrefix("content://com.android.externalstorage.documents/tree/primary")
                        val decodedPath = java.net.URLDecoder.decode(path, "UTF-8")
                        val cleanPath = if (decodedPath.startsWith(":")) decodedPath.substring(1) else decodedPath
                        val repoPath = "/storage/emulated/0/$cleanPath"
                        return File(repoPath, repoRelativePath)
                    }
                }
            }
        }
        // If no sync repo found, return a dummy file that won't exist
        return File("/dev/null")
    }
    
    /**
     * Common attachment cleanup logic for file-based storage.
     */
    private fun cleanupAttachmentsAtPath(
        bookFile: File,
        notesWithAttachments: List<Note>,
        dataRepository: DataRepository
    ): Int {
        var totalDeleted = 0
        
        try {
            // Clean up attachments for each note with attachments
            for (note in notesWithAttachments) {
                val noteIdProperty = extractNoteId(note, dataRepository) ?: continue
                val deletedCount = cleanupNoteAttachments(bookFile, noteIdProperty)
                totalDeleted += deletedCount
                
                if (BuildConfig.LOG_DEBUG && deletedCount > 0) {
                    android.util.Log.d(TAG, "Deleted $deletedCount attachment files for note: ${note.title}")
                }
            }
            
            // Try to clean up the main attachments directory if it's now empty
            val attachmentsDir = File(bookFile.parentFile, "attachments")
            if (attachmentsDir.exists() && attachmentsDir.isDirectory) {
                val remainingFiles = attachmentsDir.listFiles()
                if (remainingFiles?.isEmpty() == true) {
                    if (attachmentsDir.delete()) {
                        if (BuildConfig.LOG_DEBUG) {
                            android.util.Log.d(TAG, "Removed empty attachments directory: ${attachmentsDir.absolutePath}")
                        }
                    }
                }
            }
            
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error cleaning up attachments at path: ${bookFile.absolutePath}", e)
        }
        
        return totalDeleted
    }
    
    /**
     * Clean up attachments for DocumentFile-based repositories.
     */
    private fun cleanupBookAttachmentsDocumentFile(
        dataRepository: DataRepository,
        bookView: BookView,
        notesWithAttachments: List<Note>
    ): Int {
        var totalDeleted = 0
        val context = App.getAppContext()
        
        try {
            val repoUri = Uri.parse(bookView.linkRepo!!.url)
            val repoDocumentFile = DocumentFile.fromTreeUri(context, repoUri)
                ?: return 0
            
            // Find the attachments directory in the repository
            val attachmentsDir = repoDocumentFile.findFile("attachments")
            if (attachmentsDir == null || !attachmentsDir.exists()) {
                if (BuildConfig.LOG_DEBUG) {
                    android.util.Log.d(TAG, "No attachments directory found in DocumentFile repository")
                }
                return 0
            }
            
            if (BuildConfig.LOG_DEBUG) {
                android.util.Log.d(TAG, "Found attachments directory in DocumentFile repository")
            }
            
            // Clean up attachments for each note
            for (note in notesWithAttachments) {
                val noteIdProperty = extractNoteId(note, dataRepository) ?: continue
                val (prefix, shortenedId) = getHierarchicalDirComponents(noteIdProperty)
                
                // Find the prefix directory
                val prefixDir = attachmentsDir.findFile(prefix)
                if (prefixDir == null || !prefixDir.exists()) {
                    continue
                }
                
                // Find the note directory
                val noteDir = prefixDir.findFile(shortenedId)
                if (noteDir == null || !noteDir.exists()) {
                    continue
                }
                
                // Delete all files in the note directory
                val attachmentFiles = noteDir.listFiles()
                for (file in attachmentFiles) {
                    if (file.isFile) {
                        if (file.delete()) {
                            totalDeleted++
                            if (BuildConfig.LOG_DEBUG) {
                                android.util.Log.d(TAG, "Deleted DocumentFile attachment: ${file.name}")
                            }
                        }
                    }
                }
                
                // Delete the note directory if empty
                if (noteDir.listFiles().isEmpty()) {
                    noteDir.delete()
                    if (BuildConfig.LOG_DEBUG) {
                        android.util.Log.d(TAG, "Deleted empty note directory: $shortenedId")
                    }
                }
                
                // Delete the prefix directory if empty
                if (prefixDir.listFiles().isEmpty()) {
                    prefixDir.delete()
                    if (BuildConfig.LOG_DEBUG) {
                        android.util.Log.d(TAG, "Deleted empty prefix directory: $prefix")
                    }
                }
            }
            
            // Delete the main attachments directory if empty
            if (attachmentsDir.listFiles().isEmpty()) {
                attachmentsDir.delete()
                if (BuildConfig.LOG_DEBUG) {
                    android.util.Log.d(TAG, "Deleted empty attachments directory")
                }
            }
            
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error cleaning up DocumentFile attachments", e)
        }
        
        return totalDeleted
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
