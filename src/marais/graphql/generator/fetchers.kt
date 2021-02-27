package marais.graphql.generator

import graphql.TrivialDataFetcher
import graphql.schema.DataFetcher
import graphql.schema.PropertyDataFetcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.future.asCompletableFuture
import kotlinx.coroutines.future.future
import kotlinx.coroutines.reactive.asPublisher
import marais.graphql.dsl.Argument
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.reflect.KFunction
import kotlin.reflect.KProperty1
import kotlin.reflect.full.callSuspend

fun <R, O> propertyFetcher(property: KProperty1<R, O>, receiver: R? = null): DataFetcher<O> {
    return receiver?.let { TrivialDataFetcher { property.getter(receiver) } }
            ?: PropertyDataFetcher.fetching(property.getter)
}

/**
 * The graphql engine accepts simple values, CompletableFuture and Publisher, we map any types to those
 */
fun transformResult(value: Any?, context: CoroutineContext = EmptyCoroutineContext): Any? {
    return when (value) {
        is Deferred<*> -> value.asCompletableFuture()
        is Flow<*> -> (value as Flow<Any>).asPublisher(context)
        else -> value
    }
}

/**
 * Create the appropriate [DataFetcher] for a function.
 * Handles the case where the function is suspend or returns Flow
 */
fun functionFetcher(
        func: KFunction<Any?>,
        args: List<Argument>,
        scope: CoroutineScope = GlobalScope,
        context: CoroutineContext = EmptyCoroutineContext,
        receiver: Any? = null
): DataFetcher<Any?> {
    return if (func.isSuspend) {
        DataFetcher { env ->
            scope.future(context) {
                transformResult(func.callSuspend(
                        receiver ?: env.getSource(),
                        *args.map {
                            it.resolve<Any>(env)
                        }.toTypedArray()
                ), context)
            }
        }
    } else {
        DataFetcher { env ->
            transformResult(func.call(
                    receiver ?: env.getSource(),
                    *args.map {
                        it.resolve<Any>(env)
                    }.toTypedArray()
            ), context)
        }
    }
}
