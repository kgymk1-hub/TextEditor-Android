package jp.pocket.texteditor

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.io.*
import java.util.zip.*

object TextCodec {
    data class Decoded(val text: String, val encoding: String, val newline: String)
    fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun decode(bytes: ByteArray, requested: String? = null): Decoded {
        require(bytes.size <= 24 * 1024 * 1024) { "24MBを超えるファイルです。分割して開いてください" }
        if(bytes.size>5*1024*1024){val runtime=Runtime.getRuntime();val available=runtime.maxMemory()-(runtime.totalMemory()-runtime.freeMemory());require(bytes.size.toLong()*6<available){"端末のメモリが不足しています。ほかの文書を閉じるか、ファイルを分割してください"}}
        require(!bytes.take(8192).contains(0.toByte())) { "バイナリファイルは編集できません" }
        val bom = bytes.size >= 3 && bytes[0] == 0xef.toByte() && bytes[1] == 0xbb.toByte() && bytes[2] == 0xbf.toByte()
        val encoding = if (bom) "UTF-8 BOM" else requested ?: "UTF-8"
        val raw = if (bom) bytes.copyOfRange(3, bytes.size) else bytes
        val charset = Charset.forName(if (encoding == "UTF-8 BOM") "UTF-8" else encoding)
        val text = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString()
        val newline = when { text.contains("\r\n") -> "CRLF"; text.contains('\r') -> "CR"; else -> "LF" }
        // Preserve mixed line endings and trailing newline exactly until explicit conversion.
        return Decoded(text, encoding, newline)
    }
    fun encode(text: String, encoding: String): ByteArray {
        val charset = Charset.forName(if (encoding == "UTF-8 BOM") "UTF-8" else encoding)
        val buffer = charset.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text))
        val result = ByteArray(buffer.remaining()).also { buffer.get(it) }
        return if (encoding == "UTF-8 BOM") byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + result else result
    }
    fun convertNewlines(text: String, newline: String): String = text.replace("\r\n", "\n").replace('\r', '\n').replace("\n", when(newline) { "CRLF" -> "\r\n"; "CR" -> "\r"; else -> "\n" })
}

object CsvParser {
    fun parse(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var cells=0
        var row = mutableListOf<String>(); val cell = StringBuilder(); var quoted = false; var i = 0
        fun finishCell() { require(++cells<=500_000){"CSVのセル数が資源上限を超えました。分割して閲覧してください"}; row.add(cell.toString()); cell.setLength(0) }
        fun finishRow() { finishCell(); rows.add(row); row = mutableListOf() }
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' && quoted && i + 1 < text.length && text[i + 1] == '"' -> { cell.append('"'); i++ }
                c == '"' && (quoted || cell.isEmpty()) -> quoted = !quoted
                c == ',' && !quoted -> finishCell()
                (c == '\n' || c == '\r') && !quoted -> { finishRow(); if(c == '\r' && i+1 < text.length && text[i+1] == '\n') i++ }
                else -> cell.append(c)
            }; i++
        }
        require(!quoted) { "CSVの引用符が閉じていません" }
        if(cell.isNotEmpty() || row.isNotEmpty() || text.isEmpty() || text.last() == ',') finishRow()
        return rows
    }
}

