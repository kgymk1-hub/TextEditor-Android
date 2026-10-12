package jp.pocket.texteditor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.*
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.inputmethod.BaseInputConnection
import android.widget.EditText
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.*
import java.util.ArrayDeque

/** Native editable surface; replaceable independently of storage/Compose UI. */
class NativeEditor(context: Context, attrs: AttributeSet?=null): EditText(context,attrs) {
    var onEdit: ((String,Int,Int,Int)->Unit)?=null
    var onShortcut: ((String)->Unit)?=null
    var prefs=Preferences(); var extension="txt"; var newlineMode="LF"; var adjusting=false
    var undoStack=ArrayDeque<String>(); var redoStack=ArrayDeque<String>()
    private var precedingLines=0
    private var source=""; private var windowStart=0; private var windowEnd=0; private var windowText=""
    private var currentSession:Session?=null; private var currentDark=false
    private val paged get() = source.length>1024*1024
    private fun rememberUndo(value:String, replacedLength:Int):String = "\u0000PAGE:"+org.json.JSONArray().put(windowStart).put(value).put(replacedLength).put(windowStart+lastStart+lastRemoved).toString()
    fun page(direction:Int) {
        lastUndo=0L
        val target=if(direction>0)maxOf(windowEnd,windowStart+EditorWindow.SIZE).coerceAtMost(source.length)else (windowStart-128*1024).coerceAtLeast(0)
        val session=currentSession ?: return
        apply(session.copy(text=source,document=session.document.copy(cursor=target,selection=target,scroll=0)),prefs,currentDark)
        emit()
    }
    private var before=""; private var lastUndo=0L; private var lastInput=""; private var lastStart=0; private var lastRemoved=0
    private var colorJob: Job?=null; private val syntaxScope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val linePaint=Paint().apply{color=0x153e8c70}
    private val bracketPaint=Paint().apply{style=Paint.Style.STROKE;strokeWidth=2f;color=0xff799a8b.toInt()}
    private val gutter=Paint(Paint.ANTI_ALIAS_FLAG); private var lineStarts=listOf(0)
    private val scale=ScaleGestureDetector(context,object:ScaleGestureDetector.SimpleOnScaleGestureListener(){ override fun onScale(detector: ScaleGestureDetector): Boolean { textSize=(textSize*detector.scaleFactor).coerceIn(10f,36f);return true } })
    init {
        gravity=android.view.Gravity.TOP or android.view.Gravity.START
        setBackgroundColor(android.graphics.Color.TRANSPARENT); setTextColor(0xff25352f.toInt())
        isSingleLine=false; setHorizontallyScrolling(false)
        inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        setPadding(48,12,16,24); minHeight=0; contentDescription="本文エディタ"
        addTextChangedListener(object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) { if(!adjusting) {before=s.toString();lastStart=start;lastRemoved=count} }
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) {lastInput=s?.subSequence(start,(start+count).coerceAtMost(s.length)).toString()}
            override fun afterTextChanged(s:Editable?) {
                if(adjusting || s==null)return
                val now=System.currentTimeMillis()
                if(before.length<=5*1024*1024 && (now-lastUndo>500 || undoStack.isEmpty())) {undoStack.addLast(rememberUndo(before,s.length));while(undoStack.size>50 || (undoStack.size>1 && undoStack.sumOf{it.length.toLong()}>8*1024*1024))undoStack.removeFirst();lastUndo=now} else if(undoStack.peekLast()?.startsWith("\u0000PAGE:")==true) {
                    val record=org.json.JSONArray(requireNotNull(undoStack.peekLast()).removePrefix("\u0000PAGE:"))
                    if(record.getInt(0)==windowStart){record.put(2,s.length);undoStack.removeLast();undoStack.addLast("\u0000PAGE:"+record.toString())}
                };redoStack.clear()
                if(newlineMode!="LF" && lastInput.contains('\n') && !lastInput.contains('\r')) {
                    val replacement=TextCodec.convertNewlines(lastInput,newlineMode);val start=lastStart;val end=start+lastInput.length
                    adjusting=true;s.replace(start,end,replacement);adjusting=false;updateUndoSize(s.length)
                }
                emit();scheduleHighlight();invalidate()
                val inserted=lastInput; val pos=lastStart
                if(BaseInputConnection.getComposingSpanStart(s)<0 && (inserted in listOf("\n","\r\n","\r") || inserted.length==1)) post {
                    if(adjusting || selectionStart!=pos+inserted.length)return@post
                    if(inserted in listOf("\n","\r\n","\r") && prefs.autoIndent) {
                        val prev=before.substring(0,pos.coerceAtMost(before.length)).substringAfterLast('\n');var indent=prev.takeWhile{it==' ' || it=='\t'}
                        if(prev.trimEnd().endsWith("{"))indent+=indentUnit()
                        if(indent.isNotEmpty()) insert(indent)
                    } else if(prefs.brackets) {
                        val close=mapOf("(" to ")","[" to "]","{" to "}","\"" to "\"","'" to "'")[inserted]
                        if(close!=null){ val cursor=selectionStart;adjusting=true;editableText.insert(cursor,close);setSelection(cursor);adjusting=false;updateUndoSize(text.length);emit();scheduleHighlight() }
                    }
                }
            }
        })
    }
    private fun updateUndoSize(size:Int) {
        val raw=undoStack.peekLast() ?: return
        if(!raw.startsWith("\u0000PAGE:"))return
        val record=org.json.JSONArray(raw.removePrefix("\u0000PAGE:"))
        if(record.getInt(0)==windowStart){record.put(2,size);undoStack.removeLast();undoStack.addLast("\u0000PAGE:"+record.toString())}
    }
    private fun emit() {
        val visible=text.toString()
        if(visible!=windowText) {
            source=source.replaceRange(windowStart,windowEnd,visible)
            windowEnd=windowStart+visible.length;windowText=visible
        }
        onEdit?.invoke(source,windowStart+selectionStart.coerceAtLeast(0),windowStart+selectionEnd.coerceAtLeast(0),scrollY)
    }
    override fun onSelectionChanged(start:Int,end:Int){super.onSelectionChanged(start,end);if(!adjusting && onEdit!=null){emit();invalidate()} }
    override fun onScrollChanged(l:Int,t:Int,oldl:Int,oldt:Int){super.onScrollChanged(l,t,oldl,oldt);if(!adjusting)emit()}
    override fun performClick():Boolean {super.performClick();return true}
    override fun onTouchEvent(event:MotionEvent):Boolean {scale.onTouchEvent(event);return super.onTouchEvent(event)}
    override fun onKeyDown(keyCode:Int,event:KeyEvent):Boolean {
        if(event.isCtrlPressed) {
            val action=prefs.shortcuts.split(" ").mapNotNull { binding -> val pair=binding.split("=",limit=2); if(pair.size==2) KeyEvent.keyCodeFromString("KEYCODE_"+pair[0].uppercase()) to pair[1] else null }.toMap()[keyCode]
            if(action!=null){if(action=="Undo")undo() else if(action=="Redo")redo() else onShortcut?.invoke(action);return true}
        }
        if(!event.isShiftPressed && !event.isCtrlPressed && ((keyCode==KeyEvent.KEYCODE_DPAD_LEFT && selectionStart==0 && windowStart>0) || (keyCode==KeyEvent.KEYCODE_DPAD_RIGHT && selectionEnd==text.length && windowEnd<source.length))) {
            move(if(keyCode==KeyEvent.KEYCODE_DPAD_LEFT)-1 else 1);return true
        }
        if(keyCode==KeyEvent.KEYCODE_TAB){insert(indentUnit());return true}
        return super.onKeyDown(keyCode,event)
    }
    fun apply(s:Session,p:Preferences,dark:Boolean) {
        currentSession=s;currentDark=dark
        prefs=p;newlineMode=if(s.document.normalizedNewlines)"LF"else s.document.newline;extension=s.document.name.substringAfterLast('.').lowercase()
        setTextColor(if(dark)0xffe3e9e4.toInt() else 0xff25352f.toInt());gutter.color=if(dark)0xff869e91.toInt() else 0xff788c81.toInt()
        textSize=p.fontSize.toFloat();typeface=if(p.mono)Typeface.MONOSPACE else Typeface.DEFAULT
        setLineSpacing(p.lineSpace.toFloat(),1f);setHorizontallyScrolling(!p.wrap)
        setPadding(if(p.numbers)64 else 16,12,16,24)
        val cursor=s.document.cursor.coerceIn(0,s.text.length)
        val bounds=EditorWindow.bounds(s.text,cursor,windowStart,windowEnd)
        if(source!==s.text || windowStart!=bounds.first) precedingLines=s.text.substring(0,bounds.first).count{it=='\n'}
        if(windowStart!=bounds.first)lastUndo=0L
        source=s.text;windowStart=bounds.first;windowEnd=bounds.second
        val visible=s.text.substring(windowStart,windowEnd)
        if(text.toString()!=visible){adjusting=true;windowText=visible;setText(visible);setSelection((cursor-windowStart).coerceIn(0,text.length),(s.document.selection-windowStart).coerceIn(0,text.length));post{scrollTo(0,s.document.scroll)};adjusting=false;scheduleHighlight()}
        else { windowText=visible; if(selectionStart!=cursor-windowStart || selectionEnd!=s.document.selection-windowStart){adjusting=true;setSelection((cursor-windowStart).coerceIn(0,text.length),(s.document.selection-windowStart).coerceIn(0,text.length));adjusting=false} }
        if(!p.highlight){editableText.getSpans(0,text.length,ForegroundColorSpan::class.java).forEach{editableText.removeSpan(it)}}
        invalidate()
    }
    fun replaceForDocument(id:String,revision:Long,result:SingleReplacement.Result):Boolean {
        val current=currentSession ?: return false
        if(current.document.id!=id || current.document.revision!=revision || source!=current.text)return false
        if(result.end-result.start>EditorWindow.SIZE)return false
        apply(current.copy(document=current.document.copy(cursor=result.start,selection=result.end)),prefs,currentDark)
        if(result.start<windowStart || result.end>windowEnd)return false
        setSelection(result.start-windowStart,result.end-windowStart)
        insert(result.value)
        return true
    }
    fun insert(value:String) { val inserted=if(newlineMode=="LF")value else TextCodec.convertNewlines(value,newlineMode); val start=minOf(selectionStart,selectionEnd).coerceAtLeast(0);val end=maxOf(selectionStart,selectionEnd).coerceAtLeast(0);editableText.replace(start,end,inserted);setSelection((start+inserted.length).coerceAtMost(text.length));requestFocus() }
    fun indentUnit()=if(prefs.tabs)"\t" else " ".repeat(prefs.indent.coerceIn(1,8))
    fun move(delta:Int,select:Boolean=false) {
        val global=(windowStart+selectionEnd).coerceIn(0,source.length)
        val next=if(delta==1 && global<source.length || delta == -1 && global>0)source.offsetByCodePoints(global,delta)else (global+delta).coerceIn(0,source.length)
        if(!select && (next<windowStart || next>=windowEnd && windowEnd<source.length)) {
            currentSession?.let{apply(it.copy(text=source,document=it.document.copy(cursor=next,selection=next,scroll=0)),prefs,currentDark)};emit()
        }else {val local=(next-windowStart).coerceIn(0,text.length);if(select)setSelection(selectionStart.coerceAtLeast(0),local)else setSelection(local)}
        requestFocus()
    }
    fun lineBoundary(end:Boolean) { val t=text.toString();val p=selectionEnd.coerceAtLeast(0);val index=if(end)t.indexOf('\n',p).let{if(it<0)t.length else it}else (if(p==0)-1 else t.lastIndexOf('\n',p-1))+1;setSelection(index);requestFocus() }
    fun undo() = replay(undoStack,redoStack)
    fun redo() = replay(redoStack,undoStack)
    private fun replay(from:ArrayDeque<String>,to:ArrayDeque<String>) {
        if(from.isEmpty())return
        val value=from.removeLast()
        if(value.startsWith("\u0000PAGE:")) {
            val data=org.json.JSONArray(value.removePrefix("\u0000PAGE:"));val start=data.getInt(0);val content=data.getString(1);val length=data.getInt(2)
            require(start+length<=source.length)
            to.addLast("\u0000PAGE:"+org.json.JSONArray().put(start).put(source.substring(start,start+length)).put(content.length).put(windowStart+selectionStart.coerceAtLeast(0)).toString())
            source=source.replaceRange(start,start+length,content)
            val session=currentSession ?: return
            windowStart=0;windowEnd=0
            val cursor=data.optInt(3,start).coerceIn(0,source.length)
            apply(session.copy(text=source,document=session.document.copy(cursor=cursor,selection=cursor,scroll=0)),prefs,currentDark)
            emit();scheduleHighlight()
        } else {
            to.addLast(text.toString());val cursor=selectionStart;adjusting=true;setText(value);setSelection(cursor.coerceIn(0,value.length));adjusting=false;emit();scheduleHighlight()
        }
    }
    fun lineAction(action:String) {
        val t=text.toString();val p=selectionStart.coerceAtLeast(0);val start=(if(p==0)-1 else t.lastIndexOf('\n',p-1))+1;val end=t.indexOf('\n',selectionEnd.coerceAtLeast(p)).let{if(it<0)t.length else it+1};val range=t.substring(start,end)
        when(action){
            "行複製"->{editableText.insert(end,if(end==t.length && !range.endsWith('\n'))"\n$range" else range)}
            "行削除"->{editableText.delete(start,end);setSelection(start.coerceAtMost(text.length))}
            "行↑"->{if(start>0){val previous=t.lastIndexOf('\n',(start-2).coerceAtLeast(0))+1;val prev=t.substring(previous,start);val joined=range.trimEnd('\n')+"\n"+prev.trimEnd('\n')+if(range.endsWith('\n'))"\n" else "";editableText.replace(previous,end,joined);setSelection(previous)}}
            "行↓"->{if(end<t.length){val next=t.indexOf('\n',end).let{if(it<0)t.length else it+1};val after=t.substring(end,next);val joined=after.trimEnd('\n')+"\n"+range.trimEnd('\n')+if(after.endsWith('\n'))"\n" else "";editableText.replace(start,next,joined);setSelection(start+after.length)}}
            "字下げ+","字下げ−"->{val unit=indentUnit();val changed=range.split('\n').mapIndexed{i,line->if(line.isEmpty() && i==range.count{it=='\n'})line else if(action=="字下げ+")unit+line else if(line.startsWith('\t'))line.drop(1)else line.drop(line.takeWhile{it==' '}.length.coerceAtMost(prefs.indent))}.joinToString("\n");editableText.replace(start,end,changed);setSelection(start,(start+changed.length).coerceAtMost(text.length))}
        }
    }
    private fun scheduleHighlight() {
        colorJob?.cancel();colorJob=syntaxScope.launch {
            delay(250);val content=text.toString()
            val starts=withContext(Dispatchers.Default){ mutableListOf(0).also{list->content.forEachIndexed{i,c->if(c=='\n')list.add(i+1)}} };lineStarts=starts
            if(paged || content.length>1024*1024 || !prefs.highlight){invalidate();return@launch}
            val spans=withContext(Dispatchers.Default) {
                val re=Regex("(?m)//[^\\n]*|/\\*[\\s\\S]*?\\*/|<!--[^>]*-->|\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'|</?[a-zA-Z][^>]*>|\\b(?:const|let|var|function|return|if|else|class|true|false|null|import|export|async|await)\\b|\\b\\d+(?:\\.\\d+)?\\b|^#{1,6} .*$")
                re.findAll(content).take(20_000).map{val color=when{it.value.startsWith("//") || it.value.startsWith("/*") || it.value.startsWith("<!--")->0xff6b9478.toInt();it.value.startsWith('"') || it.value.startsWith('\'')->0xffb48648.toInt();it.value.startsWith('<')->0xff668ccb.toInt();else->0xffb67dc5.toInt()};Triple(it.range.first,it.range.last+1,color)}.toList()
            }
            if(text.toString()!=content)return@launch
            val editable=editableText;editable.getSpans(0,editable.length,ForegroundColorSpan::class.java).forEach{editable.removeSpan(it)}
            if(extension in setOf("html","htm","css","js","json","md","kt","java","py"))spans.forEach{editable.setSpan(ForegroundColorSpan(it.third),it.first,it.second,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)}
            invalidate()
        }
    }
    override fun onDraw(canvas:Canvas) {
        val lay=layout
        if(lay!=null && prefs.currentLine && selectionStart>=0){val line=lay.getLineForOffset(selectionStart.coerceAtMost(text.length));val paint=linePaint;canvas.drawRect(scrollX.toFloat(),(lay.getLineTop(line)+totalPaddingTop).toFloat(),(scrollX+width).toFloat(),(lay.getLineBottom(line)+totalPaddingTop).toFloat(),paint)}
        super.onDraw(canvas)
        if(lay==null)return
        val first=lay.getLineForVertical((scrollY-totalPaddingTop).coerceAtLeast(0));val last=lay.getLineForVertical(scrollY+height)
        gutter.textSize=textSize*0.75f
        if(prefs.numbers) for(line in first..last){val offset=lay.getLineStart(line);val logical=lineStarts.binarySearch(offset);if(logical>=0)canvas.drawText((logical+1+precedingLines).toString(),(scrollX+6).toFloat(),(lay.getLineBaseline(line)+totalPaddingTop).toFloat(),gutter)}
        if(prefs.invisible && text.length<=1024*1024) for(line in first..last){for(i in lay.getLineStart(line) until lay.getLineEnd(line)){val c=text[i];val mark=when(c){' '->"·";'\t'->"→";'\n'->"↵";else->null};if(mark!=null)canvas.drawText(mark,lay.getPrimaryHorizontal(i)+totalPaddingLeft,(lay.getLineBaseline(line)+totalPaddingTop).toFloat(),gutter)}}
        // Bound bracket scan to avoid large-document pauses.
        val cursor=selectionStart;val idx=if(cursor>0 && text[cursor-1] in "()[]{}")cursor-1 else if(cursor<text.length && cursor>=0 && text[cursor] in "()[]{}")cursor else -1
        if(idx>=0){val c=text[idx];val open="([{";val close=")] }".replace(" ","");val forward=c in open;val target=if(forward)close[open.indexOf(c)]else open[close.indexOf(c)];var depth=0;val step=if(forward)1 else -1;var n=idx;var iterations=0
            while(n in 0 until text.length && iterations++<10_000){if(text[n]==c)depth++;if(text[n]==target)depth--;if(depth==0){val line=lay.getLineForOffset(n);val paint=bracketPaint;val x=lay.getPrimaryHorizontal(n)+totalPaddingLeft;canvas.drawRect(x,(lay.getLineTop(line)+totalPaddingTop).toFloat(),x+paint.measureText(target.toString()).coerceAtLeast(textSize*0.6f),(lay.getLineBottom(line)+totalPaddingTop).toFloat(),paint);break};n+=step}
        }
    }
    override fun onDetachedFromWindow(){super.onDetachedFromWindow();colorJob?.cancel()}
}
@Composable
fun EditorSurface(session:Session,prefs:Preferences,dark:Boolean,modifier:Modifier,onReady:(NativeEditor)->Unit,onEdit:(String,Int,Int,Int)->Unit,onShortcut:(String)->Unit,undoStorage:MutableMap<String,Pair<List<String>,List<String>>>?=null,onFocus:(Boolean)->Unit={}) {
    if(session.unloaded) { Box(modifier){Text("本文を読み込み中…")};return }
    val editHandler=onEdit;val shortcutHandler=onShortcut
    key(session.document.id) {
        var editor by remember { mutableStateOf<NativeEditor?>(null) }
        Column(modifier) {
        if(session.text.length>1024*1024) Row(Modifier.fillMaxWidth()) {
            TextButton(onClick={editor?.page(-1)}){Text("前の区間")}
            Text("大容量編集・位置 ${session.document.cursor} / ${session.text.length}",modifier=Modifier.weight(1f).padding(8.dp),style=MaterialTheme.typography.labelSmall)
            TextButton(onClick={editor?.page(1)}){Text("次の区間")}
        }
        AndroidView(modifier=Modifier.weight(1f).fillMaxWidth(),factory={context->NativeEditor(context).apply{this.onEdit=null;apply(session,prefs,dark);this.onEdit=editHandler;this.onShortcut=shortcutHandler;undoStorage?.get(session.document.id)?.let{(undo,redo)->undoStack.addAll(undo);redoStack.addAll(redo)};setOnFocusChangeListener{_,focused->onFocus(focused)};editor=this;onReady(this)}},update={view->view.apply(session,prefs,dark);view.onEdit=onEdit;view.onShortcut=onShortcut;onReady(view)},onRelease={view->onFocus(false);undoStorage?.set(session.document.id,view.undoStack.toList() to view.redoStack.toList())})
        }
    }
}
