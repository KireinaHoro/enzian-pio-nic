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
        println(f"[$blockName] ${desc.base}%#x\t: $name")
      }
    }
  }

  // TODO: emit JSON for driver generator

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