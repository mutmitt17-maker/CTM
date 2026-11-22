package cd.ctm.agent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.math.RoundingMode

/** Montant affiché avec 2 décimales. */
fun usd(value: BigDecimal): String = value.setScale(2, RoundingMode.HALF_UP).toPlainString() + " USD"

fun usd(value: Double): String = usd(BigDecimal.valueOf(value))

/** Cadre commun des écrans : titre, bouton Retour, défilement, et marges qui évitent les barres du système. */
@Composable
fun ScreenFrame(title: String, onBack: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                TextButton(onClick = onBack) { Text("‹ Retour") }
            }
            Text(title, style = MaterialTheme.typography.titleLarge)
        }
        content()
    }
}

@Composable
fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    keyboard: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun ErrorText(message: String) {
    Text(message, color = MaterialTheme.colorScheme.error)
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

/** Ligne de choix unique (bouton radio). */
@Composable
fun Choice(selected: Boolean, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}

/** Bouton à bascule : plein quand il est choisi, contour sinon. */
@Composable
fun Toggle(selected: Boolean, label: String, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick) { Text(label) }
    }
}

@Composable
fun ClickableLine(text: String, onClick: () -> Unit) {
    Text(text, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp))
}
