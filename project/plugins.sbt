// You may use this file to add plugin dependencies for sbt.
addSbtPlugin("org.spark-packages" %% "sbt-spark-package" % "0.2.3")
addSbtPlugin("com.typesafe.sbteclipse" % "sbteclipse-plugin" % "5.2.3")
addSbtPlugin("org.scoverage" % "sbt-scoverage" % "1.5.0")

resolvers ++= Seq(
	"bintray-spark-packages" at "https://dl.bintray.com/spark-packages/maven/",
	"sonatype-releases" at "https://oss.sonatype.org/content/repositories/releases/"
)