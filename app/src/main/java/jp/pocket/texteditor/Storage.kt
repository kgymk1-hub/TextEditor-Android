package jp.pocket.texteditor

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.*
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

@Entity(tableName="documents",indices=[Index("name"),Index("path"),Index("updated")])
data class Document(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val kind: String = "MEMO", val name: String = "無題.txt", val path: String = name,
    val uri: String? = null, val zipId: String? = null, val originalPath: String? = null, val projectUri: String? = null,
    val encoding: String = "UTF-8", val newline: String = "LF", val normalizedNewlines: Boolean = true,
    val revision: Long = 0, val savedRevision: Long = -1,
    val savedHash: String? = null, val savedBody: String? = null,
    val body: String = "", val updated: Long = System.currentTimeMillis(),
    val favorite: Boolean = false, val deleted: Long? = null,
    val tabOrder: Int = -1, val cursor: Int = 0, val selection: Int = 0, val scroll: Int = 0,
    val mode: String = "編集", val active: Boolean = false,
    val access: String = "", val viewed: Long = System.currentTimeMillis()
)
@Entity(tableName="revisions", indices=[Index("documentId")])
data class Revision(@PrimaryKey val id: String = UUID.randomUUID().toString(), val documentId: String, val body: String, val time: Long = System.currentTimeMillis(), val reason: String, val size: Long)
@Entity(tableName="archives")
data class Archive(@PrimaryKey val id: String, val uri: String, val hash: String, val file: String, val name: String)
@Fts4(notIndexed=["documentId", "bodyPath"])
@Entity(tableName="body_search")
data class BodySearch(val documentId: String, val tokens: String, val bodyPath: String)
data class SearchStatus(val documentId:String,val bodyPath:String,val fallback:Boolean)
@Dao
interface EditorDao {
    @Query("SELECT * FROM documents ORDER BY updated DESC") fun observe(): Flow<List<Document>>
    @Query("SELECT * FROM documents") suspend fun all(): List<Document>
    @Query("SELECT * FROM documents WHERE id=:id") suspend fun get(id: String): Document?
    @Query("DELETE FROM body_search WHERE documentId=:id") suspend fun removeSearch(id: String)
    @Insert suspend fun addSearch(entry: BodySearch)
    @Query("SELECT documentId FROM body_search WHERE body_search MATCH :query") suspend fun searchIds(query: String): List<String>
    @Query("SELECT documentId, bodyPath, tokens = '' AS fallback FROM body_search") suspend fun searchEntries(): List<SearchStatus>
    @Query("SELECT bodyPath FROM body_search WHERE documentId=:id LIMIT 1") suspend fun searchBody(id:String):String?
    @Upsert suspend fun put(doc: Document)
    @Upsert suspend fun putAll(docs: List<Document>)
    @Query("DELETE FROM documents WHERE id=:id") suspend fun remove(id: String)
    @Insert suspend fun addRevision(revision: Revision)
    @Query("SELECT * FROM revisions WHERE documentId=:id ORDER BY time DESC") suspend fun history(id: String): List<Revision>
    @Query("DELETE FROM revisions WHERE id=:id") suspend fun removeRevision(id: String)
    @Upsert suspend fun putArchive(archive: Archive)
    @Query("SELECT * FROM archives WHERE id=:id") suspend fun archive(id: String): Archive?
    @Query("SELECT * FROM archives") suspend fun archives(): List<Archive>
}
@Database(entities=[Document::class, Revision::class, Archive::class, BodySearch::class], version=2, exportSchema=false)
abstract class EditorDatabase: RoomDatabase() {
    abstract fun dao(): EditorDao
    companion object {
        val MIGRATION_1_2 = object: androidx.room.migration.Migration(1,2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS `body_search` USING FTS4(`documentId` TEXT NOT NULL, `tokens` TEXT NOT NULL, `bodyPath` TEXT NOT NULL, notindexed=`documentId`, notindexed=`bodyPath`)")
            }
        }
    }
}
val Context.settings by preferencesDataStore("editor_settings")
data class Preferences(
    val theme: String = "システム", val fontSize: Int = 16, val lineSpace: Int = 4,
    val wrap: Boolean = true, val numbers: Boolean = true, val highlight: Boolean = true,
    val invisible: Boolean = false, val currentLine: Boolean = true,
    val symbols: String = "Tab { } [ ] ( ) < > \" ' ` / \\ = ; : . , _ -",
    val shortcuts: String = "S=保存 Z=Undo Y=Redo F=検索 H=置換 N=新規 O=開く TAB=次タブ",
    val symbolMode: String = "常時", val autosave: Int = 500, val histories: Int = 30,
    val historyMb: Int = 64, val historyDays: Int = 90, val trashDays: Int = 30, val restore: Boolean = true,
    val defaultEncoding: String = "UTF-8", val outputEncoding:String = "元の文字コード", val defaultNewline: String = "LF",
    val indent: Int = 4, val tabs: Boolean = false, val autoIndent: Boolean = true, val brackets: Boolean = false,
    val csvHeader: Boolean = true, val previewAuto: Boolean = false, val compatibility: Boolean = false,
    val largeMb: Int = 5, val mono: Boolean = true, val sort: String = "更新日時"
)
class PreferenceStore(private val context: Context) {
    val flow: Flow<Preferences> = context.settings.data.map { p -> Preferences(
        theme=p[stringPreferencesKey("theme")] ?: "システム", fontSize=p[intPreferencesKey("fontSize")] ?: 16,
        lineSpace=p[intPreferencesKey("lineSpace")] ?: 4, wrap=p[booleanPreferencesKey("wrap")] ?: true,
        numbers=p[booleanPreferencesKey("numbers")] ?: true, highlight=p[booleanPreferencesKey("highlight")] ?: true,
        invisible=p[booleanPreferencesKey("invisible")] ?: false, currentLine=p[booleanPreferencesKey("currentLine")] ?: true,
        symbols=p[stringPreferencesKey("symbols")] ?: Preferences().symbols, shortcuts=p[stringPreferencesKey("shortcuts")] ?: Preferences().shortcuts, symbolMode=p[stringPreferencesKey("symbolMode")] ?: "常時",
        autosave=p[intPreferencesKey("autosave")] ?: 500, histories=p[intPreferencesKey("histories")] ?: 30,
        historyMb=p[intPreferencesKey("historyMb")] ?: 64, historyDays=p[intPreferencesKey("historyDays")] ?: 90, trashDays=p[intPreferencesKey("trashDays")] ?: 30,
        restore=p[booleanPreferencesKey("restore")] ?: true, defaultEncoding=p[stringPreferencesKey("encoding")] ?: "UTF-8",
        outputEncoding=p[stringPreferencesKey("outputEncoding")] ?: "元の文字コード", defaultNewline=p[stringPreferencesKey("newline")] ?: "LF", indent=p[intPreferencesKey("indent")] ?: 4,
        tabs=p[booleanPreferencesKey("tabs")] ?: false, autoIndent=p[booleanPreferencesKey("autoIndent")] ?: true,
        brackets=p[booleanPreferencesKey("brackets")] ?: false, csvHeader=p[booleanPreferencesKey("csvHeader")] ?: true,
        previewAuto=p[booleanPreferencesKey("previewAuto")] ?: false, compatibility=p[booleanPreferencesKey("compatibility")] ?: false,
        largeMb=p[intPreferencesKey("largeMb")] ?: 5, mono=p[booleanPreferencesKey("mono")] ?: true, sort=p[stringPreferencesKey("sort")] ?: "更新日時"
    ) }
    suspend fun set(key: String, value: Any) {
        val normalized=PreferenceValues.normalize(key,value) ?: return
        context.settings.edit { when(normalized) {
            is String -> it[stringPreferencesKey(key)] = normalized
            is Int -> it[intPreferencesKey(key)] = normalized
            is Boolean -> it[booleanPreferencesKey(key)] = normalized
        } }
    }
}
object PreferenceValues {
    private val ranges=mapOf("fontSize" to 10..36,"lineSpace" to 0..20,"autosave" to 100..5000,"histories" to 1..100,"historyMb" to 8..256,"historyDays" to 1..365,"trashDays" to 1..365,"indent" to 1..8,"largeMb" to 1..20)
    private val choices=mapOf("theme" to setOf("システム","ライト","ダーク"),"symbolMode" to setOf("常時","編集中のみ","非表示"),"encoding" to setOf("UTF-8","UTF-8 BOM","Shift_JIS"),"outputEncoding" to setOf("元の文字コード","UTF-8","UTF-8 BOM","Shift_JIS"),"newline" to setOf("LF","CRLF","CR"),"sort" to setOf("更新日時","名前"))
    private val flags=setOf("wrap","numbers","highlight","invisible","currentLine","restore","tabs","autoIndent","brackets","csvHeader","previewAuto","compatibility","mono")
    fun normalize(key:String,value:Any):Any? {
        ranges[key]?.let{return (value as? Int)?.coerceIn(it)}
        choices[key]?.let{return (value as? String)?.takeIf{v->v in it}}
        if(key in flags)return value as? Boolean
        if(key in setOf("symbols","shortcuts"))return (value as? String)?.take(8192)
        return null
    }
}
class Repository(val context: Context) {
    val db = Room.databaseBuilder(context, EditorDatabase::class.java, "editor.db").addMigrations(EditorDatabase.MIGRATION_1_2).build()
    val dao = db.dao(); val prefs = PreferenceStore(context)
    val root = File(context.filesDir,"documents").apply { mkdirs() }
    private val lock = Mutex()
    fun file(path: String): File { require(path != "." && path != ".." && Regex("[A-Za-z0-9_.-]+").matches(path)); return File(root,path) }
    fun read(path: String): String = file(path).readText(Charsets.UTF_8)
    fun atomic(bytes: ByteArray, name: String = UUID.randomUUID().toString()+".txt"): String {
        val target = file(name); val temp = File(root,"$name.tmp")
        java.io.FileOutputStream(temp).use { out -> out.write(bytes); out.flush(); out.fd.sync() }
        check(temp.renameTo(target)) { "内部ファイルの確定に失敗しました" }; return name
    }
    suspend fun persist(doc: Document, text: String): Document = withContext(Dispatchers.IO) { lock.withLock {
        val existing = dao.get(doc.id)
        if(existing != null && existing.revision > doc.revision) return@withLock existing
        val path = if(existing?.revision == doc.revision && existing.body.isNotEmpty()) existing.body else atomic(text.toByteArray(Charsets.UTF_8))
        val stored = doc.copy(body=path, savedRevision=if(doc.kind=="MEMO") doc.revision else doc.savedRevision, savedBody=if(doc.kind=="MEMO") path else doc.savedBody)
        db.withTransaction {
            dao.put(stored)
            val indexed=dao.searchBody(doc.id)
            if(indexed!=path) {
                dao.removeSearch(doc.id)
                dao.addSearch(BodySearch(doc.id, BodyTokens.index(text), path))
            }
        }; stored
    } }
    suspend fun literalCandidates(query:String):Set<String>? {
        val token=BodyTokens.query(query) ?: return null
        val indexed=dao.searchEntries().associateBy{it.documentId}
        val matched=dao.searchIds(token).toSet()
        return dao.all().filter{it.id in matched || indexed[it.id]?.bodyPath!=it.body || indexed[it.id]?.fallback!=false}.map{it.id}.toSet()
    }
    suspend fun snapshot(doc: Document, text: String, reason: String, count: Int): Unit = withContext(Dispatchers.IO) { lock.withLock {
        val last = dao.history(doc.id).firstOrNull()
        if(last != null && read(last.body)==text) return@withLock
        val body = atomic(text.toByteArray(Charsets.UTF_8)); dao.addRevision(Revision(documentId=doc.id,body=body,reason=reason,size=text.toByteArray().size.toLong()))
        val limit=prefs.flow.first().historyMb.toLong()*1024*1024;var total=0L
        dao.history(doc.id).forEachIndexed { index,r -> total+=r.size; if(index>0 && (index>=count.coerceAtLeast(1) || total>limit))dao.removeRevision(r.id) }
    } }
    suspend fun repairAndClean() = withContext(Dispatchers.IO) { lock.withLock {
        val p = prefs.flow.first(); val now = System.currentTimeMillis()
        dao.all().forEach { doc ->
            if(doc.deleted != null && now-doc.deleted > TimeUnit.DAYS.toMillis(p.trashDays.toLong())) { dao.remove(doc.id); dao.history(doc.id).forEach { dao.removeRevision(it.id) } }
            else {
                if(doc.body.isNotEmpty() && !file(doc.body).exists()) {
                    val history = dao.history(doc.id).firstOrNull { file(it.body).exists() }
                    dao.put(doc.copy(body=history?.body ?: "", revision=doc.revision+1, savedRevision=if(doc.kind=="MEMO")doc.revision+1 else doc.savedRevision, access="退避ファイルが欠損しています。履歴・外部ファイルを確認してください"))
                }
                dao.history(doc.id).forEachIndexed { i, r -> if(i>=p.histories || now-r.time > TimeUnit.DAYS.toMillis(p.historyDays.toLong())) dao.removeRevision(r.id) }
            }
        }
        val indexed=dao.searchEntries().associateBy{it.documentId}
        val documents=dao.all()
        indexed.keys.filter{id->documents.none{it.id==id}}.forEach{dao.removeSearch(it)}
        documents.filter{it.body.isNotEmpty() && file(it.body).exists() && indexed[it.id]?.bodyPath!=it.body}.forEach{doc ->
            db.withTransaction { dao.removeSearch(doc.id); dao.addSearch(BodySearch(doc.id,BodyTokens.index(read(doc.body)),doc.body)) }
        }
        val referenced = mutableSetOf<String>(); dao.all().forEach { d -> referenced.add(d.body); d.savedBody?.let { referenced.add(it) }; dao.history(d.id).forEach { referenced.add(it.body) } }; dao.archives().forEach { referenced.add(it.file) }
        // Delay orphan cleanup to avoid touching files written just before a crash/transaction.
        root.listFiles()?.filter { it.name !in referenced && now-it.lastModified() > TimeUnit.DAYS.toMillis(1) }?.forEach { it.delete() }
    } }
    fun name(uri: Uri): String = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null,null,null)?.use { c -> if(c.moveToFirst()) c.getString(0) else null } ?: "document.txt"
    fun bytes(uri: Uri): ByteArray = context.contentResolver.openInputStream(uri)?.use { input ->
        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192); while(true) { val n=input.read(buffer); if(n<0) break; require(output.size()+n <= 24*1024*1024) { "24MBを超えるファイルです" }; output.write(buffer,0,n) }; output.toByteArray()
    } ?: error("ファイルを読み込めません")
    fun writeVerified(uri: Uri, bytes: ByteArray, maxBytes:Int=24*1024*1024): String {
        require(bytes.size<=maxBytes){"保存サイズが上限を超えています。文書を分割してください"}
        context.contentResolver.openOutputStream(uri,"wt")?.use { it.write(bytes); it.flush() } ?: error("書込権限がありません")
        val actual = context.contentResolver.openInputStream(uri)?.use{TextCodec.hash(it.readBounded(maxBytes))} ?: error("書込後に読み込めません"); check(actual==TextCodec.hash(bytes)) { "書込後の検証に失敗しました。作業コピーを保持しています" }; return actual
    }
}
class CleanupWorker(context: Context, params: WorkerParameters): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result = try { (applicationContext as PocketApplication).repository.repairAndClean(); Result.success() } catch(_: Exception) { Result.retry() }
}
class PocketApplication: Application() {
    val repository by lazy { Repository(this) }
    override fun onCreate() { super.onCreate(); WorkManager.getInstance(this).enqueueUniquePeriodicWork("cleanup",ExistingPeriodicWorkPolicy.KEEP,PeriodicWorkRequestBuilder<CleanupWorker>(1,TimeUnit.DAYS).build()) }
}
