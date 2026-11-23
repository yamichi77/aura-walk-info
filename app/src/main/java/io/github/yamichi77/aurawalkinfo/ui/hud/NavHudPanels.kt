package io.github.yamichi77.aurawalkinfo.ui.hud

import android.util.Log
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.foundation.shape.CornerSize
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.SubspaceComposable
import androidx.xr.compose.subspace.draw.alpha
import androidx.xr.compose.subspace.layout.SpatialRoundedCornerShape
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.width
import io.github.yamichi77.aurawalkinfo.isDebugBuild
import io.github.yamichi77.aurawalkinfo.heading.HeadingTracking
import io.github.yamichi77.aurawalkinfo.heading.hudHeadBearing
import io.github.yamichi77.aurawalkinfo.settings.HudLayoutConfig as Layout
import io.github.yamichi77.aurawalkinfo.ui.theme.HudDetail
import io.github.yamichi77.aurawalkinfo.ui.theme.HudText

internal fun HudVisualSpec.Clusters.rightClusterNavigation(bigMapOpen: Boolean) =
    navigation.takeUnless { bigMapOpen }

@Composable
internal fun NavGuidancePanel(
    visual: NavHudVisual,
    opacity: Float,
    modifier: Modifier = Modifier,
    textScale: Float = 1f,
) {
    val debug = LocalContext.current.isDebugBuild()
    LaunchedEffect(debug, visual.state, visual.primaryForLog, visual.secondary) {
        if (debug) Log.i(
            "AuraNav",
            "HUD state=${visual.state} primary=${visual.primaryForLog} secondary=${visual.secondary.orEmpty()}",
        )
    }
    Column(modifier.alpha(opacity), horizontalAlignment = Alignment.CenterHorizontally) {
        val primarySize = with(LocalDensity.current) { (16.dp * textScale).toSp() }
        val secondarySize = with(LocalDensity.current) { (14.dp * textScale).toSp() }
        NavGuidanceLine(visual.primary, visual.primaryColor, primarySize)
        visual.secondary?.let {
            NavGuidanceLine(it, visual.secondaryColor, secondarySize)
        }
    }
}

@Composable
private fun NavGuidanceLine(text: String, color: Color, size: TextUnit) {
    BasicText(
        text,
        style = LocalTextStyle.current.copy(
            color = color, fontSize = size, lineHeight = size, textAlign = TextAlign.Center,
        ),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(minFontSize = size * 0.7f, maxFontSize = size, stepSize = size * 0.05f),
    )
}

@Composable
@SubspaceComposable
internal fun SpatialNavGuidance(
    visual: NavHudVisual,
    mapSize: Float,
    dpPerMeter: Float,
    scale: Float,
    opacity: Float,
    motionOpacity: Float,
    headingTracking: HeadingTracking?,
) {
    val physicalDpPerMeter = dpPerMeter / scale
    val withArrow = visual.arrow != null
    val headBearing: (Long) -> Double? = remember(headingTracking) {
        { now -> headingTracking?.hudHeadBearing(now) }
    }
    SpatialPanel(
        HudPlacement.modifier(HudPlacement.navPose(mapSize, withArrow), dpPerMeter, scale)
            .width((HudPlacement.navWidthMeters(withArrow) * physicalDpPerMeter).dp)
            .height((HudPlacement.navHeightMeters * physicalDpPerMeter).dp)
            .alpha(motionOpacity),
        shape = SpatialRoundedCornerShape(CornerSize(0.dp)),
    ) {
        Box(Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
            NavGuidanceContent(visual, opacity, physicalDpPerMeter, headBearing)
        }
    }
}

@Composable
internal fun NavGuidanceContent(
    visual: NavHudVisual,
    opacity: Float,
    dpPerMeter: Float,
    headBearing: (Long) -> Double?,
) {
    val textScale = Layout.textScale(dpPerMeter)
    val arrow = visual.arrow
    if (arrow == null) {
        NavGuidancePanel(visual, opacity, textScale = textScale)
        return
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val arrowSize = (HudPlacement.navArrowSizeMeters * dpPerMeter).dp
        Box(Modifier.size(arrowSize)) {
            NavArrowCanvas(arrow, headBearing, opacity, Modifier.fillMaxSize())
        }
        Spacer(Modifier.width((HudPlacement.navArrowGapMeters * dpPerMeter).dp))
        NavGuidancePanel(visual, opacity, Modifier.weight(1f), textScale)
    }
}

@Composable
internal fun BoxScope.DestinationMapOverlay(
    prompt: DestinationPrompt,
    diameter: Dp,
    enabled: Boolean,
    onTap: (HudTarget) -> Unit,
) {
    if (!enabled || prompt == DestinationPrompt.Map || prompt == DestinationPrompt.ConfirmEnd) return
    val primarySize = with(LocalDensity.current) { 16.dp.toSp() }
    val secondarySize = with(LocalDensity.current) { 14.dp.toSp() }
    Column(
        Modifier.align(Alignment.Center).fillMaxWidth(0.72f),
        verticalArrangement = Arrangement.spacedBy(diameter * 0.012f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val message = when (prompt) {
            is DestinationPrompt.Listening -> "話してください"
            DestinationPrompt.Searching -> "検索中…"
            DestinationPrompt.Routing -> "経路を取得中…"
            is DestinationPrompt.Message -> prompt.text
            else -> null
        }
        message?.let {
            Text(it, color = HudText, fontSize = primarySize, textAlign = TextAlign.Center)
        }
        if (prompt is DestinationPrompt.Listening && prompt.partial.isNotBlank()) {
            Text(
                prompt.partial, color = HudDetail, fontSize = secondarySize,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (prompt is DestinationPrompt.Candidates) {
            prompt.items.take(destinationCandidateTargets.size).forEachIndexed { index, candidate ->
                Column(
                    Modifier.fillMaxWidth().hudClick(destinationCandidateTargets[index], onTap),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        candidate.name, color = HudText, fontSize = primarySize, lineHeight = primarySize,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    val detail = listOf(navDistance(candidate.distanceMeters), shortPlaceAddress(candidate.address))
                        .filter(String::isNotBlank).joinToString(" ・ ")
                    Text(
                        detail, color = HudDetail, fontSize = secondarySize, lineHeight = secondarySize,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box(
                Modifier.fillMaxWidth().height(24.dp).hudClick(HudTarget.NAV_CANCEL, onTap),
                contentAlignment = Alignment.Center,
            ) {
                Text("やめる", color = HudText, fontSize = secondarySize, lineHeight = secondarySize)
            }
        }
    }
}

private val addressPrefix = Regex("^日本[、, ]*|^〒?[0-9０-９]{3}[-−－][0-9０-９]{4}\\s*|^東京都|^北海道|^(大阪|京都)府|^[^県]{2,4}県")
private val addressNumber = Regex("[0-9０-９]")

private fun shortPlaceAddress(address: String): String {
    var rest = address.trim()
    repeat(3) { rest = rest.replaceFirst(addressPrefix, "").trim() }
    return rest.substringBefore('、').split(addressNumber, limit = 2).first().trim()
}
