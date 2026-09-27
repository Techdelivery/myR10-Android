package com.techdelivery.r10.data

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Atomic per-row rewriting of a shot CSV (DESIGN §8, ROADMAP R5/R6).
 *
 * Editing one row means rewriting the file, so the interesting part is not the
 * transformation but making it **all-or-nothing**: write a sibling temp file, fsync
 * it, then rename over the original. A process kill then leaves either the previous
 * file or the complete new one, never a half-written history — which is the whole
 * reason mutations go through here instead of an in-place edit.
 *
 * The transform gets the raw line and returns the replacement, or null to drop the
 * row. It works on **text**, not decoded shots, so a row this build cannot parse
 * survives an edit that did not concern it instead of being dropped.
 */
internal class ShotCsvRewriter(private val file: File) {

    /**
     * Apply [transform] to every data row in one atomic rewrite.
     * Returns true when at least one row changed, i.e. when a write happened.
     *
     * [header] replaces the file's header line when given — that is how a schema
     * migration moves a v1 file to the current header. Without it the existing
     * header is preserved, so a club edit never silently changes the file's shape.
     */
    fun rewrite(transform: (String) -> String?, header: String? = null): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        val pass = Pass()
        file.readLines().forEach { applyLine(it.trim(), pass, header, transform) }
        if (!pass.changed) return false
        writeAtomically(pass.lines.joinToString(separator = "\n", postfix = "\n"))
        return true
    }

    /** Running state of one rewrite pass. */
    private class Pass {
        val lines = ArrayList<String>()
        var changed = false
        var headerWritten = false
    }

    /**
     * Fold one line of the file into [pass].
     *
     * Handles a header swap, a blank line, a data row (through [transform]) or an
     * unrecognised line kept verbatim. Kept out of the loop so the loop has no
     * branching of its own — the rules live in one readable place instead of being
     * spread across continue statements.
     */
    private fun applyLine(line: String, pass: Pass, header: String?, transform: (String) -> String?) {
        if (line.isEmpty()) {
            pass.lines.add(line)
            return
        }
        if (line == ShotCsvFormat.HEADER || line == ShotCsvFormat.HEADER_V1) {
            val replacement = header?.takeUnless { pass.headerWritten }
            if (replacement == null) {
                pass.lines.add(line)
                return
            }
            pass.headerWritten = true
            if (replacement != line) pass.changed = true
            pass.lines.add(replacement)
            return
        }
        val replaced = transform(line)
        if (replaced == null || replaced != line) pass.changed = true
        if (replaced != null) pass.lines.add(replaced)
    }

    private fun writeAtomically(text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        RandomAccessFile(tmp, "rwd").use { raf ->
            raf.write(text.toByteArray(Charsets.UTF_8))
            raf.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            // Rename can fail when the destination already exists on some
            // filesystems, so fall back to delete-then-rename before giving up.
            file.delete()
            if (!tmp.renameTo(file)) {
                tmp.delete()
                throw IOException("could not replace ${file.absolutePath}")
            }
        }
    }
}
