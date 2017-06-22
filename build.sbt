import java.util.Date
import sbtprotobuf.ProtobufPlugin

val akkaVersion = "2.4.16"
val akkaHttpVersion = "10.0.1"
val sprayVersion = "1.3.4"
val kamonVersion = "0.6.7"

lazy val commonSettings = Seq(
  homepage := Some(url("https://monsantoco.github.io/kamon-prometheus")),
  organization := "com.monsanto.arch",
  organizationHomepage := Some(url("http://engineering.monsanto.org")),
  licenses := Seq("BSD New" → url("http://opensource.org/licenses/BSD-3-Clause")),
  scalaVersion := "2.11.8",
  crossScalaVersions := Seq("2.11.8", "2.12.2"),
  scalacOptions ++= Seq(
    "-deprecation",
    "-unchecked",
    "-encoding", "UTF-8"
  ),
  resolvers += Resolver.jcenterRepo,
  apiMappingsScala ++= Map(
    ("com.typesafe.akka", "akka-actor") → "http://doc.akka.io/api/akka/%s",
    ("com.typesafe.akka", "akka-http-experimental") → "http://doc.akka.io/api/akka/%s",
    ("io.spray", "spray-routing") → "http://spray.io/documentation/1.1-SNAPSHOT/api/"
  ),
  apiMappingsJava ++= Map(
    ("com.typesafe", "config") → "http://typesafehub.github.io/config/latest/api"
  )
)

val bintrayPublishing = Seq(
  bintrayOrganization := Some("monsanto"),
  bintrayPackageLabels := Seq("kamon", "prometheus", "metrics"),
  bintrayVcsUrl := Some("https://github.com/MonsantoCo/kamon-prometheus"),
  publishTo := {
    if (isSnapshot.value) Some("OJO Snapshots" at s"https://oss.jfrog.org/artifactory/oss-snapshot-local;build.timestamp=${new Date().getTime}")
    else publishTo.value
  },
  credentials ++= {
    List(bintrayCredentialsFile.value)
      .filter(_.exists())
      .map(f ⇒ Credentials.toDirect(Credentials(f)))
      .map(c ⇒ Credentials("Artifactory Realm", "oss.jfrog.org", c.userName, c.passwd))
  },
  bintrayReleaseOnPublish := {
    if (isSnapshot.value) false
    else bintrayReleaseOnPublish.value
  }
)

val noPublishing = Seq(
  publish := {},
  publishLocal := {},
  publishArtifact := false
)

lazy val BufferedConfigTest = config("buffered-config-test").extend(Test)
lazy val InvalidConfigTest = config("invalid-config-test").extend(Test)
val testConfigs = "test, buffered-config-test, invalid-config-test"

lazy val library = (project in file("library"))
  .configs(BufferedConfigTest, InvalidConfigTest)
  .disablePlugins(sbtassembly.AssemblyPlugin)
  .settings(
    commonSettings,
    bintrayPublishing,
    ProtobufPlugin.protobufSettings,
    inConfig(BufferedConfigTest)(Defaults.testSettings),
    inConfig(InvalidConfigTest)(Defaults.testSettings),
    name := "kamon-prometheus",
    description := "Kamon module to export metrics to Prometheus",
    libraryDependencies ++= Seq(
      "io.kamon"               %% "kamon-core"               % kamonVersion,
      "com.typesafe.akka"      %% "akka-actor"               % akkaVersion,
      "com.typesafe.akka"      %% "akka-http"                % akkaHttpVersion  % "provided",
      "com.typesafe"            % "config"                   % "1.3.1",
      "org.scala-lang.modules" %% "scala-parser-combinators" % "1.0.5"          % "provided",
      // -- testing --
      "ch.qos.logback"     % "logback-classic"   % "1.1.7"          % testConfigs,
      "com.typesafe.akka" %% "akka-http-testkit" % akkaHttpVersion  % "test",
      "com.typesafe.akka" %% "akka-slf4j"        % akkaVersion      % testConfigs,
      "com.typesafe.akka" %% "akka-testkit"      % akkaVersion      % "test",
      "org.scalatest"     %% "scalatest"         % "3.0.1"          % testConfigs,
      "io.kamon"          %% "kamon-akka-2.4"    % kamonVersion     % "test",
      "org.scalacheck"    %% "scalacheck"        % "1.13.4"         % "test"
    ),
    libraryDependencies ++= {
      if (scalaBinaryVersion.value == "2.11") Seq(
        "io.spray" %% "spray-routing" % sprayVersion % "provided",
        "io.spray" %% "spray-testkit" % sprayVersion % "test"
      ) else Seq.empty
    },
    dependencyOverrides ++= Set(
      "com.typesafe.akka"      %% "akka-actor"    % akkaVersion,
      "org.scala-lang"          % "scala-library" % scalaVersion.value,
      "org.scala-lang"          % "scala-reflect" % scalaVersion.value,
      "org.scala-lang.modules" %% "scala-xml"     % "1.0.6"
    ),
    version in ProtobufPlugin.protobufConfig := "2.6.1",

    // We have to ensure that Kamon starts/stops serially
    parallelExecution in Test := false,
    // Don't count Protobuf-generated code in coverage
    coverageExcludedPackages := "com\\.monsanto\\.arch\\.kamon\\.prometheus\\.metric\\..*"
  )

