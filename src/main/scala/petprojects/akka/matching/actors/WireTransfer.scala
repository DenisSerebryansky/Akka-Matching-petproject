package petprojects.akka.matching.actors

import akka.actor.{Actor, ActorRef, Props}
import petprojects.akka.matching.Utils._
import petprojects.akka.matching.actors.OrdersGenerator.Order

object WireTransfer {
  def props(client: ActorRef,
            clientOrder: Order,
            counterparty: ActorRef,
            counterpartyWaitingOrderId: Int): Props =
    Props(new WireTransfer(client, clientOrder, counterparty, counterpartyWaitingOrderId))

  case object Transfer
}

/**
  * Actor of the WireTransfer. It's a temporary child of the OrderHandler actor.
  * Serves for transfer money and shares between two clients
  *
  * @param client                     client whose order is being processed
  * @param clientOrder                order of the client
  * @param counterparty               client who has order match
  * @param counterpartyWaitingOrderId order ID which indicates order match in counterparty waiting list
  */
class WireTransfer(client: ActorRef,
                   clientOrder: Order,
                   counterparty: ActorRef,
                   counterpartyWaitingOrderId: Int) extends Actor {

  import WireTransfer._

  def receive: Receive = {
    case Transfer ⇒
      counterparty ! Client.ProcessWaitingOrderById(counterpartyWaitingOrderId)
      context.become(awaitCounterpartyOrderProcessing)
  }

  def awaitCounterpartyOrderProcessing: Receive = {
    case Client.ProcessWaitingOrderDone ⇒
      client ! Client.ProcessOrderByDetails(clientOrder.details)
      context.become(awaitClientOrderProcessing)
  }

  def awaitClientOrderProcessing: Receive = {
    case Client.ProcessOrderDone ⇒
      context.parent ! OrderHandler.OrderWasProcessed(clientOrder, counterparty.name)
      context.stop(self)
  }
}