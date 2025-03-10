package zio.actors

import java.io.File
import java.net.ConnectException

import zio.{ durationInt, Clock, Console, IO, ZIO }
import zio.actors.Actor.Stateful
import zio.test._
import zio.test.Assertion._
import SpecUtils._

object SpecUtils {
  sealed trait Message[+A]
  case class Str(value: String) extends Message[String]

  sealed trait MyErrorDomain extends Throwable
  case object DomainError    extends MyErrorDomain

  val handlerMessageTrait = new Stateful[Any, Int, Message] {
    override def receive[A](
      state: Int,
      msg: Message[A],
      context: Context
    ): IO[MyErrorDomain, (Int, A)] =
      msg match {
        case Str(value) =>
          ZIO.succeed((state + 1, value + "received plus " + state + 1))
      }
  }

  sealed trait PingPongProto[+A]
  case class Ping(sender: ActorRef[PingPongProto])        extends PingPongProto[Unit]
  case object Pong                                        extends PingPongProto[Unit]
  case class GameInit(recipient: ActorRef[PingPongProto]) extends PingPongProto[Unit]

  val protoHandler = new Stateful[Any, Unit, PingPongProto] {
    override def receive[A](
      state: Unit,
      msg: PingPongProto[A],
      context: Context
    ): IO[Throwable, (Unit, A)] =
      msg match {
        case Ping(sender) =>
          (for {
            path <- sender.path
            _    <- Console.printLine(s"Ping from: $path, sending pong")
            _    <- sender ! Pong
          } yield ((), ())).asInstanceOf[IO[Throwable, (Unit, A)]]

        case Pong         =>
          (for {
            _ <- Console.printLine("Received pong")
            _ <- ZIO.succeed(1)
          } yield ((), ())).asInstanceOf[IO[Throwable, (Unit, A)]]

        case GameInit(to) =>
          (for {
            _    <- Console.printLine("The game starts...")
            self <- context.self[PingPongProto]
            _    <- to ! Ping(self)
          } yield ((), ())).asInstanceOf[IO[Throwable, (Unit, A)]]
      }
  }

  sealed trait ErrorProto[+A]
  case object UnsafeMessage extends ErrorProto[String]

  val errorHandler = new Stateful[Any, Unit, ErrorProto] {
    override def receive[A](
      state: Unit,
      msg: ErrorProto[A],
      context: Context
    ): IO[Throwable, (Unit, A)] =
      msg match {
        case UnsafeMessage => ZIO.fail(new Exception("Error on remote side"))
      }
  }

  val configFile = Some(new File("./actors/src/test/resources/application.conf"))
}

object RemoteSpec extends ZIOSpecDefault {
  def spec =
    suite("RemoteSpec")(
      suite("Remote communication suite")(
        test("Remote test send message") {
          for {
            actorSystemOne <- ActorSystem("testSystem11", configFile)
            _              <- actorSystemOne.make("actorOne", Supervisor.none, 0, handlerMessageTrait)
            actorSystemTwo <- ActorSystem("testSystem12", configFile)
            actorRef       <- actorSystemTwo.select[Message](
                                "zio://testSystem11@127.0.0.1:9665/actorOne"
                              )
            result         <- actorRef ? Str("ZIO-Actor response... ")
          } yield assertTrue(result == "ZIO-Actor response... received plus 01")
        },
        test("ActorRef serialization case") {
          for {
            actorSystemRoot <- ActorSystem("testSystem21", configFile)
            one             <- actorSystemRoot.make("actorOne", Supervisor.none, (), protoHandler)

            actorSystem <- ActorSystem("testSystem22", configFile)
            _           <- actorSystem.make("actorTwo", Supervisor.none, (), protoHandler)

            remoteActor <- actorSystemRoot.select[PingPongProto](
                             "zio://testSystem22@127.0.0.1:9668/actorTwo"
                           )

            _           <- one ! GameInit(remoteActor)

            _ <- Clock.sleep(2.seconds)

            outputVector <- TestConsole.output
          } yield assertTrue(
            outputVector.size == 3,
            outputVector(0) == "The game starts...\n",
            outputVector(1) == "Ping from: zio://testSystem21@127.0.0.1:9667/actorOne, sending pong\n",
            outputVector(2) == "Received pong\n"
          )
        }
      ),
      suite("Error handling suite")(
        test("ActorRef not found case (in local actor system)") {
          val program = for {
            actorSystem <- ActorSystem("testSystem31", configFile)
            _           <- actorSystem.select[PingPongProto]("zio://testSystem31@127.0.0.1:9669/actorTwo")
          } yield ()

          assertZIO(program.exit)(
            fails(isSubtype[Throwable](anything)) &&
              fails(
                hasField[Throwable, String](
                  "message",
                  _.getMessage,
                  equalTo("No such actor /actorTwo in local ActorSystem.")
                )
              )
          )
        },
        test("Remote system does not exist") {
          val program = for {
            actorSystem <- ActorSystem("testSystem41", configFile)
            actorRef    <- actorSystem.select[PingPongProto](
                             "zio://testSystem42@127.0.0.1:9672/actorTwo"
                           )
            _           <- actorRef ! GameInit(actorRef)
          } yield ()

          assertZIO(program.exit)(
            fails(isSubtype[ConnectException](anything)) &&
              fails(hasField[Throwable, String]("message", _.getMessage, equalTo("Connection refused")))
          )
        },
        test("Remote actor does not exist") {
          val program = for {
            actorSystemOne <- ActorSystem("testSystem51", configFile)
            _              <- ActorSystem("testSystem52", configFile)
            actorRef       <- actorSystemOne.select[PingPongProto](
                                "zio://testSystem52@127.0.0.1:9674/actorTwo"
                              )
            _              <- actorRef ? GameInit(actorRef)
          } yield ()

          assertZIO(program.exit)(
            fails(isSubtype[Throwable](anything)) &&
              fails(hasField[Throwable, String]("message", _.getMessage, equalTo("No such remote actor")))
          )
        },
        test("On remote side error message processing error") {
          val program = for {
            actorSystemOne <- ActorSystem("testSystem61", configFile)
            _              <- actorSystemOne.make("actorOne", Supervisor.none, (), errorHandler)
            actorSystemTwo <- ActorSystem("testSystem62", configFile)
            actorRef       <- actorSystemTwo.select[ErrorProto](
                                "zio://testSystem61@127.0.0.1:9675/actorOne"
                              )
            _              <- actorRef ? UnsafeMessage
          } yield ()

          assertZIO(program.exit)(
            fails(isSubtype[Throwable](anything)) &&
              fails(hasField[Throwable, String]("message", _.getMessage, equalTo("Error on remote side")))
          )
        },
        test("remote test select actor with special symbols") {
          for {
            actorSystemOne <- ActorSystem("testSystem71", configFile)
            _              <- actorSystemOne.make("actor-One-;_&", Supervisor.none, 0, handlerMessageTrait)
            actorSystemTwo <- ActorSystem("testSystem72", configFile)
            actorRef       <- actorSystemTwo.select[Message](
                                "zio://testSystem71@127.0.0.1:9677/actor-One-;_&"
                              )
            result         <- actorRef ? Str("ZIO-Actor response... ")
          } yield assertTrue(result == "ZIO-Actor response... received plus 01")
        }
      )
    ) @@ TestAspect.withLiveClock @@ TestAspect.silent
}
