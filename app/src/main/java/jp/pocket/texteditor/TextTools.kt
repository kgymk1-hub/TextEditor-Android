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

/** Index offsets only; cells are decoded for the visible row, never for the whole table. */
class CsvIndex private constructor(private val text: String, private val starts: IntArray, private val ends: IntArray, val columns:Int) {
    val size: Int get() = starts.size
    fun row(index: Int): List<String> = CsvParser.parseRow(text, starts[index], ends[index])
    fun matching(query: String): List<Int> = (0 until size).filter { if(Thread.currentThread().isInterrupted)throw java.util.concurrent.CancellationException(); query.isBlank() || row(it).any { cell -> cell.contains(query, true) } }
    companion object {
        fun build(text: String): CsvIndex {
            val starts = ArrayList<Int>(); val ends = ArrayList<Int>()
            var start = 0; var i = 0; var quoted = false; var cellStart = true;var columns=1;var maxColumns=1
            while(i < text.length) {
                if(Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException()
                val c = text[i]
                when {
                    c == '"' && quoted && i+1 < text.length && text[i+1] == '"' -> i++
                    c == '"' && (quoted || cellStart) -> { quoted = !quoted; cellStart = false }
                    c == ',' && !quoted -> {cellStart = true;columns++}
                    (c == '\r' || c == '\n') && !quoted -> {
                        starts.add(start); ends.add(i);maxColumns=maxOf(maxColumns,columns);columns=1
                        if(c == '\r' && i+1 < text.length && text[i+1] == '\n') i++
                        start = i+1; cellStart = true
                    }
                    else -> cellStart = false
                }
                i++
            }
            require(!quoted) { "CSVの引用符が閉じていません" }
            if(start < text.length || text.isEmpty()) { starts.add(start); ends.add(text.length) }
            return CsvIndex(text, starts.toIntArray(), ends.toIntArray(),maxOf(maxColumns,columns))
        }
    }
}
object CsvParser {
    fun parse(text: String): List<List<String>> = CsvIndex.build(text).let { index -> (0 until index.size).map(index::row) }
    internal fun parseRow(text: String, start: Int, end: Int): List<String> {
        val cells = ArrayList<String>(); val cell = StringBuilder(); var quoted = false; var i = start
        while(i < end) {
            val c = text[i]
            when {
                c == '"' && quoted && i+1 < end && text[i+1] == '"' -> { cell.append('"'); i++ }
                c == '"' && (quoted || cell.isEmpty()) -> quoted = !quoted
                c == ',' && !quoted -> { cells.add(cell.toString()); cell.setLength(0) }
                else -> cell.append(c)
            }; i++
        }
        cells.add(cell.toString()); return cells
    }
}

object SafeZip {
    const val MAX_TOTAL = 64L * 1024 * 1024
    const val MAX_ENTRY = 24L * 1024 * 1024
    const val MAX_ENTRIES = 2048
    fun safePath(path: String): String {
        require(path.isNotBlank() && !path.startsWith('/') && !path.contains('\\') && !Regex("^[A-Za-z]:").containsMatchIn(path)) { "ZIP内の不正なパス: $path" }
        require(path.removeSuffix("/").split('/').none { it.isEmpty() || it == ".." || it == "." } && !path.contains('\u0000')) { "ZIP内のパス逸脱を拒否しました" }
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
        require(files.all{it.second.size.toLong()<=MAX_ENTRY}){"ZIP内の単体ファイルが24MiBを超えています"}
        val paths = files.map { require(!it.first.endsWith('/')){"ファイルのパスはディレクトリ名にできません"};safePath(it.first) }; require(paths.distinct().size == paths.size) { "同名パスがあります。名前を変更してください" }
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

/** Literal substring candidates across Japanese and Latin text; exact matching remains authoritative. */
object BodyTokens {
    private fun token(a: Char, b: Char) = "t" + a.code.toString(16).padStart(4,'0') + b.code.toString(16).padStart(4,'0')
    fun index(text: String): String {
        if(text.length>1024*1024)return ""
        val tokens=LinkedHashSet<String>()
        for(i in 0 until text.length-1) {
            tokens.add(token(text[i].uppercaseChar().lowercaseChar(),text[i+1].uppercaseChar().lowercaseChar()))
            // Empty marks documents requiring a scan; never silently exclude them.
            if(tokens.size>50_000) return ""
        }
        return tokens.joinToString(" ")
    }
    fun query(text: String): String? = if(text.length<2 || text.take(2).any{Character.isSurrogate(it)}) null else token(text[0].uppercaseChar().lowercaseChar(),text[1].uppercaseChar().lowercaseChar())
}

/** A bounded native edit window; immutable full-document snapshots remain the save authority. */
object EditorWindow {
    const val SIZE=128*1024
    fun bounds(text:String,cursor:Int,start:Int,end:Int):Pair<Int,Int> {
        val original=bounds(text.length,cursor,start,end)
        var left=original.first;var right=original.second
        if(left>0 && left<text.length && (Character.isLowSurrogate(text[left]) && Character.isHighSurrogate(text[left-1]) || text[left]=='\n' && text[left-1]=='\r'))left--
        right=minOf(right,left+SIZE)
        if(text.length<=1024*1024)return original
        if(right<text.length && right>left && (Character.isHighSurrogate(text[right-1]) && Character.isLowSurrogate(text[right]) || text[right-1]=='\r' && text[right]=='\n'))right--
        return left to right
    }
    fun bounds(length:Int,cursor:Int,start:Int,end:Int):Pair<Int,Int> {
        if(length<=1024*1024) return 0 to length
        if(end>start && cursor>=start && cursor<end && end<=length) return start to end
        val left=(cursor/SIZE*SIZE).coerceAtMost((length-1).coerceAtLeast(0))
        return left to (left+SIZE).coerceAtMost(length)
    }
}

data class TextMetrics(val lines:Int,val line:Int,val column:Int) {
    companion object {
        fun calculate(text:String,cursor:Int):TextMetrics {
            val position=cursor.coerceIn(0,text.length);var lines=1;var line=1;var previous=-1
            for(i in text.indices) {
                if(i%8192==0 && Thread.currentThread().isInterrupted)throw java.util.concurrent.CancellationException()
                if(text[i]=='\n') {lines++;if(i<position){line++;previous=i}}
            }
            return TextMetrics(lines,line,position-previous)
        }
    }
}
