package dev.gyro.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidatePlacement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * 复合按压交互源：将多个子项的 [InteractionSource] 数据流汇聚为单一交互流。
 */
class CombinedInteractionSource(
    private val sources: List<InteractionSource>,
) : InteractionSource {
    constructor(vararg sources: InteractionSource) : this(sources.toList())

    override val interactions: Flow<Interaction> =
        merge(*sources.map { it.interactions }.toTypedArray())
}

object ExpressiveMotionDefaults {
    const val PHYSICAL_VISIBILITY_THRESHOLD = 0.0001f

    val PressDownSpring =
        spring<Float>(
            dampingRatio = 1.0f,
            stiffness = 700f,
            visibilityThreshold = PHYSICAL_VISIBILITY_THRESHOLD,
        )

    val ReleaseReboundSpring =
        spring<Float>(
            dampingRatio = 0.35f,
            stiffness = 450f,
            visibilityThreshold = PHYSICAL_VISIBILITY_THRESHOLD,
        )
}

/**
 * 物理弹簧微弹性按压修饰符。
 *
 * 在按键按下时带来极其细腻的物理缩放（默认 0.95f），松手时伴随高阻尼弹簧迅速回弹。
 * 替代在视频画面上产生黑圈和脏斑的原生 Material 涟漪。
 */
fun Modifier.expressivePress(
    interactionSource: InteractionSource,
    pressedScale: Float = 0.95f,
    enabled: Boolean = true,
): Modifier =
    if (!enabled) {
        this
    } else {
        this then ExpressivePressNodeElement(
            interactionSource = interactionSource,
            pressedScale = pressedScale,
        )
    }

internal data class ExpressivePressNodeElement(
    val interactionSource: InteractionSource,
    val pressedScale: Float,
) : ModifierNodeElement<ExpressivePressNode>() {
    override fun create(): ExpressivePressNode =
        ExpressivePressNode(
            interactionSource = interactionSource,
            pressedScale = pressedScale,
        )

    override fun update(node: ExpressivePressNode) {
        node.update(interactionSource, pressedScale)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "expressivePress"
        properties["interactionSource"] = interactionSource
        properties["pressedScale"] = pressedScale
    }
}

internal class ExpressivePressNode(
    private var interactionSource: InteractionSource,
    private var pressedScale: Float,
) : Modifier.Node(), LayoutModifierNode {
    val scaleAnimatable =
        Animatable(
            initialValue = 1f,
            visibilityThreshold = ExpressiveMotionDefaults.PHYSICAL_VISIBILITY_THRESHOLD,
        )
    private var collectJob: Job? = null
    private var animJob: Job? = null

    override fun onAttach() {
        startObserving()
    }

    override fun onDetach() {
        collectJob?.cancel()
        collectJob = null
        animJob?.cancel()
        animJob = null
    }

    fun update(
        interactionSource: InteractionSource,
        pressedScale: Float,
    ) {
        val interactionChanged = this.interactionSource != interactionSource
        this.interactionSource = interactionSource
        this.pressedScale = pressedScale
        if (interactionChanged && isAttached) {
            startObserving()
        }
    }

    private fun startObserving() {
        collectJob?.cancel()
        collectJob = coroutineScope.launch {
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> {
                        animJob?.cancel()
                        animJob = launch {
                            scaleAnimatable.animateTo(
                                targetValue = pressedScale,
                                animationSpec = ExpressiveMotionDefaults.PressDownSpring,
                            ) {
                                invalidatePlacement()
                            }
                        }
                    }

                    is PressInteraction.Release, is PressInteraction.Cancel -> {
                        animJob?.cancel()
                        animJob = launch {
                            scaleAnimatable.animateTo(
                                targetValue = 1f,
                                animationSpec = ExpressiveMotionDefaults.ReleaseReboundSpring,
                            ) {
                                invalidatePlacement()
                            }
                        }
                    }
                }
            }
        }
    }

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(0, 0) {
                scaleX = scaleAnimatable.value
                scaleY = scaleAnimatable.value
            }
        }
    }
}
