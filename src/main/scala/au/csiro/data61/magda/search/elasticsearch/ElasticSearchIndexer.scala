package au.csiro.data61.magda.search.elasticsearch

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success

import org.elasticsearch.action.admin.cluster.repositories.put.PutRepositoryResponse
import org.elasticsearch.action.admin.cluster.snapshots.create.CreateSnapshotResponse
import org.elasticsearch.action.admin.cluster.snapshots.get.GetSnapshotsResponse
import org.elasticsearch.index.IndexNotFoundException
import org.elasticsearch.repositories.RepositoryMissingException
import org.elasticsearch.rest.RestStatus
import org.elasticsearch.snapshots.SnapshotInfo
import org.elasticsearch.transport.RemoteTransportException

import com.sksamuel.elastic4s.BulkDefinition
import com.sksamuel.elastic4s.BulkResult
import com.sksamuel.elastic4s.ElasticClient
import com.sksamuel.elastic4s.ElasticDsl
import com.sksamuel.elastic4s.ElasticDsl._

import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.OverflowStrategy
import akka.stream.QueueOfferResult
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.SourceQueue
import akka.stream.scaladsl.SourceQueueWithComplete
import au.csiro.data61.magda.AppConfig
import au.csiro.data61.magda.external.InterfaceConfig
import au.csiro.data61.magda.model.misc._
import au.csiro.data61.magda.model.misc.Protocols._
import au.csiro.data61.magda.search.SearchIndexer
import au.csiro.data61.magda.search.elasticsearch.ClientProvider.getClient
import au.csiro.data61.magda.search.elasticsearch.ElasticSearchImplicits._
import au.csiro.data61.magda.util.FutureRetry.retry
import spray.json._
import com.sksamuel.elastic4s.BulkItemResult

class ElasticSearchIndexer(implicit val system: ActorSystem, implicit val ec: ExecutionContext, implicit val materializer: Materializer) extends SearchIndexer {
  val logger = system.log

  /**
   * Returns an initialised {@link ElasticClient} on completion. Using this to get the client rather than just keeping a reference to an initialised client
   *  ensures that all queries will only complete after the client is initialised.
   */
  private val setupFuture = setup()

  implicit val scheduler = system.scheduler

  // This needs to be a queue here because if we queue more than 50 requests into ElasticSearch it gets very very mad.
  private lazy val indexQueue: SourceQueue[(InterfaceConfig, Seq[DataSet], Promise[BulkResult])] =
    Source.queue[(InterfaceConfig, Seq[DataSet], Promise[BulkResult])](Int.MaxValue, OverflowStrategy.backpressure)
      .mapAsync(1) {
        case (source, dataSets, promise) =>
          bulkIndex(buildDatasetIndexDefinition(source, dataSets))
            .map((source, dataSets, promise, _))
      }
      .map {
        case (source, dataSets, promise, result) =>
          if (result.hasFailures) {
            logger.warning("Failure when indexing from {}: {}", source.name, result.failureMessage)

            reindexSpatialFails(source, dataSets, result.failures)
          } else {
            logger.info("Indexed {} datasets from {}", dataSets.length, source.name)
          }

          promise.success(result)
      }
      .recover {
        case e: Throwable =>
          logger.error(e, "Error when indexing: {}", e.getMessage)
      }
      .to(Sink.last)
      .run()

  private lazy val restoreQueue: SourceQueue[(ElasticClient, IndexDefinition, SnapshotInfo, Promise[RestoreResult])] =
    Source.queue[(ElasticClient, IndexDefinition, SnapshotInfo, Promise[RestoreResult])](Int.MaxValue, OverflowStrategy.backpressure)
      .mapAsync(1) {
        case (client, definition, snapshot, promise) =>
          logger.info("Restoring snapshot {} for {} version {}", snapshot.name, definition.name, definition.version)

          logger.info("First deleting existing index if present...")

          deleteIndex(client, definition).map(_ => (client, definition, snapshot, promise))
      }
      .mapAsync(1) {
        case (client, definition, snapshot, promise) =>
          logger.info("Restoring snapshot")

          client.execute {
            restore snapshot snapshot.name from snapshotRepoName(definition) indexes definition.name waitForCompletion true
          } map { response =>
            response.status match {
              case RestStatus.OK =>
                logger.info("Restored {} version {}", definition.name, definition.version)
                promise.success(RestoreSuccess)
              case status: RestStatus =>
                logger.info("Failed to restore for {} version {} with status {}", definition.name, definition.version, status)
                promise.success(RestoreFailure)
            }
          }
      }
      .recover {
        case e: Throwable =>
          logger.error(e, "Error when restoring: {}", e.getMessage)
      }
      .to(Sink.last)
      .run()

