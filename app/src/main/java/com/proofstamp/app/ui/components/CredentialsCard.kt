package com.proofstamp.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.proofstamp.app.R
import com.proofstamp.app.data.c2pa.C2paReport
import com.proofstamp.app.data.c2pa.C2paState
import com.proofstamp.app.ui.theme.PsColors

/** Shows the C2PA Content Credentials read from the file — works for any C2PA asset, not just ProofStamp's. */
@Composable
fun CredentialsCard(report: C2paReport?) {
    if (report == null) return
    val color = when (report.state) {
        C2paState.VALID -> PsColors.Accent
        C2paState.MODIFIED -> PsColors.Danger
        C2paState.ABSENT -> PsColors.TextDim
        C2paState.ERROR -> PsColors.Warn
    }
    val icon = when (report.state) {
        C2paState.VALID -> Icons.Outlined.Verified
        C2paState.ABSENT -> Icons.Outlined.Info
        else -> Icons.Outlined.Warning
    }
    val title = stringResource(
        when (report.state) {
            C2paState.VALID -> R.string.c2pa_valid
            C2paState.MODIFIED -> R.string.c2pa_modified
            C2paState.ABSENT -> R.string.c2pa_absent
            C2paState.ERROR -> R.string.c2pa_error
        },
    )
    PsCard(accent = color) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, tint = color)
            Text(title, style = MaterialTheme.typography.titleMedium, color = PsColors.Text, modifier = Modifier.weight(1f))
        }
        if (report.state == C2paState.VALID || report.state == C2paState.MODIFIED) {
            Spacer(Modifier.height(10.dp))
            report.claimGenerator?.let { KeyValueRow(stringResource(R.string.c2pa_signed_by), it) }
            report.title?.let { KeyValueRow(stringResource(R.string.c2pa_title), it, mono = true) }
            report.issuer?.let { KeyValueRow(stringResource(R.string.c2pa_issuer), it, maxLines = 3) }
            report.capturedWhen?.let { KeyValueRow(stringResource(R.string.c2pa_signed_at), it, mono = true) }
            if (report.failureCodes.isNotEmpty()) {
                KeyValueRow(stringResource(R.string.c2pa_failures), report.failureCodes.joinToString("\n"), mono = true, maxLines = 4)
            }
            if (report.state == C2paState.VALID) {
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.c2pa_third_party), style = MaterialTheme.typography.bodySmall, color = PsColors.TextDim)
            }
        }
    }
}
