package pionic

import spinal.core._

import scala.collection.mutable

class RegAllocatorFactory {
  case class RegDesc(base: BigInt, size: BigInt)

  type BlockMap = mutable.LinkedHashMap[String, RegDesc]

  case class RegBlock(base: BigInt, blockLen: BigInt) {
    val blockMap = new BlockMap

    def apply(name: String, subName: String = "") = blockMap(genKey(name, subName)).base
  }

  type GlobalMap = mutable.LinkedHashMap[String, RegBlock]

  var blocks = new GlobalMap

  def dumpAll(): Unit = {
    println("Dumping global register map:")
    blocks.foreach { case (blockName, block) =>
      println("=============")
      block.blockMap.foreach { case (name, desc) =>
        println(f"[$blockName] ${desc.base}%#x\t: $name (${desc.size} bytes)")
      }
    }
  }

  // TODO: emit JSON for driver generator
  def writeHeader(outPath: os.Path): Unit = {
    implicit class StringRich(s: String) {
      def toCMacroName: String = "[A-Z]|\\d+".r.replaceAllIn(s, { m =>
        "_" + m.group(0)
      }).toUpperCase.replace(':', '_')
    }

    val defLines = blocks.flatMap { case (blockName, block) =>
      val bname = blockName.toCMacroName
      block.blockMap.flatMap { case (name, desc) =>
        val rname = name.toCMacroName
        Seq(
          f"#define PIONIC_${bname}_$rname ${desc.base}%#x",
          f"#define PIONIC_${bname}_${rname}_SIZE ${desc.size}%#x",
        )
      }.toSeq :+ ""
    }

    os.remove(outPath)
    os.write(outPath,
      defLines.mkString(
        """|#ifndef __PIONIC_REGS_H__
           |#define __PIONIC_REGS_H__
           |
           |""".stripMargin,
        "\n",
        """|
           |#endif // __PIONIC_REGS_H__
           |""".stripMargin))
  }

  def genKey(name: String, subName: String) = {
    val delim = if (subName.nonEmpty) ":" else ""
    s"$name$delim$subName"
  }

  def apply(blockName: String, base: BigInt, blockLen: BigInt, defaultSize: BigInt) = {
    assert(!blocks.contains(blockName), s"conflict in register block name: $blockName")
    // TODO: more checks to make sure no blocks overlap

    blocks.update(blockName, RegBlock(base, blockLen))
    val block = blocks(blockName)

    new {
      private var addr = base

      def apply(name: String, size: BigInt = defaultSize, subName: String = ""): BigInt = {
        val ret = addr
        addr += size
        assert(addr <= base + blockLen, f"register alloc overflow block length [$base%#x - ${base + blockLen}%#x]")
        block.blockMap.update(s"${genKey(name, subName)}", RegDesc(ret, size))
        ret
      }
    }
  }

  def clear(): Unit = blocks.clear()
}