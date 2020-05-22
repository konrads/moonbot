import sbt.Keys.libraryDependencies

name := "RebateCaptureBot"

version := "0.1"

scalaVersion := "2.13.2"

sbtVersion := "1.3.10"

test in assembly := {}

mainClass in Compile := Some("rcb.BotApp")

assemblyMergeStrategy in assembly := {
  case "module-info.class" => MergeStrategy.discard
  case x =>
    val oldStrategy = (assemblyMergeStrategy in assembly).value
    oldStrategy(x)
}

libraryDependencies ++= {
  val akkaVsn = "2.6.5"
  val akkaHttpVsn = "10.1.11"
  val scalaTestVsn = "3.1.1"
  Seq(
    // core
    "javax.xml.bind"    % "jaxb-api"              % "2.3.1",
    "com.typesafe.akka" %% "akka-http"            % akkaHttpVsn,
    "com.typesafe.akka" %% "akka-stream"          % akkaVsn,
    "com.typesafe.akka" %% "akka-actor-typed"     % akkaVsn,
    "com.typesafe.akka" %% "akka-slf4j"           % akkaVsn,
    "ch.qos.logback"    %  "logback-classic"      % "1.2.3",
    "com.typesafe.play" %% "play-json"            % "2.8.1",
    "org.rogach"        %% "scallop"              % "3.4.0",
    "com.typesafe.scala-logging" %% "scala-logging" % "3.9.2",
    "org.ta4j" % "ta4j-core" % "0.13",
    "com.codahale.metrics" % "metrics-graphite"   % "3.0.2", // note: not using codahale's: "com.codahale.metrics" % "metrics-core"       % "3.0.2",
    // If testkit used, explicitly declare dependency on akka-streams-testkit in same version as akka-actor
    "org.scalatest"     %% "scalatest"            % scalaTestVsn % Test,
    "com.typesafe.akka" %% "akka-http-testkit"    % akkaHttpVsn  % Test,
    "com.typesafe.akka" %% "akka-stream-testkit"  % akkaVsn      % Test
  )
}
