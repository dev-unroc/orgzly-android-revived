package com.orgzly.android.usecase

import com.orgzly.android.App
import com.orgzly.android.BookName
import com.orgzly.android.data.DataRepository
import com.orgzly.android.db.entity.BookView
import com.orgzly.android.prefs.AppPreferences
import com.orgzly.android.util.AttachmentManager
import java.io.File
import java.net.URLDecoder

class LinkFindTarget(val path: String, val noteId: String? = null, val bookFile: File? = null) : UseCase() {
    val context = App.getAppContext();

    override fun run(dataRepository: DataRepository): UseCaseResult {
        val target = openLink(dataRepository, path)

        return UseCaseResult(
                userData = target
        )
    }

    private fun openLink(dataRepository: DataRepository, path: String): Any {
        // First check if this is an attachment link
        // if (path.startsWith("attachment:") || path.contains(".attach/")) {
        if (path.startsWith("attachment:")) {
            // If we have book and note context, use it
            bookFile?.let { book ->
                val attachmentFile = AttachmentManager.resolveAttachmentLink(book, noteId, path)
                if (attachmentFile != null) {
                    return attachmentFile
                }
            }
            
            // If no context provided, try to find any note containing this attachment link
            val attachmentFile = findAttachmentFromDatabase(dataRepository, path)
            if (attachmentFile != null) {
                return attachmentFile
            }
        }

        // Don't treat attachment links as regular file paths - this prevents
        // creating incorrect paths like "/storage/emulated/0/attachment:filename"
        if (path.startsWith("attachment:")) {
            val filename = path.substring("attachment:".length)
            // Return a File that doesn't exist to trigger a "file not found" error with a more descriptive path
            return File("Attachment not found: $filename (note context missing)")
        }

        return if (isAbsolute(path)) {
            File(AppPreferences.fileAbsoluteRoot(context), path)
        } else {
            isMaybeBook(path)?.let { bookName ->
                dataRepository.getBook(bookName.name)?.let {
                    return it
                }
            }

            File(AppPreferences.fileRelativeRoot(context), path)
        }
    }

    private fun isAbsolute(path: String): Boolean {
        return path.startsWith('/')
    }

    private fun isMaybeBook(path: String): BookName? {
        val file = File(path)

        return if (!hasParent(file) && BookName.isSupportedFormatFileName(file.name)) {
            BookName.fromRepoRelativePath(file.name)
        } else {
            null
        }
    }

    private fun hasParent(file: File): Boolean {
        val parentFile = file.parentFile
        return parentFile != null && parentFile.name != "."
    }
    
    /**
     * Try to find an attachment by searching the file system and database.
     * This is a fallback when we don't have note/book context.
     * First tries to find notes containing this attachment link and extract their ID properties.
     */
    private fun findAttachmentFromDatabase(dataRepository: DataRepository, path: String): File? {
        if (!path.startsWith("attachment:")) {
            return null
        }
        
        val filename = path.substring("attachment:".length)
        android.util.Log.d("LinkFindTarget", "findAttachmentFromDatabase: Looking for attachment file: $filename")
        
        // Search for attachment files in common locations
        val result = findAttachmentInFilesystem(filename)
        android.util.Log.d("LinkFindTarget", "findAttachmentFromDatabase: Found file: ${result?.absolutePath ?: "null"}")
        return result
    }
    
