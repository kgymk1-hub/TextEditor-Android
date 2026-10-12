package jp.pocket.texteditor

object SingleReplacement {
    data class Result(val text:String,val cursor:Int,val value:String,val start:Int,val end:Int)
    fun calculate(text:String,start:Int,query:String,replacement:String,case:Boolean,regex:Boolean):Result {
        require(query.isNotEmpty()){"検索文字を入力してください"}
        require(start in 0..text.length)
        val matcher=SearchTools.regex(query,case,regex).toPattern().matcher(text)
        check(matcher.find(start) && matcher.start()==start){"検索結果が変更されています"}
        val end=matcher.end();val prefix=StringBuffer()
        matcher.appendReplacement(prefix,if(regex)replacement else java.util.regex.Matcher.quoteReplacement(replacement))
        val value=prefix.substring(start)
        return Result(prefix.toString()+text.substring(end),prefix.length,value,start,end)
    }
}
