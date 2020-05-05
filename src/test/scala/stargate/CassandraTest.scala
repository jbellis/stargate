package stargate
import java.util.UUID
import java.util.concurrent.TimeUnit

import com.datastax.oss.driver.api.core.CqlSession
import com.typesafe.scalalogging.Logger

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.{Random, Try}

trait CassandraTest {
  val logger = Logger(classOf[CassandraTest])

  // hard-coding this for now
  val dataCenter = "datacenter1"
  var contacts = List(("localhost", 9042))
  val dockerId = "test-dse-" + UUID.randomUUID()
  val _sessions = new java.util.concurrent.ConcurrentHashMap[UUID, (CqlSession, String)]()
  var alreadyRunning: Boolean = true

  def ensureCassandraRunning(): Unit = {
    val local = Try(cassandra.session(contacts, dataCenter))
    val persistentDseContainer = "test-cassandra"
    lazy val localDocker = Try(Integer.parseInt(util.process.exec(List("docker", "port", persistentDseContainer, "9042")).get.split(":")(1).trim))
    if(local.isSuccess) {
      local.get.close
      logger.info("using already running cassandra instance for test")
      alreadyRunning = true
    } else if(localDocker.isSuccess) {
      logger.info("using already running docker-cassandra instance for test: {}", localDocker.get)
      contacts = List(("localhost", localDocker.get))
      alreadyRunning = true
    } else {
      val image = "cassandra:3.11.6"
      val start = util.process.exec(List("docker", "run", "--name", dockerId, "-d", "-P", image)).get
      logger.warn(s"""starting cassandra docker container for test: ${dockerId})
        |    this can take nearly a minute to start, so it's generally faster to: 1) run cassandra in the background on port 9042, or
        |    2) run docker with name ${persistentDseContainer} with the following command:
        |    docker run -d -P --name ${persistentDseContainer} ${image}""".stripMargin)
      val port =  Integer.parseInt(util.process.exec(List("docker", "port", dockerId, "9042")).get.split(":")(1).trim)
      contacts = List(("localhost", port))
      util.retry(cassandra.session(contacts, dataCenter), Duration.apply(60, TimeUnit.SECONDS), Duration.apply(5, TimeUnit.SECONDS)).get
      alreadyRunning = false
    }
  }

  def cleanup(): Unit = {
    _sessions.values.asScala.foreach(cleanupSession)
    if(!alreadyRunning) {
      val stop = util.process.exec(List("docker", "kill", dockerId)).get
      val rm = util.process.exec(List("docker", "rm", dockerId)).get
    }
  }

  def newSession: (CqlSession, String) = {
    val keyspace = ("keyspace" + Random.alphanumeric.take(10).mkString).toLowerCase
    logger.info("new cql session for keyspace: {}", keyspace)
    val session = cassandra.session(contacts, dataCenter)
    cassandra.createKeyspace(session, keyspace, 1)
    _sessions.put(UUID.randomUUID, (session, keyspace))
    (session, keyspace)
  }

  def cleanupSession(session_keyspace: (CqlSession, String)) = {
    val (session, keyspace) = session_keyspace
    logger.info(s"cleaning up keyspace ${keyspace} and closing session: ${session.toString}")
    try {
      cassandra.wipeKeyspace(session, keyspace)
    } finally {
      session.close
    }
    ()
  }

}

