package petprojects.akka.matching.actors

import java.io.InputStream

import akka.actor.{Actor, ActorRef, Props}
import akka.pattern.ask
import akka.util.Timeout
import petprojects.akka.matching.Utils.{ActorWithName, senderExists}
import petprojects.akka.matching.actors.OrdersGenerator.Order
import petprojects.akka.matching.data.Shares._
import petprojects.akka.matching.data.{Balance, ExchangeRequest, ExchangeResponse}

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.io.Source

object Exchange {
  def props(clientsFileName: String): Props = Props(new Exchange(clientsFileName))

  case object RequestToBuffer
  final case class EnqueueExchangeRequest(request: ExchangeRequest, requester: ActorRef)
  final case class QueryClientBalances(clientNames: Set[String] = Set.empty) extends ExchangeRequest
}

/**
  * Actor of the Exchange.
  *
  * Clients and Buffer are constant children of this actor.
  *
  * Can be in two states:
  *   1. awaitingRequests;
  *   2. processingRequest.
  *
  * While being in the `awaitingRequests` state, exchange actor can receive messages of type ExchangeRequest.
  * Depends on request (order or query) this actor creates OrderHandler or QueryHandler, delegates
  * request processing to them and then changes itself state to `processingRequest`.
  *
  * While being in the `processingRequest` state, exchange actor forwards all incoming messages of type ExchangeRequest
  * to the Buffer actor. If exchange receives result of order or query processing (messages of type ExchangeResponse)
  * it sends response to the requester and asks Buffer for enqueued requests. If Buffer is empty, exchange goes to
  * `awaitingRequests` state, otherwise exchange starts processing all enqueued requests
  *
  * @param clientsFileName name of file with client descriptions
  */
class Exchange(clientsFileName: String) extends Actor {

  import Exchange._

  implicit val timeout: Timeout = Timeout(5 seconds)

  val fileStream: InputStream = getClass.getResourceAsStream(clientsFileName)
  val lines: Seq[String] = Source.fromInputStream(fileStream).getLines.toSeq

  lines.foreach(
    line ⇒ {
      val splitted = line.split("\t")
      context.actorOf(
        Client.props(
          Balance(
            splitted(1).toInt,
            Map(
              A → splitted(2).toInt,
              B → splitted(3).toInt,
              C → splitted(4).toInt,
              D → splitted(5).toInt)
          )
        ),
        splitted(0)
      )
    }
  )

  val clientNames2ActorRef: Map[String, ActorRef] = context.children.map(actor ⇒ actor.name → actor).toMap
  val buffer: ActorRef = context.actorOf(Buffer.props, "Buffer")

  override def receive: Receive = awaitingRequests

  def awaitingRequests: Receive = {
    case request: ExchangeRequest ⇒ startProcessingRequest(request, sender)
  }

  def startProcessingRequest(exchangeRequest: ExchangeRequest, requester: ActorRef): Unit = {
    exchangeRequest match {

      // delegate the processing of an order to the OrderHandler

      case order: Order ⇒
        context.actorOf(
          props =
            OrderHandler.props(
              clientNames2ActorRef(order.clientName),
              clientNames2ActorRef.filter { case (actorName, _) ⇒ actorName != order.clientName }.values.toSet,
              order
            ),
          name = s"OrderHandler-${order.id}"
        )

      // delegate the processing of a query to the QueryHandler
      // (query to all clients by default)

      case QueryClientBalances(clientNames) ⇒
        context.actorOf(
          QueryHandler.props(
            if (clientNames.isEmpty) clientNames2ActorRef.values.toSet
            else clientNames2ActorRef.filter { case (clientName, _) ⇒ clientNames contains clientName }.values.toSet
          )
        )
    }

    // change behavior to `processingRequest` with remembering of the requester
    // (actually it is necessary only for testing)

    context.become(processingRequest(requester))
  }

  def processingRequest(requester: ActorRef): Receive = {
    case request: ExchangeRequest ⇒ buffer ! EnqueueExchangeRequest(request, sender)
    case respond: ExchangeResponse ⇒

      // it doesn't make sense to respond to OrderGenerator actor (it sends orders from Actor.noSender)

      if (senderExists(requester)) requester ! respond

      // receiving requests while awaiting response from the Buffer could lead to incorrect behavior,
      // therefore asynchronous request to the Buffer is made synchronous

      Await.result(
        (buffer ? RequestToBuffer).map {
          case Buffer.BufferIsEmpty ⇒ context.become(awaitingRequests)
          case Buffer.DequeuedRequest(dequeuedRequest, dequeuedRequester) ⇒
            startProcessingRequest(dequeuedRequest, dequeuedRequester)
        }, 5 seconds)
  }
}