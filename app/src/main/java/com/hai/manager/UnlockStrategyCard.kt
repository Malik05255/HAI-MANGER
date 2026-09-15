package com.hai.manager

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hai.manager.unlock.UnlockPathStatus
import com.hai.manager.unlock.UnlockStrategyPlan

@Composable
fun UnlockStrategyCard(plan: UnlockStrategyPlan) {
    HaiCard {
        HaiSectionTitle(plan.title)
        Text(plan.summary)
        HorizontalDivider()

        plan.steps.sortedBy { it.order }.forEachIndexed { index, step ->
            if (index > 0) HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("${step.order}. ${step.title}", fontWeight = FontWeight.SemiBold)
                HaiStatusChip(
                    text = step.status.displayName,
                    active = step.status == UnlockPathStatus.READY || step.status == UnlockPathStatus.DIAGNOSTICS
                )
            }
            Text(step.description)
        }
    }
}
