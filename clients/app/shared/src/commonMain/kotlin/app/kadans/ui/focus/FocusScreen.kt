package app.kadans.ui.focus

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.kadans.i18n.LocalStrings
import app.kadans.ui.stats.StatsPane
import app.kadans.ui.templates.TemplatesPane

/** What the Focus destination shows: how the focus went, or the cycles it runs on. */
enum class FocusView { Stats, Cycles }

/** The Focus destination: the stats and the Pomodoro cycles, one switch apart (they are only about focus sessions). */
@Composable
fun FocusScreen(modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    var view by rememberSaveable { mutableStateOf(FocusView.Stats) }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        SingleChoiceSegmentedButtonRow(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            FocusView.entries.forEachIndexed { index, candidate ->
                SegmentedButton(
                    selected = view == candidate,
                    onClick = { view = candidate },
                    shape = SegmentedButtonDefaults.itemShape(index, FocusView.entries.size),
                ) {
                    Text(if (candidate == FocusView.Stats) s.focusStats.homeButton else s.cycles)
                }
            }
        }
        Box(Modifier.weight(1f).widthIn(max = 560.dp).fillMaxWidth().padding(top = 8.dp)) {
            when (view) {
                FocusView.Stats -> StatsPane(Modifier.fillMaxSize())
                FocusView.Cycles -> TemplatesPane(Modifier.fillMaxSize())
            }
        }
    }
}
