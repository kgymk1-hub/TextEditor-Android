package jp.pocket.texteditor

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class StorageTest {
    lateinit var repo: Repository
    @Before fun setup() { val context=ApplicationProvider.getApplicationContext<Application>();context.deleteDatabase("editor.db");File(context.filesDir,"documents").deleteRecursively();repo=Repository(context) }
    @After fun close(){repo.db.close()}
    @Test fun memoSurvivesRepositoryRecreationAndPositionsPersist()= runBlocking {
        val d=Document(revision=9,cursor=2,selection=3,scroll=80,tabOrder=11,active=true); val stored=repo.persist(d,"日本語\n本文")
        assertEquals(9L,stored.savedRevision);repo.db.close();repo=Repository(repo.context)
        val recovered=repo.dao.get(d.id)!!;assertEquals("日本語\n本文",repo.read(recovered.body));assertEquals(11,recovered.tabOrder);assertEquals(3,recovered.selection);assertEquals(80,recovered.scroll);assertTrue(recovered.active)
    }
    @Test fun staleAutosaveCannotReplaceNewerBody()= runBlocking { val d=Document(revision=2);repo.persist(d,"new");repo.persist(d.copy(revision=1),"old");val latest=repo.dao.get(d.id)!!;assertEquals(2L,latest.revision);assertEquals("new",repo.read(latest.body)) }
    @Test fun externalRecoveryDoesNotMarkOriginalSaved()=runBlocking {val d=Document(kind="EXTERNAL",uri="content://files/a",revision=3,savedRevision=1);val stored=repo.persist(d,"draft");assertEquals(1L,stored.savedRevision);assertEquals("draft",repo.read(stored.body))}
    @Test fun thirtyHistoryGenerationsDoNotRemoveLatestDraft()=runBlocking {val d=Document(revision=40);val stored=repo.persist(d,"latest");repeat(40){repo.snapshot(stored,"version $it","test",30)};assertEquals(30,repo.dao.history(d.id).size);assertEquals("latest",repo.read(repo.dao.get(d.id)!!.body))}
    @Test fun restoreProtectsBothPreviousAndCurrentVersions()=runBlocking {val d=Document();repo.snapshot(d,"old","test",30);repo.snapshot(d,"current","復元前",30);val history=repo.dao.history(d.id);assertEquals(setOf("old","current"),history.map{repo.read(it.body)}.toSet())}
    @Test fun missingDraftRepairsFromHistory()=runBlocking {val d=repo.persist(Document(),"current");repo.snapshot(d,"protected","test",30);repo.file(d.body).delete();repo.repairAndClean();val repaired=repo.dao.get(d.id)!!;assertEquals("protected",repo.read(repaired.body));assertTrue(repaired.access.isNotEmpty())}
    @Test fun originalUriIsOverwrittenAndVerified() {
        val provider=TestProvider();provider.attachInfo(repo.context,ProviderInfo().apply{authority="test.files"});ShadowContentResolver.registerProviderInternal("test.files",provider)
        val uri=Uri.parse("content://test.files/document");val file=provider.file;file.writeText("old text longer than new")
        val bytes=TextCodec.encode("new","UTF-8");assertEquals(TextCodec.hash(bytes),repo.writeVerified(uri,bytes));assertEquals("new",file.readText());assertEquals("source.txt",repo.name(uri))
    }
    @Test fun writeVerificationFailureIsNotSuccess() {
        val provider=TestProvider();provider.attachInfo(repo.context,ProviderInfo().apply{authority="test.files"});ShadowContentResolver.registerProviderInternal("test.files",provider);provider.corruptReads=true
        assertThrows(IllegalStateException::class.java){repo.writeVerified(Uri.parse("content://test.files/document"),"new".toByteArray())}
    }
    @Test fun readOnlyProviderStopsWriteAndKeepsDraft()=runBlocking {
        val provider=TestProvider();provider.attachInfo(repo.context,ProviderInfo().apply{authority="test.files"});ShadowContentResolver.registerProviderInternal("test.files",provider);provider.readOnly=true
        val d=repo.persist(Document(kind="EXTERNAL",revision=1,savedRevision=0),"draft")
        assertThrows(SecurityException::class.java){repo.writeVerified(Uri.parse("content://test.files/document"),"draft".toByteArray())};assertEquals("draft",repo.read(d.body));assertEquals(0L,d.savedRevision)
    }
    @Test fun bodyIndexUpdatesAtomicallyAndSupportsJapaneseSubstrings()=runBlocking {
        val d=repo.persist(Document(),"日本語で本文検索できます")
        assertEquals(listOf(d.id),repo.dao.searchIds(BodyTokens.query("本文")!!))
        repo.persist(d.copy(revision=1),"updated english text")
        assertTrue(repo.dao.searchIds(BodyTokens.query("本文")!!).isEmpty())
        assertEquals(listOf(d.id),repo.dao.searchIds(BodyTokens.query("ENGLISH")!!))
    }
    @Test fun migrationKeepsBodyHistoryAndMetadata()=runBlocking {
        val d=repo.persist(Document(cursor=7,tabOrder=4),"移行前の本文");repo.snapshot(d,"履歴","test",30)
        repo.db.openHelper.writableDatabase.execSQL("DROP TABLE body_search")
        repo.db.openHelper.writableDatabase.version=1
        repo.db.close();repo=Repository(repo.context)
        assertEquals("移行前の本文",repo.read(repo.dao.get(d.id)!!.body))
        assertEquals(4,repo.dao.get(d.id)!!.tabOrder);assertEquals(1,repo.dao.history(d.id).size)
        repo.repairAndClean();assertEquals(listOf(d.id),repo.dao.searchIds(BodyTokens.query("本文")!!))
    }
    @Test fun internalPathRejectsParentAndCurrentDirectory() {
        assertThrows(IllegalArgumentException::class.java){repo.file("..")}
        assertThrows(IllegalArgumentException::class.java){repo.file(".")}
    }

    @Test fun oversizedWriteIsRejectedBeforeTruncatingOriginal(){val provider=TestProvider();provider.attachInfo(repo.context,ProviderInfo().apply{authority="test.files"});ShadowContentResolver.registerProviderInternal("test.files",provider);provider.file.writeText("original");assertThrows(IllegalArgumentException::class.java){repo.writeVerified(Uri.parse("content://test.files/document"),ByteArray(24*1024*1024+1))};assertEquals("original",provider.file.readText())}

    @Test fun importedPreferencesCannotDisableHistoryWithInvalidBounds()=runBlocking {repo.prefs.set("histories",-100);repo.prefs.set("fontSize",99999);repo.prefs.set("encoding","invalid-charset");val p=repo.prefs.flow.first();assertEquals(1,p.histories);assertEquals(36,p.fontSize);assertEquals("UTF-8",p.defaultEncoding)}

}
class TestProvider:ContentProvider() {
    lateinit var file:File;var corruptReads=false;var readOnly=false
    override fun onCreate():Boolean {file=File(context!!.cacheDir,"provider-file.txt");return true}
    override fun query(uri:Uri,projection:Array<out String>?,selection:String?,args:Array<out String>?,sort:String?):Cursor=MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply{addRow(arrayOf("source.txt"))}
    override fun getType(uri:Uri)="text/plain"
    override fun insert(uri:Uri,values:ContentValues?):Uri?=null
    override fun update(uri:Uri,values:ContentValues?,selection:String?,args:Array<out String>?):Int=0
    override fun delete(uri:Uri,selection:String?,args:Array<out String>?):Int=0
    override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {if(mode!="r" && readOnly)throw SecurityException("read only");if(mode=="r" && corruptReads)file.writeText("corrupt");return ParcelFileDescriptor.open(file,if(mode=="r")ParcelFileDescriptor.MODE_READ_ONLY else ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)}
}