    /**
     * Search the filesystem for attachment files when database search fails.
     */
    private fun findAttachmentInFilesystem(filename: String): File? {
        android.util.Log.d("LinkFindTarget", "findAttachmentInFilesystem: Searching for file: $filename")
        
        // Try to find attachment files in common locations
        val appExternalDir = context.getExternalFilesDir("orgzly-books")?.absolutePath
        val appInternalDir = context.filesDir.absolutePath
        
        val commonRoots = listOfNotNull(
            AppPreferences.fileRelativeRoot(context),
            AppPreferences.fileAbsoluteRoot(context),
            appExternalDir, // App's external files directory where attachments are now stored
            appInternalDir, // App's internal files directory as fallback
            "/sdcard/orgzly",
            "/storage/emulated/0/orgzly",
            "/storage/emulated/0/org" // Add the common external storage location
        )
        
        android.util.Log.d("LinkFindTarget", "findAttachmentInFilesystem: Will search in ${commonRoots.size} root directories")
        
        for (rootPath in commonRoots) {
            android.util.Log.d("LinkFindTarget", "findAttachmentInFilesystem: Checking root: $rootPath")
            val rootDir = File(rootPath)
            if (rootDir.exists() && rootDir.isDirectory) {
                // Look for attachments directories recursively
                val attachDirs = findAttachDirectories(rootDir)
                for (attachDir in attachDirs) {
                    android.util.Log.d("LinkFindTarget", "findAttachmentInFilesystem: Searching in attachment dir: ${attachDir.absolutePath}")
                    // Look for the file in all note ID subdirectories
                    attachDir.listFiles()?.forEach { noteDir ->
                        if (noteDir.isDirectory) {
                            val attachmentFile = File(noteDir, filename)
                            android.util.Log.d("LinkFindTarget", "findAttachmentInFilesystem: Checking: ${attachmentFile.absolutePath}")
                            if (attachmentFile.exists()) {
                                android.util.Log.d("LinkFindTarget", "findAttachmentInFilesystem: FOUND: ${attachmentFile.absolutePath}")
                                return attachmentFile
                            }
                        }
                    }
                }
            } else {
                android.util.Log.d("LinkFindTarget", "findAttachmentInFilesystem: Root does not exist or is not directory: $rootPath")
            }
        }
        
        android.util.Log.d("LinkFindTarget", "findAttachmentInFilesystem: File not found: $filename")
        return null
    }
    
    /**
     * Recursively find all attachments directories in a given root directory.
     */
    private fun findAttachDirectories(rootDir: File): List<File> {
        val attachDirs = mutableListOf<File>()
        android.util.Log.d("LinkFindTarget", "findAttachDirectories: Searching in: ${rootDir.absolutePath}")
        
        rootDir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                // if (file.name == "attachments" || file.name == ".attach") {
                if (file.name == "attachments") {
                    android.util.Log.d("LinkFindTarget", "findAttachDirectories: Found attachment directory: ${file.absolutePath}")
                    attachDirs.add(file)
                } else {
                    // Recursively search subdirectories (but limit depth to avoid infinite loops)
                    if (file.absolutePath.split(File.separator).size < rootDir.absolutePath.split(File.separator).size + 3) {
                        attachDirs.addAll(findAttachDirectories(file))
                    }
                }
            }
        }
        
        android.util.Log.d("LinkFindTarget", "findAttachDirectories: Found ${attachDirs.size} attachment directories in ${rootDir.absolutePath}")
        return attachDirs
    }
    
    /**
     * Get book file for attachments - similar logic to AttachmentSaveFiles.
     * This determines the base directory where .attach folders should be located.
     */
    private fun getBookFileForAttachments(dataRepository: DataRepository, bookView: BookView): File {
        // Try to determine the book file path
        // This is needed for creating attachments directories relative to the org file
        
        // If book has a synced location, try to use repo relative path
        if (bookView.syncedTo != null) {
            val repoRelativePath = BookName.getRepoRelativePath(bookView)
            
            // For local file repositories, construct the full path
            val repo = bookView.linkRepo
            if (repo != null) {
                when {
                    repo.url.startsWith("file:") -> {
                        val repoPath = repo.url.removePrefix("file:")
                        val bookFile = File(repoPath, repoRelativePath)
                        return bookFile
                    }
                    repo.url.startsWith("content://com.android.externalstorage.documents/tree/primary") -> {
                        // Handle document URIs for external storage
                        val path = repo.url.removePrefix("content://com.android.externalstorage.documents/tree/primary")
                        val decodedPath = URLDecoder.decode(path, "UTF-8")
                        val cleanPath = if (decodedPath.startsWith(":")) decodedPath.substring(1) else decodedPath
                        val repoPath = "/storage/emulated/0/$cleanPath"
                        val bookFile = File(repoPath, repoRelativePath)
                        return bookFile
                    }
                }
            }
        }
        
        // Fallback to app external directory
        val appExternalDir = context.getExternalFilesDir("orgzly-books") ?: context.filesDir
        val bookDirName = bookView.book.name.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
        return File(appExternalDir, "$bookDirName.org")
    }
}
