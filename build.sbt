inThisBuild(
  List(
    organization := "com.yarhrn.cats-effect-grpc",
    version := "0.1",
    scmInfo := Some(
      ScmInfo(
        url("https://github.com/yarhrn/cats-effect-grpc"),
        "git@github.com:yarhrn/cats-effect-grpc")
    )
  ))

lazy val root = project.in(file("."))
  .enablePlugins(GitVersioning, BuildInfoPlugin)
  .settings(
    sonatypeProfileName := "com.yarhrn",
    skip in publish := true,
    pomExtra in Global := {
      <url>https://github.com/yarhrn/cats-effect-grpc</url>
        <licenses>
          <license>
            <name>MIT</name>
            <url>https://github.com/yarhrn/cats-effect-grpc/blob/master/LICENSE</url>
          </license>
        </licenses>
        <developers>
          <developer>
            <id>yarhrn</id>
            <name>Yaroslav Hryniuk</name>
            <url>http://yarhrn.com/</url>
          </developer>
        </developers>
    }
  )
  .aggregate(`sbt-scala-gen`, `runtime-support`)

lazy val `sbt-scala-gen` = project
  .settings(
    publishTo := sonatypePublishTo.value,
    sbtPlugin := true,
    crossSbtVersions := List(sbtVersion.value, "0.13.17"),
    addSbtPlugin("com.thesamet" % "sbt-protoc" % "0.99.18"),
    libraryDependencies ++= List(
      "io.grpc" % "grpc-core" % "1.11.0",
      "com.thesamet.scalapb" %% "compilerplugin" % "0.7.1"
    )
  )

lazy val `runtime-support` = project
  .settings(
    scalaVersion := "2.12.5",
    crossScalaVersions := List(scalaVersion.value, "2.11.12"),
    publishTo := sonatypePublishTo.value,
    libraryDependencies ++= List(
      "org.typelevel" %% "cats-effect" % "0.10",
      "io.grpc" % "grpc-netty-shaded" % "1.11.0" % "test",
      "org.scalatest" %% "scalatest" % "3.0.5" % "test",
      "io.grpc" % "grpc-core" % "1.11.0",
    ),
    addCompilerPlugin("org.spire-math" % "kind-projector" % "0.9.6" cross CrossVersion.binary)
  )
