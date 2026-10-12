package jp.pocket.texteditor

/** Shared rules for UI and file output; original documents are never renamed here. */
object PwaCompatibility {
    val modes = listOf("編集", "HTML", "CSV", "Markdown")
    fun symbolBarVisible(mode:String, editing:Boolean, focused:Boolean):Boolean = editing && when(mode) {
        "常時" -> true
        "編集中のみ" -> focused
        else -> false
    }
    fun unsavedCount(tabs:List<Session>):Int = tabs.count {
        it.document.revision != it.document.savedRevision
    }
    fun outputEncoding(document:Document, setting:String):String =
        if(setting=="元の文字コード") document.encoding else setting
    fun uniqueZipPaths(paths:List<String>):List<String> {
        val reserved=paths.toSet();val used=mutableSetOf<String>()
        return paths.map { path ->
            SafeZip.safePath(path)
            if(used.add(path))path else {
                val directory=path.substringBeforeLast('/',"").let{if(it.isEmpty())""else "$it/"}
                val file=path.substringAfterLast('/');val dot=file.lastIndexOf('.')
                val stem=if(dot>0)file.take(dot)else file;val extension=if(dot>0)file.substring(dot)else ""
                var number=2;var candidate:String
                do{candidate="$directory$stem-${number++}$extension"}while(candidate in used || candidate in reserved)
                used.add(candidate);candidate
            }
        }
    }
    fun columnName(index:Int):String {
        require(index>=0);var number=index+1;var name=""
        while(number>0){name=('A'+(number-1)%26)+name;number=(number-1)/26}
        return name
    }
}
object LinkedHtml {
    private fun insert(html:String,tag:String,block:String,prepend:Boolean):String {
        if(block.isEmpty())return html
        val closing=Regex("</$tag\\s*>",RegexOption.IGNORE_CASE).find(html)
        return if(closing!=null)html.replaceRange(closing.range,block+"\n"+closing.value)
        else if(prepend)block+"\n"+html else html+"\n"+block
    }
    fun build(html:String,sources:List<Pair<String,String>>,javascript:Boolean):String {
        val css=sources.filter{it.first.substringAfterLast('.').lowercase()=="css"}.joinToString("\n"){it.second}
        val js=sources.filter{it.first.substringAfterLast('.').lowercase()=="js"}.joinToString("\n"){it.second}
        val style=if(css.isEmpty())""else "<style data-pocket-linked-preview>\n"+Regex("</style",RegexOption.IGNORE_CASE).replace(css){"<\\/"+it.value.drop(2)}+"\n</style>"
        val script=if(js.isEmpty() || !javascript)""else "<script data-pocket-linked-preview>\n"+Regex("</script",RegexOption.IGNORE_CASE).replace(js){"<\\/"+it.value.drop(2)}+"\n</script>"
        return insert(insert(html,"head",style,true),"body",script,false)
    }
}
