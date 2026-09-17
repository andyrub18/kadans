package app.kadans.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.kadans.api.model.PomodoroDayStats
import app.kadans.i18n.LocalStrings
import app.kadans.i18n.StringsCatalog
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun StatsScreen(onBack: () -> Unit, viewModel: StatsViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsState()
    val s = LocalStrings.current
    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("← " + s.back) }
                Text(s.focusStats.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxSize()) {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatsRange.entries.forEach { range ->
                        FilterChip(
                            selected = state.range == range,
                            onClick = { viewModel.select(range) },
                            label = { Text(s.focusStats.rangeName(range)) },
                        )
                    }
                }
                when (val current = state) {
                    is StatsUiState.Loading ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    is StatsUiState.Error ->
                        Column(
                            Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(s.errorFor(current.code, current.message), color = MaterialTheme.colorScheme.error)
                            Button(onClick = viewModel::refresh, modifier = Modifier.padding(top = 12.dp)) { Text(s.retry) }
                        }
                    is StatsUiState.Content -> StatsContent(current.summary, s)
                }
            }
        }
    }
}

@Composable
private fun StatsContent(summary: StatsSummary, s: StringsCatalog) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Tile(s.focusStats.focusTime, StatsViewModel.formatMinutes(summary.stats.focusMinutes))
                Tile(s.focusStats.breakTime, StatsViewModel.formatMinutes(summary.stats.breakMinutes))
                Tile(s.focusStats.sessionsCompleted, summary.stats.completedRuns.toString())
                Tile(s.focusStats.sessionsCancelled, summary.stats.cancelledRuns.toString())
                Tile(s.focusStats.dailyAverage, StatsViewModel.formatMinutes(summary.dailyAverageFocusMinutes))
                summary.bestDay?.let { Tile(s.focusStats.bestDay, it.date.toString() + " · " + StatsViewModel.formatMinutes(it.focusMinutes)) }
            }
        }
        if (summary.stats.focusMinutes == 0) {
            item { Text(s.focusStats.noFocusYet, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp)) }
        } else {
            item { Text(s.focusStats.focusPerDay, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp)) }
            // Newest first: today is what people look for.
            items(summary.days.asReversed(), key = { it.date.toString() }) { day -> DayBar(day, summary.maxFocusMinutes) }
        }
    }
}

@Composable
private fun Tile(label: String, value: String) {
    Card {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DayBar(day: PomodoroDayStats, max: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(day.date.toString(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(88.dp))
        Box(Modifier.weight(1f).height(14.dp)) {
            if (day.focusMinutes > 0 && max > 0) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction = (day.focusMinutes.toFloat() / max).coerceIn(0.02f, 1f))
                        .height(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
        Text(
            if (day.focusMinutes > 0) StatsViewModel.formatMinutes(day.focusMinutes) else "–",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(84.dp).padding(start = 8.dp),
        )
    }
}