  /** Initialises an {@link ElasticClient}, handling initial connection to the ElasticSearch server and creation of the indices */
  private def setup(): Future[ElasticClient] = {
    getClient(system.scheduler, logger, ec).flatMap(client =>
      retry(() => getIndexVersions(client), 10 seconds, 10, logger.warning("Failed to get index versions, {} retries left", _))
        .flatMap { versionPairs =>
          updateIndices(client, versionPairs)
            .map { _ =>
              // If we've got to here everything has gone swimmingly - the index is all ready to have data loaded, so return the client for other methods to play with :)
              client
            }
        }
    )
  }

  private def reindexSpatialFails(source: InterfaceConfig, dataSets: Seq[DataSet], failures: Seq[BulkItemResult]) = {
    val dataSetLookup = dataSets.groupBy(_.identifier).mapValues(_.head)
    val geoFails = failures
      .filter(_.failureMessage.contains("failed to parse [spatial.geoJson]"))
      .map(result => dataSetLookup.get(result.id.split("%2F")(1)))
      .flatten
      .map(dataSet => dataSet.copy(spatial = dataSet.spatial.map(spatial => spatial.copy(geoJson = None))))

    if (geoFails.length > 0) {
      logger.info("Determined that {} datasets were excluded due to bad geojson - trying these again with spatial.geoJson excluded", geoFails.length)
      index(source, geoFails.toList)
    }
  }

  /**
   * Returns a future that gets a seq of each index paired with its current version number in ES
   */
  private def getIndexVersions(client: ElasticClient) = Future.sequence(
    IndexDefinition.indices.map(indexDef =>
      client.execute(get id "indexversion" from indexDef.name / "config")
        .map(x => if (x.isSourceEmpty || !x.isExists) 0 else x.source.get("version").asInstanceOf[Int])
        .recover {
          // If the index wasn't found that's fine, we'll just recreate it. Otherwise log an error - every subsequent request to the provider will fail with this exception.
          case outer: RemoteTransportException => outer.getCause match {
            case (inner: IndexNotFoundException) =>
              logger.warning("{} index was not present, if this is the first boot with a new index version this is fine: {}", indexDef.name, outer.getMessage)
              0
          }
        }))
    .map(versions => versions.zip(IndexDefinition.indices))

  /**
   * Compares the passed index versions with those of the codebase - if there's a mismatch then it deletes the index from ES and rebuilds it.
   */
  private def updateIndices(client: ElasticClient, versionPairs: Seq[(Int, IndexDefinition)]): Future[Object] =
    Future.sequence(versionPairs.map {
      case (indexVersion, definition) =>
        logger.info("{} index version is {}", definition.name, indexVersion)

        // If the index version on ES is lower than the code's version, wipe it all and start again.
        if (indexVersion != definition.version)
          rebuildIndex(client, definition)
        else
          Future.successful(Unit)
    })

  private def rebuildIndex(client: ElasticClient, definition: IndexDefinition): Future[Any] = {
    val snapshotFuture = if (AppConfig.conf.getBoolean("indexer.readSnapshots"))
      restoreLatestSnapshot(client, definition)
    else {
      logger.info("Snapshot restoration disabled, rebuilding index manually")
      Future(RestoreFailure)
    }

    snapshotFuture flatMap {
      case RestoreSuccess => Future.successful(Unit) // no need to reindex 
      case RestoreFailure =>
        deleteIndex(client, definition) flatMap { _ =>
          Future.sequence(Seq(
            client.execute(definition.definition)
          ))
        } recover {
          case e: Throwable =>
            logger.error(e, "Failed to set up the index")
            throw e
        } flatMap { _ =>
          logger.info("Index {} version {} created", definition.name, definition.version)

          def recordVersion() = {
            // Now we've created the index, record the version of it so we can look at it next time we boot up.
            logger.info("Recording index version")

            client.execute {
              ElasticDsl.index into definition.name / "config" id "indexversion" source Map("version" -> definition.version).toJson
            }
          }

          definition.create match {
            case Some(createFunc) => createFunc(client, materializer, system)
              .flatMap(_ => recordVersion)
              .flatMap(_ =>
                if (AppConfig.conf.getBoolean("indexer.makeSnapshots"))
                  createSnapshot(client, definition)
                else {
                  logger.info("Snapshotting disabled, skipping")
                  Future(Unit)
                }
              )
            case None => recordVersion()
          }
        }
    }
  }

