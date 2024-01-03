import mill._
import mill.util._
import scalalib._

val spinalVersion = "1.9.0"

object pioNicEngineModule extends SbtModule {
  def scalaVersion = "2.12.16"
  override def millSourcePath = os.pwd
  override def sources = T.sources(
    millSourcePath / "hw" / "spinal"
  )

  override def ivyDeps = Agg(
    ivy"com.github.spinalhdl::spinalhdl-core:$spinalVersion",
    ivy"com.github.spinalhdl::spinalhdl-lib:$spinalVersion"
  )

  override def scalacPluginIvyDeps = Agg(
    ivy"com.github.spinalhdl::spinalhdl-idsl-plugin:$spinalVersion"
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
