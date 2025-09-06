package com.orgzly.android.usecase

import com.orgzly.android.App
import com.orgzly.android.BookName
import com.orgzly.android.data.DataRepository
import com.orgzly.android.prefs.AppPreferences
import com.orgzly.android.util.AttachmentManager
import java.io.File

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
        if (path.startsWith("attachment:") || path.contains(".attach/")) {
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
     * Try to find an attachment by searching the file system.
     * This is a fallback when we don't have note/book context.
     * We look for .attach directories in common locations.
     */
    private fun findAttachmentFromDatabase(dataRepository: DataRepository, path: String): File? {
        if (!path.startsWith("attachment:")) {
            return null
        }
        
        val filename = path.substring("attachment:".length)
        
        // Try to find attachment files in common locations
        val appExternalDir = context.getExternalFilesDir("orgzly-books")?.absolutePath
        val appInternalDir = context.filesDir.absolutePath
        
        val commonRoots = listOfNotNull(
            AppPreferences.fileRelativeRoot(context),
            AppPreferences.fileAbsoluteRoot(context),
            appExternalDir, // App's external files directory where attachments are now stored
            appInternalDir, // App's internal files directory as fallback
            "/sdcard/orgzly",
            "/storage/emulated/0/orgzly"
        )
        
        for (rootPath in commonRoots) {
            val rootDir = File(rootPath)
            if (rootDir.exists() && rootDir.isDirectory) {
                // Look for .attach directories recursively
                val attachDirs = findAttachDirectories(rootDir)
                for (attachDir in attachDirs) {
                    // Look for the file in all note ID subdirectories
                    attachDir.listFiles()?.forEach { noteDir ->
                        if (noteDir.isDirectory) {
                            val attachmentFile = File(noteDir, filename)
                            if (attachmentFile.exists()) {
                                return attachmentFile
                            }
                        }
                    }
                }
            }
        }
        
        return null
    }
    
    /**
     * Recursively find all .attach directories in a given root directory.
     */
    private fun findAttachDirectories(rootDir: File): List<File> {
        val attachDirs = mutableListOf<File>()
        
        rootDir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                if (file.name == ".attach") {
                    attachDirs.add(file)
                } else {
                    // Recursively search subdirectories (but limit depth to avoid infinite loops)
                    if (file.absolutePath.split(File.separator).size < rootDir.absolutePath.split(File.separator).size + 3) {
                        attachDirs.addAll(findAttachDirectories(file))
                    }
                }
            }
        }
        
        return attachDirs
    }
}
