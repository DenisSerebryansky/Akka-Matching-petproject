package petprojects.akka.matching

import java.io.{BufferedWriter, File, FileWriter}

import akka.actor.ActorRef
import petprojects.akka.matching.data.{Balance, Shares}
import Shares._
import petprojects.akka.matching.actors.QueryHandler

import scala.collection.immutable.TreeMap

object Utils {

  implicit class ActorWithName(actor: ActorRef){
    /** Shortened invocation of the actor name */
    def name: String = actor.path.name
  }

  /** Checks if actor doesn't equal Actor.noSender */
  def senderExists(actor: ActorRef): Boolean = actor.name != "deadLetters"

  /** Prints QueryHandler response in readable form */
  def printBalances(balancesRespond: QueryHandler.AllBalancesResponse): Unit =
    println(
      s"\nClient balances:\n${
        balancesRespond
          .clientNames2Balances
          .map { case (clientName, balance) ⇒ s"$clientName: $balance" }.mkString("\n")
      }\n"
    )

  /**
    * Gets aggregate balance of the all clients (dollars and shares A, B, C, D)
    * @param balances    sequence of the client balances
    * @return  Tuple4 where the first element is dollar balance,
    *          second and so forth - shares A, B, C, D balances
    */
  def getAggregateBalance(balances: Seq[Balance]): (Int, Int, Int, Int, Int) =
    balances.foldLeft((0, 0, 0, 0, 0)){
      (acc, balance) ⇒
        (
          acc._1 + balance.dollars,
          acc._2 + balance.sharesBalance(A),
          acc._3 + balance.sharesBalance(B),
          acc._4 + balance.sharesBalance(C),
          acc._5 + balance.sharesBalance(D)
        )
    }

  /**
    * Checks if the aggregate clients balance have been remained invariant
    * @param    balancesBefore balances of the clients before processing
    * @param    balancesAfter balances of the clients after processing
    */
  def checkBalancesInvariant(balancesBefore: Seq[Balance], balancesAfter: Seq[Balance]): Boolean =
    getAggregateBalance(balancesBefore) == getAggregateBalance(balancesAfter)

  /** Saves client balances to file */
  def saveClientBalances(clientBalances: TreeMap[String, Balance], fileName: String = "Result.txt"): Unit = {

    val file = new File(fileName)
    val bufferedWriter = new BufferedWriter(new FileWriter(file))

    bufferedWriter.write(
      clientBalances
        .map {
          case (clientName, Balance(dollars, sharesBalance)) ⇒
            s"$clientName $dollars ${sharesBalance(A)} ${sharesBalance(B)} ${sharesBalance(C)} ${sharesBalance(D)}"
        }
        .mkString("\n")
      )

    bufferedWriter.close()
  }
}