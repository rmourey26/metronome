package io.iohk.metronome.networking

import cats.data.NonEmptyList
import cats.effect.concurrent.Ref
import cats.effect.{Concurrent, ContextShift, Resource, Sync, Timer}
import io.circe.{Encoder, Json, JsonObject}
import io.iohk.metronome.crypto.{ECKeyPair, ECPublicKey}
import io.iohk.metronome.networking.ConnectionHandler.MessageReceived
import io.iohk.metronome.networking.RemoteConnectionManager.{
  ClusterConfig,
  RetryConfig
}
import io.iohk.metronome.networking.RemoteConnectionManagerTestUtils._
import io.iohk.metronome.networking.RemoteConnectionManagerWithScalanetProviderSpec.{
  Cluster,
  buildTestConnectionManager
}
import io.iohk.metronome.logging.{HybridLog, HybridLogObject, LogTracer}
import io.iohk.scalanet.peergroup.dynamictls.DynamicTLSPeerGroup.FramingConfig
import io.iohk.scalanet.peergroup.PeerGroup

import java.net.InetSocketAddress
import java.security.SecureRandom
import monix.eval.{Task, TaskLift, TaskLike}
import monix.execution.Scheduler
import monix.execution.UncaughtExceptionReporter
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._
import scodec.Codec

class RemoteConnectionManagerWithScalanetProviderSpec
    extends AsyncFlatSpecLike
    with Matchers {
  import RemoteConnectionManagerWithScalanetProviderSpec.ecPublicKeyEncoder

  implicit val testScheduler =
    Scheduler.fixedPool(
      "RemoteConnectionManagerSpec",
      16,
      reporter = UncaughtExceptionReporter {
        case ex: IllegalStateException
            if ex.getMessage.contains("executor not accepting a task") =>
        case _: PeerGroup.ChannelBrokenException[_] =>
        // Probably test already closed with some task running in the background.
        case ex =>
          UncaughtExceptionReporter.default.reportFailure(ex)
      }
    )

  implicit val timeOut = 10.seconds

  behavior of "RemoteConnectionManagerWithScalanetProvider"

  it should "start connectionManager without any connections" in customTestCaseResourceT(
    buildTestConnectionManager[Task, ECPublicKey, TestMessage]()
  ) { connectionManager =>
    for {
      connections <- connectionManager.getAcquiredConnections
    } yield assert(connections.isEmpty)
  }

  it should "build fully connected cluster of 3 nodes" in customTestCaseResourceT(
    Cluster.buildCluster(3)
  ) { cluster =>
    for {
      size          <- cluster.clusterSize
      eachNodeCount <- cluster.getEachNodeConnectionsCount
    } yield {
      Inspectors.forAll(eachNodeCount)(count => count shouldEqual 2)
      size shouldEqual 3
    }
  }

  it should "build fully connected cluster of 4 nodes" in customTestCaseResourceT(
    Cluster.buildCluster(4)
  ) { cluster =>
    for {
      size          <- cluster.clusterSize
      eachNodeCount <- cluster.getEachNodeConnectionsCount
    } yield {
      Inspectors.forAll(eachNodeCount)(count => count shouldEqual 3)
      size shouldEqual 4
    }
  }

  it should "send and receive messages with other nodes in cluster" in customTestCaseResourceT(
    Cluster.buildCluster(3)
  ) { cluster =>
    for {
      eachNodeCount <- cluster.getEachNodeConnectionsCount
      sendResult    <- cluster.sendMessageFromRandomNodeToAllOthers(MessageA(1))
      (sender, receivers) = sendResult
      received <- Task.traverse(receivers.toList)(receiver =>
        cluster.getMessageFromNode(receiver)
      )
    } yield {
      Inspectors.forAll(eachNodeCount)(count => count shouldEqual 2)
      receivers.size shouldEqual 2
      received.size shouldEqual 2
      //every node should have received the same message
      Inspectors.forAll(received) { receivedMessage =>
        receivedMessage shouldBe MessageReceived(sender, MessageA(1))
      }
    }
  }

  it should "eventually reconnect to offline node" in customTestCaseResourceT(
    Cluster.buildCluster(3)
  ) { cluster =>
    for {
      size   <- cluster.clusterSize
      killed <- cluster.shutdownRandomNode
      _      <- cluster.sendMessageFromRandomNodeToAllOthers(MessageA(1))
      (address, keyPair, clusterConfig) = killed
      _ <- cluster.waitUntilEveryNodeHaveNConnections(1)
      // be offline for a moment
      _                      <- Task.sleep(3.seconds)
      connectionAfterFailure <- cluster.getEachNodeConnectionsCount
      _                      <- cluster.startNode(address, keyPair, clusterConfig)
      _                      <- cluster.waitUntilEveryNodeHaveNConnections(2)
    } yield {
      size shouldEqual 3
      Inspectors.forAll(connectionAfterFailure) { connections =>
        connections shouldEqual 1
      }
    }
  }
}
object RemoteConnectionManagerWithScalanetProviderSpec {
  val secureRandom = new SecureRandom()
  val standardFraming =
    FramingConfig.buildStandardFrameConfig(1000000, 4).getOrElse(null)
  val testIncomingQueueSize = 20

