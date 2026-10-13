package jp.pocket.texteditor

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity: ComponentActivity() {
    private val vm:EditorViewModel by viewModels()
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContent{PocketApp(vm)}}
    override fun onStop(){vm.flushAll();super.onStop()}
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PocketApp(vm:EditorViewModel) {
    val prefs by vm.preferences.collectAsState(); val docs by vm.documents.collectAsState(); val tabs by vm.tabs.collectAsState(); val active by vm.active.collectAsState()
    val context=LocalContext.current; val scope=rememberCoroutineScope();val clip=LocalClipboardManager.current
    val dark=when(prefs.theme){"ダーク"->true;"ライト"->false;else->isSystemInDarkTheme()}
    var home by rememberSaveable{mutableStateOf(true)};var dialog by rememberSaveable{mutableStateOf("")};var menu by remember{mutableStateOf(false)}
    var editorFocused by remember{mutableStateOf(false)};var symbolFocused by remember{mutableStateOf(false)}
    var zipNotice by rememberSaveable{mutableStateOf("")};var zipOutputIds by rememberSaveable{mutableStateOf<List<String>>(emptyList())};var zipOutputName by rememberSaveable{mutableStateOf("PocketTextEditor.zip")};var zipOutputShare by rememberSaveable{mutableStateOf(false)}
    var secondaryId by rememberSaveable{mutableStateOf("")};var secondaryEditor by remember{mutableStateOf<NativeEditor?>(null)};var searchTarget by rememberSaveable{mutableStateOf("")}
    var full by rememberSaveable{mutableStateOf(false)};var split by rememberSaveable{mutableStateOf(false)};var splitRatio by remember{mutableFloatStateOf(0.5f)}
    var javascript by remember{mutableStateOf(false)};var previewVersion by remember{mutableIntStateOf(0)};var editor by remember{mutableStateOf<NativeEditor?>(null)}
    var selected by remember{mutableStateOf(setOf<String>())};var target by rememberSaveable{mutableStateOf("")}
    var pending by rememberSaveable{mutableStateOf("")};var pendingId by rememberSaveable{mutableStateOf("")};var pendingIds by rememberSaveable{mutableStateOf(arrayListOf<String>())}
    var menuGroup by rememberSaveable{mutableStateOf("")}
    var closeIds by rememberSaveable{mutableStateOf<List<String>>(emptyList())}
    var pendingArchive by rememberSaveable{mutableStateOf<String?>(null)}
    var pendingEncoding by rememberSaveable{mutableStateOf<String?>(null)}
    val snackbar=remember{SnackbarHostState()};val message by vm.message.collectAsState()
    LaunchedEffect(message){message?.let{snackbar.showSnackbar(it);vm.message.value=null}}
    LaunchedEffect(active){if(active!=null && tabs.isNotEmpty())home=false}
    DisposableEffect(full){val activity=context as? ComponentActivity;val controller=activity?.let{WindowCompat.getInsetsController(it.window,it.window.decorView)};if(full)controller?.hide(WindowInsetsCompat.Type.systemBars())else controller?.show(WindowInsetsCompat.Type.systemBars());onDispose{controller?.show(WindowInsetsCompat.Type.systemBars())}}
    DisposableEffect(dark) {
        val activity=context as? ComponentActivity
        val controller=activity?.let{WindowCompat.getInsetsController(it.window,it.window.decorView)}
        val oldStatus=controller?.isAppearanceLightStatusBars;val oldNavigation=controller?.isAppearanceLightNavigationBars
        val window=activity?.window;val oldStatusColor=window?.statusBarColor;val oldNavigationColor=window?.navigationBarColor
        val barColor=if(dark)0xff111827.toInt()else 0xfff9fafb.toInt()
        window?.statusBarColor=barColor;window?.navigationBarColor=barColor
        controller?.isAppearanceLightStatusBars=!dark;controller?.isAppearanceLightNavigationBars=!dark
        onDispose {oldStatus?.let{controller?.isAppearanceLightStatusBars=it};oldNavigation?.let{controller?.isAppearanceLightNavigationBars=it};oldStatusColor?.let{window?.statusBarColor=it};oldNavigationColor?.let{window?.navigationBarColor=it}}
    }
    val fileLauncher=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){result ->
        if(result.resultCode==android.app.Activity.RESULT_OK) {
            val data=result.data;val uri=data?.data;val flags=data?.flags ?: 0
            when(pending){
                "open"->{val uris=mutableListOf<Uri>();data?.clipData?.let{for(i in 0 until it.itemCount)uris.add(it.getItemAt(i).uri)};uri?.let{uris.add(it)};uris.distinct().forEach{vm.openUri(it,flags,pendingEncoding)}}
                "saveAs","copy"->uri?.let{vm.export(pendingId,it,pending=="saveAs",flags)}
                "zipOpen"->uri?.let{vm.openZip(it,flags)}
                "zipCopy"->uri?.let{vm.exportZip(it,pendingIds.toList())}
                "archiveCopy"->uri?.let{vm.exportZip(it,pendingIds.toList(),archiveId=pendingArchive)}
                "backupExport"->uri?.let{vm.exportBackup(it)}
                "backupImport"->uri?.let{vm.importBackup(it)}
                "folder"->uri?.let{vm.openFolder(it,flags)}
            }
        };pending=""
    }
    fun pick(op:String,id:String="",name:String="document.txt",ids:List<String> = tabs.map{it.document.id},encoding:String?=null) {
        pending=op;pendingArchive=vm.current()?.document?.zipId;pendingId=id;pendingIds=ArrayList(ids);pendingEncoding=encoding
        val create=op in setOf("saveAs","copy","zipCopy","archiveCopy","backupExport")
        val intent=when{op=="folder"->Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);create->Intent(Intent.ACTION_CREATE_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type=if(op in setOf("zipCopy","archiveCopy","backupExport"))"application/zip"else "text/plain";putExtra(Intent.EXTRA_TITLE,name)};else->Intent(Intent.ACTION_OPEN_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type="*/*";putExtra(Intent.EXTRA_ALLOW_MULTIPLE,op=="open")}}
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        try{fileLauncher.launch(intent)}catch(e:Exception){vm.message.value="ファイル選択画面を開けません：${e.message}"}
    }
    suspend fun textFor(ids:List<String>):String = SearchTools.combined(tabs.filter{it.document.id in ids}.map{it.document.path to vm.loadSession(it.document.id).text})
    fun copy(text:String){ val bytes=text.toByteArray().size;if(bytes>256*1024){vm.message.value="${bytes/1024}KBです。ファイル共有を利用してください";return};try{clip.setText(AnnotatedString(text));vm.message.value="${bytes}バイトをコピーしました"}catch(e:Exception){vm.message.value="コピーできませんでした。ファイル共有をご利用ください"} }
    fun share(text:String){context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_TEXT,text)},"テキストを共有"))}
    fun shareFile(zip:Boolean,ids:List<String>) {vm.runTask{
        val bytes=if(zip)vm.zipBytes(ids)else{val s=tabs.first{it.document.id in ids};DocumentBytes.encode(s.document,s.text)}
        val name=if(zip)"PocketTextEditor.zip"else tabs.first{it.document.id in ids}.document.name.substringAfterLast('/').replace(Regex("[^\\p{L}\\p{N}._-]"),"_")
        val uri=withContext(Dispatchers.IO){val file=File(context.cacheDir,"shared/${UUID.randomUUID()}/$name");file.parentFile?.mkdirs();file.writeBytes(bytes);FileProvider.getUriForFile(context,"${context.packageName}.files",file)}
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type=if(zip)"application/zip"else "text/plain";putExtra(Intent.EXTRA_STREAM,uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},"ファイルを共有"))
    }}
    fun requestZip(ids:List<String>,name:String="PocketTextEditor.zip",share:Boolean=false) {vm.runTask {
        val changes=vm.zipNameChanges(ids)
        if(changes.isEmpty()){if(share)shareFile(true,ids)else pick("zipCopy",name=name,ids=ids)}
        else {zipOutputIds=ids;zipOutputName=name;zipOutputShare=share;zipNotice=changes.joinToString("\n"){"${it.first} → ${it.second}"};dialog="zipNames"}
    }}
    fun action(label:String){menu=false;val s=vm.current();when(label){
        "HTMLプレビュー"->s?.let{vm.mode(it.document.id,"HTML")};"CSVプレビュー"->s?.let{vm.mode(it.document.id,"CSV")};"Markdownプレビュー"->s?.let{vm.mode(it.document.id,"Markdown")};"編集に戻る"->s?.let{vm.mode(it.document.id,"編集")}
        "新規"->{vm.newMemo();home=false};"開く"->pick("open");"保存"->s?.let{vm.save(it.document.id)}
        "名前を付けて保存"->s?.let{pick("saveAs",it.document.id,it.document.name)}
        "コピーを保存"->s?.let{pick("copy",it.document.id,it.document.name)}
        "Undo"->editor?.undo();"Redo"->editor?.redo();"検索","置換"->{s?.let{vm.mode(it.document.id,"編集")};searchTarget=s?.document?.id.orEmpty();dialog="search"}
        "次タブ"->{val i=tabs.indexOfFirst{it.document.id==active};tabs.getOrNull((i+1)%tabs.size.coerceAtLeast(1))?.let{vm.activate(it.document.id)}}
        "現在タブコピー"->s?.let{copy(it.text)};"全タブ結合コピー"->vm.runTask{copy(textFor(tabs.map{it.document.id}))};"選択タブ結合コピー"->vm.runTask{copy(textFor(selected.toList()))}
        "本文を共有"->s?.let{share(it.text)};"ファイルを共有"->s?.let{shareFile(false,listOf(it.document.id))};"全タブZIP共有"->requestZip(tabs.map{it.document.id},share=true)
        "ZIPを開く"->pick("zipOpen");"ZIP内ファイル一覧"->vm.showZipBrowser();"全タブZIP保存"->requestZip(tabs.map{it.document.id})
        "選択文書ZIP保存"->requestZip(docs.filter{it.id in selected}.map{it.id},"selected.zip")
        "選択文書結合コピー"->vm.runTask{copy(SearchTools.combined(docs.filter{it.id in selected}.map{it.path to vm.loadSession(it.id).text}))}
        "選択タブZIP保存"->requestZip(tabs.filter{it.document.id in selected}.map{it.document.id},"selected.zip")
        "元ZIPへ保存"->dialog="zipConfirm";"ZIPのコピーを保存"->pick("archiveCopy",name="project.zip",ids=tabs.filter{it.document.zipId==s?.document?.zipId}.map{it.document.id})
        "フォルダを開く"->pick("folder");"バックアップを書き出す"->pick("backupExport",name="PocketTextEditor-backup.zip");"バックアップを読み込む"->pick("backupImport")
        "ライセンス"->dialog="licenses";"設定"->dialog="settings";"タブ一覧"->dialog="tabs";"履歴"->dialog="history";"文字コード・改行"->dialog="format";"指定行へ移動"->dialog="jump"
        "保存内容と比較"->dialog="diff";"ファイル間比較"->dialog="fileDiff";"横断検索"->dialog="crossSearch";"補完候補"->dialog="completion"
        "名前変更"->{target=s?.document?.id ?: "";dialog="rename"};"複製"->s?.let{vm.duplicate(it.document)}
        "全タブを閉じる"->{closeIds=tabs.map{it.document.id};dialog="closeAll"}
        "閉じる"->{target=s?.document?.id ?: "";dialog="close"};"閉じたタブを復元"->dialog="closed"
        "外部ファイルを削除"->{target=s?.document?.id.orEmpty();dialog="deleteExternal"};"本文を削除"->dialog="clear";"フルスクリーン"->full=!full;"分割編集"->dialog="splitPick";"分割表示"->{split=!split;if(!split){secondaryId="";vm.endSplit()}}
        "JavaScript許可"->dialog="javascript";"プレビュー更新"->{previewVersion++}
        "切り取り"->editor?.onTextContextMenuItem(android.R.id.cut);"コピー"->editor?.onTextContextMenuItem(android.R.id.copy);"貼り付け"->editor?.onTextContextMenuItem(android.R.id.paste)
        else->editor?.lineAction(label)
    }}
    fun symbolAction(symbol:String){when(symbol){
        "←"->editor?.move(-1);"→"->editor?.move(1);"選択←"->editor?.move(-1,true);"選択→"->editor?.move(1,true)
        "行頭"->editor?.lineBoundary(false);"行末"->editor?.lineBoundary(true);"Undo"->editor?.undo();"Redo"->editor?.redo()
        "字下げ+","字下げ−"->editor?.lineAction(symbol)
        else->editor?.insert(if(symbol=="Tab")editor?.indentUnit() ?: "    " else symbol)
    }}
    val session=tabs.find{it.document.id==active}
    val keyboardVisible=WindowInsets.ime.getBottom(LocalDensity.current)>0
    var previewExtra by remember{mutableStateOf<Map<String,ByteArray>>(emptyMap())}
    LaunchedEffect(active,previewVersion,session?.document?.mode){
        previewExtra=if(session!=null && session.document.mode!="編集")try{vm.previewResources(session.document.id)}catch(e:Exception){vm.message.value="関連リソース読込失敗：${e.message}";emptyMap()}else emptyMap()
    }

    MaterialTheme(colorScheme=pocketColors(dark)) {
        Scaffold(snackbarHost={SnackbarHost(snackbar)},modifier=Modifier.imePadding()) {padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if(home || session==null) {
                    Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically){Image(painterResource(R.drawable.ic_launcher),contentDescription=null,modifier=Modifier.size(40.dp));Text("Pocket Text Editor",Modifier.weight(1f).padding(horizontal=8.dp),style=MaterialTheme.typography.titleLarge,maxLines=2);ThemeButton(dark){vm.setting("theme",if(dark)"ライト"else "ダーク")};TextButton(onClick={dialog="settings"}){Text("設定")}}
                    Row(Modifier.padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(onClick={action("新規")}){Text("＋ 新規")};OutlinedButton(onClick={action("開く")}){Text("開く")};TextButton(onClick={dialog="homeMenu"}){Text("その他")}}
                    HomeList(docs,prefs.sort,selected,{selected=it},onOpen={vm.openMemo(it);home=false},onFavorite={vm.favorite(it)},onTrash={target=it.id;dialog="trash"},onRestore={vm.restoreTrash(it)},onRename={target=it.id;dialog="rename"},onDuplicate={vm.duplicate(it)},onSort={vm.setting("sort",it)})
                } else {
                    if(!full) {
                        EditorHeader(session.document.name,session.status,PwaCompatibility.unsavedCount(tabs),session.status.contains("失敗") || session.status.contains("競合"),dark,
                            {dialog="documentInfo"},{home=true;vm.flushAll()},{editor?.undo()},{editor?.redo()},{action("保存")},{action("開く")},{dialog="quickTools"},{vm.setting("theme",if(dark)"ライト"else "ダーク")}) {
                            Box{IconButton(onClick={menu=true},modifier=Modifier.semantics{contentDescription="その他の操作"}){Text("⋮")};DropdownMenu(expanded=menu,onDismissRequest={menu=false},modifier=Modifier.heightIn(max=480.dp)){editorMenuGroups.keys.forEach{group->DropdownMenuItem(text={Text("$group ›")},onClick={menu=false;menuGroup=group;dialog="menuGroup"})}}}
                        }
                        if(!keyboardVisible)Text("${session.document.kind} · ${session.document.encoding} · ${session.document.newline} · ${session.document.uri ?: session.document.path}",style=MaterialTheme.typography.labelSmall,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(horizontal=12.dp))
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                            LazyRow(Modifier.weight(1f).testTag("tab-strip")) {items(tabs,key={it.document.id}){tab->
                                Row(verticalAlignment=Alignment.CenterVertically) {
                                    FilterChip(selected=tab.document.id==active,onClick={vm.activate(tab.document.id)},label={Text(tab.document.name+if(tab.document.kind!="MEMO" && tab.document.revision!=tab.document.savedRevision)" •"else "",modifier=Modifier.widthIn(max=160.dp),maxLines=1,overflow=TextOverflow.Ellipsis)},modifier=Modifier.padding(start=4.dp))
                                    IconButton(onClick={target=tab.document.id;dialog="close"},modifier=Modifier.semantics{contentDescription="${tab.document.name}のタブを閉じる"}){Text("✕")}
                                }
                            }}
                            IconButton(onClick={action("新規")},modifier=Modifier.semantics{contentDescription="新しいタブを追加"}){Text("＋")}
                            TextButton(onClick={action("全タブを閉じる")},modifier=Modifier.semantics{contentDescription="全タブを閉じる"}){Text("✕ 全タブ")}
                        }
                        if(!keyboardVisible || session.document.mode!="編集")Row(Modifier.horizontalScroll(rememberScrollState()),verticalAlignment=Alignment.CenterVertically){val modes=PwaCompatibility.modes;modes.forEach{mode->TextButton(onClick={vm.mode(session.document.id,mode)}){Text(if(mode==session.document.mode)"● $mode"else mode)}};if(session.document.mode!="編集")TextButton(onClick={previewVersion++}){Text("更新")}}
                    } else TextButton(onClick={full=false}){Text("操作を表示")}
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                        val viewportWidth=maxWidth
                        Row(Modifier.fillMaxSize()) {
                        if(viewportWidth>900.dp) LazyColumn(Modifier.width(150.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceVariant)){items(tabs,key={it.document.id}){tab->Row(verticalAlignment=Alignment.CenterVertically){TextButton(onClick={vm.activate(tab.document.id)},modifier=Modifier.weight(1f)){Text(tab.document.name)};IconButton(onClick={target=tab.document.id;dialog="close"},modifier=Modifier.semantics{contentDescription="${tab.document.name}のタブを閉じる"}){Text("✕")}}}}
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                        val showPreview=session.document.mode!="編集"
                        val secondary=tabs.find{it.document.id==secondaryId && it.document.id!=session.document.id}
                        if(secondary!=null && split) Row(Modifier.fillMaxSize()) {
                            EditorSurface(session,prefs,dark,Modifier.weight(splitRatio).fillMaxHeight(),{editor=it},{text,c,s,y->vm.edit(session.document.id,text,c,s,y)},::action,vm.editorUndo,{editorFocused=it})
                            Box(Modifier.width(12.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
                            Column(Modifier.weight(1f-splitRatio).fillMaxHeight()) {
                                Row { Text(secondary.document.name,Modifier.weight(1f));TextButton(onClick={vm.save(secondary.document.id)}){Text("保存")};TextButton(onClick={secondaryId="";vm.endSplit()}){Text("終了")} }
                                EditorSurface(secondary,prefs,dark,Modifier.weight(1f).fillMaxWidth(),{secondaryEditor=it},{text,c,s,y->vm.edit(secondary.document.id,text,c,s,y)},{label->when(label){"保存"->vm.save(secondary.document.id);"検索","置換"->{vm.mode(secondary.document.id,"編集");searchTarget=secondary.document.id;dialog="search"};else->action(label)}},vm.editorUndo)
                            }
                        } else if(showPreview && (split || viewportWidth>700.dp)) Row(Modifier.fillMaxSize()) {
                            EditorSurface(session,prefs,dark,Modifier.weight(splitRatio).fillMaxHeight(),{editor=it},{text,c,s,y->vm.edit(session.document.id,text,c,s,y)},::action,vm.editorUndo,{editorFocused=it})
                            Box(Modifier.width(12.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant).pointerInput(Unit){detectHorizontalDragGestures{change,amount->change.consume();splitRatio=(splitRatio+amount/size.width.coerceAtLeast(1)/40).coerceIn(0.2f,0.8f)}})
                            DocumentPreview(session,tabs,prefs,javascript,previewVersion,Modifier.weight(1f-splitRatio).fillMaxHeight(),previewExtra,vm::linkedPreviewSources)
                        } else if(showPreview)DocumentPreview(session,tabs,prefs,javascript,previewVersion,Modifier.fillMaxSize(),previewExtra,vm::linkedPreviewSources)
                        else EditorSurface(session,prefs,dark,Modifier.fillMaxSize(),{editor=it},{text,c,s,y->vm.edit(session.document.id,text,c,s,y)},::action,vm.editorUndo,{editorFocused=it})
                        }
                        }
                    }
                    if(PwaCompatibility.symbolBarVisible(prefs.symbolMode,session.document.mode=="編集",editorFocused || symbolFocused)) {
                        LazyRow(Modifier.fillMaxWidth().heightIn(min=48.dp).background(MaterialTheme.colorScheme.surfaceVariant).onFocusChanged{symbolFocused=it.hasFocus}.focusGroup()){items(listOf("←","→","行頭","行末","選択←","選択→","Undo","Redo","字下げ+","字下げ−")+prefs.symbols.split(' ').filter{it.isNotEmpty()}){symbol->TextButton(onClick={symbolAction(symbol)},modifier=if(symbol in setOf("←","→","選択←","選択→"))Modifier.pointerInput(symbol){detectTapGestures(onPress={symbolAction(symbol);val job=scope.launch{delay(400);while(isActive){symbolAction(symbol);delay(60)}};tryAwaitRelease();job.cancel()})}else Modifier){Text(symbol)}}}
                    }
                    val cursor=session.document.cursor.coerceIn(0,session.text.length)
                    val metrics by produceState<TextMetrics?>(null,session.text,cursor){value=null;value=kotlinx.coroutines.runInterruptible(Dispatchers.Default){TextMetrics.calculate(session.text,cursor)}}
                    Text("${session.text.length}文字 · ${metrics?.let{"${it.lines}行 · ${it.line}:${it.column}"} ?: "行数を計算中…"} · 選択 ${kotlin.math.abs(session.document.selection-cursor)}",style=MaterialTheme.typography.labelSmall,modifier=Modifier.padding(6.dp))
                }
            }
        }
        when(dialog){
            "documentInfo"->session?.let{TextDialog("文書情報","${it.document.name}\n${it.document.kind} · ${it.document.encoding} · ${it.document.newline}\n${it.document.uri ?: it.document.path}\n${it.status}"){dialog=""}}
            "quickTools"->MenuDialog("よく使う操作",listOf("検索","現在タブコピー","全タブ結合コピー","ZIPを開く","ZIP内ファイル一覧","設定"),{dialog="";action(it)},{dialog=""})
            "zipNames"->ZipNamesDialog(zipNotice,{dialog="";if(zipOutputShare)shareFile(true,zipOutputIds)else pick("zipCopy",name=zipOutputName,ids=zipOutputIds)},{dialog=""})
            "menuGroup"->MenuDialog(menuGroup,editorMenuGroups[menuGroup].orEmpty(),{dialog="";action(it)},{dialog=""})
            "homeMenu"->MenuDialog("ファイル操作",listOf("ZIPを開く","ZIP内ファイル一覧","フォルダを開く","全タブZIP保存","バックアップを書き出す","バックアップを読み込む","閉じたタブを復元","ライセンス","選択文書ZIP保存","選択文書結合コピー"),{dialog="";action(it)},{dialog=""})
            "licenses"->TextDialog("ライブラリライセンス",context.assets.open("THIRD_PARTY_NOTICES.txt").bufferedReader().use{it.readText()}){dialog=""}
            "settings"->SettingsDialog(prefs,{key,value->vm.setting(key,value)},{dialog=""})
            "search"->(tabs.find{it.document.id==searchTarget} ?: session)?.let{SearchDialog(it,vm,if(it.document.id==secondaryId)secondaryEditor else editor,{dialog=""})}
            "splitPick"->ClosedDialog(docs.filter{it.id!=session?.document?.id && it.deleted==null},{d->vm.runTask{vm.prepareSplit(d.id);secondaryId=d.id;split=true;dialog=""}},{dialog=""})
            "crossSearch"->CrossSearchDialog(vm,{doc,range->vm.openMemo(doc);scope.launch{delay(100);vm.updatePosition(doc.id,range.first,range.last+1,0)};home=false;dialog=""},{dialog=""})
            "history"->session?.let{HistoryDialog(it,vm,{dialog=""})}
            "format"->session?.let{FormatDialog(it,{enc,nl->vm.changeFormat(it.document.id,enc,nl);dialog=""},{enc->vm.reload(it.document.id,enc);dialog=""},{dialog=""})}
            "jump"->InputDialog("指定行へ移動","行番号","1",{value->val n=value.toIntOrNull() ?: 1;val starts=sequenceOf(0)+session?.text.orEmpty().mapIndexedNotNull{i,c->if(c=='\n')i+1 else null}.asSequence();val pos=starts.drop((n-1).coerceAtLeast(0)).firstOrNull() ?: session?.text.orEmpty().length;editor?.setSelection(pos);editor?.requestFocus();dialog=""},{dialog=""})
            "rename"->{val doc=docs.find{it.id==target} ?: tabs.find{it.document.id==target}?.document;InputDialog("名前変更","相対パス／ファイル名",doc?.path ?: "",{vm.rename(target,it);dialog=""},{dialog=""})}
            "trash"->ConfirmDialog("ごみ箱へ移動","本文は${prefs.trashDays}日間保持されます。未保存の内容も履歴に退避します",{vm.trash(target);dialog=""},{dialog=""})
            "deleteExternal"->ConfirmDialog("外部ファイルを削除","${tabs.find{it.document.id==target}?.document?.uri.orEmpty()} を提供元から削除します。本文を内部退避し、ごみ箱に保護します",{vm.deleteExternal(target);dialog=""},{dialog=""})
            "clear"->ConfirmDialog("本文を削除","現在の本文を履歴に保護してから削除します",{session?.let{s->vm.runTask{vm.repo.snapshot(s.document,s.text,"本文削除前",prefs.histories);vm.edit(s.document.id,"",0,0,0);vm.flush(s.document.id)}};dialog=""},{dialog=""})
            "closeAll"->ConfirmDialog("全タブを閉じる","${closeIds.size}個のタブを閉じますか？本文を内部退避して閉じます。外部ファイルには保存しません。閉じたタブは復元できます",{vm.closeAll(closeIds.toList());dialog=""},{dialog=""},"全タブを閉じる")
            "close"->ConfirmDialog("タブを閉じる","「${tabs.find{it.document.id==target}?.document?.name.orEmpty()}」を閉じますか？${tabs.find{it.document.id==target}?.status.orEmpty()}。本文を内部退避して閉じます。外部ファイルには保存しません",{vm.close(target);dialog=""},{dialog=""})
            "tabs"->TabDialog(tabs,selected,{selected=it},{vm.activate(it);dialog=""},{vm.move(it,-1)},{vm.move(it,1)},{target=it;dialog="close"},{dialog=""})
            "closed"->ClosedDialog(docs.filter{it.tabOrder<0 && it.deleted==null},{vm.openMemo(it);dialog="";home=false},{dialog=""})
            "diff"->session?.let{DiffDialog(it,vm,null,{dialog=""})}
            "fileDiff"->session?.let{s->ClosedDialog(docs.filter{it.id!=s.document.id && it.deleted==null},{d->target=d.id;dialog="diffTarget"},{dialog=""})}
            "diffTarget"->session?.let{DiffDialog(it,vm,docs.find{d->d.id==target},{dialog=""})}
            "completion"->session?.let{s->val prefix=s.text.take(s.document.cursor).takeLastWhile{it.isLetterOrDigit() || it=='_'};val choices=(Regex("[A-Za-z_$][A-Za-z0-9_$]*").findAll(s.text).take(10_000).map{it.value}.toList()+listOf("function","const","return","document","querySelector","addEventListener","console.log","display","background","color")).distinct().filter{it.startsWith(prefix) && it!=prefix}.take(30);MenuDialog("補完候補",choices,{editor?.insert(it.removePrefix(prefix));dialog=""},{dialog=""})}
            "javascript"->ConfirmDialog("JavaScriptを実行","開いているHTML／関連JSがプレビュー内で実行されます。外部通信・端末ファイルアクセス・アプリの権限は遮断します",{javascript=true;previewVersion++;dialog=""},{javascript=false;previewVersion++;dialog=""},"この画面で許可","OFF")
            "zipConfirm"->ConfirmDialog("元ZIPへ保存","元ZIPの競合を検査して、同じプロジェクトの変更を反映します。上書き前の原本と履歴は内部に保持します",{vm.saveOriginalZip();dialog=""},{dialog=""})
        }
        val conflict by vm.conflict.collectAsState()
        conflict?.let{c->AlertDialog(onDismissRequest={vm.conflict.value=null},title={Text("元ファイルが変更されています")},text={Column{Text("確認なしの上書きを停止しました");TextButton(onClick={target=c.documentId;dialog="conflictDiff"}){Text("比較")};TextButton(onClick={vm.reload(c.documentId);vm.conflict.value=null}){Text("再読込（現在の本文を履歴に保護）")};TextButton(onClick={pick("copy",c.documentId,vm.session(c.documentId).document.name);vm.conflict.value=null}){Text("コピー保存")}}},confirmButton={TextButton(onClick={vm.save(c.documentId,c.hash);vm.conflict.value=null}){Text("上書きを確認")}},dismissButton={TextButton(onClick={vm.conflict.value=null}){Text("戻る")}});if(dialog=="conflictDiff")TextDialog("競合の比較",SearchTools.diff(c.external,vm.session(c.documentId).text)){dialog=""}}
        val encodingChoice by vm.encodingChoice.collectAsState()
        encodingChoice?.let{request-> EncodingReadDialog(request.first,vm,{enc->vm.encodingChoice.value=null;vm.openUri(request.first,request.second,enc)},{vm.encodingChoice.value=null})}
        val zip by vm.zipChoice.collectAsState();zip?.let{choice->FileChoiceDialog("ZIP内のファイル",choice.entries.keys.toList(),{vm.importZip(it)},{vm.zipChoice.value=null})}
        val folder by vm.folderChoice.collectAsState();folder?.let{entries->FileChoiceDialog("許可フォルダのテキスト",entries.map{it.second},{vm.importFolder(it)},{vm.folderChoice.value=null})}
    }
}
