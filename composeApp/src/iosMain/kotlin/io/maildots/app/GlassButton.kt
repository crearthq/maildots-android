package io.maildots.app

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitInteropInteractionMode
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.UIKit.UIAction
import platform.UIKit.UIButton
import platform.UIKit.UIButtonConfiguration
import platform.UIKit.UIButtonConfigurationCornerStyleCapsule
import platform.UIKit.UIButtonConfigurationSize
import platform.UIKit.UIColor
import platform.UIKit.UIControlEventPrimaryActionTriggered
import platform.UIKit.UIDevice
import platform.UIKit.UIImage
import platform.UIKit.UIImageSymbolConfiguration
import platform.UIKit.UIImageSymbolWeightSemibold

/** Liquid Glass düğmeleri iOS 26 ile geldi; öncesinde Material düğmesi kullanılır. */
internal val glassAvailable: Boolean by lazy {
    (UIDevice.currentDevice.systemVersion.substringBefore('.').toIntOrNull() ?: 0) >= 26
}

/**
 * Listedeki "Yeni e-posta" düğmesi: sistemin Liquid Glass düğmesi (marka mavisi tonunda).
 * Compose'un üstünde yerel görünüm olarak durur; böylece cam, arkasındaki listeyi gerçekten
 * kırarak gösterir. Dokunuşlar doğrudan düğmeye gider.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalComposeUiApi::class)
@Composable
internal fun GlassComposeButton(label: String, onClick: () -> Unit) {
    val click by rememberUpdatedState(onClick)
    val button = remember(label) {
        val config = UIButtonConfiguration.prominentGlassButtonConfiguration().apply {
            title = label
            image = UIImage.systemImageNamed("square.and.pencil")
            preferredSymbolConfigurationForImage = UIImageSymbolConfiguration.configurationWithWeight(UIImageSymbolWeightSemibold)
            imagePadding = 8.0
            buttonSize = UIButtonConfigurationSize.UIButtonConfigurationSizeLarge
            cornerStyle = UIButtonConfigurationCornerStyleCapsule
            baseBackgroundColor = UIColor(red = 0x2A / 255.0, green = 0x38 / 255.0, blue = 0xEC / 255.0, alpha = 1.0)
            baseForegroundColor = UIColor.whiteColor
        }
        UIButton.buttonWithConfiguration(config, primaryAction = null).apply {
            backgroundColor = UIColor.clearColor
            opaque = false
            addAction(UIAction.actionWithHandler { click() }, forControlEvents = UIControlEventPrimaryActionTriggered)
        }
    }
    val (width, height) = remember(button) { button.intrinsicContentSize.useContents { width to height } }
    UIKitView(
        factory = { button },
        // Compose düğmeyi sınırlarına kırpan bir kaba koyar; camın kenar ışığı ve gölgesi
        // dışarı taşar, kırpılınca dikdörtgen leke gibi görünür.
        update = { view -> view.superview?.clipsToBounds = false },
        modifier = Modifier.size(width.dp, height.dp),
        properties = UIKitInteropProperties(
            interactionMode = UIKitInteropInteractionMode.NonCooperative,
            placedAsOverlay = true,
        ),
    )
}