  implicit val ecPublicKeyEncoder: Encoder[ECPublicKey] =
    Encoder.instance(key => Json.fromString(key.bytes.toHex))

  // Just an example of setting up logging.
  implicit def tracers[F[_]: Sync, K: io.circe.Encoder, M]
      : NetworkTracers[F, K, M] = {
    import io.circe.syntax._
    import NetworkEvent._

    implicit val peerEncoder: Encoder.AsObject[Peer[K]] =
      Encoder.AsObject.instance { case Peer(key, address) =>
        JsonObject("key" -> key.asJson, "address" -> address.toString.asJson)
      }

    implicit val hybridLog: HybridLog[NetworkEvent[K, M]] =
      HybridLog.instance[NetworkEvent[K, M]](
        level = _ => HybridLogObject.Level.Debug,
        message = _.getClass.getSimpleName,
        event = {
          case e: ConnectionUnknown[_]      => e.peer.asJsonObject
          case e: ConnectionRegistered[_]   => e.peer.asJsonObject
          case e: ConnectionDeregistered[_] => e.peer.asJsonObject
          case e: ConnectionDiscarded[_]    => e.peer.asJsonObject
          case e: ConnectionSendError[_]    => e.peer.asJsonObject
          case e: ConnectionFailed[_] =>
            e.peer.asJsonObject.add("error", e.error.toString.asJson)
          case e: ConnectionReceiveError[_] =>
            e.peer.asJsonObject.add("error", e.error.toString.asJson)
          case e: NetworkEvent.MessageReceived[_, _] => e.peer.asJsonObject
          case e: NetworkEvent.MessageSent[_, _]     => e.peer.asJsonObject
        }
      )

    NetworkTracers(LogTracer.hybrid[F, NetworkEvent[K, M]])
  }

  def buildTestConnectionManager[
      F[_]: Concurrent: TaskLift: TaskLike: Timer,
      K: Codec: Encoder,
      M: Codec
  ](
      bindAddress: InetSocketAddress = randomAddress(),
      nodeKeyPair: ECKeyPair = ECKeyPair.generate(secureRandom),
      secureRandom: SecureRandom = secureRandom,
      useNativeTlsImplementation: Boolean = false,
      framingConfig: FramingConfig = standardFraming,
      maxIncomingQueueSizePerPeer: Int = testIncomingQueueSize,
      clusterConfig: ClusterConfig[K] = ClusterConfig(
        Set.empty[(K, InetSocketAddress)]
      ),
      retryConfig: RetryConfig = RetryConfig.default
  )(implicit
      s: Scheduler,
      cs: ContextShift[F]
  ): Resource[F, RemoteConnectionManager[F, K, M]] = {
    ScalanetConnectionProvider
      .scalanetProvider[F, K, M](
        bindAddress,
        nodeKeyPair,
        secureRandom,
        useNativeTlsImplementation,
        framingConfig,
        maxIncomingQueueSizePerPeer
      )
      .flatMap(prov =>
        RemoteConnectionManager(prov, clusterConfig, retryConfig)
      )
  }

  type ClusterNodes = Map[
    ECPublicKey,
    (
        RemoteConnectionManager[Task, ECPublicKey, TestMessage],
        ECKeyPair,
        ClusterConfig[ECPublicKey],
        Task[Unit]
    )
  ]

  def buildClusterNodes(
      keys: NonEmptyList[NodeInfo]
  )(implicit
      s: Scheduler,
      timeOut: FiniteDuration
  ): Task[Ref[Task, ClusterNodes]] = {
    val keyWithAddress = keys.toList.map(key => (key, randomAddress())).toSet

    for {
      nodes <- Ref.of[Task, ClusterNodes](Map.empty)
      _ <- Task.traverse(keyWithAddress) { case (info, address) =>
        val clusterConfig = ClusterConfig(clusterNodes =
          keyWithAddress.map(keyWithAddress =>
            (keyWithAddress._1.keyPair.pub, keyWithAddress._2)
          )
        )

        buildTestConnectionManager[Task, ECPublicKey, TestMessage](
          bindAddress = address,
          nodeKeyPair = info.keyPair,
          clusterConfig = clusterConfig
        ).allocated.flatMap { case (manager, release) =>
          nodes.update(map =>
            map + (manager.getLocalPeerInfo._1 -> (manager, info.keyPair, clusterConfig, release))
          )
        }
      }

    } yield nodes
  }

