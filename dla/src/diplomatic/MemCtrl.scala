package dla.diplomatic

import chisel3._
import chisel3.util._
import freechips.rocketchip.util._

case class EyerissMemCtrlParameters(
                             addressBits: Int,
                             sizeBits: Int,
                             dataBits: Int,
                             nIds: Int // the number of source id
                             )

class EyerissMemCommonIO()(implicit val p: EyerissMemCtrlParameters) extends Bundle {
  val ctrlPath = new Bundle {
    val putEn: Bool = Input(Bool())
  }
  val dataPath = new Bundle {
    val address: UInt = Output(UInt(p.addressBits.W))
    val size: UInt = Output(UInt(p.sizeBits.W))
    val sourceAlloc: DecoupledIO[UInt] = Decoupled(UInt(log2Ceil(p.nIds).W))
    val sourceFree: DecoupledIO[UInt] = Flipped(Decoupled(UInt(log2Ceil(p.nIds).W)))
    val inActStarAdr: UInt = Input(UInt(p.addressBits.W)) // the start address of inAct
    val weightStarAdr: UInt = Input(UInt(p.addressBits.W)) // the start address of weight
    val pSumStarAdr: UInt = Input(UInt(p.addressBits.W)) // the start address of PSum
    val oneInActSRAMSize: UInt = Input(UInt(p.sizeBits.W))
    val oneWeightSize: UInt = Input(UInt(p.sizeBits.W))
    val onePSumSRAMSize: UInt = Input(UInt(p.sizeBits.W))
  }
}

class EyerissMemCtrlModule()(implicit val p: EyerissMemCtrlParameters) extends Module {
  val io: EyerissMemCommonIO = IO(new EyerissMemCommonIO()(p))
  private val idMap = Module(new EyerissIDMapGenerator(p.nIds)).io
  // TODO: add one more idMap for put
  private val inActStarAdrReg = RegInit(0.U(p.addressBits.W))
  private val weightStarAdrReg = RegInit(0.U(p.addressBits.W))
  private val pSumStarAdrReg = RegInit(0.U(p.addressBits.W))
  private val readAdrReg = RegInit(0.U(p.addressBits.W))
  private val writeAdrReg = RegInit(0.U(p.addressBits.W)) // for pSum to writeBack
  private val oneInActSRAMSizeReg = RegInit(0.U(p.sizeBits.W))
  private val oneWeightSizeReg = RegInit(0.U(p.sizeBits.W))
  private val onePSumSRAMSizeReg = RegInit(0.U(p.sizeBits.W))
  // source
  io.dataPath.sourceAlloc <> idMap.alloc
  io.dataPath.sourceFree <> idMap.free
  // address
  /** the address of read inAct */
  inActStarAdrReg := io.dataPath.inActStarAdr
  oneInActSRAMSizeReg := io.dataPath.oneInActSRAMSize.holdUnless(io.dataPath.sourceAlloc.ready)
  oneWeightSizeReg := io.dataPath.oneWeightSize.holdUnless(io.dataPath.sourceAlloc.ready)
  readAdrReg := inActStarAdrReg + (oneInActSRAMSizeReg << 3).asUInt // TODO: check and add holdUnless
  io.dataPath.address := readAdrReg
  // size
  io.dataPath.size := oneInActSRAMSizeReg // TODO: add more cases, such as weight, inAct, data and address
}

class EyerissIDMapGenerator(val numIds: Int) extends Module {
  require(numIds > 0)

  private val w = log2Up(numIds)
  val io = IO(new Bundle {
    val free: DecoupledIO[UInt] = Flipped(Decoupled(UInt(w.W)))
    val alloc: DecoupledIO[UInt] = Decoupled(UInt(w.W))
    val finish: Bool = Output(Bool())
  })

  io.free.ready := true.B

  /** [[reqBitmap]] true indicates that the id hasn't send require signal;
    * [[respBitmap]] true indicates that the id has received response;
    * both of them have [[numIds]] bits, and each bit represents one id;
    * */
  private val reqBitmap: UInt = RegInit(((BigInt(1) << numIds) - 1).U(numIds.W)) // True indicates that the id is available
  private val respBitmap: UInt = RegInit(0.U(numIds.W)) // false means haven't receive response
  /** [[select]] is the oneHot code which represents the lowest bit that equals to true;
    * Then use `OHToUInt` to get its binary value.
    * */
  private val select: UInt = (~(leftOR(reqBitmap) << 1)).asUInt & reqBitmap
  io.alloc.bits := OHToUInt(select)
  io.alloc.valid := reqBitmap.orR() // valid when there is any id hasn't sent require signal

  private val clr: UInt = WireDefault(0.U(numIds.W))
  when(io.alloc.fire()) {
    clr := UIntToOH(io.alloc.bits)
  }

  private val set: UInt = WireDefault(0.U(numIds.W))
  when(io.free.fire()) {
    set := UIntToOH(io.free.bits) // this is the sourceId that finishes
  }
  respBitmap := respBitmap | set
  reqBitmap := (reqBitmap & (~clr).asUInt)
  /** when all the sources receive response*/
  private val finishWire = respBitmap.andR()
  when (finishWire) {
    respBitmap := 0.U
    reqBitmap := ((BigInt(1) << numIds) - 1).U
  }
  io.finish := finishWire
  //assert(!io.free.valid || !(reqBitmap & (~clr).asUInt) (io.free.bits)) // No double freeing
}
