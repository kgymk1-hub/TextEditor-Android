package jp.pocket.texteditor
import org.junit.Test
import org.junit.Assert.*
class SaveRulesTest {
    @Test fun editsDuringWriteRemainDirty() {
        val current=Document(kind="EXTERNAL",uri="content://source/a",revision=12,savedRevision=8)
        val result=SaveRules.committed(current,10,"verified-hash","snapshot.txt")
        assertEquals(12L,result.revision);assertEquals(10L,result.savedRevision);assertFalse(SaveRules.clean(result));assertEquals(current.uri,result.uri)
    }
    @Test fun verifiedExactRevisionIsClean() { val d=Document(revision=3,savedRevision=1);assertTrue(SaveRules.clean(SaveRules.committed(d,3,"hash","body"))) }
    @Test fun nativeUniformNewlineRoundTripAndAdditionalLines() {
        listOf("LF","CRLF","CR").forEach{nl ->
            val raw=TextCodec.convertNewlines("日本語\n本文",nl);val bytes=TextCodec.encode(raw,"UTF-8 BOM")
            val decoded=TextCodec.decode(bytes);val display=DocumentBytes.editorText(decoded)
            assertEquals("日本語\n本文",display.first);assertTrue(display.second)
            val doc=Document(encoding=decoded.encoding,newline=decoded.newline,normalizedNewlines=display.second)
            assertArrayEquals(bytes,DocumentBytes.encode(doc,display.first))
            assertEquals(TextCodec.convertNewlines("日本語\n本文\n追加",nl),TextCodec.decode(DocumentBytes.encode(doc,display.first+"\n追加")).text)
        }
    }
    @Test fun mixedNewlinesStayVerbatim() {val raw="a\r\nb\nc\rd";val decoded=TextCodec.decode(raw.toByteArray());val display=DocumentBytes.editorText(decoded);assertFalse(display.second);assertArrayEquals(raw.toByteArray(),DocumentBytes.encode(Document(newline=decoded.newline,normalizedNewlines=display.second),display.first))}
    @Test fun newerAcknowledgementIsRejected() {assertThrows(IllegalArgumentException::class.java){SaveRules.committed(Document(revision=2),3,"hash","body")}}
}