object SafeZip {
    const val MAX_TOTAL = 64L * 1024 * 1024
    const val MAX_ENTRY = 24L * 1024 * 1024
    const val MAX_ENTRIES = 2048
    fun safePath(path: String): String {
        require(path.isNotBlank() && !path.startsWith('/') && !path.contains('\\') && !Regex("^[A-Za-z]:").containsMatchIn(path)) { "ZIP内の不正なパス: $path" }
        require(path.split('/').none { it == ".." || it == "." } && !path.contains('\u0000')) { "ZIP内のパス逸脱を拒否しました" }
        return path
    }
    fun read(input: InputStream): LinkedHashMap<String, ByteArray> {
        val files = linkedMapOf<String, ByteArray>(); var total = 0L; var count = 0
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while(true) {
                val e = zip.nextEntry ?: break; require(++count <= MAX_ENTRIES) { "ZIP内のファイル数が多すぎます" }; safePath(e.name)
                if(!e.isDirectory) {
                    require(!files.containsKey(e.name)) { "ZIP内でファイル名が重複しています" }
                    val output = ByteArrayOutputStream(); val buffer = ByteArray(8192); var size = 0L
                    while(true) { val n = zip.read(buffer); if(n < 0) break; size += n; total += n
                        require(size <= MAX_ENTRY && total <= MAX_TOTAL) { "ZIPの展開サイズ制限を超えました" }; output.write(buffer, 0, n)
                    }
                    require(e.compressedSize <= 0 || size <= 1024*1024 || size / e.compressedSize <= 200) { "異常なZIP圧縮率です" }
                    files[e.name] = output.toByteArray()
                }; zip.closeEntry()
            }
        }; return files
    }
    fun write(files: List<Pair<String, ByteArray>>, output: OutputStream) {
        require(files.size <= MAX_ENTRIES && files.sumOf { it.second.size.toLong() } <= MAX_TOTAL) { "ZIPのサイズ制限を超えました" }
        val paths = files.map { safePath(it.first) }; require(paths.distinct().size == paths.size) { "同名パスがあります。名前を変更してください" }
        ZipOutputStream(BufferedOutputStream(output)).use { zip -> files.forEach { (path, bytes) -> zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry() } }
    }
}

object SearchTools {
    fun regex(query: String, case: Boolean, regex: Boolean): Regex = Regex(if(regex) query else Regex.escape(query), if(case) emptySet() else setOf(RegexOption.IGNORE_CASE))
    fun matches(text: String, query: String, case: Boolean, regex: Boolean): List<IntRange> = if(query.isEmpty()) emptyList() else regex(query, case, regex).findAll(text).take(100_000).map { it.range }.toList()
    fun combined(docs: List<Pair<String, String>>): String = docs.joinToString("\n\n") { (path, text) -> "===== $path =====\n$text" }
    fun diff(old: String, new: String): String {
        val a = old.lines(); val b = new.lines(); val out = StringBuilder(); val max = maxOf(a.size,b.size)
        for(i in 0 until max) { if(a.getOrNull(i) == b.getOrNull(i)) continue; out.append("@@ 行 ${i+1} @@\n"); a.getOrNull(i)?.let { out.append("- $it\n") }; b.getOrNull(i)?.let { out.append("+ $it\n") }; if(out.length > 500_000) { out.append("…差分が大きいため省略\n"); break } }; return out.toString().ifEmpty { "差分はありません" }
    }
}

/** A verified write acknowledges the captured revision only, even if typing continues. */
object SaveRules {
    fun committed(current: Document, writtenRevision: Long, hash: String, body: String): Document {
        require(writtenRevision <= current.revision)
        return current.copy(savedRevision=writtenRevision,savedHash=hash,savedBody=body,access="")
    }
    fun clean(document: Document): Boolean = document.revision == document.savedRevision
}

object DocumentBytes {
    /** Normalize uniform line endings for the native editor, preserving mixed files verbatim. */
    fun editorText(decoded: TextCodec.Decoded): Pair<String, Boolean> {
        val raw=decoded.text
        val withoutCrLf=raw.replace("\r\n","")
        val mixed=when(decoded.newline){"CRLF" -> withoutCrLf.contains('\r') || withoutCrLf.contains('\n');"CR" -> raw.contains('\n');else -> false}
        return if(mixed) raw to false else TextCodec.convertNewlines(raw,"LF") to true
    }
    fun encode(doc: Document, text: String): ByteArray = TextCodec.encode(if(doc.normalizedNewlines) TextCodec.convertNewlines(text,doc.newline) else text,doc.encoding)
}
