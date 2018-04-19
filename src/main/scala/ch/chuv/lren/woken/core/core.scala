/*
 * Copyright (C) 2017  LREN CHUV for Human Brain Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ch.chuv.lren.woken.core

import akka.actor.{ ActorRef, ActorSystem, DeadLetter }
import akka.cluster.Cluster
import akka.pattern.{ Backoff, BackoffSupervisor }
import akka.stream._
import cats.data.NonEmptyList
import com.typesafe.config.{ Config, ConfigFactory }
import ch.chuv.lren.woken.backends.chronos.ChronosThrottler
import ch.chuv.lren.woken.config.{ AppConfiguration, JobsConfiguration }
import ch.chuv.lren.woken.core.monitoring.DeadLetterMonitorActor
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._
import scala.language.postfixOps

/**
  * Core is type containing the ``system: ActorSystem`` member. This enables us to use it in our
  * apps as well as in our tests.
  */
trait Core {

  protected implicit def system: ActorSystem
  protected implicit def actorMaterializer: ActorMaterializer
  protected def cluster: Cluster

  protected def config: Config

  protected def mainRouter: ActorRef

  def beforeBoot(): Unit
  def startActors(): Unit
  def startServices(): Unit
  def selfChecks(): Unit

}

/**
  * This trait contains the actors that make up our application; it can be mixed in with
  * ``BootedCore`` for running code or ``TestKit`` for unit and integration tests.
  */
trait CoreActors {
  this: Core with LazyLogging =>

  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  protected def configurationFailed[B](e: NonEmptyList[String]): B =
    throw new IllegalStateException(s"Invalid configuration: ${e.toList.mkString(", ")}")

  // Order of loading:
  // 1. Akka configuration hard-coded for clustering (present here to avoid clashes with tests no using a cluster)
  // 2. Configuration of Akka pre-defined in akka.conf, configurable by environment variables
  // 3. Configuration of Kamon pre-defined in kamon.conf, configurable by environment variables
  // 4. Custom configuration defined in application.conf and backed by reference.conf from the libraries
  // 5. Default configuration for the algorithm library, it can be overriden in application.conf or individual versions
  //    for algorithms can be overriden by environment variables
  protected lazy val config: Config =
    ConfigFactory
      .parseString("""
        |akka {
        |  actor.provider = cluster
        |  extensions += "akka.cluster.pubsub.DistributedPubSub"
        |  extensions += "akka.cluster.client.ClusterClientReceptionist"
        |}
      """.stripMargin)
      .withFallback(ConfigFactory.parseResourcesAnySyntax("akka.conf"))
      .withFallback(ConfigFactory.parseResourcesAnySyntax("kamon.conf"))
      .withFallback(ConfigFactory.load())
      .withFallback(ConfigFactory.parseResourcesAnySyntax("algorithms.conf"))
      .resolve()

  protected lazy val appConfig: AppConfiguration = AppConfiguration
    .read(config)
    .valueOr(configurationFailed)

  protected lazy val jobsConfig: JobsConfiguration = JobsConfiguration
    .read(config)
    .valueOr(configurationFailed)

  val decider: Supervision.Decider = {
    case err: RuntimeException =>
      logger.error(err.getMessage, err)
      Supervision.Resume
    case err =>
      logger.error("Unknown error. Stopping the stream.", err)
      Supervision.Stop
  }

  /**
    * Construct the ActorSystem we will use in our application
    */
  override lazy implicit val system: ActorSystem = ActorSystem(appConfig.clusterSystemName, config)

  protected lazy implicit val actorMaterializer: ActorMaterializer = ActorMaterializer(
    ActorMaterializerSettings(system).withSupervisionStrategy(decider)
  )

  protected lazy implicit val executionContext: ExecutionContextExecutor = system.dispatcher

  lazy val chronosHttp: ActorRef = {
    val chronosSupervisorProps = BackoffSupervisor.props(
      Backoff.onFailure(
        ChronosThrottler.props(jobsConfig),
        childName = "chronosThrottler",
        minBackoff = 1 second,
        maxBackoff = 30 seconds,
        randomFactor = 0.2
      )
    )

    system.actorOf(chronosSupervisorProps, "chronosSupervisor")
  }

  def monitorDeadLetters(): Unit = {
    val deadLetterMonitorActor =
      system.actorOf(DeadLetterMonitorActor.props, name = "deadLetterMonitor")

    // Subscribe to system wide event bus 'DeadLetter'
    system.eventStream.subscribe(deadLetterMonitorActor, classOf[DeadLetter])
  }

}
