package jp.pocket.texteditor

import org.junit.Test
import org.junit.Assert.*
import java.io.*
import java.nio.charset.CharacterCodingException
import java.util.zip.*

class TextToolsTest {
    @Test fun utf8BomAndLineEndingsRoundTrip() {
        listOf("UTF-8","UTF-8 BOM","Shift_JIS").forEach {encoding -> listOf("日本語\n次の行","日本語\r\n次の行\r\n","日本語\r次の行", "混在\r\n改行\n末尾なし").forEach{text -> val bytes=TextCodec.encode(text,encoding);val decoded=TextCodec.decode(bytes,encoding);assertEquals(text,decoded.text);assertArrayEquals(bytes,TextCodec.encode(decoded.text,decoded.encoding))} }
    }
    @Test fun bomIsDetectedWithoutRequestedEncoding(){val d=TextCodec.decode(TextCodec.encode("本文","UTF-8 BOM"));assertEquals("UTF-8 BOM",d.encoding);assertEquals("本文",d.text)}
    @Test fun encodingLossIsRejected(){assertThrows(CharacterCodingException::class.java){TextCodec.encode("絵文字😀","Shift_JIS")};assertThrows(CharacterCodingException::class.java){TextCodec.decode(byteArrayOf(0xc3.toByte(),0x28))}}
    @Test fun binaryIsRejected(){assertThrows(IllegalArgumentException::class.java){TextCodec.decode(byteArrayOf(65,0,66))}}
    @Test fun explicitNewlineConversionDoesNotAddTrailingNewline(){assertEquals("a\r\nb\r\nc",TextCodec.convertNewlines("a\nb\rc","CRLF"));assertEquals("a\n",TextCodec.convertNewlines("a\r\n","LF"))}
    @Test fun multilineCsvAndQuotes(){assertEquals(listOf(listOf("name","notes","empty"),listOf("東京","first\n\"second\"","")),CsvParser.parse("name,notes,empty\r\n東京,\"first\n\"\"second\"\"\",\r\n"));assertEquals(listOf(listOf("a","b","")),CsvParser.parse("a,b,"))}
    @Test fun malformedCsvIsRejected(){assertThrows(IllegalArgumentException::class.java){CsvParser.parse("a,\"unclosed")}}
    @Test fun csvHasNo500RowCap(){assertEquals(1500,CsvParser.parse((1..1500).joinToString("\n"){"$it,value"}).size)}
    @Test fun maliciousZipPathsRejected(){listOf("../a","a/../../b","/etc/file","C:/file","a\\b","a/./b").forEach{p->assertThrows(IllegalArgumentException::class.java){SafeZip.safePath(p)}}}
    @Test fun zipKeepsPathsAndBinaryEntries(){val files=listOf("css/style.css" to "a{}".toByteArray(),"img/a.png" to byteArrayOf(0,1,2));val out=ByteArrayOutputStream();SafeZip.write(files,out);val read=SafeZip.read(ByteArrayInputStream(out.toByteArray()));assertEquals(files.map{it.first},read.keys.toList());files.forEach{assertArrayEquals(it.second,read[it.first])}}
    @Test fun zipDuplicatePathsRejected(){assertThrows(IllegalArgumentException::class.java){SafeZip.write(listOf("a.txt" to byteArrayOf(1),"a.txt" to byteArrayOf(2)),ByteArrayOutputStream())}}
    @Test fun zipBombRejected(){val output=ByteArrayOutputStream();ZipOutputStream(output).use{it.putNextEntry(ZipEntry("bomb.txt"));it.write(ByteArray(2*1024*1024));it.closeEntry()};assertThrows(IllegalArgumentException::class.java){SafeZip.read(ByteArrayInputStream(output.toByteArray()))}}
    @Test fun zipEntryLimitEnforced(){val out=ByteArrayOutputStream();ZipOutputStream(out).use{z->repeat(SafeZip.MAX_ENTRIES+1){z.putNextEntry(ZipEntry("$it.txt"));z.closeEntry()}};assertThrows(IllegalArgumentException::class.java){SafeZip.read(ByteArrayInputStream(out.toByteArray()))}}
    @Test fun combinedCopyPreservesOrderAndPaths(){assertEquals("===== css/style.css =====\na\n\n===== index.html =====\nb",SearchTools.combined(listOf("css/style.css" to "a","index.html" to "b")))}
    @Test fun searchAndZeroLengthRegex(){assertEquals(listOf(0..2,4..6),SearchTools.matches("Abc abc","abc",false,false));assertEquals(listOf(4..6),SearchTools.matches("Abc abc","abc",true,false));assertEquals(3,SearchTools.matches("aaa","(?=a)",false,true).size)}
    @Test fun literalSearchDoesNotInterpretRegex(){assertEquals(listOf(0..2),SearchTools.matches("a.b axb","a.b",true,false))}
}
