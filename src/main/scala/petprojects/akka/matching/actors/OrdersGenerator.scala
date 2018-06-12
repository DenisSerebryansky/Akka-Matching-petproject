package petprojects.akka.matching.actors

import akka.actor.{Actor, ActorRef, Props}
import petprojects.akka.matching.data._

import scala.io.Source

object OrdersGenerator {
  def props: Props = Props(new OrdersGenerator)

  final case class Order(id: Int, clientName: String, details: OrderDetails) extends ExchangeRequest
  final case class StartGenerateOrders(ordersFileName: String, target: ActorRef)

  case object GeneratingFinished
}

/**
  * Actor of the OrderGenerator.
  * Parses orders from the txt file and sends them to the target actor (exchange or test actor)
  */
class OrdersGenerator extends Actor {

  import OrdersGenerator._

  override def receive: Receive = {

    case StartGenerateOrders(ordersFileName, target) ⇒

      val fileStream = getClass.getResourceAsStream(ordersFileName)
      val lines = Source.fromInputStream(fileStream).getLines.toSeq

      val ordersList =
        lines
          .zipWithIndex
          .par
          .map {
            case (line, idx) ⇒
              val splitted = line.split("\t")
              Order(
                idx,
                splitted(0),
                OrderDetails(
                  if (splitted(1) == "b") Buy else Sell,
                  Shares.withName(splitted(2)),
                  splitted(3).toInt,
                  splitted(4).toInt)
              )
          }
          .seq

      ordersList.foreach { target.tell(_, Actor.noSender) }
      sender ! GeneratingFinished
  }
}