  class Cluster(nodes: Ref[Task, ClusterNodes]) {

    private def broadcastToAllConnections(
        manager: RemoteConnectionManager[Task, ECPublicKey, TestMessage],
        message: TestMessage
    ) = {
      manager.getAcquiredConnections.flatMap { connections =>
        Task
          .parTraverseUnordered(connections)(connectionKey =>
            manager.sendMessage(connectionKey, message)
          )
          .map { _ =>
            connections
          }
      }

    }

    def clusterSize: Task[Int] = nodes.get.map(_.size)

    def getEachNodeConnectionsCount: Task[List[Int]] = {
      for {
        runningNodes <- nodes.get.flatMap(nodes =>
          Task.traverse(nodes.values.map(_._1))(manager =>
            manager.getAcquiredConnections
          )
        )

      } yield runningNodes.map(_.size).toList
    }

    def waitUntilEveryNodeHaveNConnections(
        n: Int
    )(implicit timeOut: FiniteDuration): Task[List[Int]] = {
      getEachNodeConnectionsCount
        .restartUntil(counts =>
          counts.forall(currentNodeConnectionCount =>
            currentNodeConnectionCount == n
          )
        )
        .timeout(timeOut)
    }

    def closeAllNodes: Task[Unit] = {
      nodes.get.flatMap { nodes =>
        Task
          .parTraverseUnordered(nodes.values) { case (_, _, _, release) =>
            release
          }
          .void
      }
    }

    def sendMessageFromRandomNodeToAllOthers(
        message: TestMessage
    ): Task[(ECPublicKey, Set[ECPublicKey])] = {
      for {
        runningNodes <- nodes.get
        (key, (node, _, _, _)) = runningNodes.head
        nodesReceivingMessage <- broadcastToAllConnections(node, message)
      } yield (key, nodesReceivingMessage)
    }

    def sendMessageFromAllClusterNodesToTheirConnections(
        message: TestMessage
    ): Task[List[(ECPublicKey, Set[ECPublicKey])]] = {
      nodes.get.flatMap { current =>
        Task.parTraverseUnordered(current.values) { case (manager, _, _, _) =>
          broadcastToAllConnections(manager, message).map { receivers =>
            (manager.getLocalPeerInfo._1 -> receivers)
          }
        }
      }
    }

    def getMessageFromNode(key: ECPublicKey) = {
      nodes.get.flatMap { runningNodes =>
        runningNodes(key)._1.incomingMessages.take(1).toListL.map(_.head)
      }
    }

    def shutdownRandomNode: Task[
      (InetSocketAddress, ECKeyPair, ClusterConfig[ECPublicKey])
    ] = {
      for {
        current <- nodes.get
        (
          randomNodeKey,
          (randomManager, nodeKeyPair, clusterConfig, randomRelease)
        ) = current.head
        _ <- randomRelease
        _ <- nodes.update(current => current - randomNodeKey)
      } yield (randomManager.getLocalPeerInfo._2, nodeKeyPair, clusterConfig)
    }

    def startNode(
        bindAddress: InetSocketAddress,
        keyPair: ECKeyPair,
        clusterConfig: ClusterConfig[ECPublicKey]
    )(implicit s: Scheduler): Task[Unit] = {
      buildTestConnectionManager[Task, ECPublicKey, TestMessage](
        bindAddress = bindAddress,
        nodeKeyPair = keyPair,
        clusterConfig = clusterConfig
      ).allocated.flatMap { case (manager, release) =>
        nodes.update { current =>
          current + (manager.getLocalPeerInfo._1 -> (manager, keyPair, clusterConfig, release))
        }
      }
    }

  }

  object Cluster {
    def buildCluster(size: Int)(implicit
        s: Scheduler,
        timeOut: FiniteDuration
    ): Resource[Task, Cluster] = {
      val nodeInfos = NonEmptyList.fromListUnsafe(
        ((0 until size).map(_ => NodeInfo.generateRandom(secureRandom)).toList)
      )

      Resource.make {
        for {
          nodes <- buildClusterNodes(nodeInfos)
          cluster = new Cluster(nodes)
          _ <- cluster.getEachNodeConnectionsCount
            .restartUntil(counts => counts.forall(count => count == size - 1))
            .timeout(timeOut)
        } yield cluster
      } { cluster => cluster.closeAllNodes }
    }

  }

}
