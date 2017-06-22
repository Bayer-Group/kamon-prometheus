addSbtPlugin("com.github.gseitz" % "sbt-release" % "1.0.3")

addSbtPlugin("io.spray" % "sbt-revolver" % "0.8.0")

addSbtPlugin("com.typesafe.sbt" % "sbt-aspectj" % "0.10.6")

addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.14.3" exclude("org.apache.maven", "maven-plugin-api"))

addSbtPlugin("se.marcuslonnberg" % "sbt-docker" % "1.4.0")

addSbtPlugin("org.scoverage" % "sbt-scoverage" % "1.5.0")

addSbtPlugin("me.lessis" % "bintray-sbt" % "0.3.0")

addSbtPlugin("com.typesafe.sbt" % "sbt-site" % "0.8.2")

addSbtPlugin("com.github.gseitz" % "sbt-protobuf" % "0.5.3")

addSbtPlugin("com.typesafe.sbt" % "sbt-ghpages" % "0.5.4")

addSbtPlugin("org.scoverage" % "sbt-coveralls" % "1.1.0")

addSbtPlugin("com.eed3si9n" % "sbt-unidoc" % "0.3.3")

dependencyOverrides ++= Set(
  "com.typesafe.sbt" % "sbt-site" % "0.8.2"
)
