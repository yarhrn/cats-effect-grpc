# fs2-grpc

[![Join the chat at https://gitter.im/fs2-grpc/Lobby](https://badges.gitter.im/fs2-grpc/Lobby.svg)](https://gitter.im/fs2-grpc/Lobby?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)
gRPC implementation for FS2/cats-effect

## SBT configuration

`project/plugins.sbt`:
```scala
addSbtPlugin("com.yarhrn.cats-effect-grpc" % "sbt-scala-gen" % "0.1")
```

`build.sbt`:
```scala
PB.targets in Compile := Seq(
  scalapb.gen() -> (sourceManaged in Compile).value,
  catsEffectGrpcCodeGenerator -> (sourceManaged in Compile).value
)

scalacOptions += "-Ypartial-unification"
```
