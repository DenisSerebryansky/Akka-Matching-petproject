package petprojects.akka.matching.actors

import akka.actor.{Actor, ActorRef, Props}
import petprojects.akka.matching.Utils._
import petprojects.akka.matching.data.{Balance, ExchangeResponse}

import scala.collection.immutable.TreeMap

object QueryHandler {
  def props(clients: Set[ActorRef]): Props = Props(new QueryHandler(clients))

  case object BalanceQuery
  final case class AllBalancesResponse(clientNames2Balances: TreeMap[String, Balance]) extends ExchangeResponse
}

/**
  * Actor of the QueryHandler. It's a temporary child of the Exchange actor.
  * Serves for getting balances of the clients
  *
  * @param clients  list of the clients for the balance query
  */
class QueryHandler(clients: Set[ActorRef]) extends Actor {

  import QueryHandler._

  override def preStart(): Unit = clients.foreach(_ ! BalanceQuery)

  def receive: Receive = waitingForReplies(TreeMap.empty[ActorRef, Balance], clients)

  def waitingForReplies(repliesSoFar: TreeMap[ActorRef, Balance], stillWaiting: Set[ActorRef]): Receive = {

    case Client.BalanceResponse(balance) ⇒

      val newRepliesSoFar = repliesSoFar + (sender → balance)
      val newStillWaiting = stillWaiting - sender

      if (newStillWaiting.isEmpty) {

        val response =
          AllBalancesResponse(
            newRepliesSoFar
              .map { case (clientActor, clientBalance) ⇒ clientActor.name → clientBalance }
          )

        context.parent ! response
        context.stop(self)

      } else context.become(waitingForReplies(newRepliesSoFar, newStillWaiting))
  }
}