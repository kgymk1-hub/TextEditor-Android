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

    @Test fun largeWindowEditsPreserveOtherPagesAndUndoAcrossPages() {
        val original="a".repeat(2*1024*1024)+"終端"
        val e=editor(original);var full=original
        e.onEdit={text,_,_,_->full=text}
        assertTrue(e.text.length<=EditorWindow.SIZE)
        e.insert("日本語")
        assertEquals("日本語"+original,full)
        e.page(1);e.insert("別ページ")
        assertTrue(full.contains("別ページ"))
        e.undo();assertEquals("日本語"+original,full)
        e.undo();assertEquals(original,full)
        e.redo();assertEquals("日本語"+original,full)
        e.redo();assertTrue(full.contains("別ページ"));assertTrue(full.endsWith("終端"))
    }
    @Test fun twentyMiBUsesBoundedNativeWindowAndCanReachEnd() {
        val original="x".repeat(20*1024*1024)+"末尾"
        val e=editor(original,original.length);var full=original;e.onEdit={text,_,_,_->full=text}
        assertTrue(e.text.length<=EditorWindow.SIZE)
        e.insert("追加");assertEquals(original+"追加",full);e.undo();assertEquals(original,full)
    }

    @Test fun groupedLargeEditsUndoChangedWindowLengthWithoutLosingSuffix() {
        val original="a".repeat(2*1024*1024)+"末尾";val e=editor(original);var full=original
        e.onEdit={value,_,_,_->full=value};e.insert("one");e.insert("two");e.undo()
        assertEquals(TextCodec.hash(original.toByteArray()),TextCodec.hash(full.toByteArray()))
        e.redo();assertEquals(TextCodec.hash(("onetwo"+original).toByteArray()),TextCodec.hash(full.toByteArray()))
    }

    @Test fun composingJapaneseTextCommitsWithoutDuplicatingOrDroppingBody() {
        val e=editor("既存本文",4);e.requestFocus()
        val connection=e.onCreateInputConnection(android.view.inputmethod.EditorInfo())!!
        connection.setComposingText("にほ",1);connection.setComposingText("にほんご",1)
        connection.commitText("日本語",1);connection.finishComposingText()
        assertEquals("既存本文日本語",e.text.toString())
    }

    @Test fun pageNavigationMovesPastEmojiAtWindowBoundary(){val original="a".repeat(EditorWindow.SIZE-1)+"😀"+"x".repeat(2*1024*1024);val e=editor(original);e.page(1);assertTrue(e.text.toString().startsWith("😀"));e.page(-1);assertTrue(e.text.toString().startsWith("aaa"))}

    @Test fun cursorMovesAcrossWindowsWithoutSplittingEmoji(){val original="a".repeat(EditorWindow.SIZE-1)+"😀"+"x".repeat(2*1024*1024);val e=editor(original,EditorWindow.SIZE-1);var cursor=0;e.onEdit={_,position,_,_->cursor=position};e.move(1);assertEquals(EditorWindow.SIZE+1,cursor);assertFalse(Character.isLowSurrogate(e.text[0]));e.move(-1);assertEquals(EditorWindow.SIZE-1,cursor)}

    @Test fun undoRedoAcrossLargeModeThresholdRestoresWholeDocument(){val original="a".repeat(1024*1024-1);val e=editor(original,original.length);var full=original;e.onEdit={value,_,_,_->full=value};e.insert("xyz");e.undo();assertEquals(TextCodec.hash(original.toByteArray()),TextCodec.hash(full.toByteArray()));assertEquals(original.length,e.selectionStart);e.redo();assertEquals(TextCodec.hash((original+"xyz").toByteArray()),TextCodec.hash(full.toByteArray()));assertTrue(e.text.length<=EditorWindow.SIZE)}
}
