import akka.actor._
import scala.concurrent.ExecutionContext.Implicits.global

sealed trait Messages
case class StartTest() extends Messages
case class CommenceOperations() extends Messages
case class Shutdown() extends Messages
case class StopNow() extends Messages
case class Summation(numberOne: Int, numberTwo: Int) extends Messages

object TestingSupervision extends App {

  val system = ActorSystem("Sys") // actor system
  val supervisorMain = system.actorOf(Props[MainSupervisor], "supervisorMain") // top level supervisor, never dies by itself

  supervisorMain ! StartTest // begin testing

  // comment the next line out to make system stay alive forever - useful if you wish to see whether the top level supervisor stays alive even if all other actors die out
  supervisorMain ! Shutdown // terminate the system once all testing has finished

}

class MainSupervisor extends Actor with ActorLogging {

  import akka.actor.OneForOneStrategy
  import akka.actor.SupervisorStrategy._ // provides Resume/Restart/Stop/Escalate
  import scala.concurrent.duration._

  override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries = 10, withinTimeRange = Duration(1, "minute")) {
    case _: ArithmeticException      =>
      log.info("MainSupervisor: ArithmeticException has reached me, carry on"); Resume
    case _: NullPointerException     =>
      log.info("MainSupervisor: NullPointerException has reached me, restart the Supervisor"); Restart
    case _: IllegalArgumentException =>
      log.info("MainSupervisor: IllegalArgumentException has reached me, stop the Supervisor"); Stop
    case _: Exception                => 
      log.info("MainSupervisor: Exception has reached me, stop the Supervisor - Exception message will now be visible"); Stop
  }

  def receive = {

    case StartTest =>

      val supervisor = context.actorOf(Props[Supervisor], "supervisor") // sub-supervisor
      self ! supervisor // assign the sub-supervisor to the main supervisor

      supervisor ! CommenceOperations // start the real tests, see below

    case p: Props =>
      sender ! context.actorOf(p)

    case Shutdown =>
      // add 2 seconds delay to shutdown to allow all actor messages to reach their targets
      context.system.scheduler.scheduleOnce(Duration(2, "seconds")) {
        self ! StopNow // call the below case, system shutdown
      }

    case StopNow =>
      context.system.shutdown()

  }

  override def preStart() {
    log.info(s"MainSupervisor About to Start.")
    super.preStart() // carry out the procedure
  }

  override def postRestart(reason: Throwable) {
    val reasonStr = reason.getMessage
    log.info(s"MainSupervisor Restarted. Reason: $reasonStr")
    super.postRestart(reason) // carry out the procedure
  }

  override def postStop() {
    super.postStop() // carry out the procedure
    log.info(s"MainSupervisor Stopped.")
  }

}

class Supervisor extends Actor with ActorLogging {

  import akka.actor.OneForOneStrategy
  import akka.actor.SupervisorStrategy._ // provides Resume/Restart/Stop/Escalate
  import scala.concurrent.duration._

  // define strategy for handling issues
  override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries = 10, withinTimeRange = Duration(1, "minute")) {
    case _: ArithmeticException      =>
      log.info("Supervisor: No issues, Resuming child"); Resume
    case _: NullPointerException     =>
      log.info("Supervisor: Minor issues, Restarting child"); Restart
    case _: IllegalArgumentException =>
      log.info("Supervisor: Major issues, Stopping child forever, no Escalating"); Stop
    case _: Exception                => 
      log.info("Supervisor: Crysis! Child died - Escalating issue to MainSupervisor - exception will not be visible at this level"); Escalate
  }

  def receive = {

    case p: Props =>
      sender ! context.actorOf(p) // take control of the actor

    // comment/uncomment groups (2 lines at a time, pairs) to test out the different supervision strategy cases
    case CommenceOperations =>

      // normal operation
      val child = context.actorOf(Props[Child], "child") // spawn the child within current context
      self ! child // assign the new child to the supervisor

      child ! 42 // set state to 42
      child ! "get"

      // crash and force RESUME
      //child ! new ArithmeticException // crash it
      //child ! "get" // should still say 42

      // crash and force RESTART
      //child ! new NullPointerException // crash it harder
      //child ! "get" // should say 0, the default

      // crash and force STOP
      //child ! new IllegalArgumentException // crash it more
      //child ! "get" // message undelivered, this child died - see DeadLetters warning

      // crash and force ESCALATE to MainSupervisor
      child ! new Exception("CRASH") // and crash again
      child ! "get" // message undelivered, this child died - see DeadLetters warning

  }

  override def preStart() {
    log.info(s"Supervisor About to Start.")
    super.preStart() // carry out the procedure
  }

  override def postRestart(reason: Throwable) {
    val reasonStr = reason.getMessage
    log.info(s"Supervisor Restarted. Reason: $reasonStr")
    super.postRestart(reason) // carry out the procedure
  }

  override def postStop() {
    super.postStop() // carry out the procedure
    log.info(s"Supervisor Stopped. All children have been killed.")
  }

}

class Child extends Actor with ActorLogging {

  import akka.actor.OneForOneStrategy
  import akka.actor.SupervisorStrategy._ // provides Resume/Restart/Stop/Escalate
  import scala.concurrent.duration._
  
  override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries = 10, withinTimeRange = Duration(1, "minute")) {
    case _: IllegalArgumentException =>
      log.info("Child: Major issues, Stopping abacus forever, no Escalating"); Stop
    case _: Exception                => 
      log.info("Child: Crysis! Abacus exception thrown - Escalating issue to Supervisor - exception will not be visible at this level"); Escalate
  }

  var state = 0
  
  def receive = {
    
    case p: Props =>
      sender ! context.actorOf(p)

    case ex: Exception =>

      val msg = ex.getMessage
      log.info(s"Exception Thrown: <$msg>.")
      throw ex // carry out the action

    case x: Int =>

      state = x
      log.info(s"State is now <$state>.")

    case "get" =>

      log.info(s"Current State: <$state>")

  }

  override def preStart() {
    log.info(s"Child About to Start.")
    
    val abacus = context.actorOf(Props[Abacus], "abacus")

    self ! abacus
    abacus ! Summation(3, 10)
    
    super.preStart() // carry out the procedure
  }

  override def postRestart(reason: Throwable) {
    val reasonStr = reason.getMessage
    log.info(s"Child Restarted. Reason: $reasonStr")
    super.postRestart(reason) // carry out the procedure
  }

  override def postStop() {
    super.postStop() // carry out the procedure
    log.info(s"Child Stopped.")
  }

}

class Abacus extends Actor with ActorLogging {
  def receive = {

    case Summation(numberOne, numberTwo) =>
      if (numberOne == 0 || numberTwo == 0)
        throw new IllegalArgumentException
      else
        log.info("Abacus sum: " + (numberOne + numberTwo))
  }

  override def preStart() {
    log.info(s"Abacus About to Start.")
    super.preStart()
  }

  override def postRestart(reason: Throwable) {
    val reasonStr = reason.getMessage
    log.info(s"Abacus Restarted. Reason: $reasonStr")
    super.postRestart(reason)
  }

  override def postStop() {
    super.postStop()
    log.info(s"Abacus Stopped.")
  }
}