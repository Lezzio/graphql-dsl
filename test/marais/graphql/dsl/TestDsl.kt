package marais.graphql.dsl

import graphql.GraphQL
import graphql.schema.DataFetchingEnvironment
import graphql.schema.idl.SchemaPrinter
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TestDsl {
    private val log = LoggerFactory.getLogger(TestDsl::class.java)

    @ExperimentalStdlibApi
    @Test
    fun testDsl() {
        val startTime = System.currentTimeMillis()
        val builder = SchemaBuilder {

            scalar("Url", UrlCoercing)
            id<MyId>()

            enum<MyEnum>()

            input<Input>()

            !"This describes my Node interface"
            inter<Node> {
                derive()

                !"Description on a field too !"
                field("parent") { ->
                    MyId("Node:" + id.inner)
                }
            }

            !"""
                This is a cool looking multiline description
                No need to call .trimIndent()
            """
            type<MyData> {
                // TODO specifying interface on a type should automatically declare appropriate fields
                inter<Node>()

                // Can be a property
                include(MyData::id)
                // Can be a member function
                include(MyData::dec)
                assertFailsWith<Exception>("We already included that field with the same name") {
                    include(MyData::dec)
                }
                include(MyData::field)
                // Can be a custom function
                field("inc") { _: DataFetchingEnvironment ->
                    field + 1
                }
            }

            type<OtherData> {
                inter<Node>()

                derive()

                -OtherData::field
                assertFailsWith<Exception>("We already removed this field") {
                    exclude(OtherData::field)
                }

                field("custom") { param: String ->
                    param
                }

                field("custom2") { a: List<Int>, b: Int ->
                    a.map { it * b }
                }

                field("custom3") { a: Int, b: Float, c: MyId ->
                    "$c: ${a * b}"
                }
            }

            query(Query) { derive() }
        }
        val initTime = System.currentTimeMillis() - startTime
        val schema = builder.build()
        val buildTime = System.currentTimeMillis() - startTime
        log.debug("Schema build time : $buildTime ms (init : $initTime ms)")

        log.debug(SchemaPrinter(SchemaPrinter.Options.defaultOptions()).print(schema))

        val graphql = GraphQL.newGraphQL(schema).build()
        val result = graphql.execute(
            """
            query {
              data {
                id,
                field,
                dec,
                inc,
                parent
              },
              otherdata {
                id,
                custom(param: "hello"),
                custom2(a: [1, 2, 3], b: 4)
              },
              node {
                id,
                parent,
                ... on MyData { value: field },
                ... on OtherData { custom(param: "hello") }
              },
              testSuspend,
              testDeferred,
              testFuture
            }
        """.trimIndent()
        )
        log.debug(result.getData<Map<String, Any?>?>()?.toString())
        assertTrue(result.errors.isEmpty(), "${result.errors}")
        log.debug(result.extensions?.toString())
    }
}