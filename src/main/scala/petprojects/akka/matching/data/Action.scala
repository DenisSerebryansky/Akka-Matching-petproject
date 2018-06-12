package petprojects.akka.matching.data

sealed trait Action {
  def reverseAction: Action
}

case object Buy extends Action {
  def reverseAction: Action = Sell
}

case object Sell extends Action {
  def reverseAction: Action = Buy
}