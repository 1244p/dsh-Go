package io.github.ccch1mneyyy.dsh_app.xposed

import java.lang.reflect.Method

/**
 * Reflection that never throws. Every lookup here can fail for a reason outside our control — the
 * ROM renamed a class, the method moved to a superclass, a hidden API is blocked — and a module
 * that throws while the framework is starting takes the phone down with it. Callers get `null` and
 * record the miss in the install report instead.
 */
internal object HookSupport {
    /** [name] from [loader], or null when the class is absent or cannot be linked. */
    fun findClassOrNull(loader: ClassLoader, name: String): Class<*>? = try {
        Class.forName(name, false, loader)
    } catch (_: ClassNotFoundException) {
        null
    } catch (_: LinkageError) {
        null
    } catch (_: SecurityException) {
        null
    }

    /** [method] on [clazz] or the nearest superclass that declares it, made accessible. */
    fun findMethod(clazz: Class<*>, name: String, vararg parameterTypes: Class<*>): Method? {
        var current: Class<*>? = clazz
        while (current != null) {
            val found = try {
                current.getDeclaredMethod(name, *parameterTypes)
            } catch (_: NoSuchMethodException) {
                null
            } catch (_: LinkageError) {
                null
            } catch (_: SecurityException) {
                null
            }
            if (found != null) {
                return try {
                    found.isAccessible = true
                    found
                } catch (_: RuntimeException) {
                    null
                }
            }
            current = current.superclass
        }
        return null
    }

    /** Every declared method that matches, with inaccessible ones dropped. */
    fun findMethods(clazz: Class<*>, predicate: (Method) -> Boolean): List<Method> =
        try {
            clazz.declaredMethods.filter(predicate).onEach { runCatching { it.isAccessible = true } }
        } catch (_: LinkageError) {
            emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }
}
