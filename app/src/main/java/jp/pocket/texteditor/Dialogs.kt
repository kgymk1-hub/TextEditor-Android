package jp.pocket.texteditor

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun ConfirmDialog(title:String,text:String,confirm:()->Unit,dismiss:()->Unit,yes:String="実行",no:String="キャンセル") {AlertDialog(onDismissRequest=dismiss,title={Text(title)},text={Text(text)},confirmButton={TextButton(onClick=confirm){Text(yes)}},dismissButton={TextButton(onClick=dismiss){Text(no)}})}
@Composable
fun InputDialog(title:String,label:String,initial:String,confirm:(String)->Unit,dismiss:()->Unit){var value by remember(title,initial){mutableStateOf(initial)};AlertDialog(onDismissRequest=dismiss,title={Text(title)},text={OutlinedTextField(value,{value=it},label={Text(label)},singleLine=true)},confirmButton={TextButton(onClick={confirm(value)}){Text("決定")}},dismissButton={TextButton(onClick=dismiss){Text("キャンセル")}})}
@Composable
fun TextDialog(title:String,text:String,dismiss:()->Unit){AlertDialog(onDismissRequest=dismiss,title={Text(title)},text={SelectionContainerCompat(text)},confirmButton={TextButton(onClick=dismiss){Text("閉じる")}})}
@Composable
private fun SelectionContainerCompat(text:String){androidx.compose.foundation.text.selection.SelectionContainer{Text(text,modifier=Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()),style=MaterialTheme.typography.bodySmall)}}
@Composable
fun MenuDialog(title:String,items:List<String>,select:(String)->Unit,dismiss:()->Unit){AlertDialog(onDismissRequest=dismiss,title={Text(title)},text={LazyColumn(Modifier.heightIn(max=440.dp)){items(items){TextButton(onClick={select(it)}){Text(it)}}}},confirmButton={TextButton(onClick=dismiss){Text("閉じる")}})}
@Composable
fun HomeList(docs:List<Document>,sort:String,selected:Set<String>,onSelect:(Set<String>)->Unit,onOpen:(Document)->Unit,onFavorite:(Document)->Unit,onTrash:(Document)->Unit,onRestore:(Document)->Unit,onRename:(Document)->Unit,onDuplicate:(Document)->Unit,onSort:(String)->Unit) {
    var query by remember{mutableStateOf("")};var category by remember{mutableStateOf("メモ")};val context=LocalContext.current;var matches by remember{mutableStateOf(setOf<String>())}
    LaunchedEffect(query,docs){matches=withContext(Dispatchers.IO){if(query.isBlank())emptySet()else{val repo=(context.applicationContext as PocketApplication).repository;val candidates=repo.literalCandidates(query);docs.filter{d -> d.name.contains(query,true) || d.path.contains(query,true) || d.encoding.contains(query,true) || date(d.updated).contains(query) || ((candidates==null || d.id in candidates) && runCatching{repo.read(d.body).contains(query,true)}.getOrDefault(false))}.map{it.id}.toSet()}}}
    OutlinedTextField(query,{query=it},label={Text("名前・本文・拡張子・更新日時で検索")},singleLine=true,modifier=Modifier.fillMaxWidth().padding(16.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal=8.dp)){listOf("メモ","最近","お気に入り","ごみ箱").forEach{c->FilterChip(selected=category==c,onClick={category=c},label={Text(c)},modifier=Modifier.padding(4.dp))};TextButton(onClick={onSort(if(sort=="更新日時")"名前"else "更新日時")}){Text("並順：$sort")}}
    val list=docs.filter{d -> (when(category){"ごみ箱"->d.deleted!=null;"最近"->d.kind=="EXTERNAL" && d.deleted==null;"お気に入り"->d.favorite && d.deleted==null;else->d.kind=="MEMO" && d.deleted==null}) && (query.isBlank() || d.id in matches)}.let{if(sort=="名前")it.sortedBy{d->d.name}else it.sortedByDescending{d->if(category=="最近")d.viewed else d.updated}}
    Text("${list.size}件 · 選択 ${selected.size}件",modifier=Modifier.padding(horizontal=16.dp),style=MaterialTheme.typography.labelSmall)
    LazyColumn(Modifier.fillMaxWidth()){items(list,key={it.id}){doc->
        var more by remember{mutableStateOf(false)}
        Row(Modifier.fillMaxWidth().clickable{if(doc.deleted==null)onOpen(doc)}.padding(horizontal=8.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically){Checkbox(checked=doc.id in selected,onCheckedChange={onSelect(if(it)selected+doc.id else selected-doc.id)});Column(Modifier.weight(1f)){Text(doc.name,style=MaterialTheme.typography.titleMedium);Text("${doc.path} · ${date(doc.updated)}",style=MaterialTheme.typography.labelSmall,maxLines=2)};TextButton(onClick={onFavorite(doc)}){Text(if(doc.favorite)"★"else "☆")};Box{TextButton(onClick={more=true}){Text("⋮")};DropdownMenu(expanded=more,onDismissRequest={more=false}){if(doc.deleted!=null)DropdownMenuItem(text={Text("復元")},onClick={more=false;onRestore(doc)})else{DropdownMenuItem(text={Text("名前変更")},onClick={more=false;onRename(doc)});DropdownMenuItem(text={Text("複製")},onClick={more=false;onDuplicate(doc)});if(doc.kind=="MEMO")DropdownMenuItem(text={Text("ごみ箱へ")},onClick={more=false;onTrash(doc)})}}}}
        HorizontalDivider(Modifier.padding(horizontal=16.dp))
    }}
}
private fun date(time:Long)=SimpleDateFormat("yyyy/MM/dd HH:mm",Locale.JAPAN).format(Date(time))
@Composable
fun TabDialog(tabs:List<Session>,selected:Set<String>,onSelect:(Set<String>)->Unit,onOpen:(String)->Unit,up:(String)->Unit,down:(String)->Unit,close:(String)->Unit,dismiss:()->Unit){AlertDialog(onDismissRequest=dismiss,title={Text("全タブ・順序・選択")},text={LazyColumn(Modifier.heightIn(max=480.dp)){items(tabs,key={it.document.id}){s->Column{Row(verticalAlignment=Alignment.CenterVertically){Checkbox(checked=s.document.id in selected,onCheckedChange={onSelect(if(it)selected+s.document.id else selected-s.document.id)});TextButton(onClick={onOpen(s.document.id)}){Text(s.document.path)}};Row{TextButton(onClick={up(s.document.id)}){Text("↑")};TextButton(onClick={down(s.document.id)}){Text("↓")};TextButton(onClick={close(s.document.id)}){Text("閉じる")}}}}}},confirmButton={TextButton(onClick=dismiss){Text("閉じる")}})}
@Composable
fun ClosedDialog(docs:List<Document>,select:(Document)->Unit,dismiss:()->Unit){AlertDialog(onDismissRequest=dismiss,title={Text("文書を選択")},text={LazyColumn(Modifier.heightIn(max=480.dp)){items(docs,key={it.id}){d->TextButton(onClick={select(d)}){Text(d.path)}}}},confirmButton={TextButton(onClick=dismiss){Text("閉じる")}})}
@Composable
fun FileChoiceDialog(title:String,paths:List<String>,confirm:(Set<String>)->Unit,dismiss:()->Unit){var selected by remember(paths){mutableStateOf(setOf<String>())};AlertDialog(onDismissRequest=dismiss,title={Text(title)},text={Column{Text("テキストとして開くファイルを選択してください");Row{TextButton(onClick={selected=paths.filter{it.substringAfterLast('.').lowercase() in EditorViewModel.textExtensions}.toSet()}){Text("対応テキストを全選択")};TextButton(onClick={selected=emptySet()}){Text("解除")}};LazyColumn(Modifier.heightIn(max=400.dp)){items(paths){path->Row(Modifier.fillMaxWidth().clickable{selected=if(path in selected)selected-path else selected+path},verticalAlignment=Alignment.CenterVertically){Checkbox(path in selected,{selected=if(it)selected+path else selected-path});Text(path)}}}}},confirmButton={TextButton(onClick={confirm(selected)},enabled=selected.isNotEmpty()){Text("${selected.size}件を開く")}},dismissButton={TextButton(onClick=dismiss){Text("キャンセル")}})}
@Composable
fun SearchDialog(s: Session, vm: EditorViewModel, editor: NativeEditor?, dismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var case by remember { mutableStateOf(false) }
    var regex by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var ranges by remember { mutableStateOf<List<IntRange>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(query,case,regex,s.text) {
        try { ranges=withContext(Dispatchers.Default){SearchTools.matches(s.text,query,case,regex)};error=null }
        catch(e:Exception) { ranges=emptyList();error=e.message }
    }
    fun jump(next:Boolean) {
        val pos=s.document.selection
        val range=if(next) ranges.firstOrNull{it.first>=pos && it.first!=s.document.cursor} ?: ranges.firstOrNull()
                  else ranges.lastOrNull{it.first<s.document.cursor} ?: ranges.lastOrNull()
        range?.let{vm.updatePosition(s.document.id,it.first,(it.last+1).coerceAtLeast(it.first),s.document.scroll)}
    }
    AlertDialog(
        onDismissRequest=dismiss,
        title={Text("検索・置換")},
        text={Column {
            OutlinedTextField(query,{query=it},label={Text("検索")},singleLine=true)
            OutlinedTextField(replacement,{replacement=it},label={Text("置換")},singleLine=true)
            Toggle("大文字小文字区別",case,{case=it})
            Toggle("正規表現",regex,{regex=it})
            Text(error ?: "${ranges.size}件${if(ranges.size==100000) "（上限に達しました）" else ""}")
            Row {
                TextButton(onClick={jump(false)}){Text("前へ")}
                TextButton(onClick={jump(true)}){Text("次へ")}
            }
            Row {
                TextButton(onClick={
                    val found=ranges.firstOrNull{it.first==s.document.cursor}
                    if(found==null) jump(true)
                    else {
                        try {
                            val value=if(regex) SearchTools.regex(query,case,true).replace(s.text.substring(found.first,found.last+1),replacement) else replacement
                            editor?.setSelection(found.first,(found.last+1).coerceAtLeast(found.first))
                            editor?.insert(value)
                        } catch(e:Exception) { vm.message.value=e.message }
                    }
                }){Text("1件置換")}
                TextButton(onClick={confirm=true},enabled=ranges.isNotEmpty()){Text("全置換")}
            }
        }},
        confirmButton={TextButton(onClick=dismiss){Text("閉じる")}}
    )
    if(confirm) ConfirmDialog("${ranges.size}件を全置換","現在の本文を履歴に保護してから置換します",
        {vm.replaceAll(s.document.id,query,replacement,case,regex);confirm=false},{confirm=false})
}
@Composable
fun CrossSearchDialog(vm:EditorViewModel,select:(Document,IntRange)->Unit,dismiss:()->Unit){var query by remember{mutableStateOf("")};var case by remember{mutableStateOf(false)};var regex by remember{mutableStateOf(false)};var result by remember{mutableStateOf<List<Pair<Document,IntRange>>>(emptyList())};var error by remember{mutableStateOf<String?>(null)};LaunchedEffect(query,case,regex){delay(300);try{result=vm.crossSearch(query,case,regex);error=null}catch(e:Exception){error=e.message}};AlertDialog(onDismissRequest=dismiss,title={Text("全文・ファイル横断検索")},text={Column{OutlinedTextField(query,{query=it},label={Text("検索")});Toggle("大文字小文字区別",case,{case=it});Toggle("正規表現",regex,{regex=it});Text(error ?: "${result.size}件（最大1000件）");LazyColumn(Modifier.heightIn(max=320.dp)){items(result){(doc,range)->TextButton(onClick={select(doc,range)}){Text("${doc.path} @ ${range.first}")}}}}},confirmButton={TextButton(onClick=dismiss){Text("閉じる")}})}
@Composable
fun HistoryDialog(s:Session,vm:EditorViewModel,dismiss:()->Unit){var list by remember{mutableStateOf<List<Revision>>(emptyList())};var selected by remember{mutableStateOf<Revision?>(null)};LaunchedEffect(s.document.id){list=vm.history(s.document.id)};AlertDialog(onDismissRequest=dismiss,title={Text("編集履歴")},text={LazyColumn(Modifier.heightIn(max=420.dp)){items(list,key={it.id}){r->TextButton(onClick={selected=r}){Text("${date(r.time)}\n${r.reason} · ${r.size}バイト")}}}},confirmButton={TextButton(onClick=dismiss){Text("閉じる")}});selected?.let{r->ConfirmDialog("履歴を復元","${date(r.time)}の本文へ戻します。現在の本文も履歴に保護します",{vm.restoreRevision(s.document.id,r);dismiss()},{selected=null})}}
@Composable
fun FormatDialog(s:Session,save:(String,String)->Unit,reload:(String)->Unit,dismiss:()->Unit){var encoding by remember{mutableStateOf(s.document.encoding)};var newline by remember{mutableStateOf(s.document.newline)};var reloadConfirm by remember{mutableStateOf(false)};AlertDialog(onDismissRequest=dismiss,title={Text("文字コード・改行")},text={Column{Text("表現できない文字はエラーにして保存を停止します");Choice("文字コード",encoding,listOf("UTF-8","UTF-8 BOM","Shift_JIS"),{encoding=it});Choice("改行",newline,listOf("LF","CRLF","CR"),{newline=it});if(s.document.kind=="EXTERNAL")TextButton(onClick={reloadConfirm=true}){Text("この文字コードで元ファイルを再読込")}}},confirmButton={TextButton(onClick={save(encoding,newline)}){Text("適用")}},dismissButton={TextButton(onClick=dismiss){Text("キャンセル")}});if(reloadConfirm)ConfirmDialog("元ファイルを再読込","現在の本文を履歴に保護してから再読込します",{reload(encoding)},{reloadConfirm=false})}
@Composable
fun DiffDialog(s:Session,vm:EditorViewModel,other:Document?,dismiss:()->Unit){var diff by remember{mutableStateOf("比較中…")};LaunchedEffect(s.document.revision,other?.id){try{diff=withContext(Dispatchers.IO){val old=if(other!=null)vm.loadSession(other.id).text else s.document.savedBody?.let{vm.repo.read(it)} ?: "";SearchTools.diff(old,s.text)}}catch(e:Exception){diff=e.message ?: "比較失敗"}};TextDialog("${other?.path ?: "正式保存内容"}との比較",diff,dismiss)}
@Composable
fun Toggle(label:String,value:Boolean,onChange:(Boolean)->Unit){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(label,modifier=Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium);Switch(value,onChange)}}
@Composable
fun Choice(label:String,value:String,choices:List<String>,change:(String)->Unit){Column{Text(label,style=MaterialTheme.typography.labelMedium);Row(Modifier.horizontalScroll(rememberScrollState())){choices.forEach{c->FilterChip(selected=value==c,onClick={change(c)},label={Text(c)},modifier=Modifier.padding(end=4.dp))}}}}
@Composable
fun Stepper(label:String,value:Int,min:Int,max:Int,step:Int=1,change:(Int)->Unit){Row(verticalAlignment=Alignment.CenterVertically){Text("$label：$value",modifier=Modifier.weight(1f));TextButton(onClick={change((value-step).coerceAtLeast(min))}){Text("−")};TextButton(onClick={change((value+step).coerceAtMost(max))}){Text("＋")}}}
@Composable
fun SettingsDialog(p:Preferences,set:(String,Any)->Unit,dismiss:()->Unit){
    var symbols by remember(p.symbols){mutableStateOf(p.symbols)}
    var shortcuts by remember(p.shortcuts){mutableStateOf(p.shortcuts)}
    AlertDialog(onDismissRequest=dismiss,title={Text("設定")},text={Column(Modifier.heightIn(max=540.dp).verticalScroll(rememberScrollState())){
        Choice("テーマ",p.theme,listOf("システム","ライト","ダーク")){set("theme",it)}
        Stepper("文字サイズ",p.fontSize,10,36){set("fontSize",it)};Stepper("行間",p.lineSpace,0,20){set("lineSpace",it)}
        Toggle("等幅フォント",p.mono){set("mono",it)};Toggle("折り返し",p.wrap){set("wrap",it)};Toggle("行番号",p.numbers){set("numbers",it)};Toggle("現在行の強調",p.currentLine){set("currentLine",it)};Toggle("構文ハイライト",p.highlight){set("highlight",it)};Toggle("不可視文字",p.invisible){set("invisible",it)}
        Choice("記号バー",p.symbolMode,listOf("常時","編集中のみ","非表示")){set("symbolMode",it)}
        OutlinedTextField(symbols,{symbols=it},label={Text("記号ボタン（空白区切り・順序変更可）")});TextButton(onClick={set("symbols",symbols)}){Text("記号配置を保存")}
        OutlinedTextField(shortcuts,{shortcuts=it},label={Text("Ctrlショートカット（例 S=保存）")});TextButton(onClick={set("shortcuts",shortcuts)}){Text("キー割当を保存")}
        Stepper("自動退避 ms",p.autosave,100,5000,100){set("autosave",it)};Stepper("履歴数",p.histories,1,100,5){set("histories",it)};Stepper("履歴容量 MB",p.historyMb,8,256,8){set("historyMb",it)};Stepper("履歴保持日数",p.historyDays,1,365,7){set("historyDays",it)};Stepper("ごみ箱保持日数",p.trashDays,1,365,7){set("trashDays",it)};Toggle("前回タブを復元",p.restore){set("restore",it)}
        Choice("既定文字コード",p.defaultEncoding,listOf("UTF-8","UTF-8 BOM","Shift_JIS")){set("encoding",it)};Choice("既定改行",p.defaultNewline,listOf("LF","CRLF","CR")){set("newline",it)}
        Stepper("Tab幅",p.indent,1,8){set("indent",it)};Toggle("Tab文字を使用",p.tabs){set("tabs",it)};Toggle("自動インデント",p.autoIndent){set("autoIndent",it)};Toggle("括弧自動補完",p.brackets){set("brackets",it)}
        Toggle("CSVの先頭行をヘッダー",p.csvHeader){set("csvHeader",it)};Toggle("プレビュー自動更新",p.previewAuto){set("previewAuto",it)};Toggle("PWA互換・全タブCSS/JS合成",p.compatibility){set("compatibility",it)}
        Stepper("大容量判定 MB",p.largeMb,1,20){set("largeMb",it)}
        Text("アプリ削除で内部メモは失われます。ファイルメニューからバックアップを保存してください。共有は利用者操作時のみ行います。",style=MaterialTheme.typography.bodySmall)
        Text("Ctrl+S 保存 / Z Undo / Y Redo / F 検索 / H 置換 / N 新規 / O 開く / Tab 次タブ",style=MaterialTheme.typography.bodySmall)
    }},confirmButton={TextButton(onClick=dismiss){Text("閉じる")}})
}

@Composable
fun EncodingReadDialog(uri:android.net.Uri,vm:EditorViewModel,confirm:(String)->Unit,dismiss:()->Unit) {
    var encoding by remember{mutableStateOf("Shift_JIS")}
    var preview by remember{mutableStateOf("読込中…")};var valid by remember{mutableStateOf(false)}
    LaunchedEffect(encoding,uri) {valid=false;try{preview=withContext(Dispatchers.IO){TextCodec.decode(vm.repo.bytes(uri),encoding).text.take(16_384)};valid=true}catch(e:Exception){preview="読込できません：${e.message}"}}
    AlertDialog(onDismissRequest=dismiss,title={Text("文字コードを再指定")},text={Column{Choice("読み込み",encoding,listOf("UTF-8","Shift_JIS")){encoding=it};Text("プレビュー（先頭16KB）",style=MaterialTheme.typography.labelSmall);Text(preview,modifier=Modifier.heightIn(max=280.dp).verticalScroll(rememberScrollState()),style=MaterialTheme.typography.bodySmall)}},confirmButton={TextButton(onClick={confirm(encoding)},enabled=valid){Text("この文字コードで開く")}},dismissButton={TextButton(onClick=dismiss){Text("キャンセル")}})
}
