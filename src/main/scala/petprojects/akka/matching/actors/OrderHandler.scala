package petprojects.akka.matching.actors

import akka.actor.{Actor, ActorRef, Props}
import petprojects.akka.matching.actors.OrderHandler.{OrderWasNotProcessed, OrderWasProcessed, SearchForOrderMatches}
import petprojects.akka.matching.actors.OrdersGenerator.Order
import petprojects.akka.matching.data.{ExchangeResponse, OrderDetails}

object OrderHandler {
  def props(client: ActorRef, restOfTheClients: Set[ActorRef], order: Order): Props =
    Props(new OrderHandler(client, restOfTheClients, order))

  final case class SearchForOrderMatches(orderDetails: OrderDetails)
  final case class OrderWasNotProcessed(order: Order) extends ExchangeResponse
  final case class OrderWasProcessed(order: Order, counterpartyName: String) extends ExchangeResponse
}

/**
  * Actor of the OrderHandler. It's a temporary child of the Exchange actor.
  *
  * Determines the clients which have order match in their waiting lists.
  * Then initiates transfer between the client and his counterparty (the latter one is selected by the least order ID
  * in the waiting lists). When the transfer is done, order handler sends the processing result to it's parent and stops
  * itself.
  *
  * If there aren't order matches in the waiting lists of the rest of the clients, this actor inserts order
  * into client waiting list, also sends the processing result to it's parent and stops itself
  *
  * @param client           client whose order is being processed
  * @param restOfTheClients the rest of the exchange clients
  * @param order            client's order
  */
class OrderHandler(client: ActorRef, restOfTheClients: Set[ActorRef], order: Order) extends Actor {

  /**
    * Sends broadcast request for the order matches to the rest of the clients
    * (search for reverse order in the client waiting lists)
    */
  override def preStart(): Unit =
    restOfTheClients
      .foreach(_ ! SearchForOrderMatches(order.details.copy(action = order.details.action.reverseAction)))

  /**
    * Initial state of the order handler:
    *   - awaiting responses from the all clients except for the one whose order is being processed
    *   - empty list of deal participants
    */
  override def receive: Receive =
    waitingForReplies(
      stillWaiting = restOfTheClients,
      dealParticipants2OrderIds = Map.empty[ActorRef, Int]
    )

  /**
    * State of awaiting responses to the `SearchForOrderMatches` request from the clients.
    *
    * Delegates money and shares transfer to the WireTransfer actor and changes order handler's
    * behavior to `awaitingTransferResult` when there is order match.
    *
    * Otherwise inserts unprocessed order to the client waiting list.
    *
    * @param stillWaiting              list of the clients who haven't yet responded to the
    *                                  `SearchForOrderMatches` request
    * @param dealParticipants2OrderIds list of the clients who have order matches and IDs of their matched orders
    */
  def waitingForReplies(stillWaiting: Set[ActorRef],
                        dealParticipants2OrderIds: Map[ActorRef, Int]): Receive = {

    case searchingResult: Client.SearchingResult ⇒

      val respondedClient = sender

      val newDealParticipants = searchingResult match {
        case Client.MatchedOrder(matchedOrderId) ⇒ dealParticipants2OrderIds + (respondedClient → matchedOrderId)
        case Client.NoMatches ⇒ dealParticipants2OrderIds
      }

      val newStillWaiting = stillWaiting - respondedClient

      if (newStillWaiting.nonEmpty) context.become(waitingForReplies(newStillWaiting, newDealParticipants))
      else {
        if (newDealParticipants.nonEmpty) {

          // select client with the least matched order ID

          val counterpartyToHisOrderId = newDealParticipants.minBy(_._2)

          // delegate transfer to the appropriate actor

          val wireTransfer =
            context.actorOf(
              WireTransfer.props(
                client = client,
                clientOrder = order,
                counterparty = counterpartyToHisOrderId._1,
                counterpartyWaitingOrderId = counterpartyToHisOrderId._2
              )
            )

          wireTransfer ! WireTransfer.Transfer
          context.become(awaitTransferResult)

        } else {

          // insert unprocessed order to the client waiting list, notify parent about result and stop itself

          client ! Client.InsertOrderToWaitingList(order.id, order.details)
          context.parent ! OrderWasNotProcessed(order)
          context.stop(self)
        }
      }
  }

  /** Awaits transfer result and handles it */
  def awaitTransferResult: Receive = {
    case orderWasProcessed: OrderWasProcessed ⇒
      context.parent ! orderWasProcessed
      context.stop(self)
  }
}