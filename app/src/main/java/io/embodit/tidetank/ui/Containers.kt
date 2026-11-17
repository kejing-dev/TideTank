package io.embodit.tidetank.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.embodit.tidetank.domain.Kind

/** v0.1.3 Full Space: the Stage (manifest TideTankStage) holds the tank; this is the one control window. */
object Containers {
    const val CONTROL = "TideTankControl"
    const val CONTROL_TAG = "control"
}

/** Control window pages, switched only by the native TabBar (D8): 水缸 / 图鉴 / 设置. */
enum class Page(val stateId: String) { TANK("S02"), GUIDE("S08"), SETTINGS("S10") }

enum class Confirm { QUIT, RESET }

/** Window-page state shared process-wide so debug commands can switch pages (design stateModel panelPage axis). */
object Ui {
    var page by mutableStateOf(Page.TANK)
    /** S01 / [怎么玩]: the how-to card over the tank page. */
    var howTo by mutableStateOf(false)
    /** S08b: the guide detail of this creature (in place of the grid). */
    var detail by mutableStateOf<Kind?>(null)
    /** S09: the all-found card in place of the tank page. */
    var result by mutableStateOf(false)
    /** S11: the in-place confirm row. */
    var confirm by mutableStateOf<Confirm?>(null)
}
