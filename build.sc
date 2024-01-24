import mill._
import mill.util._
import scalalib._

import $file.deps.spinalhdl.build

object v {
  val scalaVersion = "2.13.12"
}

trait CommonModule extends SbtModule {
  override def scalaVersion = v.scalaVersion
}
trait SpinalDep { this: SbtModule =>
  def crossValue = v.scalaVersion
  def name: String
  override def millSourcePath = os.pwd / "deps" / "spinalhdl" / name
}

object spinalCore extends deps.spinalhdl.build.Core with SpinalDep { def name = "core" }
object spinalLib extends deps.spinalhdl.build.Lib with SpinalDep { def name = "lib" }
object spinalIdslPlugin extends deps.spinalhdl.build.IdslPlugin with SpinalDep { def name = "idslplugin" }

object pioNicEngineModule extends CommonModule {
  override def millSourcePath = os.pwd
  override def sources = T.sources(
    millSourcePath / "hw" / "spinal"
  )

  override def scalacOptions = super.scalacOptions() ++ spinalIdslPlugin.pluginOptions()
  override def moduleDeps = super.moduleDeps ++ Agg(spinalCore, spinalLib)
  override def ivyDeps = Agg(
    ivy"com.lihaoyi::os-lib:0.9.3",
    ivy"com.lihaoyi::mainargs:0.5.4",
  )

  def generatedSourcesPath = millSourcePath / "hw" / "gen"
  def generateVerilog = T {
    sources()
    Jvm.runSubprocess("pionic.PioNicEngineVerilog",
      runClasspath().map(_.path),
      forkArgs(),
      forkEnv(),
      workingDir = forkWorkingDir(),
      useCpPassingJar = runUseArgsFile()
    )

    Seq("Merged.v", "PioNicEngine.v").map(fn => PathRef(generatedSourcesPath / fn))
  }

  def callVivado(tcl: os.Path, args: Seq[String], cwd: os.Path): Unit = {
    os.proc("vivado",
      "-mode", "batch",
      "-source", tcl,
      "-tclargs",
      args).call(stdout = os.Inherit, stderr = os.Inherit, cwd = cwd)
  }

  def vroot = millSourcePath / "vivado"
  def projectTcl = T.source(vroot / "create_project.tcl")
  def vivadoProject = T {
    generateVerilog()
    callVivado(projectTcl().path, Seq("--origin_dir", vroot.toString), T.dest)
    PathRef(T.dest / "pio-nic")
  }

  def bitstreamTcl = T.source(vroot / "create_bitstream.tcl")
  def generateBitstream = T {
    val proj = vivadoProject().path
    callVivado(bitstreamTcl().path, Seq(), proj)
    Seq("bit", "ltx").map(ext => PathRef(proj / "pio-nic.runs" / "impl_1" / s"pio-nic.$ext"))
  }
}

// vi: ft=scala
