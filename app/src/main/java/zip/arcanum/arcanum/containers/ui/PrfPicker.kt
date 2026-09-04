package zip.arcanum.arcanum.containers.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import zip.arcanum.core.components.AppSheet
import zip.arcanum.crypto.VeraCryptEngine

/**
 * Auto plus the six PRFs, in the order the native side numbers them. One list, because
 * every place that asks about a PRF is asking the same question: which derivation opens
 * this header. Auto stands for the five PBKDF2 hashes - Argon2id is never scanned for and
 * only ever runs when it is picked here by name (#177).
 */
@Composable
fun rememberPrfOptions(): List<Pair<Int, String>> = remember {
    listOf(VeraCryptEngine.HASH_AUTO to "Auto") +
        (0..VeraCryptEngine.HASH_ARGON2ID).map { it to VeraCryptEngine.hashIdToString(it) }
}

/**
 * The PRF dropdown. Used for a vault on the unlock screen, for the hidden volume being
 * protected on the same screen, and for naming the PRF of the volume whose password or
 * keyfiles are being changed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrfPicker(
    hashes: List<Pair<Int, String>>,
    selected: Int,
    label: String,
    onSelect: (Int) -> Unit
) {
    var showSheet by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value         = hashes.first { it.first == selected }.second,
            onValueChange = {},
            readOnly      = true,
            label         = { Text(label) },
            trailingIcon  = { Icon(Icons.Outlined.ExpandMore, contentDescription = null) },
            modifier      = Modifier.fillMaxWidth()
        )
        Box(Modifier.matchParentSize().clickable { showSheet = true })
    }
    if (showSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        AppSheet(
            onDismissRequest = { showSheet = false },
            sheetState       = sheetState
        ) {
            Column(
                modifier            = Modifier.padding(bottom = 32.dp),
                verticalArrangement = Arrangement.Top
            ) {
                Text(
                    text       = label,
                    style      = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier   = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
                hashes.forEach { (id, itemLabel) ->
                    Row(
                        modifier          = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(id); showSheet = false }
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selected == id,
                            onClick  = { onSelect(id); showSheet = false }
                        )
                        Text(
                            text     = itemLabel,
                            style    = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
        }
    }
}
