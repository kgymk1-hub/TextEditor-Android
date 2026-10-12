package jp.pocket.texteditor

import org.junit.Test
import org.junit.Assert.*

class PwaCompatibilityTest {
    @Test fun linkedPreviewKeepsHeadBodyPlacementAndLiteralScriptEnd() {
        val html="<HTML><HEAD><title>test</title></HEAD><BODY><div id='app'></div></BODY></HTML>"
        val js="const value = '</ScRiPt>'; const price = '$' + 1;"
        val output=LinkedHtml.build(html,listOf("STYLE.CSS" to "#app{color:red}","APP.JS" to js),true)
        assertTrue(output.indexOf("#app{color:red}")<output.indexOf("</HEAD>"))
        assertTrue(output.indexOf("const value")>output.indexOf("id='app'"))
        assertTrue(output.indexOf("const value")<output.indexOf("</BODY>"))
        assertTrue(output.contains("<\\/ScRiPt"));assertTrue(output.contains("const price = '$' + 1"))
    }
    @Test fun scriptsStayDisabledAndFragmentsCanStillLinkCss() {
        val output=LinkedHtml.build("<p>hello</p>",listOf("style.css" to "p{color:red}","app.js" to "alert(1)"),false)
        assertTrue(output.startsWith("<style"));assertTrue(output.contains("<p>hello</p>"));assertFalse(output.contains("alert(1)"))
    }
    @Test fun zipDuplicatesDoNotStealAnotherDocumentsExistingName() {
        assertEquals(listOf("dir/a.txt","dir/a-3.txt","dir/a-2.txt","dir/a-4.txt"),PwaCompatibility.uniqueZipPaths(listOf("dir/a.txt","dir/a.txt","dir/a-2.txt","dir/a.txt")))
    }
    @Test fun zipNamesRejectTraversalInsteadOfSanitizingItSilently() {
        assertThrows(IllegalArgumentException::class.java){PwaCompatibility.uniqueZipPaths(listOf("../a.txt"))}
    }
    @Test fun csvColumnCountHandlesQuotedCommasMultilineAndRaggedRows() {
        val index=CsvIndex.build("first,second\r\n\"a,b\",\"two\nlines\",extra\r\nlast")
        assertEquals(3,index.columns);assertEquals(3,index.size)
        assertEquals(listOf("a,b","two\nlines","extra"),index.row(1))
        assertEquals("A",PwaCompatibility.columnName(0));assertEquals("Z",PwaCompatibility.columnName(25));assertEquals("AA",PwaCompatibility.columnName(26));assertEquals("ZZ",PwaCompatibility.columnName(701))
    }
    @Test fun replacementUsesFullRegexContextAndPreservesLiteralDollar() {
        val result=SingleReplacement.calculate("prefix abc suffix",7,"(?<=prefix )(a)(bc)","\$2\$1",true,true)
        assertEquals("prefix bca suffix",result.text);assertEquals(10,result.cursor)
        assertEquals("prefix $ suffix",SingleReplacement.calculate("prefix abc suffix",7,"abc","$",true,false).text)
    }
    @Test fun staleSearchOffsetDoesNotReplaceAnotherMatch() {
        assertThrows(IllegalStateException::class.java){SingleReplacement.calculate("abc abc",2,"abc","x",true,false)}
    }
}
