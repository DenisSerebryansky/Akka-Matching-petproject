package petprojects.akka.matching.actors

import akka.actor.{Actor, Props}
import petprojects.akka.matching.data.{Balance, OrderDetails}

object Client {
  def props(balance: Balance): Props = Props(new Client(balance))

  sealed trait SearchingResult
  final case class MatchedOrder(orderId: Int) extends SearchingResult
  case object NoMatches extends SearchingResult

  final case class InsertOrderToWaitingList(orderId: Int, orderDetails: OrderDetails)

  final case class ProcessWaitingOrderById(waitingOrderId: Int)
  case object ProcessWaitingOrderDone

  final case class ProcessOrderByDetails(orderDetails: OrderDetails)
  case object ProcessOrderDone

  final case class BalanceResponse(balance: Balance)

  type OrderId = Int
}

/**
  * Actor of the Client. It's a child of the Exchange actor.
  *
  * Can only be in the state `awaitingOrderProcessing`. This state keeps current client balance and waiting list of
  * unprocessed order IDs and order details (waitingOrderIds2Details).
  *
  * Actor updates its state every time it receives request for order processing or inserting order into actor's
  * waiting list.
  *
  * Client actor also can respond to a balance queries and `SearchForOrderMatches` requests
  *
  * @param initialBalance    balance of the client right after creation
  */
class Client(initialBalance: Balance) extends Actor {

  import Client._

  override def receive: Receive = awaitingOrderProcessing(initialBalance, Map.empty[OrderId, OrderDetails])

  def awaitingOrderProcessing(currentBalance: Balance,
                              waitingOrderIds2Details: Map[OrderId, OrderDetails]): Receive = {

    // receive reverse order details when some client searches for his order match. For example,
    // some client searches for match of his order |BUY 12 SHARES `D` FOR 10$ EACH|, the rest of the clients
    // will receive request for searching for order |SELL 12 SHARES `D` FOR 10$ EACH| in their waiting lists

    case OrderHandler.SearchForOrderMatches(reverseOrderDetails) ⇒
      waitingOrderIds2Details.find(_._2 == reverseOrderDetails).fold(sender ! NoMatches){
        foundedOrder ⇒ sender ! MatchedOrder(foundedOrder._1)
      }

    // insert order ID and it's details to the client waiting list when there aren't matches of this order

    case InsertOrderToWaitingList(orderId, orderDetails) ⇒
      context.become(
        awaitingOrderProcessing(
          currentBalance,
          waitingOrderIds2Details + (orderId → orderDetails)
        )
      )

    // update state of the client by order ID in the waiting list
    // (when the match of the client order appeared only after inserting it in the waiting list)
    case ProcessWaitingOrderById(waitingOrderId) ⇒
      context.become(
        awaitingOrderProcessing(
          currentBalance updateByOrderDetails waitingOrderIds2Details(waitingOrderId),
          waitingOrderIds2Details - waitingOrderId
        )
      )
      sender ! ProcessWaitingOrderDone

    // update state of the client by order details
    // (when there is match of the client order during his request to the exchange)

    case ProcessOrderByDetails(orderDetails) ⇒
      context.become(
        awaitingOrderProcessing(
          currentBalance updateByOrderDetails orderDetails,
          waitingOrderIds2Details
        )
      )
      sender ! ProcessOrderDone

    case QueryHandler.BalanceQuery ⇒ sender ! BalanceResponse(currentBalance)
  }
}