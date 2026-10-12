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
    @Test fun txtCanPreviewThenSearchAndReplaceInTheCorrectEditor() {
        compose.setContent{PocketApp(vm)}
        compose.onNodeWithText("HTML").performClick()
        compose.waitUntil(10_000){vm.current()?.document?.mode=="HTML"}
        compose.onNodeWithText("⋮").performClick()
        compose.onNodeWithText("検索・履歴・比較 ›").performClick()
        compose.onNodeWithText("検索").performClick()
        compose.waitUntil(10_000){vm.current()?.document?.mode=="編集"}
        compose.onNode(hasSetTextAction() and hasText("検索")).performTextInput("hello")
        compose.onNode(hasSetTextAction() and hasText("置換")).performTextInput("updated")
        compose.waitUntil(10_000){compose.onAllNodesWithText("1件").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("1件置換").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("1件置換").performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(10_000){vm.current()?.text=="<p>updated</p>"}
        compose.onNodeWithText("閉じる").performClick()
        compose.onNodeWithText("未保存 1件",substring=true).assertExists()
        compose.onNodeWithText("↶").performClick()
        compose.waitUntil(10_000){vm.current()?.text=="<p>hello</p>"}
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
