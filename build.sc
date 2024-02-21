import mill._
import mill.util._
import scalalib._

import $file.deps.spinalhdl.build
import $file.deps.`spinal-blocks`.build

object v {
  val scalaVersion = "2.13.12"
}

trait ApplyScalaVersion { this: SbtModule =>
  def crossValue = v.scalaVersion
}
trait SpinalDep { this: SbtModule =>
  def name: String
  override def millSourcePath = os.pwd / "deps" / "spinalhdl" / name
}

object spinalCore extends deps.spinalhdl.build.Core with ApplyScalaVersion with SpinalDep { def name = "core" }
object spinalLib extends deps.spinalhdl.build.Lib with ApplyScalaVersion with SpinalDep { def name = "lib" }
object spinalIdslPlugin extends deps.spinalhdl.build.IdslPlugin with ApplyScalaVersion with SpinalDep { def name = "idslplugin" }

object blocks extends deps.`spinal-blocks`.build.BlocksModule with ApplyScalaVersion {
  override def millSourcePath = os.pwd / "deps" / "spinal-blocks"
  override def spinalDeps = Agg(spinalCore, spinalLib)
  override def spinalPluginOptions = spinalIdslPlugin.pluginOptions
}

trait CommonModule extends SbtModule {
  override def scalaVersion = v.scalaVersion
  override def millSourcePath = os.pwd
  override def sources = T.sources(
    millSourcePath / "hw" / "spinal"
  )
  override def scalacOptions = super.scalacOptions() ++ spinalIdslPlugin.pluginOptions()
  override def moduleDeps = super.moduleDeps ++ Agg(blocks, spinalCore, spinalLib)
  override def ivyDeps = Agg(
    ivy"com.lihaoyi::os-lib:0.9.3",
    ivy"com.lihaoyi::mainargs:0.5.4",
  )
  def generatedSourcesPath = millSourcePath / "hw" / "gen"

  def generatorMainClass: String
  def generatedModuleName: String
  def generateVerilog = T {
    sources()
    Jvm.runSubprocess(generatorMainClass,
      runClasspath().map(_.path),
      forkArgs(),
      forkEnv(),
      workingDir = forkWorkingDir(),
      useCpPassingJar = runUseArgsFile()
    )

    Seq(s"$generatedModuleName-ips.v", s"$generatedModuleName.v").map(fn => PathRef(generatedSourcesPath / fn))
  }

  def callVivado(tcl: os.Path, args: Seq[String], cwd: os.Path): Unit = {
    os.proc("vivado",
      "-mode", "batch",
      "-source", tcl,
      "-tclargs",
      args).call(stdout = os.Inherit, stderr = os.Inherit, cwd = cwd)
  }

  def vivadoRoot: os.Path
  def vivadoProjectName: String
  def projectTcl = T.source(vivadoRoot / "create_project.tcl")
  def vivadoProject = T {
    generateVerilog()
    callVivado(projectTcl().path, Seq("--origin_dir", vivadoRoot.toString), T.dest)
    PathRef(T.dest / vivadoProjectName)
  }

  def bitstreamTcl = T.source(vivadoRoot / "create_bitstream.tcl")
  def generateBitstream = T {
    val proj = vivadoProject().path
    callVivado(bitstreamTcl().path, Seq(), proj)
    Seq("bit", "ltx").map(ext => PathRef(proj / s"$vivadoProjectName.runs" / "impl_1" / s"$vivadoProjectName.$ext"))
  }
}

object pcie extends CommonModule {
  def generatedModuleName = "PcieEngine"
  def generatorMainClass = s"pionic.pcie.GenEngineVerilog"

  def vivadoRoot = millSourcePath / "vivado" / "pcie"
  def vivadoProjectName = "pio-nic-pcie"
}

object eci extends CommonModule {
  def generatedModuleName = "EciEngine"
  def generatorMainClass = s"pionic.eci.GenEngineVerilog"

  def vivadoRoot = millSourcePath / "vivado" / "eci"
  def vivadoProjectName = "pio-nic-eci"
}

// vi: ft=scala
