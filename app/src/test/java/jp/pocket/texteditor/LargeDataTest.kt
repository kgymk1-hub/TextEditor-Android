package jp.pocket.texteditor
import org.junit.Test
import org.junit.Assert.*
class LargeDataTest {
    @Test fun streamingCsvIndexesMultilineQuotesCrLfAndTrailingCells() {
        val text="a,b\r\n\"複数\r\n行\",\"二重\"\"引用\"\r\nlast,"
        val index=CsvIndex.build(text)
        assertEquals(3,index.size);assertEquals(listOf("複数\r\n行","二重\"引用"),index.row(1));assertEquals(listOf("last",""),index.row(2))
        assertEquals(listOf(1),index.matching("行"))
    }
    @Test fun largeCsvHasNoFixedRowOrCellLimit() {
        val index=CsvIndex.build("a,b,c\n".repeat(200_000))
        assertEquals(200_000,index.size);assertEquals(listOf("a","b","c"),index.row(199_999))
    }
    @Test fun twentyMiBCsvRetainsOnlyOffsetsAndReadsLastRow() {
        val row="value,"+"x".repeat(100)+"\n";val count=20*1024*1024/row.length
        val index=CsvIndex.build(row.repeat(count))
        assertEquals(count,index.size);assertEquals("value",index.row(count-1)[0])
    }
    @Test fun malformedCsvNeverLooksComplete(){assertThrows(IllegalArgumentException::class.java){CsvIndex.build("a,\"unclosed")}}
    @Test fun windowBoundsCoverEndAndNeverExceedSize(){val n=20*1024*1024;for(p in listOf(0,EditorWindow.SIZE,n-1,n)){val (a,b)=EditorWindow.bounds(n,p,0,0);assertTrue(a<=p && b>=p);assertTrue(b-a<=EditorWindow.SIZE)}}
    @Test fun substringTokensPreserveJapaneseAndLiteralPunctuation(){val tokens=BodyTokens.index("日本語 a.b C++");for(q in listOf("本語",".b","C+"))assertTrue(tokens.split(' ').contains(BodyTokens.query(q)))}

    @Test fun windowsNeverSplitSurrogatePairsOrCrLf(){for(boundary in listOf("😀","\r\n")){val text="a".repeat(EditorWindow.SIZE-1)+boundary+"x".repeat(2*1024*1024);val (_,end)=EditorWindow.bounds(text,0,0,0);assertEquals(EditorWindow.SIZE-1,end);val (start,_)=EditorWindow.bounds(text,EditorWindow.SIZE,0,0);assertEquals(EditorWindow.SIZE-1,start)}}
    @Test fun unicodeCaseCandidatesAgreeWithInsensitiveSearch(){for((a,b) in listOf("Σx" to "ςx","Ix" to "ıx","Sx" to "ſx")){assertTrue(SearchTools.matches(a,b,false,false).isNotEmpty());assertTrue(BodyTokens.index(a).split(' ').contains(BodyTokens.query(b)))}}
}
