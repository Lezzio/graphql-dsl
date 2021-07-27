package marais.graphql.dsl

import graphql.schema.StaticDataFetcher
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.valueParameters
import kotlin.reflect.jvm.reflect
import kotlin.reflect.typeOf

@SchemaDsl
sealed class BaseTypeBuilder<R : Any>(
    val kclass: KClass<R>,
    private val instance: R?,
    val name: String,
    description: String?,
    private val context: SchemaBuilderContext
) : DescriptionHolder {
    val description: String? = description ?: kclass.extractDesc()
    val fields: MutableList<Field> = mutableListOf()

    // For the DescriptionHolder implementation
    override var nextDesc: String? = null

    /**
     * Include a field from a static value. No computations, a plain static value that won't ever change.
     *
     * @param name the name of this field.
     * @param value the value returned by this field.
     * @param T the type of this field as shown in the schema. By default, it is inferred from [value].
     */
    @SchemaDsl
    inline fun <reified T : Any> static(name: String, value: T) {
        fields += CustomField(name, takeDesc(), typeOf<T>(), emptyList(), StaticDataFetcher(value))
    }

    /**
     * Include a field originating from a class property.
     *
     * @param property the property to include as a field.
     * @param name the name of the field, defaults to the name of the property.
     * @param O the type of the field as shown in the schema. By default, it is inferred from the type of [property].
     */
    @SchemaDsl
    fun <O> include(
        property: KProperty1<R, O>,
        name: String = property.name
    ) {
        if (name in fields)
            throw Exception("A field with this name is already included")
        fields += PropertyField(property, name, takeDesc())
    }

    /**
     * Include a field originating from a class property.
     */
    operator fun KProperty1<R, *>.unaryPlus() {
        include(this)
    }

    /**
     * Include a field originating from a class function.
     *
     * @param func the function to include as a field.
     * @param name the name of the field, defaults to the name of the function.
     * @param O the type of the field as shown in the schema. By default, it is inferred from the type of [func].
     */
    @SchemaDsl
    fun <O : Any?> include(
        func: KFunction<O>,
        name: String = func.name,
    ) {
        if (name in fields)
            throw Exception("A field with this name is already included")
        fields += FunctionField<R>(func, name, takeDesc(), null, context)
    }

    /**
     * Include a field originating from a class function.
     */
    operator fun KFunction<*>.unaryPlus() {
        include(this)
    }

    fun derive(
        nameFilter: List<String>,
        propFilter: List<KProperty1<R, *>>,
        funFilter: List<KFunction<*>>,
    ) {
        deriveProperties(nameFilter, propFilter)
        deriveFunctions(nameFilter, funFilter)
        // TODO handle the case where a property and a function with the same name exist
    }

    fun deriveProperties(
        nameFilter: List<String>,
        propFilter: List<KProperty1<R, *>>,
    ) {
        kclass.memberProperties.asSequence().filter {
            it.name !in nameFilter
        }.filter {
            it !in propFilter
        }.forEach {
            context.log.debug("[derive] ${name}[${kclass.qualifiedName}] property `${it.name}`: ${it.returnType}")
            fields += PropertyField(it, it.name, null, instance)
        }
    }

    fun deriveFunctions(
        nameFilter: List<String>,
        funFilter: List<KFunction<*>>,
    ) {
        kclass.memberFunctions.asSequence().filter {
            it.name.isValidFunctionForDerive() && it.name !in nameFilter
        }.filter {
            it !in funFilter
        }.forEach {
            context.log.debug("[derive] ${name}[${kclass.qualifiedName}] function `${it.name}`: ${it.returnType}")
            fields += FunctionField(it, it.name, null, instance, context)
        }
    }

    /**
     * Include fields based on properties and functions present in the backing class.
     *
     * @param exclusionsBuilder allows you to configure exclusion rules, defaults to a set of known functions.
     */
    @SchemaDsl
    inline fun derive(exclusionsBuilder: ExclusionFilterBuilder<R>.() -> Unit = {}) {
        val exclusions = ExclusionFilterBuilder<R>().apply(exclusionsBuilder)
        derive(exclusions.nameExclusions, exclusions.propExclusions, exclusions.funExclusions)
    }

    /**
     * Include fields based on properties present in the backing class.
     *
     * @param exclusionsBuilder allows you to configure exclusion rules, defaults to no exclusions.
     */
    @SchemaDsl
    inline fun deriveProperties(exclusionsBuilder: PropertyExclusionFilterBuilder<R>.() -> Unit = {}) {
        val exclusions = PropertyExclusionFilterBuilder<R>().apply(exclusionsBuilder)
        deriveProperties(exclusions.nameExclusions, exclusions.propExclusions)
    }

    /**
     * Include fields based on functions present in the backing class.
     *
     * @param exclusionsBuilder allows you to configure exclusion rules, defaults to a set of known functions.
     */
    @SchemaDsl
    inline fun deriveFunctions(exclusionsBuilder: FunctionExclusionFilterBuilder.() -> Unit = {}) {
        val exclusions = FunctionExclusionFilterBuilder().apply(exclusionsBuilder)
        deriveFunctions(exclusions.nameExclusions, exclusions.funExclusions)
    }

    //We can't call raw Function<*> because of how kotlin-reflect warks atm, so we have to specify each possibility.
    // TODO explore the possibility of making these functions inline to remove the call to resolver.reflect()

    /**
     * Declare a custom field.
     *
     * @param name the name of the field
     * @param fetcher the code executed behind this field
     */
    @SchemaDsl
    fun <O> field(
        name: String,
        fetcher: suspend R.() -> O
    ) {
        val reflected = fetcher.reflect()!!
        fields += CustomField(
            name,
            takeDesc(),
            reflected.returnType.unwrapAsyncType(),
            emptyList(),
            suspendFetcher {
                fetcher(
                    instance ?: it.getSource()
                )
            })
    }

    /**
     * Declare a custom field.
     *
     * @param fetcher the code executed behind this field
     */
    @SchemaDsl
    operator fun <O> String.invoke(fetcher: suspend R.() -> O) {
        field(this, fetcher)
    }

    // TODO Maybe inlining could be better to obtain the type of A
    // We need to reflect the lambda anyway to get param names

    /**
     * Declare a custom field.
     *
     * @param name the name of the field
     * @param fetcher the code executed behind this field
     */
    @Suppress("UNCHECKED_CAST")
    @SchemaDsl
    fun <O, A> field(
        name: String,
        fetcher: suspend R.(A) -> O
    ) {
        val reflected = fetcher.reflect()!!
        val params = reflected.valueParameters
        val arg0 = params[0].createArgument(context)
        fields += CustomField(
            name,
            takeDesc(),
            reflected.returnType.unwrapAsyncType(),
            listOf(arg0),
            suspendFetcher {
                fetcher(
                    instance ?: it.getSource(),
                    arg0.resolve(it) as A
                )
            })
    }

    /**
     * Declare a custom field.
     *
     * @param fetcher the code executed behind this field
     */
    @SchemaDsl
    operator fun <O, A> String.invoke(fetcher: suspend R.(A) -> O) {
        field(this, fetcher)
    }

    /**
     * Declare a custom field.
     *
     * @param name the name of the field
     * @param fetcher the code executed behind this field
     */
    @Suppress("UNCHECKED_CAST")
    @SchemaDsl
    fun <O, A, B> field(
        name: String,
        fetcher: suspend R.(A, B) -> O
    ) {
        val reflected = fetcher.reflect()!!
        val params = reflected.valueParameters
        val arg0 = params[0].createArgument(context)
        val arg1 = params[1].createArgument(context)
        fields += CustomField(
            name,
            takeDesc(),
            reflected.returnType.unwrapAsyncType(),
            listOf(arg0, arg1),
            suspendFetcher {
                fetcher(
                    instance ?: it.getSource(),
                    arg0.resolve(it) as A,
                    arg1.resolve(it) as B
                )
            })
    }

    /**
     * Declare a custom field.
     *
     * @param fetcher the code executed behind this field
     */
    @SchemaDsl
    operator fun <O, A, B> String.invoke(fetcher: suspend R.(A, B) -> O) {
        field(this, fetcher)
    }

    /**
     * Declare a custom field.
     *
     * @param name the name of the field
     * @param fetcher the code executed behind this field
     */
    @Suppress("UNCHECKED_CAST")
    @SchemaDsl
    fun <O, A, B, C> field(
        name: String,
        fetcher: suspend R.(A, B, C) -> O
    ) {
        val reflected = fetcher.reflect()!!
        val params = reflected.valueParameters
        val arg0 = params[0].createArgument(context)
        val arg1 = params[1].createArgument(context)
        val arg2 = params[2].createArgument(context)
        fields += CustomField(
            name,
            takeDesc(),
            reflected.returnType.unwrapAsyncType(),
            listOf(arg0, arg1, arg2),
            suspendFetcher {
                fetcher(
                    instance ?: it.getSource(),
                    arg0.resolve(it) as A,
                    arg1.resolve(it) as B,
                    arg2.resolve(it) as C
                )
            })
    }

    /**
     * Declare a custom field.
     *
     * @param fetcher the code executed behind this field
     */
    @SchemaDsl
    operator fun <O, A, B, C> String.invoke(fetcher: suspend R.(A, B, C) -> O) {
        field(this, fetcher)
    }

    /**
     * Declare a custom field.
     *
     * @param name the name of the field
     * @param fetcher the code executed behind this field
     */
    @Suppress("UNCHECKED_CAST")
    @SchemaDsl
    fun <O, A, B, C, D> field(
        name: String,
        fetcher: suspend R.(A, B, C, D) -> O
    ) {
        val reflected = fetcher.reflect()!!
        val params = reflected.valueParameters
        val arg0 = params[0].createArgument(context)
        val arg1 = params[1].createArgument(context)
        val arg2 = params[2].createArgument(context)
        val arg3 = params[3].createArgument(context)
        fields += CustomField(
            name,
            takeDesc(),
            reflected.returnType.unwrapAsyncType(),
            listOf(arg0, arg1, arg2, arg3),
            suspendFetcher {
                fetcher(
                    instance ?: it.getSource(),
                    arg0.resolve(it) as A,
                    arg1.resolve(it) as B,
                    arg2.resolve(it) as C,
                    arg3.resolve(it) as D
                )
            })
    }

    /**
     * Declare a custom field.
     *
     * @param fetcher the code executed behind this field
     */
    @SchemaDsl
    operator fun <O, A, B, C, D> String.invoke(fetcher: suspend R.(A, B, C, D) -> O) {
        field(this, fetcher)
    }

    /**
     * Declare a custom field.
     *
     * @param name the name of the field
     * @param fetcher the code executed behind this field
     */
    @Suppress("UNCHECKED_CAST")
    @SchemaDsl
    fun <O, A, B, C, D, E> field(
        name: String,
        fetcher: suspend R.(A, B, C, D, E) -> O
    ) {
        val reflected = fetcher.reflect()!!
        val params = reflected.valueParameters
        val arg0 = params[0].createArgument(context)
        val arg1 = params[1].createArgument(context)
        val arg2 = params[2].createArgument(context)
        val arg3 = params[3].createArgument(context)
        val arg4 = params[4].createArgument(context)
        fields += CustomField(
            name,
            takeDesc(),
            reflected.returnType.unwrapAsyncType(),
            listOf(arg0, arg1, arg2, arg3, arg4),
            suspendFetcher {
                fetcher(
                    instance ?: it.getSource(),
                    arg0.resolve(it) as A,
                    arg1.resolve(it) as B,
                    arg2.resolve(it) as C,
                    arg3.resolve(it) as D,
                    arg4.resolve(it) as E
                )
            })
    }

    /**
     * Declare a custom field.
     *
     * @param fetcher the code executed behind this field
     */
    @SchemaDsl
    operator fun <O, A, B, C, D, E> String.invoke(fetcher: suspend R.(A, B, C, D, E) -> O) {
        field(this, fetcher)
    }

    /**
     * Declare a custom field.
     *
     * @param name the name of the field
     * @param fetcher the code executed behind this field
     */
    @Suppress("UNCHECKED_CAST")
    @SchemaDsl
    fun <O, A, B, C, D, E, F> field(
        name: String,
        fetcher: suspend R.(A, B, C, D, E, F) -> O
    ) {
        val reflected = fetcher.reflect()!!
        val params = reflected.valueParameters
        val arg0 = params[0].createArgument(context)
        val arg1 = params[1].createArgument(context)
        val arg2 = params[2].createArgument(context)
        val arg3 = params[3].createArgument(context)
        val arg4 = params[4].createArgument(context)
        val arg5 = params[5].createArgument(context)
        fields += CustomField(
            name,
            takeDesc(),
            reflected.returnType.unwrapAsyncType(),
            listOf(arg0, arg1, arg2, arg3, arg4, arg5),
            suspendFetcher {
                fetcher(
                    instance ?: it.getSource(),
                    arg0.resolve(it) as A,
                    arg1.resolve(it) as B,
                    arg2.resolve(it) as C,
                    arg3.resolve(it) as D,
                    arg4.resolve(it) as E,
                    arg5.resolve(it) as F
                )
            })
    }

    /**
     * Declare a custom field.
     *
     * @param fetcher the code executed behind this field
     */
    @SchemaDsl
    operator fun <O, A, B, C, D, E, F> String.invoke(fetcher: suspend R.(A, B, C, D, E, F) -> O) {
        field(this, fetcher)
    }
}

