package com.yarhrn.cats_effect_grpc.runtime_support.sbt_gen

import com.google.protobuf.Descriptors.FileDescriptor
import com.google.protobuf.ExtensionRegistry
import com.google.protobuf.compiler.PluginProtos
import com.google.protobuf.compiler.PluginProtos.{CodeGeneratorRequest, CodeGeneratorResponse}
import protocbridge.{Artifact, JvmGenerator, ProtocCodeGenerator}
import sbt._
import sbt.Keys._
import scalapb.compiler.FunctionalPrinter
import scalapb.options.compiler.Scalapb

import scala.collection.JavaConverters._

object CatsEffectCodeGenerator extends ProtocCodeGenerator {

  def generateServiceFiles(file: FileDescriptor): Seq[PluginProtos.CodeGeneratorResponse.File] = {
    println("Services: " + file.getServices.asScala)
    file.getServices.asScala.map { service =>
      val p = new CatsEffectGrpcServicePrinter(service)

      import p.{FileDescriptorPimp, ServiceDescriptorPimp}
      val code = p.printService(FunctionalPrinter()).result()
      val b    = CodeGeneratorResponse.File.newBuilder()
      b.setName(file.scalaDirectory + "/" + service.objectName + "K.scala")
      b.setContent(code)
      println(b.getName)
      b.build
    }
  }

  def handleCodeGeneratorRequest(request: PluginProtos.CodeGeneratorRequest): PluginProtos.CodeGeneratorResponse = {
    val filesByName: Map[String, FileDescriptor] =
      request.getProtoFileList.asScala.foldLeft[Map[String, FileDescriptor]](Map.empty) {
        case (acc, fp) =>
          val deps = fp.getDependencyList.asScala.map(acc)
          acc + (fp.getName -> FileDescriptor.buildFrom(fp, deps.toArray))
      }

    val files = request.getFileToGenerateList.asScala.map(filesByName).flatMap(generateServiceFiles)
    PluginProtos.CodeGeneratorResponse.newBuilder.addAllFile(files.asJava).build()
  }

  override def run(req: Array[Byte]): Array[Byte] = {
    println("Running")
    val registry = ExtensionRegistry.newInstance()
    Scalapb.registerAllExtensions(registry)
    val request = CodeGeneratorRequest.parseFrom(req, registry)
    handleCodeGeneratorRequest(request).toByteArray
  }

  override def suggestedDependencies: Seq[Artifact] = Seq(
    Artifact("com.thesamet.scalapb", "scalapb-runtime", scalapb.compiler.Version.scalapbVersion, crossVersion = true)
  )
}

object Fs2GrpcPlugin extends AutoPlugin {
  object autoImport {
    val catsEffectGrpcCodeGenerator: (JvmGenerator, Seq[String]) = (JvmGenerator("cats-effect-grpc", CatsEffectCodeGenerator), Seq.empty)
  }

  override def requires = sbtprotoc.ProtocPlugin
  override def trigger  = AllRequirements

  override def projectSettings: Seq[Def.Setting[_]] = List(
    libraryDependencies ++= List(
      "io.grpc"               % "grpc-core"             % scalapb.compiler.Version.grpcJavaVersion,
      "io.grpc"               % "grpc-stub"             % scalapb.compiler.Version.grpcJavaVersion,
      "com.thesamet.scalapb"  %% "scalapb-runtime"      % scalapb.compiler.Version.scalapbVersion,
      "com.thesamet.scalapb"  %% "scalapb-runtime-grpc" % scalapb.compiler.Version.scalapbVersion
    )
  )
}
