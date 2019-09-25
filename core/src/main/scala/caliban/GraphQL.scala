package caliban

import caliban.CalibanError.ExecutionError
import caliban.Rendering.renderTypes
import caliban.execution.Executor
import caliban.introspection.Introspector
import caliban.introspection.adt.{ __Introspection, __Type }
import caliban.parsing.Parser
import caliban.parsing.adt.ExecutableDefinition.FragmentDefinition
import caliban.parsing.adt.{ Selection, Value }
import caliban.schema.RootSchema.Operation
import caliban.schema.{ ResponseValue, RootSchema, RootType, Schema }
import caliban.validation.Validator
import zio.stream.ZStream
import zio.{ IO, Runtime, ZIO }

class GraphQL[Q, M, S](schema: RootSchema[Q, M, S]) {

  private val rootType =
    RootType(
      schema.query.schema.toType(),
      schema.mutation.map(_.schema.toType()),
      schema.subscription.map(_.schema.toType())
    )
  private val introspectionRootSchema: RootSchema[__Introspection, Nothing, Nothing] = Introspector.introspect(rootType)
  private val introspectionRootType                                                  = RootType(introspectionRootSchema.query.schema.toType(), None, None)

  def render: String = renderTypes(rootType.types)

  def execute(query: String, operationName: Option[String] = None): IO[CalibanError, ResponseValue] =
    for {
      document <- Parser.parseQuery(query)
      intro    = Introspector.isIntrospection(document)
      _        <- Validator.validate(document, if (intro) introspectionRootType else rootType)
      result   <- Executor.execute(document, if (intro) introspectionRootSchema else schema, operationName)
    } yield result
}

object GraphQL {

  def graphQL[Q, M, S](
    resolver: RootResolver[Q, M, S]
  )(implicit querySchema: Schema[Q], mutationSchema: Schema[M], subscriptionSchema: Schema[S]): GraphQL[Q, M, S] =
    new GraphQL[Q, M, S](
      RootSchema(
        Operation(querySchema, resolver.queryResolver),
        resolver.mutationResolver.map(Operation(mutationSchema, _)),
        resolver.subscriptionResolver.map(Operation(subscriptionSchema, _))
      )
    )

  implicit def effectSchema[R, E <: Throwable, A](implicit ev: Schema[A], runtime: Runtime[R]): Schema[ZIO[R, E, A]] =
    new Schema[ZIO[R, E, A]] {
      override def toType(isInput: Boolean = false): __Type = ev.toType(isInput)
      override def exec(
        value: ZIO[R, E, A],
        selectionSet: List[Selection],
        arguments: Map[String, Value],
        fragments: Map[String, FragmentDefinition],
        parallel: Boolean
      ): IO[ExecutionError, ResponseValue] =
        value.flatMap(ev.exec(_, selectionSet, arguments, fragments, parallel)).provide(runtime.Environment).mapError {
          case e: ExecutionError => e
          case other             => ExecutionError("Caught error during execution of effectful field", Some(other))
        }
    }

  implicit def streamSchema[R, E <: Throwable, A](
    implicit ev: Schema[A],
    runtime: Runtime[R]
  ): Schema[ZStream[R, E, A]] =
    new Schema[ZStream[R, E, A]] {
      override def toType(isInput: Boolean = false): __Type = ev.toType(isInput)
      override def exec(
        stream: ZStream[R, E, A],
        selectionSet: List[Selection],
        arguments: Map[String, Value],
        fragments: Map[String, FragmentDefinition],
        parallel: Boolean
      ): IO[ExecutionError, ResponseValue] =
        IO.succeed(
          ResponseValue.StreamValue(
            stream.mapM(ev.exec(_, selectionSet, arguments, fragments, parallel)).provide(runtime.Environment)
          )
        )
    }

}