/**
 * DSL for building a GraphQL interface.
 */
class InterfaceBuilder<R : Any>(
    kclass: KClass<R>,
    name: String,
    description: String?,
    context: SchemaBuilderContext
) : BaseTypeBuilder<R>(kclass, null, name, description, context) {

    init {
        require(kclass.isValidClassForInterface())
    }
}

/**
 * DSL for building a GraphQL Object type.
 */
class TypeBuilder<R : Any>(
    kclass: KClass<R>,
    name: String,
    description: String?,
    context: SchemaBuilderContext
) : BaseTypeBuilder<R>(kclass, null, name, description, context) {

    init {
        require(kclass.isValidClassForType())
    }

    /**
     * Interfaces implemented by this type
     */
    val interfaces = mutableListOf<KClass<*>>()

    /**
     * Declare this type as implementing interface [I].
     *
     * @param I the interface this type should be implementing
     */
    @SchemaDsl
    inline fun <reified I : Any> inter() {
        // TODO check that R : I
        interfaces += I::class
        // TODO include interface fields
    }
}

/**
 * DSL for building a root object.
 */
class OperationBuilder<R : Any>(name: String, instance: R, context: SchemaBuilderContext) :
    BaseTypeBuilder<R>(instance::class as KClass<R>, instance, name, null, context) {

    init {
        require(kclass.isValidClassForType())
    }
}
