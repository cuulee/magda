package au.csiro.data61.magda.registry

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.{ HttpMethods, HttpRequest, MessageEntity, Uri }
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Sink, Source }
import scalikejdbc._
import spray.json.JsString
import au.csiro.data61.magda.model.Registry._

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._
import scala.util.{ Success, Failure }

class WebHookProcessor(actorSystem: ActorSystem, val publicUrl: Uri, implicit val executionContext: ExecutionContext) extends Protocols {
  private val http = Http(actorSystem)
  private implicit val materializer: ActorMaterializer = ActorMaterializer()(actorSystem)

  def sendSomeNotificationsForOneWebHook(id: String, webHook: WebHook, eventPage: EventsPage): Future[Boolean] = {

    //    val events = if (!startup && webHook.isWaitingForResponse.getOrElse(false)) List() else eventPage.events
    val events = eventPage.events
    val relevantEventTypes = webHook.eventTypes

    val changeEvents = events.filter(event => relevantEventTypes.contains(event.eventType))
    val recordChangeEvents = events.filter(event => event.eventType.isRecordEvent || event.eventType.isRecordAspectEvent)
    val aspectDefinitionChangeEvents = events.filter(event => event.eventType.isAspectDefinitionEvent)

    val aspectDefinitionIds = aspectDefinitionChangeEvents.map(_.data.fields("aspectId").asInstanceOf[JsString].value)

    // If we're including records, get a complete record with aspects for each record ID
    val records = webHook.config.includeRecords match {
      case Some(false) | None => None
      case Some(true) => DB readOnly { implicit session =>
        // We're going to include two types of records in the payload:
        // 1. Records that are directly referenced by one of the events.
        // 2. Records that _link_ to a record referenced by one of the events.
        //
        // For #1, we should ignore record aspect create/change/delete events that the
        // web hook didn't request.  i.e. only consider aspects in the web hook's
        // 'aspects' and 'optionalAspects' lists.  These are "direct" events/records
        // in the code below.
        //
        // For #2, aspects/optionalAspects don't control what is returned

        val directRecordChangeEvents = recordChangeEvents.filter { event =>
          if (!event.eventType.isRecordAspectEvent)
            true
          else {
            val aspectId = event.data.fields("aspectId").asInstanceOf[JsString].value
            webHook.config.aspects.getOrElse(List()).contains(aspectId) || webHook.config.optionalAspects.getOrElse(List()).contains(aspectId)
          }
        }

        val directRecordIds = directRecordChangeEvents.map(_.data.fields("recordId").asInstanceOf[JsString].value).toSet

        // Get records directly modified by these events.
        val directRecords = if (directRecordIds.isEmpty) RecordsPage(0, None, List()) else RecordPersistence.getByIdsWithAspects(
          session,
          directRecordIds,
          webHook.config.aspects.getOrElse(List()),
          webHook.config.optionalAspects.getOrElse(List()),
          webHook.config.dereference)

        // If we're dereferencing, we also need to include any records that link to
        // changed records from aspects that we're including.
        val recordsFromDereference = webHook.config.dereference match {
          case Some(false) | None => List[Record]()
          case Some(true) => {
            val allRecordIds = recordChangeEvents.map(_.data.fields("recordId").asInstanceOf[JsString].value).toSet
            if (allRecordIds.isEmpty) {
              List()
            } else {
              RecordPersistence.getRecordsLinkingToRecordIds(
                session,
                allRecordIds,
                directRecords.records.map(_.id),
                webHook.config.aspects.getOrElse(List()),
                webHook.config.optionalAspects.getOrElse(List()),
                webHook.config.dereference).records
            }
          }
        }
        
        Some(directRecords.records ++ recordsFromDereference)
      }
    }

    val aspectDefinitions = webHook.config.includeAspectDefinitions match {
      case Some(false) | None => None
      case Some(true)         => DB readOnly { implicit session => Some(AspectPersistence.getByIds(session, aspectDefinitionIds)) }
    }

    val payload = WebHookPayload(
      action = "records.changed",
      lastEventId = eventPage.events.lastOption.flatMap(_.id).orElse(webHook.lastEvent).get, //if (events.isEmpty) webHook.lastEvent.get else events.last.id.get,
      events = if (webHook.config.includeEvents.getOrElse(true)) Some(changeEvents) else None,
      records = records,
      aspectDefinitions = aspectDefinitions,
      deferredResponseUrl = Some(Uri(s"hooks/${java.net.URLEncoder.encode(webHook.id.get, "UTF-8")}/ack").resolvedAgainst(publicUrl).toString()))

    Marshal(payload).to[MessageEntity].flatMap(entity => {
      val singleRequestStream = Source.single(HttpRequest(
        uri = Uri(webHook.url),
        method = HttpMethods.POST,
        entity = entity))
      val responseStream = singleRequestStream.map((_, 1)).via(http.superPool())
      val resultStream = responseStream.mapAsync(1) { response =>
        response match {
          case (Success(response), _) => {
            if (response.status.isFailure()) {
              response.discardEntityBytes()
              Future.failed(new Exception(s"Response from $webHook.url was ${response.status.reason()}"))
            } else {
              // Try to deserialize the success response as a WebHook response.  It's ok if this fails.
              Unmarshal(response.entity).to[WebHookResponse].map { webHookResponse =>
                if (webHookResponse.deferResponse) {
                  DB localTx { session =>
                    HookPersistence.setIsWaitingForResponse(session, webHook.id.get, true)
                  }
                  true
                } else {
                  DB localTx { session =>
                    HookPersistence.setLastEvent(session, webHook.id.get, payload.lastEventId)
                  }
                  false
                }
              }.recover {
                case _: Throwable => {
                  // Success response that can't be unmarshalled to a WebHookResponse.  This is fine!
                  // It just means the webhook was handled successfully.
                  DB localTx { session =>
                    HookPersistence.setLastEvent(session, webHook.id.get, payload.lastEventId)
                  }
                  false
                }
              }
            }
          }
          case (Failure(error), _) =>
            Future.failed(error)
        }
      }
      resultStream.completionTimeout(60 seconds).runWith(Sink.head)
    })
  }
}
