package jp.pocket.texteditor

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.*
import java.util.UUID
import org.json.*

data class Session(val document: Document, val text: String, val status: String = "保存済み", val unloaded: Boolean = false, val backedRevision: Long = document.revision)
data class Conflict(val documentId: String, val external: String, val hash: String)
data class ZipChoice(val archive: Archive, val entries: Map<String,ByteArray>)
class EditorViewModel(app: Application): AndroidViewModel(app) {
    val repo = (app as PocketApplication).repository
    val preferences = repo.prefs.flow.stateIn(viewModelScope,SharingStarted.Eagerly,Preferences())
    val documents = repo.dao.observe().stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
    private val _tabs = MutableStateFlow<List<Session>>(emptyList()); val tabs = _tabs.asStateFlow()
    private val _active = MutableStateFlow<String?>(null); val active = _active.asStateFlow()
    val message = MutableStateFlow<String?>(null)
    val conflict = MutableStateFlow<Conflict?>(null)
    val zipChoice = MutableStateFlow<ZipChoice?>(null)
    private val autosaves = mutableMapOf<String,Job>(); private val locks = java.util.concurrent.ConcurrentHashMap<String,Mutex>()
    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val ready = MutableStateFlow(false)
    private var selectedProject: String? = null
    private var pinned: String? = null
    val editorUndo = mutableMapOf<String, Pair<List<String>,List<String>>>()
    fun current(): Session? = _tabs.value.find { it.document.id==_active.value }
    fun session(id: String): Session = _tabs.value.first { it.document.id==id }
    private fun update(id: String, transform: (Session)->Session) { _tabs.update { list -> list.map { if(it.document.id==id) transform(it) else it } } }
    fun runTask(initializing: Boolean=false, block: suspend ()->Unit) { viewModelScope.launch {
        try { if(!initializing)ready.first{it};block() }
        catch(e: Exception) { if(e is CancellationException) throw e; message.value=e.message ?: "処理に失敗しました。作業コピーを確認してください" }
        finally { if(initializing)ready.value=true }
    } }
    init { runTask(initializing=true) {
        withContext(Dispatchers.IO) { repo.repairAndClean() }
        val p = repo.prefs.flow.first(); val stored = repo.dao.all()
        if(p.restore) {
            val restoredActive=stored.find{it.active && it.deleted==null && it.tabOrder>=0}?.id ?: stored.filter{it.deleted==null && it.tabOrder>=0}.minByOrNull{it.tabOrder}?.id
            val loaded = withContext(Dispatchers.IO) { stored.filter { it.tabOrder>=0 && it.deleted==null }.sortedBy { it.tabOrder }.map { d ->
                val access = if(d.kind=="EXTERNAL" && d.uri!=null) try { repo.context.contentResolver.openFileDescriptor(Uri.parse(d.uri),"r")?.close(); "" } catch(_:Exception) { "アクセス権なし。退避本文を表示中" } else d.access
                Session(d.copy(access=access), if(d.id==restoredActive && d.body.isNotEmpty()) repo.read(d.body) else "", if(access.isNotEmpty()) access else if(d.kind=="MEMO" || d.revision==d.savedRevision) "保存済み" else "自動退避済み／元ファイル未保存", unloaded=d.id!=restoredActive)
            } }
            _tabs.value=loaded; _active.value=loaded.find { it.document.active }?.document?.id ?: loaded.firstOrNull()?.document?.id
        } else repo.dao.putAll(stored.map { it.copy(tabOrder=-1,active=false) })
        ready.value=true
    } }
    fun newMemo(text: String="", name: String="無題.txt") = runTask {
        val d=Document(name=name,path=name,encoding=preferences.value.defaultEncoding,newline=preferences.value.defaultNewline,tabOrder=_tabs.value.size,active=true)
        _tabs.value += Session(d,text,"自動退避中"); _active.value=d.id; flush(d.id); persistTabs()
    }
    fun edit(id: String, text: String, cursor: Int, selection: Int, scroll: Int) {
        if(_tabs.value.find{it.document.id==id}?.unloaded!=false)return
        if(text.length>24*1024*1024){message.value="本文が編集サイズの上限を超えました。文書を分割してください";return}
        update(id) { s -> val changed=s.text!=text; s.copy(text=text,document=s.document.copy(revision=s.document.revision+if(changed) 1 else 0,cursor=cursor,selection=selection,scroll=scroll,updated=if(changed) System.currentTimeMillis() else s.document.updated),status=if(changed) "自動退避中" else s.status) }
        autosaves.remove(id)?.cancel()
        autosaves[id]=scope.launch { delay(preferences.value.autosave.coerceIn(100,5000).toLong()); try { flush(id) } catch(e:Exception) { if(e is CancellationException) throw e; update(id) { it.copy(status="自動退避失敗") }; message.value="自動退避失敗：${e.message}。別名保存してください" } }
    }
    suspend fun flush(id: String) { locks.getOrPut(id){Mutex()}.withLock {
        val s=_tabs.value.find { it.document.id==id } ?: return@withLock
        if(s.unloaded){repo.dao.put(s.document);return@withLock}
        val stored=repo.persist(s.document,s.text)
        update(id) { latest -> latest.copy(backedRevision=stored.revision,document=latest.document.copy(body=stored.body,savedBody=if(latest.document.kind=="MEMO") stored.savedBody else latest.document.savedBody,savedRevision=if(latest.document.kind=="MEMO") stored.revision else latest.document.savedRevision),status=if(latest.document.revision!=stored.revision) "自動退避中" else if(latest.document.kind=="MEMO" || latest.document.savedRevision==latest.document.revision) "保存済み" else "自動退避済み／元ファイル未保存") }
        val history=repo.dao.history(id).firstOrNull()
        if(history==null || System.currentTimeMillis()-history.time>60_000) repo.snapshot(stored,s.text,"定期退避",preferences.value.histories)
    } }
    fun flushAll() { if(!ready.value) return; scope.launch { try { _tabs.value.forEach { flush(it.document.id) }; persistTabs() } catch(e:Exception) { message.value="退避失敗：${e.message}" } } }
    private suspend fun persistTabs() {
        val selected=_tabs.value.find{it.document.id==_active.value && it.unloaded}
        if(selected!=null) {
            val text=withContext(Dispatchers.IO){if(selected.document.body.isEmpty())""else repo.read(selected.document.body)}
            update(selected.document.id){it.copy(text=text,unloaded=false)}
        }
        _tabs.value.forEachIndexed { index,s -> update(s.document.id) { it.copy(document=it.document.copy(tabOrder=index,active=it.document.id==_active.value)) } }
        _tabs.value.forEach { flush(it.document.id) }
    }
    fun activate(id: String) { runTask {
        val old=_active.value;old?.let{flush(it)}
        val next=session(id);if(next.unloaded){val text=withContext(Dispatchers.IO){repo.read(next.document.body)};update(id){it.copy(text=text,unloaded=false)}}
        _active.value=id;persistTabs();unloadInactive()
    } }
    private fun unloadInactive() {
        val all=_tabs.value.sumOf{it.text.length.toLong()}
        if(all>8*1024*1024 || _tabs.value.any{it.document.id!=_active.value && it.text.length>1024*1024}) {
            _tabs.update{list->list.map{if(it.document.id!=_active.value && it.document.id!=pinned && it.document.body.isNotEmpty() && it.backedRevision==it.document.revision)it.copy(text="",unloaded=true)else it}}
            editorUndo.entries.removeAll{it.key!=_active.value}
        }
    }
    suspend fun prepareSplit(id: String) {
        val loaded=loadSession(id);pinned=id
        if(_tabs.value.none{it.document.id==id})_tabs.value+=loaded.copy(document=loaded.document.copy(tabOrder=_tabs.value.size))
        else update(id){it.copy(text=loaded.text,unloaded=false)}
        persistTabs()
    }
    fun endSplit(){pinned=null;flushAll()}
    fun openMemo(doc: Document) = runTask {
        if(_tabs.value.none { it.document.id==doc.id }) { val text=withContext(Dispatchers.IO){if(doc.body.isNotEmpty()) repo.read(doc.body) else ""}; _tabs.value += Session(doc.copy(tabOrder=_tabs.value.size,active=true),text,if(doc.kind=="MEMO" || doc.revision==doc.savedRevision) "保存済み" else "自動退避済み／元ファイル未保存") }
        val opened=session(doc.id);if(opened.unloaded){val text=withContext(Dispatchers.IO){repo.read(opened.document.body)};update(doc.id){it.copy(text=text,unloaded=false)}}
        _active.value=doc.id; persistTabs();unloadInactive()
    }
    val encodingChoice=MutableStateFlow<Pair<Uri,Int>?>(null)
    fun openUri(uri: Uri, flags: Int, encoding: String?=null, relative: String?=null) = runTask {
        try { openUriNow(uri,flags,encoding ?: preferences.value.defaultEncoding,relative) }
        catch(e:java.nio.charset.CharacterCodingException){encodingChoice.value=uri to flags;message.value="文字コードを判定できません。再指定してプレビューしてください"}
    }
    private suspend fun openUriNow(uri: Uri, flags: Int, encoding: String?=null, relative: String?=null) {
        val existing=_tabs.value.find { it.document.uri==uri.toString() && it.document.deleted==null }
        if(existing!=null) { activate(existing.document.id); return }
        val decoded=withContext(Dispatchers.IO) { val bytes=repo.bytes(uri); Pair(TextCodec.decode(bytes,encoding),TextCodec.hash(bytes)) }
        try { repo.context.contentResolver.takePersistableUriPermission(uri,flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) } catch(_:SecurityException) { message.value="永続権限を取得できません。再起動後は再選択が必要な場合があります" }
        val display=withContext(Dispatchers.Default){DocumentBytes.editorText(decoded.first)}
        val name=repo.name(uri); val body=withContext(Dispatchers.IO){repo.atomic(display.first.toByteArray())}
        val old=repo.dao.all().find { it.uri==uri.toString() && it.deleted==null }
        if(old!=null && old.revision!=old.savedRevision) { openMemo(old); message.value="未保存の退避本文があるため、再読込は比較画面から実行してください"; return }
        val d=(old ?: Document()).copy(kind="EXTERNAL",name=name,path=relative ?: name,uri=uri.toString(),projectUri=if(relative!=null) selectedProject else null,encoding=decoded.first.encoding,newline=decoded.first.newline,normalizedNewlines=display.second,savedRevision=old?.revision ?: 0,savedHash=decoded.second,savedBody=body,body=body,tabOrder=_tabs.value.size,active=true,access="",viewed=System.currentTimeMillis())
        _tabs.value+=Session(d,display.first); _active.value=d.id; flush(d.id); persistTabs()
        if(display.first.length>preferences.value.largeMb*1024*1024) message.value="大容量モード：構文解析・プレビューを制限します"
    }
    fun save(id: String, forceHash: String?=null) = runTask { locks.getOrPut(id){Mutex()}.withLock {
        val s=session(id); val d=s.document
        if(d.kind=="MEMO") { val stored=repo.persist(d,s.text); update(id) { it.copy(document=it.document.copy(body=stored.body,savedBody=stored.savedBody,savedRevision=stored.revision),status=if(it.document.revision==stored.revision) "保存済み" else "自動退避中") }; return@withLock }
        if(d.kind=="ZIP") { message.value="ZIPの変更は［ZIPとして保存］で反映してください"; return@withLock }
        val uri=Uri.parse(d.uri ?: error("保存先がありません"))
        withContext(Dispatchers.IO) {
            repo.persist(d,s.text); repo.snapshot(d,s.text,"外部上書き前",preferences.value.histories)
        }
        try {
            val currentBytes=withContext(Dispatchers.IO){repo.bytes(uri)}; val hash=withContext(Dispatchers.IO){TextCodec.hash(currentBytes)}
            if(hash != (forceHash ?: d.savedHash)) { conflict.value=Conflict(id,withContext(Dispatchers.IO){TextCodec.decode(currentBytes,d.encoding).text},hash); update(id){it.copy(status="競合あり")}; return@withLock }
            val bytes=withContext(Dispatchers.IO){DocumentBytes.encode(d,s.text)}
            update(id){it.copy(status="元ファイルへ保存中")}
            val newHash=withContext(Dispatchers.IO){repo.writeVerified(uri,bytes)}
            val savedBody=withContext(Dispatchers.IO){repo.atomic(s.text.toByteArray())}
            update(id) { latest -> latest.copy(document=SaveRules.committed(latest.document,d.revision,newHash,savedBody),status=if(latest.document.revision==d.revision) "保存済み" else "自動退避中") }
            val latest=session(id); val stored=repo.persist(latest.document,latest.text); update(id){it.copy(document=it.document.copy(body=stored.body))}
            message.value="書込・再読込検証が完了しました（クラウド同期の完了ではありません）"
        } catch(e:Exception) { if(e is CancellationException) throw e; update(id){it.copy(status="保存失敗",document=it.document.copy(access=e.message ?: "保存失敗"))}; message.value="保存失敗：${e.message}。退避本文を保持しています" }
    } }
    fun export(id: String, uri: Uri, switch: Boolean, flags: Int) = runTask { locks.getOrPut(id){Mutex()}.withLock {
        val s=session(id); repo.persist(s.document,s.text)
        val bytes=withContext(Dispatchers.IO){DocumentBytes.encode(s.document,s.text)}
        val hash=withContext(Dispatchers.IO){repo.writeVerified(uri,bytes)}; val name=repo.name(uri)
        if(switch) {
            try { repo.context.contentResolver.takePersistableUriPermission(uri,flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) } catch(_:SecurityException) { message.value="保存しましたが永続権限を取得できません" }
            val body=withContext(Dispatchers.IO){repo.atomic(s.text.toByteArray())}
            update(id){it.copy(document=it.document.copy(kind="EXTERNAL",uri=uri.toString(),name=name,path=name,zipId=null,savedRevision=s.document.revision,savedHash=hash,savedBody=body),status=if(it.document.revision==s.document.revision) "保存済み" else "自動退避中")}
            val latest=session(id); repo.persist(latest.document,latest.text)
        }
        message.value="${if(switch) "編集先を切替" else "コピーを保存"}：$name${if(name!=s.document.name) "（提供元が決定した名前）" else ""}"
    } }
    private fun assertUnchanged(s:Session) {check(session(s.document.id).document.revision==s.document.revision){"処理中に新しい入力がありました。本文を保持して操作を中止しました。再試行してください"}}
    fun reload(id: String, encoding: String?=null) = runTask {
        val s=session(id); repo.snapshot(s.document,s.text,"再読込前",preferences.value.histories)
        val bytes=withContext(Dispatchers.IO){repo.bytes(Uri.parse(s.document.uri ?: error("外部ファイルではありません")))}
        val decoded=withContext(Dispatchers.IO){TextCodec.decode(bytes,encoding ?: s.document.encoding)};val display=withContext(Dispatchers.Default){DocumentBytes.editorText(decoded)}; val body=withContext(Dispatchers.IO){repo.atomic(display.first.toByteArray())}
        assertUnchanged(s)
        update(id){it.copy(text=display.first,document=it.document.copy(revision=it.document.revision+1,savedRevision=it.document.revision+1,savedHash=TextCodec.hash(bytes),savedBody=body,body=body,encoding=decoded.encoding,newline=decoded.newline,normalizedNewlines=display.second),status="保存済み")}; flush(id); conflict.value=null
    }
    fun rename(id: String, name: String) = runTask {
        require(name.isNotBlank()); SafeZip.safePath(name)
        val s=_tabs.value.find { it.document.id==id }
        val doc=s?.document ?: repo.dao.get(id) ?: return@runTask
        val d=if(doc.kind=="EXTERNAL") {
            require(!name.contains('/')){"外部ファイルの改名にはファイル名だけを指定してください"}
            val loaded=loadSession(id);repo.persist(doc,loaded.text);repo.snapshot(doc,loaded.text,"外部改名前",preferences.value.histories)
            val uri=Uri.parse(doc.uri ?: error("URIがありません"))
            val newUri=withContext(Dispatchers.IO){requireProviderFlag(uri,DocumentsContract.Document.FLAG_SUPPORTS_RENAME);DocumentsContract.renameDocument(repo.context.contentResolver,uri,name) ?: error("改名できません")}
            doc.copy(uri=newUri.toString(),name=repo.name(newUri),path=doc.path.substringBeforeLast('/',"").let{if(it.isEmpty())repo.name(newUri)else "$it/${repo.name(newUri)}"},updated=System.currentTimeMillis())
        } else doc.copy(name=name.substringAfterLast('/'),path=name,updated=System.currentTimeMillis())
        if(s!=null){ update(id){it.copy(document=it.document.copy(name=d.name,path=d.path,uri=d.uri,updated=d.updated))}; flush(id) } else repo.dao.put(d)
    }
    private fun requireProviderFlag(uri:Uri,flag:Int) {
        val flags=repo.context.contentResolver.query(uri,arrayOf(DocumentsContract.Document.COLUMN_FLAGS),null,null,null)?.use{c->val index=c.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS);if(c.moveToFirst() && index>=0)c.getInt(index)else 0} ?: 0
        require(flags and flag != 0){"ファイル提供元がこの操作を許可していません。別名保存してください"}
    }
    fun deleteExternal(id:String)=runTask {
        val s=loadSession(id);require(s.document.kind=="EXTERNAL")
        val stored=repo.persist(s.document,s.text);repo.snapshot(stored,s.text,"外部削除前",preferences.value.histories)
        val uri=Uri.parse(stored.uri ?: error("URIがありません"))
        withContext(Dispatchers.IO){requireProviderFlag(uri,DocumentsContract.Document.FLAG_SUPPORTS_DELETE);check(DocumentsContract.deleteDocument(repo.context.contentResolver,uri)){"外部ファイルを削除できません"}}
        val live=_tabs.value.find{it.document.id==id}
        if(live==null)repo.persist(stored.copy(kind="MEMO",uri=null,projectUri=null),s.text)
        else update(id){it.copy(document=it.document.copy(kind="MEMO",uri=null,projectUri=null),status="削除後の本文を保護中")}
        try {
            detach(id,true)
            message.value="外部ファイルを削除しました。本文はアプリのごみ箱に保持されています"
        } catch(e:Exception) {
            if(e is CancellationException)throw e
            message.value="外部ファイルは削除済みです。本文は内部メモとして保持しています。${e.message}"
        }
    }
    fun favorite(doc: Document) = runTask { val s=_tabs.value.find {it.document.id==doc.id}; if(s!=null){ update(doc.id){it.copy(document=it.document.copy(favorite=!it.document.favorite))}; flush(doc.id) } else repo.dao.put(doc.copy(favorite=!doc.favorite)) }
    private suspend fun detach(id:String,trash:Boolean) {
        autosaves.remove(id)?.cancel()
        locks.getOrPut(id){Mutex()}.withLock {
            val captured=loadSession(id)
            if(trash)require(captured.document.kind=="MEMO"){"外部文書はタブを閉じてください"}
            val stored=repo.persist(captured.document,captured.text)
            if(trash)repo.snapshot(stored,captured.text,"削除前",preferences.value.histories)
            repo.dao.put(stored.copy(deleted=if(trash)System.currentTimeMillis()else stored.deleted,tabOrder=-1,active=false))
            val live=_tabs.value.find{it.document.id==id}
            if(live!=null && live.document.revision!=captured.document.revision) {
                // Restore live metadata and preserve text before reporting cancellation.
                repo.persist(live.document,live.text)
                error("処理中に新しい入力がありました。本文を保持して操作を中止しました。再試行してください")
            }
            _tabs.value=_tabs.value.filter{it.document.id!=id}
            if(_active.value==id)_active.value=_tabs.value.lastOrNull()?.document?.id
        }
        persistTabs()
    }
    fun close(id:String)=runTask{detach(id,false)}
    fun move(id: String, delta: Int) = runTask { val list=_tabs.value.toMutableList(); val i=list.indexOfFirst {it.document.id==id}; val j=(i+delta).coerceIn(0,list.lastIndex); if(i>=0) { val s=list.removeAt(i); list.add(j,s); _tabs.value=list; persistTabs() } }
    fun duplicate(doc: Document) = runTask { val s=_tabs.value.find {it.document.id==doc.id && !it.unloaded}; val text=s?.text ?: withContext(Dispatchers.IO){repo.read(doc.body)}; newMemo(text,"コピー_${doc.name}") }
    fun trash(id:String)=runTask{detach(id,true)}
    fun restoreTrash(doc: Document)=runTask { repo.dao.put(doc.copy(deleted=null,updated=System.currentTimeMillis())) }
    suspend fun history(id: String)=repo.dao.history(id)
    fun restoreRevision(id: String, revision: Revision)=runTask { val s=session(id); repo.snapshot(s.document,s.text,"履歴復元前",preferences.value.histories); val text=withContext(Dispatchers.IO){repo.read(revision.body)}; assertUnchanged(s);edit(id,text,0,0,0); flush(id) }
    fun replaceAll(id: String, query: String, replacement: String, case: Boolean, regex: Boolean)=runTask {
        val s=session(id); repo.snapshot(s.document,s.text,"全置換前",preferences.value.histories)
        assertUnchanged(s);val text=withContext(Dispatchers.Default){val re=SearchTools.regex(query,case,regex);if(regex)re.replace(s.text,replacement)else re.replace(s.text){replacement}};assertUnchanged(s);edit(id,text,s.document.cursor.coerceAtMost(text.length),s.document.cursor.coerceAtMost(text.length),s.document.scroll); flush(id)
    }
    fun changeFormat(id: String, encoding: String, newline: String)=runTask { val s=session(id);val text=withContext(Dispatchers.Default){val value=if(newline==s.document.newline)s.text else TextCodec.convertNewlines(s.text,"LF");TextCodec.encode(value,encoding);value}; repo.snapshot(s.document,s.text,"文字コード・改行変更前",preferences.value.histories);assertUnchanged(s); update(id){it.copy(text=text,document=it.document.copy(encoding=encoding,newline=newline,normalizedNewlines=if(newline!=it.document.newline)true else it.document.normalizedNewlines,revision=it.document.revision+1),status="自動退避中")}; flush(id) }
    fun updatePosition(id: String, cursor: Int, selection: Int, scroll: Int) { val s=_tabs.value.find{it.document.id==id} ?: return; edit(id,s.text,cursor,selection,scroll) }
    fun mode(id: String, mode: String) { update(id){it.copy(document=it.document.copy(mode=mode))}; runTask{flush(id)} }
    fun setting(key: String,value: Any)=runTask{repo.prefs.set(key,value)}
    fun openZip(uri: Uri,flags: Int)=runTask {
        val bytes=withContext(Dispatchers.IO){ repo.context.contentResolver.openInputStream(uri)?.use { it.readBounded(64*1024*1024) } ?: error("ZIPを読めません") }
        val entries=withContext(Dispatchers.IO){SafeZip.read(ByteArrayInputStream(bytes))}
        try{repo.context.contentResolver.takePersistableUriPermission(uri,flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))}catch(_:SecurityException){}
        val archive=Archive(UUID.randomUUID().toString(),uri.toString(),TextCodec.hash(bytes),withContext(Dispatchers.IO){repo.atomic(bytes,UUID.randomUUID().toString()+".zip")},repo.name(uri)); repo.dao.putArchive(archive); zipChoice.value=ZipChoice(archive,entries)
    }
    fun importZip(paths: Set<String>)=runTask {
        val choice=zipChoice.value ?: return@runTask
        val imported=withContext(Dispatchers.IO) { paths.map { path -> val decoded=TextCodec.decode(choice.entries.getValue(path),preferences.value.defaultEncoding); val display=DocumentBytes.editorText(decoded);val body=repo.atomic(display.first.toByteArray()); Session(Document(kind="ZIP",name=path.substringAfterLast('/'),path=path,zipId=choice.archive.id,originalPath=path,encoding=decoded.encoding,newline=decoded.newline,normalizedNewlines=display.second,savedRevision=0,savedBody=body,body=body),display.first) } }
        _tabs.value+=imported; _active.value=imported.lastOrNull()?.document?.id ?: _active.value; zipChoice.value=null; persistTabs()
    }
    suspend fun loadSession(id: String): Session = _tabs.value.find{it.document.id==id && !it.unloaded} ?: withContext(Dispatchers.IO) {val d=repo.dao.get(id) ?: error("文書がありません");Session(d,repo.read(d.body))}
    suspend fun zipBytes(ids: List<String>, archiveId: String?=null): ByteArray = withContext(Dispatchers.IO) {
        val selected=ids.map{loadSession(it)}; val files=if(archiveId!=null) { val a=repo.dao.archive(archiveId) ?: error("元ZIPがありません"); SafeZip.read(repo.file(a.file).inputStream()) } else linkedMapOf()
        val docs=if(archiveId!=null) { repo.dao.all().filter{it.zipId==archiveId}.map { d -> _tabs.value.find{it.document.id==d.id && !it.unloaded} ?: Session(d,repo.read(d.body)) } } else selected
        require(docs.map{it.document.path}.distinct().size==docs.size){"同名パスが重複しています。名前変更してください"}
        docs.forEach{ s -> if(s.document.originalPath!=null && s.document.originalPath!=s.document.path)files.remove(s.document.originalPath); files[s.document.path]=DocumentBytes.encode(s.document,s.text) }
        ByteArrayOutputStream().also {SafeZip.write(files.toList(),it)}.toByteArray()
    }
    fun exportZip(uri: Uri, ids: List<String>, overwrite: Boolean=false, expected: String?=null, archiveId: String?=null)=runTask {
        val captured=ids.map{loadSession(it)}; captured.forEach{flush(it.document.id);repo.snapshot(it.document,it.text,"ZIP保存前",preferences.value.histories)}
        val bytes=zipBytes(ids,archiveId)
        if(overwrite) { val actual=withContext(Dispatchers.IO){repo.context.contentResolver.openInputStream(uri)?.use{TextCodec.hash(it.readBounded(64*1024*1024))} ?: error("元ZIPが読めません")}; require(actual==expected){"元ZIPが外部で変更されています。ZIPのコピーを保存してください"} }
        val hash=withContext(Dispatchers.IO) { repo.context.contentResolver.openOutputStream(uri,"wt")?.use{it.write(bytes);it.flush()} ?: error("ZIPの書込権限がありません"); val verify=repo.context.contentResolver.openInputStream(uri)?.use{TextCodec.hash(it.readBounded(64*1024*1024))}; check(verify==TextCodec.hash(bytes)){"ZIPの書込検証に失敗しました"}; verify!! }
        if(overwrite && archiveId!=null) {
            val archive=repo.dao.archive(archiveId) ?: error("元ZIPがありません"); val file=withContext(Dispatchers.IO){repo.atomic(bytes,UUID.randomUUID().toString()+".zip")}; repo.dao.putArchive(archive.copy(hash=hash,file=file))
            captured.forEach{s -> update(s.document.id){it.copy(document=it.document.copy(savedRevision=s.document.revision),status=if(it.document.revision==s.document.revision) "保存済み" else "自動退避中")};flush(s.document.id)}
        }; message.value="ZIP保存・内容検証が完了しました"
    }
    fun saveOriginalZip()=runTask { val d=current()?.document ?: return@runTask; val a=repo.dao.archive(d.zipId ?: error("ZIP由来の文書ではありません")) ?: error("元ZIPがありません"); val ids=_tabs.value.filter{it.document.zipId==a.id}.map{it.document.id}; exportZip(Uri.parse(a.uri),ids,true,a.hash,a.id) }
    fun openFolder(uri: Uri,flags: Int)=runTask {
        try{repo.context.contentResolver.takePersistableUriPermission(uri,flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))}catch(_:SecurityException){}
        selectedProject=uri.toString()
        val entries=withContext(Dispatchers.IO){ val found=mutableListOf<Pair<Uri,String>>(); var visited=0
            fun walk(dir: DocumentFile, prefix: String, depth: Int) { require(depth<=16){"フォルダ階層が深すぎます"}; dir.listFiles().forEach { f -> require(++visited<=2048){"フォルダの項目が多すぎます"}; val path=prefix+(f.name ?: "file"); if(f.isDirectory) walk(f,"$path/",depth+1) else if(path.substringAfterLast('.').lowercase() in textExtensions) found.add(f.uri to path) } }
            walk(DocumentFile.fromTreeUri(repo.context,uri) ?: error("フォルダが開けません"),"",0);found
        }; folderChoice.value=entries
    }
    val folderChoice=MutableStateFlow<List<Pair<Uri,String>>?>(null)
    fun importFolder(paths: Set<String>)=runTask{ val entries=folderChoice.value ?: return@runTask; entries.filter{it.second in paths}.forEach{openUriNow(it.first,3,null,it.second)};folderChoice.value=null }
    suspend fun backupBytes(): ByteArray = withContext(Dispatchers.IO) {
        _tabs.value.forEach{flush(it.document.id)}
        val files=mutableListOf<Pair<String,ByteArray>>();val docs=JSONArray()
        repo.dao.all().forEach{d -> val obj=JSONObject().put("id",d.id).put("name",d.name).put("path",d.path).put("encoding",d.encoding).put("newline",d.newline).put("normalizedNewlines",d.normalizedNewlines).put("favorite",d.favorite).put("deleted",d.deleted).put("tabOrder",d.tabOrder).put("active",d.active).put("cursor",d.cursor).put("selection",d.selection).put("scroll",d.scroll).put("mode",d.mode); files.add("bodies/${d.id}.txt" to repo.read(d.body).toByteArray()); val history=JSONArray();repo.dao.history(d.id).forEach{r -> history.put(JSONObject().put("id",r.id).put("time",r.time).put("reason",r.reason));files.add("history/${r.id}.txt" to repo.read(r.body).toByteArray())};obj.put("history",history);docs.put(obj) }
        val prefs=JSONObject(); repo.context.settings.data.first().asMap().forEach{(key,value)->prefs.put(key.name,value)}
        files.add("manifest.json" to JSONObject().put("format","pocket-backup-v1").put("documents",docs).put("preferences",prefs).toString().toByteArray());ByteArrayOutputStream().also{SafeZip.write(files,it)}.toByteArray()
    }
    fun exportBackup(uri: Uri)=runTask{val bytes=backupBytes(); withContext(Dispatchers.IO){repo.writeVerified(uri,bytes,64*1024*1024)};message.value="本文・履歴・設定をバックアップしました。外部URIの権限は移行しません"}
    fun importBackup(uri: Uri)=runTask {
        val files=withContext(Dispatchers.IO){repo.context.contentResolver.openInputStream(uri)?.use{SafeZip.read(it)} ?: error("バックアップを読めません")}
        val manifest=JSONObject(files["manifest.json"]?.toString(Charsets.UTF_8) ?: error("バックアップではありません"));require(manifest.getString("format")=="pocket-backup-v1")
        val list=manifest.getJSONArray("documents"); val prepared=mutableListOf<Pair<Document,List<Revision>>>()
        withContext(Dispatchers.IO) {
            for(i in 0 until list.length()) { val obj=list.getJSONObject(i);val original=obj.getString("id"); require(Regex("[A-Za-z0-9-]+").matches(original)); val text=TextCodec.decode(files["bodies/$original.txt"] ?: error("本文が欠損しています")).text;val body=repo.atomic(text.toByteArray());val d=Document(name=obj.getString("name"),path=SafeZip.safePath(obj.getString("path")),encoding=obj.getString("encoding").also{require(it in setOf("UTF-8","UTF-8 BOM","Shift_JIS"))},newline=obj.getString("newline").also{require(it in setOf("LF","CRLF","CR"))},normalizedNewlines=obj.optBoolean("normalizedNewlines",false),body=body,savedRevision=0,cursor=obj.optInt("cursor").coerceIn(0,text.length),selection=obj.optInt("selection").coerceIn(0,text.length),scroll=obj.optInt("scroll").coerceAtLeast(0),mode=obj.optString("mode","編集").takeIf{it in setOf("編集","HTML","Markdown","CSV")} ?: "編集",tabOrder=if(obj.optInt("tabOrder",-1)>=0 && obj.isNull("deleted"))_tabs.value.size+obj.getInt("tabOrder")else -1,active=_tabs.value.isEmpty() && obj.optBoolean("active"),favorite=obj.optBoolean("favorite"),deleted=if(obj.isNull("deleted")) null else obj.getLong("deleted"));val revisions=mutableListOf<Revision>();val history=obj.optJSONArray("history") ?: JSONArray();for(j in 0 until history.length()){val r=history.getJSONObject(j);val bytes=files["history/${r.getString("id")}.txt"] ?: error("履歴が欠損しています");revisions.add(Revision(documentId=d.id,body=repo.atomic(bytes),time=r.getLong("time"),reason=r.getString("reason"),size=bytes.size.toLong()))};prepared.add(d to revisions) }
        }
        repo.db.withTransaction { prepared.forEach{(d,rs)->repo.dao.put(d);rs.forEach{repo.dao.addRevision(it)}} }
        val p=manifest.optJSONObject("preferences"); p?.keys()?.forEach{ key -> val value=p.get(key); if(value is String || value is Boolean || value is Int) repo.prefs.set(key,value) }
        val imported=prepared.map{it.first}.filter{it.tabOrder>=0}.sortedBy{it.tabOrder}
        _tabs.value+=imported.map{Session(it,"",unloaded=true)}
        val next=if(_active.value==null) imported.find{it.active}?.id ?: imported.firstOrNull()?.id else null
        next?.let{activate(it)}
        message.value="本文・履歴・タブ位置・設定を内部メモとして追加しました。既存データは保持されています"
    }
    suspend fun previewResources(id: String): Map<String,ByteArray> = withContext(Dispatchers.IO) {
        val s=session(id); val d=s.document
        if(d.zipId!=null) {
            val a=repo.dao.archive(d.zipId) ?: return@withContext emptyMap();val files=SafeZip.read(repo.file(a.file).inputStream())
            repo.dao.all().filter{it.zipId==d.zipId && it.deleted==null}.forEach{related -> val current=loadSession(related.id);if(current.document.originalPath!=null && current.document.originalPath!=current.document.path)files.remove(current.document.originalPath);files[current.document.path]=current.text.toByteArray()}
            return@withContext files
        }
        if(d.projectUri==null)return@withContext emptyMap()
        val tree=DocumentFile.fromTreeUri(repo.context,Uri.parse(d.projectUri)) ?: return@withContext emptyMap()
        val sources=_tabs.value.filter{it.document.projectUri==d.projectUri}.map{loadSession(it.document.id)};val resources=mutableMapOf<String,ByteArray>();var size=0
        sources.forEach { source ->
            val references=Regex("(?:src|href)\\s*=\\s*[\"']([^\"']+)[\"']|url\\(\\s*[\"']?([^\\)\"']+)").findAll(source.text).take(100)
            references.forEach { match ->
                val relative=(match.groups[1]?.value ?: match.groups[2]?.value ?: "").substringBefore('#').substringBefore('?')
                if(relative.isBlank() || relative.contains(':') || relative.startsWith('/'))return@forEach
                val path=java.net.URI(source.document.path).resolve(relative).normalize().path
                try {SafeZip.safePath(path)}catch(_:Exception){return@forEach}
                var f:DocumentFile?=tree;path.split('/').forEach{part->f=f?.findFile(part)}
                f?.takeIf{it.isFile}?.let{file -> if(file.length()<=4*1024*1024 && size<16*1024*1024){val bytes=repo.context.contentResolver.openInputStream(file.uri)?.use{it.readBounded(4*1024*1024)};if(bytes!=null){resources[path]=bytes;size+=bytes.size}}}
            }
        };resources
    }
    suspend fun crossSearch(query: String, case: Boolean, regex: Boolean): List<Pair<Document,IntRange>> = withContext(Dispatchers.IO) {
        val token=if(regex)null else BodyTokens.query(query)
        val entries=if(token!=null)repo.dao.searchEntries().associateBy{it.documentId}else emptyMap()
        val candidates=if(token!=null)repo.dao.searchIds(token).toSet()else emptySet()
        val live=_tabs.value.filter{!it.unloaded}.associateBy{it.document.id}
        repo.dao.all().filter{it.deleted==null && (token==null || it.id in live || it.id in candidates || entries[it.id]?.bodyPath!=it.body || entries[it.id]?.fallback!=false)}.flatMap{d -> val text=_tabs.value.find{it.document.id==d.id && !it.unloaded}?.text ?: if(d.body.isEmpty())""else repo.read(d.body);SearchTools.matches(text,query,case,regex).take(100).map{d to it}}.take(1000)
    }
    companion object { val textExtensions=setOf("txt","csv","html","htm","js","css","json","md","xml","yaml","yml","kt","java","py","log","svg") }
}
fun InputStream.readBounded(max: Int): ByteArray { val out=ByteArrayOutputStream();val buffer=ByteArray(8192);while(true){val n=read(buffer);if(n<0)break;require(out.size()+n<=max){"読込サイズの上限を超えました"};out.write(buffer,0,n)};return out.toByteArray() }
