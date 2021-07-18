package marais.graphql.dsl

import org.slf4j.Logger
import kotlin.reflect.KClass

/**
 * Called when converting a string input to your Id class
 */
typealias IdCoercer<T> = (value: String?) -> T?

/**
 * Shared read-only context for all builders
 */
abstract class SchemaBuilderContext(internal val log: Logger) {

    abstract val idCoercers: Map<KClass<*>, IdCoercer<*>>
    abstract val inputs: List<InputBuilder>

    fun isInputType(kclass: KClass<*>) = inputs.find { it.kclass == kclass } != null
}
