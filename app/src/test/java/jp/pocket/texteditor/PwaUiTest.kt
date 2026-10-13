package jp.pocket.texteditor

import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class PwaUiTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private lateinit var vm:EditorViewModel
    private val store=ViewModelStore()
    @Before fun setup() {
        val context=ApplicationProvider.getApplicationContext<Application>();context.deleteDatabase("editor.db")
        runBlocking{context.settings.edit{it.clear()}}
        val app=PocketApplication();ReflectionHelpers.callInstanceMethod<Unit>(app,"attach",ClassParameter.from(Context::class.java,context))
        vm=EditorViewModel(app);store.put("editor",vm)
        vm.setting("autosave",5000)
        vm.newMemo("<p>hello</p>","notes.txt")
        val deadline=System.nanoTime()+10_000_000_000
        while(vm.current()?.status!="保存済み" || vm.preferences.value.autosave!=5000){shadowOf(Looper.getMainLooper()).idle();check(System.nanoTime()<deadline);Thread.sleep(5)}
    }
    @After fun cleanup(){store.clear();ReflectionHelpers.getField<CoroutineScope>(vm,"scope").cancel();vm.repo.db.close()}
    private fun awaitModel(condition:()->Boolean){compose.waitUntil(10_000){shadowOf(Looper.getMainLooper()).idle();condition()}}
    @Test fun txtCanPreviewThenSearchAndReplaceInTheCorrectEditor() {
        compose.setContent{PocketApp(vm)}
        compose.onNodeWithText("HTML").performClick()
        awaitModel{vm.current()?.document?.mode=="HTML"}
        compose.onNodeWithText("⋮").performClick()
        compose.onNodeWithText("検索・履歴・比較 ›").performClick()
        compose.onNodeWithText("検索").performClick()
        awaitModel{vm.current()?.document?.mode=="編集"}
        compose.onNode(hasSetTextAction() and hasText("検索")).performTextInput("hello")
        compose.onNode(hasSetTextAction() and hasText("置換")).performTextInput("updated")
        compose.waitUntil(10_000){compose.onAllNodesWithText("1件").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("1件置換").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("1件置換").performScrollTo().assertIsDisplayed().performClick()
        awaitModel{vm.current()?.text=="<p>updated</p>"}
        compose.onNodeWithText("閉じる").performClick()
        compose.onNodeWithText("未保存 1件",substring=true).assertExists()
        compose.onNodeWithText("↶").performClick()
        awaitModel{vm.current()?.text=="<p>hello</p>"}
    }
    @Test fun quickThemeTogglePersistsBothThemesWithoutLosingText() {
        compose.setContent{PocketApp(vm)}
        compose.onNodeWithText("HTML").assertIsDisplayed()
        compose.onNodeWithContentDescription("ダークモードに切り替え").assertIsDisplayed().performClick()
        awaitModel{vm.preferences.value.theme=="ダーク"}
        compose.onNodeWithContentDescription("ライトモードに切り替え").assertIsDisplayed().performClick()
        awaitModel{vm.preferences.value.theme=="ライト"}
        assertEquals("<p>hello</p>",vm.current()!!.text)
    }
    @Test fun addTabAndCloseButtonsKeepTheirConfirmationFlow() {
        compose.setContent{PocketApp(vm)}
        compose.onNodeWithContentDescription("新しいタブを追加").performClick()
        awaitModel{vm.tabs.value.size==2 && vm.current()?.status=="保存済み"}
        compose.onNodeWithTag("tab-strip").performScrollToIndex(1)
        compose.onNodeWithContentDescription("無題.txtのタブを閉じる").performClick()
        compose.onNodeWithText("キャンセル").performClick()
        assertEquals(2,vm.tabs.value.size)
        compose.onNodeWithContentDescription("全タブを閉じる").performClick()
        compose.onNodeWithText("キャンセル").performClick()
        assertEquals(2,vm.tabs.value.size)
        compose.onNodeWithContentDescription("全タブを閉じる").performClick()
        compose.onNode(hasText("全タブを閉じる") and hasClickAction()).assertIsDisplayed().performClick()
        awaitModel{vm.tabs.value.isEmpty()}
        compose.onNodeWithText("Pocket Text Editor").assertExists()
    }

    @Test fun focusOnlySymbolBarWorksWithoutAnOnScreenKeyboard() {
        vm.setting("symbolMode","編集中のみ")
        val deadline=System.nanoTime()+10_000_000_000
        while(vm.preferences.value.symbolMode!="編集中のみ"){shadowOf(Looper.getMainLooper()).idle();check(System.nanoTime()<deadline);Thread.sleep(5)}
        compose.setContent{PocketApp(vm)}
        compose.onNodeWithText("HTML").assertExists();compose.onNodeWithText("CSV").assertExists()
        fun find(view:View):NativeEditor? {
            if(view is NativeEditor)return view
            if(view is ViewGroup)for(i in 0 until view.childCount){find(view.getChildAt(i))?.let{return it}}
            return null
        }
        compose.runOnUiThread{assertTrue(find(compose.activity.window.decorView)!!.requestFocus())}
        compose.onNodeWithText("←").assertIsDisplayed()
    }
}
