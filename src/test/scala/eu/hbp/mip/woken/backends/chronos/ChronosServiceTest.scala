/*
 * Copyright 2017 Human Brain Project MIP by LREN CHUV
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.hbp.mip.woken.backends.chronos

import akka.actor.{ ActorRef, ActorSystem }
import akka.testkit.{ ImplicitSender, TestKit, TestProbe }
import eu.hbp.mip.woken.backends.chronos.ChronosService.{ Error, Schedule }
import eu.hbp.mip.woken.core.{ Core, CoreActors }
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpecLike }
import eu.hbp.mip.woken.backends.chronos.{ EnvironmentVariable => EV, Parameter => P }

import scala.concurrent.duration._
import scala.language.postfixOps

class ChronosServiceTest
    extends TestKit(ActorSystem("ChronosServiceSpec"))
    with ImplicitSender
    with WordSpecLike
    with Matchers
    with BeforeAndAfterAll
    with Core
    with CoreActors {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  "Chronos Service" must {

    val container = Container(`type` = ContainerType.DOCKER,
                              image = "hbpmip/somealgo",
                              network = NetworkMode.BRIDGE,
                              parameters = List(P("network", "bridge1")))

    val environmentVariables: List[EV] =
      List(EV("JOB_ID", "12345"), EV("NODE", "local"), EV("DOCKER_IMAGE", "hbpmip/somealgo"))

    val job = ChronosJob(
      name = "hbpmip_somealgo_1",
      command = "compute",
      shell = false,
      schedule = "R1//PT24H",
      epsilon = Some("PT5M"),
      runAsUser = Some("root"),
      container = Some(container),
      cpus = Some(0.5),
      mem = Some(512),
      owner = Some("mip@chuv.ch"),
      environmentVariables = environmentVariables,
      retries = 0
    )

    "Schedule a new job" in {
      chronosHttp ! Schedule(job)

      within(40 seconds) {
        val msg = expectMsgType[Error](5 seconds)
        println(msg)
      }
    }

  }

  override protected def mainRouter: ActorRef = ???
}