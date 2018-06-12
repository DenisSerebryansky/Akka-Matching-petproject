package petprojects.akka.matching

import akka.actor.ActorSystem
import akka.pattern.ask
import akka.util.Timeout
import petprojects.akka.matching.Utils.saveClientBalances
import petprojects.akka.matching.actors.{Exchange, OrdersGenerator, QueryHandler}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

object MatchingApp extends App {

  val system = ActorSystem("Matching")
  val exchange = system.actorOf(Exchange.props("/clients.txt"), "Exchange")
  val orderGenerator = system.actorOf(OrdersGenerator.props, "OrderGenerator")

  implicit val timeout: Timeout = Timeout(5 seconds)

  (orderGenerator ? OrdersGenerator.StartGenerateOrders("/orders.txt", exchange))
    .flatMap { case OrdersGenerator.GeneratingFinished ⇒ exchange ? Exchange.QueryClientBalances() }
    .foreach { case QueryHandler.AllBalancesResponse(clientBalances) ⇒
      saveClientBalances(clientBalances)
      system.terminate
    }
}