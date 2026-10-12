package jp.pocket.texteditor

import android.webkit.*
import android.net.Uri
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import kotlin.math.ceil
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.commonmark.ext.gfm.tables.TablesExtension
import java.io.ByteArrayInputStream

@Suppress("DEPRECATION")
@Composable
fun DocumentPreview(session:Session,tabs:List<Session>,prefs:Preferences,javascript:Boolean,version:Int,modifier:Modifier,extra:Map<String,ByteArray> = emptyMap(),linkedSources:suspend ()->List<Pair<String,String>> = {emptyList()}) {
    val mode=session.document.mode
    if(mode=="CSV") {CsvPreview(session.text,prefs.csvHeader,modifier);return}
    if(session.text.length>prefs.largeMb*1024*1024) { Box(modifier.padding(16.dp)){Text("大容量モードではプレビューを停止します。テキスト編集をご利用ください")};return }
    var previewError by remember(session.document.id,version){mutableStateOf<String?>(null)}
    var console by remember(session.document.id,version){mutableStateOf(listOf<String>())}
    val html by produceState("",session.document.id,version,if(prefs.previewAuto)session.text else null,mode,prefs.compatibility,javascript,if(prefs.previewAuto)tabs.map{it.document.id to it.document.revision}else null) {
        try {
        previewError=null
        val sources=if(prefs.compatibility && mode=="HTML")linkedSources()else emptyList()
        value=withContext(Dispatchers.Default){if(mode=="Markdown") {val extensions=listOf(TablesExtension.create());HtmlRenderer.builder().escapeHtml(true).sanitizeUrls(true).extensions(extensions).build().render(Parser.builder().extensions(extensions).build().parse(session.text))}else if(prefs.compatibility)LinkedHtml.build(session.text,sources,javascript)else session.text}
        } catch(e:Exception){if(e is kotlinx.coroutines.CancellationException)throw e;previewError="プレビュー読込失敗：${e.message}";value=""}
    }
    val resourceMap=remember(tabs,extra,version){ extra.toMutableMap().also { map -> tabs.filter{!it.unloaded && (if(session.document.zipId!=null)it.document.zipId==session.document.zipId else if(session.document.projectUri!=null)it.document.projectUri==session.document.projectUri else it.document.zipId==null && it.document.projectUri==null)}.groupBy{it.document.path}.forEach{(path,related)->if(related.size==1)map[path]=related.single().text.toByteArray()else map.remove(path)} } }
    val currentResources=rememberUpdatedState(resourceMap)
    Column(modifier) {
        previewError?.let{Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(8.dp))}
        Text(if(javascript)"JavaScript実行中・外部通信遮断" else "JavaScript OFF・外部通信遮断",style=MaterialTheme.typography.labelSmall,modifier=Modifier.padding(8.dp))
        key(session.document.id,version,javascript,mode) {
            AndroidView(modifier=Modifier.weight(1f).fillMaxWidth(),factory={context -> WebView(context).apply {
                settings.javaScriptEnabled=javascript && mode=="HTML";settings.allowFileAccess=false;settings.allowContentAccess=false
                settings.domStorageEnabled=false;settings.databaseEnabled=false;settings.blockNetworkLoads=true
                settings.mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.allowFileAccessFromFileURLs=false
                settings.allowUniversalAccessFromFileURLs=false
                CookieManager.getInstance().setAcceptThirdPartyCookies(this,false)
                webViewClient=object:WebViewClient(){
                    override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean=true
                    override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse {
                        val uri=request.url;val path=uri.path?.removePrefix("/") ?: ""
                        if(uri.host!="pocket.invalid" || uri.scheme!="https" || path.split('/').any{it==".."})return emptyResource()
                        val bytes=currentResources.value[path] ?: return emptyResource()
                        val mime=when(path.substringAfterLast('.').lowercase()){ "css"->"text/css";"js"->"application/javascript";"png"->"image/png";"jpg","jpeg"->"image/jpeg";"gif"->"image/gif";"svg"->"image/svg+xml";else->"text/plain" }
                        return WebResourceResponse(mime,"UTF-8",ByteArrayInputStream(bytes))
                    }
                    override fun onReceivedSslError(view:WebView,handler:SslErrorHandler,error:android.net.http.SslError){handler.cancel()}
                }
                webChromeClient=object:WebChromeClient(){
                    override fun onConsoleMessage(message:ConsoleMessage):Boolean {post {console=(console+"${message.messageLevel()} L${message.lineNumber()}: ${message.message()}").takeLast(100)};return true}
                    override fun onPermissionRequest(request:PermissionRequest){request.deny()}
                    override fun onGeolocationPermissionsShowPrompt(origin:String,callback:GeolocationPermissions.Callback){callback.invoke(origin,false,false)}
                }
            }}, update={view -> val token="${session.document.id}:$version:$javascript:${html.hashCode()}"; if(view.tag!=token){view.tag=token; val base="https://pocket.invalid/"+session.document.path.substringBeforeLast('/',"").let{if(it.isEmpty())""else "$it/"};view.loadDataWithBaseURL(base,"<meta name='viewport' content='width=device-width,initial-scale=1'><style>body{font-family:sans-serif;padding:12px;overflow-wrap:anywhere}table{border-collapse:collapse}td,th{border:1px solid #aaa;padding:6px}pre{overflow:auto}</style>$html","text/html","UTF-8",null)}},onRelease={it.stopLoading();it.destroy()})
        }
        if(console.isNotEmpty()) LazyColumn(Modifier.heightIn(max=140.dp).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)){items(console){Text(it,style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(4.dp))}}
    }
}
private fun emptyResource()=WebResourceResponse("text/plain","UTF-8",403,"Blocked",emptyMap(),ByteArrayInputStream(ByteArray(0)))
@Composable
fun CsvPreview(text:String,header:Boolean,modifier:Modifier) {
    var index by remember(text){mutableStateOf<CsvIndex?>(null)};var error by remember(text){mutableStateOf<String?>(null)}
    LaunchedEffect(text){try{index=kotlinx.coroutines.runInterruptible(Dispatchers.Default){CsvIndex.build(text)}}catch(e:Exception){if(e is kotlinx.coroutines.CancellationException)throw e;error=e.message}}
    var query by remember{mutableStateOf("")};var width by remember{mutableIntStateOf(160)}
    var copyMessage by remember{mutableStateOf<String?>(null)};val clipboard=LocalClipboardManager.current
    fun copyCell(cell:String){if(cell.toByteArray().size>256*1024){copyMessage="大きなセルです。テキスト側からファイル共有をご利用ください";return};try{clipboard.setText(AnnotatedString(cell));copyMessage="セルをコピーしました"}catch(e:Exception){copyMessage="コピーできませんでした：${e.message}"}}
    val visible by produceState<List<Int>>(emptyList(),index,query,header) {
        value=kotlinx.coroutines.runInterruptible(Dispatchers.Default){index?.matching(query)?.filter{!header || it!=0} ?: emptyList()}
    }
    Column(modifier){
        Row(Modifier.fillMaxWidth()){OutlinedTextField(query,{query=it},label={Text("表内検索")},modifier=Modifier.weight(1f),singleLine=true);TextButton(onClick={width=(width-40).coerceAtLeast(80)}){Text("列−")};TextButton(onClick={width=(width+40).coerceAtMost(480)}){Text("列＋")}}
        Text(copyMessage ?: error ?: index?.let{"${it.size}行・${it.columns}列 · セルをタップしてコピー"} ?: "CSVの行索引を作成中…",style=MaterialTheme.typography.labelSmall)
        if(error==null)BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val viewport=(maxWidth.value-56).coerceAtLeast(1f)
            val count=index?.columns ?: 1
            val maxOffset=(count.toFloat()*width-viewport).coerceAtLeast(0f)
            var offset by remember(text){mutableFloatStateOf(0f)}
            LaunchedEffect(maxOffset){offset=offset.coerceAtMost(maxOffset)}
            val density=LocalDensity.current.density
            val scroll=rememberScrollableState { delta ->
                val previous=offset;offset=(offset-delta/density).coerceIn(0f,maxOffset);(previous-offset)*density
            }
            val first=(offset/width).toInt().coerceAtMost(count-1)
            val last=(first+ceil(viewport/width).toInt()+1).coerceAtMost(count)
            val shift=offset-first*width
            val titles=remember(index,header){if(header && (index?.size ?: 0)>0)index!!.row(0)else emptyList()}
            Column(Modifier.fillMaxSize().testTag("csv-table").semantics {
                horizontalScrollAxisRange=ScrollAxisRange({offset*density},{maxOffset*density})
                scrollBy { x,_ -> val previous=offset;offset=(offset+x/density).coerceIn(0f,maxOffset);offset!=previous }
            }.scrollable(scroll,Orientation.Horizontal)) {
                CsvTableRow(-1,first,last,width,shift,if(header)titles else null,::copyCell)
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("csv-body")) {items(visible,key={it}){row ->
                    val cells=remember(index,row){index?.row(row) ?: emptyList()}
                    CsvTableRow(row,first,last,width,shift,cells,::copyCell)
                }}
            }
        }
    }
}
@Composable
private fun CsvTableRow(row:Int,first:Int,last:Int,width:Int,shift:Float,cells:List<String>?,copyCell:(String)->Unit) {
    val color=if(row<0)MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).testTag("csv-row-$row")) {
        Surface(color=color,modifier=Modifier.width(56.dp).fillMaxHeight().border(0.5.dp,MaterialTheme.colorScheme.outline)) {
            Text(if(row<0)"#"else (row+1).toString(),Modifier.padding(8.dp).testTag("csv-number-$row"),style=MaterialTheme.typography.bodySmall)
        }
        Box(Modifier.weight(1f).clipToBounds()) {
            Row(Modifier.offset(x=(-shift).dp).height(IntrinsicSize.Min)) {
                for(column in first until last) {
                    val cell=if(row<0 && cells==null)PwaCompatibility.columnName(column)else cells?.getOrNull(column).orEmpty()
                    Surface(color=color,modifier=Modifier.width(width.dp).fillMaxHeight().heightIn(min=48.dp).border(0.5.dp,MaterialTheme.colorScheme.outline).testTag("csv-cell-$row-$column").clickable{copyCell(cell)}) {
                        Text(cell,Modifier.padding(8.dp),style=MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
