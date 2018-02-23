package akka

import scala.concurrent.duration._
import akka.actor.Actor
import akka.actor.ActorIdentity
import akka.actor.ActorRef
import akka.actor.Identify
import akka.actor.ReceiveTimeout
import akka.actor.Terminated
import akka.actor.Props
import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory

// actor object based on the Sender Class
object Sender {
	
	//initial setup
	def main(args: Array[String]): Unit = {
    
		// load sender.conf
		val system = ActorSystem("Sys", ConfigFactory.load("sender"))
		// load receiver.conf
		val receiverConf = ConfigFactory.load("receiver")
		// load testData.conf
		val testDataConf = ConfigFactory.load("testData")
				
		// actor default settings, if not specified through command line
		val remoteHostPort = if (args.length >= 1) args(0) // Receiver network location
			else ( 
				(receiverConf getString("akka.remote.netty.tcp.hostname")) + 
				":" +
				(receiverConf getString("akka.remote.netty.tcp.port"))
			) 
		// string definition so that any variables ($VARNAME) get replaced by their values
		val remotePath = s"akka.tcp://Sys@$remoteHostPort/user/rcv" // Receiver ActorSystem location
		val totalMessages = if (args.length >= 2) args(1).toInt // number of test messages to send
			else (testDataConf getInt("test.totalMessages")) 
		val burstSize = if (args.length >= 3) args(2).toInt // with this batch size 
			else (testDataConf getInt("test.burstSize")) 
		val payloadSize = if (args.length >= 4) args(3).toInt // each message having this many Bytes 
			else (testDataConf getInt("test.payloadSize")) 
			
		val config = Array( remoteHostPort, remotePath, totalMessages, burstSize, payloadSize );
		for (i <- 0 to config.length - 1)
			println(config(i))
		
		// add actor to Props while passing it the above settings
		system.actorOf(Sender.props(remotePath, totalMessages, burstSize, payloadSize), "snd")
  
	}
	
	// implement a custom `props` method to deal with generating the Sender actor Props definition
	def props(path: String, totalMessages: Int, burstSize: Int, payloadSize: Int): Props =
			Props(new Sender(path, totalMessages, burstSize, payloadSize))
	
	// cases / messages
	private case object Warmup
	case object Shutdown
	case class Print(msg: String)
	sealed trait Echo
	case object Start extends Echo
	case object Done extends Echo
	case class Continue(remaining: Int, startTime: Long, burstStartTime: Long, n: Int) extends Echo

}

class Sender(path: String, totalMessages: Int, burstSize: Int, payloadSize: Int) extends Actor {
  
	import Sender._

	// payload - vector (type of array) filled with Bytes of data
	val payload: Array[Byte] = Vector.fill(payloadSize)("a").mkString.getBytes
	var startTime = 0L
	var maxRoundTripMillis = 0L
	
	
	context.setReceiveTimeout(3 seconds) // set Receiver search timeout
	sendIdentifyRequest() // have the Receiver search path be printed out

	def sendIdentifyRequest(): Unit = context.actorSelection(path) ! Identify(path)

	def receive = identifying

	// initial receive method while trying to establish connection to Receiver
	def identifying: Receive = {
		
		// if Receiver actor identity is known
		case ActorIdentity(`path`, Some(actor)) =>
			context.watch(actor) // have the ActorSystem watch this actor
			context.become(active(actor)) // and enable the `active` receive method, replacing current one
			context.setReceiveTimeout(Duration.Undefined) // reset the search timeout
			self ! Warmup // send Warmup message to self - found in the `active` receive method
		
		// unknown Receiver path, not yet started
		case ActorIdentity(`path`, None)	=> println(s"Remote actor not available: $path")
		
		// display the Receiver search request 
		case ReceiveTimeout					=> sendIdentifyRequest()
	}

	def active(actor: ActorRef): Receive = {
    
		case Warmup =>
			// start batch test messages
			sendBatch(actor, burstSize)
			actor ! Start // start sending messages
		
		case Start =>
			println(s"Starting benchmark of $totalMessages messages with burst size $burstSize and payload size $payloadSize")
			startTime = System.nanoTime
			
			val remaining = sendBatch(actor, totalMessages)
			
			// stop if all messages have been sent on the first go
			if (remaining == 0)
				actor ! Done
			// otherwise carry on by having the Continue case run recursively (call itself over and over until done)
			else
				actor ! Continue(remaining, startTime, startTime, burstSize)

		case c @ Continue(remaining, t0, t1, n) =>
			val now = System.nanoTime
			val duration = (now - t0).nanos.toMillis
			val roundTripMillis = (now - t1).nanos.toMillis
			
			maxRoundTripMillis = math.max(maxRoundTripMillis, roundTripMillis)
			
			// every 500ms print status
			if (duration >= 500) {
				
				val throughtput = (n * 1000.0 / duration).toInt
				
				println(s"It took $duration ms to deliver $n messages, throughtput $throughtput msg/s, " +
							s"latest round-trip $roundTripMillis ms, remaining $remaining of $totalMessages")
			}

			// send next batch
			val nextRemaining = sendBatch(actor, remaining)
			
			// check if any messages still remain, and stop if none
			if (nextRemaining == 0)
				actor ! Done
			// otherwise if 500ms have passed, run Continue again
			else if (duration >= 500)
				actor ! Continue(nextRemaining, now, now, burstSize)
			// otherwise create a copy of Continue and trigger it again before printing the status
			else
				actor ! c.copy(remaining = nextRemaining, burstStartTime = now, n = n + burstSize)
		
		// finished sending all messages
		case Done =>
			val took = (System.nanoTime - startTime).nanos.toMillis
			val throughtput = (totalMessages * 1000.0 / took).toInt
			
			// display summary
			println(s"== It took $took ms to deliver $totalMessages messages, throughtput $throughtput msg/s, " +
						s"max round-trip $maxRoundTripMillis ms, burst size $burstSize, " +
						s"payload size $payloadSize")
			
			// stop Receiver local ActorSystem
			actor ! Shutdown

		case Terminated(`actor`) =>
			println("Receiver terminated")
			// stop local ActorSystem
			context.system.shutdown()

	}

	/**
	 * @return remaining messages after sending the batch
	 */
	def sendBatch(actor: ActorRef, remaining: Int): Int = {
		
		val batchSize = math.min(remaining, burstSize) // check how many messages need to be sent currently
		
		(1 to batchSize) foreach { x => actor ! payload } // send them each in turn
		
		remaining - batchSize // return remaining messages
  
	}

}

