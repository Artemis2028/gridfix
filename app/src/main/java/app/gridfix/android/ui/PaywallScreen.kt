package app.gridfix.android.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gridfix.android.AppInfo
import app.gridfix.android.billing.BillingManager
import app.gridfix.android.ui.theme.LabelFamily
import app.gridfix.android.ui.theme.MonoFamily

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Subscription gate shown instead of the app when there is no active
 * MGRS GPS Pro subscription — the Blackout layout from the design canvas:
 * one line of intent, four benefits, two plan cards (annual pre-selected),
 * the trial sentence, one amber action. Prices and the free-trial length
 * come live from Play Console — nothing is hardcoded here. [onClose] is
 * non-null only in the debug-build preview, where the paywall is dismissible.
 */
@Composable
fun PaywallScreen(
    billing: BillingManager,
    onClose: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val plans by billing.plans.collectAsStateWithLifecycle()
    val plansStatus by billing.plansStatus.collectAsStateWithLifecycle()
    val notice by billing.notice.collectAsStateWithLifecycle()
    var selectedId by rememberSaveable { mutableStateOf(BillingManager.ANNUAL) }
    val selected = plans.firstOrNull { it.productId == selectedId } ?: plans.firstOrNull()
    val cs = MaterialTheme.colorScheme

    Surface(modifier = Modifier.fillMaxSize(), color = cs.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            // Header: product mark left, restore right
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "MGRS GPS PRO",
                    fontFamily = LabelFamily,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 3.sp,
                    color = cs.primary,
                )
                Text(
                    "RESTORE",
                    fontFamily = LabelFamily,
                    fontSize = 12.sp,
                    letterSpacing = 1.5.sp,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier
                        .clickable { billing.restore() }
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                )
            }
            Spacer(Modifier.height(10.dp))

            Text(
                "Land nav that works where the signal doesn't.",
                fontFamily = LabelFamily,
                fontSize = 30.sp,
                lineHeight = 34.sp,
                fontWeight = FontWeight.SemiBold,
                color = cs.onBackground,
            )
            Spacer(Modifier.height(22.dp))

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Benefit("Offline maps: download USGS topo by area, or bring your own MBTiles")
                Benefit("MGRS grid overlay, 4- to 10-digit, anywhere on earth")
                Benefit("Terrain: line of sight, viewshed, contours, elevation")
                Benefit("Tracks, routes, route cards, GPX / KML / ATAK export")
            }
            Spacer(Modifier.height(24.dp))

            if (plans.isEmpty()) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (plansStatus == BillingManager.PlansStatus.LOADING) {
                        CircularProgressIndicator(color = cs.primary)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Loading plans from Google Play…",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            "Plans are not available right now.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = cs.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { billing.restore() }) { Text("Retry") }
                }
            } else {
                val monthly = plans.firstOrNull { it.productId == BillingManager.MONTHLY }
                // Monthly left, annual right, whatever order Play returns them in
                val ordered = plans.sortedBy { if (it.period == "year") 1 else 0 }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ordered.forEach { plan ->
                        val savings = if (plan.period == "year" && monthly != null && monthly.priceMicros > 0) {
                            val pct = 100L - (plan.priceMicros * 100L / (monthly.priceMicros * 12L))
                            if (pct in 1L..99L) "SAVE $pct%" else null
                        } else null
                        PlanCard(
                            plan = plan,
                            tag = savings,
                            selected = selected?.productId == plan.productId,
                            onSelect = { selectedId = plan.productId },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))

                Text(
                    if (selected != null && selected.trialDays > 0) {
                        "${selected.trialDays}-day free trial, then ${selected.price} per ${selected.period}. " +
                            "Cancel anytime in Google Play — nothing is charged if you cancel before the trial ends."
                    } else if (selected != null) {
                        "${selected.price} per ${selected.period}, auto-renewing until canceled. Cancel anytime in Google Play."
                    } else "",
                    fontFamily = LabelFamily,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = cs.onSurfaceVariant,
                )
                Spacer(Modifier.height(28.dp))

                Button(
                    onClick = {
                        val activity = context.findActivity()
                        if (activity != null && selected != null) {
                            billing.launchPurchase(activity, selected)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    enabled = selected != null,
                    shape = RectangleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = cs.primary,
                        contentColor = cs.onPrimary,
                    ),
                ) {
                    Text(
                        if (selected != null && selected.trialDays > 0) "START ${selected.trialDays}-DAY FREE TRIAL"
                        else "SUBSCRIBE",
                        fontFamily = LabelFamily,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 3.sp,
                        maxLines = 1,
                    )
                }
            }

            notice?.let {
                Spacer(Modifier.height(10.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Cancel anytime in Google Play · ",
                    fontFamily = LabelFamily,
                    fontSize = 11.sp,
                    color = cs.onSurfaceVariant,
                )
                Text(
                    "Terms",
                    fontFamily = LabelFamily,
                    fontSize = 11.sp,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier
                        .clickable { openLink(context, AppInfo.TERMS_URL) }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                )
                Text(
                    "·",
                    fontFamily = LabelFamily,
                    fontSize = 11.sp,
                    color = cs.onSurfaceVariant,
                )
                Text(
                    "Privacy",
                    fontFamily = LabelFamily,
                    fontSize = 11.sp,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier
                        .clickable { openLink(context, AppInfo.PRIVACY_URL) }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                )
            }
            if (onClose != null) {
                TextButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("Close preview (debug build)")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Benefit(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(30.dp)) {
        Icon(
            Icons.Outlined.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text,
            fontFamily = LabelFamily,
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
        )
    }
}

/** One plan: name, price, period, a radio dot; amber border and a surface fill when chosen. */
@Composable
private fun PlanCard(
    plan: BillingManager.Plan,
    tag: String?,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Box(modifier.padding(top = 10.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .border(if (selected) 1.5.dp else 1.dp, if (selected) cs.primary else cs.outline)
                .background(if (selected) cs.surface else Color.Transparent)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
                .padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                plan.title.uppercase(),
                fontFamily = LabelFamily,
                fontSize = 12.sp,
                letterSpacing = 1.7.sp,
                color = cs.onSurfaceVariant,
                maxLines = 1,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    plan.price,
                    fontFamily = MonoFamily,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = cs.onSurface,
                    maxLines = 1,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "/ " + plan.period,
                    fontFamily = LabelFamily,
                    fontSize = 12.sp,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 5.dp),
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(2.dp))
            Box(
                Modifier
                    .size(18.dp)
                    .border(1.5.dp, if (selected) cs.primary else cs.outline, CircleShape)
                    .background(if (selected) cs.primary else Color.Transparent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Icon(Icons.Outlined.Check, contentDescription = null, tint = cs.onPrimary, modifier = Modifier.size(12.dp))
                }
            }
        }
        if (tag != null) {
            Text(
                tag,
                fontFamily = LabelFamily,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.2.sp,
                color = cs.onPrimary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-10).dp, y = (-10).dp)
                    .background(cs.primary)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}
