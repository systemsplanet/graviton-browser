package app.graviton.shell

import javafx.geometry.Pos
import tornadofx.*

class LogoView : Fragment() {
    @Suppress("ConstantConditionIf")
    override val root = hbox {
        alignment = Pos.CENTER
        imageview(appBrandLogo) {
            if (appLogoEffect != null)
                effect = appLogoEffect
        }
        if (!appBrandLogoIsName)
            label("graviton") { addClass(Styles.logoText) }
    }
}
