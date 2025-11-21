package icons

import javax.swing.Icon
import javax.swing.ImageIcon

/**
 * Заглушки для HandlebarsIcons, чтобы сборка плагина проходила без реальных SVG.
 */
object HandlebarsIcons {
    private fun load(expUIPath: String, path: String, cacheKey: Int, flags: Int): Icon {
        // Возвращаем пустой Icon-заглушку
        return ImageIcon()
    }

    object Elements {
        val OpenBlock: Icon = load("", "", 0, 0)
        val OpenInverse: Icon = load("", "", 0, 0)
        val OpenMustache: Icon = load("", "", 0, 0)
        val OpenPartial: Icon = load("", "", 0, 0)
        val OpenUnescaped: Icon = load("", "", 0, 0)
    }

    val Handlebars_icon: Icon = load("", "", 0, 0)
}
