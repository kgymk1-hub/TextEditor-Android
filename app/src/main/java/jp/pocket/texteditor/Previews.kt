package jp.pocket.texteditor

import android.webkit.*
import android.net.Uri
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
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
fun DocumentPreview(session:Session,tabs:List<Session>,prefs:Preferences,javascript:Boolean,version:Int,modifier:Modifier,extra:Map<String,ByteArray> = emptyMap()) {
    val mode=session.document.mode
    if(mode=="CSV") {CsvPreview(session.text,prefs.csvHeader,modifier);return}
    if(session.text.length>prefs.largeMb*1024*1024) { Box(modifier.padding(16.dp)){Text("大容量モードではプレビューを停止します。テキスト編集をご利用ください")};return }
    var console by remember(session.document.id,version){mutableStateOf(listOf<String>())}
    val html by produceState("",session.document.id,version,if(prefs.previewAuto)session.text else null,mode) {
        value=withContext(Dispatchers.Default){if(mode=="Markdown") {val extensions=listOf(TablesExtension.create());HtmlRenderer.builder().escapeHtml(true).sanitizeUrls(true).extensions(extensions).build().render(Parser.builder().extensions(extensions).build().parse(session.text))}else session.text}
    }
    val resourceMap=remember(tabs,extra,version){ extra.toMutableMap().also { map -> tabs.filter{!it.unloaded && (if(session.document.zipId!=null)it.document.zipId==session.document.zipId else if(session.document.projectUri!=null)it.document.projectUri==session.document.projectUri else it.document.zipId==null && it.document.projectUri==null)}.groupBy{it.document.path}.forEach{(path,related)->if(related.size==1)map[path]=related.single().text.toByteArray()else map.remove(path)} } }
    val currentResources=rememberUpdatedState(resourceMap)
    val compat=if(prefs.compatibility)tabs.filter{it.document.id!=session.document.id}.joinToString("\n"){when(it.document.name.substringAfterLast('.')){"css"->"<style>${it.text}</style>";"js"->if(javascript)"<script>${it.text}</script>"else "";else->""}}else ""
    Column(modifier) {
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
            }}, update={view -> val token="${session.document.id}:$version:$javascript:${html.hashCode()}:${compat.hashCode()}"; if(view.tag!=token){view.tag=token; val base="https://pocket.invalid/"+session.document.path.substringBeforeLast('/',"").let{if(it.isEmpty())""else "$it/"};view.loadDataWithBaseURL(base,"<meta name='viewport' content='width=device-width,initial-scale=1'><style>body{font-family:sans-serif;padding:12px;overflow-wrap:anywhere}table{border-collapse:collapse}td,th{border:1px solid #aaa;padding:6px}pre{overflow:auto}</style>$html$compat","text/html","UTF-8",null)}},onRelease={it.stopLoading();it.destroy()})
        }
        if(console.isNotEmpty()) LazyColumn(Modifier.heightIn(max=140.dp).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)){items(console){Text(it,style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(4.dp))}}
    }
}
private fun emptyResource()=WebResourceResponse("text/plain","UTF-8",403,"Blocked",emptyMap(),ByteArrayInputStream(ByteArray(0)))
@Composable
fun CsvPreview(text:String,header:Boolean,modifier:Modifier) {
    var index by remember(text){mutableStateOf<CsvIndex?>(null)};var error by remember(text){mutableStateOf<String?>(null)}
    LaunchedEffect(text){try{index=kotlinx.coroutines.runInterruptible(Dispatchers.Default){CsvIndex.build(text)}}catch(e:Exception){if(e is kotlinx.coroutines.CancellationException)throw e;error=e.message}}
    var query by remember{mutableStateOf("")};var width by remember{mutableIntStateOf(160)};val clipboard=LocalClipboardManager.current
    Column(modifier){
        Row(Modifier.fillMaxWidth()){OutlinedTextField(query,{query=it},label={Text("表内検索")},modifier=Modifier.weight(1f),singleLine=true);TextButton(onClick={width=(width-40).coerceAtLeast(80)}){Text("列−")};TextButton(onClick={width=(width+40).coerceAtMost(480)}){Text("列＋")}}
        Text(error ?: index?.let{"${it.size}行・セルをタップしてコピー"} ?: "CSVの行索引を作成中…",style=MaterialTheme.typography.labelSmall)
        val visible by produceState<List<Int>>(emptyList(),index,query){value=kotlinx.coroutines.runInterruptible(Dispatchers.Default){index?.matching(query) ?: emptyList()}}
        LazyColumn(Modifier.weight(1f)){items(visible,key={it}){rowNumber ->
            val cells=remember(index,rowNumber){index?.row(rowNumber) ?: emptyList()}
            LazyRow {itemsIndexed(cells){column,cell -> Surface(color=if(header && rowNumber==0)MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,modifier=Modifier.width(width.dp).heightIn(min=48.dp).border(0.5.dp,MaterialTheme.colorScheme.outline).clickable{clipboard.setText(AnnotatedString(cell))}){Text(cell,modifier=Modifier.padding(8.dp),style=MaterialTheme.typography.bodySmall)}}}
        }}
    }
}
