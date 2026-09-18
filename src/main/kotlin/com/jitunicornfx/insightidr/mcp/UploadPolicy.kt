package com.jitunicornfx.insightidr.mcp

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Which local files `upload_attachment` may read, and how much of one.
 *
 * That tool takes a path from the model and sends the file's bytes to Rapid7. With no limit on the
 * path, a prompt injection in a log line can ask for `~/.ssh/id_rsa`, the browser's cookie store or
 * this server's own environment, and have it attached to an investigation the attacker can read.
 * Refusing UNC paths stops one trick (forced SMB authentication); it does nothing about that.
 *
 * So uploading is **off until the operator names a directory** ([Config.ENV_UPLOAD_DIR]), and then
 * only files inside that directory can be read. Containment is checked twice:
 *
 *  1. lexically, on the normalized path, BEFORE the filesystem is touched — so a path outside the
 *     directory is refused without revealing whether anything exists there;
 *  2. on the real path, after symbolic links and junctions are resolved — so a link inside the
 *     directory cannot point the read somewhere else.
 *
 * What this does not defend against is someone who can already write to the upload directory and
 * swaps a path component for a link between the check and the read. Java has no `openat`/`O_BENEATH`
 * to close that. The directory is the operator's to protect; this keeps the MODEL inside it.
 *
 * Process-wide and installed once from Main, like [ResultBudget] and [SpoolStore].
 */
class UploadPolicy(
    /** The only directory files may be read from. Null means uploading is disabled. */
    val directory: Path?,
    val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    val enabled: Boolean get() = directory != null

    /**
     * Resolve [requested] to the real file it names inside [directory].
     *
     * A relative path is taken relative to the upload directory. No message repeats the path: it is
     * model-supplied, and these messages are written in the server's own voice.
     *
     * @throws IllegalStateException if uploading is disabled.
     * @throws IllegalArgumentException if the path is outside the directory, missing, or not a file.
     */
    fun resolve(requested: String): Path {
        val root = directory ?: throw IllegalStateException(DISABLED_MESSAGE)
        // Before ANY filesystem access: a UNC path makes Windows open an SMB connection the moment a
        // File API touches it, handing the host's NetNTLM credentials to whoever runs that server.
        requireLocalFilePath(requested)

        val lexical = try {
            root.resolve(requested).normalize()
        } catch (e: java.nio.file.InvalidPathException) {
            throw IllegalArgumentException("'file_path' is not a valid path.")
        }
        require(lexical.startsWith(root.normalize())) { OUTSIDE_MESSAGE }

        val realRoot = try {
            root.toRealPath()
        } catch (e: IOException) {
            throw IllegalArgumentException("The upload directory (${Config.ENV_UPLOAD_DIR}) does not exist or cannot be read.")
        }
        val real = try {
            lexical.toRealPath()
        } catch (e: IOException) {
            throw IllegalArgumentException("No such file in the upload directory.")
        }
        // A symbolic link or junction inside the directory can name a file outside it.
        require(real.startsWith(realRoot)) { OUTSIDE_MESSAGE }
        require(Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) { "'file_path' is not a regular file." }
        return real
    }

    /**
     * Read [file], refusing anything over [maxBytes].
     *
     * The limit is enforced on the bytes actually READ, not on a size looked up beforehand: a file can
     * grow between the two, and `length()` followed by `readBytes()` would then read all of it. The
     * size check first is only there to refuse an obviously huge file without reading 100 MiB of it.
     */
    fun readCapped(file: Path): ByteArray {
        require(Files.size(file) <= maxBytes) { tooLarge() }
        return Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use(::readAtMost)
    }

    /** Everything in [input], or a refusal if there is more than [maxBytes] of it. */
    internal fun readAtMost(input: InputStream): ByteArray {
        // One byte past the limit is enough to know the limit was passed.
        val bytes = input.readNBytes(Math.toIntExact(maxBytes + 1))
        require(bytes.size <= maxBytes) { tooLarge() }
        return bytes
    }

    private fun tooLarge() = "The file exceeds the ${maxBytes / (1024 * 1024)} MiB upload limit."

    companion object {
        /** 100 MiB. The file is held in memory while it is sent. */
        const val DEFAULT_MAX_BYTES: Long = 100L * 1024 * 1024

        const val DISABLED_MESSAGE =
            "upload_attachment is disabled on this server. It reads a file from the machine running the " +
                "server and sends it to Rapid7, so it only works once the operator names the one directory " +
                "it may read from, by setting ${Config.ENV_UPLOAD_DIR}. Ask them to set it; do not look for " +
                "another way to send the file."

        const val OUTSIDE_MESSAGE =
            "Refusing to read a file outside the upload directory. Only files inside the directory the " +
                "operator configured (${Config.ENV_UPLOAD_DIR}) can be uploaded."

        /** Uploading disabled. What a server reports until the operator opts in. */
        val DISABLED = UploadPolicy(directory = null)

        @Volatile
        private var installed: UploadPolicy = DISABLED

        /** The process-wide policy. */
        val active: UploadPolicy get() = installed

        /** Install the configured policy. Called once from Main before anything is served. */
        fun install(policy: UploadPolicy) {
            installed = policy
        }

        /** The policy for a configured directory, or [DISABLED] when none was given. */
        fun resolve(configured: String?): UploadPolicy =
            if (configured == null) DISABLED else UploadPolicy(File(configured).toPath().toAbsolutePath())

        /**
         * Reject UNC / remote / device-namespace paths.
         *
         * Any path beginning with two separators is UNC (`\\host\share`, `//host/share`) or the
         * Windows device namespace (`\\?\`, `\\.\`). None is a file in a local upload directory.
         */
        internal fun requireLocalFilePath(path: String) {
            val trimmed = path.trimStart()
            require(!(trimmed.startsWith("\\\\") || trimmed.startsWith("//"))) {
                "Refusing to read a UNC, remote or device path. Give a path inside the upload directory."
            }
        }
    }
}
