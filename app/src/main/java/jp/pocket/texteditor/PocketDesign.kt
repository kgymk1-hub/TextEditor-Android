package jp.pocket.texteditor

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

fun pocketColors(dark:Boolean) = if(dark)darkColorScheme(
    primary=Color(0xff60a5fa),onPrimary=Color(0xff111827),primaryContainer=Color(0xff1e3a8a),onPrimaryContainer=Color(0xffdbeafe),
    secondary=Color(0xffd1d5db),onSecondary=Color(0xff111827),secondaryContainer=Color(0xff374151),onSecondaryContainer=Color(0xffe5e7eb),
    background=Color(0xff111827),onBackground=Color(0xffe5e7eb),surface=Color(0xff1f2937),onSurface=Color(0xffe5e7eb),
    surfaceVariant=Color(0xff374151),onSurfaceVariant=Color(0xffd1d5db),outline=Color(0xff9ca3af),error=Color(0xfffca5a5)
)else lightColorScheme(
    primary=Color(0xff2563eb),onPrimary=Color.White,primaryContainer=Color(0xffdbeafe),onPrimaryContainer=Color(0xff1e3a8a),
    secondary=Color(0xff4b5563),onSecondary=Color.White,secondaryContainer=Color(0xfff3f4f6),onSecondaryContainer=Color(0xff1f2937),
    background=Color(0xfff9fafb),onBackground=Color(0xff1f2937),surface=Color.White,onSurface=Color(0xff1f2937),
    surfaceVariant=Color(0xfff3f4f6),onSurfaceVariant=Color(0xff4b5563),outline=Color(0xff6b7280),error=Color(0xffdc2626)
)
@Composable
fun ThemeButton(dark:Boolean,toggle:()->Unit) {
    TextButton(onClick=toggle,modifier=Modifier.semantics{contentDescription=if(dark)"ライトモードに切り替え"else "ダークモードに切り替え"}){Text(if(dark)"明"else "暗")}
}
@Composable
fun EditorHeader(name:String,status:String,unsaved:Int,error:Boolean,dark:Boolean,info:()->Unit,home:()->Unit,undo:()->Unit,redo:()->Unit,save:()->Unit,open:()->Unit,tools:()->Unit,theme:()->Unit,menu:@Composable ()->Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide=maxWidth>=600.dp && LocalDensity.current.fontScale<1.3f
        @Composable fun Title(modifier:Modifier) {
            Column(modifier.clickable(onClick=info).padding(horizontal=8.dp,vertical=6.dp)) {
                Text(name,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(status,Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.labelSmall,color=if(error)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary);Text("未保存 ${unsaved}件",Modifier.padding(start=4.dp),maxLines=1,style=MaterialTheme.typography.labelSmall)}
            }
        }
        @Composable fun Actions() {
            TextButton(onClick=home,modifier=Modifier.width(48.dp),contentPadding=PaddingValues(horizontal=6.dp)){Text("一覧")}
            IconButton(onClick=undo,modifier=Modifier.semantics{contentDescription="元に戻す"}){Text("↶")}
            IconButton(onClick=redo,modifier=Modifier.semantics{contentDescription="やり直す"}){Text("↷")}
            FilledTonalButton(onClick=save){Text("保存")}
            TextButton(onClick=open){Text("開く")}
            TextButton(onClick=tools){Text("ツール")}
        }
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically) {
                Title(Modifier.weight(1f))
                if(wide)Actions()
                ThemeButton(dark,theme)
                menu()
            }
            if(!wide)Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically){Actions()}
        }
    }
}
