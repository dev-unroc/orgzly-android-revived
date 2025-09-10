package com.orgzly.android.usecase

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.orgzly.BuildConfig
import com.orgzly.android.App
import com.orgzly.android.BookName
import com.orgzly.android.data.DataRepository
import com.orgzly.android.db.entity.BookView
import com.orgzly.android.db.entity.Note
import com.orgzly.android.prefs.AppPreferences
import com.orgzly.android.util.AttachmentManager
import com.orgzly.android.util.LogUtils
import com.orgzly.android.LocalStorage
import java.io.File
import java.net.URLDecoder

/**
 * Use case for saving shared files as attachments when creating or updating notes.
 * 
 * This handles copying files from URIs to the appropriate attachment directories
 * and updating note content to use attachment links.
 */
class AttachmentSaveFiles(
    private val noteId: Long,
    private val sharedFileUri: Uri?,
    private val noteIdProperty: String?
) : UseCase() {

    private val context: Context = App.getAppContext()
    
    companion object {
        private const val TAG = "AttachmentSaveFiles"
        
        /**
         * Resolve the filesystem path for a repository URI.
         * This works for Document-based repositories and File-based repositories.
         */
        private fun resolveRepositoryFilesystemPath(repoUri: Uri): String? {
            return when (repoUri.scheme) {
                "content" -> {
                    when {
                        repoUri.toString().startsWith("content://com.android.externalstorage.documents/tree/primary") -> {
                            // Handle external storage document URIs
                            val path = repoUri.toString()
                                .removePrefix("content://com.android.externalstorage.documents/tree/primary")
                                .let { URLDecoder.decode(it, "UTF-8") }
                                .let { if (it.startsWith(":")) it.substring(1) else it }
                            
                            // Use LocalStorage to get the proper base external storage path
                            val baseStorage = LocalStorage.storage(App.getAppContext())
                            if (path.isNotEmpty()) {
                                "$baseStorage/$path"
                            } else {
                                baseStorage
                            }
                        }
                        else -> {
                            // Other content URIs - cannot resolve to filesystem path
                            null
                        }
                    }
                }
                "file" -> {
                    // File-based repositories
                    repoUri.path
                }
                else -> {
                    // Unknown scheme
                    null
                }
            }
        }
    }

    override fun run(dataRepository: DataRepository): UseCaseResult {
        if (BuildConfig.LOG_DEBUG) {
            LogUtils.d(TAG, "Starting attachment save for noteId=$noteId")
        }
        
        if (sharedFileUri == null || noteIdProperty == null) {
            Log.w(TAG, "Missing required parameters for attachment save")
            return UseCaseResult()
        }

        val note = dataRepository.getNote(noteId) ?: run {
            Log.e(TAG, "Note not found for id: $noteId")
            return UseCaseResult()
        }
        
        val bookView = dataRepository.getBookView(note.position.bookId) ?: run {
            Log.e(TAG, "BookView not found for bookId: ${note.position.bookId}")
            return UseCaseResult()
        }
        
        val bookFile = getBookFileForAttachments(dataRepository, bookView)

        return try {
            // Check if we're dealing with external storage through DocumentFile
            val repoUri = bookView.linkRepo?.url
            val useDocumentFile = repoUri != null && repoUri.startsWith("content://com.android.externalstorage.documents/tree/")
            
            val attachmentFile = if (useDocumentFile) {
                saveAttachmentUsingDocumentFile(bookView, noteIdProperty, sharedFileUri)
            } else {
                saveAttachmentUsingFileApi(bookFile, noteIdProperty, sharedFileUri)
            }
            
            // Update note content if needed (the content should already have the attachment link
            // but we might need to handle cases where the filename changed due to conflicts)
            val originalFilename = AttachmentManager.getFileNameFromUri(context, sharedFileUri)
            val actualFilename = attachmentFile.name
            
            if (originalFilename != actualFilename) {
                // File was renamed due to conflict, update the note content
                val updatedContent = note.content?.replace(
                    "[[attachment:$originalFilename]]",
                    "[[attachment:$actualFilename]]"
                )
                
                if (updatedContent != null && updatedContent != note.content) {
                    dataRepository.updateNoteContent(noteId, updatedContent)
                }
            }

            UseCaseResult(
                userData = attachmentFile
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save attachment", e)
            throw Exception("Failed to save attachment: ${e.message}", e)
        }
    }

    /**
     * Save attachment using DocumentFile API for external storage
     */
    private fun saveAttachmentUsingDocumentFile(
        bookView: BookView,
        noteIdProperty: String,
        sharedFileUri: Uri
    ): File {
        LogUtils.d(TAG, "Saving attachment using DocumentFile API")
        
        val repoUri = Uri.parse(bookView.linkRepo!!.url)
        LogUtils.d(TAG, "Repository URI: $repoUri")
        
        // Get the root DocumentFile for the repository
        val repoDocumentFile = DocumentFile.fromTreeUri(context, repoUri)
            ?: throw IllegalStateException("Cannot access repository directory")
        
        LogUtils.d(TAG, "Repository DocumentFile: ${repoDocumentFile.uri}")
        
        // Create or find attachments directory in the repository root (using visible directory for testing)
        val attachRootDir = repoDocumentFile.findFile("attachments")
            ?: repoDocumentFile.createDirectory("attachments")
            ?: throw IllegalStateException("Cannot create attachments directory")
        
        LogUtils.d(TAG, "Attachment root directory: ${attachRootDir.uri}")
        
        // Create or find hierarchical attachment directory (Emacs org-mode compatible)
        // For UUID caeffd7f-f678-486b-8ffa-7bac167f4e71, use "ca" and "effd7f-f678-486b-8ffa-7bac167f4e71"
        val (noteIdPrefix, shortenedId) = getHierarchicalDirComponents(noteIdProperty)
        
        // First, get or create the prefix subdirectory
        val prefixDir = attachRootDir.findFile(noteIdPrefix)
            ?: attachRootDir.createDirectory(noteIdPrefix)
            ?: throw IllegalStateException("Cannot create prefix attachment directory")
        
        // Then create the note-specific directory within the prefix directory
        val noteAttachDir = prefixDir.findFile(shortenedId)
            ?: prefixDir.createDirectory(shortenedId)
            ?: throw IllegalStateException("Cannot create note attachment directory")
        
        LogUtils.d(TAG, "Note attachment directory: ${noteAttachDir.uri}")
        
        if (BuildConfig.LOG_DEBUG) {
            logDirectoryContents(attachRootDir, "Attach root directory")
        }
        
        // Get the original filename
        val originalFilename = AttachmentManager.getFileNameFromUri(context, sharedFileUri)
        LogUtils.d(TAG, "Original filename: $originalFilename")
        
        // Create the attachment file (delete existing if present)
        val existingFile = noteAttachDir.findFile(originalFilename)
        if (existingFile != null) {
            LogUtils.d(TAG, "Deleting existing attachment file")
            existingFile.delete()
        }
        
        val attachmentDocFile = noteAttachDir.createFile("*/*", originalFilename)
            ?: throw IllegalStateException("Cannot create attachment file")
        
        LogUtils.d(TAG, "Created attachment DocumentFile: ${attachmentDocFile.uri}")
        
        // Copy the content using common copy function
        copyFileContent(sharedFileUri, attachmentDocFile.uri, "DocumentFile API")
        
        // Calculate the expected file system path using proper repository path resolution
        val repoPath = resolveRepositoryFilesystemPath(repoUri)
            ?: throw IllegalStateException("Cannot resolve repository filesystem path for $repoUri")
        
        val attachmentPath = "$repoPath/attachments/$noteIdPrefix/$shortenedId/$originalFilename"
        
        // Verify file creation and trigger media scanner
        verifyAndRegisterFile(attachmentDocFile, attachmentPath)
        
        LogUtils.d(TAG, "Attachment file path: $attachmentPath")
        return File(attachmentPath)
    }
    
    /**
     * Save attachment using regular File API for internal/local storage
     */
    private fun saveAttachmentUsingFileApi(
        bookFile: File,
        noteIdProperty: String,
        sharedFileUri: Uri
    ): File {
        LogUtils.d(TAG, "Saving attachment using File API")
        
        // Test write access to sync directory
        val parentDir = bookFile.parentFile
        val canWriteToSyncDir = testDirectoryWriteAccess(parentDir)
        
        val finalBookFile = if (!canWriteToSyncDir) {
            LogUtils.d(TAG, "Sync directory not writable, using app external directory")
            // Fallback to app's external directory
            val appExternalDir = context.getExternalFilesDir("orgzly-books")
                ?: context.filesDir
            val bookDirName = bookFile.nameWithoutExtension.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
            File(appExternalDir, "$bookDirName.org")
        } else {
            LogUtils.d(TAG, "Using sync directory for attachments")
            bookFile
        }
        
        // Get or create attachment directory for the note
        LogUtils.d(TAG, "Getting/creating attachment directory for noteIdProperty: $noteIdProperty")
        val attachmentDir = AttachmentManager.getOrCreateNoteAttachmentDir(finalBookFile, noteIdProperty)
        LogUtils.d(TAG, "Attachment directory: ${attachmentDir.absolutePath}")
        
        // Copy the shared file to attachment directory
        LogUtils.d(TAG, "Copying file from URI to attachment directory")
        val attachmentFile = AttachmentManager.copyUriToAttachment(
            context, 
            sharedFileUri, 
            attachmentDir
        )
        LogUtils.d(TAG, "File copied to: ${attachmentFile.absolutePath}")
        
        return attachmentFile
    }

    
    /**
     * Get the book file for attachment purposes.
     * For local files, tries to determine the actual file path.
     * For books without file paths (e.g., temporary books), creates a synthetic path.
     */
    private fun getBookFileForAttachments(dataRepository: DataRepository, bookView: BookView): File {
        LogUtils.d(TAG, "getBookFileForAttachments called")
        LogUtils.d(TAG, "BookView: name='${bookView.book.name}', syncedTo=${bookView.syncedTo?.uri}")
        
        // Try to determine the book file path
        // This is needed for creating attachments directories relative to the org file
        
        // If book has a synced location, try to use repo relative path
        if (bookView.syncedTo != null) {
            LogUtils.d(TAG, "Book has synced location: ${bookView.syncedTo!!.uri}")
            val repoRelativePath = BookName.getRepoRelativePath(bookView)
            LogUtils.d(TAG, "Repo relative path: '$repoRelativePath'")
            
            // For local file repositories, construct the full path
            val repo = bookView.linkRepo
            LogUtils.d(TAG, "LinkRepo: ${repo?.url}")
            if (repo != null) {
                when {
                    repo.url.startsWith("file:") -> {
                        val repoPath = repo.url.removePrefix("file:")
                        LogUtils.d(TAG, "Repo path after removing file: prefix: '$repoPath'")
                        val bookFile = File(repoPath, repoRelativePath)
                        LogUtils.d(TAG, "Constructed book file path: ${bookFile.absolutePath}")
                        
                        // For synced books, ensure the parent directory exists
                        val parentDir = bookFile.parentFile
                        if (parentDir != null) {
                            if (!parentDir.exists()) {
                                LogUtils.d(TAG, "Creating parent directory: ${parentDir.absolutePath}")
                                val created = parentDir.mkdirs()
                                LogUtils.d(TAG, "Parent directory creation result: $created")
                            }
                            LogUtils.d(TAG, "Using synced book file path: ${bookFile.absolutePath}")
                            return bookFile
                        } else {
                            LogUtils.d(TAG, "Cannot determine parent directory, will create synthetic path")
                        }
                    }
                    repo.url.startsWith("content://com.android.externalstorage.documents/tree/primary") -> {
                        // Handle document URIs for external storage
                        // URL format: content://com.android.externalstorage.documents/tree/primary%3Aorg
                        // After removePrefix: %3Aorg
                        // After decode: :org
                        // We want: org
                        val path = repo.url.removePrefix("content://com.android.externalstorage.documents/tree/primary")
                        val decodedPath = java.net.URLDecoder.decode(path, "UTF-8")
                        val cleanPath = if (decodedPath.startsWith(":")) decodedPath.substring(1) else decodedPath
                        val repoPath = "/storage/emulated/0/$cleanPath"
                        LogUtils.d(TAG, "Document URI path: '$path' -> decoded: '$decodedPath' -> clean: '$cleanPath' -> final: '$repoPath'")
                        val bookFile = File(repoPath, repoRelativePath)
                        LogUtils.d(TAG, "Constructed book file path from document URI: ${bookFile.absolutePath}")
                        
                        // For synced books, ensure the parent directory exists and is writable
                        val parentDir = bookFile.parentFile
                        if (parentDir != null) {
                            if (!parentDir.exists()) {
                                LogUtils.d(TAG, "Creating parent directory: ${parentDir.absolutePath}")
                                val created = parentDir.mkdirs()
                                LogUtils.d(TAG, "Parent directory creation result: $created")
                            }
                            
                            // Test if we can write to this directory by trying to create a attachments subdirectory
                            val attachDir = File(parentDir, "attachments")
                            val canWrite = try {
                                if (!attachDir.exists()) {
                                    attachDir.mkdirs()
                                } else {
                                    attachDir.canWrite()
                                }
                            } catch (e: Exception) {
                                LogUtils.d(TAG, "Cannot write to document URI directory: ${e.message}")
                                false
                            }
                            
                            if (canWrite) {
                                LogUtils.d(TAG, "Using synced book file path from document URI: ${bookFile.absolutePath}")
                                return bookFile
                            } else {
                                LogUtils.d(TAG, "Document URI directory not writable, falling back to app external directory")
                            }
                        } else {
                            LogUtils.d(TAG, "Cannot determine parent directory from document URI, will create synthetic path")
                        }
                    }
                    else -> {
                        LogUtils.d(TAG, "Unknown repo URL format: ${repo.url}, will create synthetic path")
                    }
                }
            } else {
                LogUtils.d(TAG, "Repo is null, will create synthetic path")
            }
        } else {
            LogUtils.d(TAG, "Book has no synced location, will create synthetic path")
        }
        
        // For books without a physical file location (like shared notes),
        // create a synthetic path in app's external files directory
        val appExternalDir = context.getExternalFilesDir("orgzly-books")
            ?: context.filesDir // fallback to internal storage if external not available
        
        // Create a directory based on the book name to organize attachments
        val bookDirName = bookView.book.name.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
        val syntheticBookFile = File(appExternalDir, "$bookDirName.org")
        
        LogUtils.d(TAG, "Using synthetic book file path: ${syntheticBookFile.absolutePath}")
        
        // Ensure the parent directory exists
        syntheticBookFile.parentFile?.mkdirs()
        
        return syntheticBookFile
    }
    
    /**
     * Extract hierarchical directory components from note ID.
     * Returns (prefix, shortenedId) for Emacs org-mode attachment directory structure.
     */
    private fun getHierarchicalDirComponents(noteIdProperty: String): Pair<String, String> {
        val segments = noteIdProperty.lowercase().split("-")
        val noteIdPrefix = if (segments.isNotEmpty() && segments[0].length >= 2) {
            segments[0].take(2)
        } else {
            noteIdProperty.lowercase().take(2)
        }
        val shortenedId = if (segments.isNotEmpty() && segments[0].length > 2) {
            segments[0].drop(2) + if (segments.size > 1) "-" + segments.drop(1).joinToString("-") else ""
        } else {
            noteIdProperty.lowercase()
        }
        return noteIdPrefix to shortenedId
    }
    
    /**
     * Copy file content from source URI to destination URI.
     */
    private fun copyFileContent(sourceUri: Uri, destUri: Uri, apiName: String) {
        try {
            context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                context.contentResolver.openOutputStream(destUri)?.use { outputStream ->
                    val bytesCopied = inputStream.copyTo(outputStream)
                    outputStream.flush()
                    if (BuildConfig.LOG_DEBUG) {
                        LogUtils.d(TAG, "Successfully copied $bytesCopied bytes via $apiName")
                    }
                }
            }
        } catch (e: Exception) {
            LogUtils.d(TAG, "Failed to copy file via $apiName: ${e.message}")
            throw e
        }
    }
    
    /**
     * Test write access to a directory.
     */
    private fun testDirectoryWriteAccess(parentDir: File?): Boolean {
        return if (parentDir != null) {
            try {
                val testAttachDir = File(parentDir, "attachments")
                val testFile = File(testAttachDir, "test.tmp")
                testAttachDir.mkdirs()
                val canWrite = testFile.createNewFile()
                if (canWrite) {
                    testFile.delete() // Clean up test file
                }
                if (BuildConfig.LOG_DEBUG) {
                    LogUtils.d(TAG, "Write test to sync directory: $canWrite")
                }
                canWrite
            } catch (e: Exception) {
                LogUtils.d(TAG, "Cannot write to sync directory: ${e.message}")
                false
            }
        } else {
            false
        }
    }
    
    /**
     * Log directory contents for debugging.
     */
    private fun logDirectoryContents(directory: DocumentFile, description: String) {
        try {
            val contents = directory.listFiles()
            LogUtils.d(TAG, "$description contains ${contents.size} items:")
            contents.forEach { item ->
                LogUtils.d(TAG, "  - ${item.name} (${if (item.isDirectory) "DIR" else "FILE"}): ${item.uri}")
            }
        } catch (e: Exception) {
            LogUtils.d(TAG, "Failed to list $description contents: ${e.message}")
        }
    }
    
    /**
     * Verify file was created and register with media scanner.
     */
    private fun verifyAndRegisterFile(documentFile: DocumentFile, filePath: String) {
        try {
            val fileExists = documentFile.exists()
            val fileLength = documentFile.length()
            val fileName = documentFile.name
            if (BuildConfig.LOG_DEBUG) {
                LogUtils.d(TAG, "File verification: exists=$fileExists, name='$fileName', size=$fileLength bytes")
            }
            
            // Trigger media scanner to make file visible
            android.media.MediaScannerConnection.scanFile(
                context,
                arrayOf(filePath),
                arrayOf("*/*"),
                null
            )
            
            if (BuildConfig.LOG_DEBUG) {
                // Test direct filesystem access
                val directFile = File(filePath)
                LogUtils.d(TAG, "Direct file system access: exists=${directFile.exists()}, parent exists=${directFile.parentFile?.exists()}")
            }
        } catch (e: Exception) {
            LogUtils.d(TAG, "File verification failed: ${e.message}")
        }
    }
}