lazy val demo = (project in file("demo"))
  .dependsOn(library)
  .enablePlugins(DockerPlugin)
  .settings(
    commonSettings,
    aspectjSettings,
    noPublishing,
    name := "kamon-prometheus-demo",
    description := "Docker image containing a demonstration of kamon-prometheus in action.",
    libraryDependencies += "io.kamon" %% "kamon-system-metrics" % kamonVersion,
    libraryDependencies ++= {
      if (scalaBinaryVersion.value == "2.11") Seq(
        "io.kamon" %% "kamon-spray" % kamonVersion,
        "io.spray" %% "spray-can" % sprayVersion,
        "com.monsanto.arch" %% "spray-kamon-metrics" % "0.1.3"
      ) else Seq.empty
    },
    fork in run := true,
    javaOptions in run ++= { (AspectjKeys.weaverOptions in Aspectj).value },
    javaOptions in reStart ++= { (AspectjKeys.weaverOptions in Aspectj).value },
    assemblyJarName in assembly := s"${name.value}-${version.value}.jar",
    docker := (docker.dependsOn(assembly)).value,
    imageNames in docker := Seq(
      ImageName(
        namespace = Some("monsantoco"),
        repository = "kamon-prometheus-demo",
        tag = Some("latest")
      )
    ),
    dockerfile in docker := {
      import sbtdocker.Instructions._

      val prometheusVersion = "0.15.1"
      val grafanaVersion = "2.1.3"

      val dockerSources = (sourceDirectory in Compile).value / "docker"
      val supervisordConf = dockerSources / "supervisord.conf"
      val prometheusYaml = dockerSources / "prometheus.yml"
      val grafanaRules = dockerSources / "grafana.rules"
      val grafanaIni = dockerSources / "grafana.ini"
      val grafanaDb = dockerSources / "grafana.db"
      val demoAssembly = (assemblyOutputPath in assembly).value
      val weaverAgent = (AspectjKeys.weaver in Aspectj).value.get
      val grafanaPluginsHash = "27f1398b497650f5b10b983ab9507665095a71b3"

      val instructions = Seq(
        From("java:8-jre"),
        WorkDir("/tmp"),
        Raw("RUN", Seq(
          // install supervisor
          "apt-get update && apt-get -y install supervisor",
          // install Prometheus
          s"curl -L https://github.com/prometheus/prometheus/releases/download/$prometheusVersion/prometheus-$prometheusVersion.linux-amd64.tar.gz | tar xz",
          "mv prometheus /usr/bin",
          "mkdir -p /etc/prometheus",
          "mv ./consoles ./console_libraries /etc/prometheus",
          "mkdir -p /var/lib/prometheus",
          // install Grafana
          "apt-get install -y adduser libfontconfig",
          s"curl -L -o grafana.deb https://grafanarel.s3.amazonaws.com/builds/grafana_${grafanaVersion}_amd64.deb",
          "dpkg -i grafana.deb",
          s"curl -L https://github.com/grafana/grafana-plugins/archive/$grafanaPluginsHash.tar.gz | tar xz",
          s"mv grafana-plugins-$grafanaPluginsHash/datasources/prometheus /usr/share/grafana/public/app/plugins/datasource",
          // clean up
          "rm -rf /tmp/* /var/lib/apt/lists/*"
        ).mkString(" && ")),
        // configure and use supervisor
        Copy(CopyFile(supervisordConf), "/etc/supervisor/conf.d/prometheus-demo.conf"),
        EntryPoint.exec(Seq("/usr/bin/supervisord", "-c", "/etc/supervisor/supervisord.conf")),
        // install the demo application
        Copy(CopyFile(demoAssembly), "/usr/share/kamon-prometheus-demo/demo.jar"),
        Copy(CopyFile(weaverAgent), "/usr/share/kamon-prometheus-demo/weaverAgent.jar"),
        // configure Prometheus
        Copy(Seq(CopyFile(prometheusYaml), CopyFile(grafanaRules)), "/etc/prometheus/"),
        // configure Grafana
        Copy(CopyFile(grafanaIni), "/etc/grafana/grafana.ini"),
        Copy(CopyFile(grafanaDb), "/var/lib/grafana/grafana.db"),
        // expose ports
        Expose(Seq(80, 3000, 9090))
      )
      sbtdocker.immutable.Dockerfile(instructions)
    },
    // Don't count demo code in coverage
    coverageExcludedPackages := "com\\.monsanto\\.arch\\.kamon\\.prometheus\\.demo\\..*"
  )

lazy val ghPagesSettings =
  ghpages.settings ++
  Seq(
    git.remoteRepo := "git@github.com:MonsantoCo/kamon-prometheus.git"
  )

lazy val `kamon-prometheus` = (project in file("."))
  .disablePlugins(sbtassembly.AssemblyPlugin)
  .aggregate(library, demo)
  .settings(
    commonSettings,
    noPublishing,
    ghPagesSettings,
    unidocSettings,
    site.settings,
    site.asciidoctorSupport(),
    site.includeScaladoc("api/snapshot"),
    UnidocKeys.unidocProjectFilter in (ScalaUnidoc, UnidocKeys.unidoc) := inAnyProject -- inProjects(demo),
    site.addMappingsToSiteDir(mappings in (ScalaUnidoc, packageDoc), "api/snapshot")
  )
