package petprojects.akka.matching.data

import petprojects.akka.matching.data.Shares.Shares

case class OrderDetails(action: Action, share: Shares, price: Int, count: Int)