  def deleteIndex(client: ElasticClient, definition: IndexDefinition): Future[Unit] = client.execute {
    delete index definition.name
  } recover {
    case outer: RemoteTransportException => outer.getCause match {
      case (inner: IndexNotFoundException) => {
        // Meh, we were trying to delete it anyway.
      }
      case inner: RemoteTransportException => inner.getCause match {
        case (inner: IndexNotFoundException) => {
          // Meh, we were trying to delete it anyway.
        }
      }
      case e =>
        logger.debug("Inner exception class {}", e.getClass.toString)
        throw e
    }
    case e =>
      logger.debug("Exception class {}", e.getClass.toString)
      throw e
  } map { _ =>
    Unit
  }

  sealed trait RestoreResult
  case object RestoreSuccess extends RestoreResult
  case object RestoreFailure extends RestoreResult

  private def restoreLatestSnapshot(client: ElasticClient, index: IndexDefinition): Future[RestoreResult] = {
    logger.info("Attempting to restore snapshot for {} version {}", index.name, index.version)

    getLatestSnapshot(client, index) flatMap {
      case None =>
        logger.info("Could not find a snapshot for {} version {}", index.name, index.version)
        Future.successful(RestoreFailure)
      case Some(snapshot) =>
        logger.info("Found snapshot {} for {} version {}, queueing restore operation", snapshot.name, index.name, index.version)
        val promise = Promise[RestoreResult]()
        restoreQueue.offer((client, index, snapshot, promise))
        promise.future
    }
  }

  private def getLatestSnapshot(client: ElasticClient, index: IndexDefinition): Future[Option[SnapshotInfo]] = {
    def getSnapshot(): Future[GetSnapshotsResponse] = client.execute {
      get snapshot Seq() from snapshotRepoName(index)
    }

    getSnapshot()
      .map(x => Future.successful(x))
      .recover {
        case (e: RemoteTransportException) => e.getCause match {
          case e: RemoteTransportException => e.getCause match {
            case (e: RepositoryMissingException) =>
              createSnapshotRepo(client, index).flatMap(_ => getSnapshot)
            case e => throw new RuntimeException(e)
          }
          case (e: RepositoryMissingException) =>
            createSnapshotRepo(client, index).flatMap(_ => getSnapshot)
          case e => throw new RuntimeException(e)
        }
        case e: Throwable => throw new RuntimeException(e)
      }
      .flatMap(identity)
      .map { response =>
        response.getSnapshots
          .view
          .filter(_.name.startsWith(snapshotRepoName(index)))
          .filter(_.failedShards() == 0)
          .sortBy(-_.endTime)
          .headOption
      }
  }

  private def createSnapshotRepo(client: ElasticClient, definition: IndexDefinition): Future[PutRepositoryResponse] = {
    val repoConfig = AppConfig.conf.getConfig("elasticSearch.snapshotRepo")
    val repoType = repoConfig.getString("type")
    val settings = repoConfig.getConfig("types." + repoType).entrySet().map { case entry => (entry.getKey, entry.getValue().unwrapped()) } toMap

    client.execute(
      create repository snapshotRepoName(definition) `type` repoType settings settings
    )
  }

  private def snapshotRepoName(definition: IndexDefinition) = s"${definition.name}-${definition.version}"

