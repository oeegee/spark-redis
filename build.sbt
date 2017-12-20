// Your sbt build file. Guides on how to write one can be found at
// http://www.scala-sbt.org/0.13/docs/index.html

scalaVersion := "2.11.11"
crossScalaVersions := Seq("2.10.7", "2.11.11", "2.12.4")

sparkVersion := "2.2.0"

spName := "RedisLabs/spark-redis"

description := "A library for reading and writing data from and to Redis with Apache Spark, for Spark SQL and DataFrames."

// Don't forget to set the version
version := "0.3.3"

homepage := Some(url("https://github.com/RedisLabs/spark-redis"))

// All Spark Packages need a license
licenses := Seq("BSD 3-Clause" -> url("http://opensource.org/licenses/BSD-3-Clause"))

organization := "com.redislabs"

organizationName := "Redis Labs, Inc."

organizationHomepage := Some(url("https://redislabs.com"))

// Add Spark components this package depends on, e.g, "mllib", ....
sparkComponents ++= Seq("sql", "streaming")

libraryDependencies ++= Seq(
  "redis.clients" % "jedis" % "2.9.0",
  "junit" % "junit" % "4.12",  
  "org.scalatest" %% "scalatest" % "2.2.6" % "test"
  )  

// uncomment and change the value below to change the directory where your zip artifact will be created
// spDistDirectory := target.value

// add any Spark Package dependencies using spDependencies.
// e.g. spDependencies += "databricks/spark-avro:0.1"

credentials += Credentials(Path.userHome / ".ivy2" / ".sbtcredentials") // A file containing credentials

// Can't parallelly execute in test
parallelExecution := true
parallelExecution in Test := false

concurrentRestrictions in Global := Seq(
  Tags.limit(Tags.CPU, 4),
  Tags.limit(Tags.Network, 20),
  Tags.limit(Tags.Test, 1),
  Tags.limitAll( 15 )
)
