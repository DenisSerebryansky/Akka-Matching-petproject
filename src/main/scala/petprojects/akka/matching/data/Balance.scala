package petprojects.akka.matching.data

import petprojects.akka.matching.data.Shares.Shares

case class Balance(dollars: Int, sharesBalance: Map[Shares, Int]) {
  override def toString: String =
    s"$$$dollars, (${sharesBalance.map { case (s, c) ⇒ s"$s: $c" }.mkString(", ")})"

  def updateByOrderDetails(orderDetails: OrderDetails): Balance = {
    val changedShareBalance = sharesBalance(orderDetails.share)

    orderDetails.action match {
      case Buy ⇒
        this.copy(
          dollars = dollars - orderDetails.count * orderDetails.price,
          sharesBalance = sharesBalance.updated(orderDetails.share, changedShareBalance + orderDetails.count))
      case Sell ⇒
        this.copy(
          dollars = dollars + orderDetails.count * orderDetails.price,
          sharesBalance = sharesBalance.updated(orderDetails.share, changedShareBalance - orderDetails.count))
    }
  }
}