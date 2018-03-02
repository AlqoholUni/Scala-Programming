/**
 * Flow:
 * 		calculate()
 *   		=> 	create listener
 *     		=> 	create master
 *       		=> Calculate()
 *       			=>	Work()
 *       					=>
 *         						worker1..N
 *         					<=
 *             		<=	Result()
 * 			=>	master
 *       		=>	PiApproximation()
 *         			=>	listener
 *  		=> shutdown
 * */

import akka.actor._
import akka.routing.RoundRobinPool

// Calculate value of Pi using Leibniz formula for π
object Pi extends App {
	
  println("Creating PI object")
	// start calculation program
	// change these starting values and observe the program output changing
	// noOfWorkers 	- No. Minions (Workers) to use
	// noOfElements	- No. of sum elements to use in calculation
	// nrOfMessages	- No. of splits of the nrOfElements, each split assigned to an available Minion
	calculate(nrOfWorkers = 5, nrOfElements = 10000, nrOfMessages = 10)
	
	// PiMessage trait - acts as a sealed class
	sealed trait PiMessage
	// PiMessage Calculate object
  	case object Calculate
  	// PiMessage cases
  	case class Work(start: Int, nrOfElements: Int)
  	case class Result(value: Double)
  	// PiApproximation case
  	case class PiApproximation(pi: Double, duration: Long)
  	
  	// worker Actor class
  	class Worker extends Actor {
		
		def calculatePiFor(start: Int, nrOfElements: Int): Double = {
		  
			var workerID = self.toString().replace("Actor[akka://PiSystem/user/master/workerRouter/", "").replace("]", "")
			println("Worker<" + workerID + "> Starting Job..")
			
			var acc = 0.0
			
			val startTime = System.currentTimeMillis
			
			// Java: for (i = start; i < start + noOfElements; i++)
			for (i <- start until (start + nrOfElements))
				acc += 4.0 * (1 - (i % 2) * 2) / (2 * i + 1)
				
			val totalMillis = (System.currentTimeMillis - startTime)
				
			// implicit return - Double
			acc
			
		}
		
		// Actor receive method implementation
		def receive = {
			
			// receive work
			case Work(start, nrOfElements) =>
				// perform the work
				// ! = send message from case class Result
				sender ! 
					Result(
						calculatePiFor(start, nrOfElements)
					)				
		}
	
	}
	
	// master Actor class
	class Master (nrOfWorkers: Int, nrOfMessages: Int, nrOfElements: Int, listener: ActorRef)
			extends Actor {
		
	  println("Creating Master")
		// mutable variables - _ means initialisation with default value
		// all variables need to be initialised before use
		// this only works with "var", because "val" cannot be changed
		var pi: Double = _
		var nrOfResults: Int = _
		
		// immutable variable
		val start: Long = System.currentTimeMillis
		
		// implicit use of akka.actor.ActorContext - start new worker router
		val workerRouter = context.actorOf(
			Props[Worker].withRouter(
				RoundRobinPool(
					nrOfWorkers
				)
			),
			name = "workerRouter"
		)
		
		// Actor receive method implementation
		def receive = {
			
			// calculate job - send Work signals through the workerRouter to all its Workers
			case Calculate =>
				
				//for (i <- 0 to nrOfMessages - 1 by 1) workerRouter ! Work(i * nrOfElements, nrOfElements)
				for (i <- nrOfMessages - 1 to 0 by -1) workerRouter ! Work(i * nrOfElements, nrOfElements)
			
			// result 
			case Result(value) =>
			  			
				val printTurn = if (nrOfResults % 2 == 0) println("Odd Turn!") else println("Even Turn!")
				pi += value
			
				// increase number of results
				nrOfResults += 1
				
				// if number of results is the same as the number of messages issued
				if (nrOfResults == nrOfMessages) {
					
					// calculate duration
					val duration = (System.currentTimeMillis - start)
					
					println("Printing Results...")
					
					// Send the result to the Listener through the PiApproximation case class
					listener ! PiApproximation(pi, duration)
					
				}
				
    	}
 
	}
	
	// slave listener Actor
	class Listener extends Actor {
		
	  println("Creating Listener")
		// Actor receive method implementation
		def receive = {
			
			// display result received through the PiApproximation case class
			case PiApproximation(pi, duration) =>
				
				println("\n\tActual Value of Pi: \t\t\t3.1415926535897932")
				
				println(
					"\tPi approximation from this program: \t%s\n\tCalculation time: \t\t\t%s millis"
					.format(pi, duration)
				)
			
				// shut down the Actor system
				context.system.shutdown()
			
		}
		
	}
 
	// starting point
	def calculate(nrOfWorkers: Int, nrOfElements: Int, nrOfMessages: Int) {
    
	  println("Starting Job")
		// Create an Akka system
		val system = ActorSystem("PiSystem")
 
		// create the result listener, which will print the result and shutdown the system
		val listener = system.actorOf(Props[Listener], name = "listener")
 
		// create the master
		val master = system.actorOf(
			Props(
				new Master(
					nrOfWorkers, nrOfMessages, nrOfElements, listener
				)
			),
			name = "master"
		)
 
		// start the calculation
		master ! Calculate
 
	}
	
}
