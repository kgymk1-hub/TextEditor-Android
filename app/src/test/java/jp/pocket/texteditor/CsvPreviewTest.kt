package jp.pocket.texteditor

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class CsvPreviewTest {
    @get:Rule val compose=createComposeRule()
    private fun show(text:String,header:Boolean) {
        compose.setContent{MaterialTheme{CsvPreview(text,header,Modifier.fillMaxSize())}}
        compose.waitUntil(10_000){compose.onAllNodesWithTag("csv-cell-1-0").fetchSemanticsNodes().isNotEmpty()}
    }
    @Test fun headerAndRowNumbersStayVisibleWithRaggedRows() {
        show("name,value,extra\nfirst,second\nlast",true)
        compose.onNodeWithTag("csv-cell--1-0").assertTextEquals("name")
        compose.onNodeWithTag("csv-number-1").assertTextEquals("2")
        compose.onNodeWithTag("csv-cell-1-2").assertTextEquals("")
        compose.onNodeWithTag("csv-row-0").assertDoesNotExist()
    }
    @Test fun horizontalScrollMovesHeaderAndDataTogetherWhileNumbersStayFixed() {
        val row=(0..30).joinToString(","){"value$it"}
        show("$row\n$row",false)
        val shift=with(compose.density){320.dp.toPx()}
        compose.onNodeWithTag("csv-table").performSemanticsAction(SemanticsActions.ScrollBy){it(shift,0f)}
        compose.waitForIdle()
        compose.waitUntil(5_000){compose.onAllNodesWithTag("csv-cell--1-0").fetchSemanticsNodes().isEmpty()}
        compose.onNodeWithTag("csv-cell--1-2").assertTextEquals("C")
        compose.onNodeWithTag("csv-cell-1-2").assertTextEquals("value2")
        compose.onNodeWithTag("csv-cell--1-0").assertDoesNotExist()
        compose.onNodeWithTag("csv-cell-1-0").assertDoesNotExist()
        compose.onNodeWithTag("csv-number-1").assertTextEquals("2")
    }
    @Test fun verticalScrollKeepsColumnHeadersPinned() {
        show("name,value\n"+(1..200).joinToString("\n"){"row$it,value$it"},true)
        compose.onNodeWithTag("csv-body").performScrollToIndex(199)
        compose.onNodeWithTag("csv-cell--1-0").assertIsDisplayed().assertTextEquals("name")
        compose.onNodeWithTag("csv-number-200").assertIsDisplayed().assertTextEquals("201")
    }
    @Test fun headerOffGeneratesLabelsBeyondZ() {
        val row=(0..30).joinToString(","){"value$it"};show("$row\n$row",false)
        val shift=with(compose.density){(26*160).dp.toPx()}
        compose.onNodeWithTag("csv-table").performSemanticsAction(SemanticsActions.ScrollBy){it(shift,0f)}
        compose.waitForIdle()
        compose.waitUntil(5_000){compose.onAllNodesWithTag("csv-cell--1-26").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("csv-cell--1-26").assertTextEquals("AA")
        compose.onNodeWithTag("csv-cell-0-26").assertTextEquals("value26")
    }
}
