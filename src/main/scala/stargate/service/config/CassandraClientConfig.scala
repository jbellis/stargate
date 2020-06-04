package stargate.service.config

import scala.beans.BeanProperty
import CassandraClientConfig._
import com.typesafe.config.Config
/**
  * properties needed to create a cassandra client
  *
  * @param cassandraContactPoints contact points used for stargate to connect to cassandra, usually 2 or 3 nodes will suffice
  * @param cassandraDataCenter datacenter to connect to. Note if a listed contact point is not in this datacenter the connection will not work
  * @param cassandraReplication number of copies of data stored (ie RF 1 means there is only a single copy of all data and losing it means losing that data permanently)
  * @param cassandraAuthProvider custom auth provider to use for Apache Cassandra. See https://docs.datastax.com/en/developer/java-driver/4.6/manual/core/authentication/ for more details
  * @param cassandraUserName when using the PlainTextProvider the user name to use to connect to Apache Cassandra
  * @param cassandraPassword when using the PlainTextProvider the password to use to connect to Apache Cassandra
  */
case class CassandraClientConfig(
  @BeanProperty val cassandraContactPoints: List[(String, Int)],
  @BeanProperty val cassandraDataCenter: String = DEFAULT_DATACENTER,
  @BeanProperty val cassandraReplication: Int = DEFAULT_REPLICATION,
  @BeanProperty val cassandraAuthProvider: String = DEFAULT_AUTH_PROVIDER,
  @BeanProperty val cassandraUserName: String = DEFAULT_USERNAME,
  @BeanProperty val cassandraPassword: String = DEFAULT_PASSWORD)


object CassandraClientConfig {

  val DEFAULT_DATACENTER = "datacenter1"
  val DEFAULT_REPLICATION = 1
  val DEFAULT_AUTH_PROVIDER = ""
  val DEFAULT_USERNAME = "cassandra"
  val DEFAULT_PASSWORD = "cassandra"

  def parse(config: Config): CassandraClientConfig = {
    CassandraClientConfig(
      config.getString("contactPoints").split(",").map(_.split(":")).map(hp => (hp(0), Integer.parseInt(hp(1)))).toList,
      config.getString("dataCenter"),
      config.getInt("replication"),
      config.getString("username"),
      config.getString("password"),
      config.getString("authProvider"))
  }
}