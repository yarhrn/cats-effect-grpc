addSbtPlugin("com.jsuereth"     % "sbt-pgp"      % "1.1.0")
addSbtPlugin("com.typesafe.sbt" % "sbt-git"      % "0.9.3")
addSbtPlugin("org.xerial.sbt"   % "sbt-sonatype" % "2.3")
addSbtPlugin("com.thesamet" % "sbt-protoc" % "0.99.18")
addSbtPlugin("com.timushev.sbt" % "sbt-updates"   % "0.3.4")
addSbtPlugin("com.geirsson"     % "sbt-scalafmt"  % "1.4.0")
addSbtPlugin("com.eed3si9n"     % "sbt-buildinfo" % "0.8.0")

libraryDependencies += "com.thesamet.scalapb" %% "compilerplugin" % "0.7.1"
