package petprojects.akka.matching

import akka.actor.{Actor, ActorRef, ActorSystem}
import akka.testkit.{ImplicitSender, TestKit}
import akka.util.Timeout
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import petprojects.akka.matching.data.Shares._
import petprojects.akka.matching.data._
import Utils._
import petprojects.akka.matching.actors.{Exchange, OrderHandler, OrdersGenerator, QueryHandler}
import petprojects.akka.matching.actors.OrdersGenerator.Order

import scala.collection.immutable.TreeMap
import scala.concurrent.duration._
import scala.language.postfixOps

class ExchangeSpec
  extends TestKit(ActorSystem("ExchangeSpec"))
    with WordSpecLike
    with BeforeAndAfterAll
    with ImplicitSender
    with Matchers {

  override def afterAll(): Unit = shutdown(system)

  implicit val timeout: Timeout = 10 seconds

  lazy val testOrderBuy  = Order(0, "C8", OrderDetails(Buy,  C, 15, 5))
  lazy val testOrderSell = Order(1, "C2", OrderDetails(Sell, C, 15, 5))

  lazy val initialClientBalances =
    TreeMap(
      "C1" → Balance(1000, Map(A → 130, B → 240, C → 760, D → 320)),
      "C2" → Balance(4350, Map(A → 370, B → 120, C → 950, D → 560)),
      "C3" → Balance(2760, Map(A → 0,   B → 0,   C → 0,   D → 0)),
      "C4" → Balance(560,  Map(A → 450, B → 540, C → 480, D → 950)),
      "C5" → Balance(1500, Map(A → 0,   B → 0,   C → 400, D → 100)),
      "C6" → Balance(1300, Map(A → 890, B → 320, C → 100, D → 0)),
      "C7" → Balance(750,  Map(A → 20,  B → 0,   C → 790, D → 0)),
      "C8" → Balance(7000, Map(A → 90,  B → 190, C → 0,   D → 0)),
      "C9" → Balance(7250, Map(A → 190, B → 190, C → 0,   D → 280))
    )

  var exchange: ActorRef = _
  lazy val ordersGenerator: ActorRef = system.actorOf(OrdersGenerator.props)

  "The Exchange actor" should {

    "respond with `OrderWasNotProcessed` when the only one order was sent" in {
      exchange = system.actorOf(Exchange.props("/clients.txt"))

      exchange ! testOrderBuy
      expectMsg(OrderHandler.OrderWasNotProcessed(testOrderBuy))
    }

    "respond with `OrderWasProcessed` when there is order match" in {
      exchange = system.actorOf(Exchange.props("/clients.txt"))

      exchange ! testOrderBuy
      expectMsg(OrderHandler.OrderWasNotProcessed(testOrderBuy))

      exchange ! testOrderSell
      expectMsg(OrderHandler.OrderWasProcessed(testOrderSell, "C8"))
    }

    "return initial client balances" in {
      exchange = system.actorOf(Exchange.props("/clients.txt"))

      exchange ! Exchange.QueryClientBalances()
      expectMsg(QueryHandler.AllBalancesResponse(initialClientBalances))
    }

    "return balance of the only one client" in {
      exchange = system.actorOf(Exchange.props("/clients.txt"))

      exchange ! Exchange.QueryClientBalances(Set("C1"))
      expectMsg(
        QueryHandler.AllBalancesResponse(
          TreeMap(initialClientBalances.head)
        )
      )
    }

    "process order with the least ID" in {
      exchange = system.actorOf(Exchange.props("/clients.txt"))

      exchange.tell(testOrderBuy.copy(id = 101, clientName = "C2"), Actor.noSender)
      exchange.tell(testOrderBuy.copy(id = 100, clientName = "C1"), Actor.noSender)

      exchange ! testOrderSell
      expectMsg(OrderHandler.OrderWasProcessed(testOrderSell, "C1"))
    }

    "not process order of a client to himself" in {
      exchange = system.actorOf(Exchange.props("/clients.txt"))

      val buyOrder: Order = testOrderBuy.copy(id = 100, clientName = "C2")
      val sellOrder: Order = testOrderSell.copy(id = 101, clientName = "C2")

      exchange.tell(buyOrder, Actor.noSender)
      exchange ! sellOrder

      expectMsg(OrderHandler.OrderWasNotProcessed(sellOrder))
    }

    "correctly process deal between two clients" in {
      exchange = system.actorOf(Exchange.props("/clients.txt"))

      exchange.tell(testOrderBuy, Actor.noSender)

      exchange ! testOrderSell
      expectMsg(OrderHandler.OrderWasProcessed(testOrderSell, "C8"))

      exchange ! Exchange.QueryClientBalances(Set("C8", "C2"))
      expectMsg(
        QueryHandler.AllBalancesResponse(
          TreeMap(
            "C2" → Balance(4425, Map(A → 370, B → 120, C → 945, D → 560)),
            "C8" → Balance(6925, Map(A → 90, B → 190, C → 5, D → 0))
          )
        )
      )
    }

    "correctly process deal between several clients" in {
      exchange = system.actorOf(Exchange.props("/testClients.txt"))

      ordersGenerator ! OrdersGenerator.StartGenerateOrders("/testOrders.txt", exchange)
      expectMsg(OrdersGenerator.GeneratingFinished)

      exchange ! Exchange.QueryClientBalances()
      expectMsg(5 seconds,
        QueryHandler.AllBalancesResponse(
          TreeMap(
            "C1" → Balance(90,  Map(A → 10, B → 10, C → 11, D → 10)),
            "C2" → Balance(110, Map(A → 10, B → 10, C → 9,  D → 10)),
            "C3" → Balance(110, Map(A → 10, B → 10, C → 8,  D → 10)),
            "C4" → Balance(90,  Map(A → 20, B → 10, C → 10, D → 10)),
            "C5" → Balance(90,  Map(A → 10, B → 10, C → 12, D → 10)),
            "C6" → Balance(80,  Map(A → 10, B → 10, C → 10, D → 15)),
            "C7" → Balance(110, Map(A → 0,  B → 10, C → 10, D → 10)),
            "C8" → Balance(100, Map(A → 10, B → 10, C → 10, D → 10)),
            "C9" → Balance(120, Map(A → 10, B → 10, C → 10, D → 5))
          )
        )
      )
    }

    "correctly process real orders flow" in {
      exchange = system.actorOf(Exchange.props("/clients.txt"))

      ordersGenerator ! OrdersGenerator.StartGenerateOrders("/orders.txt", exchange)
      expectMsg(OrdersGenerator.GeneratingFinished)

      exchange ! Exchange.QueryClientBalances()

      val areBalancesInvariant =
        expectMsgPF(10 seconds) {
          case balanceResponse @ QueryHandler.AllBalancesResponse(balancesAfterProcessing) ⇒
            printBalances(balanceResponse)
            checkBalancesInvariant(
              balancesBefore = initialClientBalances.values.toSeq,
              balancesAfter = balancesAfterProcessing.values.toSeq
            )
        }

      areBalancesInvariant shouldBe true
    }
  }
}