  /** Returns a list of all years between two Instants, inclusively, as strings */
  private def getYears(from: Option[Instant], to: Option[Instant]): List[Int] = {
    def getYearsInner(from: LocalDate, to: LocalDate): List[Int] =
      if (from.isAfter(to)) {
        Nil
      } else {
        from.getYear :: getYearsInner(from.plusYears(1), to)
      }

    (from, to) match {
      case (None, None) => Nil
      case _ => {
        val newFrom = from.getOrElse(to.get).atZone(ZoneId.systemDefault).toLocalDate
        val newTo = to.getOrElse(from.get).atZone(ZoneId.systemDefault).toLocalDate

        getYearsInner(newFrom, newTo)
      }
    }
  }

  override def index(source: InterfaceConfig, dataSets: List[DataSet]) =
    if (dataSets.length > 0) {
      val promise = Promise[BulkResult]()
      indexQueue.offer((source, dataSets, promise))
        .map {
          case QueueOfferResult.Enqueued    => QueueOfferResult.Enqueued
          case QueueOfferResult.Dropped     => throw new Exception("Dropped")
          case QueueOfferResult.QueueClosed => throw new Exception("Queue Closed")
          case QueueOfferResult.Failure(e)  => throw e
        }

      promise.future
    } else {
      Future(Unit)
    }

  def snapshot(): Future[Unit] = setupFuture.flatMap(client => createSnapshot(client, IndexDefinition.datasets)).map(_ => Unit)

  private def createSnapshot(client: ElasticClient, definition: IndexDefinition): Future[CreateSnapshotResponse] = {
    logger.info("Creating snapshot for {} at version {}", definition.name, definition.version)

    val future = client.execute {
      create snapshot snapshotRepoName(definition) + "-" + Instant.now().toString.toLowerCase in snapshotRepoName(IndexDefinition.datasets) waitForCompletion true indexes definition.name
    }

    future.onComplete {
      case Success(result) =>
        val info = result.getSnapshotInfo
        logger.info("Snapshotted {} shards of {} for {}", info.successfulShards(), info.totalShards(), definition.name)
      case Failure(e) => logger.error(e, "Failed to snapshot {}", definition.name)
    }

    future
  }

  override def needsReindexing(source: InterfaceConfig): Future[Boolean] = {
    setupFuture.flatMap(client =>
      retry(() =>
        client.execute {
          ElasticDsl.search in "datasets" / "datasets" query matchQuery("catalog", source.name) limit 0
        }, 10 seconds, 10, logger.warning("Failed to get dataset count, {} retries left", _))
        .map { result =>
          logger.debug("{} reindex check hit count: {}", source.name, result.getHits.getTotalHits)
          result.getHits.getTotalHits == 0
        }
    )
  }

  private def bulkIndex(definition: BulkDefinition): Future[BulkResult] =
    setupFuture.flatMap { client =>
      client.execute(definition)
        .recover {
          case t: Throwable =>
            logger.error(t, "Error when indexing records")
            throw t
        }
    }

  /**
   * Indexes a number of datasets into ES using a bulk insert.
   */
  private def buildDatasetIndexDefinition(source: InterfaceConfig, dataSets: Seq[DataSet]): BulkDefinition =
    bulk(
      dataSets.map { dataSet =>
        val indexDataSet = ElasticDsl.index into "datasets" / "datasets" id dataSet.uniqueId source (
          dataSet.copy(
            catalog = source.name,
            years = getYears(dataSet.temporal.flatMap(_.start.flatMap(_.date)), dataSet.temporal.flatMap(_.end.flatMap(_.date))) match {
              case Nil  => None
              case list => Some(list)
            }).toJson)

        val indexPublisher = dataSet.publisher.flatMap(_.name.map(publisherName =>
          ElasticDsl.index into "datasets" / Publisher.id
            id publisherName.toLowerCase
            source Map("value" -> publisherName).toJson))

        val indexYears = getYears(
          dataSet.temporal.flatMap(_.start.flatMap(_.date)),
          dataSet.temporal.flatMap(_.end.flatMap(_.date))).map(year => ElasticDsl.index into "datasets" / Year.id id year source Map("value" -> year).toJson)

        val indexFormats = dataSet.distributions.filter(_.format.isDefined).map { distribution =>
          val format = distribution.format.get

          ElasticDsl.index into "datasets" / Format.id id format.toLowerCase source Map("value" -> format).toJson
        }

        indexDataSet :: indexYears ++ indexPublisher.toList ++ indexFormats
      }.flatten)

}