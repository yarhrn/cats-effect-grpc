# cats-effect-grpc 0.1

gRPC implementation for cats-effect

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
