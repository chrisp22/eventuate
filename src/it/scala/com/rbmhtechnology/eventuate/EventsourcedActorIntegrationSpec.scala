/*
 * Copyright (C) 2015 Red Bull Media House GmbH <http://www.redbullmediahouse.com> - all rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.rbmhtechnology.eventuate

import scala.util._

import akka.actor._
import akka.testkit._

import com.rbmhtechnology.eventuate.log.EventLogSupport
import com.typesafe.config.ConfigFactory

import org.scalatest._

object EventsourcedActorIntegrationSpec {
  val config = ConfigFactory.parseString("log.leveldb.dir = target/test")

  case class Cmd(payloads: String*)

  class SampleActor(val replicaId: String, val eventLog: ActorRef, probe: ActorRef) extends EventsourcedActor {
    override val onCommand: Receive = {
      case "reply-success" => persist("okay") {
        case Success(r) => sender() ! r
        case Failure(_) => sender() ! "unexpected failure"
      }
      case "reply-failure" => persist("boom") {
        case Success(_) => sender() ! "unexpected success"
        case Failure(e) => sender() ! e.getMessage
      }
      case "boom" =>
        throw boom
      case Cmd(ps @ _*) =>
        ps.foreach { s =>
          persist(s) {
            case Success(r) => sender() ! r
            case Failure(e) => sender() ! e.getMessage
          }
        }
    }

    override val onEvent: Receive = {
      case evt: String => probe ! evt
    }
  }
  
  class AccActor(val replicaId: String, val eventLog: ActorRef, probe: ActorRef) extends EventsourcedActor {
    var acc: Vector[String] = Vector.empty

    override val onCommand: Receive = {
      case "get-acc" => sender() ! acc
      case s: String => persist(s)(r => onEvent(r.get))
    }

    override val onEvent: Receive = {
      case s: String =>
        acc = acc :+ s
        if (acc.size == 4) probe ! acc
    }
  }

  class ConfirmedDeliveryActor(val replicaId: String, val eventLog: ActorRef, probe: ActorRef) extends EventsourcedActor with ConfirmedDelivery {
    override val onCommand: Receive = {
      case "boom" => throw boom
      case "end" => probe ! "end"
      case "cmd-1" => persist("evt-1")(_ => probe ! "out-1")
      case "cmd-2" => persist("evt-2")(r => onEvent(r.get))
      case "cmd-2-confirm" => persist("evt-2-confirm")(r => onEvent(r.get))
    }

    override val onEvent: Receive = {
      case "evt-2" => deliver("2", "out-2", probe.path)
      case "evt-2-confirm" => confirm("2")
    }
  }

  class DelayActor(val replicaId: String, val eventLog: ActorRef, probe: ActorRef) extends EventsourcedActor {
    override def stateSync: Boolean = false

    override val onCommand: Receive = {
      case "persist" => persist("a")(r => probe ! r.get)
      case "delay" => delay("b")(r => probe ! r)
    }

    override val onEvent: Receive = {
      case _ =>
    }
  }

  class ConditionalActor(val replicaId: String, val eventLog: ActorRef, probe: ActorRef) extends EventsourcedActor {
    override val onCommand: Receive = {
      case "persist"      => persist("a")(r => probe ! r.get)
      case "persist-mute" => persist("a")(_ => ())
      case other          => probe ! other
    }

    override val onEvent: Receive = {
      case "a" =>
    }
  }

  class ConditionalView(val eventLog: ActorRef, probe: ActorRef) extends EventsourcedView {
    override val onCommand: Receive = {
      case other => probe ! other
    }

    override val onEvent: Receive = {
      case "a" =>
    }
  }

  case class CollabCmd(to: String)
  case class CollabEvt(to: String, from: String)

  class CollabActor(val replicaId: String, val eventLog: ActorRef, probe: ActorRef) extends EventsourcedActor {
    var initiated = false

    override val onCommand: Receive = {
      case CollabCmd(to) =>
        persist(CollabEvt(to, replicaId))(_ => ())
        initiated = true
    }

    override val onEvent: Receive = {
      case evt @ CollabEvt(`replicaId`, from) =>
        if (initiated) probe ! lastVectorTimestamp else self ! CollabCmd(from)
    }
  }
}

import EventsourcedActorIntegrationSpec._

class EventsourcedActorIntegrationSpec extends TestKit(ActorSystem("test", config)) with WordSpecLike with Matchers with EventLogSupport {
  var probe: TestProbe = _

  override def beforeEach(): Unit = {
    super.beforeEach()
    probe = TestProbe()
  }
  
  "An EventsourcedActor" can {
    "preserve the command sender when invoking the persist handler on success" in {
      val actor = system.actorOf(Props(new SampleActor("1", log, probe.ref)))
      actor.tell("reply-success", probe.ref)
      probe.expectMsg("okay")
    }
    "preserve the command sender when invoking the persist handler on failure" in {
      val actor = system.actorOf(Props(new SampleActor("1", log, probe.ref)))
      actor.tell("reply-failure", probe.ref)
      probe.expectMsg("boom")
    }
    "persist multiple events per command as atomic batch" in {
      val actor = system.actorOf(Props(new SampleActor("1", log, probe.ref)))
      actor.tell(Cmd("a", "boom", "c"), probe.ref)
      probe.expectMsg("boom")
      probe.expectMsg("boom")
      probe.expectMsg("boom")
      actor.tell(Cmd("x", "y"), probe.ref)
      probe.expectMsg("x")
      probe.expectMsg("y")
      actor ! "boom"
      probe.expectMsg("x")
      probe.expectMsg("y")
    }
    "consume events from other actors (via event log)" in {
      val probe1 = TestProbe()
      val probe2 = TestProbe()
      val probe3 = TestProbe()

      val actor1 = system.actorOf(Props(new AccActor("1", log, probe1.ref)))
      val actor2 = system.actorOf(Props(new AccActor("2", log, probe2.ref)))
      val actor3 = system.actorOf(Props(new AccActor("3", log, probe3.ref)))

      actor1 ! "a"
      actor2 ! "b"
      actor1 ! "boom"
      actor1 ! "c"
      actor3 ! "d"

      val r1 = probe1.expectMsgClass(classOf[Vector[String]])
      val r2 = probe2.expectMsgClass(classOf[Vector[String]])
      val r3 = probe3.expectMsgClass(classOf[Vector[String]])

      val expected = Vector("a", "b", "c", "d")

      // check content
      r1.sorted should be(expected)
      r2.sorted should be(expected)
      r3.sorted should be(expected)

      // check ordering
      r1 should be(r2)
      r1 should be(r3)
    }
    "produce commands to other actors (at-most-once)" in {
      val actor = system.actorOf(Props(new ConfirmedDeliveryActor("1", log, probe.ref)))
      actor ! "cmd-1"
      probe.expectMsg("out-1")
      actor ! "boom"
      actor ! "end"
      probe.expectMsg("end")
    }
    "produce commands to other actors (at-least-once)" in {
      val actor = system.actorOf(Props(new ConfirmedDeliveryActor("1", log, probe.ref)))
      actor ! "cmd-2"
      probe.expectMsg("out-2")
      actor ! "boom"
      actor ! "end"
      probe.expectMsg("out-2")
      probe.expectMsg("end")
      actor ! "cmd-2-confirm"
      actor ! "boom"
      actor ! "end"
      probe.expectMsg("end")
    }
    "delay commands" in {
      val actor = system.actorOf(Props(new DelayActor("1", log, probe.ref)))
      actor ! "persist"
      actor ! "delay"
      actor ! "persist"
      probe.expectMsg("a")
      probe.expectMsg("b")
      probe.expectMsg("a")
    }
  }

  "An EventsourcedActor's vector clock size" must {
    "scale with the number of its collaboration partners" in {
      val probeAB = TestProbe()
      val probeCD = TestProbe()

      val actorA = system.actorOf(Props(new CollabActor("A", log, probeAB.ref)))
      val actorC = system.actorOf(Props(new CollabActor("C", log, probeCD.ref)))

      val actorB = system.actorOf(Props(new CollabActor("B", log, system.deadLetters)))
      val actorD = system.actorOf(Props(new CollabActor("D", log, system.deadLetters)))

      actorA ! CollabCmd("B")
      actorC ! CollabCmd("D")

      probeAB.expectMsg(VectorTime("A" -> 1, "B" -> 2))
      probeCD.expectMsg(VectorTime("C" -> 1, "D" -> 2))
    }
  }

  "Eventsourced actors and views" must {
    "support conditional command processing" in {
      val viewProps = Props(new ConditionalView(log, probe.ref))
      val act1Props = Props(new ConditionalActor("1", log, probe.ref))
      val act2Props = Props(new ConditionalActor("2", log, probe.ref))

      val view = system.actorOf(viewProps, "view")
      val act1 = system.actorOf(act1Props, "act1")
      val act2 = system.actorOf(act2Props, "act2")

      val condition = VectorTime("1" -> 3L)

      view ! ConditionalCommand(condition, "delayed")
      act1 ! ConditionalCommand(condition, "delayed-1")
      act2 ! ConditionalCommand(condition, "delayed-2")

      act1 ! "persist"
      act1 ! "persist"
      act1 ! "persist-mute"

      probe.expectMsg("a")
      probe.expectMsg("a")
      probe.expectMsgAllOf("delayed-1", "delayed-2", "delayed")

      // make sure that conditions are also met after recovery
      system.actorOf(viewProps) ! ConditionalCommand(condition, "delayed")
      system.actorOf(act1Props) ! ConditionalCommand(condition, "delayed-1")
      system.actorOf(act2Props) ! ConditionalCommand(condition, "delayed-2")

      probe.expectMsgAllOf("delayed-1", "delayed-2", "delayed")
    }
  }
}
