package akka

import akka.actor.Actor
import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import akka.actor.Props

// create an object based on the Receiver Class and run it straight away
object Receiver {
	
	def main(args: Array[String]): Unit = {
			
		// load receiver.conf
		val system = ActorSystem("Sys", ConfigFactory.load("receiver"))
		
		// add this actor to Props
		system.actorOf(Props[Receiver], "rcv")
  
	}

}

class Receiver extends Actor {
	
	import Sender._
	
	var messageCount = 0

	def receive = {
    	
		// sender() => Actor that sent this latest message
		case m: Echo	=> 
		  messageCount += 1
		  self ! Print(m.toString())
		  sender ! m
		
		case Print(msg) => println(msg + "\n")
		
		// shutdown the ActorSystem
		case Shutdown	=> 
		  self ! Print(messageCount.toString())
		  context.system.shutdown()
		
		// anything else, do nothing
		case _			=>
  
	}

}