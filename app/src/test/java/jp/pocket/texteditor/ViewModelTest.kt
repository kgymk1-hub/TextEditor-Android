package jp.pocket.texteditor

import android.app.Application
import android.content.Context
import android.content.pm.ProviderInfo
import android.net.Uri
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class ViewModelTest {
    private lateinit var vm:EditorViewModel
    private lateinit var provider:TestProvider
    private val store=ViewModelStore()
    @Before fun setup() {
        val context=ApplicationProvider.getApplicationContext<Application>()
        context.deleteDatabase("editor.db");File(context.filesDir,"documents").deleteRecursively()
        runBlocking {context.settings.edit{it.clear()}}
        val app=PocketApplication()
        ReflectionHelpers.callInstanceMethod<Unit>(app,"attach",ClassParameter.from(Context::class.java,context))
        vm=EditorViewModel(app);store.put("editor",vm)
        provider=TestProvider();provider.attachInfo(context,ProviderInfo().apply{authority="test.files"})
        ShadowContentResolver.registerProviderInternal("test.files",provider)
    }
    @After fun cleanup(){store.clear();ReflectionHelpers.getField<CoroutineScope>(vm,"scope").cancel();vm.repo.db.close()}
    private fun await(condition:()->Boolean){val deadline=System.nanoTime()+10_000_000_000;while(!condition()){shadowOf(Looper.getMainLooper()).idle();if(System.nanoTime()>deadline)throw AssertionError("Timed out; message=${vm.message.value}");Thread.sleep(5)};shadowOf(Looper.getMainLooper()).idle()}
    private fun <T> background(block:suspend ()->T):T {
        val future=java.util.concurrent.CompletableFuture.supplyAsync{runBlocking{block()}}
        await{future.isDone};return future.get()
    }
    private fun memo(text:String,name:String="test.txt"):Session{val count=vm.tabs.value.size;vm.newMemo(text,name);await{vm.tabs.value.size==count+1 && vm.current()?.status=="保存済み"};return vm.current()!!}
    @Test fun previewReplacementChangesOnlyTheTargetDocumentAndKeepsHistory() {
        val target=memo("hello target","target.txt");val other=memo("hello other","other.txt")
        vm.mode(target.document.id,"HTML")
        vm.replaceOne(target.document.id,target.document.revision,0,"hello","updated",true,false)
        await{vm.session(target.document.id).text=="updated target"}
        assertEquals("hello other",vm.session(other.document.id).text)
        assertTrue(background{vm.history(target.document.id)}.any{background{vm.repo.read(it.body)}=="hello target"})
    }
    @Test fun staleReplacementCannotOverwriteNewInput() {
        val target=memo("hello target")
        vm.edit(target.document.id,"hello new input",0,0,0)
        vm.replaceOne(target.document.id,target.document.revision,0,"hello","updated",true,false)
        await{vm.message.value?.contains("本文が変更") == true}
        assertEquals("hello new input",vm.session(target.document.id).text)
    }
    @Test fun readAndOutputEncodingAreIndependentAndCopyDoesNotChangeOriginal() {
        val first=memo("日本語","first.txt")
        background{vm.repo.prefs.set("encoding","Shift_JIS");vm.repo.prefs.set("outputEncoding","UTF-8 BOM")}
        await{vm.preferences.value.defaultEncoding=="Shift_JIS" && vm.preferences.value.outputEncoding=="UTF-8 BOM"}
        val before=vm.session(first.document.id).document
        vm.export(first.document.id,Uri.parse("content://test.files/document"),false,3)
        await{vm.message.value?.contains("コピーを保存") == true}
        assertEquals("UTF-8 BOM",TextCodec.decode(provider.file.readBytes()).encoding)
        assertEquals(before.encoding,vm.session(first.document.id).document.encoding)
        assertEquals(before.savedRevision,vm.session(first.document.id).document.savedRevision)
    }
    @Test fun outputDefaultsDoNotReencodeExternalOverwrite() {
        provider.file.writeBytes(TextCodec.encode("日本語","Shift_JIS"))
        background{vm.repo.prefs.set("outputEncoding","UTF-8 BOM")}
        await{vm.preferences.value.outputEncoding=="UTF-8 BOM"}
        vm.openUri(Uri.parse("content://test.files/document"),3,"Shift_JIS")
        await{vm.current()?.document?.kind=="EXTERNAL" && vm.current()?.status=="保存済み"}
        val first=vm.current()!!;vm.edit(first.document.id,"更新した日本語",0,0,0)
        vm.save(first.document.id)
        await{vm.session(first.document.id).status=="保存済み"}
        assertArrayEquals(TextCodec.encode("更新した日本語","Shift_JIS"),provider.file.readBytes())
    }
    @Test fun zipBrowserContinuesWithoutDuplicatingOrDiscardingEditedDocuments() {
        val bytes=java.io.ByteArrayOutputStream();SafeZip.write(listOf("a.txt" to "first".toByteArray(),"b.txt" to "second".toByteArray()),bytes)
        provider.file.writeBytes(bytes.toByteArray());vm.openZip(Uri.parse("content://test.files/document"),3)
        await{vm.zipChoice.value!=null};val archive=vm.zipChoice.value!!.archive.id
        vm.importZip(setOf("a.txt"));await{vm.tabs.value.size==1}
        val first=vm.current()!!;vm.edit(first.document.id,"edited first",0,0,0)
        vm.showZipBrowser();await{vm.zipChoice.value!=null}
        assertEquals(archive,vm.zipChoice.value!!.archive.id)
        vm.importZip(setOf("a.txt","b.txt"));await{vm.tabs.value.size==2 && vm.zipChoice.value==null}
        assertEquals("edited first",vm.session(first.document.id).text)
        assertEquals(2,vm.tabs.value.map{it.document.id}.distinct().size)
    }
    @Test fun duplicateZipExportPreservesBothBodiesAndDoesNotRenameTabs() {
        val first=memo("first","a.txt");val second=memo("second","a.txt")
        val files=SafeZip.read(java.io.ByteArrayInputStream(background{vm.zipBytes(listOf(first.document.id,second.document.id))}))
        assertEquals("first",files.getValue("a.txt").decodeToString());assertEquals("second",files.getValue("a-2.txt").decodeToString())
        assertTrue(vm.tabs.value.all{it.document.path=="a.txt"})
    }
    @Test fun compatibilityPreviewLoadsUnloadedCssBodies() {
        val css="a{}".repeat(400_000);val first=memo(css,"STYLE.CSS");val second=memo("<p>test</p>","index.html")
        vm.activate(second.document.id);await{vm.session(first.document.id).unloaded}
        assertEquals(css,background{vm.linkedPreviewSources()}.single().second)
    }

    @Test fun closeAllPreservesLatestTextAndAllowsRestoringTabs() {
        val first=memo("original","first.txt");val second=memo("second","second.txt")
        vm.edit(second.document.id,"unsaved latest text",3,3,0)
        vm.closeAll(listOf(first.document.id,second.document.id))
        await{vm.tabs.value.isEmpty()}
        assertNull(vm.active.value)
        val stored=background{vm.repo.dao.get(second.document.id)!!}
        assertEquals(-1,stored.tabOrder);assertNull(stored.deleted)
        assertEquals("unsaved latest text",background{vm.repo.read(stored.body)})
        vm.openMemo(stored);await{vm.current()?.document?.id==stored.id}
        assertEquals("unsaved latest text",vm.current()!!.text)
    }
    @Test fun closeAllBacksUpExternalEditsWithoutWritingExternalFile() {
        provider.file.writeText("external original")
        vm.openUri(Uri.parse("content://test.files/document"),3,"UTF-8")
        await{vm.current()?.document?.kind=="EXTERNAL" && vm.current()?.status=="保存済み"}
        val external=vm.current()!!
        vm.edit(external.document.id,"external unsaved edit",0,0,0)
        vm.closeAll(listOf(external.document.id))
        await{vm.tabs.value.isEmpty()}
        assertEquals("external original",provider.file.readText())
        val stored=background{vm.repo.dao.get(external.document.id)!!}
        assertEquals("external unsaved edit",background{vm.repo.read(stored.body)})
        assertTrue(stored.revision>stored.savedRevision)
    }

    @Test fun closeAllOnlyClosesConfirmedTabs() {
        val first=memo("first");val second=memo("second")
        val confirmed=listOf(first.document.id,second.document.id)
        val later=memo("opened after confirmation")
        vm.close(first.document.id);await{vm.tabs.value.size==2}
        vm.closeAll(confirmed)
        await{vm.tabs.value.size==1}
        assertEquals(later.document.id,vm.current()!!.document.id)
        assertEquals("opened after confirmation",vm.current()!!.text)
    }

    @Test fun closingActiveTabReloadsUnloadedBody() {
        val original="本文".repeat(600_000);val first=memo(original,"large.txt");val second=memo("small","small.txt")
        vm.activate(second.document.id);await{vm.session(first.document.id).unloaded}
        vm.close(second.document.id);await{vm.active.value==first.document.id && !vm.current()!!.unloaded}
        assertEquals(TextCodec.hash(original.toByteArray()),TextCodec.hash(vm.current()!!.text.toByteArray()))
    }
    @Test fun trashUnloadedMemoProtectsActualBodyInHistory() {
        val first=memo("大容量".repeat(400_000),"large.txt");val second=memo("small")
        vm.activate(second.document.id);await{vm.session(first.document.id).unloaded}
        vm.trash(first.document.id);await{vm.tabs.value.none{it.document.id==first.document.id}}
        runBlocking {val d=vm.repo.dao.get(first.document.id)!!;assertNotNull(d.deleted);assertEquals(TextCodec.hash(first.text.toByteArray()),TextCodec.hash(vm.repo.read(d.body).toByteArray()));assertTrue(vm.repo.dao.history(d.id).all{vm.repo.read(it.body).isNotEmpty()})}
    }
    @Test fun backupAddsMemoAndHistoryWithoutReplacingExistingDocument() {
        val first=memo("混在\r\n改行\n末尾","first.txt")
        runBlocking {vm.repo.snapshot(first.document,"履歴","test",30);vm.repo.prefs.set("fontSize",22)}
        vm.mode(first.document.id,"Markdown")
        provider.file.writeBytes(background{vm.backupBytes()})
        vm.importBackup(Uri.parse("content://test.files/document"))
        await{vm.tabs.value.size==2 && vm.message.value?.contains("既存データは保持")==true}
        runBlocking {val docs=vm.repo.dao.all();assertEquals(2,docs.size);assertNotNull(vm.repo.dao.get(first.document.id));val imported=docs.first{it.id!=first.document.id};assertEquals("MEMO",imported.kind);assertNull(imported.uri);assertEquals("Markdown",imported.mode);assertEquals(first.text,vm.repo.read(imported.body));assertTrue(vm.repo.dao.history(imported.id).any{vm.repo.read(it.body)=="履歴"});assertTrue(vm.repo.dao.searchEntries().none{it.documentId==imported.id})}
    }
    @Test fun indexedSearchUsesLatestUnsavedTextInsteadOfSavedIndex() {
        val first=memo("saved original","first.txt")
        vm.edit(first.document.id,"編集中の日本語",0,0,0)
        val hits=runBlocking{vm.crossSearch("日本語",true,false)}
        assertEquals(listOf(first.document.id),hits.map{it.first.id}.distinct())
        assertTrue(runBlocking{vm.crossSearch("original",true,false)}.isEmpty())
    }

    @Test fun typingDuringTrashCancelsRemovalAndPersistsLatestAcceptedText() {
        val first=memo("original")
        background{vm.flush(first.document.id)}
        await{vm.viewModelScope.coroutineContext[Job]!!.children.count()==2}
        val mutex=ReflectionHelpers.getField<kotlinx.coroutines.sync.Mutex>(vm.repo,"lock")
        runBlocking{mutex.lock()}
        try {vm.trash(first.document.id);shadowOf(Looper.getMainLooper()).idle();vm.edit(first.document.id,"new input during removal",0,0,0)}finally{mutex.unlock()}
        await{vm.message.value?.contains("新しい入力") == true}
        assertEquals("new input during removal",vm.session(first.document.id).text)
        runBlocking{val doc=vm.repo.dao.get(first.document.id)!!;assertNull(doc.deleted);assertEquals("new input during removal",vm.repo.read(doc.body))}
    }
}
