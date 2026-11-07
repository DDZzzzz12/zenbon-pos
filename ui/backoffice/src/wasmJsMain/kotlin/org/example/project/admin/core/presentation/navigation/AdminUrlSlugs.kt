package org.example.project.admin.core.presentation.navigation

import androidx.navigation.NavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType

/**
 * Readable URL fragments for the panel's type-safe routes: `#document-detail/42`,
 * `#settings?section=PRINTERS`, `#dashboard`, instead of the library default
 * `#org.example.project.admin.core.presentation.navigation.DocumentDetailRoute/42`.
 *
 * Generic on purpose, with no per-route table: a slug is the route class's simple name in kebab case
 * without the `Route` suffix, followed by the route's own arguments in the shape the route pattern
 * already has (path arguments after `/`, the rest as `?key=value`). [toRoute] reverses it to the
 * route string `NavController.navigate(String)` takes. A new `@Serializable` route in
 * [ROUTE_PACKAGE] therefore gets a URL for free. Arguments that equal their default are left out.
 *
 * Fragments in the old long form (a bookmark made before this) are still accepted by [toRoute].
 */
object AdminUrlSlugs {
    /** Where every route class lives; the slug carries only the simple name. */
    const val ROUTE_PACKAGE = "org.example.project.admin.core.presentation.navigation."
    private const val ROUTE_SUFFIX = "Route"
    private val SLUG_HEAD = Regex("[a-z0-9]+(-[a-z0-9]+)*")

    /**
     * The fragment (without `#`) for a route [pattern] such as `<package>.KassenbuchRoute?headerId={headerId}`,
     * given the serialized value of each argument that should appear. Null when the pattern is not one
     * of this panel's routes.
     */
    fun slugOf(
        pattern: String,
        argValues: Map<String, String>,
    ): String? {
        if (!pattern.startsWith(ROUTE_PACKAGE)) return null
        val withoutPackage = pattern.removePrefix(ROUTE_PACKAGE)
        val head = withoutPackage.substringBefore('?')
        val query = withoutPackage.substringAfter('?', missingDelimiterValue = "")
        val segments = head.split('/')
        val name = segments.first()
        if (!name.endsWith(ROUTE_SUFFIX)) return null

        val path =
            segments.drop(1).map { segment ->
                argValues[segment.removeSurrounding("{", "}")] ?: return null
            }
        val kept =
            query
                .split('&')
                .filter { it.isNotEmpty() }
                .map { it.substringBefore('=') }
                .filter { it in argValues }
                .joinToString("&") { "$it=${argValues.getValue(it)}" }

        return buildString {
            append(kebab(name.removeSuffix(ROUTE_SUFFIX)))
            path.forEach { append('/').append(it) }
            if (kept.isNotEmpty()) append('?').append(kept)
        }
    }

    /**
     * The route string for a fragment (with or without the leading `#`), or null for an empty or
     * malformed one. It may still name a route that does not exist; `navigate` rejects that.
     */
    fun toRoute(fragment: String): String? {
        val slug = fragment.removePrefix("#")
        if (slug.startsWith(ROUTE_PACKAGE)) return slug
        val head = slug.substringBefore('/').substringBefore('?')
        if (!SLUG_HEAD.matches(head)) return null
        val rest = slug.removePrefix(head)
        return ROUTE_PACKAGE + pascal(head) + ROUTE_SUFFIX + rest
    }

    /** The slug for a back-stack entry: its route's argument values, defaults and nulls dropped. */
    fun slugOf(entry: NavBackStackEntry): String {
        val pattern = entry.destination.route.orEmpty()
        val arguments = entry.arguments
        val values = mutableMapOf<String, String>()
        if (arguments != null) {
            for ((key, argument) in entry.destination.arguments) {
                serializedValue(argument, arguments, key)?.let { values[key] = it }
            }
        }
        // A route that is not one of ours keeps the library's long form rather than disappearing.
        return slugOf(pattern, values) ?: pattern
    }

    @Suppress("UNCHECKED_CAST")
    private fun serializedValue(
        argument: NavArgument,
        arguments: androidx.savedstate.SavedState,
        key: String,
    ): String? {
        val type = argument.type as NavType<Any?>
        val value = type[arguments, key] ?: return null
        if (argument.isDefaultValuePresent && value == argument.defaultValue) return null
        return type.serializeAsValue(value)
    }

    private fun kebab(pascal: String): String =
        buildString {
            pascal.forEachIndexed { index, char ->
                if (char.isUpperCase() && index > 0) append('-')
                append(char.lowercaseChar())
            }
        }

    private fun pascal(kebab: String): String = kebab.split('-').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
}
