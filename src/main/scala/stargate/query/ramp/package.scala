package stargate.query

import java.util.concurrent._
import java.util.concurrent.atomic.AtomicLong
import java.util.{Comparator, UUID}

import com.datastax.oss.driver.api.core.servererrors.QueryConsistencyException
import com.datastax.oss.driver.api.core.{CqlSession, DefaultConsistencyLevel}
import stargate.cassandra.CassandraTable
import stargate.model.queries._
import stargate.model.{OutputModel, ScalarCondition}
import stargate.query.ramp.read.{MaybeRead, MaybeReadRows}
import stargate.query.ramp.write.WriteOp
import stargate.schema.GroupedConditions
import stargate.{cassandra, keywords, query, schema, util}

import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._
import scala.util.Random

package object ramp {

  type TransactionId = java.util.List[java.lang.Long]
  object TransactionIdOrdering extends Ordering[TransactionId] {
    override def compare(x: TransactionId, y: TransactionId): Int = Ordering[Iterable[java.lang.Long]].compare(x.asScala, y.asScala)
  }
  object TransactionState extends Enumeration {
    class Value(val name: String, val asInt: Int) extends super.Val(name)
    val IN_PROGRESS = new Value("IN_PROGRESS", 1)
    val SUCCESS = new Value("SUCCESS", 0)
    val FAILED = new Value("FAILED", 2)

    val ints: Map[Int, Value] = this.values.iterator.map(state => {
      val stateValue = state.asInstanceOf[Value]
      (stateValue.asInt, stateValue)
    }).toMap
    def toInt(state: Value): Int = state.asInt
    def fromInt(int: Int): Value = ints(int)
  }

  type GetTransactionState = TransactionId => Future[TransactionState.Value]
  type SetTransactionState = (TransactionId, TransactionState.Value) => Future[Unit]
  case class Context(model: OutputModel, getState: GetTransactionState, setState: SetTransactionState, session: CqlSession, executor: ExecutionContext, scheduler: ScheduledExecutorService) {
    val queryContext = query.Context(model, session, executor)
    type QueueEntry = (Long, Long, CassandraTable, Map[String,Object])
    val comparator: Comparator[QueueEntry] = (a: QueueEntry, b: QueueEntry) => Ordering[(Long,Long)].compare((a._1, a._2), (b._1, b._2))
    val deletionQueue: PriorityBlockingQueue[QueueEntry] = new PriorityBlockingQueue(11, comparator)
    val deletionDelayMs: Long = 10000
    val deleteCounter: AtomicLong = new AtomicLong(0)
    val conflictBackoff = Duration.apply("3ms")
    val conflictRetries = 100
  }
  def createContext(model: OutputModel, session: CqlSession, executor: ExecutionContext): Context = {
    val stateMap = new ConcurrentHashMap[TransactionId, TransactionState.Value]()
    def getState(id: TransactionId) = Future.successful(stateMap.get(id))
    def setState(id: TransactionId, state: TransactionState.Value) = Future.successful({ stateMap.put(id, state); () })
    val scheduler = Executors.newSingleThreadScheduledExecutor()
    Context(model, getState, setState, session, executor, scheduler)
  }

  type MutationResult = Future[Option[(List[Map[String,Object]], List[ramp.write.WriteOp])]]




  def addResponseMetadata(result: MutationResult, metadata: Map[String,Object], executor: ExecutionContext): MutationResult = {
    result.map(_.map(entities_statements => {
      (entities_statements._1.map(entity => entity ++ metadata), entities_statements._2)
    }))(executor)
  }
  def addResponseMetadata(result: MutationResult, key: String, value: String, executor: ExecutionContext): MutationResult = {
    addResponseMetadata(result, Map((key, value)), executor)
  }

  def matchEntities(context: Context, transactionId: TransactionId, entityName: String, conditions: List[ScalarCondition[Object]]): MaybeReadRows = {
    val executor = context.executor
    val potentialIds = query.matchEntities(context.queryContext, entityName, conditions).dedupe(executor)
    val potentialEntities = potentialIds.map(id => ramp.read.entityIdToLastValidState(context, transactionId, entityName, id), executor)
    ramp.read.flatten(potentialEntities, executor).map(_.map(_.filter(query.read.checkConditions(_, conditions))))(executor)
  }
  def matchEntities(context: Context, transactionId: TransactionId, entityName: String, conditions: GroupedConditions[Object]): MaybeRead[UUID] = {
    val groupedEntities = conditions.toList.map(path_conds => {
      val (path, conditions) = path_conds
      val targetEntityName = schema.traverseEntityPath(context.model.input.entities, entityName, path)
      (path, matchEntities(context, transactionId, targetEntityName, conditions).map(_.map(_.map(_(schema.ENTITY_ID_COLUMN_NAME).asInstanceOf[UUID])))(context.executor))
    }).toMap
    val rootIds = util.sequence(groupedEntities.toList.map(path_ids => resolveReverseRelations(context, transactionId, entityName, path_ids._1, path_ids._2)), context.executor)
    rootIds.map(rootIds => util.allDefined(rootIds).map(idLists => idLists.map(_.toSet).reduce(_.intersect(_)).toList))(context.executor)
  }



  def resolveRelations(context: Context, transactionId: TransactionId, entityName: String, relationPath: List[String], ids: MaybeRead[UUID]): MaybeRead[UUID] = {
    if(relationPath.isEmpty) {
      ids
    } else {
      val relationName = relationPath.head
      val next = stargate.util.flattenFOFO(ids.map(_.map(ids => ramp.read.resolveRelationIds(context, transactionId, entityName, ids, relationName)))(context.executor), context.executor)
      resolveRelations(context, transactionId, context.model.input.entities(entityName).relations(relationName).targetEntityName, relationPath.tail, next)
    }
  }
  def resolveRelations(context: Context, transactionId: TransactionId, entityName: String, relationPath: List[String], ids: List[UUID]): MaybeRead[UUID] = {
    resolveRelations(context, transactionId, entityName, relationPath, Future.successful(Some(ids)))
  }
  def resolveReverseRelations(context: Context, transactionId: TransactionId, rootEntityName: String, relationPath: List[String], relatedIds: MaybeRead[UUID]): MaybeRead[UUID] = {
    if(relationPath.isEmpty) {
      relatedIds
    } else {
      val relations = schema.traverseRelationPath(context.model.input.entities, rootEntityName, relationPath).reverse
      val newRootEntityName = relations.head.targetEntityName
      val inversePath = relations.map(_.inverseName)
      resolveRelations(context, transactionId, newRootEntityName, inversePath, relatedIds)
    }
  }
  def resolveReverseRelations(context: Context, transactionId: TransactionId, entityName: String, relationPath: List[String], ids: List[UUID]): MaybeRead[UUID] = {
    resolveReverseRelations(context, transactionId, entityName, relationPath, Future.successful(Some(ids)))
  }


  def getEntitiesAndRelated(context: Context, transactionId: TransactionId, entityName: String, ids: MaybeRead[UUID], payload: GetSelection): MaybeReadRows = {
    val executor = context.executor
    val relations = context.model.input.entities(entityName).relations
    val results = ids.map(_.map(_.map(id => {
      val futureMaybeEntity = ramp.read.entityIdToLastValidState(context, transactionId, entityName, id)
      val entityAndRelations = futureMaybeEntity.map(_.map(_.map(entity => {
        val related = payload.relations.toList.map(name_selection => {
          val (relationName, nestedSelection) = name_selection
          val childIds = resolveRelations(context, transactionId, entityName, List(relationName), List(id))
          val recurse = getEntitiesAndRelated(context, transactionId, relations(relationName).targetEntityName, childIds, nestedSelection)
          recurse.map(_.map(result => (relationName, result)))(executor)
        })
        val sequencedRelated = util.sequence(related, executor).map(relations => util.allDefined(relations))(executor)
        sequencedRelated.map(_.map(relation_children => entity ++ relation_children))(executor)
      })))(executor)
      util.flattenFOLFO(entityAndRelations, executor)
    })))(executor)
    util.flattenFOLFOL(results, executor)
  }
  def get(context: Context, transactionId: TransactionId, entityName: String, payload: GetQuery): MaybeReadRows = {
    val ids = matchEntities(context, transactionId, entityName, payload.`match`)
    getEntitiesAndRelated(context, transactionId, entityName, ids, payload.selection)
  }



  def relationLink(model: OutputModel, transactionId: TransactionId, entityName: String, parentId: UUID, relationName: String, payload: List[Map[String,Object]]): List[WriteOp] = {
    payload.flatMap(entity => ramp.write.createBidirectionalRelation(model, transactionId, entityName, relationName, parentId, entity(schema.ENTITY_ID_COLUMN_NAME).asInstanceOf[UUID]))
  }
  def relationUnlink(model: OutputModel, transactionId: TransactionId, entityName: String, parentId: UUID, relationName: String, payload: List[Map[String,Object]]): List[WriteOp] = {
    def ids(entity: Map[String,Object]): Map[String,Object] = Map((schema.RELATION_FROM_COLUMN_NAME, parentId), (schema.RELATION_TO_COLUMN_NAME, entity(schema.ENTITY_ID_COLUMN_NAME)), (schema.TRANSACTION_ID_COLUMN_NAME, entity(schema.TRANSACTION_ID_COLUMN_NAME)))
    payload.flatMap(entity => ramp.write.deleteBidirectionalRelation(model, transactionId, entityName, relationName, ids(entity)))
  }
  // payload is a list of entities wrapped in link or unlink.  perform whichever link operation is specified between parent ids and child ids
  def relationChange(model: OutputModel, transactionId: TransactionId, entityName: String, parentId: UUID, relationName: String, payload: List[Map[String,Object]]): List[WriteOp] = {
    val byOperation = payload.groupBy(_(keywords.response.RELATION))
    val linked = byOperation.get(keywords.response.RELATION_LINK).map(relationLink(model, transactionId, entityName, parentId, relationName, _)).getOrElse(List.empty)
    val unlinked = byOperation.get(keywords.response.RELATION_UNLINK).map(relationUnlink(model, transactionId, entityName, parentId, relationName, _)).getOrElse(List.empty)
    linked ++ unlinked
  }

  // perform nested mutation, then take result (child entities wrapped in either link/unlink/replace) and update relations to parent ids
  def mutateAndLinkRelations(context: Context, transactionId: TransactionId, entityName: String, entityId: UUID, payloadMap: Map[String,RelationMutation]): MutationResult = {
    implicit val executor: ExecutionContext = context.executor
    val entity = context.model.input.entities(entityName)
    val relationMutationResults = payloadMap.toList.map(name_mutation => {
      val (relationName, childMutation) = name_mutation
      val mutationResult = relationMutation(context, transactionId, entityName, entityId, relationName, entity.relations(relationName).targetEntityName, childMutation)
      mutationResult.map(_.map((relationName, _)))
    })
    val sequencedMutationResults = util.sequence(relationMutationResults, executor).map(util.allDefined)(executor)
    val relationLinkResults = sequencedMutationResults.map(_.map(relations => {
      val entity: Map[String,Object] = query.write.entityIdPayload(entityId) ++ relations.map(kv => (kv._1, kv._2._1)).toMap
      val changes = relations.map(name_result => {
        relationChange(context.model, transactionId, entityName, entityId, name_result._1, name_result._2._1)
      })
      (List(entity), relations.flatMap(_._2._2) ++ changes.flatten)
    }))
    relationLinkResults
  }

  def createOne(context: Context, transactionId: TransactionId, entityName: String, payload: CreateOneMutation): MutationResult = {
    val (uuid, creates) = ramp.write.createEntity(context.model.entityTables(entityName), payload.fields.updated(schema.TRANSACTION_ID_COLUMN_NAME, transactionId))
    val linkWrapped = payload.relations.map((rm: (String,Mutation)) => (rm._1, LinkMutation(rm._2)))
    val linkResults = mutateAndLinkRelations(context, transactionId, entityName, uuid, linkWrapped)
    val createResult = linkResults.map(_.map(linkResult => (linkResult._1, creates ++ linkResult._2)))(context.executor)
    addResponseMetadata(createResult, keywords.response.ACTION, keywords.response.ACTION_CREATE, context.executor)
  }

  def create(context: Context, transactionId: TransactionId, entityName: String, payload: CreateMutation): MutationResult = {
    val creates = payload.creates.map(createOne(context, transactionId, entityName, _))
    util.sequence(creates, context.executor).map(lists => util.allDefined(lists).map(data_ops => (data_ops.flatMap(_._1), data_ops.flatMap(_._2))))(context.executor)
  }

  def matchMutation(context: Context, transactionId: TransactionId, entityName: String, payload: MatchMutation): MutationResult = {
    val ids = matchEntities(context, transactionId, entityName, payload.`match`)
    ids.map(_.map(ids => (ids.map(id => query.write.entityIdPayload(id).updated(keywords.response.ACTION, keywords.response.ACTION_UPDATE)), List.empty)))(context.executor)
  }

  def update(context: Context, transactionId: TransactionId, entityName: String, ids: MaybeRead[UUID], payload: UpdateMutation): MutationResult = {
    val executor = context.executor
    val updateAll = ids.map(_.map(_.map( id => {
      val state = ramp.read.entityIdToLastValidState(context, transactionId, entityName, id)
      val updateOne = state.map(_.map(_.map(currentEntity => {
        val updates = ramp.write.updateEntity(context.model.entityTables(entityName), currentEntity, payload.fields.updated(schema.TRANSACTION_ID_COLUMN_NAME, transactionId))
        val linkResults = mutateAndLinkRelations(context, transactionId, entityName, id, payload.relations)
        linkResults.map(_.map(linkResult => (linkResult._1, updates ++ linkResult._2)))(executor)
      })))(executor)
      util.flattenFOLFO(updateOne, executor)
    })))(executor)
    val result = util.flattenFOLFO(updateAll, executor)
    result.map(_.map(lists => {
      val flat = lists.flatten
      (flat.flatMap(_._1), flat.flatMap(_._2))
    }))(executor)
  }
  def update(context: Context, transactionId: TransactionId, entityName: String, payload: UpdateMutation): MutationResult = {
    val ids = matchEntities(context, transactionId, entityName, payload.`match`)
    val result = update(context, transactionId, entityName, ids, payload)
    addResponseMetadata(result, keywords.response.ACTION, keywords.response.ACTION_UPDATE, context.executor)
  }

  def delete(context: Context, transactionId: TransactionId, entityName: String, ids: MaybeRead[UUID], payload: DeleteSelection): MutationResult = {
    implicit val executor: ExecutionContext = context.executor
    val relations = context.model.input.entities(entityName).relations
    val result = ids.map(_.map(ids => ids.map(id => {
      val state = ramp.read.entityIdToLastValidState(context, transactionId, entityName, id)
      val deleteResult = state.map(_.map(_.map(currentEntity => {
        val deleteCurrent = ramp.write.deleteEntity(context.model.entityTables(entityName), transactionId, currentEntity)
        val childResults = relations.toList.map(name_relation => {
          val (relationName, relation) = name_relation
          val childRows = ramp.read.resolveRelation(context, transactionId, entityName, List(id), relationName)
          // TODO: dont double delete inverse relations
          val unlinks = childRows.map(_.map(rows => rows.flatMap(row => ramp.write.deleteBidirectionalRelation(context.model, transactionId, entityName, relationName, row))))
          val recurse = if(payload.relations.contains(relationName)) {
            val childIds = childRows.map(_.map(rows => rows.map(_(schema.RELATION_TO_COLUMN_NAME).asInstanceOf[UUID])))
            delete(context, transactionId, relation.targetEntityName, childIds, payload.relations(relationName))
          } else {
            Future.successful(Some(List.empty, List.empty))
          }
          unlinks.map(_.map(unlinks => recurse.map(_.map(recurse => (relationName, recurse._1, unlinks ++ recurse._2)))))
        })
        util.flattenFOLFO(util.sequence(childResults, executor).map(util.allDefined), executor).map(_.map(relationsOps => {
          val entity = Map((schema.ENTITY_ID_COLUMN_NAME, currentEntity(schema.ENTITY_ID_COLUMN_NAME))) ++ relationsOps.map(r => (r._1, r._2)).toMap
          (entity, deleteCurrent ++ relationsOps.flatMap(_._3))
        }))
      })))
      util.flattenFOLFO(deleteResult, executor)
    })))
    util.flattenFOLFO(result, executor).map(_.map(lists => (lists.flatMap(_.map(_._1)), lists.flatMap(_.flatMap(_._2)))))
  }

  def delete(context: Context, transactionId: TransactionId, entityName: String, payload: DeleteQuery): MutationResult = {
    val ids = matchEntities(context, transactionId, entityName, payload.`match`)
    val result = delete(context, transactionId, entityName, ids, payload.selection)
    addResponseMetadata(result, keywords.response.ACTION, keywords.response.ACTION_DELETE, context.executor)
  }



  def mutation(context: Context, transactionId: TransactionId, entityName: String, payload: Mutation): MutationResult = {
    payload match {
      case createReq: CreateMutation => create(context, transactionId, entityName, createReq)
      case `match`: MatchMutation => matchMutation(context, transactionId, entityName, `match`)
      case updateReq: UpdateMutation => update(context, transactionId, entityName, updateReq)
    }
  }

  def unlinkObject(relationRow: Map[String,Object]): Map[String,Object] = {
    Map[String,Object]((schema.ENTITY_ID_COLUMN_NAME, relationRow(schema.RELATION_TO_COLUMN_NAME)),
      (schema.TRANSACTION_ID_COLUMN_NAME, relationRow(schema.TRANSACTION_ID_COLUMN_NAME)),
      (keywords.response.ACTION, keywords.response.ACTION_UPDATE),
      (keywords.response.RELATION, keywords.response.RELATION_UNLINK))
  }
  def linkMutation(context: Context, transactionId: TransactionId, entityName: String, payload: Mutation): MutationResult = {
    val result = mutation(context, transactionId, entityName, payload)
    addResponseMetadata(result, keywords.response.RELATION, keywords.response.RELATION_LINK, context.executor)
  }
  def unlinkMutation(context: Context, transactionId: TransactionId, parentEntityName: String, parentId: UUID, parentRelation: String, entityName: String, `match`: GroupedConditions[Object]): MutationResult = {
    val matchIds = matchEntities(context, transactionId, entityName, `match`)
    val relations = ramp.read.resolveRelation(context, transactionId, parentEntityName, List(parentId), parentRelation)
    val result = relations.map(_.map(relations => {
      matchIds.map(_.map(matchIds => {
        val matchIdSet = matchIds.toSet
        val matchingRelations = relations.filter(r => matchIdSet(r(schema.RELATION_TO_COLUMN_NAME).asInstanceOf[UUID]))
        (matchingRelations.map(unlinkObject), List.empty)
      }))(context.executor)
    }))(context.executor)
    util.flattenFOFO(result, context.executor)
  }
  def replaceMutation(context: Context, transactionId: TransactionId, parentEntityName: String, parentId: UUID, parentRelation: String, entityName: String, payload: Mutation): MutationResult = {
    val linkMutationResult = mutation(context, transactionId, entityName, payload)
    val result = linkMutationResult.map(_.map(linked_statements => {
      val (mutationObjects, mutationStatements) = linked_statements
      val linkIds = mutationObjects.map(_(stargate.schema.ENTITY_ID_COLUMN_NAME).asInstanceOf[UUID]).toSet
      val relations = ramp.read.resolveRelation(context, transactionId, parentEntityName, List(parentId), parentRelation)
      val unlinkRelations = relations.map(_.map(_.filter(r => linkIds(r(schema.RELATION_TO_COLUMN_NAME).asInstanceOf[UUID]))))(context.executor)
      unlinkRelations.map(_.map(unlinkRelations => {
        val linkObjects = mutationObjects.map(_.updated(keywords.response.RELATION, keywords.response.RELATION_LINK))
        val unlinkObjects = unlinkRelations.map(unlinkObject)
        (linkObjects ++ unlinkObjects, mutationStatements)
      }))(context.executor)
    }))(context.executor)
    util.flattenFOFO(result, context.executor)
  }
  def relationMutation(context: Context, transactionId: TransactionId, parentEntityName: String, parentId: UUID, parentRelation: String, entityName: String, payload: RelationMutation): MutationResult = {
    payload match {
      case link: LinkMutation => linkMutation(context, transactionId, entityName, link.mutation)
      case unlink: UnlinkMutation => unlinkMutation(context, transactionId, parentEntityName, parentId, parentRelation, entityName, unlink.`match`)
      case replace: ReplaceMutation => replaceMutation(context, transactionId, parentEntityName, parentId, parentRelation, entityName, replace.mutation)
    }
  }


  def queueDeletion(context: Context, table: CassandraTable, row: Map[String, Object], delayMs: Long): Unit = {
    context.deletionQueue.add((System.currentTimeMillis(), context.deleteCounter.getAndIncrement(), table, row))
    def task(): Unit = {
      val next = context.deletionQueue.poll()
      val result = if(next != null) {
        cassandra.executeAsync(context.session, query.write.deleteEntityStatement(table, row).build.setConsistencyLevel(DefaultConsistencyLevel.ALL), context.executor)
      } else {
        Future.successful(())
      }
      result.onComplete(result => {
        if(result.isFailure) {
          logger.warn("deletion failed", result.failed.get)
          result.failed.get match {
            case _: QueryConsistencyException => {
              context.deletionQueue.add(next)
              context.scheduler.schedule((() => task()): Callable[Unit], context.deletionDelayMs, TimeUnit.MILLISECONDS)
            }
            case _ => ()
          }
        } else { /* TODO: remove from in-flight deletes on success if deleting in parallel */ }
      })(context.executor)
    }
    context.scheduler.schedule((() => task()):Callable[Unit], delayMs, TimeUnit.MILLISECONDS)
  }
  def executeMutation(context: Context, mutation: MutationResult): MaybeReadRows = {
    val executor = context.executor
    val result = mutation.map(_.map(entities_ops => {
      val (entities, writes) = entities_ops
      val writeResults = util.sequence(writes.map(write => ramp.write.execute(context, write)), executor)
      writeResults.map(writeResults => {
        if(writeResults.forall(_.success)) {
          writeResults.flatMap(_.cleanup).foreach(delete => queueDeletion(context, delete._1, delete._2, context.deletionDelayMs))
          Some(entities)
        } else {
          writeResults.flatMap(_.writes).foreach(delete => queueDeletion(context, delete._1, delete._2, 0))
          None
        }
      })(executor)
    }))(executor)
    util.flattenFOFO(result, executor)
  }


  def newTransactionId: TransactionId = {
    java.util.Arrays.asList(System.currentTimeMillis(), Random.nextLong)
  }
  def mutationTransaction[R](context: Context, mutate: (TransactionId) => MutationResult, retries: Int): MaybeReadRows = {
    val executor: ExecutionContext = context.executor
    val transactionId = newTransactionId
    val inProgress = context.setState(transactionId, TransactionState.IN_PROGRESS)
    val mutationResult = inProgress.flatMap(_ => mutate(transactionId))(executor)
    val result = executeMutation(context, mutationResult)
    result.flatMap(result => {
      if(result.isDefined) {
        context.setState(transactionId, TransactionState.SUCCESS).map(_ => result)(executor)
      } else {
        context.setState(transactionId, TransactionState.FAILED).flatMap(_ => {
          if(retries > 0) {
            mutationTransaction(context, mutate, retries - 1)
          } else {
            throw new RuntimeException("transaction failed due to conflicts")
          }
        })(executor)
      }
    })(executor)
  }
  def get(context: Context, entityName: String, payload: GetQuery): MaybeReadRows = {
    def getWrap(transactionId: TransactionId): MutationResult = get(context, transactionId, entityName, payload).map(_.map(res => (res, List.empty)))(context.executor)
    mutationTransaction(context, getWrap, context.conflictRetries)
  }
  def mutation(context: Context, entityName: String, payload: Mutation): MaybeReadRows = {
    mutationTransaction(context, tid => mutation(context, tid, entityName, payload), context.conflictRetries)
  }
  def delete(context: Context, entityName: String, payload: DeleteQuery): MaybeReadRows = {
    mutationTransaction(context, tid => delete(context, tid, entityName, payload), context.conflictRetries)
  }
}
