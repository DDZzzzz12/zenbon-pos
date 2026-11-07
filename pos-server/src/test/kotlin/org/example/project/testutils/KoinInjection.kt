package org.example.project.testutils

/**
 * The private constructor property [field] of [instance], read by reflection (Kotlin keeps its name).
 *
 * Lets binding tests prove which instance Koin actually injected, not just that a port resolves.
 */
fun injectedValue(
    instance: Any,
    field: String,
): Any? {
    val declared =
        generateSequence<Class<*>>(instance.javaClass) { it.superclass }
            .firstNotNullOfOrNull { cls -> cls.declaredFields.firstOrNull { it.name == field } }
            ?: error("${instance.javaClass.name} has no field `$field` — re-read its constructor")
    declared.isAccessible = true
    return declared.get(instance)
}

/** [injectedValue] narrowed to a [List]. */
fun injectedList(
    instance: Any,
    field: String,
): List<*> = injectedValue(instance, field) as? List<*> ?: error("${instance.javaClass.name}.$field is not a List")
