package jp.pocket.texteditor
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class NativeEditorTest {
    private fun editor(text:String,cursor:Int=0)=NativeEditor(ApplicationProvider.getApplicationContext()).apply { apply(Session(Document(cursor=cursor,selection=cursor),text),Preferences(),false) }
    @Test fun japaneseSelectionAndSymbolInsertion() {val e=editor("日本語の文章",3);e.setSelection(0,3);e.insert("東京");assertEquals("東京の文章",e.text.toString());assertEquals(2,e.selectionStart)}
    @Test fun undoRedoRestoresText() {val e=editor("本文",2);e.insert("追加");assertEquals("本文追加",e.text.toString());e.undo();assertEquals("本文",e.text.toString());e.redo();assertEquals("本文追加",e.text.toString())}
    @Test fun cursorSupportsLineBoundaries() {val e=editor("first\n日本語\nthird",8);e.lineBoundary(false);assertEquals(6,e.selectionStart);e.lineBoundary(true);assertEquals(9,e.selectionStart);e.move(-1,true);assertEquals(9,e.selectionStart);assertEquals(8,e.selectionEnd)}
    @Test fun indentSelectionAndRemoveIndent(){val e=editor("a\nb\n",0);e.setSelection(0,3);e.lineAction("字下げ+");assertEquals("    a\n    b\n",e.text.toString());e.lineAction("字下げ−");assertEquals("a\nb\n",e.text.toString())}
}
