import mill._, util._, scalalib._
import mill.define.ModuleRef
import $file.deps.spinalhdl.build
import $file.deps.`spinal-blocks`.build

// mill-vcs-version for version CSR
import $ivy.`de.tototec::de.tobiasroeser.mill.vcs.version::0.4.0`
import de.tobiasroeser.mill.vcs.version.VcsVersion

// download static-shell release checkpoint
import $ivy.`com.lihaoyi::requests:0.8.2`

object v {
  val scalaVersion = "2.13.12"
  val eciStaticShellVersion = "v0.1.0"
}

trait MySpinal { this: deps.spinalhdl.build.SpinalModule =>
  def name: String
  def crossValue = v.scalaVersion
  override def millSourcePath = os.pwd / "deps" / "spinalhdl" / name

  override def coreMod = ModuleRef(spinalCore)
  override def libMod = ModuleRef(spinalLib)
  override def idslpluginMod = ModuleRef(spinalIdslPlugin)
}
trait MyBlocks { this: deps.`spinal-blocks`.build.BlocksBaseModule =>
  def crossValue = v.scalaVersion
  override def blocksMod = ModuleRef(blocks)
  override def spinalDeps: Agg[ScalaModule] = Agg(spinalCore, spinalLib, spinalTester)
  override def spinalPluginOptions = spinalIdslPlugin.pluginOptions
  override def millSourcePath = os.pwd / "deps" / "spinal-blocks"
}

object spinalCore extends deps.spinalhdl.build.Core with MySpinal { def name = "core" }
object spinalLib extends deps.spinalhdl.build.Lib with MySpinal { def name = "lib" }
object spinalTester extends deps.spinalhdl.build.Tester with MySpinal { def name = "tester" }
object spinalIdslPlugin extends deps.spinalhdl.build.IdslPlugin with MySpinal { def name = "idslplugin" }

object blocks extends deps.`spinal-blocks`.build.BlocksModule with MyBlocks
object blocksTester extends deps.`spinal-blocks`.build.BlocksTester with MyBlocks

trait CommonModule extends ScalaModule {
  override def scalaVersion = v.scalaVersion
  override def millSourcePath = os.pwd
  override def sources = T.sources(
    millSourcePath / "hw" / "spinal"
  )
  override def scalacOptions = super.scalacOptions() ++ spinalIdslPlugin.pluginOptions()
  override def moduleDeps = super.moduleDeps ++ Agg(blocks, spinalCore, spinalLib)

  override def ivyDeps = Agg(
    ivy"com.lihaoyi::mainargs:0.5.4",
  )
}

trait HwProjModule extends Module {
  def variant: String
  def generatedSourcesPath = millSourcePath / "hw" / "gen" / variant
  override def millSourcePath = os.pwd

  // 8 bytes
  def gitVersion = T { VcsVersion.vcsState().currentRevision.take(16) }

  def generateVerilog = T {
    gen.runForkedTask(
      mainClass = T.task { "pionic.GenEngineVerilog" },
      args = T.task {
        Args("--name", variant, "--version", gitVersion())
      }
    )()

    // use .sv suffix for IPs
    os.move.over(generatedSourcesPath / "NicEngine_ips.v", generatedSourcesPath / "NicEngine_ips.sv")

    Seq("NicEngine_ips.sv", "NicEngine.v", "NicEngine.xdc").map(fn => PathRef(generatedSourcesPath / fn))
  }

  def extraEnvironment = T { Map.empty[String, String] }

  def callVivado(tcl: os.Path, args: Seq[String], cwd: os.Path, env: Map[String, String] = null): Unit = {
    os.proc("vivado",
      "-mode", "batch",
      "-source", tcl,
      "-tclargs",
      args).call(
        stdout = os.Inherit,
        stderr = os.Inherit,
        cwd = cwd,
        env = env,
      )
  }

  def vivadoRoot = millSourcePath / "vivado" / variant
  def vivadoProjectName = s"pio-nic-$variant"
  def projectTcl = T.source(vivadoRoot / "create_project.tcl")
  def projectTclDeps = T.source(vivadoRoot / "bd" / "design_1.tcl")
  def vivadoProject = T {
    projectTclDeps()
    generateVerilog()
    callVivado(projectTcl().path, Seq("--origin_dir", vivadoRoot.toString), T.dest)
    PathRef(T.dest / vivadoProjectName)
  }

  def bitstreamTcl = T.source(vivadoRoot / "create_bitstream.tcl")

  def bitstreamEnv = T { Map.empty[String, String] }
  def generateBitstream = T {
    generateVerilog()
    callVivado(bitstreamTcl().path, Seq(), vivadoProject().path / os.up, env = bitstreamEnv())
    Seq("bit", "ltx").map(ext => PathRef(vivadoProject().path / s"$vivadoProjectName.runs" / "impl_1" / s"$vivadoProjectName.$ext"))
  }
}

object gen extends CommonModule { outer =>
  object test extends ScalaTests with TestModule.ScalaTest {
    override def moduleDeps = super.moduleDeps ++ Agg(spinalTester, blocksTester)
    override def millSourcePath = outer.millSourcePath
    override def sources = T.sources(millSourcePath / "hw" / "tests")

    override def ivyDeps = Agg(
      ivy"com.lihaoyi::os-lib:0.9.3",
      ivy"commons-io:commons-io:2.15.1",
      ivy"org.pcap4j:pcap4j-core:1.8.2",
      ivy"org.pcap4j:pcap4j-packetfactory-static:1.8.2",
    )
  }
}

object pcie extends HwProjModule { def variant = "pcie" }
object eci extends HwProjModule {
  def variant = "eci"
  override def bitstreamEnv = T {
    println(s"Downloading static shell ${v.eciStaticShellVersion} checkpoint...")
    val staticShellCkptUrl = s"https://gitlab.ethz.ch/api/v4/projects/48046/packages/generic/release/${v.eciStaticShellVersion}/build_static_shell_routed.dcp"
    os.write(T.dest / "static_shell_routed.dcp", requests.get.stream(staticShellCkptUrl))
    Map("ENZIAN_SHELL_DIR" -> T.dest.toString)
  }
}

// vi: ft=scala
