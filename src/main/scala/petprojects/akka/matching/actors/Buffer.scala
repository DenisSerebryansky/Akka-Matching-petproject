package petprojects.akka.matching.actors

import akka.actor.{Actor, ActorRef, Props}
import petprojects.akka.matching.data.ExchangeRequest

import scala.collection.immutable.Queue

object Buffer {
  def props: Props = Props(new Buffer)

  case object BufferIsEmpty
  final case class DequeuedRequest(request: ExchangeRequest, requester: ActorRef)

  type Requester = ActorRef
}

/**
  * Actor of the Buffer. It's a child of the Exchange actor.
  *
  * Serves for enqueueing requests to the exchange when it cannot process them due to the processing
  * of another order or query.
  *
  * Can only be in the state `awaitingRequests`. This state keeps queue of requests and their requester
  */
class Buffer extends Actor {

  import Buffer._

  override def receive: Receive = awaitingRequests(Queue.empty[(ExchangeRequest, Requester)])

  def awaitingRequests(queue: Queue[(ExchangeRequest, Requester)]): Receive = {

    case Exchange.EnqueueExchangeRequest(request, requester) ⇒
      context.become(awaitingRequests(queue.enqueue(request → requester)))

    case Exchange.RequestToBuffer ⇒
      if (queue.nonEmpty) {
        val ((request, requester), newQueue) = queue.dequeue

        sender ! DequeuedRequest(request, requester)
        context.become(awaitingRequests(newQueue))
      }
      else sender ! BufferIsEmpty
  }
}