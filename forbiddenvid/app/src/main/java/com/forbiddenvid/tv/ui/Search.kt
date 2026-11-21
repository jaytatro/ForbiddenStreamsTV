package com.forbiddenvid.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.forbiddenvid.tv.data.Addons

/** Search every add-on that supports it. Results come back as one row per add-on catalog. */
@Composable
fun SearchScreen(onAddons: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    val rows = Addons.searchRows()

    Column(Modifier.fillMaxSize().padding(top = 30.dp)) {
        ScreenTitle("Search", Modifier.padding(start = 36.dp))
        if (Addons.list.isEmpty()) {
            MessageBox("No add-ons yet", "Install an add-on that offers search.", "Add an add-on", onAddons)
            return@Column
        }
        if (rows.isEmpty()) {
            MessageBox("Search isn't available", "None of your add-ons offer search.", "Open Add-ons", onAddons)
            return@Column
        }
        Row(Modifier.padding(start = 36.dp, top = 14.dp, end = 36.dp), verticalAlignment = Alignment.CenterVertically) {
            TvTextField(text, { text = it }, "Movie or show name…", onDone = { query = text.trim() },
                modifier = Modifier.width(620.dp), imeAction = ImeAction.Search)
            Pill("Search", selected = true, onClick = { query = text.trim() }, modifier = Modifier.padding(start = 14.dp))
        }
        if (query.isBlank()) {
            Text("Searching ${rows.size} catalog${if (rows.size == 1) "" else "s"} from your add-ons.", color = FS.TextDim, fontSize = 16.sp,
                modifier = Modifier.padding(start = 36.dp, top = 16.dp))
        } else {
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f).padding(top = 16.dp),
                contentPadding = PaddingValues(bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                rows.forEach { (a, c) ->
                    item(key = query + "|" + a.manifestUrl + "|" + c.type + "|" + c.id) {
                        CatalogRow(a, c, label = "${c.name} · ${typeName(c.type)}", search = query, onFocus = { })
                    }
                }
            }
        }
    }
}
